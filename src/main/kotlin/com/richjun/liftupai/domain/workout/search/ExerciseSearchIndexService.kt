package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon
import com.richjun.liftupai.domain.workout.search.ops.SearchSynonymRepository
import com.richjun.liftupai.domain.workout.search.ops.SynonymLines
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectorStore
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import com.richjun.liftupai.domain.workout.search.semantic.QueryVectorSource
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 운동 검색 인덱스(ExerciseSearchEngine)를 DB 에서 만들고 캐시한다.
 *
 * - 앱 기동 직후(ApplicationReadyEvent) 미리 만들고 대표 질의로 데워 첫 요청이 느리지 않게 한다.
 * - 카탈로그 변경은 커밋된 뒤(AFTER_COMMIT)에만 반영한다. 세대 번호(generation)를 올려 두고, 빌드가 도는 사이에
 *   이벤트가 오면 그 빌드 결과는 캐시하지 않는다 — 커밋 전 데이터로 만든 스냅샷이 TTL 동안 남지 않는다.
 * - 재빌드가 실패하면 이전 인덱스를 계속 쓰고 WARN 만 남긴다(DB 순단으로 검색이 500 이 되지 않게). 이전 것이 없을 때만 예외.
 * - TTL 이 지나면 요청 스레드가 막히지 않게 백그라운드에서 갱신하고 그동안은 이전 인덱스를 쓴다.
 * - 질의는 길이·단어 수를 제한하고 중복 단어를 빼서(엔진 비용 폭주 방지) 넘기고, 같은 (질의·필터·로케일) 결과는 작은 LRU 로 재사용한다(페이지 넘김).
 * - 런타임 동의어(search_synonyms)를 짧은 TTL 로 읽어 [activeSynonymLines] 로 제공한다. 줄이 바뀌면 인덱스를 다시 만든다.
 */
@Service
class ExerciseSearchIndexService(
    private val exerciseRepository: ExerciseRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository,
    private val synonymRepository: SearchSynonymRepository? = null,
    /** 동작 설명문(instructions·tips) 투영 — 없거나 읽기에 실패하면 설명문 검색 계층만 꺼지고 이름 검색은 그대로다 */
    private val descriptionRepository: ExerciseDescriptionRepository? = null,
    /** 질의 임베딩 — 없거나 꺼져 있거나 실패하면 어휘 검색만 쓴다(의미 검색은 어휘 결과를 보강할 뿐 대체하지 않는다) */
    private val queryVectors: QueryVectorSource? = null,
    /** 운동 문서 임베딩(리소스) */
    private val vectorStore: ExerciseVectorStore? = null
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private class Snapshot(
        val engine: ExerciseSearchEngine,
        val builtAtMillis: Long,
        val generation: Long,
        val synonymVersion: Int,
        /** 의미 검색을 섞을 수 있으면(설정 켜짐 + 문서 임베딩 있음) 하이브리드, 아니면 null(어휘 전용) */
        val hybrid: HybridSearch? = null
    ) {
        /** 이 스냅샷 전용 결과 캐시 — 스냅샷이 바뀌면 함께 사라진다 */
        val results = ResultCache(RESULT_CACHE_SIZE)
    }

    private class SynonymState(val lines: List<String>, val loadedAtMillis: Long) {
        val version: Int = lines.hashCode()
    }

    private data class ResultKey(
        val query: String,
        val category: ExerciseCategory?,
        val equipments: Set<Equipment>?,
        val locale: String?
    )

    /** 캐시된 결과 id 목록. [degradedAtMillis] 가 있으면 의미 검색이 필요했는데 질의 벡터를 못 구해 어휘 결과만 담은 것 — 잠깐만 쓴다. */
    private class CachedIds(val ids: List<Long>, val degradedAtMillis: Long? = null)

    private class ResultCache(private val max: Int) {
        private val map = object : LinkedHashMap<ResultKey, CachedIds>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ResultKey, CachedIds>?) = size > max
        }

        @Synchronized
        fun get(key: ResultKey): CachedIds? = map[key]

        @Synchronized
        fun put(key: ResultKey, value: CachedIds) {
            map[key] = value
        }

        @Synchronized
        fun size(): Int = map.size
    }

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var synonyms: SynonymState? = null

    private val generation = AtomicLong()
    private val lock = Any()
    private val refreshing = AtomicBoolean(false)

    /** 재빌드 실패 후 이 시각 전에는 다시 시도하지 않는다 (DB 장애 중 요청마다 빌드를 두드리지 않게) */
    @Volatile
    private var retryNotBeforeMillis = 0L

    /** 현재 스냅샷의 결과 캐시 항목 수 (테스트·운영 점검용) */
    internal fun cachedResultCount(): Int = snapshot?.results?.size() ?: 0

    /** 빌드 횟수 (테스트·운영 점검용) */
    internal val buildCount = AtomicInteger()

    internal var ttlMillis: Long = TTL_MILLIS
    internal var synonymTtlMillis: Long = SYNONYM_TTL_MILLIS
    internal var failureBackoffMillis: Long = FAILURE_BACKOFF_MILLIS
    internal var clock: () -> Long = System::currentTimeMillis

    /** 엔진의 질의당 시간 예산(테스트에서 줄여 "예산 초과 결과는 캐시하지 않음"을 확인한다) */
    internal var engineBudgetNanos: Long = ExerciseSearchEngine.DEFAULT_BUDGET_NANOS

    private val refresher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "exercise-search-index-refresh").apply { isDaemon = true } }
    }

    // ── 캐시 수명 ────────────────────────────────────────────────────────────────────────────────

    fun engine(): ExerciseSearchEngine = current().engine

    private fun current(): Snapshot {
        val cur = snapshot
        if (cur != null && cur.generation == generation.get() && cur.synonymVersion == (synonyms?.version ?: cur.synonymVersion)) {
            val now = clock()
            val synonymsStale = synonymRepository != null && (synonyms?.let { now - it.loadedAtMillis >= synonymTtlMillis } ?: true)
            if (now - cur.builtAtMillis >= ttlMillis || synonymsStale) refreshInBackground()
            return cur
        }
        return rebuildBlocking()
    }

    /** 커밋된 카탈로그 변경 — 다음 요청이 새 인덱스를 만들게 한다. 이전 인덱스는 재빌드가 실패할 때를 위해 남겨 둔다. */
    @TransactionalEventListener(classes = [ExerciseCatalogChangedEvent::class], phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** 앱이 요청을 받기 시작하면 인덱스를 만들고 대표 질의로 JIT 를 데운다 */
    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        try {
            val engine = engine()
            WARM_QUERIES.forEach { q -> engine.search(q, null, null, "ko") }
        } catch (e: Exception) {
            logger.warn("Exercise search index warm-up failed (will build on first request): {}", e.message)
        }
    }

    private fun refreshInBackground() {
        // 직전 재빌드가 실패했다면 백오프 동안은 DB 를 다시 두드리지 않는다
        if (clock() < retryNotBeforeMillis) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            refresher.execute {
                try {
                    synchronized(lock) {
                        val cur = snapshot
                        val before = synonyms?.version
                        val state = loadSynonyms(force = true)
                        val expired = cur == null || clock() - cur.builtAtMillis >= ttlMillis
                        // 동의어만 만료된 경우에는 줄이 실제로 바뀌었을 때만 인덱스를 다시 만든다
                        if (expired || state.version != before || cur!!.generation != generation.get()) rebuildLocked(forceSynonymReload = false)
                    }
                } catch (e: Exception) {
                    logger.warn("Exercise search index background refresh failed, keeping previous index: {}", e.message)
                } finally {
                    refreshing.set(false)
                }
            }
        } catch (e: Exception) {
            refreshing.set(false)
        }
    }

    private fun rebuildBlocking(): Snapshot {
        val prev = snapshot
        if (prev != null && clock() < retryNotBeforeMillis) return prev
        synchronized(lock) {
            // 기다리는 동안 다른 스레드가 이미 만들었으면 그것을 쓴다
            val again = snapshot
            if (again != null && again.generation == generation.get() && again.synonymVersion == (synonyms?.version ?: again.synonymVersion)) return again
            return rebuildLocked(forceSynonymReload = false)
        }
    }

    /** lock 보유 상태에서 호출. 실패 시 이전 스냅샷(있으면)을 돌려준다. */
    private fun rebuildLocked(forceSynonymReload: Boolean): Snapshot {
        var attempt = 0
        while (true) {
            attempt++
            val gen = generation.get()
            val built: Snapshot
            try {
                val lines = loadSynonyms(force = forceSynonymReload || synonyms == null).lines
                val engine = build(lines)
                built = Snapshot(engine, clock(), gen, synonyms?.version ?: 0, hybridFor(engine))
            } catch (e: Exception) {
                val prev = snapshot
                if (prev == null) throw e
                retryNotBeforeMillis = clock() + failureBackoffMillis
                logger.warn("Exercise search index rebuild failed, keeping previous index: {}", e.message)
                return prev
            }
            retryNotBeforeMillis = 0L
            // 빌드 도중 카탈로그 변경이 커밋되었다면 이 결과는 낡았을 수 있다 — 캐시하지 않고 다시 만든다(마지막 시도는 캐시 없이 반환)
            if (gen == generation.get()) {
                snapshot = built
                return built
            }
            if (attempt >= MAX_REBUILD_ATTEMPTS) return built
        }
    }

    // ── 질의 ─────────────────────────────────────────────────────────────────────────────────────

    /** 질의 검색(질의가 비면 브라우즈) — 결과는 점수 순 운동 id 목록 */
    fun searchIds(
        query: String?,
        category: ExerciseCategory?,
        equipments: Set<Equipment>?,
        locale: String?
    ): List<Long> {
        val snap = current()
        val q = normalizeQuery(query)
        val key = ResultKey(q.orEmpty(), category, equipments?.toSet(), locale?.lowercase())
        snap.results.get(key)?.let { cached ->
            // 의미 검색 없이 낸(벡터를 못 구한) 결과는 잠깐만 재사용하고, 그 뒤에는 다시 시도한다 — 순간 장애가 TTL 동안 품질을 깎지 않게
            val degradedAt = cached.degradedAtMillis
            if (degradedAt == null || clock() - degradedAt < DEGRADED_RESULT_TTL_MILLIS) return cached.ids
        }
        val hybrid = snap.hybrid
        if (hybrid != null && q != null) {
            // 질의 임베딩이 어떤 이유로든 예외를 던져도 검색은 어휘 결과로 계속된다
            val result = hybrid.search(q, category, equipments, locale) { runCatching { queryVectors?.embed(q) }.getOrNull() }
            val ids = result.hits.map { it.doc.id }
            if (result.complete) snap.results.put(key, CachedIds(ids, if (result.degraded) clock() else null))
            return ids
        }
        val outcome = snap.engine.searchOutcome(q, category, equipments, locale)
        val ids = outcome.hits.map { it.doc.id }
        // 시간 예산을 넘겨 일부만 훑은 결과는 캐시하지 않는다 — 한 번 느렸다고 TTL 동안 잘린 결과가 남지 않게
        if (outcome.complete) snap.results.put(key, CachedIds(ids))
        return ids
    }

    /** 노출 가능한 운동(ESSENTIAL + STANDARD)만, 브라우즈 순서(티어 → 인기도 → 이름)로 */
    fun listableIds(category: ExerciseCategory?, locale: String?): List<Long> =
        engine().browse(category, null, locale)
            .filter { it.doc.tier == RecommendationTier.ESSENTIAL || it.doc.tier == RecommendationTier.STANDARD }
            .map { it.doc.id }

    /**
     * 0건일 때 보여줄 대안 — 같은 질의를 필터 없이 / 카테고리 없이 / 단어별로 완화해서 가장 먼저 걸리는 것의 대표 운동(최대 limit).
     * 단어별 완화는 합산 점수로 가장 많이 걸린 카테고리의 운동을 티어 → 점수 순으로 고른다. 아무것도 못 찾으면 빈 목록(억지 인기 운동은 주지 않는다).
     */
    fun fallbackIds(
        query: String?,
        category: ExerciseCategory?,
        equipments: Set<Equipment>?,
        locale: String?,
        limit: Int = FALLBACK_LIMIT
    ): List<Long> {
        val q = normalizeQuery(query) ?: return emptyList()
        val engine = engine()
        // 질의 전체의 부정·부상 맥락("점프 빼고", "무릎이 안 좋은데")을 대안에도 적용한다 — 단어별로 쪼개 찾으면 맥락이 사라져 금지된 운동이 되살아난다
        val excluded = engine.exclusionPredicate(q)
        fun allowed(hits: List<ExerciseSearchHit>): List<ExerciseSearchHit> =
            if (excluded == null) hits else hits.filter { !excluded(it.doc) }
        fun representative(found: List<ExerciseSearchHit>): List<Long> {
            val hits = allowed(found)
            return hits.filter { it.doc.tier == RecommendationTier.ESSENTIAL || it.doc.tier == RecommendationTier.STANDARD }
                .ifEmpty { hits }.map { it.doc.id }.distinct().take(limit)
        }

        if (equipments != null) {
            representative(engine.search(q, category, null, locale)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (category != null) {
            representative(engine.search(q, null, null, locale)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val tokens = SearchText.words(q).filter { it.length >= 2 }.distinct().take(MAX_FALLBACK_TOKENS)
        if (tokens.size < 2) return emptyList() // 한 단어는 위에서 이미 같은 질의로 시도했다
        val scores = HashMap<Long, Double>()
        val docs = HashMap<Long, ExerciseSearchDoc>()
        tokens.forEach { token ->
            allowed(engine.search(token, category, null, locale)).take(FALLBACK_PER_TOKEN).forEach { hit ->
                scores.merge(hit.doc.id, hit.score, Double::plus)
                docs[hit.doc.id] = hit.doc
            }
        }
        if (scores.isEmpty()) return emptyList()
        val ranked = scores.entries.sortedByDescending { it.value }.map { docs.getValue(it.key) }
        val dominant = ranked.take(5).groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key
        return ranked.filter { dominant == null || it.category == dominant }
            .sortedWith(compareBy<ExerciseSearchDoc> { tierRank(it.tier) }.thenByDescending { scores.getValue(it.id) })
            .map { it.id }.take(limit)
    }

    private fun tierRank(t: RecommendationTier) = when (t) {
        RecommendationTier.ESSENTIAL -> 0
        RecommendationTier.STANDARD -> 1
        else -> 2
    }

    // ── 런타임 동의어 ─────────────────────────────────────────────────────────────────────────────

    /**
     * 현재 켜져 있는 동의어 줄(엔진 사전과 같은 형식). 짧은 TTL 로 DB 를 다시 읽는다.
     * 형식이 틀린 줄은 건너뛰고 WARN. DB 읽기 실패 시 이전 목록(없으면 빈 목록)을 유지한다.
     * 이 목록은 ExerciseSearchEngine(docs, extraSynonyms) 생성자로 넘어간다.
     */
    fun activeSynonymLines(): List<String> = loadSynonyms(force = false).lines

    /** TTL 을 기다리지 않고 동의어를 다시 읽고(줄이 바뀌었으면) 인덱스를 새로 만든다. 재배포 없이 반영하는 경로. */
    fun reloadSynonyms(): List<String> {
        val before = synonyms?.version
        val state = loadSynonyms(force = true)
        if (before != state.version) synchronized(lock) { rebuildLocked(forceSynonymReload = false) }
        return state.lines
    }

    private fun loadSynonyms(force: Boolean): SynonymState {
        val prev = synonyms
        val now = clock()
        if (!force && prev != null && now - prev.loadedAtMillis < synonymTtlMillis) return prev
        val repo = synonymRepository ?: return SynonymState(emptyList(), now).also { synonyms = it }
        return try {
            val lines = repo.findByEnabledTrueOrderByIdAsc().mapNotNull { row ->
                SynonymLines.validate(row.line).also { if (it == null) logger.warn("Ignoring invalid search synonym row id={}", row.id) }
            }
            SynonymState(lines, now).also { synonyms = it }
        } catch (e: Exception) {
            logger.warn("Search synonyms reload failed, keeping previous list: {}", e.message)
            val keep = SynonymState(prev?.lines.orEmpty(), now)
            synonyms = keep
            keep
        }
    }

    // ── 빌드 ─────────────────────────────────────────────────────────────────────────────────────

    private fun build(synonymLines: List<String>): ExerciseSearchEngine {
        val started = System.currentTimeMillis()
        val exercises = exerciseRepository.findAllWithMuscleGroups()
        val namesByExercise = HashMap<Long, MutableMap<String, String>>()
        exerciseTranslationRepository.findAllNameRows().forEach { row ->
            val id = (row[0] as Number).toLong()
            namesByExercise.getOrPut(id) { linkedMapOf() }[(row[1] as String).lowercase()] = row[2] as String
        }
        val descriptions = loadDescriptions()
        val docs = exercises.map { toDoc(it, namesByExercise[it.id].orEmpty(), descriptions[it.id].orEmpty()) }
        buildCount.incrementAndGet()
        logger.info("Exercise search index built: {} docs in {}ms", docs.size, System.currentTimeMillis() - started)
        return newEngine(docs, synonymLines)
    }

    /** 의미 검색이 켜져 있고 문서 임베딩이 있으면 엔진 위에 하이브리드 검색을 얹는다. 아니면 null — 어휘 검색만(오늘과 같다). */
    private fun hybridFor(engine: ExerciseSearchEngine): HybridSearch? {
        val source = queryVectors ?: return null
        if (!source.enabled) return null
        val vectors = try { vectorStore?.get() } catch (e: Exception) { logger.warn("Exercise embeddings unavailable, semantic search is off: {}", e.message); null } ?: return null
        val hybrid = HybridSearch(engine, vectors)
        logger.info("Semantic search enabled: {} of {} exercises have embeddings", hybrid.coveredDocs, engine.size)
        return hybrid
    }

    /** DB 동의어(search_synonyms)를 내장 사전 뒤에 붙여 엔진을 만든다 — 재배포 없이 검색 결과를 조정하는 경로 */
    private fun newEngine(docs: List<ExerciseSearchDoc>, synonymLines: List<String>): ExerciseSearchEngine =
        ExerciseSearchEngine(docs, synonymLines, engineBudgetNanos)

    /** 운동별 로케일 → 설명문(instructions + tips). 읽기에 실패하면 빈 맵 — 설명문은 보조 계층이라 검색 전체를 막지 않는다. */
    private fun loadDescriptions(): Map<Long, Map<String, String>> {
        val repo = descriptionRepository ?: return emptyMap()
        return try {
            val out = HashMap<Long, MutableMap<String, String>>()
            repo.findAllDescriptionRows().forEach { row ->
                val id = (row[0] as Number).toLong()
                val locale = (row[1] as String).lowercase()
                val text = listOfNotNull(row[2] as? String, row[3] as? String).filter { it.isNotBlank() }.joinToString("\n")
                if (text.isNotEmpty()) out.getOrPut(id) { linkedMapOf() }[locale] = text
            }
            out
        } catch (e: Exception) {
            logger.warn("Exercise description rows could not be loaded, description search is off: {}", e.message)
            emptyMap()
        }
    }

    private fun toDoc(e: Exercise, translated: Map<String, String>, descriptions: Map<String, String> = emptyMap()): ExerciseSearchDoc {
        val names = LinkedHashMap<String, String>()
        names.putAll(translated)
        // 번역이 없는 로케일을 대비해 기본 이름을 en(없으면 기본 로케일)으로 보존
        names.putIfAbsent(e.defaultLocale.lowercase(), e.name)
        return ExerciseSearchDoc(
            id = e.id,
            slug = e.slug,
            names = names,
            category = e.category,
            equipment = e.equipment,
            muscleGroups = e.muscleGroups.toSet(),
            movementPattern = e.movementPattern,
            tier = e.recommendationTier,
            popularity = e.popularity,
            difficulty = e.difficulty,
            descriptions = descriptions
        )
    }

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
        private const val SYNONYM_TTL_MILLIS = 60 * 1000L
        private const val FAILURE_BACKOFF_MILLIS = 30 * 1000L
        private const val MAX_REBUILD_ATTEMPTS = 3
        private const val RESULT_CACHE_SIZE = 512
        private const val DEGRADED_RESULT_TTL_MILLIS = 30 * 1000L
        private const val FALLBACK_LIMIT = 10
        private const val FALLBACK_PER_TOKEN = 30
        private const val MAX_FALLBACK_TOKENS = 5

        /** 엔진에 넘기는 질의의 상한 — 이보다 긴 입력은 엔진 비용이 초선형으로 늘어난다 */
        const val MAX_QUERY_CHARS = 160
        const val MAX_QUERY_WORDS = 14
        private const val HEAD_WORDS = 8
        private val FILLER_WORDS = setOf("a", "an", "the", "of", "to", "for", "my", "me", "i", "im", "ive", "please", "just", "some", "any", "that", "this", "it", "is", "are", "be", "so", "very", "really", "can", "could", "would", "should", "do", "does", "want", "wanna", "need", "like", "also", "too", "because", "since", "as")


        private val WHITESPACE = Regex("\\s+")
        private val WARM_QUERIES = listOf(
            "squat", "스쿼트", "ベンチプレス", "bench press", "하체", "胸", "덤벨 가슴", "lat pulldown", "ラットプルダウン", "plank", "うつぶせで膝を曲げるマシン", "허벅지 뒤쪽이 땡겨요", "スクワットの代わり"
        )

        /** 제어문자 제거·공백 정리·반복 단어 제거·길이(160자)와 단어 수(14개) 제한. 비면 null(브라우즈) */
        fun normalizeQuery(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val limit = MAX_QUERY_CHARS * 4
            var cleaned = buildString(raw.length.coerceAtMost(limit)) {
                for (c in raw.take(limit)) append(if (c.isISOControl()) ' ' else c)
            }.replace(WHITESPACE, " ").trim()
            // 입력이 상한에서 단어 중간에 잘렸다면 그 조각 단어는 버린다("…dumbbell c" 같은 가짜 단어가 질의에 남지 않게). 공백 없는 한 덩어리면 그대로 둔다.
            if (raw.length > limit && !raw[limit].isWhitespace() && !raw[limit - 1].isWhitespace()) {
                val ws = cleaned.lastIndexOf(' ')
                if (ws > 0) cleaned = cleaned.substring(0, ws)
            }
            if (cleaned.isEmpty()) return null
            // 같은 단어의 반복("dumbbell curl row" × 450)은 의미 없이 비용만 키운다 — 중복을 빼고 단어 수를 제한한다.
            // 부정어("no squat no lunge" 의 두 번째 no)는 반복해도 뜻이 있어 남긴다.
            val seen = HashSet<String>()
            val distinct = cleaned.split(' ').filter { w -> seen.add(w.lowercase()) || w.lowercase() in QueryLexicon.negationWords }
            val joined = selectWords(distinct).joinToString(" ")
            val cut = if (joined.length > MAX_QUERY_CHARS) {
                var end = MAX_QUERY_CHARS
                if (Character.isHighSurrogate(joined[end - 1])) end--
                joined.substring(0, end).trim()
            } else joined
            return cut.ifEmpty { null }
        }

        /**
         * 단어 수 상한(14) 안에서 고른다. 넘치면 뜻 없는 영어 군더더기(a, the, of, please …)부터 빼고, 그래도 넘치면 앞 8 + 뒤 6 단어를 남긴다 —
         * 부정·부상 표현("…without jumping", "…because my knees hurt")은 꼬리에 오므로 앞 N개만 자르면 가장 중요한 말이 사라진다.
         */
        private fun selectWords(words: List<String>): List<String> {
            if (words.size <= MAX_QUERY_WORDS) return words
            val lean = words.filter { it.lowercase() !in FILLER_WORDS }
            val base = if (lean.size >= 2) lean else words
            if (base.size <= MAX_QUERY_WORDS) return base
            return base.take(HEAD_WORDS) + base.takeLast(MAX_QUERY_WORDS - HEAD_WORDS)
        }
    }
}

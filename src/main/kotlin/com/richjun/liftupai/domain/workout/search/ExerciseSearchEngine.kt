package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.engine.DescriptionIndex
import com.richjun.liftupai.domain.workout.search.engine.DescriptionLexicon
import com.richjun.liftupai.domain.workout.search.engine.IDoc
import com.richjun.liftupai.domain.workout.search.engine.Mode
import com.richjun.liftupai.domain.workout.search.engine.NameKey
import com.richjun.liftupai.domain.workout.search.engine.QueryAnalyzer
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Role
import com.richjun.liftupai.domain.workout.search.engine.QueryPlan
import com.richjun.liftupai.domain.workout.search.engine.SigIndex
import com.richjun.liftupai.domain.workout.search.engine.SigKey
import com.richjun.liftupai.domain.workout.search.engine.Vocabulary
import com.richjun.liftupai.domain.workout.search.semantic.SemanticConstraint
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** 검색 엔진이 보는 운동 문서 (DB 비의존). names 는 locale → 표시 이름. */
data class ExerciseSearchDoc(
    val id: Long,
    val slug: String,
    val names: Map<String, String>,
    val category: ExerciseCategory,
    val equipment: Equipment?,
    val muscleGroups: Set<MuscleGroup>,
    val movementPattern: String?,
    val tier: RecommendationTier,
    val popularity: Int,
    val difficulty: Int,
    /** locale → 동작 설명문(instructions + tips). 이름으로 못 찾는 묘사 질의("うつぶせで膝を曲げる")를 위한 보조 재현율 계층에만 쓰인다. */
    val descriptions: Map<String, String> = emptyMap()
)

data class ExerciseSearchHit(val doc: ExerciseSearchDoc, val score: Double)

/**
 * 다국어(ko/ja/en) 운동 검색 엔진 — 순수 메모리 구조.
 *
 * 처리 순서
 * 1. 질의 이해(engine/QueryAnalyzer): 조사·어미·군더더기·수치를 떼고, 부위·목표·자세·장비·집·수준을 뽑고,
 *    부정("없이/以外/without")과 부상("무릎 안 좋을 때/膝に優しい/bad knees") 표현을 제외·가중 조건으로 바꾼다.
 * 2. 의도 질의(이름이 아니라 부위/목표를 물은 질의)는 이름 속 "신호 표기"로 운동을 가려 랭킹한다.
 * 3. 이름 질의는 매칭 품질 band(정확 > 단어 경계 토큰 > 부분 토큰 > 일부 토큰 > 표기 변이 > 퍼지)
 *    + 티어/인기도 타이브레이커로 랭킹한다. 오타는 어휘 단위로 먼저 교정한다.
 * 4. 이름 근거가 없거나 약한 질의, 또는 동작을 풀어 쓴 질의("うつぶせで膝を曲げるマシン")는 운동의 동작 설명문
 *    (instructions·tips, ExerciseSearchDoc.descriptions)을 BM25 로 검색해 이름보다 낮은 대역에서 섞는다(engine/DescriptionIndex).
 *    설명문이 없는 문서 집합에서는 이 계층이 아무 일도 하지 않는다.
 * 브라우즈(질의 없음)는 티어 → 인기도 → 로컬라이즈 이름 순.
 *
 * 입력은 상한(160자·14단어)으로 묶고, 같은 질의는 작은 LRU 에 캐시한다. 그래서 어떤 입력도 비용이 일정 범위 안이다.
 *
 * @param extraSynonyms 런타임 동의어 줄("트리거|트리거 => 확장|확장", `=>>` 는 강한 별칭). 내장 사전에 더해진다. 형식이 틀린 줄은 무시한다.
 */
class ExerciseSearchEngine(
    docs: Collection<ExerciseSearchDoc>,
    extraSynonyms: List<String> = emptyList(),
    /** 한 질의의 점수 계산 예산(나노초). 넘겨서 끝난 결과는 불완전하므로 캐시하지 않는다. 테스트에서 줄여 쓴다. */
    private val budgetNanos: Long = DEFAULT_BUDGET_NANOS
) {

    /** 한 질의의 시간 예산. 한 번이라도 넘기면 [exceeded] 가 서서 결과가 불완전함을 알린다. */
    private class Budget(nanos: Long) {
        private val deadline = System.nanoTime() + nanos
        @Volatile var exceeded = false
        fun over(): Boolean {
            if (System.nanoTime() > deadline) { exceeded = true; return true }
            return false
        }
    }

    /** 검색 결과와 완전성 — 시간 예산을 넘겨 끝난 결과(complete=false)는 호출자도 캐시하지 않아야 한다 */
    class SearchOutcome(val hits: List<ExerciseSearchHit>, val complete: Boolean)

    private class Variant(
        val words: List<String>,
        val penalty: Double,
        val exactBand: Double
    ) {
        val strict: String = SearchText.strictKey(words)
        val loose: String = SearchText.looseKey(strict)
        val looseWords: List<String> = words.map { SearchText.looseKey(it) }

        /** 일반 단어(장비·수식어·stretch)를 뺀 핵심 키 — 퍼지·바이그램은 이것으로 비교한다(frog stretch 가 모든 stretch 에 걸리지 않게) */
        val coreLoose: String = SearchText.looseKey(SearchText.strictKey(words.filter { it !in QueryLexicon.fuzzyIgnorable }))

        /** 이 변형이 말하는 동작 단어(머리 단어)와 부위 묶음 */
        val heads: BitSet = BitSet().also { b ->
            // 마지막 단어가 동작 단어를 치다 만 것("face pul")이어도 그 동작을 말한 것으로 본다
            val tail = words.lastOrNull().orEmpty()
            QueryLexicon.headWords.forEachIndexed { i, k -> if (strict.contains(k.key) || (tail.length >= 3 && k.key.length > tail.length && k.key.contains(tail))) b.set(i) }
        }
        /** 부위만 가리키는 단어(chest, 가슴, 脚) — 부분 일치에서 이것만 맞은 이름은 일치로 치지 않는다 */
        val regionWords: Set<String> = words.filter { w ->
            QueryLexicon.regionGroups.any { g -> g.any { k -> if (k.latin) w == k.key else w.contains(k.key) } }
        }.toSet()
        val regions: BitSet = BitSet().also { b ->
            // 라틴 표기는 단어가 같을 때만, 한글·가나 표기는 붙여 쓴 복합어(レッグエクステンション) 안에서도 부위로 본다
            QueryLexicon.regionGroups.forEachIndexed { i, g ->
                if (g.any { k -> if (k.latin) words.any { w -> w == k.key } else strict.contains(k.key) }) b.set(i)
            }
        }
    }

    private class MuscleHint(val muscles: Set<MuscleGroup>, val categories: Set<ExerciseCategory>) {
        fun matches(d: ExerciseSearchDoc): Boolean =
            (muscles.isEmpty() && categories.isEmpty()).not() &&
                (d.muscleGroups.any { it in muscles } || d.category in categories)
    }

    private val synonymTable = ExerciseSearchLexicon.synonymTable(extraSynonyms)

    private val indexed: List<IDoc> = docs.mapIndexed { i, d ->
        IDoc(i, d, d.names.values.filter { it.isNotBlank() }.map { NameKey.build(it) }, rankOf(d))
    }

    private val sigIndex = SigIndex(indexed)

    /** 설명문 색인 — 설명문이 없는 문서 집합(테스트·빈 카탈로그)에서는 비어 있어 아무 일도 하지 않는다 */
    private val descIndex = DescriptionIndex(indexed)
    private val indexById: Map<Long, IDoc> = indexed.associateBy { it.doc.id }

    /** 모든 이름의 strict 키 — 질의가 이름과 정확히 같은지 O(1) 로 본다 */
    private val exactNames: Set<String> = indexed.flatMapTo(HashSet()) { d -> d.names.map { it.strict } }
    private val vocabulary = Vocabulary(docs.map { it.names.values.filter { n -> n.isNotBlank() } }, synonymTable)
    private val analyzer = QueryAnalyzer(vocabulary, synonymTable)

    /** 개념 → 신호 일치 비트셋 (개념은 정적이라 한 번만 계산) */
    private val conceptBits = ConcurrentHashMap<String, BitSet>()

    /** 질의 결과 LRU — 같은 질의를 페이지마다 다시 계산하지 않는다 */
    private val cache = object : LinkedHashMap<String, List<ExerciseSearchHit>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExerciseSearchHit>>?): Boolean = size > CACHE_SIZE
    }

    val size: Int get() = indexed.size

    /** 색인된 운동 문서(등록 순서) — 하이브리드 검색이 문서 벡터와 짝지을 때 쓴다 */
    fun documents(): List<ExerciseSearchDoc> = indexed.map { it.doc }

    /** 대표 질의 몇 개를 미리 돌려 클래스 로딩·JIT 비용을 첫 요청이 아니라 빌드 시점에 치른다 */
    fun warmUp() {
        runCatching {
            for (q in WARM_QUERIES) search(q)
            synchronized(cache) { cache.clear() }
        }
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────────────────────

    /** 질의 검색 (질의가 비어 있으면 브라우즈). 필터는 전부 AND 로 적용된다. */
    fun search(
        query: String?,
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): List<ExerciseSearchHit> = searchOutcome(query, category, equipments, locale).hits

    /** [search] 와 같되 결과가 시간 예산 안에서 끝났는지도 알려 준다. 불완전한 결과는 엔진 캐시에도 넣지 않는다. */
    fun searchOutcome(
        query: String?,
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): SearchOutcome {
        val raw = query.orEmpty()
        val norm = if (raw.isBlank()) "" else SearchText.normalize(raw).trim()
        val loc = locale?.lowercase()?.substringBefore('-').orEmpty()
        val cacheKey = buildString {
            append(norm).append('\u0000').append(category?.name).append('\u0000')
            equipments?.map { it.name }?.sorted()?.joinTo(this, ",")
            append('\u0000').append(if (norm.isEmpty()) loc else "")
        }
        synchronized(cache) { cache[cacheKey] }?.let { return SearchOutcome(ArrayList(it), true) }

        val budget = Budget(budgetNanos)
        val result = compute(raw, norm, category, equipments, locale, budget)
        // 시간 예산을 넘겨 일부만 훑은 결과는 캐시하지 않는다 — 한 번 느렸다고 TTL 동안 잘린 결과가 고정되지 않게
        if (!budget.exceeded) synchronized(cache) { cache[cacheKey] = result }
        return SearchOutcome(ArrayList(result), !budget.exceeded)
    }

    /** 엔진 결과 캐시에 든 질의 수(테스트용) */
    internal fun cachedQueryCount(): Int = synchronized(cache) { cache.size }

    /**
     * 질의 전체의 부정·부상 조건으로 제외될 운동인지 판별하는 함수(없으면 null).
     * 0건 대안처럼 질의를 쪼개 다시 찾는 곳이 "점프 빼고"·"무릎이 안 좋아서" 맥락을 잃지 않게 한다.
     */
    internal fun exclusionPredicate(query: String): ((ExerciseSearchDoc) -> Boolean)? {
        val tok = SearchText.tokenize(query)
        if (tok.words.isEmpty() || tok.strict in exactNames) return null
        val plan = analyzer.analyze(tok).plan
        if (!plan.hasExclusions) return null
        val ex = exclusionSet(plan)
        return { doc -> indexById[doc.id]?.let { isExcluded(it, plan, ex) } ?: false }
    }

    /**
     * 의미 검색(임베딩) 후보에 적용할 하드 조건 — 어휘 검색이 같은 질의에 거는 조건과 똑같다.
     * 질의가 없거나(브라우즈)·초성이거나·어떤 운동 이름과 정확히 같으면 의미 검색 대상이 아니라 null.
     * - 카테고리·장비 필터(호출자가 넘긴 것)
     * - 부정한 운동·동작·장비·부위, 부상 관절의 금지 동작([exclusionSet]·[isExcluded])
     * - 부상 관절의 "조심할" 동작(어휘 검색은 뒤로 미루지만, 의미 검색 후보는 아예 쓰지 않는다 — 안전 쪽으로 보수적으로)
     * - 의도 질의에서 말한 장비·집·생활 사물의 장비 범위(어휘 검색의 [searchIntent] 와 같은 범위)
     */
    fun semanticConstraint(
        query: String?,
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        forHints: Boolean = false
    ): SemanticConstraint? {
        val raw = query.orEmpty()
        val norm = if (raw.isBlank()) "" else SearchText.normalize(raw).trim()
        if (norm.isEmpty() || SearchText.isChoseongQuery(norm)) return null
        val tok = SearchText.tokenize(raw)
        if (tok.words.isEmpty() || tok.strict in exactNames) return null
        val analysis = analyzer.analyze(tok)
        val plan = analysis.plan
        if (plan.mode == Mode.BROWSE) return null
        val pool = filtered(category, equipments)
        // "수건으로 …"처럼 장비 자리에 쓴 생활 사물(밴드 대용) — 힌트 후보의 장비 범위를 홈 장비로 넓혀도 되는 경우
        val substituteObject = forHints && plan.home && SUBSTITUTE_OBJECT_KEYS.any { tok.strict.contains(it) }
        val ex = if (plan.hasExclusions) exclusionSet(plan) else null
        val soft = if (plan.injuries.isNotEmpty()) softBits(plan) else null
        var cands = pool.filter { ex == null || !isExcluded(it, plan, ex) }
        if (plan.mode == Mode.INTENT) {
            val allowedEquip = if (forHints) hintIntentEquipment(plan, substituteObject) else intentEquipment(plan)
            // 스트레칭·모빌리티 의도에서 장비는 "있으면 좋은" 조건 — 그 장비의 스트레칭이 몇 개 없으면 의미 후보를 장비로 거르지 않는다
            if (allowedEquip != null) cands.filter { it.doc.equipment in allowedEquip }
                .takeIf { it.isNotEmpty() && !(mobilityIntent(plan) && it.count { d -> d.isStretch } < SOFT_EQUIP_MIN_HITS) }?.let { cands = it }
        }
        val allowed = BitSet(indexed.size)
        for (d in cands) if (soft == null || !soft.get(d.index)) allowed.set(d.index)
        val byId = indexById
        // "벤치프레스 보조로 삼두"처럼 목표 말투에서 그 운동 자체는 뒤로 미룰 문서, 그리고 이름 일치를 "토큰 통째"로 확인할 내용 토큰
        val goalLifts = if (plan.mode == Mode.NAME) goalSupport(raw, analysis, plan, pool)?.liftIds.orEmpty() else emptySet()
        val contentTokens = contentTokensOf(plan)
        return object : SemanticConstraint {
            override fun allows(doc: ExerciseSearchDoc): Boolean = byId[doc.id]?.let { allowed.get(it.index) } ?: false
            override val goalLiftIds: Set<Long> = goalLifts
            override val requestedEquipment: Set<Equipment> = if (SUBSTITUTE_OBJECT_KEYS.any { tok.strict.contains(it) }) emptySet() else plan.equipments
            override fun wholeTokenNameMatch(doc: ExerciseSearchDoc): Boolean = byId[doc.id]?.let { d -> wholeTokenMatch(d, contentTokens) } ?: false
            override val unrecognized: Boolean = plan.hasUnknown
            override val injuryAware: Boolean = plan.injuries.isNotEmpty()
            override val homeBodyweightFirst: Boolean = plan.home && plan.equipments.isEmpty() && equipments.isNullOrEmpty()
            override val broadKeyword: Boolean = plan.mode == Mode.INTENT && !plan.hasUnknown && plan.concepts.isNotEmpty() && plan.concepts.all { it.id.startsWith("muscle:") } &&
                !plan.hasExclusions && !plan.home && !plan.stretch && plan.level == 0 && !plan.regress && plan.similarTo.isEmpty() && plan.numbers.isEmpty() && !plan.highReps
            override val lexicalInformative: Boolean = plan.hasIntentSignal || plan.stretch || plan.level != 0 || plan.regress || plan.injuries.isNotEmpty() ||
                plan.seeds.any { it.isNotEmpty() } || plan.hasUnknown || plan.similarTo.isNotEmpty() || plan.numbers.isNotEmpty() || plan.highReps || plan.combineAll
        }
    }

    /** 이 문자열이 어떤 운동의 이름(어느 로케일이든)과 정확히 같은가 — LLM 이 말한 이름이 카탈로그에 그대로 있는지 알아볼 때 쓴다 */
    fun isExactName(text: String): Boolean {
        val tok = SearchText.tokenize(text)
        return tok.words.isNotEmpty() && tok.strict in exactNames
    }

    /** 브라우즈 순서: 티어 → 인기도 → 로컬라이즈 이름 */
    fun browse(
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): List<ExerciseSearchHit> = browseSorted(filtered(category, equipments), locale)

    /** 질의 해석 결과(진단·테스트용) */
    internal fun analyze(query: String): QueryPlan = analyzer.analyze(SearchText.tokenize(query)).plan

    internal fun nearestDebug(word: String): List<String> = vocabulary.nearest(SearchText.words(word).firstOrNull().orEmpty(), relaxed = true)

    /** 질의 해석을 사람이 읽는 문자열로(진단용): 분해된 조각과 계획 */
    internal fun explain(query: String): String {
        val a = analyzer.analyze(SearchText.tokenize(query))
        val tok = a.tok  // 사전 용어 오타를 고친 토큰열
        val p = a.plan
        val segs = a.segs.joinToString(" ") { sg ->
            val text = tok.strict.substring(sg.s, sg.e)
            if (sg.unknown) "?$text" else if (sg.isNum) "#$text" else text + "/" + sg.terms!!.joinToString("+") { it.role.name.take(4) }
        }
        return "mode=${p.mode} segs=[$segs] concepts=${p.concepts.map { it.id }} equip=${p.equipments} home=${p.home} stretch=${p.stretch} level=${p.level} " +
            "exEquip=${p.excludeEquip} exKeys=${p.excludeKeys.map { it.key }} injuries=${p.injuries} nums=${p.numbers} seeds=${p.seeds}" +
            (if (p.mode == Mode.NAME && p.seeds.isNotEmpty()) "\n   variants=" + buildVariants(p.seeds).first.joinToString(" ") { "${it.words}@${it.penalty}" } else "")
    }

    /** 설명문 검색 결과(진단용) */
    internal fun explainDesc(query: String): String {
        val a = analyzer.analyze(SearchText.tokenize(query))
        val text = descQueryText(a)
        return "desc[$text] " + descIndex.search(text, null, 8).joinToString(" ") { "${indexed[it.index].doc.slug}(${"%.2f".format(it.coverage)}/${"%.1f".format(it.bm25)})" }
    }

    private fun compute(
        raw: String,
        norm: String,
        category: ExerciseCategory?,
        equipments: Set<Equipment>?,
        locale: String?,
        budget: Budget
    ): List<ExerciseSearchHit> {
        val pool = filtered(category, equipments)
        if (norm.isEmpty()) return browseSorted(pool, locale)

        if (SearchText.isChoseongQuery(norm)) return searchChoseong(norm, pool)
        val tok = SearchText.tokenize(raw)
        // 글자·숫자가 하나도 없는(기호·이모지·낱자 자모뿐인) 질의는 결과 없음
        if (tok.words.isEmpty()) return emptyList()

        // 질의가 어떤 운동의 이름(어느 언어든)과 정확히 같으면 해석할 것 없이 그 이름으로 찾는다 ("AB 턱", "Barbell good morning")
        val analysis = if (tok.strict in exactNames) null else analyzer.analyze(tok)
        val plan = analysis?.plan ?: QueryPlan.nameOnly(tok.words)
        // "벤치프레스 보조로 삼두 키우기"처럼 어떤 운동을 돕는 목표 말투면 그 운동이 아니라 목표 부위 운동을 찾는다(그 운동 자체는 뒤로)
        if (analysis != null && plan.mode == Mode.NAME) goalSupport(raw, analysis, plan, pool)?.let { g ->
            val out = searchIntent(g.plan, pool).map { h -> if (h.doc.id in g.liftIds) ExerciseSearchHit(h.doc, h.score - GOAL_LIFT_PENALTY) else h }
            if (out.isNotEmpty()) return rankSorted(out)
        }
        var result = when (plan.mode) {
            Mode.BROWSE -> browseSorted(excludeFrom(pool, plan), locale)
            Mode.INTENT -> searchIntent(plan, pool)
            Mode.NAME -> searchName(plan, pool, budget)
        }
        // 이름으로는 못 찾았는데 부위·장비를 알아들었다면 그 의도로 보여 준다
        var weak = false
        if (result.isEmpty() && plan.mode == Mode.NAME && plan.hasIntentSignal) { result = searchIntent(plan, pool); weak = true }
        if (analysis != null && descIndex.indexed > 0 && plan.mode != Mode.BROWSE) result = withDescriptions(analysis, plan, pool, result, weak)
        return result
    }

    // ── 설명문 검색(동작 묘사 질의) ───────────────────────────────────────────────────────────

    /** 질의에서 군더더기·부정·수치 표현을 뺀 설명문 검색용 텍스트(뺀 자리는 공백이라 n-gram 이 이어 붙지 않는다) */
    private fun descQueryText(a: QueryAnalyzer.Analysis, dropConcepts: Boolean = false): String {
        val text = a.tok.strict
        val drop = BooleanArray(text.length)
        for (sg in a.segs) {
            val t = sg.terms ?: continue
            if (dropConcepts && sg.e - sg.s >= 2 && t.any { it.role == Role.CONCEPT }) { for (p in sg.s until sg.e) drop[p] = true; continue }
            // 장비·장소 표기("マシン", "덤벨", "집에서")는 필터로 따로 쓰므로 설명문 질의에서는 뺀다 — 안 그러면 설명문에 "ケーブルマシン"이 나오는 케이블 운동이 걸려든다
            if (t.all { it.role == Role.EQUIP || it.role == Role.HOME }) { for (p in sg.s until sg.e) drop[p] = true; continue }
            if (sg.isNum || t.all { it.role == Role.UNIT || it.role == Role.NEG || it.role == Role.CONJ || it.role == Role.LEVEL || (it.role == Role.NOISE && !it.particle) }) {
                // "lift"·"持ち上げる" 처럼 이름 검색에서는 군더더기지만 동작 묘사에서는 핵심인 말은 남긴다
                if (!sg.isNum && t.any { it.role == Role.NOISE } && t.none { it.role == Role.NEG || it.role == Role.UNIT }) {
                    val keep = HashSet<String>().also { DescriptionLexicon.conceptsIn(DescriptionLexicon.canon(SearchText.fold(text.substring(sg.s, sg.e))), it) }
                    if (keep.any { it in DescriptionLexicon.MOTION_CONCEPTS }) continue
                }
                for (p in sg.s until sg.e) drop[p] = true
            }
        }
        val sb = StringBuilder()
        for ((wi, w) in a.tok.words.withIndex()) {
            val st = a.tok.starts[wi]
            sb.append(' ')
            for (k in w.indices) if (drop[st + k]) sb.append(' ') else sb.append(w[k])
        }
        return sb.toString()
    }

    /** 설명문으로 찾을 만한 길이의 질의인가 — 내용어(라틴 단어·한글·가나·한자 덩어리)가 둘 이상이거나 CJK 글자가 5자 이상 */
    private fun descriptive(text: String): Boolean {
        var units = 0
        var cjkLetters = 0
        val sb = StringBuilder()
        var latin = false
        fun flush() {
            if (sb.isNotEmpty()) {
                // stretch·machine 같은 일반어만으로는 묘사가 아니다("frog stretch" 의 stretch)
                if (!latin || SearchText.stem(sb.toString()) !in QueryLexicon.fuzzyIgnorable) units++
                sb.setLength(0)
            }
        }
        for (c in SearchText.fold(text)) {
            val isLatin = c.code < 0x250 && c.isLetter()
            val isCjk = c.code >= 0x250 && c.isLetter()
            if (isLatin || isCjk) {
                if (sb.isNotEmpty() && latin != isLatin) flush()
                latin = isLatin
                sb.append(c)
                if (isCjk) cjkLetters++
            } else flush()
        }
        flush()
        return units >= 2 || cjkLetters >= 5
    }

    /**
     * 이름 검색이 비었거나 약할 때, 또는 동작을 풀어 쓴 의도 질의일 때 설명문(instructions·tips) 검색을 섞는다.
     * 이름만으로 찾은 후보는 설명문이 새로 끼워 넣는 후보보다 앞선다(설명문만으로 찾은 운동은 BAND_INTENT 근처에서 시작해 이름 일치 밴드를 넘지 못한다).
     * 이름 근거가 이미 확실하면(토큰 일치 이상) 설명문은 아무 일도 하지 않는다.
     */
    private fun withDescriptions(a: QueryAnalyzer.Analysis, plan: QueryPlan, pool: List<IDoc>, result: List<ExerciseSearchHit>, weak: Boolean): List<ExerciseSearchHit> {
        val topScore = result.firstOrNull()?.score ?: 0.0
        val text = descQueryText(a)
        // 동작 단서는 부위·목표 개념 표기("内ももの引き締め")를 뺀 나머지 말에서 찾는다 — 개념어 속의 動詞 비슷한 글자가 묘사로 오해되지 않게
        val verbText = descQueryText(a, dropConcepts = true)
        val concepts = HashSet<String>().also { DescriptionLexicon.conceptsIn(DescriptionLexicon.canon(SearchText.fold(verbText)), it) }
        // 한·일어는 동사 하나("回す")로도 동작 묘사지만, 영어의 push·lower·raise 는 이름에도 흔해서 둘 이상 겹칠 때만 묘사로 본다
        val motionCount = concepts.count { it in DescriptionLexicon.MOTION_CONCEPTS }
        val cjk = verbText.any { it.code >= 0x250 && it.isLetter() }
        val motion = motionCount >= (if (cjk) 1 else 2)
        // 이름 근거가 약한가: 비었거나, 이름으로는 못 찾아 의도 폴백으로 갔거나, 부분 일치뿐이다. 동작 묘사 질의는 한 단계 엄격하다(side·arm 같은 흔한 단어가 부분 일치로 걸리기 쉽다).
        var nameWeak = result.isEmpty() || weak || topScore < BAND_PARTIAL || (motion && plan.mode == Mode.NAME && topScore < BAND_TOKEN_SUB)
        // 한 단어짜리 질의("squat")는 묘사가 아니라 이름이다 — 필터(카테고리·장비)로 0건이 됐다고 설명문에서 억지로 찾지 않는다(대안은 호출자가 낸다)
        if (!motion && !descriptive(text)) return result
        // 필터가 걸린 채 이름으로 0건이면 "그 필터 안에는 그런 운동이 없다"는 뜻이다 — 묘사 질의가 아닌 한 설명문으로 채우지 않는다
        if (!motion && result.isEmpty() && pool.size < indexed.size) return result
        // 스트레칭 의도("二の腕を伸ばす")의 伸ばす 나 부정("ジャンプしない")의 대상은 동작 묘사가 아니다
        val intentMotion = plan.mode == Mode.INTENT && motion && !plan.stretch && plan.excludeKeys.isEmpty() && plan.excludeConcepts.isEmpty()
        if (!nameWeak && !intentMotion) return result
        // 장비를 말한 질의("…マシン", "…머신")는 설명문 후보도 그 장비로 좁힌다(그 장비의 운동이 없으면 좁히지 않는다)
        var equipPool = if (plan.equipments.isEmpty()) pool else pool.filter { it.doc.equipment in plan.equipments }.ifEmpty { pool }
        // 집·생활 사물 의도는 이름·의도 경로와 같은 장비 범위(맨몸·홈 장비·밴드)만 쓴다 — 설명문만으로 찾은 후보가 바벨·머신으로 새지 않게.
        // 스트레칭·모빌리티 의도에서 장비는 "있으면 좋은" 조건이라 그 범위의 스트레칭이 몇 개 없으면 좁히지 않는다.
        if (plan.equipments.isEmpty()) intentEquipment(plan)?.let { eq ->
            pool.filter { it.doc.equipment in eq }
                .takeIf { it.isNotEmpty() && !(mobilityIntent(plan) && it.count { d -> d.isStretch } < SOFT_EQUIP_MIN_HITS) }
                ?.let { equipPool = it }
        }
        val allowed = BitSet(indexed.size).also { b -> equipPool.forEach { b.set(it.index) } }
        val soft = if (plan.injuries.isNotEmpty()) softBits(plan) else null
        val prefer = if (plan.injuries.isNotEmpty()) preferBits(plan) else null
        val ex = if (plan.hasExclusions) exclusionSet(plan) else null
        // 이름 일치가 조금이라도 있는 질의("frog stretch")는 설명문이 그 위에 쏟아져 들어오지 않게 더 엄격하게(coverage 높게) 본다
        val minCoverage = if (result.isNotEmpty() && !weak && !motion) DESC_MIN_COVERAGE_WITH_NAME else DESC_MIN_COVERAGE
        val hits = descIndex.search(text, allowed).filter { it.coverage >= minCoverage }
            .filter { h -> ex == null || !isExcluded(indexed[h.index], plan, ex) }
        if (hits.isEmpty()) return result
        // 동작 묘사로 물었고 설명문이 분명하게 가리키는 곳이 있으면(coverage 높음) 부위 의도 후보 중 설명문에 안 걸린 것은 뒤로 — 부위 한 단어가 묘사 전체를 덮지 않게
        if (intentMotion && hits.first().coverage >= STRONG_DESC_COVERAGE) nameWeak = true
        val out = HashMap<Long, ExerciseSearchHit>()
        for (h in result) out[h.doc.id] = h
        val best = hits.first().bm25.coerceAtLeast(1e-9)
        val seen = HashSet<Long>()
        // 부위가 아니라 자세·사물("서서", "의자")만 알아들은 의도 질의는 설명문이 주도한다 — 그 개념에 맞는 후보를 강하게 지키지 않는다
        val posOnly = intentMotion && plan.concepts.isNotEmpty() && plan.concepts.all { it.id.startsWith("pos-") || it.id.startsWith("obj:") }
        val newBase = if (posOnly) BAND_INTENT + 20.0 else BAND_INTENT
        for (h in hits) {
            val d = indexed[h.index]
            val rel = 0.5 * h.coverage + 0.5 * (h.bm25 / best)
            val cur = out[d.doc.id]
            seen.add(d.doc.id)
            // 새로 끼워 넣는 후보에도 부상 관절의 "조심할 동작" 뒤로 미룸·권장 동작 앞으로를 같게 적용한다(이미 찾은 후보는 그 경로에서 적용됐다)
            val injury = if (cur == null && soft != null && prefer != null) injuryAdjust(d, plan, soft, prefer) else 0.0
            out[d.doc.id] = ExerciseSearchHit(d.doc, (cur?.score ?: (newBase + d.rank * 0.3 + injury)) + DESC_WEIGHT * rel)
        }
        // 이름 근거가 약한데 설명문이 가리키는 곳이 따로 있으면, 설명문에 안 걸린 약한 후보는 뒤로
        // (이름 부분 일치 이상으로 찾은 후보는 이름 질의의 근거라 깎지 않는다 — 의도·폴백·퍼지로 찾은 것만)
        if (nameWeak) for (h in result) if (h.doc.id !in seen && (plan.mode == Mode.INTENT || weak || h.score < BAND_PARTIAL)) {
            out[h.doc.id] = ExerciseSearchHit(h.doc, h.score - (if (posOnly) WEAK_DEMOTE * 2 else WEAK_DEMOTE))
        }
        return rankSorted(out.values.toList())
    }

    // ── 목표 말투("~의 보조로 삼두") ───────────────────────────────────────────────────────────

    /** 목표 말투로 읽은 결과: 목표 부위 의도 계획과, 말로 언급된 운동 자체(뒤로 미룰 문서들) */
    private class GoalSupport(val plan: QueryPlan, val liftIds: Set<Long>)

    /**
     * "ベンチプレスの補助で三頭筋を鍛える", "벤치프레스 보조로 삼두", "triceps work to support my bench press" —
     * 어떤 운동(말로 언급)을 돕는 보조 목표 말투이고 목표 부위가 있으면, 언급된 운동이 아니라 그 부위의 운동을 찾는다.
     * 아니면 null. 부위 개념과 언급된 운동(이름 구간)과 보조 표현이 모두 있을 때만 켜진다.
     */
    private fun goalSupport(raw: String, a: QueryAnalyzer.Analysis, plan: QueryPlan, pool: List<IDoc>): GoalSupport? {
        if (plan.mode != Mode.NAME || plan.concepts.none { it.id.startsWith("muscle:") }) return null
        val folded = SearchText.normalize(raw).lowercase()
        if (!GOAL_PHRASES.containsMatchIn(folded)) return null
        val lifts = ArrayList<String>()
        for (sg in a.segs) {
            if (!sg.has(Role.NAME)) continue
            val t = a.tok.strict.substring(sg.s, sg.e)
            if (t.length < 2 || GOAL_PHRASES.containsMatchIn(t)) continue  // "support" 자체가 이름 단어로 읽힌 구간은 언급된 운동이 아니다
            lifts.add(t)
        }
        if (lifts.isEmpty()) return null
        val ids = HashSet<Long>()
        for (d in pool) if (lifts.all { t -> d.names.any { n -> occurrenceQuality(n, t) >= OCC_WORD } }) ids.add(d.doc.id)
        if (ids.isEmpty()) return null
        val intent = QueryPlan(
            mode = Mode.INTENT, concepts = plan.concepts, equipments = plan.equipments, home = plan.home, stretch = plan.stretch, level = plan.level,
            regress = plan.regress, excludeEquip = plan.excludeEquip, excludeKeys = plan.excludeKeys, injuries = plan.injuries, numbers = plan.numbers,
            highReps = plan.highReps, seeds = plan.seeds, hasUnknown = plan.hasUnknown, similarTo = plan.similarTo, excludeConcepts = plan.excludeConcepts,
            combineAll = plan.combineAll
        )
        return GoalSupport(intent, ids)
    }

    /** 질의의 내용 토큰 — 이름 검색용 첫 단어열에서 장비·수식어 같은 일반 단어를 뺀 것(다 빠지면 그대로) */
    private fun contentTokensOf(plan: QueryPlan): List<String> {
        val words = plan.seeds.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        return words.filter { it !in QueryLexicon.genericWords }.ifEmpty { words }
    }

    /**
     * 모든 내용 토큰이 이 문서의 어떤 이름에서 "통째 단어(단어 경계에서 시작해 단어 경계에서 끝남)"로 나오는가 — 합성어 속 부분 문자열("삼각형" ⊂ "역삼각형")은 아니다.
     * 사전이 아는 별칭("ブルスク", "불스")이거나 사전 낱말로 나뉘는 붙여 쓴 복합어("ケーブルロウ")는 그 말이 이름을 가리키는 것이니 통째로 본다(부분 문자열 우연 일치가 아니다).
     */
    private fun wholeTokenMatch(d: IDoc, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        return tokens.all { t -> d.names.any { n -> alignedOccurrence(n, t) } || knownAlias(t) }
    }

    private fun knownAlias(t: String): Boolean {
        if (synonymGroups(t) != null) return true
        val parts = splitCompounds(listOf(t)) ?: return false
        return parts.all { synonymGroups(it) != null || it in ExerciseSearchLexicon.equipmentTerms }
    }

    private fun alignedOccurrence(n: NameKey, t: String): Boolean {
        if (t.isEmpty()) return false
        var from = 0
        while (true) {
            val i = n.strict.indexOf(t, from)
            if (i < 0) return false
            val e = i + t.length
            if ((i == 0 || i in n.starts) && (e == n.strict.length || e in n.starts)) return true
            from = i + 1
        }
    }

    // ── 풀/브라우즈 ──────────────────────────────────────────────────────────────────────────

    private fun filtered(category: ExerciseCategory?, equipments: Set<Equipment>?): List<IDoc> =
        indexed.filter { d ->
            (category == null || d.doc.category == category) &&
                (equipments.isNullOrEmpty() || d.doc.equipment in equipments)
        }

    private fun browseSorted(pool: List<IDoc>, locale: String?): List<ExerciseSearchHit> {
        val loc = locale?.lowercase()?.substringBefore('-')
        fun nameOf(d: ExerciseSearchDoc) = (d.names[loc] ?: d.names["en"] ?: d.names.values.firstOrNull()).orEmpty().lowercase()
        return pool.sortedWith(
            compareByDescending<IDoc> { tierScore(it.doc.tier) }
                .thenByDescending { it.doc.popularity }
                .thenBy { nameOf(it.doc) }
                .thenBy { it.doc.id }
        ).map { ExerciseSearchHit(it.doc, it.rank) }
    }

    private fun excludeFrom(pool: List<IDoc>, plan: QueryPlan): List<IDoc> {
        if (!plan.hasExclusions) return pool
        val ex = exclusionSet(plan)
        return pool.filter { !isExcluded(it, plan, ex) }
    }

    private fun tierScore(t: RecommendationTier) = when (t) {
        RecommendationTier.ESSENTIAL -> 2
        RecommendationTier.STANDARD -> 1
        else -> 0
    }

    /** 매칭 품질이 같을 때의 타이브레이커 (0..9): 티어 우선, 그다음 인기도 */
    private fun rankOf(d: ExerciseSearchDoc): Double {
        val t = when (d.tier) {
            RecommendationTier.ESSENTIAL -> 6.0
            RecommendationTier.STANDARD -> 3.0
            else -> 0.0
        }
        return t + d.popularity.coerceIn(0, 100) / 100.0 * 4.0
    }

    // ── 제외(부정·부상) ──────────────────────────────────────────────────────────────────────

    /** 이름 신호로 제외할 문서: 부정 대상 동작 + 부상 관절의 금지 동작 */
    private fun exclusionSet(plan: QueryPlan): BitSet {
        val b = BitSet(indexed.size)
        for (k in plan.excludeKeys) b.or(sigIndex.hits(k))
        for (j in plan.injuries) QueryLexicon.injuryProfiles[j]?.hard?.forEach { b.or(sigIndex.hits(it)) }
        for (c in plan.excludeConcepts) b.or(conceptMembers(c))
        return b
    }

    /**
     * 부정된 부위·목표("가슴 말고", "no legs")에 속하는 운동. 부위 메타(카테고리·근육)가 있으면 그것으로 — 이름 신호는
     * 다른 부위 운동에도 걸린다(fly ⊂ reverse fly). 메타가 없는 개념(자세 등)만 이름 신호로 가려낸다.
     */
    private fun conceptMembers(c: QueryLexicon.Concept): BitSet = conceptBits.getOrPut(c.id + "#members") {
        val b = BitSet(indexed.size)
        if (c.categories.isEmpty() && c.muscles.isEmpty()) b.or(sigIndex.anyOf(c.sig))
        else for (d in indexed) {
            val docMuscles = d.doc.muscleGroups
            // 같은 카테고리라도 근육이 겹쳐야 그 부위 운동이다 — 팔 카테고리의 삼두 운동은 "이두 빼고" 에서 남는다
            val inCategory = d.doc.category in c.categories && (c.muscles.isEmpty() || docMuscles.any { it in c.muscles })
            if (inCategory || (docMuscles.isNotEmpty() && docMuscles.all { it in c.muscles })) b.set(d.index)
        }
        b
    }

    private fun isExcluded(d: IDoc, plan: QueryPlan, ex: BitSet): Boolean =
        ex.get(d.index) || (d.doc.equipment != null && d.doc.equipment in plan.excludeEquip)

    /** 부상 관절의 조심할 동작(뒤로 미룸)·권장 동작(앞으로) */
    private fun injuryAdjust(d: IDoc, plan: QueryPlan, soft: BitSet, prefer: BitSet): Double {
        if (plan.injuries.isEmpty()) return 0.0
        var a = 0.0
        if (soft.get(d.index)) a -= INJURY_SOFT_PENALTY
        if (prefer.get(d.index)) a += INJURY_PREFER_BONUS
        return a
    }

    private fun softBits(plan: QueryPlan): BitSet = BitSet(indexed.size).also { b ->
        for (j in plan.injuries) QueryLexicon.injuryProfiles[j]?.soft?.forEach { b.or(sigIndex.hits(it)) }
    }

    private fun preferBits(plan: QueryPlan): BitSet = BitSet(indexed.size).also { b ->
        for (j in plan.injuries) QueryLexicon.injuryProfiles[j]?.prefer?.forEach { b.or(sigIndex.hits(it)) }
    }

    private fun levelAdjust(d: ExerciseSearchDoc, level: Int): Double {
        if (level == 0) return 0.0
        val a = ((d.difficulty - 35) / 60.0 * 10.0).coerceIn(-LEVEL_ADJUST, LEVEL_ADJUST)
        return if (level < 0) -a else a
    }

    // ── 초성 ─────────────────────────────────────────────────────────────────────────────────

    private fun searchChoseong(query: String, pool: List<IDoc>): List<ExerciseSearchHit> {
        val q = SearchText.choseong(query)
        val out = ArrayList<ExerciseSearchHit>()
        for (d in pool) {
            var best = -1.0
            for (n in d.names) {
                val idx = n.choseong.indexOf(q)
                if (idx < 0) continue
                val s = BAND_CHOSEONG + (if (idx == 0) 4.0 else 0.0) - n.choseong.length * 0.02
                if (s > best) best = s
            }
            if (best >= 0) out.add(ExerciseSearchHit(d.doc, best + d.rank))
        }
        return rankSorted(out)
    }

    // ── 의도(부위·목표·자세·장비·집) ─────────────────────────────────────────────────────────

    /** 이름에 부위 표기가 있는 문서 — "胸椎"처럼 다른 부위를 가리키는 복합어 속의 글자는 세지 않는다 */
    private fun nameHits(keys: List<SigKey>): BitSet {
        val b = BitSet(indexed.size)
        for (d in indexed) {
            if (d.sigNames.any { n -> keys.any { k -> hasOutsideMasks(n, k) } }) b.set(d.index)
        }
        return b
    }

    private fun hasOutsideMasks(n: NameKey, k: SigKey): Boolean {
        val masks = QueryLexicon.nameMasks
        var from = 0
        while (true) {
            val i = n.strict.indexOf(k.key, from)
            if (i < 0) return false
            val e = i + k.key.length
            val startOk = !k.latin || i == 0 || i in n.starts
            val endOk = !k.wordEnd || e == n.strict.length || e in n.starts
            val masked = masks.any { m -> var at = n.strict.indexOf(m); var hit = false; while (at >= 0) { if (i >= at && i < at + m.length) { hit = true; break }; at = n.strict.indexOf(m, at + 1) }; hit }
            if (startOk && endOk && !masked) return true
            from = i + 1
        }
    }

    private fun bitsFor(c: QueryLexicon.Concept, kind: String, keys: List<SigKey>): BitSet =
        conceptBits.getOrPut(c.id + "#" + kind) { sigIndex.anyOf(keys) }

    /** 일반 스트레칭 대표 목록 — 부위별 스트레칭 질의에서 가산한다 */
    private val generalStretch: BitSet by lazy { QueryLexicon.conceptById["stretchgeneral"]?.let { sigIndex.anyOf(it.sig) } ?: BitSet() }

    private inner class ConceptEval(val c: QueryLexicon.Concept, val stretchWanted: Boolean) {
        val explicit = !c.id.startsWith("muscle:")
        val sig: BitSet = bitsFor(c, "sig", c.sig)
        val sig2: BitSet = bitsFor(c, "sig2", c.sig2)
        val avoid: BitSet = bitsFor(c, "avoid", c.avoid)
        val names: BitSet = if (stretchWanted) conceptBits.getOrPut(c.id + "#names") { nameHits(c.nameWords) } else sig

        /** 이 개념에 대한 적합도 0..1 (후보가 아니면 -1) */
        fun fit(d: IDoc): Double {
            val doc = d.doc
            val patternHit = c.patterns.isNotEmpty() && doc.movementPattern in c.patterns
            val sigP = if (sig.get(d.index) || patternHit) 1.0 else 0.0
            val sigS = if (sig2.get(d.index)) 1.0 else 0.0
            val cat = if (doc.category in c.categories) 1.0 else 0.0
            val mus = if (doc.muscleGroups.any { it in c.muscles }) 1.0 else 0.0
            val overlap = doc.muscleGroups.count { it in c.muscles }
            val purity = if (doc.muscleGroups.isEmpty()) 0.0 else overlap.toDouble() / doc.muscleGroups.size
            var fit: Double
            if (stretchWanted) {
                // 스트레칭 질의: 스트레칭 문서 중 이름에 그 부위 표기가 있는 것을 우선
                if (!d.isStretch) return -1.0
                val nameHit = if (names.get(d.index)) 1.0 else 0.0
                if (nameHit == 0.0 && sigP == 0.0 && sigS == 0.0 && mus == 0.0) return -1.0
                // 널리 쓰는 대표 스트레칭(차일드 포즈·캣카우 …)은 부위 표기가 이름에 없어도 앞쪽에 둔다
                val canonical = if (c.id != "stretchgeneral" && generalStretch.get(d.index)) CANONICAL_STRETCH_BONUS else 0.0
                val f = 0.55 * nameHit + 0.25 * maxOf(sigP, 0.5 * sigS) + 0.2 * mus + canonical
                // 폼롤러·마사지는 스트레칭이 아니라 롤링이다 — 진짜 스트레칭 뒤로
                return if (d.isRolling) f * ROLLING_FACTOR else f
            }
            if (!explicit) {
                if (cat == 0.0 && mus == 0.0) return -1.0
                fit = 0.35 * cat + 0.35 * mus + 0.1 * purity + 0.2 * sigP
            } else {
                val sigAny = maxOf(sigP, 0.5 * sigS)
                if (c.strict && sigAny == 0.0) return -1.0
                if (!c.strict && sigAny == 0.0 && cat == 0.0 && mus == 0.0) return -1.0
                val hasMeta = c.categories.isNotEmpty() || c.muscles.isNotEmpty()
                fit = if (hasMeta) 0.6 * sigAny + 0.15 * cat + 0.15 * mus + 0.1 * purity else sigAny
            }
            if (avoid.get(d.index)) fit -= 0.5
            if (d.isStretch && !c.stretch) fit *= 0.4
            return fit.coerceAtLeast(0.02)
        }
    }

    private fun searchIntent(plan: QueryPlan, pool: List<IDoc>): List<ExerciseSearchHit> {
        val ex = exclusionSet(plan)
        val soft = softBits(plan)
        val prefer = preferBits(plan)
        val allowed: Set<Equipment>? = intentEquipment(plan)
        var cands = pool.filter { !isExcluded(it, plan, ex) }
        var unrestricted: List<IDoc>? = null
        if (allowed != null) {
            val byEquip = cands.filter { it.doc.equipment in allowed }
            if (byEquip.isEmpty()) {
                // 장비만 물은 질의("kettlebell")인데 필터 안에 그 장비가 없으면 결과 없음 — 전체를 돌려주면 무관한 운동이 쏟아진다
                if (plan.concepts.isEmpty()) return emptyList()
            } else {
                unrestricted = cands
                cands = byEquip
            }
        }
        var evals = plan.concepts.map { ConceptEval(it, plan.stretch) }
        if (plan.concepts.isEmpty() && plan.similarTo.isNotEmpty()) similarFits(plan)?.let { fits ->
            val similar = cands.filter { fits.containsKey(it.index) }
            if (similar.isNotEmpty()) return rankSorted(scoreIntent(plan, similar, emptyList(), soft, prefer, fits))
        }
        var out = scoreIntent(plan, cands, evals, soft, prefer)
        // 스트레칭·모빌리티 의도에서 장비는 "있으면 좋은" 조건이다("수건으로 어깨 풀기"의 수건은 밴드 대용일 뿐) — 장비로 거르면 결과가 너무 적을 때는 장비 조건을 풀어서 다시 찾는다
        if (unrestricted != null && out.size < SOFT_EQUIP_MIN_HITS && mobilityIntent(plan)) out = scoreIntent(plan, unrestricted, evals, soft, prefer)
        if (plan.combineAll && out.size < COMBINE_MIN_HITS && plan.concepts.none { it.id == "foamroll" }) {
            // 두 조건에 모두 맞는 운동이 적으면("가슴 웜업") 한쪽만 맞는 운동으로 채우되, 둘 다 맞는 것을 위에 둔다
            val both = out.associateBy { it.doc.id }
            out = scoreIntent(plan, cands, evals, soft, prefer, requireAll = false)
                .map { h -> if (both.containsKey(h.doc.id)) ExerciseSearchHit(h.doc, h.score + COMBINE_BOTH_BONUS) else h }
        }
        if (out.isEmpty() && evals.any { it.c.strict }) {
            // 신호 표기가 하나도 안 맞으면(카탈로그가 바뀐 경우) 부위·카테고리만으로라도 보여 준다
            val relaxed = plan.concepts.map { ConceptEval(relax(it), plan.stretch) }
            evals = relaxed
            out = scoreIntent(plan, cands, evals, soft, prefer)
        }
        if (out.isEmpty() && cands.isNotEmpty() && evals.isNotEmpty() && (allowed != null)) {
            // 장비·집 조건 안에 그 부위 운동이 없으면, 장비 조건만으로라도 보여 준다 (kettlebell 전신)
            out = scoreIntent(plan, cands, emptyList(), soft, prefer)
        }
        if (out.isEmpty() && plan.stretch) {
            // 스트레칭을 물었는데 부위 신호가 안 맞으면(전신 스트레칭) 스트레칭 전반을 인기·티어 순으로 보여 준다
            out = cands.filter { it.isStretch }.map { ExerciseSearchHit(it.doc, BAND_INTENT + 6.0 + it.rank - (if (it.isRolling) ROLLING_FALLBACK_PENALTY else 0.0)) }
        }
        return rankSorted(out)
    }

    private fun mobilityIntent(plan: QueryPlan): Boolean = plan.stretch || plan.concepts.any { it.stretch || it.id.startsWith("mob:") }

    /** 의도 질의가 말한 장비 범위: 장비를 직접 말했으면 그것, 집이면 홈 장비, 벽·의자·테이블 같은 생활 사물이면 맨몸·밴드(바벨·머신·케이블·덤벨은 "사물만 쓰는" 운동이 아니다) */
    private fun intentEquipment(plan: QueryPlan): Set<Equipment>? = when {
        plan.equipments.isNotEmpty() -> plan.equipments
        plan.home -> ExerciseSearchLexicon.homeEquipmentPreference.keys
        plan.concepts.any { it.id.startsWith("obj:") } -> OBJECT_EQUIPMENT
        else -> null
    }

    /**
     * LLM 힌트 후보에 거는 의도 장비 범위 — [intentEquipment] 와 같되, 집이라고 말하면서 사전이 장비로 읽은 말이 생활 사물 대용("수건"→밴드)일 때만 그 장비 하나로 좁히지 않고 홈 장비 전체를 허용한다.
     * 덤벨처럼 사용자가 직접 말한 일반 장비는 넓히지 않는다("집에서 덤벨 운동"의 힌트가 밴드·맨몸으로 희석되지 않게).
     * (사전의 장비 해석이 틀릴 수 있는 곳에서 LLM 의 읽기를 살리되, "집·사물만 쓴다"는 범위는 그대로 지킨다)
     */
    private fun hintIntentEquipment(plan: QueryPlan, substituteObject: Boolean): Set<Equipment>? =
        if (plan.equipments.isNotEmpty() && plan.home && substituteObject) plan.equipments + ExerciseSearchLexicon.homeEquipmentPreference.keys else intentEquipment(plan)

    private fun relax(c: QueryLexicon.Concept) = QueryLexicon.Concept(
        c.id + "~relaxed", c.muscles, c.categories, c.sig, c.sig2, c.patterns, c.avoid, false, c.stretch, c.cardio, c.nameWords
    )

    /**
     * "스쿼트 대신" 처럼 대체를 물은 질의: 제외 대상과 이름이 맞는 운동들의 대표 카테고리·동작·근육을 읽어,
     * 같은 카테고리의 다른 운동을 동작·근육이 비슷한 순으로 돌려준다. 기준 운동이 없으면 null.
     */
    private fun similarFits(plan: QueryPlan): Map<Int, Double>? {
        val ref = sigIndex.anyOf(plan.similarTo)
        val refDocs = indexed.filter { ref.get(it.index) }
        if (refDocs.isEmpty()) return null
        val category = refDocs.groupingBy { it.doc.category }.eachCount().maxByOrNull { it.value }!!.key
        val patterns = refDocs.mapNotNull { it.doc.movementPattern }.filter { it != "OTHER" }.map { patternFamily(it) }.toSet()
        val muscles = refDocs.flatMap { it.doc.muscleGroups }.groupingBy { it }.eachCount().filter { it.value * 2 >= refDocs.size }.keys
        val out = HashMap<Int, Double>()
        for (d in indexed) {
            if (ref.get(d.index) || d.doc.category != category || d.isStretch) continue
            val patternHit = if (d.doc.movementPattern?.let { patternFamily(it) } in patterns) 1.0 else 0.0
            val union = (d.doc.muscleGroups + muscles).size
            val jaccard = if (union == 0) 0.0 else d.doc.muscleGroups.count { it in muscles }.toDouble() / union
            out[d.index] = 0.3 + 0.35 * patternHit + 0.35 * jaccard
        }
        return out
    }

    /** 장비 접미사를 뗀 동작 계열(HORIZONTAL_PRESS_BARBELL → HORIZONTAL_PRESS) — 바벨 프레스의 대안으로 머신·덤벨 프레스도 같은 계열이다 */
    private fun patternFamily(p: String): String {
        for (suffix in PATTERN_EQUIPMENT_SUFFIXES) if (p.endsWith(suffix)) return p.removeSuffix(suffix)
        return p
    }

    private fun scoreIntent(plan: QueryPlan, cands: List<IDoc>, evals: List<ConceptEval>, soft: BitSet, prefer: BitSet, fits: Map<Int, Double>? = null, requireAll: Boolean = plan.combineAll): List<ExerciseSearchHit> {
        val out = ArrayList<ExerciseSearchHit>()
        for (d in cands) {
            var fit = fits?.get(d.index) ?: 1.0
            if (evals.isNotEmpty()) {
                var best = -1.0
                if (requireAll) {
                    // "하체 워밍업": 부위에도 웜업 신호에도 맞는 운동만, 둘 다 잘 맞을수록 앞에
                    var sum = 0.0
                    for (e in evals) { val f = e.fit(d); if (f < 0) { sum = -1.0; break }; sum += f }
                    if (sum >= 0) best = sum / evals.size
                } else {
                    // 자세 개념("서서", "누워서")은 부위·목표 개념에 대한 가산점이지 후보를 대신하지 않는다 — "서서 하는 유산소" 가 이름에 standing 이 든 운동 전체로 번지지 않게
                    val mains = evals.filter { !it.c.id.startsWith("pos-") }
                    if (mains.isNotEmpty() && mains.size < evals.size) {
                        for (e in mains) { val f = e.fit(d); if (f > best) best = f }
                        if (best >= 0) {
                            val pf = evals.filter { it.c.id.startsWith("pos-") }.maxOf { it.fit(d) }
                            if (pf > 0) best += POSTURE_BONUS * pf
                        }
                    } else for (e in evals) { val f = e.fit(d); if (f > best) best = f }
                }
                if (best < 0) continue
                fit = best
            }
            var score = BAND_INTENT + 25.0 * fit + d.rank - 0.5 * (d.wordCount - 3).coerceIn(0, 4)
            if ((plan.home || plan.concepts.any { it.id.startsWith("obj:") }) && plan.equipments.isEmpty()) {
                score += HOME_PREF_WEIGHT * (ExerciseSearchLexicon.homeEquipmentPreference[d.doc.equipment] ?: 0.0)
            }
            score += levelAdjust(d.doc, plan.level) + injuryAdjust(d, plan, soft, prefer)
            out.add(ExerciseSearchHit(d.doc, score))
        }
        return out
    }

    // ── 이름 검색 ────────────────────────────────────────────────────────────────────────────

    private fun searchName(plan: QueryPlan, rawPool: List<IDoc>, budget: Budget): List<ExerciseSearchHit> {
        val seeds = plan.seeds.filter { it.isNotEmpty() }
        if (seeds.isEmpty()) return emptyList()
        if (seeds.size > 1) {
            // 이름에도 쓰이는 군더더기를 남긴 후보로 확실한 일치가 있으면 그것만, 없을 때만 군더더기를 뗀 후보까지 본다
            val primary = searchNameWith(plan, rawPool, budget, seeds.take(1))
            if (primary.any { it.score >= BAND_TOKEN_SUB }) return primary
        }
        return searchNameWith(plan, rawPool, budget, seeds)
    }

    private fun searchNameWith(plan: QueryPlan, rawPool: List<IDoc>, budget: Budget, seeds: List<List<String>>): List<ExerciseSearchHit> {
        val ex = if (plan.hasExclusions) exclusionSet(plan) else null
        val pool = if (ex != null) rawPool.filter { !isExcluded(it, plan, ex) } else rawPool
        val soft = if (plan.injuries.isNotEmpty()) softBits(plan) else null
        val prefer = if (plan.injuries.isNotEmpty()) preferBits(plan) else null
        val regressBits = if (plan.regress) sigIndex.anyOf(QueryLexicon.regressionKeys) else null

        val flat = SearchText.strictKey(seeds.first())
        if (isShort(flat) && synonymGroups(flat) == null) return searchShort(flat, pool)

        val (variants, equipPref0) = buildVariants(seeds)
        // 질의(동의어 변형 포함)가 말하는 동작 단어 전체
        val queryHeads = BitSet().also { b -> variants.forEach { b.or(it.heads) } }
        val queryRegions = BitSet().also { b -> variants.forEach { b.or(it.regions) } }
        val equipPref = HashSet(equipPref0)
        if (plan.highReps) equipPref.add(Equipment.BODYWEIGHT)
        // 의자·벽·평행봉 같은 생활 사물을 말했으면 그 사물로 하는 맨몸 운동이 먼저다(케이블·머신 이름에 chair 가 들어 있어도 뒤로)
        if (seeds.first().any { it in HOME_OBJECT_WORDS }) { equipPref.add(Equipment.BODYWEIGHT); equipPref.add(Equipment.OTHER) }
        val muscleHints = muscleHintsOf(seeds.first())
        val queryStrict = flat
        val unwantedModifiers = ExerciseSearchLexicon.modifierGroups.filter { g -> g.none { queryStrict.contains(it) } }
        val results = HashMap<Long, Double>()
        val docById = HashMap<Long, IDoc>()

        fun consider(d: IDoc, base: Double, nameUsed: NameKey?) {
            var s = base + d.rank - SIMPLICITY_WEIGHT * d.wordCount
            // 질의에 없는 수식어(어시스트, 한 팔)가 붙은 변형은 기본 동작 뒤로 미룬다
            if (unwantedModifiers.any { g -> d.names.any { n -> g.any { n.strict.contains(it) } } }) s -= MODIFIER_PENALTY
            if (equipPref.isNotEmpty() && d.doc.equipment in equipPref) s += EQUIP_PREF_BOOST
            if (nameUsed != null) {
                s -= headPenalty(nameUsed, queryHeads)
                // 질의가 말한 부위와 다른 부위의 이름(bicep curl → leg curl)은 뒤로
                if (!queryRegions.isEmpty && !nameUsed.regions.isEmpty && !queryRegions.intersects(nameUsed.regions)) s -= REGION_MISMATCH_PENALTY
            }
            if (plan.numbers.isNotEmpty() && plan.numbers.any { num -> d.names.any { it.strict.contains(num) } }) s += NUMBER_BOOST
            s += levelAdjust(d.doc, plan.level)
            if (regressBits != null && regressBits.get(d.index)) s += REGRESS_BOOST
            if (soft != null && prefer != null) s += injuryAdjust(d, plan, soft, prefer)
            val cur = results[d.doc.id]
            if (cur == null || s > cur) { results[d.doc.id] = s; docById[d.doc.id] = d }
        }

        var solid = 0
        var counter = 0
        for (d in pool) {
            var best = -1.0
            var bestName: NameKey? = null
            for (v in variants) for (n in d.names) {
                val s = matchTokens(v, n, muscleHints, d.doc)
                if (s > best) { best = s; bestName = n }
            }
            if (best >= 0) {
                consider(d, best, bestName)
                if (best >= BAND_LOOSE) solid++
            }
            if ((++counter and 127) == 0 && budget.over()) break
        }

        val overBudget = { budget.over() }

        // 확실한 매칭이 적으면 퍼지(오타 허용) 보강
        if (solid < FUZZY_TRIGGER && !overBudget()) {
            for (d in pool) {
                if ((results[d.doc.id] ?: 0.0) >= BAND_LOOSE) continue
                var best = -1.0
                for (v in variants) {
                    if (v.penalty > 0) continue  // 퍼지는 원문 단어열에만 — 동의어 변형까지 오타 허용하면 무관한 운동이 걸린다
                    val core = v.coreLoose
                    if (core.isEmpty() || (isLatin(core) && core.length <= FUZZY_MIN_LATIN)) continue  // HIIT 가 hip 으로, frog 가 front 로 번지는 것 방지
                    val k = SearchText.allowedTypos(core.length)
                    if (k == 0) continue
                    for (n in d.names) {
                        if (missingChars(core, n.bloom) > k) continue
                        if (conflicts(v, n)) continue
                        val dist = SearchText.approxSubstring(core, n.loose, k)
                        if (dist <= k) {  // dist 0 은 단어 경계를 넘는 우연 일치이므로 오타 1개와 동급
                            val s = BAND_FUZZY - maxOf(dist, 1) * 6.0 - v.penalty
                            if (s > best) best = s
                        }
                    }
                }
                if (best >= 0) consider(d, best, null)
                if ((++counter and 127) == 0 && overBudget()) break
            }
        }

        // 그래도 결과가 거의 없으면 문자 바이그램 유사도(Dice)로 보강
        if (results.size < BIGRAM_TRIGGER && !overBudget()) {
            val base = variants.first()
            val qBigrams = bigrams(base.coreLoose)
            if (base.coreLoose.length >= BIGRAM_MIN_LEN && qBigrams.size >= 3) {
                for (d in pool) {
                    if (results.containsKey(d.doc.id)) continue
                    var best = 0.0
                    for (n in d.names) {
                        if (conflicts(base, n)) continue
                        val nb = bigrams(n.loose)
                        val common = qBigrams.count { it in nb }
                        val dice = 2.0 * common / (qBigrams.size + nb.size)
                        if (common >= 3 && dice > best) best = dice
                    }
                    if (best >= BIGRAM_MIN_DICE) consider(d, BAND_BIGRAM + 20.0 * best, null)
                }
            }
        }

        val out = results.map { (id, s) -> ExerciseSearchHit(docById.getValue(id).doc, s) }
        return rankSorted(out)
    }

    /** 라틴 2글자 이하, 그 밖의 문자 1글자 이하 — 정보가 너무 적어 퍼지·부분 일치를 쓰면 무관한 결과가 나온다 */
    private fun isShort(flat: String): Boolean = flat.length <= 1 || (flat.length == 2 && isLatin(flat))

    /** 짧은 질의: 이름의 단어와 정확히 같거나 단어가 그 글자로 시작할 때만. 퍼지·부분 일치는 쓰지 않는다. */
    private fun searchShort(q: String, pool: List<IDoc>): List<ExerciseSearchHit> {
        val out = ArrayList<ExerciseSearchHit>()
        for (d in pool) {
            var best = -1.0
            for (n in d.names) {
                var s = -1.0
                for (st in n.starts) {
                    if (!n.strict.startsWith(q, st)) continue
                    val wordEnd = st + q.length
                    val exactWord = wordEnd == n.strict.length || wordEnd in n.starts
                    if (q.length == 1 && !exactWord) continue  // 한 글자는 단어가 정확히 같을 때만
                    s = maxOf(s, if (exactWord) BAND_TOKEN_WB else BAND_TOKEN_SUB)
                }
                // 2글자 CJK 단어("ヨガ")는 이름 안 어디에서든 글자 그대로 나오면 인정 (한 글자는 단어가 정확히 같을 때만)
                if (s < 0 && q.length == 2 && !isLatin(q) && n.strict.contains(q)) s = BAND_TOKEN_SUB - 10.0
                if (s > best) best = s
            }
            if (best >= 0) out.add(ExerciseSearchHit(d.doc, best + d.rank))
        }
        return rankSorted(out)
    }

    private fun missingChars(pattern: String, bloom: Long): Int {
        var m = 0
        for (c in pattern) if ((bloom shr (c.code and 63)) and 1L == 0L) m++
        return m
    }

    private fun bigrams(key: String): Set<String> =
        if (key.length < 2) emptySet() else (0 until key.length - 1).map { key.substring(it, it + 2) }.toSet()

    /**
     * 이름에는 있지만 질의 변형에는 없는 동작 단어가 붙은 이름은 다른 운동일 가능성이 높다 — 기본 동작 뒤로 미룬다.
     * 질의에 동작 단어가 전혀 없을 때(jump rope, smith, 줄넘기) 그런 이름은 혼합 동작(jump rope row)이라 크게 미룬다.
     */
    private fun headPenalty(n: NameKey, queryHeads: BitSet): Double {
        var extra = 0
        var i = n.heads.nextSetBit(0)
        while (i >= 0) { if (!queryHeads.get(i)) extra++; i = n.heads.nextSetBit(i + 1) }
        if (extra == 0) return 0.0
        return if (queryHeads.isEmpty) HYBRID_PENALTY else EXTRA_HEAD_PENALTY * minOf(extra, 2)
    }

    /** 부분 일치 중 다른 운동으로 판단되는 경우: 질의의 동작/부위 단어가 이름에 없고 이름에는 다른 동작/부위 단어가 있다 */
    private fun conflicts(v: Variant, n: NameKey): Boolean {
        if (!v.heads.isEmpty) {
            var missing = false
            var i = v.heads.nextSetBit(0)
            while (i >= 0) { if (!n.heads.get(i)) { missing = true; break }; i = v.heads.nextSetBit(i + 1) }
            if (missing) {
                var j = n.heads.nextSetBit(0)
                while (j >= 0) { if (!v.heads.get(j)) return true; j = n.heads.nextSetBit(j + 1) }
            }
        }
        if (!v.regions.isEmpty && !n.regions.isEmpty && !v.regions.intersects(n.regions)) return true
        return false
    }

    private fun matchTokens(v: Variant, n: NameKey, muscleHints: MuscleHint, doc: ExerciseSearchDoc): Double {
        if (v.strict.isEmpty()) return -1.0
        if (n.strict == v.strict) return v.exactBand - v.penalty
        val tokens = v.words
        var allStrict = true
        var allBoundary = true
        var exactWords = 0
        var matchedLen = 0
        var totalLen = 0
        for (t in tokens) {
            totalLen += t.length
            val q = occurrenceQuality(n, t)
            if (q == OCC_NONE) { allStrict = false; continue }
            if (q == OCC_MID_WORD) allBoundary = false
            // 부분 일치에서는 단어 중간 일치와 라틴 접두 일치를 일치로 치지 않는다 — sled push ↛ pushdown
            else if (q != OCC_WORD || !isLatin(t)) matchedLen += t.length
            if (q == OCC_EXACT && isLatin(t)) exactWords++
        }
        if (allStrict) {
            var s = if (allBoundary) BAND_TOKEN_WB else BAND_TOKEN_SUB
            // 이름의 단어와 정확히 같은 토큰(row ↔ rowing 구분)은 접두 일치보다 앞선다
            s += EXACT_WORD_BONUS * exactWords / tokens.size
            if (n.strict.contains(v.strict)) s += 2.0
            // 질의가 이름의 큰 부분을 차지할수록(= 더 구체적으로 일치) 가산
            s += COVERAGE_WEIGHT * minOf(1.0, v.strict.length.toDouble() / n.strict.length)
            return s - v.penalty
        }
        // 표기 변이(자모 접기, 장음/촉음 제거) — 접기로 달라진 표기가 있을 때만
        var allLoose = v.loose != v.strict
        if (allLoose) for (t in v.looseWords) if (t.isNotEmpty() && !n.loose.contains(t)) { allLoose = false; break }
        if (allLoose) {
            var s = BAND_LOOSE + COVERAGE_WEIGHT * minOf(1.0, v.loose.length.toDouble() / n.loose.length)
            if (n.loose.contains(v.loose)) s += 2.0
            return s - v.penalty
        }
        // 일부 토큰만 일치 (예: "삼두 푸시다운" 에서 "삼두" 가 이름에 없는 경우)
        if (tokens.size >= 2 && matchedLen > 0 && matchedLen.toDouble() / totalLen >= PARTIAL_MIN_FRACTION) {
            if (conflicts(v, n)) return -1.0
            // 일반 단어(장비·수식어) 하나만 맞은 이름은 일치로 치지 않는다
            if (tokens.none { t -> t !in QueryLexicon.genericWords && t !in v.regionWords && occurrenceQuality(n, t).let { q -> q == OCC_EXACT || (q == OCC_WORD && !isLatin(t)) } }) return -1.0
            // 질의의 부위 단어가 이름에 없으면, 그 운동이 정말 그 부위의 것일 때만 인정한다 (leg extension ↛ triceps extension)
            if (!v.regions.isEmpty && !n.regions.intersects(v.regions) && !muscleHints.matches(doc)) return -1.0
            var s = BAND_PARTIAL + 20.0 * matchedLen / totalLen
            if (muscleHints.matches(doc)) s += 8.0
            return s - v.penalty
        }
        return -1.0
    }

    private fun isLatin(t: String) = t.isNotEmpty() && t[0].code < 0x250

    private fun isKana(t: String) = t.isNotEmpty() && t.all { it.code in 0x3041..0x30FF }

    /**
     * 토큰이 이름 키에서 나타나는 방식의 최선 품질.
     * 라틴 토큰은 단어 시작에서 일치해야 경계 일치(OCC_WORD)이고, 단어 중간 일치(OCC_MID_WORD)는 한 단계 낮다.
     * 단어 경계를 넘어 다음 단어 중간에서 끝나는 일치("squatt" ↔ "squat to")는 우연한 일치로 보고 버린다.
     */
    private fun occurrenceQuality(n: NameKey, t: String): Int {
        var from = 0
        var best = OCC_NONE
        val latin = isLatin(t)
        while (true) {
            val i = n.strict.indexOf(t, from)
            if (i < 0) return best
            val e = i + t.length
            if (!latin) {
                // 한글 1~2음절 토큰이 다른 단어 한가운데에서 일치하면 우연 일치(예: 컬 ⊂ 스컬)로 본다
                val hangul = SearchText.isHangulSyllable(t[0])
                val startsWord = i in n.starts
                val endsWord = e == n.strict.length || n.wordOf[e] != n.wordOf[e - 1]
                var q = if (!hangul || t.length >= 3 || startsWord || endsWord) OCC_WORD else OCC_MID_WORD
                // 짧은 가나 토큰이 더 긴 단어의 앞부분일 뿐이면(ロー ⊂ ロールアウト) 약한 일치 — 합성어의 끝에 붙은 경우(フルスクワット)는 정상
                if (q == OCC_WORD && !hangul && t.length <= 4 && startsWord && !endsWord && isKana(t)) q = OCC_MID_WORD
                best = maxOf(best, q)
                if (best == OCC_EXACT) return best
                from = i + 1
                continue
            }
            val startsWord = i in n.starts
            val crossesUnaligned = n.wordOf[e - 1] != n.wordOf[i] && e < n.strict.length && n.wordOf[e] == n.wordOf[e - 1]
            // 3글자 이하 라틴 토큰은 단어 중간에서 일치하지 않는다 (hop ⊄ chop)
            if (!crossesUnaligned && (startsWord || t.length > 3)) {
                val endsWord = e == n.strict.length || e in n.starts
                best = maxOf(best, if (startsWord && endsWord) OCC_EXACT else if (startsWord) OCC_WORD else OCC_MID_WORD)
                if (best == OCC_EXACT) return best
            }
            from = i + 1
        }
    }

    // ── 질의 변형(오타 교정/동의어/장비 분리) ────────────────────────────────────────────────

    private fun muscleHintsOf(words: List<String>): MuscleHint {
        val m = HashSet<MuscleGroup>()
        val c = HashSet<ExerciseCategory>()
        for (w in words) ExerciseSearchLexicon.muscleTerms[w]?.let { m.addAll(it.muscles); c.addAll(it.categories) }
        return MuscleHint(m, c)
    }

    /** 사전 단어(동의어 트리거·장비)로만 이루어진 붙여 쓴 CJK 복합어("ワンハンドロー")를 사전 단어 단위로 쪼갠다. 못 쪼개면 null. */
    private fun splitCompounds(words: List<String>): List<String>? {
        val lex = ExerciseSearchLexicon
        fun known(k: String) = k in synonymTable.index || k in lex.equipmentTerms || SearchText.looseKey(k) in synonymTable.looseIndex
        fun split(w: String, depth: Int): List<String>? {
            if (depth > 3 || w.length < 4) return null
            for (len in w.length - 2 downTo 2) {
                val head = w.substring(0, len)
                val tail = w.substring(len)
                if (!known(head)) continue
                if (known(tail)) return listOf(head, tail)
                split(tail, depth + 1)?.let { return listOf(head) + it }
            }
            return null
        }
        var changed = false
        val out = ArrayList<String>()
        for (w in words) {
            val parts = if (!isLatin(w) && !known(w)) split(w, 0) else null
            if (parts != null) { out.addAll(parts); changed = true } else out.add(w)
        }
        return if (changed) out else null
    }

    private fun synonymGroups(key: String): List<ExerciseSearchLexicon.Synonym>? =
        synonymTable.index[key] ?: synonymTable.looseIndex[SearchText.looseKey(key)]

    /** 어휘에 없는 단어를 가장 가까운 어휘 단어로 바꾼 단어열들 (장비 단어가 맞아도 운동 단어의 오타를 고친다) */
    private fun typoCorrections(words: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        val best = words.toMutableList()
        var changed = false
        for ((i, w) in words.withIndex()) {
            if (w.any { it.isDigit() }) continue
            val cands = vocabulary.nearest(w)
            if (cands.isEmpty()) continue
            val single = words.toMutableList(); single[i] = cands.first()
            out.add(single)
            if (cands.size > 1) { val alt = words.toMutableList(); alt[i] = cands[1]; out.add(alt) }
            best[i] = cands.first(); changed = true
        }
        if (changed && best !in out) out.add(0, best)
        return out.take(MAX_TYPO_VARIANTS)
    }

    private fun buildVariants(seeds: List<List<String>>): Pair<List<Variant>, Set<Equipment>> {
        val all = LinkedHashMap<String, Variant>()
        var cap = MAX_VARIANTS_TOTAL
        fun put(v: Variant): Boolean = all.size < cap && all.putIfAbsent(v.strict + "|" + v.words.size, v) == null

        val bases = ArrayList<Variant>()
        for ((i, sd) in seeds.withIndex()) { val v = Variant(sd, if (i == 0) 0.0 else SEED_ALT_PENALTY, if (i == 0) BAND_EXACT else BAND_EXACT_VARIANT); if (put(v)) bases.add(v) }
        // 오타 교정 변형 (정확 일치 band 바로 아래)
        for (b in bases.toList()) for (c in typoCorrections(b.words)) {
            val v = Variant(c, TYPO_PENALTY, BAND_EXACT_VARIANT)
            put(v)
        }

        val seedVars = ArrayList(all.values)
        // 0) 붙여 쓴 복합어 분해
        for (sv in seedVars.toList()) splitCompounds(sv.words)?.let { parts ->
            put(Variant(parts, sv.penalty + SYN_PENALTY, BAND_EXACT_VARIANT))
        }

        // 1) 동의어 확장 (단어 run 단위 치환)
        val seedsAll = ArrayList(all.values)
        val syn = ArrayList<Variant>()
        for (seed in seedsAll) {
            val sw = seed.words
            for (i in sw.indices) {
                val sb = StringBuilder()
                for (j in i until minOf(sw.size, i + MAX_SYN_RUN)) {
                    sb.append(sw[j])
                    val groups = synonymGroups(sb.toString()) ?: continue
                    for (g in groups) for (exp in g.expansions) {
                        if (SearchText.strictKey(exp) == sb.toString()) continue
                        val pen = seed.penalty + SYN_PENALTY - (if (g.strong) STRONG_SYN_BONUS + SYN_PENALTY else 0.0)
                        syn.add(Variant(sw.subList(0, i) + exp + sw.subList(j + 1, sw.size), pen, BAND_EXACT_VARIANT))
                    }
                }
            }
        }
        syn.take(MAX_SYN_VARIANTS).forEach { put(it) }
        // 서로 다른 자리의 동의어가 둘 이상 겹치는 질의("원핸드 로우")는 조합까지 만든다
        for (seed in seedsAll) if (seed.words.size <= MAX_COMBO_WORDS) combineSynonyms(seed).forEach { put(it) }

        // 2) 장비 단어 분리 (이름에 장비가 안 들어 있는 운동도 장비로 우선 정렬) — 변형 수 상한과 별도 예산을 쓴다
        cap += MAX_STRIP_VARIANTS
        val equipPref = HashSet<Equipment>()
        val extra = ArrayList<Variant>()
        for (v in all.values.toList()) {
            val stripped = stripEquipment(v.words, equipPref, allowFuzzy = bases.any { it === v })
            if (stripped != null && stripped.isNotEmpty()) {
                extra.add(Variant(stripped, v.penalty + EQUIP_STRIP_PENALTY, BAND_EXACT_VARIANT - 20.0))
            }
        }
        extra.forEach { put(it) }
        return all.values.toList() to equipPref
    }

    /** 단어열을 가장 긴 동의어 run 으로 나눠 각 자리의 대안을 곱한다 (2개 자리 이상에 동의어가 있을 때만) */
    private fun combineSynonyms(seed: Variant): List<Variant> {
        val w = seed.words
        val slots = ArrayList<List<List<String>>>()
        var i = 0
        var synSlots = 0
        while (i < w.size) {
            var hit: List<ExerciseSearchLexicon.Synonym>? = null
            var len = 0
            for (l in minOf(MAX_SYN_RUN, w.size - i) downTo 1) {
                val g = synonymGroups(w.subList(i, i + l).joinToString("")) ?: continue
                hit = g; len = l; break
            }
            if (hit == null) { slots.add(listOf(listOf(w[i]))); i++; continue }
            val orig = w.subList(i, i + len).toList()
            val alts = LinkedHashMap<String, List<String>>()
            alts[SearchText.strictKey(orig)] = orig
            hit.flatMap { it.expansions }.forEach { alts.putIfAbsent(SearchText.strictKey(it), it) }
            slots.add(alts.values.toList())
            if (alts.size > 1) synSlots++
            i += len
        }
        if (synSlots < 2) return emptyList()
        var combos: List<List<String>> = listOf(emptyList())
        for (slot in slots) {
            combos = combos.flatMap { c -> slot.map { c + it } }.take(MAX_COMBOS)
        }
        return combos.filter { it != w }.map { Variant(it, seed.penalty + SYN_PENALTY, BAND_EXACT_VARIANT) }
    }

    /** 장비 단어(덤벨, DB, ダンベル...)를 떼어 낸 단어 목록. 장비를 못 찾으면 null. */
    private fun stripEquipment(words: List<String>, collect: MutableSet<Equipment>, allowFuzzy: Boolean): List<String>? {
        val lex = ExerciseSearchLexicon.equipmentTerms
        val out = ArrayList<String>()
        var found = false
        var i = 0
        while (i < words.size) {
            var matched = false
            for (len in minOf(MAX_SYN_RUN, words.size - i) downTo 1) {
                val key = words.subList(i, i + len).joinToString("")
                val eq = lex[key]
                if (eq != null) { collect.addAll(eq); found = true; matched = true; i += len; break }
            }
            if (matched) continue
            // 장비 단어 오타 ("덥벨", "dumbell") — 자모 기준 편집거리 1
            val w = words[i]
            val wl = SearchText.looseKey(w)
            if (allowFuzzy && wl.length >= 6) {
                val near = ExerciseSearchLexicon.fuzzyEquipmentTerms.firstOrNull { (term, _) ->
                    kotlin.math.abs(term.length - wl.length) <= 1 && SearchText.approxSubstring(term, wl, 1) <= 1 &&
                        SearchText.approxSubstring(wl, term, 1) <= 1
                }
                if (near != null) { collect.addAll(near.second); found = true; i++; continue }
            }
            // 붙여 쓴 CJK ("덤벨컬", "ダンベルカール") — 첫 단어 접두 장비
            if (i == 0) {
                val first = words[0]
                val hit = lex.entries
                    .filter { !isLatin(it.key) && it.key.length >= 2 && first.startsWith(it.key) && first.length - it.key.length >= 2 }
                    .maxByOrNull { it.key.length }
                if (hit != null) {
                    collect.addAll(hit.value); found = true
                    out.add(first.substring(hit.key.length)); i++
                    continue
                }
            }
            out.add(words[i]); i++
        }
        return if (found) out else null
    }

    private fun rankSorted(hits: List<ExerciseSearchHit>): List<ExerciseSearchHit> =
        hits.sortedWith(compareByDescending<ExerciseSearchHit> { it.score }.thenBy { it.doc.id })

    init {
        warmUp()  // 반드시 모든 프로퍼티가 초기화된 뒤(클래스 맨 끝)에 돌린다
    }

    companion object {
        // band 간격(20)은 장비 우선 가중치(15)·타이브레이커(<=9)보다 크게 유지
        private const val SOFT_EQUIP_MIN_HITS = 6
        /** 장비 자리에 쓰는 생활 사물(밴드 대용) 표기 — 사전이 장비로 읽어도 LLM 이 다른 홈 장비를 고를 수 있다 */
        private val SUBSTITUTE_OBJECT_KEYS: List<String> = ExerciseSearchLexiconRound3.TOWEL.split("|").map { SearchText.strictKey(it) }.filter { it.isNotEmpty() }
        /** 목표 말투에서 언급된 운동 자체를 목표 부위 운동 뒤로 미루는 점수 */
        private const val GOAL_LIFT_PENALTY = 30.0
        /** 보조·지원 목표 말투 표지(ja·ko·en) — 부위 개념·언급된 운동과 함께 있을 때만 쓴다 */
        private val GOAL_PHRASES = Regex("補助|補強|補完|サポート|アシスト|支える|ほじょ|さぽーと|보조|보완|보강|서포트|받쳐|\\b(?:support|supports|supporting|assist|assisting|assistance|accessory|accessories|supplement|supplemental|complement|complementary)\\b")
        /** 생활 사물(벽·의자·테이블…)만 쓰는 운동에 허용하는 장비 */
        internal val OBJECT_EQUIPMENT: Set<Equipment> = setOf(Equipment.BODYWEIGHT, Equipment.OTHER, Equipment.RESISTANCE_BAND)
        const val BAND_EXACT = 200.0
        const val BAND_EXACT_VARIANT = 170.0
        const val BAND_TOKEN_WB = 160.0
        const val BAND_TOKEN_SUB = 140.0
        const val BAND_CHOSEONG = 130.0
        const val BAND_LOOSE = 120.0
        const val BAND_PARTIAL = 100.0
        const val BAND_INTENT = 100.0
        const val BAND_FUZZY = 90.0
        const val BAND_BIGRAM = 50.0
        /** 설명문 검색으로만 찾은 운동의 기준 점수 — 퍼지(90)와 부분 일치(100) 사이 */
        const val BAND_DESC = 96.0
        private const val DESC_WEIGHT = 50.0
        private const val WEAK_DEMOTE = 25.0
        private const val DESC_MIN_COVERAGE = 0.45
        private const val DESC_MIN_COVERAGE_WITH_NAME = 0.62
        private const val STRONG_DESC_COVERAGE = 0.6

        private const val COVERAGE_WEIGHT = 1.0
        private const val SIMPLICITY_WEIGHT = 0.15
        private const val BIGRAM_TRIGGER = 5
        private const val BIGRAM_MIN_DICE = 0.5
        private const val BIGRAM_MIN_LEN = 6
        private const val OCC_NONE = 0
        private const val OCC_MID_WORD = 1
        private const val OCC_WORD = 2
        private const val OCC_EXACT = 3
        private const val EXACT_WORD_BONUS = 4.0

        private val PATTERN_EQUIPMENT_SUFFIXES = listOf("_BARBELL", "_DUMBBELL", "_MACHINE", "_CABLE")
        private val HOME_OBJECT_WORDS: Set<String> = listOf("椅子", "いす", "イス", "의자", "chair", "壁", "かべ", "벽", "wall", "平行棒", "평행봉", "ベッド", "침대", "bed", "ソファ", "소파", "sofa", "couch", "階段", "계단", "stairs", "table", "테이블", "机", "책상", "desk")
            .map { SearchText.strictKey(it) }.toSet()
        private const val POSTURE_BONUS = 0.15
        private const val ROLLING_FACTOR = 0.5
        private const val COMBINE_MIN_HITS = 8
        private const val COMBINE_BOTH_BONUS = 12.0
        private const val CANONICAL_STRETCH_BONUS = 0.3
        private const val ROLLING_FALLBACK_PENALTY = 8.0
        private const val SYN_PENALTY = 2.0
        private const val TYPO_PENALTY = 3.0
        private const val SEED_ALT_PENALTY = 12.0
        private const val STRONG_SYN_BONUS = 6.0
        private const val MODIFIER_PENALTY = 6.0
        private const val EQUIP_STRIP_PENALTY = 2.0
        private const val EQUIP_PREF_BOOST = 15.0
        private const val EXTRA_HEAD_PENALTY = 6.0
        private const val HYBRID_PENALTY = 60.0
        private const val REGION_MISMATCH_PENALTY = 10.0
        private const val NUMBER_BOOST = 1.5
        private const val HOME_PREF_WEIGHT = 12.0
        private const val LEVEL_ADJUST = 5.0
        private const val REGRESS_BOOST = 14.0
        private const val INJURY_SOFT_PENALTY = 14.0
        private const val INJURY_PREFER_BONUS = 10.0
        private const val FUZZY_TRIGGER = 10
        private const val FUZZY_MIN_LATIN = 4
        private const val PARTIAL_MIN_FRACTION = 0.4
        private const val MAX_VARIANTS_TOTAL = 28
        private const val MAX_STRIP_VARIANTS = 14
        private const val MAX_SYN_VARIANTS = 16
        private const val MAX_TYPO_VARIANTS = 4
        private const val MAX_COMBOS = 40
        private const val MAX_COMBO_WORDS = 6
        private const val MAX_SYN_RUN = 4
        private const val CACHE_SIZE = 512
        private val WARM_QUERIES = listOf("スクワット", "벤치프레스", "bench press", "背中を鍛えたい", "무릎 안 좋을 때 하체운동", "bad knees leg workout", "without deadlifts", "덤벨 컬", "유산소", "ヒップアップ", "うつぶせで膝を曲げるマシン", "tight hamstrings", "스쿼트 대신", "raise both arms out to the sides")
        /** 한 질의의 후보 점수 계산 예산 — 넘으면 비싼 보강 단계(퍼지·바이그램)를 건너뛴다 */
        const val DEFAULT_BUDGET_NANOS = 40_000_000L
    }
}

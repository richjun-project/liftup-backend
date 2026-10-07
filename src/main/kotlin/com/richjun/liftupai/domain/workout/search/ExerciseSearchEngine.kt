package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.engine.IDoc
import com.richjun.liftupai.domain.workout.search.engine.Mode
import com.richjun.liftupai.domain.workout.search.engine.NameKey
import com.richjun.liftupai.domain.workout.search.engine.QueryAnalyzer
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon
import com.richjun.liftupai.domain.workout.search.engine.QueryPlan
import com.richjun.liftupai.domain.workout.search.engine.SigIndex
import com.richjun.liftupai.domain.workout.search.engine.SigKey
import com.richjun.liftupai.domain.workout.search.engine.Vocabulary
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
    val difficulty: Int
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
 * 브라우즈(질의 없음)는 티어 → 인기도 → 로컬라이즈 이름 순.
 *
 * 입력은 상한(160자·14단어)으로 묶고, 같은 질의는 작은 LRU 에 캐시한다. 그래서 어떤 입력도 비용이 일정 범위 안이다.
 *
 * @param extraSynonyms 런타임 동의어 줄("트리거|트리거 => 확장|확장", `=>>` 는 강한 별칭). 내장 사전에 더해진다. 형식이 틀린 줄은 무시한다.
 */
class ExerciseSearchEngine(
    docs: Collection<ExerciseSearchDoc>,
    extraSynonyms: List<String> = emptyList()
) {

    private class Variant(
        val words: List<String>,
        val penalty: Double,
        val exactBand: Double
    ) {
        val strict: String = SearchText.strictKey(words)
        val loose: String = SearchText.looseKey(strict)
        val looseWords: List<String> = words.map { SearchText.looseKey(it) }

        /** 일반 단어(장비·수식어·stretch)를 뺀 핵심 키 — 퍼지·바이그램은 이것으로 비교한다(frog stretch 가 모든 stretch 에 걸리지 않게) */
        val coreLoose: String = SearchText.looseKey(SearchText.strictKey(words.filter { it !in QueryLexicon.genericWords }))

        /** 이 변형이 말하는 동작 단어(머리 단어)와 부위 묶음 */
        val heads: BitSet = BitSet().also { b ->
            // 마지막 단어가 동작 단어를 치다 만 것("face pul")이어도 그 동작을 말한 것으로 본다
            val tail = words.lastOrNull().orEmpty()
            QueryLexicon.headWords.forEachIndexed { i, k -> if (strict.contains(k.key) || (tail.length >= 3 && k.key.length > tail.length && k.key.contains(tail))) b.set(i) }
        }
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
    private val vocabulary = Vocabulary(docs.map { it.names.values.filter { n -> n.isNotBlank() } }, synonymTable)
    private val analyzer = QueryAnalyzer(vocabulary, synonymTable)

    /** 개념 → 신호 일치 비트셋 (개념은 정적이라 한 번만 계산) */
    private val conceptBits = ConcurrentHashMap<String, BitSet>()

    /** 질의 결과 LRU — 같은 질의를 페이지마다 다시 계산하지 않는다 */
    private val cache = object : LinkedHashMap<String, List<ExerciseSearchHit>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExerciseSearchHit>>?): Boolean = size > CACHE_SIZE
    }

    val size: Int get() = indexed.size

    init {
        warmUp()
    }

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
    ): List<ExerciseSearchHit> {
        val raw = query.orEmpty()
        val norm = if (raw.isBlank()) "" else SearchText.normalize(raw).trim()
        val loc = locale?.lowercase()?.substringBefore('-').orEmpty()
        val cacheKey = buildString {
            append(norm).append('\u0000').append(category?.name).append('\u0000')
            equipments?.map { it.name }?.sorted()?.joinTo(this, ",")
            append('\u0000').append(if (norm.isEmpty()) loc else "")
        }
        synchronized(cache) { cache[cacheKey] }?.let { return ArrayList(it) }

        val result = compute(raw, norm, category, equipments, locale)
        synchronized(cache) { cache[cacheKey] = result }
        return ArrayList(result)
    }

    /** 브라우즈 순서: 티어 → 인기도 → 로컬라이즈 이름 */
    fun browse(
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): List<ExerciseSearchHit> = browseSorted(filtered(category, equipments), locale)

    /** 질의 해석 결과(진단·테스트용) */
    internal fun analyze(query: String): QueryPlan = analyzer.analyze(SearchText.tokenize(query)).plan

    internal fun nearestDebug(word: String): List<String> = vocabulary.nearest(SearchText.words(word).firstOrNull().orEmpty())

    /** 질의 해석을 사람이 읽는 문자열로(진단용): 분해된 조각과 계획 */
    internal fun explain(query: String): String {
        val tok = SearchText.tokenize(query)
        val a = analyzer.analyze(tok)
        val p = a.plan
        val segs = a.segs.joinToString(" ") { sg ->
            val text = tok.strict.substring(sg.s, sg.e)
            if (sg.unknown) "?$text" else if (sg.isNum) "#$text" else text + "/" + sg.terms!!.joinToString("+") { it.role.name.take(4) }
        }
        return "mode=${p.mode} segs=[$segs] concepts=${p.concepts.map { it.id }} equip=${p.equipments} home=${p.home} stretch=${p.stretch} level=${p.level} " +
            "exEquip=${p.excludeEquip} exKeys=${p.excludeKeys.map { it.key }} injuries=${p.injuries} nums=${p.numbers} seeds=${p.seeds}" +
            (if (p.mode == Mode.NAME && p.seeds.isNotEmpty()) "\n   variants=" + buildVariants(p.seeds).first.joinToString(" ") { "${it.words}@${it.penalty}" } else "")
    }

    private fun compute(
        raw: String,
        norm: String,
        category: ExerciseCategory?,
        equipments: Set<Equipment>?,
        locale: String?
    ): List<ExerciseSearchHit> {
        val pool = filtered(category, equipments)
        if (norm.isEmpty()) return browseSorted(pool, locale)
        val deadline = System.nanoTime() + BUDGET_NANOS

        val tok = SearchText.tokenize(raw)
        // 글자·숫자가 하나도 없는(기호·이모지뿐인) 질의는 결과 없음
        if (tok.words.isEmpty()) return emptyList()
        if (SearchText.isChoseongQuery(norm)) return searchChoseong(norm, pool)

        val plan = analyzer.analyze(tok).plan
        var result = when (plan.mode) {
            Mode.BROWSE -> excludeFrom(browseSorted(pool, locale), plan)
            Mode.INTENT -> searchIntent(plan, pool)
            Mode.NAME -> searchName(plan, pool, deadline)
        }
        // 이름으로는 못 찾았는데 부위·장비를 알아들었다면 그 의도로 보여 준다
        if (result.isEmpty() && plan.mode == Mode.NAME && plan.hasIntentSignal) result = searchIntent(plan, pool)
        return result
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

    private fun excludeFrom(hits: List<ExerciseSearchHit>, plan: QueryPlan): List<ExerciseSearchHit> {
        if (!plan.hasExclusions) return hits
        val ex = exclusionSet(plan)
        return hits.filter { h -> !isExcluded(indexed[indexOf(h.doc)], plan, ex) }
    }

    private val indexById: Map<Long, Int> = indexed.associate { it.doc.id to it.index }
    private fun indexOf(d: ExerciseSearchDoc): Int = indexById.getValue(d.id)

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
        return t + d.popularity.coerceIn(0, 100) / 100.0 * 3.0
    }

    // ── 제외(부정·부상) ──────────────────────────────────────────────────────────────────────

    /** 이름 신호로 제외할 문서: 부정 대상 동작 + 부상 관절의 금지 동작 */
    private fun exclusionSet(plan: QueryPlan): BitSet {
        val b = BitSet(indexed.size)
        for (k in plan.excludeKeys) b.or(sigIndex.hits(k))
        for (j in plan.injuries) QueryLexicon.injuryProfiles[j]?.hard?.forEach { b.or(sigIndex.hits(it)) }
        return b
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

    private fun bitsFor(c: QueryLexicon.Concept, kind: String, keys: List<SigKey>): BitSet =
        conceptBits.getOrPut(c.id + "#" + kind) { sigIndex.anyOf(keys) }

    private inner class ConceptEval(val c: QueryLexicon.Concept, val stretchWanted: Boolean) {
        val explicit = !c.id.startsWith("muscle:")
        val sig: BitSet = bitsFor(c, "sig", c.sig)
        val sig2: BitSet = bitsFor(c, "sig2", c.sig2)
        val avoid: BitSet = bitsFor(c, "avoid", c.avoid)
        val names: BitSet = if (stretchWanted) bitsFor(c, "names", c.nameWords) else sig

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
                return 0.55 * nameHit + 0.25 * maxOf(sigP, 0.5 * sigS) + 0.2 * mus
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
        val allowed: Set<Equipment>? = when {
            plan.equipments.isNotEmpty() -> plan.equipments
            plan.home -> ExerciseSearchLexicon.homeEquipmentPreference.keys
            else -> null
        }
        var cands = pool.filter { !isExcluded(it, plan, ex) }
        if (allowed != null) {
            val byEquip = cands.filter { it.doc.equipment in allowed }
            if (byEquip.isEmpty()) {
                // 장비만 물은 질의("kettlebell")인데 필터 안에 그 장비가 없으면 결과 없음 — 전체를 돌려주면 무관한 운동이 쏟아진다
                if (plan.concepts.isEmpty()) return emptyList()
            } else cands = byEquip
        }
        var evals = plan.concepts.map { ConceptEval(it, plan.stretch) }
        var out = scoreIntent(plan, cands, evals, soft, prefer)
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
            out = cands.filter { it.isStretch }.map { ExerciseSearchHit(it.doc, BAND_INTENT + 6.0 + it.rank) }
        }
        return rankSorted(out)
    }

    private fun relax(c: QueryLexicon.Concept) = QueryLexicon.Concept(
        c.id + "~relaxed", c.muscles, c.categories, c.sig, c.sig2, c.patterns, c.avoid, false, c.stretch, c.cardio, c.nameWords
    )

    private fun scoreIntent(plan: QueryPlan, cands: List<IDoc>, evals: List<ConceptEval>, soft: BitSet, prefer: BitSet): List<ExerciseSearchHit> {
        val out = ArrayList<ExerciseSearchHit>()
        for (d in cands) {
            var fit = 1.0
            if (evals.isNotEmpty()) {
                var best = -1.0
                for (e in evals) { val f = e.fit(d); if (f > best) best = f }
                if (best < 0) continue
                fit = best
            }
            var score = BAND_INTENT + 25.0 * fit + d.rank - 0.5 * (d.wordCount - 3).coerceIn(0, 4)
            if (plan.home && plan.equipments.isEmpty()) {
                score += HOME_PREF_WEIGHT * (ExerciseSearchLexicon.homeEquipmentPreference[d.doc.equipment] ?: 0.0)
            }
            score += levelAdjust(d.doc, plan.level) + injuryAdjust(d, plan, soft, prefer)
            out.add(ExerciseSearchHit(d.doc, score))
        }
        return out
    }

    // ── 이름 검색 ────────────────────────────────────────────────────────────────────────────

    private fun searchName(plan: QueryPlan, rawPool: List<IDoc>, deadline: Long): List<ExerciseSearchHit> {
        val seeds = plan.seeds.filter { it.isNotEmpty() }
        if (seeds.isEmpty()) return emptyList()
        val ex = if (plan.hasExclusions) exclusionSet(plan) else null
        val pool = if (ex != null) rawPool.filter { !isExcluded(it, plan, ex) } else rawPool
        val soft = if (plan.injuries.isNotEmpty()) softBits(plan) else null
        val prefer = if (plan.injuries.isNotEmpty()) preferBits(plan) else null

        val flat = SearchText.strictKey(seeds.first())
        if (isShort(flat) && synonymGroups(flat) == null) return searchShort(flat, pool)

        val (variants, equipPref0) = buildVariants(seeds)
        // 질의(동의어 변형 포함)가 말하는 동작 단어 전체
        val queryHeads = BitSet().also { b -> variants.forEach { b.or(it.heads) } }
        val queryRegions = BitSet().also { b -> variants.forEach { b.or(it.regions) } }
        val equipPref = HashSet(equipPref0)
        if (plan.highReps) equipPref.add(Equipment.BODYWEIGHT)
        val muscleHints = muscleHintsOf(seeds.first())
        val queryStrict = flat
        val unwantedModifiers = ExerciseSearchLexicon.modifierGroups.filter { g -> g.none { queryStrict.contains(it) } }
        val results = HashMap<Long, Double>()
        val docById = HashMap<Long, IDoc>()

        fun consider(d: IDoc, base: Double, nameUsed: NameKey?, variantUsed: Variant? = null) {
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
            if (soft != null && prefer != null) s += injuryAdjust(d, plan, soft, prefer)
            val cur = results[d.doc.id]
            if (cur == null || s > cur) { results[d.doc.id] = s; docById[d.doc.id] = d }
        }

        var solid = 0
        var counter = 0
        for (d in pool) {
            var best = -1.0
            var bestName: NameKey? = null
            var bestVariant: Variant? = null
            for (v in variants) for (n in d.names) {
                val s = matchTokens(v, n, muscleHints, d.doc)
                if (s > best) { best = s; bestName = n; bestVariant = v }
            }
            if (best >= 0) {
                consider(d, best, bestName, bestVariant)
                if (best >= BAND_LOOSE) solid++
            }
            if ((++counter and 127) == 0 && System.nanoTime() > deadline) break
        }

        val overBudget = { System.nanoTime() > deadline }

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
            // 부분 일치에서는 단어 중간 일치와, (마지막 단어가 아닌) 접두 일치를 일치로 치지 않는다 — sled push ↛ pushdown
            else if (q != OCC_WORD || !isLatin(t) || t === tokens.last()) matchedLen += t.length
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
            if (tokens.none { t -> t !in QueryLexicon.genericWords && occurrenceQuality(n, t).let { q -> q == OCC_EXACT || (q == OCC_WORD && (!isLatin(t) || t === tokens.last())) } }) return -1.0
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
        fun put(v: Variant): Boolean = all.size < MAX_VARIANTS_TOTAL && all.putIfAbsent(v.strict + "|" + v.words.size, v) == null

        val bases = ArrayList<Variant>()
        for (s in seeds) { val v = Variant(s, 0.0, BAND_EXACT); if (put(v)) bases.add(v) }
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

        // 2) 장비 단어 분리 (이름에 장비가 안 들어 있는 운동도 장비로 우선 정렬)
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

    companion object {
        // band 간격(20)은 장비 우선 가중치(15)·타이브레이커(<=9)보다 크게 유지
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

        private const val SYN_PENALTY = 2.0
        private const val TYPO_PENALTY = 3.0
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
        private const val INJURY_SOFT_PENALTY = 14.0
        private const val INJURY_PREFER_BONUS = 10.0
        private const val FUZZY_TRIGGER = 10
        private const val FUZZY_MIN_LATIN = 4
        private const val PARTIAL_MIN_FRACTION = 0.4
        private const val MAX_VARIANTS_TOTAL = 28
        private const val MAX_SYN_VARIANTS = 16
        private const val MAX_TYPO_VARIANTS = 4
        private const val MAX_COMBOS = 40
        private const val MAX_COMBO_WORDS = 6
        private const val MAX_SYN_RUN = 3
        private const val CACHE_SIZE = 512
        private val WARM_QUERIES = listOf("スクワット", "벤치프레스", "bench press", "背中を鍛えたい", "무릎 안 좋을 때 하체운동", "bad knees leg workout", "without deadlifts", "덤벨 컬", "유산소", "ヒップアップ")
        /** 한 질의의 후보 점수 계산 예산 — 넘으면 비싼 보강 단계(퍼지·바이그램)를 건너뛴다 */
        private const val BUDGET_NANOS = 40_000_000L
    }
}

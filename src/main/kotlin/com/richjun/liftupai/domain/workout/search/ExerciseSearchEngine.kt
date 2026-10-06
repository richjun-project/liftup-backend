package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

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
 * 점수 = 매칭 품질 band(정확 > 단어 경계 토큰 > 부분 토큰 > 일부 토큰 > 표기 변이 > 퍼지 > 의도)
 *        + 티어/인기도 타이브레이커. 부위·장비 의도 질의("하체", "덤벨 가슴", "home legs")는 의도 점수로 랭킹한다.
 * 브라우즈(질의 없음)는 티어 → 인기도 → 로컬라이즈 이름 순.
 */
class ExerciseSearchEngine(docs: Collection<ExerciseSearchDoc>) {

    private class IName(
        val strict: String,
        val loose: String,
        val starts: Set<Int>,
        val wordOf: IntArray,
        val choseong: String,
        val wordCount: Int
    )

    private class IDoc(val doc: ExerciseSearchDoc, val names: List<IName>, val rank: Double) {
        /** 가장 단어가 많은 이름 기준 단어 수 (영문이 가장 길게 풀려 있다) — 의도 랭킹에서 단순한 이름을 선호하는 데 사용 */
        val wordCount: Int = names.maxOfOrNull { it.wordCount } ?: 0
    }

    private class Variant(
        val words: List<String>,
        val penalty: Double,
        val exactBand: Double
    ) {
        val strict: String = SearchText.strictKey(words)
        val loose: String = SearchText.looseKey(strict)
        val looseWords: List<String> = words.map { SearchText.looseKey(it) }
    }

    private class Intent(
        val muscles: Set<MuscleGroup>,
        val categories: Set<ExerciseCategory>,
        val equipments: Set<Equipment>,
        val home: Boolean,
        val hasMuscle: Boolean
    )

    private val indexed: List<IDoc> = docs.map { d ->
        IDoc(d, d.names.values.filter { it.isNotBlank() }.map { buildName(it) }, rankOf(d))
    }

    val size: Int get() = indexed.size

    private fun buildName(name: String): IName {
        val words = SearchText.words(name)
        val starts = HashSet<Int>()
        val wordOf = IntArray(words.sumOf { it.length })
        var off = 0
        words.forEachIndexed { wi, w ->
            starts.add(off)
            for (k in w.indices) wordOf[off + k] = wi
            off += w.length
        }
        val strict = SearchText.strictKey(words)
        return IName(strict, SearchText.looseKey(strict), starts, wordOf, SearchText.choseong(name), words.size)
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────────────────────

    /** 질의 검색 (질의가 비어 있으면 브라우즈). 필터는 전부 AND 로 적용된다. */
    fun search(
        query: String?,
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): List<ExerciseSearchHit> {
        val pool = filtered(category, equipments)
        val words = SearchText.words(query.orEmpty())
        if (words.isEmpty()) return browseSorted(pool, locale)

        if (SearchText.isChoseongQuery(query.orEmpty())) return searchChoseong(query.orEmpty(), pool)

        val strict = SearchText.strictKey(words)
        parseIntent(strict)?.let { return searchIntent(it, pool) }
        return searchName(words, pool)
    }

    /** 브라우즈 순서: 티어 → 인기도 → 로컬라이즈 이름 */
    fun browse(
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null
    ): List<ExerciseSearchHit> = browseSorted(filtered(category, equipments), locale)

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

    // ── 의도(부위/장비) ──────────────────────────────────────────────────────────────────────

    /** 질의 전체가 사전의 부위/장비/홈/군더더기 단어로만 구성되면 의도 질의로 해석 */
    private fun parseIntent(strict: String): Intent? {
        val n = strict.length
        if (n == 0) return null
        val lex = ExerciseSearchLexicon
        // dp[i] = 앞 i 글자를 덮는 최소 세그먼트 열 (null = 불가)
        val best = arrayOfNulls<List<String>>(n + 1)
        best[0] = emptyList()
        for (i in 0 until n) {
            val prefix = best[i] ?: continue
            for (j in i + 1..minOf(n, i + MAX_TERM_LEN)) {
                val seg = strict.substring(i, j)
                if (seg in lex.muscleTerms || seg in lex.equipmentTerms || seg in lex.homeTerms || seg in lex.fillerTerms) {
                    val cand = prefix + seg
                    val cur = best[j]
                    if (cur == null || cand.size < cur.size) best[j] = cand
                }
            }
        }
        val segs = best[n] ?: return null
        val muscles = HashSet<MuscleGroup>()
        val cats = HashSet<ExerciseCategory>()
        val equips = HashSet<Equipment>()
        var home = false
        var hasMuscle = false
        var anyReal = false
        for (s in segs) {
            lex.muscleTerms[s]?.let {
                muscles.addAll(it.muscles); cats.addAll(it.categories); hasMuscle = true; anyReal = true
            }
            lex.equipmentTerms[s]?.let { equips.addAll(it); anyReal = true }
            if (s in lex.homeTerms) { home = true; anyReal = true }
        }
        if (!anyReal) return null
        return Intent(muscles, cats, equips, home, hasMuscle)
    }

    private fun searchIntent(intent: Intent, pool: List<IDoc>): List<ExerciseSearchHit> {
        val allowed: Set<Equipment>? = when {
            intent.equipments.isNotEmpty() -> intent.equipments
            intent.home -> ExerciseSearchLexicon.homeEquipmentPreference.keys
            else -> null
        }
        val signature = intent.muscles.flatMap { ExerciseSearchLexicon.signatureTerms[it].orEmpty() }.toSet()
        var cands = if (allowed != null) pool.filter { it.doc.equipment in allowed } else pool
        if (cands.isEmpty()) cands = pool
        val out = ArrayList<ExerciseSearchHit>()
        for (d in cands) {
            val doc = d.doc
            var fit = 1.0
            if (intent.hasMuscle) {
                val cat = doc.category in intent.categories
                val mus = doc.muscleGroups.any { it in intent.muscles }
                if (!cat && !mus) continue
                val overlap = doc.muscleGroups.count { it in intent.muscles }
                val purity = if (doc.muscleGroups.isEmpty()) 0.0 else overlap.toDouble() / doc.muscleGroups.size
                val sig = if (signature.any { key -> d.names.any { it.strict.contains(key) } }) 1.0 else 0.0
                fit = 0.35 * (if (cat) 1.0 else 0.0) + 0.35 * (if (mus) 1.0 else 0.0) + 0.1 * purity + 0.2 * sig
                if (doc.movementPattern == "STRETCHING") fit *= 0.4
            }
            var score = BAND_INTENT + 25.0 * fit + d.rank - 0.5 * (d.wordCount - 3).coerceIn(0, 4)
            if (intent.home && intent.equipments.isEmpty()) {
                score += 8.0 * (ExerciseSearchLexicon.homeEquipmentPreference[doc.equipment] ?: 0.0)
            }
            out.add(ExerciseSearchHit(doc, score))
        }
        return rankSorted(out)
    }

    // ── 이름 검색 ────────────────────────────────────────────────────────────────────────────

    private fun searchName(words: List<String>, pool: List<IDoc>): List<ExerciseSearchHit> {
        val (variants, equipPref) = buildVariants(words)
        val muscleHints = muscleHintsOf(words)
        val queryStrict = SearchText.strictKey(words)
        val unwantedModifiers = ExerciseSearchLexicon.modifierGroups.filter { g -> g.none { queryStrict.contains(it) } }
        val results = HashMap<Long, Double>()
        val docById = HashMap<Long, IDoc>()

        fun consider(d: IDoc, base: Double) {
            var s = base + d.rank - SIMPLICITY_WEIGHT * d.wordCount
            // 질의에 없는 수식어(어시스트, 한 팔)가 붙은 변형은 기본 동작 뒤로 미룬다
            if (unwantedModifiers.any { g -> d.names.any { n -> g.any { n.strict.contains(it) } } }) s -= MODIFIER_PENALTY
            if (equipPref.isNotEmpty() && d.doc.equipment in equipPref) s += EQUIP_PREF_BOOST
            val cur = results[d.doc.id]
            if (cur == null || s > cur) { results[d.doc.id] = s; docById[d.doc.id] = d }
        }

        var solid = 0
        for (d in pool) {
            var best = -1.0
            for (v in variants) for (n in d.names) {
                val s = matchTokens(v, n, muscleHints, d.doc)
                if (s > best) best = s
            }
            if (best >= 0) {
                consider(d, best)
                if (best >= BAND_LOOSE) solid++
            }
        }

        // 확실한 매칭이 적으면 퍼지(오타 허용) 보강
        if (solid < FUZZY_TRIGGER) {
            for (d in pool) {
                if ((results[d.doc.id] ?: 0.0) >= BAND_LOOSE) continue
                var best = -1.0
                for (v in variants) {
                    if (v.penalty > 0 && v.exactBand < BAND_EXACT_VARIANT) continue
                    val k = SearchText.allowedTypos(v.loose.length)
                    if (k == 0) continue
                    for (n in d.names) {
                        val dist = SearchText.approxSubstring(v.loose, n.loose, k)
                        if (dist <= k) {  // dist 0 은 단어 경계를 넘는 우연 일치이므로 오타 1개와 동급
                            val s = BAND_FUZZY - maxOf(dist, 1) * 6.0 - v.penalty
                            if (s > best) best = s
                        }
                    }
                }
                if (best >= 0) consider(d, best)
            }
        }

        // 그래도 결과가 거의 없으면 문자 바이그램 유사도(Dice)로 보강
        if (results.size < BIGRAM_TRIGGER) {
            val base = variants.first()
            val qBigrams = bigrams(base.loose)
            if (qBigrams.size >= 3) {
                for (d in pool) {
                    if (results.containsKey(d.doc.id)) continue
                    var best = 0.0
                    for (n in d.names) {
                        val nb = bigrams(n.loose)
                        val common = qBigrams.count { it in nb }
                        val dice = 2.0 * common / (qBigrams.size + nb.size)
                        if (common >= 3 && dice > best) best = dice
                    }
                    if (best >= BIGRAM_MIN_DICE) consider(d, BAND_BIGRAM + 20.0 * best)
                }
            }
        }

        val out = results.map { (id, s) -> ExerciseSearchHit(docById.getValue(id).doc, s) }
        return rankSorted(out)
    }

    private fun bigrams(key: String): Set<String> =
        if (key.length < 2) emptySet() else (0 until key.length - 1).map { key.substring(it, it + 2) }.toSet()

    private fun matchTokens(v: Variant, n: IName, muscleHints: MuscleHint, doc: ExerciseSearchDoc): Double {
        if (v.strict.isEmpty()) return -1.0
        if (n.strict == v.strict) return v.exactBand - v.penalty
        val tokens = v.words
        var allStrict = true
        var allBoundary = true
        var matchedLen = 0
        var totalLen = 0
        for (t in tokens) {
            totalLen += t.length
            val q = occurrenceQuality(n, t)
            if (q == OCC_NONE) { allStrict = false; continue }
            matchedLen += t.length
            if (q == OCC_MID_WORD) allBoundary = false
        }
        if (allStrict) {
            var s = if (allBoundary) BAND_TOKEN_WB else BAND_TOKEN_SUB
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
            var s = BAND_PARTIAL + 20.0 * matchedLen / totalLen
            if (muscleHints.matches(doc)) s += 8.0
            return s - v.penalty
        }
        return -1.0
    }

    private fun isLatin(t: String) = t.isNotEmpty() && t[0].code < 0x250

    /**
     * 토큰이 이름 키에서 나타나는 방식의 최선 품질.
     * 라틴 토큰은 단어 시작에서 일치해야 경계 일치(OCC_WORD)이고, 단어 중간 일치(OCC_MID_WORD)는 한 단계 낮다.
     * 단어 경계를 넘어 다음 단어 중간에서 끝나는 일치("squatt" ↔ "squat to")는 우연한 일치로 보고 버린다.
     */
    private fun occurrenceQuality(n: IName, t: String): Int {
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
                val q = if (!hangul || t.length >= 3 || startsWord || endsWord) OCC_WORD else OCC_MID_WORD
                best = maxOf(best, q)
                if (best == OCC_WORD) return best
                from = i + 1
                continue
            }
            val startsWord = i in n.starts
            val crossesUnaligned = n.wordOf[e - 1] != n.wordOf[i] && e < n.strict.length && n.wordOf[e] == n.wordOf[e - 1]
            if (!crossesUnaligned) {
                best = maxOf(best, if (startsWord) OCC_WORD else OCC_MID_WORD)
                if (best == OCC_WORD) return best
            }
            from = i + 1
        }
    }

    // ── 질의 변형(동의어/장비 분리) ─────────────────────────────────────────────────────────

    private class MuscleHint(val muscles: Set<MuscleGroup>, val categories: Set<ExerciseCategory>) {
        fun matches(d: ExerciseSearchDoc): Boolean =
            (muscles.isEmpty() && categories.isEmpty()).not() &&
                (d.muscleGroups.any { it in muscles } || d.category in categories)
    }

    private fun muscleHintsOf(words: List<String>): MuscleHint {
        val m = HashSet<MuscleGroup>()
        val c = HashSet<ExerciseCategory>()
        for (w in words) ExerciseSearchLexicon.muscleTerms[w]?.let { m.addAll(it.muscles); c.addAll(it.categories) }
        return MuscleHint(m, c)
    }

    /** 사전 단어(동의어 트리거·장비)로만 이루어진 붙여 쓴 CJK 복합어("ワンハンドロー")를 사전 단어 단위로 쪼갠다. 못 쪼개면 null. */
    private fun splitCompounds(words: List<String>): List<String>? {
        val lex = ExerciseSearchLexicon
        fun known(k: String) = k in lex.synonymIndex || k in lex.equipmentTerms || SearchText.looseKey(k) in lex.synonymLooseIndex
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
        ExerciseSearchLexicon.synonymIndex[key]
            ?: ExerciseSearchLexicon.synonymLooseIndex[SearchText.looseKey(key)]

    private fun buildVariants(rawWords: List<String>): Pair<List<Variant>, Set<Equipment>> {
        val words = rawWords
        val base = Variant(words, 0.0, BAND_EXACT)
        val all = LinkedHashMap<String, Variant>()
        all[base.strict + "|" + base.words.size] = base

        // 0) 붙여 쓴 복합어 분해 — 이후 단계는 분해된 단어열도 기준으로 삼는다
        val seeds = ArrayList<Variant>()
        seeds.add(base)
        splitCompounds(words)?.let { parts ->
            val v = Variant(parts, SYN_PENALTY, BAND_EXACT_VARIANT)
            if (all.putIfAbsent(v.strict + "|" + v.words.size, v) == null) seeds.add(v)
        }

        // 1) 동의어 확장 (단어 run 단위 치환)
        val syn = ArrayList<Variant>()
        for (seed in seeds) {
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
        // 동의어는 부위 단어가 없는 질의에서도 바로 쓰이도록 그대로 등록
        syn.take(MAX_VARIANTS).forEach { all.putIfAbsent(it.strict + "|" + it.words.size, it) }
        // 서로 다른 자리의 동의어가 둘 이상 겹치는 질의("원핸드 로우")는 조합까지 만든다
        for (seed in seeds) combineSynonyms(seed).forEach { all.putIfAbsent(it.strict + "|" + it.words.size, it) }

        // 2) 장비 단어 분리 (이름에 장비가 안 들어 있는 운동도 장비로 우선 정렬)
        val equipPref = HashSet<Equipment>()
        val extra = ArrayList<Variant>()
        for (v in all.values.toList()) {
            val stripped = stripEquipment(v.words, equipPref, allowFuzzy = v === base)
            if (stripped != null && stripped.isNotEmpty()) {
                extra.add(Variant(stripped, v.penalty + EQUIP_STRIP_PENALTY, BAND_EXACT_VARIANT - 20.0))
            }
        }
        extra.forEach { all.putIfAbsent(it.strict + "|" + it.words.size, it) }
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
        private const val OCC_NONE = 0
        private const val OCC_MID_WORD = 1
        private const val OCC_WORD = 2

        private const val SYN_PENALTY = 2.0
        private const val STRONG_SYN_BONUS = 6.0
        private const val MODIFIER_PENALTY = 6.0
        private const val EQUIP_STRIP_PENALTY = 2.0
        private const val EQUIP_PREF_BOOST = 15.0
        private const val FUZZY_TRIGGER = 10
        private const val PARTIAL_MIN_FRACTION = 0.4
        private const val MAX_VARIANTS = 24
        private const val MAX_COMBOS = 40
        private const val MAX_SYN_RUN = 3
        private const val MAX_TERM_LEN = 16
    }
}

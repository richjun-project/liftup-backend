package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.search.ExerciseSearchLexicon
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Concept
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Joint
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Role
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Term
import com.richjun.liftupai.domain.workout.search.SearchText

internal enum class Mode { BROWSE, INTENT, NAME }

/** 질의 이해 결과 */
internal class QueryPlan(
    val mode: Mode,
    val concepts: List<Concept>,
    val equipments: Set<Equipment>,
    val home: Boolean,
    val stretch: Boolean,
    /** -1 초보 선호, 0 없음, +1 상급 선호 */
    val level: Int,
    val excludeEquip: Set<Equipment>,
    val excludeKeys: List<SigKey>,
    val injuries: Set<Joint>,
    /** 질의에 있던 숫자(이름에 같은 숫자가 있으면 소폭 가산) */
    val numbers: List<String>,
    /** "100회" 처럼 반복 수가 큰 숫자 — 맨몸 운동을 선호 */
    val highReps: Boolean,
    /** 이름 검색용 단어열 후보(군더더기를 뗀 것 → 애매한 군더더기를 남긴 것) */
    val seeds: List<List<String>>,
    /** 이름으로 보이지만 어휘에 없는 덩어리(오타 가능성) 존재 */
    val hasUnknown: Boolean
) {
    val hasExclusions: Boolean get() = excludeEquip.isNotEmpty() || excludeKeys.isNotEmpty() || injuries.isNotEmpty()
    val hasIntentSignal: Boolean get() = concepts.isNotEmpty() || equipments.isNotEmpty() || home
}

internal class Seg(val s: Int, val e: Int, val terms: List<Term>?, val isNum: Boolean = false) {
    val unknown: Boolean get() = terms == null
    fun has(role: Role): Boolean = terms?.any { it.role == role } == true
    fun onlyRoles(vararg roles: Role): Boolean = terms != null && terms.all { it.role in roles }
}

/**
 * 질의 → 의미 단위 분해.
 * strict 키(단어를 이은 문자열)를 사전 용어로 가장 많이 덮도록 동적계획으로 나눈다.
 * 덮이지 않는 글자는 "모르는 글자"(비용 큼)로 남는다. 조사·어미·수치 단위는 앞말과의 결합 조건을 만족할 때만 인정한다.
 */
internal class QueryAnalyzer(private val vocab: Vocabulary, private val synonyms: ExerciseSearchLexicon.SynonymTable) {

    private val lex = QueryLexicon
    private val index: Map<String, List<Term>>
    private val maxLen: Int

    /** 군더더기이면서 이름 단어이기도 한 표기("up", "with") — 이름 검색에서는 남긴 후보를 따로 둔다 */
    private val ambiguousNoise: Set<String>

    init {
        val nameTerm = listOf(Term(Role.NAME))
        val merged = HashMap<String, List<Term>>(lex.termIndex)
        val ambiguous = HashSet<String>()
        for (w in vocab.words.keys) {
            val existing = merged[w]
            if (existing == null) merged[w] = nameTerm
            else if (existing.all { it.role == Role.NOISE || it.role == Role.CONJ || it.role == Role.LEVEL }) ambiguous.add(w)
        }
        for (s in synonyms.synonyms) for (t in s.triggers) if (t.length >= 2 && !merged.containsKey(t)) merged[t] = nameTerm
        index = merged
        ambiguousNoise = ambiguous
        maxLen = minOf(MAX_TERM_LEN, merged.keys.maxOf { it.length })
    }

    class Analysis(val tok: SearchText.Tokenized, val segs: List<Seg>, val plan: QueryPlan)

    fun analyze(tok: SearchText.Tokenized): Analysis {
        val segs = segment(tok)
        return Analysis(tok, segs, buildPlan(tok, segs))
    }

    // ── 분해 (DP) ────────────────────────────────────────────────────────────────────────────

    private fun segment(tok: SearchText.Tokenized): List<Seg> {
        val text = tok.strict
        val n = text.length
        val cost = IntArray(n + 1) { Int.MAX_VALUE }
        val back = arrayOfNulls<Seg>(n + 1)
        val known = BooleanArray(n + 1)
        val numEnd = BooleanArray(n + 1)
        val unkRun = IntArray(n + 1)
        cost[0] = 0

        fun relax(to: Int, c: Int, seg: Seg, isKnown: Boolean, isNum: Boolean, run: Int) {
            if (c < cost[to]) {
                cost[to] = c; back[to] = seg; known[to] = isKnown; numEnd[to] = isNum; unkRun[to] = run
            }
        }

        for (i in 0 until n) {
            if (cost[i] == Int.MAX_VALUE) continue
            val base = cost[i]
            // 숫자 덩어리
            if (text[i].isDigit() && (i == 0 || !text[i - 1].isDigit())) {
                var j = i
                while (j < n && (text[j].isDigit() || (text[j] == 'x' && j + 1 < n && text[j + 1].isDigit() && j > i))) j++
                if (tok.isWordEnd(j) || !text[j].isLetter() || text[j].code >= 0x250) {
                    relax(j, base + NUM_COST, Seg(i, j, emptyList(), isNum = true), true, true, 0)
                }
            }
            // 사전 용어 (긴 것부터)
            val maxJ = minOf(n, i + maxLen)
            for (j in maxJ downTo i + 1) {
                val key = text.substring(i, j)
                val terms = index[key] ?: continue
                val valid = terms.filter { accept(it, key, i, j, tok, known[i], numEnd[i], unkRun[i]) }
                if (valid.isEmpty()) continue
                val unitOnly = valid.all { it.role == Role.UNIT }
                // 이름 단어(복합어)는 길수록 비싸다 — "股関節ストレッチ" 같은 복합어보다 부위+스트레칭 분해를 선호
                val nameOnly = valid.all { it.role == Role.NAME }
                relax(j, base + SEG_COST + (if (nameOnly) key.length else 0), Seg(i, j, valid), true, unitOnly, 0)
            }
            // 모르는 글자 한 개
            relax(i + 1, base + UNKNOWN_COST, Seg(i, i + 1, null), false, false, unkRun[i] + 1)
        }

        val out = ArrayList<Seg>()
        var p = n
        while (p > 0) {
            val sg = back[p] ?: break
            out.add(sg)
            p = sg.s
        }
        out.reverse()
        return out
    }

    /** 용어가 이 자리(i..j)에서 유효한가 */
    private fun accept(t: Term, key: String, i: Int, j: Int, tok: SearchText.Tokenized, prevKnown: Boolean, prevNum: Boolean, prevUnkRun: Int): Boolean {
        val latin = key[0].code < 0x250 && key[0].isLetterOrDigit()
        if (latin && !(tok.isWordStart(i) && tok.isWordEnd(j))) return false
        when (t.role) {
            Role.UNIT -> if (!prevNum) return false
            else -> Unit
        }
        if (t.particle) {
            if (anyKata(tok, i, j)) return false
            val needsPrev = (t.role == Role.NEG && t.targetBefore) || (t.role == Role.NOISE && key.length <= 2) || t.role == Role.CONJ
            if (needsPrev) {
                val ok = prevKnown || (prevUnkRun >= 2 && i > 0 && !isHiragana(tok, i - 1))
                if (!ok) return false
                // 조사 뒤에는 단어 끝이거나 사전 용어가 이어져야 한다 ("바벨 로오" 의 로 는 조사가 아니다)
                if (!(tok.isWordEnd(j) || startsKnown(tok, j))) return false
            }
        }
        if (t.isolated) {
            if (!(tok.isWordStart(i) || prevKnown)) return false
            if (!(tok.isWordEnd(j) || nextStartsKnown(tok, j))) return false
        }
        return true
    }

    /** j 위치에서 사전 용어(숫자 포함)가 시작하는가 */
    private fun startsKnown(tok: SearchText.Tokenized, j: Int): Boolean {
        val text = tok.strict
        if (j >= text.length) return true
        if (text[j].isDigit()) return true
        for (end in minOf(text.length, j + maxLen) downTo j + 1) if (index.containsKey(text.substring(j, end))) return true
        return false
    }

    /** 1글자 용어 뒤에 사전 용어가 바로 이어지는가(예: 背中 의 背 는 아니지만 "背を"처럼 조사가 이어지는 경우) */
    private fun nextStartsKnown(tok: SearchText.Tokenized, j: Int): Boolean {
        val text = tok.strict
        for (end in minOf(text.length, j + 4) downTo j + 1) {
            val terms = index[text.substring(j, end)] ?: continue
            if (terms.any { it.role == Role.NOISE || it.role == Role.NEG || it.role == Role.CONJ || it.role == Role.UNIT || it.role == Role.CUE }) return true
        }
        return false
    }

    private fun anyKata(tok: SearchText.Tokenized, i: Int, j: Int): Boolean {
        for (k in i until j) if (tok.kata[k]) return true
        return false
    }

    private fun isHiragana(tok: SearchText.Tokenized, i: Int): Boolean {
        val c = tok.strict[i]
        return c.code in 0x3041..0x309F && !tok.kata[i]
    }

    // ── 계획 ─────────────────────────────────────────────────────────────────────────────────

    private fun buildPlan(tok: SearchText.Tokenized, segs: List<Seg>): QueryPlan {
        val text = tok.strict
        val consumed = BooleanArray(segs.size)
        val excludeEquip = LinkedHashSet<Equipment>()
        val excludeKeys = ArrayList<SigKey>()
        var cueFlag = false

        fun chunkAt(idx: Int): Pair<Int, Int> {
            // idx 에서 시작하는 모르는 글자 덩어리의 [from, to] 범위
            var a = idx; var b = idx
            while (a - 1 >= 0 && segs[a - 1].unknown) a--
            while (b + 1 < segs.size && segs[b + 1].unknown) b++
            return a to b
        }

        fun skippable(sg: Seg) = !sg.unknown && !sg.isNum && sg.onlyRoles(Role.NOISE)

        // 1) 부정: 표지 + 대상
        for (idx in segs.indices) {
            val neg = segs[idx].terms?.firstOrNull { it.role == Role.NEG } ?: continue
            if (consumed[idx]) continue
            val targets = ArrayList<IntRange>()
            var cursor = idx
            val step = if (neg.targetBefore) -1 else 1
            var chainLeft = 3
            while (chainLeft-- > 0) {
                var j = cursor + step
                while (j in segs.indices && (skippable(segs[j]))) j += step
                if (j !in segs.indices) break
                val sg = segs[j]
                if (sg.has(Role.NEG) || sg.has(Role.UNIT) || sg.isNum) break
                val range = if (sg.unknown) {
                    val (a, b) = chunkAt(j); a..b
                } else j..j
                targets.add(range)
                cursor = if (step < 0) range.first else range.last
                // 접속어로 이어지는 다음 대상
                val k = cursor + step
                if (k in segs.indices && segs[k].has(Role.CONJ)) {
                    cursor = k
                } else break
            }
            var any = false
            for (r in targets) {
                if (resolveTarget(segs, r, text, excludeEquip, excludeKeys, consumed)) any = true
                else if (r.any { segs[it].has(Role.JOINT) || segs[it].has(Role.CUE) }) { cueFlag = true; any = true }
            }
            if (any) consumed[idx] = true
        }

        // 2) 소음/충격 회피 표현 → 점프류 제외 + 집
        var quiet = false
        for (idx in segs.indices) if (!consumed[idx] && segs[idx].has(Role.QUIET)) { quiet = true; consumed[idx] = true }
        if (quiet) lex.moveGroups["jump"]?.let { excludeKeys.addAll(it.keys) }

        // 3) 부상: 단서가 있으면 관절은 목표가 아니라 피할 부위
        val cue = cueFlag || segs.indices.any { !consumed[it] && segs[it].has(Role.CUE) }
        val injuries = LinkedHashSet<Joint>()
        if (cue) {
            for (idx in segs.indices) {
                if (consumed[idx]) continue
                val sg = segs[idx]
                val joint = sg.terms?.firstNotNullOfOrNull { it.joint }
                if (joint != null) { injuries.add(joint); consumed[idx] = true }
                else if (sg.has(Role.CUE)) consumed[idx] = true
            }
        }

        // 4) 긍정 요소
        val concepts = ArrayList<Concept>()
        val equipments = LinkedHashSet<Equipment>()
        var home = quiet
        var stretch = false
        var level = 0
        val numbers = ArrayList<String>()
        var highReps = false
        var positiveName = false
        val unknownChunks = ArrayList<String>()
        var jointPlain = false

        var idx = 0
        while (idx < segs.size) {
            val sg = segs[idx]
            if (sg.unknown) {
                var b = idx
                while (b + 1 < segs.size && segs[b + 1].unknown) b++
                if (!consumed[idx]) unknownChunks.add(text.substring(sg.s, segs[b].e))
                idx = b + 1
                continue
            }
            if (!consumed[idx]) {
                if (sg.isNum) {
                    val digits = text.substring(sg.s, sg.e)
                    numbers.add(digits)
                    val next = segs.getOrNull(idx + 1)
                    val nextKey = next?.let { text.substring(it.s, it.e) }
                    val v = digits.toIntOrNull()
                    if (v != null && v >= HIGH_REPS && nextKey != null && nextKey in REP_UNITS) highReps = true
                } else {
                    val terms = sg.terms!!
                    val concept = terms.firstOrNull { it.role == Role.CONCEPT }?.concept
                    if (concept != null) concepts.add(concept)
                    terms.firstOrNull { it.role == Role.EQUIP }?.let { equipments.addAll(it.equipment) }
                    if (terms.any { it.role == Role.HOME }) home = true
                    if (terms.any { it.role == Role.STRETCH }) stretch = true
                    terms.firstOrNull { it.role == Role.LEVEL }?.let { level = it.level }
                    val contentRole = terms.any { it.role == Role.CONCEPT || it.role == Role.EQUIP || it.role == Role.HOME || it.role == Role.STRETCH }
                    if (!contentRole) {
                        if (terms.any { it.role == Role.NAME }) positiveName = true
                        else if (terms.any { it.role == Role.JOINT }) jointPlain = true
                    }
                }
            }
            idx++
        }
        if (jointPlain) positiveName = true
        // 스트레칭 단독("스트레칭", "ヨガ")은 이름 검색, 부위와 함께면 의도 검색
        if (stretch && concepts.isEmpty() && equipments.isEmpty()) positiveName = true

        val distinctConcepts = concepts.distinctBy { it.id }
        var effectiveConcepts: List<Concept> = distinctConcepts

        // 부상만 있고 목표 부위가 없으면: 그 관절에 안전한 운동 전반
        if (effectiveConcepts.isEmpty() && injuries.isNotEmpty() && !positiveName) {
            val prefer = injuries.flatMap { lex.injuryProfiles[it]?.prefer.orEmpty() }
            if (prefer.isNotEmpty()) {
                effectiveConcepts = listOf(Concept("injury-safe:" + injuries.joinToString(","), emptySet(), emptySet(), prefer, emptyList(), emptySet(), emptyList(), true, false, false))
            }
        }

        val nameLikeUnknown = unknownChunks.any { chunk -> chunk.length >= 2 && vocab.nearest(chunk).isNotEmpty() } ||
            // 장비만 알아들었고 나머지를 모르면 그 나머지는 운동 이름일 가능성이 높다("바벨 로오")
            (unknownChunks.isNotEmpty() && effectiveConcepts.isEmpty() && !home && equipments.isNotEmpty())
        val hasContentSignal = effectiveConcepts.isNotEmpty() || equipments.isNotEmpty() || home
        val seeds = buildSeeds(tok, segs, consumed)

        // 모르는 덩어리가 남았는데 부위·장비·집 같은 내용 신호가 없으면(수준·제외 표현뿐) 그 덩어리가 운동 이름이다
        val onlyModifiers = !hasContentSignal && unknownChunks.isNotEmpty()
        var mode = when {
            !positiveName && !nameLikeUnknown && !onlyModifiers && (hasContentSignal || level != 0 || excludeEquip.isNotEmpty() || excludeKeys.isNotEmpty() || injuries.isNotEmpty()) -> Mode.INTENT
            !positiveName && !nameLikeUnknown && unknownChunks.isEmpty() -> Mode.BROWSE
            else -> Mode.NAME
        }
        // 군더더기만 있는 질의는 목록(브라우즈). 단, 숫자만 있거나 1~2글자면 이름 검색으로 보낸다(무관한 전체 목록을 주지 않는다).
        var finalSeeds = seeds
        if (mode == Mode.BROWSE && (numbers.isNotEmpty() || text.length <= 2)) {
            mode = Mode.NAME
            finalSeeds = listOf(tok.words)
        }
        if (mode == Mode.NAME && finalSeeds.isEmpty()) finalSeeds = listOf(tok.words)
        return QueryPlan(
            mode = mode, concepts = effectiveConcepts, equipments = equipments, home = home, stretch = stretch, level = level,
            excludeEquip = excludeEquip, excludeKeys = excludeKeys, injuries = injuries, numbers = numbers, highReps = highReps,
            seeds = finalSeeds, hasUnknown = unknownChunks.isNotEmpty()
        )
    }

    /** 부정 대상 하나를 해석해 제외 조건에 반영한다. 반영했으면 true. */
    private fun resolveTarget(
        segs: List<Seg>, range: IntRange, text: String,
        excludeEquip: MutableSet<Equipment>, excludeKeys: MutableList<SigKey>, consumed: BooleanArray
    ): Boolean {
        val first = segs[range.first]
        if (first.unknown) {
            val chunk = text.substring(first.s, segs[range.last].e)
            if (chunk.length < 2) return false
            excludeKeys.add(SigKey(chunk, false))
            for (i in range) consumed[i] = true
            return true
        }
        val terms = first.terms!!
        if (terms.any { it.role == Role.JOINT || it.role == Role.CUE }) return false
        val eq = terms.firstOrNull { it.role == Role.EQUIP }
        if (eq != null) {
            excludeEquip.addAll(eq.equipment)
            consumed[range.first] = true
            return true
        }
        val key = text.substring(first.s, first.e)
        val move = terms.firstNotNullOfOrNull { it.move }
        if (move != null) {
            excludeKeys.addAll(move.keys)
            consumed[range.first] = true
            return true
        }
        if (terms.any { it.role == Role.NAME }) {
            excludeKeys.add(SigKey(key, false))
            // 같은 동작의 다른 언어 표기도 함께 제외 (데드리프트 → deadlift, 데드리프트)
            synonyms.index[key].orEmpty().forEach { g ->
                g.expansions.forEach { exp -> SearchText.strictKey(exp).takeIf { it.length >= 3 }?.let { excludeKeys.add(SigKey(it, false)) } }
            }
            consumed[range.first] = true
            return true
        }
        if (terms.any { it.role == Role.HOME || it.role == Role.STRETCH }) {
            // "스트레칭 없이", "집 말고" 등: 스트레칭 제외로 해석
            if (terms.any { it.role == Role.STRETCH }) {
                excludeKeys.add(SigKey(SearchText.strictKey("stretch"), false))
                excludeKeys.add(SigKey(SearchText.strictKey("ストレッチ"), false))
                excludeKeys.add(SigKey(SearchText.strictKey("스트레칭"), false))
                consumed[range.first] = true
                return true
            }
        }
        return false
    }

    // ── 이름 검색용 단어열 ───────────────────────────────────────────────────────────────────

    /**
     * 질의에서 군더더기·수치·부정·부상 표현을 뗀 단어열 후보.
     * 첫째: 군더더기를 모두 뗀 것. 둘째: 이름에도 쓰이는 군더더기("up", "with")를 남긴 것(원문 단어 경계는 유지).
     * 아무것도 뗄 게 없으면 원문 단어열과 같다.
     */
    private fun buildSeeds(tok: SearchText.Tokenized, segs: List<Seg>, consumed: BooleanArray): List<List<String>> {
        val text = tok.strict
        fun build(keepAmbiguous: Boolean): List<String> {
            val words = ArrayList<String>()
            val cur = StringBuilder()
            fun flush() { if (cur.isNotEmpty()) { words.add(cur.toString()); cur.setLength(0) } }
            for ((idx, sg) in segs.withIndex()) {
                val key = text.substring(sg.s, sg.e)
                val keep: Boolean = when {
                    sg.unknown -> !consumed[idx]
                    consumed[idx] -> false
                    sg.isNum -> false
                    else -> {
                        val terms = sg.terms!!
                        val contentRole = terms.any { it.role == Role.CONCEPT || it.role == Role.EQUIP || it.role == Role.NAME || it.role == Role.JOINT || it.role == Role.STRETCH }
                        when {
                            contentRole -> true
                            keepAmbiguous && key in ambiguousNoise -> true
                            terms.any { it.role == Role.NOISE && !it.particle } && isDubiousNoise(tok, segs, idx) -> true
                            else -> false
                        }
                    }
                }
                if (!keep) { flush(); continue }
                // 한 조각이 원문의 여러 단어에 걸쳐 있어도(예: "cable fly" 동의어) 원문 단어 경계는 유지한다
                for (p in sg.s until sg.e) {
                    if (cur.isNotEmpty() && tok.isWordStart(p)) flush()
                    cur.append(text[p])
                }
            }
            flush()
            return words
        }
        val a = build(false)
        val b = build(true)
        val out = ArrayList<List<String>>()
        if (a.isNotEmpty()) out.add(a)
        if (b.isNotEmpty() && b != a) out.add(b)
        return out
    }

    /** 군더더기 표기가 모르는 글자와 붙어 있어 한 단어의 일부일 수 있는가 (그러면 떼지 않는다) */
    private fun isDubiousNoise(tok: SearchText.Tokenized, segs: List<Seg>, idx: Int): Boolean {
        val sg = segs[idx]
        if (tok.isWordStart(sg.s) && tok.isWordEnd(sg.e)) return false
        val prev = segs.getOrNull(idx - 1)
        val next = segs.getOrNull(idx + 1)
        val prevUnknown = prev != null && prev.unknown && !tok.isWordStart(sg.s)
        val nextUnknown = next != null && next.unknown && !tok.isWordEnd(sg.e)
        return prevUnknown || nextUnknown
    }

    companion object {
        private const val UNKNOWN_COST = 30
        private const val SEG_COST = 6
        private const val NUM_COST = 2
        private const val MAX_TERM_LEN = 40
        private const val HIGH_REPS = 20
        private val REP_UNITS = setOf("회", "개", "回", "번", "rep", "time", "個").map { SearchText.strictKey(it) }.toSet()
    }
}

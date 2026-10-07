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
    /** 못 하는 운동의 쉬운 변형을 앞세운다 */
    val regress: Boolean,
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
    val hasUnknown: Boolean,
    /** "X 대신/alternative to X" 의 X — 제외하되 X 와 비슷한(같은 부위·동작) 운동을 보여 준다 */
    val similarTo: List<SigKey> = emptyList(),
    /** "가슴 말고", "no legs" 처럼 부정된 부위·목표 — 그 부위 운동을 결과에서 뺀다 */
    val excludeConcepts: List<Concept> = emptyList(),
    /** 웜업처럼 다른 부위와 겹쳐 쓰는 개념("하체 워밍업") — 합집합이 아니라 모든 개념에 맞는 운동을 앞세운다 */
    val combineAll: Boolean = false
) {
    companion object {
        /** 이해 계층을 거치지 않고 단어열 그대로 이름 검색하는 계획(질의가 어떤 운동의 이름과 정확히 같을 때) */
        fun nameOnly(words: List<String>) = QueryPlan(
            mode = Mode.NAME, concepts = emptyList(), equipments = emptySet(), home = false, stretch = false, level = 0, regress = false,
            excludeEquip = emptySet(), excludeKeys = emptyList(), injuries = emptySet(), numbers = emptyList(), highReps = false,
            seeds = listOf(words), hasUnknown = false
        )
    }

    val hasExclusions: Boolean get() = excludeEquip.isNotEmpty() || excludeKeys.isNotEmpty() || injuries.isNotEmpty() || excludeConcepts.isNotEmpty()
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
        val isolatedName = listOf(Term(Role.NAME, isolated = true))
        for (s in synonyms.synonyms) for (t in s.triggers) {
            if (merged.containsKey(t)) continue
            // 한 글자 한자 별칭(壁·床)도 단어 경계에서는 이름 조각이다 — 그렇지 않으면 "壁を使ったスクワット" 의 壁 이 모르는 글자로 남는다
            if (t.length >= 2) merged[t] = nameTerm else if (t.length == 1 && t[0].code in 0x4E00..0x9FFF) merged[t] = isolatedName
        }
        index = merged
        ambiguousNoise = ambiguous
        maxLen = minOf(MAX_TERM_LEN, merged.keys.maxOf { it.length })
    }

    class Analysis(val tok: SearchText.Tokenized, val segs: List<Seg>, val plan: QueryPlan)

    fun analyze(tok: SearchText.Tokenized): Analysis {
        val fixed = correctLexiconTypos(tok) ?: tok
        val segs = segment(fixed)
        return Analysis(fixed, segs, buildPlan(fixed, segs))
    }

    // ── 사전 용어 오타 교정 ──────────────────────────────────────────────────────────────────
    // 카탈로그 이름 어휘의 오타는 이름 검색 단계가 고치지만, 부위·장비·스트레칭·웜업 같은 "사전에만 있는 말"의
    // 오타("strecthing", "워밍엎", "worm up")는 이해 계층이 못 알아들어 이름 검색으로 새 버린다. 한 글자(자모) 오타만 같은 사전 용어로 본다.

    private class Target(val key: String, val loose: String)

    /** 라틴은 6글자 이상, 한글·가나·한자는 자모·가나 기준 4 이상일 때만 오타 교정 대상(짧은 말은 이웃이 너무 많다) */
    private fun typoEligible(strict: String, loose: String): Boolean =
        if (strict.isNotEmpty() && strict[0].code < 0x250) strict.length >= TYPO_MIN_LATIN else strict.length >= 3 && loose.length >= TYPO_MIN_LOOSE

    private val typoTargets: Map<Int, List<Target>> by lazy {
        index.entries.asSequence()
            .filter { (k, v) -> v.any { it.role in TYPO_ROLES && !it.particle } }
            .map { (k, _) -> Target(k, SearchText.looseKey(k)) }
            .filter { typoEligible(it.key, it.loose) }
            .groupBy { it.loose.length }
    }

    /** 단어(또는 이웃 두 단어를 붙인 것)가 사전 용어와 오타 한 칸 거리면 그 용어로 바꾼 새 토큰열. 바꿀 것이 없으면 null. */
    private fun correctLexiconTypos(tok: SearchText.Tokenized): SearchText.Tokenized? {
        val words = tok.words
        if (words.isEmpty() || words.size > TYPO_MAX_WORDS) return null
        // 질의 전체를 나눠 봤을 때 모르는 글자가 걸친 단어만 후보다 — 사전 용어를 이어 붙인 말("ホームトレ")이나 여러 단어로 된 용어("살 빠지는")는 이미 알아들은 것이다
        val firstPass = segment(tok)
        val unknownMask = BooleanArray(tok.strict.length)
        for (sg in firstPass) if (sg.unknown) for (p in sg.s until sg.e) unknownMask[p] = true
        fun hasUnknown(i: Int): Boolean {
            val st = tok.starts[i]
            val end = minOf(unknownMask.size, st + words[i].length)
            var unknownChars = 0
            for (p in st until end) if (unknownMask[p]) unknownChars++
            if (unknownChars == 0) return false
            // 한글·가나·한자 단어에서 이미 알아들은 부분(윗복근 의 복근, ダンベルカ 의 ダンベル)이 절반 이상이면 오타가 아니라 새 말이다
            val known = (end - st) - unknownChars
            return words[i][0].code < 0x250 || known < 2 || known * 2 < (end - st)
        }
        fun unknown(i: Int): Boolean {
            val w = words[i]
            return w.length >= 3 && !w.all { it.isDigit() } && w !in vocab.words && !index.containsKey(w) && hasUnknown(i)
        }
        fun nearest(w: String): String? {
            val loose = SearchText.looseKey(w)
            val k = SearchText.allowedTypos(loose.length)
            if (!typoEligible(w, loose) || k == 0) return null
            var best: Target? = null
            var bestD = k + 1
            var tie = false
            for (len in (loose.length - k)..(loose.length + k)) {
                for (t in typoTargets[len].orEmpty()) {
                    val d = SearchText.editDistance(loose, t.loose, k)
                    if (d > k) continue
                    if (d < bestD) { best = t; bestD = d; tie = false } else if (d == bestD && best != null && best.key != t.key) tie = true
                }
            }
            // 표기만 다른(장음·촉음) 같은 말은 거리 0 이라 어느 쪽이든 같다 — 거리가 있는 후보끼리 동률일 때만 포기한다
            return if (best != null && (!tie || bestD == 0)) best.key else null
        }
        val out = ArrayList<String>(words.size)
        var changed = false
        var i = 0
        while (i < words.size) {
            val w = words[i]
            // 이웃 단어와 붙여 쓴 형태("worm up" → warmup)를 먼저 본다 — 두 단어 중 하나라도 모르는 말일 때만
            if (i + 1 < words.size && (unknown(i) || unknown(i + 1)) && !index.containsKey(w + words[i + 1])) {
                val joined = nearest(w + words[i + 1])
                if (joined != null) { out.add(joined); changed = true; i += 2; continue }
            }
            val fix = if (unknown(i)) nearest(w) else null
            if (fix != null) { out.add(fix); changed = true } else out.add(w)
            i++
        }
        return if (changed) SearchText.tokenize(out.joinToString(" ")) else null
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
                // 군더더기 조각은 비싸다 — "good morning"(이름) 이 good + morning(군더더기 둘)로 쪼개지지 않게
                val noiseOnly = valid.all { it.role == Role.NOISE || it.role == Role.CONJ }
                relax(j, base + SEG_COST + (if (nameOnly) key.length else 0) + (if (noiseOnly) NOISE_EXTRA_COST else 0), Seg(i, j, valid), true, unitOnly, 0)
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
        // 영어 "free"/"less" 는 "equipment free"·"jump free" 처럼 부정이지만 "free weights"·"free motion" 의 free 는 아니다
        if (t.role == Role.NEG && latin && (key == "free" || key == "less")) {
            val next = nextWord(tok, j)
            if (key == "free" && next in FREE_NOT_NEG_BEFORE) return false
            if (key == "less" && next.isNotEmpty() && next !in LESS_NEG_BEFORE) return false
        }
        if (t.particle) {
            if (anyKata(tok, i, j)) return false
            val needsPrev = (t.role == Role.NEG && t.targetBefore) || (t.role == Role.NOISE && key.length <= 2) || t.role == Role.CONJ
            if (needsPrev) {
                val ok = prevKnown || (prevUnkRun >= 2 && i > 0 && !isHiragana(tok, i - 1))
                if (!ok) return false
                // 조사 뒤에는 단어 끝이거나 사전 용어가 이어져야 한다 ("바벨 로오" 의 로 는 조사가 아니다). 나열 조사(も·도·や·と)는 뒤가 무엇이든 인정한다.
                if (t.role != Role.CONJ && !(tok.isWordEnd(j) || startsKnown(tok, j))) return false
            }
        }
        if (t.isolated) {
            if (!(tok.isWordStart(i) || prevKnown)) return false
            if (!(tok.isWordEnd(j) || nextStartsKnown(tok, j))) return false
        }
        return true
    }

    /** j 위치에서 시작하는 다음 단어(없으면 빈 문자열) */
    private fun nextWord(tok: SearchText.Tokenized, j: Int): String {
        val text = tok.strict
        if (j >= text.length) return ""
        var e = j + 1
        while (e < text.length && !tok.isWordStart(e)) e++
        return text.substring(j, e)
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
        for (end in minOf(text.length, j + 8) downTo j + 1) {
            val terms = index[text.substring(j, end)] ?: continue
            if (terms.any { it.role == Role.NOISE || it.role == Role.NEG || it.role == Role.CONJ || it.role == Role.UNIT || it.role == Role.CUE || it.role == Role.STRETCH || it.role == Role.HOME || it.role == Role.QUIET }) return true
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
        val similarKeys = ArrayList<SigKey>()
        val negConcepts = ArrayList<Concept>()
        val insteadConcepts = ArrayList<Concept>()
        val negJoints = LinkedHashSet<Joint>()
        var cueFlag = false

        fun chunkAt(idx: Int): Pair<Int, Int> {
            // idx 에서 시작하는 모르는 글자 덩어리의 [from, to] 범위
            var a = idx; var b = idx
            while (a - 1 >= 0 && segs[a - 1].unknown) a--
            while (b + 1 < segs.size && segs[b + 1].unknown) b++
            return a to b
        }

        fun skippable(sg: Seg) = !sg.unknown && !sg.isNum && (sg.onlyRoles(Role.NOISE, Role.CONJ) || text.substring(sg.s, sg.e) in QUANTIFIERS)

        // 1) 부정: 표지 + 대상
        for (idx in segs.indices) {
            val neg = segs[idx].terms?.firstOrNull { it.role == Role.NEG } ?: continue
            if (consumed[idx]) continue
            // "背中の肉をなくしたい" — 군살·처짐을 없애고 싶다는 목표다. 그 부위를 빼라는 부정이 아니다(앞의 군살 개념은 긍정 목표로 둔다).
            if (!neg.instead && text.substring(segs[idx].s, segs[idx].e) in REDUCE_KEYS) {
                var p = idx - 1
                while (p >= 0 && skippable(segs[p])) p--
                if (p >= 0 && !segs[p].unknown && !segs[p].isNum && segs[p].terms!!.any { it.role == Role.CONCEPT && it.concept != null && it.concept.id in FAT_GOAL_IDS }) { consumed[idx] = true; continue }
            }
            // "ダンベルしか空いてない", "덤벨밖에 없을 때" — "그것밖에 없다"는 그것만 쓸 수 있다는 긍정이다(그 장비를 빼라는 뜻이 아니다)
            if (idx > 0 && !segs[idx - 1].unknown && !segs[idx - 1].isNum && text.substring(segs[idx - 1].s, segs[idx - 1].e).let { it.endsWith("しか") || it.endsWith("밖에") }) continue
            // "ダンベルで代用したい" — 「で」 뒤의 代用 은 앞의 것으로 대신한다는 긍정이다(앞 장비를 빼라는 뜻이 아니다)
            if (neg.instead && idx > 0 && !segs[idx - 1].unknown && !segs[idx - 1].isNum && text.substring(segs[idx].s, segs[idx].e) == "代用" && text.substring(segs[idx - 1].s, segs[idx - 1].e) == "で") continue
            // "ローラーが無いときの代わり", "랙 없을 때 대신" — 없다는 표지 바로 뒤에 대신 표지가 이어지면 이 부정은 대안 요청이다
            val insteadHere = neg.instead || run {
                var j = idx + 1
                while (j in segs.indices && skippable(segs[j])) j++
                j in segs.indices && segs[j].terms?.any { it.role == Role.NEG && it.instead } == true
            }
            /** 표지에서 step 방향으로 부정 대상 덩어리들을 모은다 */
            fun collectTargets(step: Int): List<IntRange> {
                val targets = ArrayList<IntRange>()
                var cursor = idx
                var chainLeft = 4
                while (chainLeft-- > 0) {
                    var j = cursor + step
                    var sawConj = false
                    var broken = false
                    while (j in segs.indices && skippable(segs[j])) {
                        if (segs[j].has(Role.CONJ)) sawConj = true
                        // "without barbell with dumbbells", "but", "instead" — 새 절이 시작되면 부정은 거기서 끝난다(뒤 장비는 긍정)
                        if (targets.isNotEmpty() && text.substring(segs[j].s, segs[j].e) in lex.chainBreakers) { broken = true; break }
                        j += step
                    }
                    if (broken || j !in segs.indices) break
                    // "스쿼트 하면 무릎 아픈데 대체 운동" — 대안을 물은 대상 앞에 부상 표현(아픈데·무릎)이 끼어 있으면 그것을 건너뛰고 운동을 찾는다(부상은 따로 해석된다)
                    if (insteadHere && targets.isEmpty()) {
                        var guard = 0
                        while (j in segs.indices && guard++ < 6 && !segs[j].unknown && !segs[j].isNum &&
                            segs[j].terms!!.any { it.role == Role.CUE || it.role == Role.JOINT } && segs[j].terms!!.none { it.role == Role.NAME || it.role == Role.EQUIP }) {
                            j += step
                            while (j in segs.indices && skippable(segs[j])) j += step
                        }
                        if (j !in segs.indices) break
                    }
                    val sg = segs[j]
                    if (sg.has(Role.NEG) || sg.has(Role.UNIT) || sg.isNum) break
                    var range = if (sg.unknown) {
                        val (a2, b2) = chunkAt(j); a2..b2
                    } else j..j
                    // 붙여 쓴 이름 복합어("워킹런지"가 워킹 + 런지로 쪼개진 것)는 한 대상이다 — 앞 조각만 빼면 남은 조각이 긍정 이름이 돼 버린다
                    if (!sg.unknown) {
                        fun nameSeg(i: Int) = i in segs.indices && !segs[i].unknown && !segs[i].isNum && segs[i].onlyRoles(Role.NAME)
                        while (nameSeg(range.first) && nameSeg(range.first - 1) && !tok.isWordStart(segs[range.first].s) && !consumed[range.first - 1]) range = (range.first - 1)..range.last
                        while (nameSeg(range.last) && nameSeg(range.last + 1) && !tok.isWordStart(segs[range.last + 1].s) && !consumed[range.last + 1]) range = range.first..(range.last + 1)
                    }
                    // 붙여 쓴 장비 복합어("ケーブルマシン")는 한 대상이다
                    if (!sg.unknown && sg.has(Role.EQUIP)) {
                        fun equipSeg(i: Int) = i in segs.indices && !segs[i].unknown && !segs[i].isNum && segs[i].has(Role.EQUIP)
                        while (equipSeg(range.first - 1) && !tok.isWordStart(segs[range.first].s) && !consumed[range.first - 1]) range = (range.first - 1)..range.last
                        while (equipSeg(range.last + 1) && !tok.isWordStart(segs[range.last + 1].s) && !consumed[range.last + 1]) range = range.first..(range.last + 1)
                    }
                    if (targets.isNotEmpty()) {
                        // 이어지는 대상은 접속어(や·と·도·and)가 있거나, 쉼표·공백으로 나열된 별개 단어일 때만 (스쿼트, 런지 없이)
                        val laterStart = if (step < 0) segs[cursor].s else segs[range.first].s
                        // 부위·관절은 "가슴이랑 등 빼고"처럼 접속어로 이어졌을 때만 같은 부정에 묶는다
                        val resolvable = sg.unknown || (!sg.has(Role.CONCEPT) && (sg.has(Role.NAME) || sg.has(Role.EQUIP))) ||
                            (sawConj && (sg.has(Role.CONCEPT) || sg.has(Role.JOINT)))
                        if (!resolvable) break
                        // 이미 아는 운동을 대상으로 잡은 뒤, 접속어 없이 이어지는 모르는 말("knees cave forward on squats alternatives" 의 cave forward)은 운동 이름이 아니라 문장의 다른 부분이다
                        if (sg.unknown && !sawConj && targets.none { r -> segs[r.first].unknown }) break
                        // 접속어 없이 나열만 된 경우는 같은 종류끼리만 이어 붙인다 ("ベンチプレス ダンベル以外" 의 벤치프레스는 제외 대상이 아니다)
                        val sameKind = segs[targets.last().first].has(Role.EQUIP) == sg.has(Role.EQUIP)
                        if (!sawConj && !(tok.isWordStart(laterStart) && sameKind)) break
                    }
                    targets.add(range)
                    cursor = if (step < 0) range.first else range.last
                }
                return targets
            }
            var cueOnly = false
            fun resolveAll(targets: List<IntRange>): Boolean {
                var any = false
                for (r in targets) {
                    val before = excludeKeys.size
                    if (resolveTarget(segs, r, text, excludeEquip, excludeKeys, consumed, negConcepts, negJoints, insteadHere, insteadConcepts)) {
                        any = true
                        if (insteadHere) similarKeys.addAll(excludeKeys.subList(before, excludeKeys.size))
                    }
                    else if (r.any { segs[it].has(Role.JOINT) || segs[it].has(Role.CUE) }) { cueFlag = true; cueOnly = true }
                }
                return any
            }
            var resolved = resolveAll(collectTargets(if (neg.targetBefore) -1 else 1))
            // "pull up alternatives for home / for bad knees" — "alternatives for" 뒤의 말이 운동·장비가 아니라 상황(집·부상)이면 대안을 물은 대상은 앞에 있다
            if (!resolved && neg.instead && !neg.targetBefore) resolved = resolveAll(collectTargets(-1))
            val any = resolved || cueOnly
            if (any) consumed[idx] = true
        }

        // 1.5) 부정된 관절("무릎 빼고", "without knees")은 그 관절의 부상 프로필로 — 이름 검색이 아니라 부담 큰 동작을 피한다
        val injuries = LinkedHashSet<Joint>(negJoints)

        // 2) 소음/충격 회피 표현 → 점프류 제외 + 집
        var quiet = false
        for (idx in segs.indices) if (!consumed[idx] && segs[idx].has(Role.QUIET)) { quiet = true; if (!segs[idx].has(Role.CONCEPT)) consumed[idx] = true }
        if (quiet) lex.moveGroups["jump"]?.let { excludeKeys.addAll(it.keys) }

        // 3) 부상: 단서(痛い/아플 때/bad)에 걸린 관절은 목표가 아니라 피할 부위. 단서마다 가장 가까운 관절 하나(와 접속어로 이어진 관절들)만 쓴다.
        val cue = cueFlag || segs.indices.any { !consumed[it] && segs[it].has(Role.CUE) }
        // "stiff neck", "tight hamstrings" — 단서이면서 풀어 달라는 증상 표현(stiff·tight·sore)이기도 한 말은 스트레칭 의도로도 센다.
        // 단, 운동 자체를 물은 질의("sore knees leg workout")는 안전한 운동을 찾는 것이라 스트레칭으로 바꾸지 않는다.
        var stretchFromCue = false
        if (cue) {
            val asksWorkout = segs.any { sg -> !sg.unknown && !sg.isNum && text.substring(sg.s, sg.e) in WORKOUT_WORDS }
            if (!asksWorkout) stretchFromCue = segs.indices.any { !consumed[it] && segs[it].has(Role.CUE) && segs[it].has(Role.STRETCH) }
            for (idx in segs.indices) {
                if (consumed[idx] || !segs[idx].has(Role.CUE)) continue
                val own = segs[idx].terms?.firstNotNullOfOrNull { t -> if (t.role == Role.CUE) t.joint else null }
                if (own != null) { injuries.add(own); consumed[idx] = true; continue }
                consumed[idx] = true
                val near = nearestJoint(segs, consumed, idx, text) ?: continue
                for (j in near) { injuries.add(segs[j].terms!!.firstNotNullOf { it.joint }); consumed[j] = true }
            }
            // 단서 없이 남은 관절 표기는 목표 부위로 쓰이므로 건드리지 않는다
        }

        // 3.5) 세부 부위: 부위 + 수식어(상부/下部/rear …) → 세부 개념. 한글·가나는 부위 뒤, 라틴은 부위 앞 수식어가 흔하다.
        val refined = HashMap<Int, Concept>()
        for (idx in segs.indices) {
            if (consumed[idx]) continue
            val mod = segs[idx].terms?.firstNotNullOfOrNull { it.mod } ?: continue
            val latin = text[segs[idx].s].code < 0x250
            val order = if (latin) intArrayOf(1, -1) else intArrayOf(-1, 1)
            for (step in order) {
                var j = idx + step
                var guard = 0
                while (j in segs.indices && guard++ < 3 && segs[j].onlyRoles(Role.NOISE)) j += step
                if (j !in segs.indices || consumed[j]) continue
                val base = refined[j] ?: segs[j].terms?.firstOrNull { it.role == Role.CONCEPT }?.concept ?: continue
                val target = lex.refine(base, mod) ?: continue
                refined[j] = target
                consumed[idx] = true
                break
            }
        }

        // 3.8) 맥락 운동: "벤치 하기 전에 어깨 예열", "ランニング前に必ずやる準備運動", "warm up shoulders before bench day" —
        // "전에/前に/before" 가 붙은 운동·모르는 덩어리는 찾는 대상이 아니라 그 운동을 하기 전이라는 상황이다. 찾는 것은 나머지 말(부위·웜업·스트레칭)이다.
        // 한·일어는 운동이 표지 앞에, 영어는 표지 뒤에 온다. 찾는 내용(부위·목표·스트레칭)이 따로 있을 때만 적용한다.
        run {
            val hasTarget = segs.indices.any { i -> !consumed[i] && !segs[i].unknown && !segs[i].isNum && segs[i].terms!!.any { it.role == Role.CONCEPT || it.role == Role.STRETCH || it.role == Role.JOINT } }
            if (!hasTarget) return@run
            fun contextOnly(i: Int) = i in segs.indices && !consumed[i] && (segs[i].unknown || (!segs[i].isNum && segs[i].onlyRoles(Role.NAME)))
            for (idx in segs.indices) {
                val sg = segs[idx]
                if (sg.unknown || sg.isNum) continue
                val key = text.substring(sg.s, sg.e)
                val latin = key in BEFORE_LATIN
                if (!latin && key !in BEFORE_CJK) continue
                val step = if (latin) 1 else -1
                var j = idx + step
                var guard = 0
                while (j in segs.indices && guard++ < 3 && (skippable(segs[j]) || consumed[j])) j += step
                var taken = 0
                while (contextOnly(j) && taken < 3) {
                    if (segs[j].unknown) {
                        // 모르는 덩어리는 글자 단위 조각이라 덩어리 전체를 한 번에 맥락으로 돌린다("サッカー前の脚の準備" 의 サッカー)
                        val (a, b) = chunkAt(j)
                        for (i in a..b) consumed[i] = true
                        j = if (step > 0) b + 1 else a - 1
                    } else { consumed[j] = true; j += step }
                    taken++
                }
                if (taken > 0) consumed[idx] = true
            }
        }

        // 4) 긍정 요소
        val concepts = ArrayList<Concept>()
        val equipments = LinkedHashSet<Equipment>()
        var home = quiet
        var stretch = stretchFromCue
        var level = 0
        var regress = false
        val numbers = ArrayList<String>()
        var highReps = false
        var positiveName = false
        val unknownChunks = ArrayList<String>()
        var jointPlain = false
        var loneModifier = false
        var timeOnlyNumbers = true

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
                    if (nextKey == null || nextKey !in TIME_UNITS) timeOnlyNumbers = false
                } else {
                    val terms = sg.terms!!
                    val concept = refined[idx] ?: terms.firstOrNull { it.role == Role.CONCEPT }?.concept
                    if (concept != null) concepts.add(concept)
                    terms.firstOrNull { it.role == Role.EQUIP }?.let { equipments.addAll(it.equipment) }
                    if (terms.any { it.role == Role.HOME }) home = true
                    if (terms.any { it.role == Role.STRETCH }) stretch = true
                    terms.firstOrNull { it.role == Role.LEVEL }?.let { level = it.level; if (it.regress) regress = true }
                    val contentRole = terms.any { it.role == Role.CONCEPT || it.role == Role.EQUIP || it.role == Role.HOME || it.role == Role.STRETCH }
                    if (!contentRole) {
                        // 부위에 붙지 못한 수식어(front squat 의 front)는 이름의 일부 — 한 글자 한자 수식어는 무시
                        if (terms.any { it.role == Role.NAME }) positiveName = true
                        else if (terms.any { it.role == Role.MODIFIER } && sg.e - sg.s >= 2) loneModifier = true
                        else if (terms.any { it.role == Role.JOINT }) jointPlain = true
                    }
                }
            }
            idx++
        }
        if (jointPlain) positiveName = true
        // "首の後ろが張る" — 부위·스트레칭 의도가 분명한 질의의 수식어("後ろ")는 이름 조각이 아니라 부위 설명이다
        if (loneModifier && !(stretch && concepts.isNotEmpty())) positiveName = true
        // "허리 아플 때 스트레칭": 아픈 관절은 피할 부위이기도 하지만 스트레칭에서는 풀어 줄 부위다 — 그 부위 스트레칭을 보여 준다
        if (stretch && concepts.isEmpty() && equipments.isEmpty() && injuries.isNotEmpty() && !positiveName) {
            for (j in injuries) lex.jointStretchTarget(j)?.let { concepts.add(it) }
        }
        // 스트레칭 단독("스트레칭", "ストレッチ")은 가장 쓸모 있는 일반 스트레칭 목록, 다른 이름 조각이 섞였으면("스트레칭 폴") 이름 검색, 부위와 함께면 의도 검색
        if (stretch && concepts.isEmpty() && equipments.isEmpty()) {
            val general = lex.conceptById["stretchgeneral"]
            if (general != null && !positiveName && unknownChunks.isEmpty()) concepts.add(general) else positiveName = true
        }

        // "전신 스트레칭": 전신은 특정 부위가 아니라서 부위별 스트레칭 신호가 없다 — 가장 쓸모 있는 일반 스트레칭 목록으로
        if (stretch && concepts.isNotEmpty() && concepts.all { it.id == "fullbody" }) {
            lex.conceptById["stretchgeneral"]?.let { concepts.clear(); concepts.add(it) }
        }

        concepts.addAll(insteadConcepts)
        // "座りっぱなしでお尻が痛い" — 자세 개념(posture)은 구체적인 부위 개념이 함께 있으면 그 부위가 목표다
        val distinctConcepts = concepts.distinctBy { it.id }.let { list -> if (list.size > 1) list.filter { it.id != "fatloss" }.ifEmpty { list } else list }
            .let { list -> if (list.size > 1 && list.any { it.id.startsWith("muscle:") }) list.filter { it.id != "posture" }.ifEmpty { list } else list }
        var effectiveConcepts: List<Concept> = distinctConcepts
        // "다이어트 근력 운동" — 살 빼기에 근력 단서가 붙으면 유산소가 아니라 몸을 쓰는 근력·복합 동작을 보여 준다
        if (effectiveConcepts.any { it.id == "fatloss" } && segs.any { sg -> !sg.unknown && !sg.isNum && text.substring(sg.s, sg.e) in STRENGTH_CUES }) {
            lex.conceptById["fatlossstrength"]?.let { fs -> effectiveConcepts = effectiveConcepts.filter { it.id != "fatloss" } + fs }
        }
        // "덤벨로 전신" — 전신 + 장비는 전신 복합 동작(버피·스윙 같은 특수 동작이 아니라 스쿼트·로우·프레스 같은 기본기)
        if (effectiveConcepts.size == 1 && effectiveConcepts[0].id == "fullbody" && equipments.isNotEmpty() && !stretch) {
            lex.conceptById["fullbodybasic"]?.let { effectiveConcepts = listOf(it) }
        }

        // 부상만 있고 목표 부위가 없으면: 그 관절에 안전한 운동 전반
        if (effectiveConcepts.isEmpty() && injuries.isNotEmpty() && !positiveName && similarKeys.isEmpty()) {
            val prefer = injuries.flatMap { lex.injuryProfiles[it]?.prefer.orEmpty() }
            if (prefer.isNotEmpty()) {
                effectiveConcepts = listOf(Concept("injury-safe:" + injuries.joinToString(","), emptySet(), emptySet(), prefer, emptyList(), emptySet(), emptyList(), true, false, false))
            }
        }

        // 모르는 덩어리가 든 "단어 전체"가 카탈로그 어휘의 오타로 보이면("レッグレーズ" ≈ レッグレイズ) 이름이다
        val typoWord = unknownChunks.isNotEmpty() && tok.words.any { w ->
            val start = tok.strict.indexOf(w)
            start >= 0 && segs.any { sg -> sg.unknown && !consumed[segs.indexOf(sg)] && sg.s >= start && sg.e <= start + w.length } &&
                w.length >= 4 && vocab.nearest(w, relaxed = true).isNotEmpty()
        }
        val nameLikeUnknown = typoWord || unknownChunks.any { chunk -> chunk.length >= 2 && vocab.nearest(chunk, relaxed = true).isNotEmpty() } ||
            // 장비만 알아들었고 나머지를 모르면 그 나머지는 운동 이름일 가능성이 높다("바벨 로오")
            (unknownChunks.isNotEmpty() && effectiveConcepts.isEmpty() && !home && equipments.isNotEmpty())
        val hasContentSignal = effectiveConcepts.isNotEmpty() || equipments.isNotEmpty() || home
        val seeds = buildSeeds(tok, segs, consumed)

        // 모르는 덩어리가 남았는데 부위·장비·집 같은 내용 신호가 없으면(수준·제외 표현뿐) 그 덩어리가 운동 이름이다
        val grammarRemnantsOnly = (injuries.isNotEmpty() || negConcepts.isNotEmpty() || excludeEquip.isNotEmpty() || excludeKeys.isNotEmpty() || similarKeys.isNotEmpty()) && unknownChunks.all { it.length <= 3 }
        val onlyModifiers = !hasContentSignal && unknownChunks.isNotEmpty() && !grammarRemnantsOnly
        var mode = when {
            !positiveName && !nameLikeUnknown && !onlyModifiers && (hasContentSignal || level != 0 || excludeEquip.isNotEmpty() || excludeKeys.isNotEmpty() || injuries.isNotEmpty() || negConcepts.isNotEmpty()) -> Mode.INTENT
            !positiveName && !nameLikeUnknown && unknownChunks.isEmpty() -> Mode.BROWSE
            else -> Mode.NAME
        }
        // 군더더기만 있는 질의는 목록(브라우즈). 단, 숫자만 있거나 1~2글자면 이름 검색으로 보낸다(무관한 전체 목록을 주지 않는다).
        var finalSeeds = seeds
        if (mode == Mode.BROWSE) {
            // 이름에도 쓰이는 군더더기("lift", "set")만 남은 질의는 그 단어 자체로 이름 검색한다
            val keepAmbiguous = seeds.firstOrNull().orEmpty()
            if (keepAmbiguous.isNotEmpty()) { mode = Mode.NAME; finalSeeds = listOf(keepAmbiguous) }
            // "朝5분でできる運動", "10 minute workout" — 시간만 말한 수치는 이름이 아니라 군더더기라서 목록(브라우즈)이다
            else if ((numbers.isNotEmpty() && !timeOnlyNumbers) || text.length <= 2) { mode = Mode.NAME; finalSeeds = listOf(tok.words) }
        }
        if (mode == Mode.NAME && finalSeeds.isEmpty()) finalSeeds = listOf(tok.words)
        return QueryPlan(
            mode = mode, concepts = effectiveConcepts, equipments = equipments, home = home, stretch = stretch, level = level, regress = regress,
            excludeEquip = excludeEquip, excludeKeys = excludeKeys, injuries = injuries, numbers = numbers, highReps = highReps,
            seeds = finalSeeds, hasUnknown = unknownChunks.isNotEmpty(), similarTo = similarKeys,
            excludeConcepts = negConcepts.distinctBy { it.id },
            combineAll = effectiveConcepts.size > 1 && effectiveConcepts.any { it.id in COMBINE_IDS }
        )
    }

    /**
     * 단서 위치 idx 에 걸린 관절 조각들의 인덱스. 일본어·한국어는 관절이 단서 앞(膝が痛い), 영어 형용사 단서는 뒤(bad knees)·명사 단서는 앞(knee pain).
     * 접속어로 이어진 관절(무릎이랑 허리)도 함께 돌려준다.
     */
    private fun nearestJoint(segs: List<Seg>, consumed: BooleanArray, idx: Int, text: String): List<Int>? {
        fun isJoint(i: Int) = !consumed[i] && segs[i].terms?.any { it.joint != null && it.role == Role.JOINT } == true
        fun skippable(i: Int) = !segs[i].unknown && !segs[i].isNum && (segs[i].onlyRoles(Role.NOISE) || segs[i].has(Role.CONJ))
        fun scan(from: Int, step: Int): Int? {
            var i = from + step
            var guard = 0
            while (i in segs.indices && guard++ < 8) {
                if (isJoint(i)) return i
                if (!skippable(i)) return null
                i += step
            }
            return null
        }
        val left = scan(idx, -1)
        val right = scan(idx, 1)
        val cueKey = text.substring(segs[idx].s, segs[idx].e)
        val latin = cueKey.isNotEmpty() && cueKey[0].code < 0x250
        fun weak(i: Int?) = i != null && segs[i].terms!!.filter { it.role == Role.JOINT }.all { it.weakJoint }
        val pick = when {
            left != null && right != null -> when {
                weak(left) && !weak(right) -> right
                weak(right) && !weak(left) -> left
                latin && cueKey in lex.adjectiveCues -> right
                else -> left
            }
            else -> left ?: right
        } ?: return null
        val out = arrayListOf(pick)
        // 접속어로 이어진 같은 쪽 관절들
        val step = if (pick < idx) -1 else 1
        var cur = pick
        while (true) {
            var i = cur + step
            var sawConj = false
            while (i in segs.indices && skippable(i)) { if (segs[i].has(Role.CONJ)) sawConj = true; i += step }
            if (sawConj && i in segs.indices && isJoint(i)) { out.add(i); cur = i } else break
        }
        return out
    }

    /** 부정 대상 하나를 해석해 제외 조건에 반영한다. 반영했으면 true. */
    private fun resolveTarget(
        segs: List<Seg>, range: IntRange, text: String,
        excludeEquip: MutableSet<Equipment>, excludeKeys: MutableList<SigKey>, consumed: BooleanArray,
        negConcepts: MutableList<Concept>, negJoints: MutableSet<Joint>,
        instead: Boolean = false, insteadConcepts: MutableList<Concept> = ArrayList()
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
        if (terms.any { it.role == Role.CUE }) return false
        val concept = terms.firstOrNull { it.role == Role.CONCEPT }?.concept
        if (terms.any { it.role == Role.JOINT }) {
            // 관절: 무릎·손목처럼 부위 개념이 없으면 부상 프로필, 어깨·허리처럼 부위이기도 하면 그 부위 운동도 뺀다. 등·back 은 부위로만 쓴다.
            var done = false
            terms.firstOrNull { it.role == Role.JOINT && it.joint != null && !it.weakJoint }?.joint?.let { negJoints.add(it); done = true }
            // 관절 돌봄 개념(무릎·발목·손목 운동)은 부위 전체가 아니라 그 관절 주변이라서, 부정하면 부상 프로필만 건다
            if (concept != null && !concept.id.endsWith("care")) { negConcepts.add(concept); done = true }
            if (done) for (i in range) consumed[i] = true
            return done
        }
        val eq = terms.firstOrNull { it.role == Role.EQUIP }
        if (eq != null) {
            val found = LinkedHashSet<Equipment>()
            for (i in range) segs[i].terms?.firstOrNull { it.role == Role.EQUIP }?.let { found.addAll(it.equipment); consumed[i] = true }
            // "ケーブルマシン" — 구체 장비 뒤의 일반 머신은 같은 기구의 설명이라서 머신 전체를 뺀다는 뜻이 아니다
            if (found.size > 1) found.remove(Equipment.MACHINE)
            excludeEquip.addAll(found)
            return true
        }
        val compound = range.last > range.first && range.all { i -> !segs[i].unknown && segs[i].onlyRoles(Role.NAME) }
        val key = if (compound) text.substring(first.s, segs[range.last].e) else text.substring(first.s, first.e)
        val move = terms.firstNotNullOfOrNull { it.move }
        if (move != null) {
            val after = text.substring(minOf(text.length, segs[range.last].e))
            if (move.id == "bench" && text.substring(first.s, first.e) in plainBenchKeys) {
                // "ベンチがなくても", "no bench" — 벤치가 없으면 벤치·랙이 필요한 바벨·머신·케이블 운동은 못 한다(벤치프레스를 빼는 것과는 다르다: 그쪽은 아래 move.keys 로만 제외)
                excludeEquip.addAll(BENCHLESS_EXCLUDED_EQUIPMENT)
                excludeKeys.addAll(move.keys)
            } else if (move.id == "rack" && !after.startsWith("pull") && !after.startsWith("ぷる") && !after.startsWith("풀")) {
                // "스쿼트 랙 없이" — 랙이 없으면 바벨 스쿼트를 못 한다. 바벨을 빼고 랙 없이 하는 스쿼트류를 보여 준다.
                excludeEquip.add(Equipment.BARBELL)
                lex.conceptById["squatany"]?.let { insteadConcepts.add(it) }
            } else excludeKeys.addAll(move.keys)
            consumed[range.first] = true
            return true
        }
        if (terms.any { it.role == Role.NAME } || compound) {
            excludeKeys.add(SigKey(key, false))
            // 같은 동작의 다른 언어 표기도 함께 제외 (데드리프트 → deadlift, 데드리프트)
            synonyms.index[key].orEmpty().forEach { g ->
                g.expansions.forEach { exp -> SearchText.strictKey(exp).takeIf { it.length >= 3 }?.let { excludeKeys.add(SigKey(it, false)) } }
            }
            for (i in range) consumed[i] = true
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
        if (concept != null && instead) {
            // "pallof press alternative for anti rotation" — 대안을 물은 대상이 부위·목표 개념이면 그 개념 운동이 곧 대안이다(빼지 않고 목표로 삼는다)
            insteadConcepts.add(concept)
            consumed[range.first] = true
            return true
        }
        if (concept != null) {
            // "가슴 말고", "no legs", "유산소 빼고" — 부정된 부위·목표는 긍정 목표로 더하지 않고 그 부위 운동을 뺀다
            negConcepts.add(concept)
            consumed[range.first] = true
            return true
        }
        return false
    }

    // ── 이름 검색용 단어열 ───────────────────────────────────────────────────────────────────

    /**
     * 질의에서 군더더기·수치·부정·부상 표현을 뗀 단어열 후보.
     * 이름에도 쓰이는 군더더기("up", "with")가 있으면 그것을 남긴 후보가 먼저, 군더더기를 모두 뗀 후보가 그다음(원문 단어 경계는 유지).
     * 아무것도 뗄 게 없으면 원문 단어열과 같다.
     */
    private fun buildSeeds(tok: SearchText.Tokenized, segs: List<Seg>, consumed: BooleanArray): List<List<String>> {
        val text = tok.strict
        // 운동 이름이 따로 있는데 "볼더 숄더" 같은 목표 표현이 함께 오면(lateral raises for boulder shoulders) 목표 표현은 이름 검색에서 뺀다
        val hasName = segs.indices.any { i -> !consumed[i] && segs[i].terms?.let { t -> t.any { it.role == Role.NAME } && t.none { it.role == Role.CONCEPT } } == true }
        fun goalOnly(sg: Seg): Boolean = hasName && sg.terms?.any { it.role == Role.CONCEPT && it.concept?.id in GOAL_DROP } == true
        fun build(keepAmbiguous: Boolean, splitSegments: Boolean = false): List<String> {
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
                        val contentRole = terms.any { it.role == Role.CONCEPT || it.role == Role.EQUIP || it.role == Role.NAME || it.role == Role.JOINT || it.role == Role.STRETCH || it.role == Role.MODIFIER }
                        when {
                            goalOnly(sg) -> false
                            contentRole -> true
                            keepAmbiguous && key in ambiguousNoise -> true
                            terms.any { it.role == Role.NOISE && !it.particle } && isDubiousNoise(tok, segs, idx) -> true
                            else -> false
                        }
                    }
                }
                if (!keep) { flush(); continue }
                // 붙여 쓴 CJK 복합어를 사전 조각 단위로 쪼개 본다 (シーテッドロウマシン → シーテッド / ロウ / マシン)
                if (splitSegments && !sg.unknown) flush()
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
        // 이름에도 쓰이는 군더더기를 남긴 쪽이 더 구체적이라 먼저, 모두 뗀 쪽은 그다음(엔진이 약간 감점한다)
        if (b.isNotEmpty() && b != a) out.add(b)
        if (a.isNotEmpty()) out.add(a)
        // 붙여 쓴 복합어를 조각으로 쪼갠 후보는 마지막 보조 후보(앞선 후보들로 확실한 일치가 없을 때만 쓰인다)
        val c = build(false, splitSegments = true)
        if (c.size > 1 && c !in out) out.add(c)
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
        /** 이름 검색에서는 뺄 수 있는 "목표" 개념 — 운동 이름과 함께 오면 이름이 이긴다 */
        private val GOAL_DROP = setOf("bouldershoulder", "bigarms", "glutegoal", "sixpack", "legslim", "fatloss", "posture", "backthick", "backwide", "armpit", "hipdip")
        private val STRENGTH_CUES: Set<String> = listOf("筋トレ", "筋力トレーニング", "筋力", "ウエイト", "ウェイト", "웨이트", "근력운동", "근력", "strength", "weights", "weight training", "resistance training", "lifting", "筋トレメニュー")
            .map { SearchText.strictKey(it) }.toSet()
        private val WORKOUT_WORDS: Set<String> = listOf("workout", "workouts", "exercise", "exercises", "training", "routine", "program", "運動", "トレ", "トレーニング", "筋トレ", "メニュー", "운동", "트레이닝", "루틴", "프로그램")
            .map { SearchText.strictKey(it) }.toSet()
        private const val UNKNOWN_COST = 30
        private const val SEG_COST = 6
        private const val NUM_COST = 2
        private const val NOISE_EXTRA_COST = 4
        private const val MAX_TERM_LEN = 40
        /** 맥락 표지: 영어는 뒤에 운동이 오고("before bench day"), 한·일어는 앞에 온다("벤치 하기 전에", "ランニング前に") */
        private val BEFORE_LATIN = listOf("before", "ahead of", "prior to").map { SearchText.strictKey(it) }.toSet()
        private val BEFORE_CJK = listOf("前に", "前の", "前には", "前で", "前から", "전에", "전엔", "이전에", "하기 전에", "하기전에").map { SearchText.strictKey(it) }.toSet()
        /** "machines are all busy" 의 all 처럼 이름과 철자가 겹쳐도 수량 표현이면 부정 대상을 찾을 때 건너뛴다 */
        private val QUANTIFIERS = listOf("all", "every", "both", "everything", "each").map { SearchText.strictKey(it) }.toSet()
        /** "なくしたい"·"없애고 싶다" 처럼 없앤다는 동사의 NEG 표기 — 군살 개념 뒤에서는 부정이 아니라 목표다 */
        private val REDUCE_KEYS = listOf("なく", "なくし", "無く", "無くし", "なくす", "無くす", "なくせ", "없애", "없애고", "없애기").map { SearchText.strictKey(it) }.toSet()
        private val FAT_GOAL_IDS = setOf("backfat", "legslim", "hipdip", "armpit", "fatloss", "belly")
        private val BENCHLESS_EXCLUDED_EQUIPMENT = setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE)
        private val plainBenchKeys: Set<String> = listOf("bench", "benches", "ベンチ", "ベンチ台", "벤치", "벤치대", "flat bench").map { SearchText.strictKey(it) }.toSet()
        /** 다른 부위와 겹쳐 쓰는 개념 — "하체 워밍업", "앉아서 하는 복근" 은 두 조건에 모두 맞는 운동을 앞세운다 */
        private val COMBINE_IDS = setOf("warmup", "pos-seated", "pos-lying", "pos-standing", "foamroll", "obj:chair", "obj:wall")
        private val TYPO_ROLES = setOf(Role.CONCEPT, Role.STRETCH, Role.EQUIP, Role.NOISE)
        private const val TYPO_MIN_LOOSE = 4
        private const val TYPO_MIN_LATIN = 6
        private const val TYPO_MAX_WORDS = 8
        private const val HIGH_REPS = 20
        private val FREE_NOT_NEG_BEFORE = setOf("weight", "motion", "hand", "style", "form", "flow", "fall", "standing", "range", "running", "lift", "lifting").map { SearchText.strictKey(it) }.toSet()
        private val LESS_NEG_BEFORE = setOf("workout", "exercise", "training", "routine", "program", "plan").map { SearchText.strictKey(it) }.toSet()
        private val TIME_UNITS = setOf("분", "分", "分間", "min", "mins", "minute", "minutes", "분간", "초", "秒", "sec", "secs", "second", "seconds", "時間", "시간", "hour", "hours", "hr", "hrs", "日", "일", "day", "days", "週", "주", "week", "weeks", "ヶ月", "개월", "month", "months").map { SearchText.strictKey(it) }.toSet()
        private val REP_UNITS = setOf("회", "개", "回", "번", "rep", "time", "個").map { SearchText.strictKey(it) }.toSet()
    }
}

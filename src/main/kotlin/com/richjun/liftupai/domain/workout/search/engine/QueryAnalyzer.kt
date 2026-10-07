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
        val negJoints = LinkedHashSet<Joint>()
        var cueFlag = false

        fun chunkAt(idx: Int): Pair<Int, Int> {
            // idx 에서 시작하는 모르는 글자 덩어리의 [from, to] 범위
            var a = idx; var b = idx
            while (a - 1 >= 0 && segs[a - 1].unknown) a--
            while (b + 1 < segs.size && segs[b + 1].unknown) b++
            return a to b
        }

        fun skippable(sg: Seg) = !sg.unknown && !sg.isNum && sg.onlyRoles(Role.NOISE, Role.CONJ)

        // 1) 부정: 표지 + 대상
        for (idx in segs.indices) {
            val neg = segs[idx].terms?.firstOrNull { it.role == Role.NEG } ?: continue
            if (consumed[idx]) continue
            val targets = ArrayList<IntRange>()
            var cursor = idx
            val step = if (neg.targetBefore) -1 else 1
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
                val sg = segs[j]
                if (sg.has(Role.NEG) || sg.has(Role.UNIT) || sg.isNum) break
                val range = if (sg.unknown) {
                    val (a2, b2) = chunkAt(j); a2..b2
                } else j..j
                if (targets.isNotEmpty()) {
                    // 이어지는 대상은 접속어(や·と·도·and)가 있거나, 쉼표·공백으로 나열된 별개 단어일 때만 (스쿼트, 런지 없이)
                    val laterStart = if (step < 0) segs[cursor].s else segs[range.first].s
                    // 부위·관절은 "가슴이랑 등 빼고"처럼 접속어로 이어졌을 때만 같은 부정에 묶는다
                    val resolvable = sg.unknown || (!sg.has(Role.CONCEPT) && (sg.has(Role.NAME) || sg.has(Role.EQUIP))) ||
                        (sawConj && (sg.has(Role.CONCEPT) || sg.has(Role.JOINT)))
                    if (!resolvable) break
                    // 접속어 없이 나열만 된 경우는 같은 종류끼리만 이어 붙인다 ("ベンチプレス ダンベル以外" 의 벤치프레스는 제외 대상이 아니다)
                    val sameKind = segs[targets.last().first].has(Role.EQUIP) == sg.has(Role.EQUIP)
                    if (!sawConj && !(tok.isWordStart(laterStart) && sameKind)) break
                }
                targets.add(range)
                cursor = if (step < 0) range.first else range.last
            }
            var any = false
            for (r in targets) {
                val before = excludeKeys.size
                if (resolveTarget(segs, r, text, excludeEquip, excludeKeys, consumed, negConcepts, negJoints)) {
                    any = true
                    if (neg.instead) similarKeys.addAll(excludeKeys.subList(before, excludeKeys.size))
                }
                else if (r.any { segs[it].has(Role.JOINT) || segs[it].has(Role.CUE) }) { cueFlag = true; any = true }
            }
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
        if (cue) {
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

        // 4) 긍정 요소
        val concepts = ArrayList<Concept>()
        val equipments = LinkedHashSet<Equipment>()
        var home = quiet
        var stretch = false
        var level = 0
        var regress = false
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
                    val concept = refined[idx] ?: terms.firstOrNull { it.role == Role.CONCEPT }?.concept
                    if (concept != null) concepts.add(concept)
                    terms.firstOrNull { it.role == Role.EQUIP }?.let { equipments.addAll(it.equipment) }
                    if (terms.any { it.role == Role.HOME }) home = true
                    if (terms.any { it.role == Role.STRETCH }) stretch = true
                    terms.firstOrNull { it.role == Role.LEVEL }?.let { level = it.level; if (it.regress) regress = true }
                    val contentRole = terms.any { it.role == Role.CONCEPT || it.role == Role.EQUIP || it.role == Role.HOME || it.role == Role.STRETCH }
                    if (!contentRole) {
                        // 부위에 붙지 못한 수식어(front squat 의 front)는 이름의 일부 — 한 글자 한자 수식어는 무시
                        if (terms.any { it.role == Role.NAME } || (terms.any { it.role == Role.MODIFIER } && sg.e - sg.s >= 2)) positiveName = true
                        else if (terms.any { it.role == Role.JOINT }) jointPlain = true
                    }
                }
            }
            idx++
        }
        if (jointPlain) positiveName = true
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

        val distinctConcepts = concepts.distinctBy { it.id }.let { list -> if (list.size > 1) list.filter { it.id != "fatloss" }.ifEmpty { list } else list }
        var effectiveConcepts: List<Concept> = distinctConcepts

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
            else if (numbers.isNotEmpty() || text.length <= 2) { mode = Mode.NAME; finalSeeds = listOf(tok.words) }
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
        negConcepts: MutableList<Concept>, negJoints: MutableSet<Joint>
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
            if (concept != null) { negConcepts.add(concept); done = true }
            if (done) for (i in range) consumed[i] = true
            return done
        }
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
        private const val UNKNOWN_COST = 30
        private const val SEG_COST = 6
        private const val NUM_COST = 2
        private const val NOISE_EXTRA_COST = 4
        private const val MAX_TERM_LEN = 40
        /** 다른 부위와 겹쳐 쓰는 개념 — "하체 워밍업", "앉아서 하는 복근" 은 두 조건에 모두 맞는 운동을 앞세운다 */
        private val COMBINE_IDS = setOf("warmup", "pos-seated", "pos-lying", "pos-standing")
        private const val HIGH_REPS = 20
        private val FREE_NOT_NEG_BEFORE = setOf("weight", "motion", "hand", "style", "form", "flow", "fall", "standing", "range", "running", "lift", "lifting").map { SearchText.strictKey(it) }.toSet()
        private val LESS_NEG_BEFORE = setOf("workout", "exercise", "training", "routine", "program", "plan").map { SearchText.strictKey(it) }.toSet()
        private val REP_UNITS = setOf("회", "개", "回", "번", "rep", "time", "個").map { SearchText.strictKey(it) }.toSet()
    }
}

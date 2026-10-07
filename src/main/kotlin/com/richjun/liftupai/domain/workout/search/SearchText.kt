package com.richjun.liftupai.domain.workout.search

import java.text.Normalizer
import java.util.Locale

/**
 * 운동 검색용 텍스트 정규화 유틸 (DB/Spring 비의존).
 *
 * - fold: NFKC(전각/반각 통일) + 소문자 + 가타카나→히라가나
 * - words: 공백/기호(・·-/ 등) 및 라틴↔CJK 경계로 단어 분리, 라틴 단어는 복수형 s 를 떼어 낸다
 * - looseKey: 오타·표기 변이에 관대한 키 (한글은 자모 분해 + 쌍자음/ㅐㅔ/ㅟ 접기, 일본어는 장음/촉음/작은 모음 가나 제거)
 * - choseong: 한글 초성열 (ㅅㅋㅌ 검색)
 * - approxSubstring: 편집거리(전치 포함) 기반 부분문자열 근사 매칭
 */
object SearchText {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val JONG = arrayOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )

    /** 쌍자음 → 평음, ㅐ→ㅔ, ㅒ→ㅖ→ㅔ, ㅘ→ㅝ, ㅙ/ㅚ→ㅞ, ㅟ→ㅣ (외래어 표기 변이 흡수) */
    private val JAMO_FOLD = mapOf(
        'ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ',
        'ㅐ' to 'ㅔ', 'ㅒ' to 'ㅔ', 'ㅖ' to 'ㅔ', 'ㅘ' to 'ㅝ', 'ㅙ' to 'ㅞ', 'ㅚ' to 'ㅞ', 'ㅟ' to 'ㅣ'
    )

    fun isHangulSyllable(c: Char): Boolean = c.code in HANGUL_BASE..HANGUL_END

    fun isCompatConsonant(c: Char): Boolean = c.code in 0x3131..0x314E

    /** 질의 원문 상한(문자). 이보다 긴 입력은 앞부분만 본다 — 검색 비용을 입력 길이와 무관하게 묶는다. */
    const val MAX_QUERY_CHARS = 160

    /** 한 질의에서 다루는 최대 단어 수 */
    const val MAX_QUERY_WORDS = 14

    /** NFKC + 소문자. 입력 상한을 먼저 적용한다(NFKC 는 길이를 늘릴 수 있으므로 2배까지 허용). */
    fun normalize(raw: String): String {
        val head = if (raw.length > MAX_QUERY_CHARS * 2) raw.substring(0, MAX_QUERY_CHARS * 2) else raw
        // 아포스트로피는 단어를 가르지 않고 지운다 (captain's → captains)
        val n = Normalizer.normalize(head, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it != '\'' && it != '\u2019' && it != '`' }
        return if (n.length > MAX_QUERY_CHARS) n.substring(0, MAX_QUERY_CHARS) else n
    }

    /** NFKC + 소문자 + 가타카나→히라가나. NFKC 가 호환 자모를 conjoining 자모로 바꾸므로 호환 자모로 되돌린다. */
    fun fold(raw: String): String = foldNormalized(normalize(raw))

    /** normalize 를 이미 거친 문자열을 접는다 (글자 수는 그대로 — 위치가 1:1 로 대응한다). */
    fun foldNormalized(n: String): String {
        val sb = StringBuilder(n.length)
        for (c in n) {
            val code = c.code
            when {
                code == 0x30F4 -> sb.append('ば')  // ヴ → バ (ヴァ→バァ; 뒤따르는 작은 가나는 looseKey 가 접는다)
                code in 0x30A1..0x30F6 -> sb.append((code - 0x60).toChar())
                code in 0x1100..0x1112 -> sb.append(CHO[code - 0x1100])
                code in 0x1161..0x1175 -> sb.append(JUNG[code - 0x1161])
                code in 0x11A8..0x11C2 -> sb.append(JONG[code - 0x11A8 + 1].first())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private enum class Script { LATIN, CJK, OTHER }

    private fun scriptOf(c: Char): Script = when {
        !c.isLetterOrDigit() && c != 'ー' -> Script.OTHER
        c.code < 0x250 -> Script.LATIN
        else -> Script.CJK
    }

    /**
     * 질의를 분석 단위로 쪼갠 결과.
     * - words: 정규화된 단어(라틴은 복수형 s 제거)
     * - strict: 단어를 이은 키
     * - kata: strict 의 각 글자가 원문에서 가타카나였는지(조사 판별에 쓴다 — 접힌 키에서는 가타카나/히라가나를 구분할 수 없다)
     * - starts: strict 에서 각 단어가 시작하는 위치
     */
    class Tokenized(val words: List<String>, val strict: String, val kata: BooleanArray, val starts: IntArray) {
        private val startSet: Set<Int> = starts.toHashSet()
        fun isWordStart(i: Int): Boolean = i == 0 || i in startSet
        fun isWordEnd(j: Int): Boolean = j == strict.length || j in startSet
    }

    /** 단어 분리. 분리자: 공백·기호·・, 라틴↔CJK 경계. 라틴 단어는 복수형 s 제거. 단어 수는 MAX_QUERY_WORDS 로 제한. */
    fun words(raw: String): List<String> = tokenize(raw).words

    fun tokenize(raw: String): Tokenized {
        val n = normalize(raw)
        val f = foldNormalized(n)
        val words = ArrayList<String>()
        val kata = ArrayList<Boolean>()
        val starts = ArrayList<Int>()
        val cur = StringBuilder()
        val curKata = ArrayList<Boolean>()
        var curScript = Script.OTHER
        var offset = 0
        fun flush() {
            if (cur.isNotEmpty()) {
                val latin = curScript == Script.LATIN
                val w = if (latin) stem(cur.toString()) else cur.toString()
                if (words.size < MAX_QUERY_WORDS) {
                    words.add(w)
                    starts.add(offset)
                    for (k in w.indices) kata.add(!latin && curKata[k])
                    offset += w.length
                }
                cur.setLength(0)
                curKata.clear()
            }
        }
        for (idx in f.indices) {
            val c = f[idx]
            val s = scriptOf(c)
            if (s == Script.OTHER) {
                flush(); curScript = Script.OTHER
            } else {
                if (curScript != s) flush()
                curScript = s
                cur.append(c)
                val o = n[idx].code
                // ー 는 앞 글자의 종류를 따른다
                curKata.add(if (o == 0x30FC && curKata.isNotEmpty()) curKata.last() else o in 0x30A1..0x30FA || o in 0x31F0..0x31FF)
            }
        }
        flush()
        val strict = words.joinToString("")
        return Tokenized(words, strict, kata.toBooleanArray(), starts.toIntArray())
    }

    /** 가벼운 복수형 처리: squats→squat, lunges→lunge, presses→press, crunches→crunch, flies→fly (press/glass 같은 ss 는 유지) */
    fun stem(word: String): String {
        if (word.length > 4 && word.endsWith("sses")) return word.dropLast(2)
        if (word.length > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes"))) return word.dropLast(2)  // crunches→crunch
        if (word.length > 4 && word.endsWith("ies")) return word.dropLast(3) + "y"  // flies→fly
        if (word.length >= 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
            return word.dropLast(1)
        }
        return word
    }

    fun strictKey(words: List<String>): String = words.joinToString("")

    fun strictKey(raw: String): String = strictKey(words(raw))

    /** 오타·표기 변이 관대 키 */
    fun looseKey(strict: String): String {
        val sb = StringBuilder(strict.length * 2)
        for (c in strict) {
            when {
                isHangulSyllable(c) -> appendJamo(sb, c, fold = true)
                c == 'ー' || c == 'っ' || c == 'ぁ' || c == 'ぃ' || c == 'ぅ' || c == 'ぇ' || c == 'ぉ' -> Unit
                else -> sb.append(JAMO_FOLD[c] ?: c)
            }
        }
        return sb.toString()
    }

    /** 한글 음절을 호환 자모 열로 분해 (종성은 초성과 같은 문자로 매핑되어 편집거리 비교가 자연스럽다) */
    fun jamo(strict: String): String {
        val sb = StringBuilder(strict.length * 2)
        for (c in strict) if (isHangulSyllable(c)) appendJamo(sb, c, fold = false) else sb.append(c)
        return sb.toString()
    }

    private fun appendJamo(sb: StringBuilder, c: Char, fold: Boolean) {
        val idx = c.code - HANGUL_BASE
        val l = idx / (21 * 28)
        val v = (idx % (21 * 28)) / 28
        val t = idx % 28
        fun put(ch: Char) = sb.append(if (fold) (JAMO_FOLD[ch] ?: ch) else ch)
        put(CHO[l])
        put(JUNG[v])
        JONG[t].forEach { put(it) }
    }

    /** 한글 음절의 초성열 (비한글은 무시) */
    fun choseong(raw: String): String {
        val sb = StringBuilder()
        for (c in fold(raw)) {
            if (isHangulSyllable(c)) {
                val l = (c.code - HANGUL_BASE) / (21 * 28)
                sb.append(JAMO_FOLD[CHO[l]] ?: CHO[l])
            } else if (isCompatConsonant(c)) {
                sb.append(JAMO_FOLD[c] ?: c)
            }
        }
        return sb.toString()
    }

    /** 초성만으로 이루어진 질의인가 (공백 제외 2자 이상) */
    fun isChoseongQuery(raw: String): Boolean {
        val letters = fold(raw).filter { !it.isWhitespace() }
        return letters.length >= 2 && letters.all { isCompatConsonant(it) }
    }

    /**
     * pattern 이 text 의 어떤 부분문자열과도 가질 수 있는 최소 편집거리 (치환/삽입/삭제/인접 전치 = 1).
     * maxK 를 넘으면 maxK+1 을 반환한다.
     */
    fun approxSubstring(pattern: String, text: String, maxK: Int): Int {
        val m = pattern.length
        if (m == 0) return 0
        if (text.isEmpty()) return minOf(m, maxK + 1)
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        var best = minOf(m, maxK + 1)
        for (j in 1..text.length) {
            cur[0] = 0
            val tc = text[j - 1]
            for (i in 1..m) {
                val cost = if (pattern[i - 1] == tc) 0 else 1
                var v = minOf(prev[i - 1] + cost, prev[i] + 1, cur[i - 1] + 1)
                if (i > 1 && j > 1 && pattern[i - 1] == text[j - 2] && pattern[i - 2] == tc) {
                    v = minOf(v, prev2[i - 2] + 1)
                }
                cur[i] = v
            }
            if (cur[m] < best) best = cur[m]
            if (best == 0) return 0
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return best
    }

    /** 두 문자열의 Damerau 편집거리(인접 전치 포함). maxK 를 넘으면 maxK+1 을 반환한다. */
    fun editDistance(a: String, b: String, maxK: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > maxK) return maxK + 1
        if (a == b) return 0
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxK) return maxK + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return minOf(prev[b.length], maxK + 1)
    }

    /** 키 길이(자모/문자 수)에 따른 허용 오타 수 */
    fun allowedTypos(len: Int): Int = when {
        len < 4 -> 0
        len < 9 -> 1
        else -> 2
    }
}

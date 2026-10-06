package com.richjun.liftupai.domain.workout.search

import java.text.Normalizer
import java.util.Locale

/**
 * 운동 검색용 텍스트 정규화 유틸 (DB/Spring 비의존).
 *
 * - fold: NFKC(전각/반각 통일) + 소문자 + 가타카나→히라가나
 * - words: 공백/기호(・·-/ 등) 및 라틴↔CJK 경계로 단어 분리, 라틴 단어는 복수형 s 를 떼어 낸다
 * - looseKey: 오타·표기 변이에 관대한 키 (한글은 자모 분해 + 쌍자음/ㅐㅔ/ㅟ 접기, 일본어는 장음/촉음 제거)
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

    /** NFKC + 소문자 + 가타카나→히라가나. NFKC 가 호환 자모를 conjoining 자모로 바꾸므로 호환 자모로 되돌린다. */
    fun fold(raw: String): String {
        val n = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(n.length)
        for (c in n) {
            val code = c.code
            when {
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

    /** 단어 분리. 분리자: 공백·기호·・, 라틴↔CJK 경계. 라틴 단어는 복수형 s 제거. */
    fun words(raw: String): List<String> {
        val f = fold(raw)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curScript = Script.OTHER
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(if (curScript == Script.LATIN) stem(cur.toString()) else cur.toString())
                cur.setLength(0)
            }
        }
        for (c in f) {
            val s = scriptOf(c)
            if (s == Script.OTHER) {
                flush(); curScript = Script.OTHER
            } else {
                if (curScript != s) flush()
                curScript = s
                cur.append(c)
            }
        }
        flush()
        return out
    }

    /** 가벼운 복수형 처리: squats→squat, lunges→lunge, presses→press (press/glass 같은 ss 는 유지) */
    fun stem(word: String): String {
        if (word.length > 4 && word.endsWith("sses")) return word.dropLast(2)
        if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) {
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
                c == 'ー' || c == 'っ' -> Unit
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

    /** 키 길이(자모/문자 수)에 따른 허용 오타 수 */
    fun allowedTypos(len: Int): Int = when {
        len < 4 -> 0
        len < 9 -> 1
        else -> 2
    }
}

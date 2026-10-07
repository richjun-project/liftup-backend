package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.ExerciseSearchLexicon
import com.richjun.liftupai.domain.workout.search.SearchText

/**
 * 카탈로그와 동의어 사전에 나오는 단어 어휘.
 * - 질의 분석에서 "운동 이름 단어"를 알아보는 데 쓴다(조사·군더더기와 구분).
 * - 오타 교정: 어휘에 없는 단어를 편집거리 가장 가까운 어휘 단어로 바꾼다 (예: 스쿼투 → 스쿼트).
 */
internal class Vocabulary(
    names: Collection<Collection<String>>,
    synonyms: ExerciseSearchLexicon.SynonymTable
) {
    private class Entry(val word: String, val loose: String, val freq: Int)

    /** strict 단어 → 등장한 이름 수 */
    val words: Map<String, Int>

    private val byLooseLength: Map<Int, List<Entry>>

    /** 한글·가나 단어의 글자 수별 목록 (글자 단위 오타 교정용) */
    private val byStrictLength: Map<Int, List<Entry>>

    init {
        val freq = HashMap<String, Int>()
        for (doc in names) {
            val seen = HashSet<String>()
            for (n in doc) for (w in SearchText.words(n)) if (seen.add(w)) freq.merge(w, 1, Int::plus)
        }
        for (s in synonyms.synonyms) {
            for (e in s.expansions) for (w in e) freq.merge(w, 1, Int::plus)
            if (!s.noTypo) for (t in s.triggers) freq.putIfAbsent(t, 1)
        }
        words = freq.filterKeys { isUsable(it) }
        val entries = words.map { (w, f) -> Entry(w, SearchText.looseKey(w), f) }.filter { it.loose.length >= MIN_CORRECT_LEN }
        byLooseLength = entries.groupBy { it.loose.length }
        byStrictLength = words.filterKeys { it.length >= 2 && it[0].code >= 0x250 }.map { (w, f) -> Entry(w, SearchText.looseKey(w), f) }.groupBy { it.word.length }
    }

    fun contains(word: String): Boolean = word in words

    /**
     * 어휘에 없는 단어의 교정 후보(가까운 순, 같은 거리면 많이 쓰이는 순). 후보가 없거나 이미 어휘에 있으면 빈 목록.
     * 두 가지 거리로 찾는다: (a) 자모·문자 편집거리(스쿼투 → 스쿼트), (b) 한글·가나는 글자(음절) 단위 한 칸 오타 —
     * 치환·삽입·삭제·인접 전치(쿼스트 → 스쿼트, 롤웃 → 롤아웃).
     * 짧은 단어는 이웃이 많아 흔한 단어 하나가 뚜렷하게 앞설 때만 교정한다(로오 → 로우).
     * relaxed 는 "이 덩어리가 오타 난 이름일까?"를 가늠할 때 쓰는 느슨한 기준(자모 2개까지).
     */
    fun nearest(token: String, limit: Int = 2, relaxed: Boolean = false): List<String> {
        if (token in words) return emptyList()
        if (relaxed && token.length < RELAXED_MIN_CHARS) return emptyList()  // 2글자 이하 덩어리는 이름인지 가늠하지 않는다(군더더기가 많다)
        val loose = SearchText.looseKey(token)
        val found = HashMap<String, Pair<Entry, Int>>()
        fun add(e: Entry, d: Int) { val cur = found[e.word]; if (cur == null || d < cur.second) found[e.word] = e to d }

        if (loose.length >= MIN_CORRECT_LEN) {
            var k = minOf(2, SearchText.allowedTypos(loose.length))
            if (relaxed && loose.length >= RELAXED_LEN) k = 2
            if (k > 0) {
                for (len in (loose.length - k)..(loose.length + k)) {
                    for (e in byLooseLength[len].orEmpty()) {
                        val d = SearchText.editDistance(loose, e.loose, k)
                        if (d <= k) add(e, d)
                    }
                }
            }
        }
        if (token.length >= 2 && token[0].code >= 0x250) {
            for (len in (token.length - 1)..(token.length + 1)) {
                for (e in byStrictLength[len].orEmpty()) {
                    if (SearchText.editDistance(token, e.word, 1) <= 1) add(e, 1)
                }
            }
        }
        if (found.isEmpty()) return emptyList()
        val bestD = found.values.minOf { it.second }
        val ranked = found.values.filter { it.second <= bestD }
            .sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.freq }.thenBy { it.first.word })
        if (loose.length < CONFIDENT_LEN || token.length <= 2) {
            val top = ranked.first()
            if (top.first.freq < MIN_SHORT_FREQ) return emptyList()
            val second = ranked.getOrNull(1)
            if (second != null && second.second == top.second && top.first.freq < 2 * second.first.freq) return emptyList()
            return listOf(top.first.word)
        }
        return ranked.take(limit).map { it.first.word }
    }

    private fun isUsable(w: String): Boolean = w.isNotEmpty() && !(w.length == 1) && !w.all { it.isDigit() }

    companion object {
        private const val MIN_CORRECT_LEN = 4
        private const val CONFIDENT_LEN = 6
        private const val MIN_SHORT_FREQ = 8
        private const val RELAXED_LEN = 6
        private const val RELAXED_MIN_CHARS = 3
    }
}

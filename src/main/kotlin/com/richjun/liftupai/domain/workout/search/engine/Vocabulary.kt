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

    init {
        val freq = HashMap<String, Int>()
        for (doc in names) {
            val seen = HashSet<String>()
            for (n in doc) for (w in SearchText.words(n)) if (seen.add(w)) freq.merge(w, 1, Int::plus)
        }
        for (s in synonyms.synonyms) {
            for (e in s.expansions) for (w in e) freq.merge(w, 1, Int::plus)
            for (t in s.triggers) freq.putIfAbsent(t, 1)
        }
        words = freq.filterKeys { isUsable(it) }
        val entries = words.map { (w, f) -> Entry(w, SearchText.looseKey(w), f) }.filter { it.loose.length >= MIN_CORRECT_LEN }
        byLooseLength = entries.groupBy { it.loose.length }
    }

    fun contains(word: String): Boolean = word in words

    /**
     * 어휘에 없는 단어의 교정 후보(가까운 순, 같은 거리면 많이 쓰이는 순). 후보가 없거나 이미 어휘에 있으면 빈 목록.
     * 자모 기준 4 미만은 교정하지 않고, 6 미만은 흔한 단어 하나가 뚜렷할 때만 교정한다(오탐 방지).
     */
    fun nearest(token: String, limit: Int = 2): List<String> {
        if (token in words) return emptyList()
        val loose = SearchText.looseKey(token)
        if (loose.length < MIN_CORRECT_LEN) return emptyList()
        val k = minOf(2, SearchText.allowedTypos(loose.length))
        if (k == 0) return emptyList()
        var bestD = k + 1
        val found = ArrayList<Pair<Entry, Int>>()
        for (len in (loose.length - k)..(loose.length + k)) {
            for (e in byLooseLength[len].orEmpty()) {
                val d = SearchText.editDistance(loose, e.loose, minOf(k, bestD))
                if (d > k) continue
                if (d < bestD) bestD = d
                found.add(e to d)
            }
        }
        val ranked = found.filter { it.second <= bestD }
            .sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.freq }.thenBy { it.first.word })
        if (loose.length < CONFIDENT_LEN) {
            // 짧은 단어는 이웃이 많다 — 흔한 단어 하나가 뚜렷하게 앞설 때만 교정한다 (로오 → 로우)
            val top = ranked.firstOrNull() ?: return emptyList()
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
    }
}

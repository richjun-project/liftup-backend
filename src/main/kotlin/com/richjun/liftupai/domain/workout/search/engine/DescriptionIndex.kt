package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.SearchText
import java.util.BitSet
import kotlin.math.ln

/**
 * 설명문(instructions·tips) 검색 색인 — 이름으로는 못 찾는 동작 묘사 질의를 위한 재현율 계층.
 *
 * 토큰: 영어는 단어(복수형 s 제거), 한·일은 글자 bigram(+한자 한 글자), 거기에 자세·관절·동작 개념 토큰(@prone, @bend …).
 * 점수: BM25(k1=1.2, b=0.5). 호출자가 이름 점수보다 낮은 대역에서 쓴다.
 * 질의 토큰은 길이가 짧고(상한 [MAX_QUERY_TOKENS]) 포스팅은 문서 수(~1200)로 묶여 있어 질의당 비용이 일정하다.
 */
internal class DescriptionIndex(docs: List<IDoc>) {

    class Hit(val index: Int, val bm25: Double, val coverage: Double)

    private val n = docs.size
    private val postings: Map<String, IntArray>
    private val lengths = IntArray(n)
    private val avgLen: Double
    private val idf = HashMap<String, Double>()

    /** 설명문이 하나라도 있는 문서 수 — 0 이면 이 계층은 꺼진다 */
    val indexed: Int

    init {
        val acc = HashMap<String, ArrayList<Int>>(1 shl 16)
        var withText = 0
        var total = 0L
        for (d in docs) {
            val text = d.doc.descriptions.values.filter { it.isNotBlank() }
            if (text.isEmpty()) continue
            withText++
            val tf = HashMap<String, Int>()
            for (t in text) tokenize(t, tf)
            var len = 0
            for ((tok, c) in tf) {
                len += c
                acc.getOrPut(tok) { ArrayList(4) }.add((d.index shl 8) or minOf(c, 255))
            }
            lengths[d.index] = len
            total += len
        }
        indexed = withText
        avgLen = if (withText == 0) 1.0 else total.toDouble() / withText
        postings = acc.mapValues { (_, v) -> v.toIntArray() }
        for ((tok, p) in postings) idf[tok] = idfOf(p.size)
    }

    private fun idfOf(df: Int): Double = ln(1.0 + (indexed - df + 0.5) / (df + 0.5))

    /**
     * 질의 텍스트로 검색. [allowed] 가 있으면 그 문서만 센다. 점수 순 상위 [limit] 개.
     * coverage 는 질의 토큰의 idf 질량 중 문서가 가진 비율(0..1) — 호출자가 "충분히 맞는가"를 가르는 기준이다.
     */
    fun search(text: String, allowed: BitSet? = null, limit: Int = 60): List<Hit> {
        if (indexed == 0) return emptyList()
        val q = HashMap<String, Int>()
        tokenize(text, q)
        if (q.isEmpty()) return emptyList()
        if (q.size > MAX_QUERY_TOKENS) {
            val keep = q.keys.sortedByDescending { idf[it] ?: idfOf(1) }.take(MAX_QUERY_TOKENS).toSet()
            q.keys.retainAll(keep)
        }
        val score = DoubleArray(n)
        val matched = DoubleArray(n)
        var mass = 0.0
        for (tok in q.keys) {
            val w = if (tok[0] == '@') CONCEPT_WEIGHT else 1.0
            val p = postings[tok]
            val tokIdf = idf[tok] ?: idfOf(1)
            mass += tokIdf * w
            if (p == null) continue
            for (e in p) {
                val di = e ushr 8
                if (allowed != null && !allowed.get(di)) continue
                val tf = (e and 0xFF).toDouble()
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[di] / avgLen))
                score[di] += tokIdf * w * norm
                matched[di] += tokIdf * w
            }
        }
        if (mass <= 0) return emptyList()
        val hits = ArrayList<Hit>()
        for (i in 0 until n) if (matched[i] > 0) hits.add(Hit(i, score[i], matched[i] / mass))
        hits.sortByDescending { it.bm25 }
        return if (hits.size > limit) hits.subList(0, limit) else hits
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.5
        private const val CONCEPT_WEIGHT = 1.6
        private const val MAX_QUERY_TOKENS = 48

        private val STOP: Set<String> = ("the|a|an|and|or|of|to|your|you|with|on|in|at|for|as|is|are|be|it|its|then|until|that|this|each|both|while|from|by|into|so|if|can|keep|keeping|do|does|my|me|i|am|im|want|how|what|which|get|use|using|make|any|all|" +
            "ensure|ensuring|maintain|maintaining|slightly|bit|little|just|also|not|no|up|down|out|over|off|ready|position|starting|start|repeat|desired|number|times|reps|rep|set|sets|exercise|workout|training|movement|motion|body").split("|").toSet()

        /** 접어서 토큰으로 쪼갠다. tf 에 (토큰 → 횟수)를 더한다. */
        fun tokenize(raw: String, tf: MutableMap<String, Int>) {
            val folded = DescriptionLexicon.canon(SearchText.fold(raw))
            val concepts = HashSet<String>()
            DescriptionLexicon.conceptsIn(folded, concepts)
            for (c in concepts) tf.merge("@$c", 1, Int::plus)
            val run = StringBuilder()
            var latin = false
            fun flush() {
                if (run.isEmpty()) return
                if (latin) {
                    val w = SearchText.stem(run.toString())
                    if (w.length >= 2 && w !in STOP) tf.merge(w, 1, Int::plus)
                } else {
                    val s = run.toString()
                    if (s.length == 1) {
                        if (isHan(s[0])) tf.merge(s, 1, Int::plus)
                    } else {
                        for (i in 0 until s.length - 1) tf.merge(s.substring(i, i + 2), 1, Int::plus)
                        for (c in s) if (isHan(c)) tf.merge(c.toString(), 1, Int::plus)
                    }
                }
                run.setLength(0)
            }
            for (c in folded) {
                val isLatin = c.code < 0x250 && c.isLetter()
                val isCjk = c.code >= 0x250 && (c.isLetter() || c == 'ー')
                when {
                    isLatin -> { if (!latin) flush(); latin = true; run.append(c) }
                    isCjk -> { if (latin) flush(); latin = false; run.append(c) }
                    else -> { flush(); latin = false }
                }
            }
            flush()
        }

        private fun isHan(c: Char) = c.code in 0x4E00..0x9FFF
    }
}

package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.SearchText
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** 이름 하나를 검색용으로 풀어 둔 구조 */
internal class NameKey(
    val strict: String,
    val loose: String,
    val starts: Set<Int>,
    val wordOf: IntArray,
    val choseong: String,
    val wordCount: Int
) {
    /** 문자 블룸(64비트) — 퍼지 비교 전 값싼 사전 필터 */
    val bloom: Long = bloomOf(loose)

    /** 이름에 들어 있는 동작 단어(머리 단어) 인덱스 집합과 부위 묶음 인덱스 집합 */
    val heads: BitSet = BitSet().also { b -> QueryLexicon.headWords.forEachIndexed { i, k -> if (has(k)) b.set(i) } }
    val regions: BitSet = BitSet().also { b -> QueryLexicon.regionGroups.forEachIndexed { i, g -> if (g.any { has(it) }) b.set(i) } }

    /**
     * 신호 표기가 이름에 있는가.
     * 라틴 표기는 단어 시작에서만 인정(row 가 arrow 에 걸리지 않게), `~` 표기는 단어 끝에서 끝나야 한다.
     */
    fun has(k: SigKey): Boolean {
        var from = 0
        while (true) {
            val i = strict.indexOf(k.key, from)
            if (i < 0) return false
            val e = i + k.key.length
            val startOk = !k.latin || i == 0 || i in starts
            val endOk = !k.wordEnd || e == strict.length || e in starts
            if (startOk && endOk) return true
            from = i + 1
        }
    }

    companion object {
        fun bloomOf(s: String): Long {
            var b = 0L
            for (c in s) b = b or (1L shl (c.code and 63))
            return b
        }

        fun build(name: String): NameKey {
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
            return NameKey(strict, SearchText.looseKey(strict), starts, wordOf, SearchText.choseong(name), words.size)
        }
    }
}

internal typealias SigKey = QueryLexicon.SigKey

/** 색인된 운동 문서 */
internal class IDoc(val index: Int, val doc: ExerciseSearchDoc, val names: List<NameKey>, val rank: Double) {
    /** 가장 단어가 많은 이름 기준 단어 수 — 의도 랭킹에서 단순한 이름을 선호하는 데 사용 */
    val wordCount: Int = names.maxOfOrNull { it.wordCount } ?: 0

    /** 신호 일치용: 표시 이름들 + slug(영문 표기). 표시 이름에 없는 영문 철자까지 잡는다. */
    val sigNames: List<NameKey> = names + NameKey.build(doc.slug.replace('-', ' ').replace('_', ' '))

    val isStretch: Boolean = doc.movementPattern == "STRETCHING" || sigNames.any { n -> STRETCH_KEYS.any { n.has(it) } }

    /** 폼롤러 같은 롤링 운동 — 스트레칭 질의에서는 진짜 스트레칭 뒤로 미룬다 */
    val isRolling: Boolean = sigNames.any { n -> ROLLING_KEYS.any { n.has(it) } }

    companion object {
        private val ROLLING_KEYS = listOf("foam roller", "foam rolling", "フォームローラー", "폼롤러", "폼 롤러").map { SigKey(SearchText.strictKey(it), false) }
        private val STRETCH_KEYS = listOf("stretch", "ストレッチ", "스트레칭", "yoga", "ヨガ", "요가").map { SigKey(SearchText.strictKey(it), false) }
    }
}

/** 신호 키 → 일치하는 문서 비트셋 (키마다 한 번만 계산해 재사용) */
internal class SigIndex(private val docs: List<IDoc>) {
    private val memo = ConcurrentHashMap<String, BitSet>()

    fun hits(k: SigKey): BitSet {
        if (memo.size > MEMO_LIMIT) memo.clear()  // 질의에서 온 임의 키로 메모가 무한히 자라지 않게
        return compute(k)
    }

    private fun compute(k: SigKey): BitSet = memo.getOrPut(k.key + (if (k.wordEnd) "~" else "")) {
        val b = BitSet(docs.size)
        for (d in docs) if (d.sigNames.any { it.has(k) }) b.set(d.index)
        b
    }

    /** 키 목록 중 하나라도 일치하는 문서 */
    fun anyOf(keys: List<SigKey>): BitSet {
        val out = BitSet(docs.size)
        for (k in keys) out.or(hits(k))
        return out
    }

    companion object {
        private const val MEMO_LIMIT = 4096
    }
}

package com.richjun.liftupai.domain.workout.search.rewrite

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchHit

/**
 * LLM 해석 융합 — 하이브리드(어휘+의미) 결과에 LLM 힌트의 어휘 검색 결과를 RRF 로 섞고, LLM 이 낸 제외 장비를 걸러 낸다.
 * 순수·결정적이다(네트워크·시계 없음). 결정적 분석기의 하드 조건은 여기서 모르고, 호출자([com.richjun.liftupai.domain.workout.search.semantic.HybridSearch])가
 * 힌트 후보를 만들 때 먼저 걸러서 넘기고 장비 제외도 마지막에 한 번 더 확인한다.
 *
 * 점수 = Σ weight / (k + 순위). 하이브리드 상위 [Config.baseDepth] 개는 weight 1, i 번째 힌트의 후보(상위 [Config.perHint])는 weight [Config.hintWeight] × [Config.decay]^i.
 * 융합한 순서 뒤에 하이브리드 결과의 나머지를 원래 순서로 이어 붙인다 — 길이·페이지 넘김은 하이브리드와 같다.
 */
object RewriteFusion {
    data class Config(
        val rrfK: Int = 60,
        val baseWeight: Double = 1.0,
        val hintWeight: Double = 1.0,
        val decay: Double = 0.85,
        val baseDepth: Int = 10,
        val perHint: Int = 3,
        /** 힌트가 카탈로그의 운동 이름과 정확히 같으면 그 운동(후보 1위)에 곱하는 배율 — 코치가 정확한 운동을 짚은 것이니 이웃 운동보다 믿는다 */
        val exactBoost: Double = 2.0
    )

    /** 힌트 하나의 후보 — [exact] 는 힌트 문자열이 운동 이름과 정확히 같은가(그러면 [hits] 첫째가 그 운동) */
    /** [intended]: 조건을 걸기 전 그 힌트의 1위 — LLM 이 실제로 가리킨 운동 */
    class HintHits(val hits: List<ExerciseSearchHit>, val exact: Boolean = false, val intended: ExerciseSearchDoc? = null)

    private const val SCORE_SCALE = 1000.0

    /**
     * @param base 하이브리드 결과(전체 순서)
     * @param hintHits 힌트별 후보(힌트 순서대로, 이미 하드 조건을 통과한 것만)
     * @param forbid LLM 이 낸 제외 장비
     * @param pinnedId 있으면 이 운동을 1위로 지킨다(정확·단어 경계 이름 일치)
     * 제외 장비로 걸러 내면 아무것도 안 남는 경우에는 거르지 않는다(LLM 의 오판이 빈 결과를 만들지 않게).
     */
    fun fuse(
        base: List<ExerciseSearchHit>,
        hintHits: List<HintHits>,
        forbid: Set<Equipment>,
        pinnedId: Long? = null,
        config: Config = Config()
    ): List<ExerciseSearchHit> {
        val k = config.rrfK.toDouble()
        val score = HashMap<Long, Double>()
        val doc = HashMap<Long, ExerciseSearchDoc>()
        val baseRank = HashMap<Long, Int>()
        base.take(config.baseDepth).forEachIndexed { r, h ->
            score.merge(h.doc.id, config.baseWeight / (k + r + 1), Double::plus)
            doc[h.doc.id] = h.doc; baseRank[h.doc.id] = r
        }
        var w = config.hintWeight
        for (hint in hintHits) {
            hint.hits.take(config.perHint).forEachIndexed { r, h ->
                score.merge(h.doc.id, (if (r == 0 && hint.exact) w * config.exactBoost else w) / (k + r + 1), Double::plus)
                doc.putIfAbsent(h.doc.id, h.doc)
            }
            w *= config.decay
        }
        val order = score.entries.sortedWith(
            compareByDescending<Map.Entry<Long, Double>> { it.key == pinnedId }
                .thenByDescending { it.value }
                .thenBy { baseRank[it.key] ?: Int.MAX_VALUE }
                .thenBy { it.key }
        )
        val fused = ArrayList<ExerciseSearchHit>(base.size + order.size)
        val seen = HashSet<Long>()
        for (e in order) { fused.add(ExerciseSearchHit(doc.getValue(e.key), e.value * SCORE_SCALE)); seen.add(e.key) }
        val floor = fused.lastOrNull()?.score ?: 0.0
        var tail = 0
        for (h in base) if (seen.add(h.doc.id)) fused.add(ExerciseSearchHit(h.doc, floor - (++tail) * 1e-6))
        if (forbid.isEmpty()) return fused
        val kept = fused.filter { it.doc.equipment !in forbid }
        return kept.ifEmpty { fused }
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

/**
 * 대체 운동 순위 (순수 로직). 같은 동작 패턴 → 근육 겹침 → 티어·인기도 순으로 점수를 매긴다.
 *
 * 예전에는 같은 카테고리·근육 겹침 후보를 "이름순으로 30개" 가져와 그 안에서만 점수를 매겼다.
 * 이름 앞쪽(숫자·A로 시작하는 희귀 운동)만 후보가 되어 대표 운동은 후보에도 못 들었다.
 */
object SubstituteRanker {

    data class Ranked(val exercise: Exercise, val score: Double, val similarity: Double)

    /**
     * @param curatedIds 운영자가 큐레이션한 대체 목록(exercise_substitutions)에 있는 운동 — 가산점
     * @param isAllowed 사용자 장비·부상 필터 (비로그인이면 항상 true)
     */
    fun rank(
        original: Exercise,
        pool: Collection<Exercise>,
        curatedIds: Set<Long>,
        isAllowed: (Exercise) -> Boolean,
        limit: Int = 10,
    ): List<Ranked> {
        val originalMuscles = original.muscleGroups.toSet()
        val originalPattern = original.movementPattern?.takeIf { it.isNotBlank() && it != "OTHER" }
        return pool.asSequence()
            .filter { it.id != original.id && isAllowed(it) }
            .map { alt ->
                val altMuscles = alt.muscleGroups.toSet()
                val union = (originalMuscles union altMuscles).size
                val similarity = if (union > 0) (originalMuscles intersect altMuscles).size.toDouble() / union else 0.0
                val samePattern = originalPattern != null && alt.movementPattern == originalPattern
                val sameFamily = !samePattern && originalPattern != null && family(alt.movementPattern) == family(originalPattern)
                var score = similarity * 40.0
                if (samePattern) score += 30.0 else if (sameFamily) score += 24.0
                if (alt.category == original.category) score += 6.0
                // 같은 동작을 다른 장비로 하는 것이 진짜 대체(벤치프레스 → 덤벨 프레스). 같은 장비의 그립·각도 변형은 사실상 같은 운동이라 소폭 감점
                if (alt.equipment != original.equipment) score += 8.0
                else if (samePattern) score -= 6.0
                score += when (alt.recommendationTier) {
                    RecommendationTier.ESSENTIAL -> 16.0
                    RecommendationTier.STANDARD -> 5.0
                    else -> -10.0
                }
                score += alt.popularity.coerceIn(0, 100) * 0.08
                // 플랜 후보로 검수된 운동(희귀·변형 운동 제외)을 앞세운다
                if (alt.isPlanEligible) score += 6.0
                if (alt.id in curatedIds) score += 10.0
                Triple(alt, score, similarity) to samePattern
            }
            // 같은 패턴이거나 근육이 꽤 겹치는 것만 대체로 인정(전혀 다른 운동이 점수 합으로 올라오지 않게)
            .filter { (t, samePattern) -> samePattern || family(t.first.movementPattern) == family(originalPattern) && originalPattern != null || t.third >= 0.34 || t.first.id in curatedIds }
            .map { (t, _) -> Ranked(t.first, t.second, t.third) }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.exercise.id })
            .take(limit)
            .toList()
    }

    private val EQUIPMENT_SUFFIX = Regex("_(BARBELL|DUMBBELL|MACHINE|CABLE)$")

    /** 동작 패턴 계열: HORIZONTAL_PRESS_BARBELL / _DUMBBELL / _MACHINE 은 모두 HORIZONTAL_PRESS */
    private fun family(pattern: String?): String? = pattern?.replace(EQUIPMENT_SUFFIX, "")
}

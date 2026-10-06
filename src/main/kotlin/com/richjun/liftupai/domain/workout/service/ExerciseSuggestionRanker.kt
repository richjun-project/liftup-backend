package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

/**
 * "운동 추가" 화면의 개인화 추천 순위 (순수 로직).
 *
 * 2026-10-06 실사용: 하체 카테고리를 이름순으로 5페이지까지 넘기다 아무것도 추가하지 않고 나갔다.
 * 처음 보이는 몇 개가 그 사람의 장비로 할 수 있고, 다치지 않고, 수준에 맞는 대표 운동이어야 한다.
 *
 * 걸러내기(필수): 카테고리·제외 id·보유 장비·부상·난이도 상한
 * 점수: 부족한 근육 적합도 + 집중 부위 + 티어 + 인기도 + 수준 적합 → 같은 동작 패턴이 몰리지 않게 다양성 감점으로 탐욕 선택
 */
object ExerciseSuggestionRanker {

    enum class Why { MUSCLE, FOCUS, ESSENTIAL, BEGINNER, EQUIPMENT, POPULAR }

    data class Input(
        val category: ExerciseCategory? = null,
        /** 보강하고 싶은(오늘 운동에서 덜 다뤄진) 근육. 비면 근육 조건 없음 */
        val muscles: Set<MuscleGroup> = emptySet(),
        val excludeIds: Set<Long> = emptySet(),
        /** 저장된 보유 장비(Equipment enum 이름). 비면 제한 없음 */
        val allowedEquipment: Set<String> = emptySet(),
        val experience: String? = null,
        val focus: List<FocusArea> = emptyList(),
        val limit: Int = 10,
    )

    data class Suggestion(
        val exercise: Exercise,
        val score: Double,
        val why: Why,
        /** why 가 MUSCLE 이면 맞닿은 근육, FOCUS 면 맞닿은 집중 부위 이름 */
        val matched: List<String> = emptyList(),
    )

    private const val PATTERN_REPEAT_PENALTY = 7.0

    fun rank(pool: Collection<Exercise>, isRestricted: (Exercise) -> Boolean, input: Input): List<Suggestion> {
        val hardCap = hardDifficultyCap(input.experience)
        val eligible = pool.filter { ex ->
            ex.id !in input.excludeIds &&
                (input.category == null || ex.category == input.category) &&
                TemplatePlanAdapter.equipmentAllowed(ex, input.allowedEquipment) &&
                ex.difficulty <= hardCap &&
                (input.muscles.isEmpty() || ex.muscleGroups.any { it in input.muscles }) &&
                !isRestricted(ex)
        }
        // 특수 운동은 모자랄 때만 채운다
        val mainstream = eligible.filter { it.recommendationTier != RecommendationTier.SPECIALIZED }
        val scored = (mainstream + if (mainstream.size < input.limit) eligible.filter { it.recommendationTier == RecommendationTier.SPECIALIZED } else emptyList())
            .map { score(it, input) }

        // 탐욕 선택: 이미 뽑힌 동작 패턴과 겹치면 감점
        val remaining = scored.toMutableList()
        val picked = mutableListOf<Suggestion>()
        val patternCount = HashMap<String?, Int>()
        while (picked.size < input.limit && remaining.isNotEmpty()) {
            val best = remaining.maxWithOrNull(
                compareBy<Suggestion> { it.score - PATTERN_REPEAT_PENALTY * (patternCount[it.exercise.movementPattern] ?: 0) }
                    .thenByDescending { -it.exercise.id }
            ) ?: break
            remaining.remove(best)
            picked += best
            patternCount.merge(best.exercise.movementPattern, 1, Int::plus)
        }
        return picked
    }

    private fun score(ex: Exercise, input: Input): Suggestion {
        var total = 0.0
        var why = Why.POPULAR
        var matched: List<String> = emptyList()

        if (input.muscles.isNotEmpty()) {
            val common = ex.muscleGroups.intersect(input.muscles)
            val recall = common.size.toDouble() / input.muscles.size
            val precision = common.size.toDouble() / ex.muscleGroups.size.coerceAtLeast(1)
            total += 40.0 * (0.6 * recall + 0.4 * precision)
            if (common.isNotEmpty() && recall >= 0.5) {
                why = Why.MUSCLE
                matched = common.map { it.name }.sorted()
            }
        }
        val focusHit = input.focus.firstOrNull { it.hits(ex) }
        if (focusHit != null) {
            total += 10.0
            if (why == Why.POPULAR) { why = Why.FOCUS; matched = listOf(focusHit.name) }
        }
        total += when (ex.recommendationTier) {
            RecommendationTier.ESSENTIAL -> 20.0
            RecommendationTier.STANDARD -> 8.0
            else -> 0.0
        }
        total += ex.popularity.coerceIn(0, 100) * 0.15
        // 수준 적합: 초보에게는 쉬운 동작을 앞으로, 편안한 난이도를 넘는 동작은 감점
        val comfy = comfortableDifficulty(input.experience)
        if (comfy != null && ex.difficulty > comfy) total -= (ex.difficulty - comfy) * 0.5
        val beginner = TemplateRecommendationEngine.rankOf(input.experience) == 0
        if (beginner && ex.difficulty <= 30) total += 4.0

        if (why == Why.POPULAR) {
            why = when {
                ex.recommendationTier == RecommendationTier.ESSENTIAL -> Why.ESSENTIAL
                beginner && ex.difficulty <= 30 -> Why.BEGINNER
                input.allowedEquipment.isNotEmpty() && ex.equipment?.name in input.allowedEquipment -> Why.EQUIPMENT
                else -> Why.POPULAR
            }
        }
        return Suggestion(ex, total, why, matched)
    }

    /** 이보다 어려운 동작은 제안하지 않는다 (초보: 데드리프트 계열·올림픽 리프트 등 61+ 제외) */
    private fun hardDifficultyCap(experience: String?): Int = when (TemplateRecommendationEngine.rankOf(experience)) {
        0 -> 60
        1 -> 80
        else -> 100
    }

    private fun comfortableDifficulty(experience: String?): Int? = when (TemplateRecommendationEngine.rankOf(experience)) {
        0 -> 50
        1 -> 65
        else -> null
    }
}

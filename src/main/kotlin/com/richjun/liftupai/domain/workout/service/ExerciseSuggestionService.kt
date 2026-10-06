package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.ExerciseDetailV2
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.exception.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * GET /api/v2/exercises/suggestions — 내 장비로 할 수 있고, 다치지 않고, 내 수준에 맞는 운동을 부족한 근육·집중 부위 순으로.
 */
@Service
@Transactional(readOnly = true)
class ExerciseSuggestionService(
    private val catalogCache: ExerciseCatalogCache,
    private val trainingContext: UserTrainingContextResolver,
    private val injuryFilterService: InjuryFilterService,
    private val detailService: ExerciseCatalogDetailService,
    private val sharedSupport: WorkoutV2SharedSupport,
) {
    fun suggest(
        userId: Long,
        category: String?,
        muscleGroups: String?,
        excludeIds: String?,
        limit: Int?,
        localeOverride: String?,
    ): List<ExerciseDetailV2> {
        val locale = sharedSupport.resolveLocale(userId, localeOverride)
        val ctx = trainingContext.resolve(userId)
        val input = ExerciseSuggestionRanker.Input(
            category = category?.takeIf { it.isNotBlank() }?.let { parseCategory(it) },
            muscles = parseMuscles(muscleGroups),
            excludeIds = parseIds(excludeIds),
            allowedEquipment = ctx.equipment,
            experience = ctx.experience,
            focus = ctx.focusAreas,
            limit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        )
        val ranked = ExerciseSuggestionRanker.rank(
            pool = catalogCache.get().all,
            isRestricted = injuryFilterService.restrictionChecker(ctx.injuries),
            input = input,
        )
        val details = detailService.toExerciseDetails(ranked.map { it.exercise }, locale)
        return details.zip(ranked).map { (detail, s) -> detail.copy(reason = reasonText(s, locale)) }
    }

    internal fun reasonText(s: ExerciseSuggestionRanker.Suggestion, locale: String): String = when (s.why) {
        ExerciseSuggestionRanker.Why.MUSCLE -> WorkoutLocalization.message(
            "suggest.reason.muscle", locale,
            s.matched.mapNotNull { runCatching { MuscleGroup.valueOf(it) }.getOrNull() }
                .joinToString(", ") { WorkoutLocalization.muscleGroupName(it, locale) }
        )
        ExerciseSuggestionRanker.Why.FOCUS -> WorkoutLocalization.message(
            "suggest.reason.focus", locale,
            s.matched.firstOrNull()?.let { WorkoutLocalization.message("template.reco.focus.${it.lowercase()}", locale) }.orEmpty()
        )
        ExerciseSuggestionRanker.Why.ESSENTIAL -> WorkoutLocalization.message("suggest.reason.essential", locale)
        ExerciseSuggestionRanker.Why.BEGINNER -> WorkoutLocalization.message("suggest.reason.beginner", locale)
        ExerciseSuggestionRanker.Why.EQUIPMENT -> WorkoutLocalization.message("suggest.reason.equipment", locale)
        ExerciseSuggestionRanker.Why.POPULAR -> WorkoutLocalization.message("suggest.reason.popular", locale)
    }

    private fun parseCategory(raw: String): ExerciseCategory = try {
        ExerciseCategory.valueOf(raw.trim().uppercase())
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid category: $raw")
    }

    private fun parseMuscles(raw: String?): Set<MuscleGroup> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
            try {
                MuscleGroup.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("Invalid muscle group: $it")
            }
        }.toSet()

    private fun parseIds(raw: String?): Set<Long> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toLongOrNull() ?: throw BadRequestException("Invalid exclude id: $it")
        }.toSet()

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
    }
}

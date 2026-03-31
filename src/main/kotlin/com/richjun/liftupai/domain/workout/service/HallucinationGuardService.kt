package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AIPlanExerciseEntry(
    var exerciseId: Long,
    var exerciseName: String,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val order: Int,
    val isCompound: Boolean,
    val targetRPE: Double = 7.0,
    val notes: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AIPlanDayEntry(
    val dayNumber: Int,
    val dayName: String,
    val workoutType: String,
    val estimatedDurationMinutes: Int,
    var exercises: List<AIPlanExerciseEntry>
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AIPlanResponse(
    val planName: String,
    val planDescription: String,
    val splitType: String,
    val totalDays: Int,
    val estimatedWeeks: Int,
    var days: List<AIPlanDayEntry>,
    val coachingNotes: String? = null,
    val progressionModel: String = "LINEAR"
)

enum class RepairReason { ID_MISMATCH, HALLUCINATION_REPLACED, FALLBACK_POPULAR }

data class RepairLog(
    val originalId: Long,
    val originalName: String,
    val replacedWithId: Long,
    val replacedWithName: String,
    val reason: RepairReason
)

data class ValidatedPlan(
    val plan: AIPlanResponse,
    val repairs: List<RepairLog>
)

@Service
class HallucinationGuardService(
    private val exerciseRepository: ExerciseRepository
) {
    private val log = LoggerFactory.getLogger(HallucinationGuardService::class.java)

    fun validateAndRepairPlan(plan: AIPlanResponse): ValidatedPlan {
        val repairs = mutableListOf<RepairLog>()

        val validatedDays = plan.days.map { day ->
            val workoutCategories = mapWorkoutTypeToCategories(day.workoutType)
            val validatedExercises = day.exercises.map { exercise ->
                validateExercise(exercise, workoutCategories, repairs)
            }
            day.copy(exercises = validatedExercises)
        }

        if (repairs.isNotEmpty()) {
            log.warn("Hallucination guard repaired ${repairs.size} exercises in plan '${plan.planName}'")
            repairs.forEach { r ->
                log.info("  Repaired: ${r.originalName}(${r.originalId}) -> ${r.replacedWithName}(${r.replacedWithId}) [${r.reason}]")
            }
        }

        return ValidatedPlan(
            plan = plan.copy(days = validatedDays),
            repairs = repairs
        )
    }

    private fun validateExercise(
        exercise: AIPlanExerciseEntry,
        fallbackCategories: List<ExerciseCategory>,
        repairs: MutableList<RepairLog>
    ): AIPlanExerciseEntry {
        // Step 1: ID exact match
        val found = exerciseRepository.findById(exercise.exerciseId).orElse(null)
        if (found != null) return exercise

        // Step 2: Exact name match first (Bug 4 fix)
        val exactMatch = exerciseRepository.findByNameIgnoreCase(exercise.exerciseName)
        if (exactMatch != null) {
            repairs.add(RepairLog(exercise.exerciseId, exercise.exerciseName, exactMatch.id, exactMatch.name, RepairReason.ID_MISMATCH))
            return exercise.copy(exerciseId = exactMatch.id, exerciseName = exactMatch.name)
        }

        // Step 3: Name fuzzy match - sort by name length ascending (shorter = closer match)
        val byName = exerciseRepository.findByNameContainingIgnoreCase(exercise.exerciseName)
            .sortedBy { it.name.length }
        if (byName.isNotEmpty()) {
            val best = byName.first()
            repairs.add(RepairLog(exercise.exerciseId, exercise.exerciseName, best.id, best.name, RepairReason.ID_MISMATCH))
            return exercise.copy(exerciseId = best.id, exerciseName = best.name)
        }

        // Step 4: Fallback to popular exercise - try multiple categories (Bug 5 fix)
        for (category in fallbackCategories) {
            val fallback = exerciseRepository.findFirstByCategoryAndRecommendationTierOrderByPopularityDesc(
                category,
                RecommendationTier.ESSENTIAL
            ) ?: exerciseRepository.findByCategory(category).firstOrNull()

            if (fallback != null) {
                repairs.add(RepairLog(exercise.exerciseId, exercise.exerciseName, fallback.id, fallback.name, RepairReason.FALLBACK_POPULAR))
                return exercise.copy(exerciseId = fallback.id, exerciseName = fallback.name)
            }
        }

        // No fallback found - return as-is (will be caught by quality validator)
        log.error("No fallback found for exercise: ${exercise.exerciseName} (${exercise.exerciseId})")
        return exercise
    }

    /**
     * Returns a prioritized list of categories to try for fallback exercise resolution.
     * Bug 5 fix: PUSH tries CHEST then SHOULDERS, UPPER tries CHEST then BACK, etc.
     */
    private fun mapWorkoutTypeToCategories(workoutType: String): List<ExerciseCategory> {
        return when (workoutType.uppercase()) {
            "PUSH" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS)
            "PULL" -> listOf(ExerciseCategory.BACK)
            "LEGS" -> listOf(ExerciseCategory.LEGS)
            "UPPER" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS)
            "LOWER" -> listOf(ExerciseCategory.LEGS)
            "FULL_BODY" -> listOf(ExerciseCategory.FULL_BODY, ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.LEGS)
            "CARDIO" -> listOf(ExerciseCategory.CARDIO)
            "CHEST" -> listOf(ExerciseCategory.CHEST)
            "BACK" -> listOf(ExerciseCategory.BACK)
            "SHOULDERS" -> listOf(ExerciseCategory.SHOULDERS)
            "ARMS" -> listOf(ExerciseCategory.ARMS)
            else -> listOf(ExerciseCategory.FULL_BODY, ExerciseCategory.CHEST)
        }
    }

    /**
     * Public single-category mapping for external callers (e.g., AIPlanGenerationService fallback).
     * Returns the primary category for the given workout type.
     */
    fun mapWorkoutTypeToCategory(workoutType: String): ExerciseCategory {
        return mapWorkoutTypeToCategories(workoutType).first()
    }
}

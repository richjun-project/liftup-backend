package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import org.springframework.stereotype.Service

@Service
class CuratedExercisePoolService(
    private val exerciseRepository: ExerciseRepository
) {
    fun getPlanEligibleExercises(): List<Exercise> {
        return exerciseRepository.findAllByIsPlanEligibleTrue()
    }

    fun getPlanEligibleByCategory(category: ExerciseCategory): List<Exercise> {
        return exerciseRepository.findByIsPlanEligibleTrueAndCategory(category)
    }

    /**
     * Generate a formatted exercise list for AI prompt injection.
     * Groups by planCategory, includes id + name + equipment + difficulty.
     */
    fun getPromptReadyExerciseList(availableEquipment: Set<String> = emptySet()): String {
        val allowedEquipment = availableEquipment
            .map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }
            .filter { it.isNotBlank() }
            .toSet()
        val exercises = exerciseRepository.findAllByIsPlanEligibleTrue()
            .filter { exercise ->
                val equipmentName = exercise.equipment?.name
                allowedEquipment.isEmpty() ||
                    equipmentName == null ||
                    equipmentName == "BODYWEIGHT" ||
                    equipmentName in allowedEquipment
            }
        if (exercises.isEmpty()) return "No exercises available."

        return exercises.groupBy { it.planCategory ?: it.category.name.lowercase() }
            .toSortedMap()
            .map { (category, exs) ->
                "$category:\n" + exs.sortedByDescending { it.popularity }.joinToString("\n") {
                    "  - id:${it.id} | ${it.name} | ${it.equipment?.name ?: "BODYWEIGHT"} | difficulty:${it.difficulty} | muscles:${it.muscleGroups.joinToString(",") { mg -> mg.name }}"
                }
            }.joinToString("\n\n")
    }
}

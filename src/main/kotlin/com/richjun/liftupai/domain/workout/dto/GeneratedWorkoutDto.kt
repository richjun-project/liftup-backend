package com.richjun.liftupai.domain.workout.dto

import com.richjun.liftupai.domain.workout.entity.WorkoutType

data class ProgramWarmupSet(val weight: Double, val reps: Int)

data class ProgramSubstituteExercise(
    val exerciseId: Long,
    val name: String,
    val reason: String
)

data class ProgramGeneratedExercise(
    val exerciseId: Long,
    val name: String,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val suggestedWeight: Double?,
    val targetRPE: Double,
    val isCompound: Boolean,
    val warmupSets: List<ProgramWarmupSet>,
    val substitutes: List<ProgramSubstituteExercise>
)

data class GraduationStatusDto(
    val shouldGraduate: Boolean,
    val completionRate: Double,
    val nextProgramCode: String?,
    val nextProgramName: String?,
    val message: String
)

data class GeneratedWorkout(
    val programName: String,
    val weekNumber: Int,
    val dayNumber: Int,
    val dayName: String,
    val isDeloadWeek: Boolean,
    val periodizationPhase: String,
    val workoutType: WorkoutType,
    val estimatedDuration: Int,
    val exercises: List<ProgramGeneratedExercise>,
    val graduationStatus: GraduationStatusDto? = null
)

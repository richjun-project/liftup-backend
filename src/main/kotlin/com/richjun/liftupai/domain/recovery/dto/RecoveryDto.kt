package com.richjun.liftupai.domain.recovery.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class MuscleRecoveryStatus(
    val name: String,
    val recoveryPercentage: Int,
    val lastWorked: String?,
    val estimatedRecoveryTime: Int,
    val status: String
)

data class RecoveryStatusResponse(
    val muscles: List<MuscleRecoveryStatus>
)

data class UpdateRecoveryRequest(
    @field:NotBlank
    val muscleGroup: String,

    @field:Min(1)
    @field:Max(10)
    val feelingScore: Int,

    @field:Min(0)
    @field:Max(10)
    val soreness: Int
)

data class UpdateRecoveryResponse(
    val success: Boolean,
    val updatedStatus: MuscleRecoveryStatus
)

data class RecoveryExercise(
    val name: String,
    val description: String,
    val duration: Int,
    val type: String
)

data class NutritionTip(
    val tip: String,
    val reason: String
)

data class RecoveryRecommendationsResponse(
    val readyMuscles: List<String>,
    val recoveryExercises: List<RecoveryExercise>,
    val nutritionTips: List<NutritionTip>
)
package com.richjun.liftupai.domain.workout.dto.request

data class ApplyTemplateRequest(
    val injuries: List<String>? = null,
    val equipment: List<String>? = null
)

data class SetCurrentDayRequest(
    val dayNumber: Int
)

data class GenerateAIPlanRequest(
    val experienceLevel: String,
    val goals: List<String>,
    val gender: String?,
    val age: Int?,
    val weeklyDays: Int,
    val sessionDuration: Int,
    val equipment: List<String>,
    val injuries: List<String>? = null,
    val focusAreas: List<String>? = null
)

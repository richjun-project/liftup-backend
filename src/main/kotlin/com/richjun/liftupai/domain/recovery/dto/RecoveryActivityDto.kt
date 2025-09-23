package com.richjun.liftupai.domain.recovery.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivityType
import com.richjun.liftupai.domain.recovery.entity.RecoveryIntensity
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

data class RecordActivityRequest(
    @field:NotNull(message = "Activity type is required")
    val activityType: RecoveryActivityType,

    @field:NotNull(message = "Duration is required")
    @field:Min(value = 1, message = "Duration must be at least 1 minute")
    val duration: Int,

    @field:NotNull(message = "Intensity is required")
    val intensity: RecoveryIntensity,

    val notes: String? = null,

    val bodyParts: Set<String> = emptySet(),

    @field:NotNull(message = "Performed time is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val performedAt: LocalDateTime
)

data class RecordActivityResponse(
    val activityId: String,
    val recoveryScore: Int,
    val recoveryBoost: String,
    val nextRecommendation: String,
    val recorded: Boolean
)

data class RecoveryHistoryRequest(
    @JsonFormat(pattern = "yyyy-MM-dd")
    val startDate: String,

    @JsonFormat(pattern = "yyyy-MM-dd")
    val endDate: String,

    val activityType: String? = "all"
)

data class RecoveryHistoryResponse(
    val history: List<DailyRecoveryHistory>,
    val summary: RecoverySummary
)

data class DailyRecoveryHistory(
    @JsonFormat(pattern = "yyyy-MM-dd")
    val date: String,
    val activities: List<RecoveryActivityDetail>,
    val dailyRecoveryScore: Int,
    val muscleSoreness: MuscleSoreness
)

data class RecoveryActivityDetail(
    val activityId: String,
    val activityType: RecoveryActivityType,
    val duration: Int,
    val intensity: RecoveryIntensity,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val performedAt: LocalDateTime,
    val recoveryImpact: String
)

data class MuscleSoreness(
    val overall: Int,
    val details: Map<String, Int>
)

data class RecoverySummary(
    val totalActivities: Int,
    val mostFrequent: String,
    val averageRecoveryScore: Int,
    val trend: String
)
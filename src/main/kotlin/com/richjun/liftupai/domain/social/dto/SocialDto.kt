package com.richjun.liftupai.domain.social.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class ShareWorkoutRequest(
    @field:NotNull
    val sessionId: Long,

    @field:NotBlank
    val shareType: String,

    @field:NotBlank
    val visibility: String
)

data class ShareWorkoutResponse(
    val shareId: String,
    val shareUrl: String,
    val preview: SharePreview
)

data class SharePreview(
    val title: String,
    val stats: String,
    val image: String
)

data class FindPartnersRequest(
    val gymLocation: String?,
    val workoutTime: String?,
    val level: String?
)

data class FindPartnersResponse(
    val partners: List<WorkoutPartner>
)

data class WorkoutPartner(
    val userId: String,
    val nickname: String,
    val level: String,
    val preferredTime: String,
    val workoutSplit: String,
    val matchScore: Int
)
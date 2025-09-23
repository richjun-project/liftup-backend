package com.richjun.liftupai.domain.notification.dto

data class PTScheduleResponse(
    val success: Boolean,
    val message: String,
    val schedulesCreated: Int
)
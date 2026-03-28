package com.richjun.liftupai.domain.trainer.dto

data class TrainerResponse(
    val id: String,
    val imageUrl: String,
)

data class TrainerListResponse(
    val trainers: List<TrainerResponse>,
)

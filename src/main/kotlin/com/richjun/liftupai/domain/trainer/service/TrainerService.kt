package com.richjun.liftupai.domain.trainer.service

import com.richjun.liftupai.domain.trainer.dto.TrainerListResponse
import com.richjun.liftupai.domain.trainer.dto.TrainerResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TrainerService(
    @Value("\${cloud.aws.s3.bucket}") private val bucket: String,
    @Value("\${cloud.aws.region.static}") private val region: String,
    @Value("\${cloud.aws.s3.folder}") private val folder: String,
) {
    private val trainerIds = listOf(
        "spartan",
        "burnout",
        "game_master",
        "influencer",
        "hip_hop",
        "retired_teacher",
        "office_master",
        "la_korean",
        "busan_veteran",
        "soldier",
    )

    fun getTrainers(): TrainerListResponse {
        val baseUrl = "https://$bucket.s3.$region.amazonaws.com/${folder}trainers"
        val trainers = trainerIds.map { id ->
            TrainerResponse(
                id = id,
                imageUrl = "$baseUrl/$id.png",
            )
        }
        return TrainerListResponse(trainers = trainers)
    }
}

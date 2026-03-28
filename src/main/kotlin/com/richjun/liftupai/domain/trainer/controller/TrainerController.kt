package com.richjun.liftupai.domain.trainer.controller

import com.richjun.liftupai.domain.trainer.dto.TrainerListResponse
import com.richjun.liftupai.domain.trainer.service.TrainerService
import com.richjun.liftupai.global.common.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trainers")
class TrainerController(
    private val trainerService: TrainerService,
) {
    @GetMapping
    fun getTrainers(): ResponseEntity<ApiResponse<TrainerListResponse>> {
        val response = trainerService.getTrainers()
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}

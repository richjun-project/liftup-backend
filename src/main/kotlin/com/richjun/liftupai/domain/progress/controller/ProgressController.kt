package com.richjun.liftupai.domain.progress.controller

import com.richjun.liftupai.domain.progress.dto.LevelResponse
import com.richjun.liftupai.domain.progress.service.ProgressService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/progress")
class ProgressController(private val progressService: ProgressService) {

    @GetMapping("/level")
    fun getLevel(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<ApiResponse<LevelResponse>> =
        ResponseEntity.ok(ApiResponse.success(progressService.getLevel(userDetails.getId())))
}

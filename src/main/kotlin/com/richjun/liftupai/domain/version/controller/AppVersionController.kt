package com.richjun.liftupai.domain.version.controller

import com.richjun.liftupai.domain.version.dto.*
import com.richjun.liftupai.domain.version.service.AppVersionService
import com.richjun.liftupai.global.common.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/version")
class AppVersionController(
    private val appVersionService: AppVersionService
) {

    /**
     * 버전 체크 API - 클라이언트 앱에서 호출
     * 인증 불필요 (로그인 전에도 체크 가능해야 함)
     */
    @PostMapping("/check")
    fun checkVersion(
        @Valid @RequestBody request: VersionCheckRequest
    ): ResponseEntity<ApiResponse<VersionCheckResponse>> {
        val response = appVersionService.checkVersion(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 버전 히스토리 조회
     */
    @GetMapping("/history")
    fun getVersionHistory(
        @RequestParam platform: String
    ): ResponseEntity<ApiResponse<List<VersionHistoryResponse>>> {
        val history = appVersionService.getVersionHistory(platform)
        return ResponseEntity.ok(ApiResponse.success(history))
    }

    /**
     * 특정 버전 정보 조회
     */
    @GetMapping("/{id:[0-9]+}")
    fun getVersion(
        @PathVariable id: Long
    ): ResponseEntity<ApiResponse<Any>> {
        val version = appVersionService.getVersion(id)
        return ResponseEntity.ok(ApiResponse.success(version))
    }

    /**
     * 새 버전 등록 (관리자 전용)
     */
    @PostMapping
    fun createVersion(
        @Valid @RequestBody request: CreateVersionRequest
    ): ResponseEntity<ApiResponse<Any>> {
        val version = appVersionService.createVersion(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(mapOf(
                "message" to "새 버전이 등록되었습니다",
                "version" to version
            )))
    }

    /**
     * 버전 정보 수정 (관리자 전용)
     */
    @PutMapping("/{id:[0-9]+}")
    fun updateVersion(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateVersionRequest
    ): ResponseEntity<ApiResponse<Any>> {
        val version = appVersionService.updateVersion(id, request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to "버전 정보가 수정되었습니다",
            "version" to version
        )))
    }

    /**
     * 버전 비활성화 (관리자 전용)
     */
    @DeleteMapping("/{id:[0-9]+}")
    fun deactivateVersion(
        @PathVariable id: Long
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        appVersionService.deactivateVersion(id)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "success" to true,
            "message" to "버전이 비활성화되었습니다"
        )))
    }

    /**
     * 점검 모드 설정 (관리자 전용)
     */
    @PostMapping("/maintenance")
    fun setMaintenanceMode(
        @RequestParam platform: String,
        @RequestParam enabled: Boolean,
        @RequestParam(required = false) message: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        appVersionService.setMaintenanceMode(platform, enabled, message)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "success" to true,
            "message" to if (enabled) "점검 모드가 활성화되었습니다" else "점검 모드가 해제되었습니다"
        )))
    }
}
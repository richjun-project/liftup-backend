package com.richjun.liftupai.domain.auth.controller

import com.richjun.liftupai.domain.auth.dto.*
import com.richjun.liftupai.domain.auth.service.AuthService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<ApiResponse<AuthResponse>> {
        val response = authService.register(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<AuthResponse>> {
        val response = authService.login(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/refresh")
    fun refreshToken(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<ApiResponse<AuthResponse>> {
        val response = authService.refreshToken(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        authService.logout(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(mapOf("success" to true)))
    }

    @GetMapping("/check-nickname")
    fun checkNickname(@RequestParam nickname: String): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val available = authService.checkNickname(nickname)
        val message = if (available) "사용 가능한 닉네임입니다" else "이미 사용 중인 닉네임입니다"

        return ResponseEntity.ok(
            ApiResponse.success(
                mapOf(
                    "available" to available,
                    "message" to message
                )
            )
        )
    }

    @GetMapping("/check-device")
    fun checkDevice(@RequestParam deviceId: String): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val exists = authService.checkExistingDevice(deviceId)
        val message = if (exists) "기존 계정이 있습니다" else "새로운 디바이스입니다"

        return ResponseEntity.ok(
            ApiResponse.success(
                mapOf(
                    "exists" to exists,
                    "message" to message
                )
            )
        )
    }

    // Device-based authentication endpoints
    @PostMapping("/device/register")
    fun registerDevice(@Valid @RequestBody request: DeviceRegisterRequest): ResponseEntity<ApiResponse<DeviceAuthResponse>> {
        val response = authService.registerDevice(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @PostMapping("/device/login")
    fun loginDevice(@Valid @RequestBody request: DeviceLoginRequest): ResponseEntity<ApiResponse<DeviceAuthResponse>> {
        val response = authService.loginDevice(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/device/check")
    fun checkDeviceStatus(@RequestParam deviceId: String): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val exists = authService.checkExistingDevice(deviceId)
        val message = if (exists) "이미 등록된 디바이스입니다" else "새로운 디바이스입니다"

        return ResponseEntity.ok(
            ApiResponse.success(
                mapOf(
                    "exists" to exists,
                    "message" to message
                )
            )
        )
    }

    @PostMapping("/reactivate")
    fun reactivateAccount(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        // LoginRequest를 재사용하여 이메일과 비밀번호를 받음
        val result = authService.reactivateAccount(request.email, request.password)
        return ResponseEntity.ok(ApiResponse.success(result))
    }
}
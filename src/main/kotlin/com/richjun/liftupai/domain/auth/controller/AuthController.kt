package com.richjun.liftupai.domain.auth.controller

import com.richjun.liftupai.domain.auth.dto.*
import com.richjun.liftupai.domain.auth.service.AuthService
import com.richjun.liftupai.domain.auth.service.OAuthService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.i18n.ErrorLocalization
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val oAuthService: OAuthService
) {

    private fun resolveLocale(request: HttpServletRequest): String {
        val acceptLanguage = request.getHeader("Accept-Language") ?: return "en"
        val primary = acceptLanguage.split(",").firstOrNull()?.trim()?.split(";")?.firstOrNull()?.trim() ?: return "en"
        return when {
            primary.startsWith("ko") -> "ko"
            primary.startsWith("ja") -> "ja"
            else -> "en"
        }
    }

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
    fun checkNickname(@RequestParam nickname: String, httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val locale = resolveLocale(httpRequest)
        val available = authService.checkNickname(nickname)
        val message = if (available) {
            ErrorLocalization.message("error.nickname_available", locale)
        } else {
            ErrorLocalization.message("error.nickname_already_in_use", locale)
        }

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
    fun checkDevice(@RequestParam deviceId: String, httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val locale = resolveLocale(httpRequest)
        val exists = authService.checkExistingDevice(deviceId)
        val message = if (exists) {
            ErrorLocalization.message("error.existing_account_found", locale)
        } else {
            ErrorLocalization.message("error.new_device", locale)
        }

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
    fun checkDeviceStatus(@RequestParam deviceId: String, httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val locale = resolveLocale(httpRequest)
        val exists = authService.checkExistingDevice(deviceId)
        val message = if (exists) {
            ErrorLocalization.message("error.device_already_registered", locale)
        } else {
            ErrorLocalization.message("error.new_device", locale)
        }

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

    @PostMapping("/oauth/google")
    fun loginWithGoogle(@RequestBody request: GoogleLoginRequest): ResponseEntity<ApiResponse<OAuthResponse>> {
        val response = oAuthService.loginWithGoogle(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/oauth/apple")
    fun loginWithApple(@RequestBody request: AppleLoginRequest): ResponseEntity<ApiResponse<OAuthResponse>> {
        val response = oAuthService.loginWithApple(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/oauth/kakao")
    fun loginWithKakao(@RequestBody request: KakaoLoginRequest): ResponseEntity<ApiResponse<OAuthResponse>> {
        val response = oAuthService.loginWithKakao(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}

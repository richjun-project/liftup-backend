package com.richjun.liftupai.domain.notification.controller

import com.richjun.liftupai.domain.analytics.ClientInfo
import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService
) {

    @PostMapping("/register")
    fun registerDevice(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: RegisterDeviceRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<RegisterDeviceResponse>> {
        // 본문에 앱 버전이 없으면(1.1.26 이하 앱) 모든 요청에 붙는 X-App-Version 헤더로 채운다 — 기기별 버전을 봐야 푸시 회복을 판단할 수 있다
        val versioned = if (request.appVersion.isNullOrBlank()) request.copy(appVersion = ClientInfo.from(httpRequest).appVersion)
            else request.copy(appVersion = ClientInfo.sanitizeVersion(request.appVersion))
        val response = notificationService.registerDevice(userDetails.getId(), versioned)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @PostMapping("/unregister")
    fun unregisterDevice(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: UnregisterDeviceRequest
    ): ResponseEntity<ApiResponse<UnregisterDeviceResponse>> {
        val response = notificationService.unregisterDevice(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/settings")
    fun getNotificationSettings(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<NotificationSettingsResponse>> {
        val response = notificationService.getNotificationSettings(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PutMapping("/settings")
    fun updateNotificationSettings(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: UpdateNotificationSettingsRequest
    ): ResponseEntity<ApiResponse<UpdateNotificationSettingsResponse>> {
        val response = notificationService.updateNotificationSettings(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/send")
    fun sendTestNotification(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<PushNotificationResponse>> {
        val response = notificationService.sendTestNotification(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/schedule/workout")
    fun scheduleWorkoutReminder(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: WorkoutReminderRequest
    ): ResponseEntity<ApiResponse<ScheduleResponse>> {
        val response = notificationService.scheduleWorkoutReminder(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @DeleteMapping("/schedule/workout/{scheduleId}")
    fun deleteWorkoutSchedule(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable scheduleId: Long
    ): ResponseEntity<ApiResponse<DeleteScheduleResponse>> {
        val response = notificationService.deleteWorkoutSchedule(userDetails.getId(), scheduleId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/history")
    fun getNotificationHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean
    ): ResponseEntity<ApiResponse<NotificationHistoryResponse>> {
        val response = notificationService.getNotificationHistory(userDetails.getId(), page, limit, unreadOnly)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PutMapping("/{notificationId}/read")
    fun markNotificationAsRead(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable notificationId: String
    ): ResponseEntity<ApiResponse<MarkAsReadResponse>> {
        val response = notificationService.markNotificationAsRead(userDetails.getId(), notificationId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}

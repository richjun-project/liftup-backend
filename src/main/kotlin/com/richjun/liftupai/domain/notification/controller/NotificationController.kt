package com.richjun.liftupai.domain.notification.controller

import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
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
        @Valid @RequestBody request: RegisterDeviceRequest
    ): ResponseEntity<ApiResponse<RegisterDeviceResponse>> {
        val response = notificationService.registerDevice(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
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
        val request = PushNotificationRequest(
            userId = userDetails.getId(),
            title = "테스트 알림",
            body = "LiftUp AI 알림 테스트입니다",
            data = mapOf("type" to "test")
        )
        val response = notificationService.sendPushNotification(request)
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
package com.richjun.liftupai.domain.notification.controller

import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/pt-schedule")
class PTScheduleController(
    private val ptScheduledMessageService: PTScheduledMessageService,
    private val userSettingsRepository: UserSettingsRepository
) {

    @PostMapping("/create")
    fun createPTSchedules(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<PTScheduleResponse> {
        val userId = userDetails.getId()
        val locale = resolveLocale(userId)
        ptScheduledMessageService.createPTSchedulesForUser(userId)

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = NotificationLocalization.message("pt.schedule.created", locale),
                schedulesCreated = 5
            )
        )
    }

    @PutMapping("/update-style")
    fun updatePTSchedulesForStyleChange(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam style: String
    ): ResponseEntity<PTScheduleResponse> {
        val userId = userDetails.getId()
        val locale = resolveLocale(userId)
        val ptStyle = try {
            PTStyle.valueOf(style.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(
                PTScheduleResponse(
                    success = false,
                    message = NotificationLocalization.message("pt.schedule.invalid_style", locale, style),
                    schedulesCreated = 0
                )
            )
        }

        ptScheduledMessageService.updatePTSchedulesForStyleChange(userId, ptStyle)

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = NotificationLocalization.message("pt.schedule.updated", locale, ptStyle.name),
                schedulesCreated = 5
            )
        )
    }

    @PostMapping("/test-message")
    fun sendTestMessage(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam messageType: String
    ): ResponseEntity<PTScheduleResponse> {
        val userId = userDetails.getId()
        val locale = resolveLocale(userId)

        // 테스트 메시지 발송 로직
        // 실제 구현 시 PTScheduledMessageService에 테스트 메시지 발송 메소드 추가 필요

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = NotificationLocalization.message("pt.schedule.test_sent", locale, messageType),
                schedulesCreated = 0
            )
        )
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }
}

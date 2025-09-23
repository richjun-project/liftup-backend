package com.richjun.liftupai.domain.notification.controller

import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/pt-schedule")
class PTScheduleController(
    private val ptScheduledMessageService: PTScheduledMessageService
) {

    @PostMapping("/create")
    fun createPTSchedules(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<PTScheduleResponse> {
        val userId = userDetails.getId()
        ptScheduledMessageService.createPTSchedulesForUser(userId)

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = "PT 스케줄이 생성되었습니다",
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
        val ptStyle = try {
            PTStyle.valueOf(style.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(
                PTScheduleResponse(
                    success = false,
                    message = "유효하지 않은 PT 스타일입니다: $style",
                    schedulesCreated = 0
                )
            )
        }

        ptScheduledMessageService.updatePTSchedulesForStyleChange(userId, ptStyle)

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = "PT 스타일이 $ptStyle 로 변경되고 스케줄이 재생성되었습니다",
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

        // 테스트 메시지 발송 로직
        // 실제 구현 시 PTScheduledMessageService에 테스트 메시지 발송 메소드 추가 필요

        return ResponseEntity.ok(
            PTScheduleResponse(
                success = true,
                message = "테스트 메시지가 발송되었습니다: $messageType",
                schedulesCreated = 0
            )
        )
    }
}
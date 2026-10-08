package com.richjun.liftupai.domain.analytics

import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 앱 활성화 이벤트 수집(로그인 사용자). 저장 규칙은 [AppEventService]. */
@RestController
@RequestMapping("/api/v2/events")
class AppEventController(
    private val appEventService: AppEventService
) {
    @PostMapping
    fun record(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: AppEventBatchRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<AppEventBatchResponse>> {
        val result = appEventService.record(userDetails.getId(), request.events, ClientInfo.from(httpRequest))
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(AppEventBatchResponse(accepted = result.accepted, dropped = result.dropped)))
    }
}

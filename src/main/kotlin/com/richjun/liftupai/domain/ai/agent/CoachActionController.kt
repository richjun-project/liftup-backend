package com.richjun.liftupai.domain.ai.agent

import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.security.CustomUserDetails
import com.richjun.liftupai.global.time.AppTime
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * "코치가 한 일" — 트레이너가 바꾼 플랜·설정 목록과 되돌리기(2026-11-14 coach-autopilot-r2). 앱 1.1.44 부터 쓴다.
 */
@RestController
@RequestMapping("/api/coach/actions")
class CoachActionController(
    private val service: CoachActionService,
    private val userSettingsRepository: UserSettingsRepository,
) {
    data class ActionDto(
        val id: Long, val tool: String, val source: String, val summary: String,
        val createdAt: String, val undoable: Boolean, val undone: Boolean,
    )

    data class UndoDto(val ok: Boolean, val code: String, val message: String)

    @GetMapping
    fun list(@AuthenticationPrincipal user: CustomUserDetails): ResponseEntity<ApiResponse<List<ActionDto>>> =
        ResponseEntity.ok(ApiResponse.success(service.list(user.getId()).map { (a, undoable) ->
            ActionDto(a.id, a.tool, a.source, a.summary, AppTime.formatUtcRequired(a.createdAt), undoable, a.undoneAt != null)
        }))

    @PostMapping("/{id}/undo")
    fun undo(
        @AuthenticationPrincipal user: CustomUserDetails,
        @PathVariable id: Long,
        @RequestParam(required = false) locale: String?,
    ): ResponseEntity<ApiResponse<UndoDto>> {
        val lang = locale ?: userSettingsRepository.findByUser_Id(user.getId()).orElse(null)?.language ?: "en"
        val r = service.undo(user.getId(), id, lang) ?: throw ResourceNotFoundException("coach action not found")
        return ResponseEntity.ok(ApiResponse.success(UndoDto(r.ok, r.code, r.message)))
    }
}

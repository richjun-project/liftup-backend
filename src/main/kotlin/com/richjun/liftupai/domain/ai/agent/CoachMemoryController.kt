package com.richjun.liftupai.domain.ai.agent

import com.richjun.liftupai.domain.ai.dto.ToolUseDto
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.security.CustomUserDetails
import com.richjun.liftupai.global.time.AppTime
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

/**
 * "트레이너가 기억하는 것" — 사용자가 AI PT 의 기억을 보고 지울 수 있게 한다(투명성·정정권).
 */
@RestController
@RequestMapping("/api/ai/memories")
class CoachMemoryController(private val memoryRepository: CoachMemoryRepository) {

    data class MemoryDto(val id: Long, val category: String, val content: String, val updatedAt: String)

    @GetMapping
    fun list(@AuthenticationPrincipal user: CustomUserDetails): ResponseEntity<ApiResponse<List<MemoryDto>>> =
        ResponseEntity.ok(ApiResponse.success(memoryRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).map {
            MemoryDto(it.id, it.category, it.content, AppTime.formatUtcRequired(it.updatedAt))
        }))

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@AuthenticationPrincipal user: CustomUserDetails, @PathVariable id: Long): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        val memory = memoryRepository.findByIdAndUserId(id, user.getId()) ?: throw ResourceNotFoundException("memory not found")
        memoryRepository.delete(memory)
        return ResponseEntity.ok(ApiResponse.success(mapOf("deleted" to true)))
    }

    @DeleteMapping
    @Transactional
    fun clear(@AuthenticationPrincipal user: CustomUserDetails): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        memoryRepository.deleteByUserId(user.getId())
        return ResponseEntity.ok(ApiResponse.success(mapOf("deleted" to true)))
    }
}

/** chat_messages.tool_trace("name[:fail][:changed],…") ↔ 응답 DTO */
object CoachToolTrace {
    fun parse(raw: String?): List<ToolUseDto> = raw?.split(',')?.mapNotNull { token ->
        val parts = token.trim().split(':')
        val name = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ToolUseDto(name, ok = "fail" !in parts, changed = "changed" in parts)
    }.orEmpty()
}

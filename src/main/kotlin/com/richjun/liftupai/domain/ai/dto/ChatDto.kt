package com.richjun.liftupai.domain.ai.dto

import jakarta.validation.constraints.NotBlank

// AI 채팅용 DTOs - domain/chat/dto/ChatDto.kt와는 별개
data class ChatRequest(
    @field:NotBlank(message = "Message is required")
    val message: String,

    val context: ChatContext? = null,

    /** 채팅에 첨부한 사진 URL(업로드 API 가 돌려준 것). 앱은 예전부터 보냈지만 서버가 받지 않아 트레이너가 사진을 못 봤다. 최대 3장만 쓴다. */
    val images: List<String>? = null
)

data class ChatResponse(
    val reply: String,
    val timestamp: String,
    val messageId: String,
    val suggestions: List<String>? = null,
    /** 답 아래에 보일 버튼(ChatAction 이름). 모르는 이름은 앱이 무시한다. */
    val actions: List<String> = emptyList(),
    /** AI PT 가 이 답을 만들며 쓴 도구(확인한 기록·바꾼 것). 예전 앱은 이 필드를 모르고 지나간다. */
    val tools: List<ToolUseDto> = emptyList()
)

data class ToolUseDto(val name: String, val ok: Boolean, val changed: Boolean)

/**
 * 앱 버전별 도구 기록 호환. 앱은 [ToolUseDto.name] 을 보고 바뀐 화면(플랜 캐시 등)을 다시 받는데,
 * 1.1.41 이하 앱은 2026-11-13 에 생긴 set_today_workout_day 를 몰라 트레이너가 오늘 Day 를 바꿔도
 * 홈·'오늘 운동 시작'이 옛 Day 를 연다 → 같은 갱신(activePlan·dayWorkout·todayWorkout 무효화)을 일으키는
 * swap_exercise_in_plan(changed) 을 덧붙인다. 1.1.42 부터는 앱이 새 이름을 직접 처리한다.
 */
object ToolUseCompat {
    private const val TODAY_DAY_TOOL = "set_today_workout_day"
    private const val EQUIPMENT_TOOL = "set_available_equipment"
    private const val LEGACY_PLAN_REFRESH_TOOL = "swap_exercise_in_plan"
    private val FIRST_AWARE = listOf(1, 1, 42)

    /**
     * 플랜을 바꾸는 새 도구 → 그 이름을 직접 처리하는 첫 앱 버전(null = 아직 없음 — 모든 앱에 갱신 신호를 덧붙인다).
     * set_available_equipment(2026-11-14)는 플랜 종목을 바꾸므로 앱이 플랜을 다시 받아야 한다.
     */
    private val PLAN_TOOLS_FIRST_AWARE: Map<String, List<Int>?> = mapOf(
        TODAY_DAY_TOOL to FIRST_AWARE,
        EQUIPMENT_TOOL to null,
    )

    fun forClient(tools: List<ToolUseDto>, appVersion: String?): List<ToolUseDto> {
        val needsRefresh = tools.any { t ->
            t.ok && t.changed && PLAN_TOOLS_FIRST_AWARE.containsKey(t.name) &&
                !knows(appVersion, PLAN_TOOLS_FIRST_AWARE[t.name])
        }
        if (!needsRefresh) return tools
        if (tools.any { it.name == LEGACY_PLAN_REFRESH_TOOL && it.ok && it.changed }) return tools
        return tools + ToolUseDto(LEGACY_PLAN_REFRESH_TOOL, ok = true, changed = true)
    }

    /** "1.1.42+59" 같은 값. 헤더가 없거나 못 읽으면 예전 앱으로 본다. */
    fun knowsTodayDayTool(appVersion: String?): Boolean = knows(appVersion, FIRST_AWARE)

    private fun knows(appVersion: String?, firstAware: List<Int>?): Boolean {
        if (firstAware == null) return false
        val parts = appVersion?.substringBefore('+')?.split('.')?.map { it.trim().toIntOrNull() ?: return false } ?: return false
        if (parts.size < 3) return false
        for (i in 0 until 3) {
            if (parts[i] != firstAware[i]) return parts[i] > firstAware[i]
        }
        return true
    }
}

data class ChatContext(
    val workoutType: String? = null,
    val currentExercise: String? = null,
    val sessionId: Long? = null,
    val difficulty: String? = null,
    val muscleGroup: String? = null,
    val userGoal: String? = null
)

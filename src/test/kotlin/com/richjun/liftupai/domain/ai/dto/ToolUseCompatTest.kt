package com.richjun.liftupai.domain.ai.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolUseCompatTest {
    private val dayChanged = ToolUseDto("set_today_workout_day", ok = true, changed = true)

    @Test
    fun `새 도구를 아는 버전은 1_1_42 부터`() {
        assertFalse(ToolUseCompat.knowsTodayDayTool(null))
        assertFalse(ToolUseCompat.knowsTodayDayTool("1.1.41+58"))
        assertFalse(ToolUseCompat.knowsTodayDayTool("1.1.9"))
        assertFalse(ToolUseCompat.knowsTodayDayTool("garbage"))
        assertTrue(ToolUseCompat.knowsTodayDayTool("1.1.42+59"))
        assertTrue(ToolUseCompat.knowsTodayDayTool("1.1.100"))
        assertTrue(ToolUseCompat.knowsTodayDayTool("1.2.0+70"))
        assertTrue(ToolUseCompat.knowsTodayDayTool("2.0.0"))
    }

    @Test
    fun `예전 앱에만, 오늘 Day 를 실제로 바꿨을 때만, 한 번만 덧붙인다`() {
        assertEquals(listOf("set_today_workout_day", "swap_exercise_in_plan"), ToolUseCompat.forClient(listOf(dayChanged), "1.1.41+58").map { it.name })
        assertEquals(listOf(dayChanged), ToolUseCompat.forClient(listOf(dayChanged), "1.1.42+59"))
        val unchanged = listOf(dayChanged.copy(changed = false))
        assertEquals(unchanged, ToolUseCompat.forClient(unchanged, "1.1.41+58"))
        val failed = listOf(dayChanged.copy(ok = false, changed = false))
        assertEquals(failed, ToolUseCompat.forClient(failed, null))
        val withSwap = listOf(dayChanged, ToolUseDto("swap_exercise_in_plan", ok = true, changed = true))
        assertEquals(withSwap, ToolUseCompat.forClient(withSwap, "1.1.41+58"))
    }
}

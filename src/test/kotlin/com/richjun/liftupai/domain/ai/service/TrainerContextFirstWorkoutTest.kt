package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 완료 0회 사용자에게 채팅 트레이너가 하는 말 — 첫 세션 규칙 대상만 '가볍게 시작'(2026-11-12) */
class TrainerContextFirstWorkoutTest {

    @Test
    fun `beginner to intermediate and unknown get the light start`() {
        listOf(ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE, ExperienceLevel.INTERMEDIATE, null).forEach {
            val line = TrainerContextBuilder.firstWorkoutLine(it)
            assertTrue(line.contains("light start"), "$it: $line")
            assertTrue(line.contains("[[START_TODAY_WORKOUT]]"))
        }
    }

    @Test
    fun `advanced and expert keep their full plan and are not told to cut it short`() {
        listOf(ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT).forEach {
            val line = TrainerContextBuilder.firstWorkoutLine(it)
            assertFalse(line.contains("light start"), "$it: $line")
            assertTrue(line.contains("full plan") && line.contains(it.name))
            assertTrue(line.contains("[[START_TODAY_WORKOUT]]"))
        }
    }
}

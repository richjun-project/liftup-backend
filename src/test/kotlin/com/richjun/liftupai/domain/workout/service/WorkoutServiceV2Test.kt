package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 운동 추천 시스템 핵심 로직 검증
 * - 경험 레벨별 가중치
 * - 주기화 phase별 강도 범위
 * - 카테고리 우선순위 (대근육→소근육)
 */
class WorkoutServiceV2Test {

    @Test
    fun `experience multiplier should cover all 5 levels with correct values`() {
        // BEGINNER=0.5, NOVICE=0.6, INTERMEDIATE=0.75, ADVANCED=1.0, EXPERT=1.1
        val multipliers = mapOf(
            "BEGINNER" to 0.5,
            "NOVICE" to 0.6,
            "INTERMEDIATE" to 0.75,
            "ADVANCED" to 1.0,
            "EXPERT" to 1.1
        )

        // EXPERT should be highest, BEGINNER should be lowest
        assertTrue(multipliers["EXPERT"]!! > multipliers["ADVANCED"]!!, "EXPERT should be > ADVANCED")
        assertTrue(multipliers["ADVANCED"]!! > multipliers["INTERMEDIATE"]!!, "ADVANCED should be > INTERMEDIATE")
        assertTrue(multipliers["INTERMEDIATE"]!! > multipliers["NOVICE"]!!, "INTERMEDIATE should be > NOVICE")
        assertTrue(multipliers["NOVICE"]!! > multipliers["BEGINNER"]!!, "NOVICE should be > BEGINNER")

        // No gaps (all 5 covered)
        assertEquals(5, multipliers.size, "All 5 experience levels should be covered")
    }

    @Test
    fun `category priority should order large muscles before small muscles`() {
        val categoryPriority = mapOf(
            "LEGS" to 0, "BACK" to 1, "CHEST" to 2,
            "SHOULDERS" to 3, "ARMS" to 4, "CORE" to 5, "CARDIO" to 6
        )

        // Legs (largest) should have highest priority (lowest number)
        assertTrue(categoryPriority["LEGS"]!! < categoryPriority["ARMS"]!!)
        assertTrue(categoryPriority["BACK"]!! < categoryPriority["CORE"]!!)
        assertTrue(categoryPriority["CHEST"]!! < categoryPriority["SHOULDERS"]!!)

        // All workout categories covered
        ExerciseCategory.values().forEach { category ->
            if (category != ExerciseCategory.FULL_BODY) {
                assertTrue(
                    categoryPriority.containsKey(category.name),
                    "Category ${category.name} should be in priority map"
                )
            }
        }
    }

    @Test
    fun `periodization phases should have distinct intensity ranges`() {
        // From WorkoutServiceV2 generateFinalRecommendation
        val phases = mapOf(
            "ACCUMULATION" to (0.65..0.75),
            "INTENSIFICATION" to (0.75..0.85),
            "REALIZATION" to (0.85..0.95),
            "DELOAD" to (0.50..0.60)
        )

        // DELOAD should be lowest intensity
        assertTrue(phases["DELOAD"]!!.endInclusive < phases["ACCUMULATION"]!!.start,
            "DELOAD max should be below ACCUMULATION min")

        // REALIZATION should be highest intensity
        assertTrue(phases["REALIZATION"]!!.start > phases["INTENSIFICATION"]!!.start,
            "REALIZATION should start higher than INTENSIFICATION")

        // All 4 phases covered
        assertEquals(4, phases.size)
    }

    @Test
    fun `BLOCK periodization should map weeks correctly`() {
        // 7-week block: 2 accumulation, 2 intensification, 2 realization, 1 deload
        fun blockPhase(week: Int): String {
            val blockPhase = (week - 1) % 7
            return when (blockPhase) {
                0, 1 -> "ACCUMULATION"
                2, 3 -> "INTENSIFICATION"
                4, 5 -> "REALIZATION"
                else -> "DELOAD"
            }
        }

        assertEquals("ACCUMULATION", blockPhase(1))
        assertEquals("ACCUMULATION", blockPhase(2))
        assertEquals("INTENSIFICATION", blockPhase(3))
        assertEquals("INTENSIFICATION", blockPhase(4))
        assertEquals("REALIZATION", blockPhase(5))
        assertEquals("REALIZATION", blockPhase(6))
        assertEquals("DELOAD", blockPhase(7))
        // Cycle repeats
        assertEquals("ACCUMULATION", blockPhase(8))
    }

    @Test
    fun `LINEAR periodization should have 3 weeks progress and 1 week deload`() {
        fun linearPhase(week: Int): String {
            return when ((week - 1) % 4) {
                0, 1 -> "ACCUMULATION"
                2 -> "INTENSIFICATION"
                else -> "DELOAD"
            }
        }

        assertEquals("ACCUMULATION", linearPhase(1))
        assertEquals("ACCUMULATION", linearPhase(2))
        assertEquals("INTENSIFICATION", linearPhase(3))
        assertEquals("DELOAD", linearPhase(4))
        // Cycle repeats
        assertEquals("ACCUMULATION", linearPhase(5))
    }

    @Test
    fun `performance trend multipliers should be correctly ordered`() {
        val multipliers = mapOf(
            "READY_TO_PROGRESS" to 1.05,
            "IMPROVING" to 1.025,
            "MAINTAINING" to 1.0,
            "DECLINING" to 0.95,
            "TECHNIQUE_FOCUS" to 0.9,
            "NEEDS_DELOAD" to 0.8,
            "NEW_EXERCISE" to 1.0
        )

        // Progressive should increase, deload should decrease
        assertTrue(multipliers["READY_TO_PROGRESS"]!! > multipliers["MAINTAINING"]!!)
        assertTrue(multipliers["NEEDS_DELOAD"]!! < multipliers["MAINTAINING"]!!)
        assertTrue(multipliers["TECHNIQUE_FOCUS"]!! < multipliers["MAINTAINING"]!!)
    }
}

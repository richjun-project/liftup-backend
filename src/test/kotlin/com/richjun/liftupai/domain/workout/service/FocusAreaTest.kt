package com.richjun.liftupai.domain.workout.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusAreaTest {

    @Test
    fun `세부 목표가 집중 부위로 바뀐다`() {
        assertEquals(listOf(FocusArea.GLUTES, FocusArea.LEGS), FocusArea.resolve(listOf("weight_loss", "glutes", "thick_legs")))
        assertEquals(listOf(FocusArea.LEGS, FocusArea.GLUTES), FocusArea.resolve(listOf("slim_legs")))
        assertEquals(listOf(FocusArea.CORE), FocusArea.resolve(listOf("six_pack", "core_strength")))
    }

    @Test
    fun `집중 부위는 최대 3개이고 먼저 고른 목표가 우선한다`() {
        val areas = FocusArea.resolve(listOf("wide_shoulders", "thick_chest", "big_arms", "wide_back", "glutes"))
        assertEquals(listOf(FocusArea.SHOULDERS, FocusArea.CHEST, FocusArea.ARMS), areas)
    }

    @Test
    fun `앱이 보낸 focus_areas 도 읽고 중복은 한 번만`() {
        assertEquals(listOf(FocusArea.LEGS, FocusArea.CORE), FocusArea.resolve(listOf("thick_legs"), listOf("legs", "core")))
        assertTrue(FocusArea.resolve(listOf("bulk_up", "weight_loss")).isEmpty())
    }

    @Test
    fun `집중 부위 목표 판별`() {
        assertTrue(FocusArea.isFocusGoal("Thick-Legs"))
        assertFalse(FocusArea.isFocusGoal("bulk_up"))
    }

    @Test
    fun `밀기 날에 이두를, 당기기 날에 삼두를 넣지 않는다`() {
        assertTrue(FocusArea.ARMS.fitsDay("PUSH"))
        assertFalse(FocusArea.LEGS.fitsDay("PUSH"))
        assertTrue(FocusArea.LEGS.fitsDay("FULL_BODY"))
        assertTrue(FocusArea.CORE.fitsDay("PULL"))
    }
}

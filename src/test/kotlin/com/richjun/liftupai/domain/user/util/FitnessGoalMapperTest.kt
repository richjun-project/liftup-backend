package com.richjun.liftupai.domain.user.util

import com.richjun.liftupai.domain.user.entity.FitnessGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FitnessGoalMapperTest {
    /** 앱 onboarding_chat_models.dart 의 목표 옵션 20종 전부 — 하나라도 null 이면 사용자 목표가 사라진다 */
    private val appGoals = listOf(
        "athletic_performance", "big_arms", "bulk_up", "core_strength", "endurance", "flexibility",
        "general_fitness", "glutes", "lean_mass", "posture", "rehabilitation", "six_pack", "slim_legs",
        "strength", "thick_chest", "thick_legs", "toned_arms", "weight_loss", "wide_back", "wide_shoulders",
    )

    @Test fun `앱 목표 20종 모두 서버 목표로 변환된다`() {
        val unmapped = appGoals.filter { FitnessGoalMapper.parse(it) == null }
        assertEquals(emptyList<String>(), unmapped)
    }

    @Test fun `근육 키우는 목표는 MUSCLE_GAIN`() {
        listOf("bulk_up", "big_arms", "thick_chest", "wide_back", "wide_shoulders", "thick_legs", "lean_mass", "glutes")
            .forEach { assertEquals(FitnessGoal.MUSCLE_GAIN, FitnessGoalMapper.parse(it), it) }
    }

    @Test fun `실사용 109·129 처럼 세부 목표만 고른 경우도 목표가 남는다`() {
        assertEquals(setOf(FitnessGoal.MUSCLE_GAIN, FitnessGoal.WEIGHT_LOSS), FitnessGoalMapper.parseAll(listOf("big_arms", "six_pack", "thick_chest")))
    }

    @Test fun `양성 대조 - 정말 모르는 값은 버린다`() {
        assertNull(FitnessGoalMapper.parse("become_a_wizard"))
        assertNull(FitnessGoalMapper.parse(""))
    }
}

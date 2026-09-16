package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 부상으로 비어버린 템플릿 날을 안전한 운동으로 다시 채우는 규칙.
 * 2026-09-15 실사용: 무릎·고관절·허리 부상 사용자의 플랜이 하루 2개까지 줄었다.
 */
class TemplateRefillTest {
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val injuryFilterService = mockk<InjuryFilterService>()

    private fun ex(id: Long, name: String, cat: ExerciseCategory, eq: com.richjun.liftupai.domain.workout.entity.Equipment? = null) =
        Exercise(id = id, slug = name.lowercase().replace(" ", "-"), name = name, category = cat, isPlanEligible = true, equipment = eq)

    private val legs = listOf(ex(1, "Squat", ExerciseCategory.LEGS), ex(2, "Leg curl", ExerciseCategory.LEGS))
    private val core = listOf(ex(10, "Plank", ExerciseCategory.CORE), ex(11, "Dead bug", ExerciseCategory.CORE))
    private val chest = listOf(
        ex(20, "Bench", ExerciseCategory.CHEST, com.richjun.liftupai.domain.workout.entity.Equipment.BARBELL),
        ex(21, "Push-up", ExerciseCategory.CHEST, com.richjun.liftupai.domain.workout.entity.Equipment.BODYWEIGHT),
    )
    private val back = listOf(ex(30, "Row", ExerciseCategory.BACK))

    private fun service(): UserPlanService {
        // mockk 은 나중 스텁이 이기므로 any() 를 먼저 둔다
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(any()) } returns emptyList()
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.LEGS) } returns legs
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.CORE) } returns core
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.CHEST) } returns chest
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.BACK) } returns back
        // 무릎: 다리 운동 전부 제한
        every { injuryFilterService.isExerciseRestricted(any(), setOf("knee")) } answers { firstArg<Long>() in setOf(1L, 2L) }
        return UserPlanService(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            injuryFilterService, mockk(), exerciseRepository, mockk(), mockk(), mockk(), mockk(), mockk(),
        )
    }

    @Test
    fun `무릎 부상이면 하체 날을 코어·상체로 채우고 제한 운동은 0개`() {
        val picked = service().pickRefillExercises("LEGS", setOf("knee"), exclude = emptySet(), needed = 4)
        assertEquals(4, picked.size)
        assertTrue(picked.none { it.id in setOf(1L, 2L) }, "무릎 제한 운동이 섞이면 안 된다: ${picked.map { it.name }}")
        // 라운드로빈이라 순서는 고정하지 않는다 — 코어·상체에서 골고루, 4개
        assertEquals(setOf("Plank", "Dead bug", "Bench", "Row"), picked.map { it.name }.toSet())
    }

    @Test
    fun `이미 남아 있는 운동은 다시 넣지 않는다`() {
        val picked = service().pickRefillExercises("LEGS", setOf("knee"), exclude = setOf(10L), needed = 2)
        assertEquals(listOf("Dead bug", "Bench"), picked.map { it.name })
    }

    @Test
    fun `덤벨만 있으면 바벨 운동은 건너뛰고 맨몸은 허용한다`() {
        val picked = service().pickRefillExercises("PUSH", emptySet(), emptySet(), 2, allowedEquipment = setOf("DUMBBELL"))
        assertEquals(listOf("Push-up"), picked.map { it.name }.filter { it != "Row" }.take(1))
        assertTrue(picked.none { it.name == "Bench" }, "바벨 벤치가 섞였다: ${picked.map { it.name }}")
    }

    @Test
    fun `부족한 개수가 0이면 아무것도 고르지 않는다`() {
        assertTrue(service().pickRefillExercises("LEGS", setOf("knee"), emptySet(), 0).isEmpty())
    }

    @Test
    fun `양성 대조 - 부상이 없으면 그날 부위 운동을 그대로 고른다`() {
        val picked = service().pickRefillExercises("LEGS", emptySet(), emptySet(), 2)
        // 하체 날: 다리 → 코어 라운드로빈이라 [Squat, Plank]. 제한이 없으니 다리 운동이 그대로 들어간다.
        assertEquals("Squat", picked.first().name)
        assertTrue(picked.any { it.category == ExerciseCategory.LEGS })
    }
}

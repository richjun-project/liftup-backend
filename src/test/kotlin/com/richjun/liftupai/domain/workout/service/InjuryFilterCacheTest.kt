package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 플랜 가드가 후보 수백 개를 순회해도 큐레이션 테이블은 부상 세트당 한 번만 조회한다. */
class InjuryFilterCacheTest {

    private val restrictionRepo = mockk<InjuryExerciseRestrictionRepository>()
    private val exerciseRepo = mockk<com.richjun.liftupai.domain.workout.repository.ExerciseRepository>()
    private val service = InjuryFilterService(restrictionRepo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), exerciseRepo)

    private fun ex(id: Long, pattern: String, vararg m: MuscleGroup) =
        Exercise(id = id, slug = "e$id", name = "e$id", category = ExerciseCategory.LEGS, movementPattern = pattern, muscleGroups = m.toMutableSet())

    @Test
    @DisplayName("같은 부상 세트로 300번 검사해도 테이블 조회는 1회")
    fun repositoryHitOnce() {
        every { restrictionRepo.findByInjuryTypeAndSeverityIn(any(), any()) } returns emptyList()
        val injuries = setOf("knee:moderate")
        val candidates = (1..300L).map { ex(it, if (it % 2 == 0L) "SQUAT" else "HORIZONTAL_PRESS_BARBELL", if (it % 2 == 0L) MuscleGroup.QUADRICEPS else MuscleGroup.CHEST) }
        every { exerciseRepo.findById(any()) } answers { java.util.Optional.ofNullable(candidates.firstOrNull { it.id == firstArg<Long>() }) }

        val restricted = candidates.count { service.isExerciseRestricted(it.id, injuries) || service.isRestrictedByRule(it, injuries) }

        verify(exactly = 1) { restrictionRepo.findByInjuryTypeAndSeverityIn("knee", any()) }
        assertTrue(restricted == 150, "규칙으로 스쿼트 150개가 걸려야 한다: $restricted")
    }

    @Test
    @DisplayName("restrictionChecker 는 테이블 id + 속성 규칙을 함께 본다")
    fun checkerCombinesTableAndRule() {
        every { restrictionRepo.findByInjuryTypeAndSeverityIn(any(), any()) } returns emptyList()
        val check = service.restrictionChecker(setOf("knee"))
        assertTrue(check(ex(1, "SQUAT", MuscleGroup.QUADRICEPS)))
        assertFalse(check(ex(2, "HORIZONTAL_PRESS_BARBELL", MuscleGroup.CHEST)))
        assertFalse(service.restrictionChecker(emptySet())(ex(1, "SQUAT", MuscleGroup.QUADRICEPS)), "부상이 없으면 아무것도 제한하지 않는다")
    }
}

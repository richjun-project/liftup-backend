package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * 부상 부위별 종목 제한 규칙.
 *
 * 배경: injury_exercise_restrictions 테이블이 비어 있어 부상 필터가 연결된 6개 경로 전부가
 * 실제로는 아무 것도 거르지 않았다. 무릎 수술을 신고한 사용자의 플랜에
 * 스쿼트·런지·레그프레스가 그대로 들어갔다(2026-08-26 실사용 로그).
 */
class InjuryRuleTest {

    private val service = InjuryFilterService(
        mock(com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.ProgramDayRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.ExerciseRepository::class.java),
    )

    private fun ex(pattern: String?, vararg muscles: MuscleGroup) = Exercise(
        id = 1L,
        slug = "t",
        name = "t",
        category = com.richjun.liftupai.domain.workout.entity.ExerciseCategory.LEGS,
        movementPattern = pattern,
        muscleGroups = muscles.toMutableSet(),
    )

    @Test
    @DisplayName("무릎 부상이면 스쿼트·런지·레그프레스가 제한된다 — 실제 사고 종목")
    fun kneeBlocksLegWork() {
        val injuries = setOf("knee")
        assertThat(service.isRestrictedByRule(ex("SQUAT", MuscleGroup.QUADRICEPS), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("LUNGE", MuscleGroup.QUADRICEPS), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("LEG_PRESS", MuscleGroup.QUADRICEPS), injuries)).isTrue()
    }

    @Test
    @DisplayName("양성 대조 — 무릎 부상이어도 상체 종목은 통과해야 한다(전부 막으면 플랜이 빈다)")
    fun kneeAllowsUpperBody() {
        val injuries = setOf("knee")
        assertThat(service.isRestrictedByRule(ex("HORIZONTAL_PRESS_BARBELL", MuscleGroup.CHEST), injuries)).isFalse()
        assertThat(service.isRestrictedByRule(ex("LAT_PULLDOWN", MuscleGroup.LATS), injuries)).isFalse()
        assertThat(service.isRestrictedByRule(ex("BICEP_CURL_DUMBBELL", MuscleGroup.BICEPS), injuries)).isFalse()
    }

    @Test
    @DisplayName("허리 부상이면 데드리프트·바벨로우가 제한되고, 랫풀다운은 통과한다")
    fun lowerBackRules() {
        val injuries = setOf("lower_back")
        assertThat(service.isRestrictedByRule(ex("DEADLIFT", MuscleGroup.BACK), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("BARBELL_ROW", MuscleGroup.BACK), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("LAT_PULLDOWN", MuscleGroup.LATS), injuries)).isFalse()
    }

    @Test
    @DisplayName("어깨 부상이면 오버헤드 프레스가 제한되고, 하체는 통과한다")
    fun shoulderRules() {
        val injuries = setOf("shoulder")
        assertThat(service.isRestrictedByRule(ex("VERTICAL_PRESS_BARBELL", MuscleGroup.SHOULDERS), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("SQUAT", MuscleGroup.QUADRICEPS), injuries)).isFalse()
    }

    @Test
    @DisplayName("심각도 접미사(knee:severe)가 붙어도 동작한다")
    fun handlesSeveritySuffix() {
        assertThat(service.isRestrictedByRule(ex("SQUAT", MuscleGroup.QUADRICEPS), setOf("knee:severe"))).isTrue()
    }

    @Test
    @DisplayName("허리 부상이면 런지와 스탠딩 바벨 오버헤드 프레스도 제한된다")
    fun lowerBackAlsoBlocksLungeAndStandingPress() {
        val injuries = setOf("lower_back")
        assertThat(service.isRestrictedByRule(ex("LUNGE", MuscleGroup.QUADRICEPS), injuries)).isTrue()
        assertThat(service.isRestrictedByRule(ex("VERTICAL_PRESS_BARBELL", MuscleGroup.SHOULDERS), injuries)).isTrue()
        // 대조 — 앉아서 하는 머신 종목은 통과해야 한다
        assertThat(service.isRestrictedByRule(ex("LEG_PRESS", MuscleGroup.QUADRICEPS), injuries)).isFalse()
    }

    @Test
    @DisplayName("양성 대조 — 부상이 없으면 어떤 종목도 제한되지 않는다")
    fun noInjuryNoRestriction() {
        assertThat(service.isRestrictedByRule(ex("SQUAT", MuscleGroup.QUADRICEPS), emptySet())).isFalse()
    }
}

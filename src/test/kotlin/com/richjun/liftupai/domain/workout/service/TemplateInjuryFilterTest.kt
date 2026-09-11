package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional

/**
 * 부상 반영 회귀 테스트.
 *
 * 배경: 무릎 수술을 신고한 사용자(user_settings_injuries='knee')의 템플릿 플랜에
 * 하체 운동 6개가 그대로 들어갔다. 부상 필터가 템플릿 경로에만 빠져 있었다(2026-08-26 실사용 로그).
 *
 * 여기서 검증하는 것은 "부상 목록 해석"이다 — 이 값이 비면 필터가 통째로 무력화되므로
 * 이 단계가 실패 원인이 될 수 있는 지점이다.
 */
class TemplateInjuryFilterTest {

    private val settingsRepo = mock(UserSettingsRepository::class.java)

    private fun service(): UserPlanService = UserPlanService(
        mock(com.richjun.liftupai.domain.workout.repository.UserWorkoutPlanRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.UserPlanDayRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.UserPlanDayExerciseRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.WorkoutPlanTemplateRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.TemplateDayRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.TemplateDayExerciseRepository::class.java),
        mock(InjuryFilterService::class.java),
        settingsRepo,
        mock(com.richjun.liftupai.domain.workout.repository.ExerciseRepository::class.java),
        mock(WeightRecommendationService::class.java),
        mock(ProgramProgressiveOverloadService::class.java),
        mock(PlanWorkoutEnricher::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
        mock(com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository::class.java),
    )

    private fun userWithSettingsInjuries(vararg injuries: String): User {
        val user = User(id = 1L, email = "t@t.t", password = null, nickname = "QA")
        val settings = UserSettings(user = user)
        settings.injuries = injuries.toMutableSet()
        `when`(settingsRepo.findByUser_Id(1L)).thenReturn(Optional.of(settings))
        return user
    }

    @Test
    @DisplayName("요청에 부상이 없으면 온보딩에서 저장된 설정값을 쓴다 — 이게 비면 필터가 무력화된다")
    fun fallsBackToSettings() {
        val user = userWithSettingsInjuries("knee")
        val resolved = service().resolveInjuries(user, ApplyTemplateRequest(injuries = null))
        assertThat(resolved).containsExactly("knee")
    }

    @Test
    @DisplayName("심각도가 붙은 형식(knee:severe)에서도 부위만 뽑는다")
    fun stripsSeveritySuffix() {
        val user = userWithSettingsInjuries("knee:severe", "shoulder:mild")
        val resolved = service().resolveInjuries(user, null)
        assertThat(resolved).containsExactlyInAnyOrder("knee", "shoulder")
    }

    @Test
    @DisplayName("요청에 부상이 담겨 오면 그것을 우선한다")
    fun requestTakesPrecedence() {
        val user = userWithSettingsInjuries("knee")
        val resolved = service().resolveInjuries(user, ApplyTemplateRequest(injuries = listOf("shoulder")))
        assertThat(resolved).containsExactly("shoulder")
    }

    @Test
    @DisplayName("양성 대조 — 부상이 없는 사용자는 빈 집합이라 아무 것도 걸러지지 않는다")
    fun positiveControlNoInjury() {
        val user = userWithSettingsInjuries()
        assertThat(service().resolveInjuries(user, null)).isEmpty()
        assertThat(service().resolveInjuries(user, ApplyTemplateRequest(injuries = listOf("none")))).isEmpty()
        assertThat(service().resolveInjuries(user, ApplyTemplateRequest(injuries = listOf("  ")))).isEmpty()
    }
}

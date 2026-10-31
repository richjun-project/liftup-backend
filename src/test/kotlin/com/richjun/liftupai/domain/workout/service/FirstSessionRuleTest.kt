package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.service.new.SessionFit
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 첫 세션은 짧게(2026-10-31, FirstSessionRule). 운영: 6~7종목·약 60분 첫 운동을 받은 실사용자 3명 중 2명이 미리보기만 보고 시작하지 않았다.
 * 실제 UserPlanService·PlanWorkoutEnricher 로 6종목·세션 60분 플랜을 만든다.
 */
class FirstSessionRuleTest {

    private val user = RecoTestKit.user()
    private val repos = RecoTestKit.Repos(
        RecoTestKit.profile(user, ExperienceLevel.INTERMEDIATE, 30),
        RecoTestKit.settings(user, durationMinutes = 60),
    )
    private val slugs = listOf(
        "barbell-full-squat", "barbell-bench-press", "barbell-bent-over-row-pronated-grip",
        "cable-pulldown", "lateral-raises-dumbbell", "dumbbell-curls",
    )
    private val fixture = RecoTestKit.PlanFixture(repos, slugs.map { Triple(RecoTestKit.bySlug.getValue(it), 4, 8..12) })

    private fun firstTimer() {
        every { fixture.sessionRepo.countByUserAndStatus(any(), SessionStatus.COMPLETED) } returns 0L
    }

    @Test
    @DisplayName("완료 운동 0회: 앞 4종목(순서 그대로)·30분 안")
    fun firstSessionIsShort() {
        firstTimer()
        val day = fixture.service.getDayWorkout(user.id, 1)

        assertEquals(slugs.take(FirstSessionRule.MAX_EXERCISES).map { RecoTestKit.bySlug.getValue(it).id }, day.exercises.map { it.exerciseId })
        // 시간 맞춤의 허용 범위(목표의 +5%, SessionFit.UPPER)까지 — 30분이면 31.5분 → 반올림 32
        val ceiling = kotlin.math.ceil(FirstSessionRule.MAX_MINUTES * SessionFit.UPPER).toInt()
        assertTrue(day.estimatedDuration <= ceiling, "첫 세션이 30분 범위를 넘는다: ${day.estimatedDuration}")
    }

    @Test
    @DisplayName("운동을 끝내 본 사용자: 저장된 6종목·세션 시간 그대로")
    fun veteranGetsFullPlan() {
        val day = fixture.service.getDayWorkout(user.id, 1)

        assertEquals(6, day.exercises.size)
        assertTrue(day.estimatedDuration > FirstSessionRule.MAX_MINUTES, "경험자 플랜이 줄었다: ${day.estimatedDuration}")
    }

    @Test
    @DisplayName("대시보드의 종목 수·분이 첫 세션 하루 상세와 같다")
    fun dashboardMatchesDayDetail() {
        firstTimer()
        val day = fixture.service.getDayWorkout(user.id, 1)
        val overview = fixture.service.getPlanDashboard(user.id, "ko").days.single()

        assertEquals(day.exercises.size, overview.exerciseCount)
        assertEquals(day.estimatedDuration, overview.estimatedDuration)
    }

    @Test
    @DisplayName("첫 세션 시간: 더 짧게 골랐으면 그 값, 없거나 길면 30분")
    fun minutesCap() {
        assertEquals(30, FirstSessionRule.minutes(null))
        assertEquals(20, FirstSessionRule.minutes(20))
        assertEquals(30, FirstSessionRule.minutes(60))
        assertEquals(30, FirstSessionRule.minutes(0))
    }
}

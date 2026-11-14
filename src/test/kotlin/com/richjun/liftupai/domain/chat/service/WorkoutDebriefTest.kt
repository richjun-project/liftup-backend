package com.richjun.liftupai.domain.chat.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.subscription.service.SubscriptionService
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.response.DayExerciseDetail
import com.richjun.liftupai.domain.workout.dto.response.DayWorkoutResponse
import com.richjun.liftupai.domain.workout.entity.UserWorkoutPlan
import com.richjun.liftupai.domain.workout.service.UserPlanService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.Optional

/**
 * 운동 후 정리(플레이북 2, 2026-11-14): 트레이너의 반응 + 다음 운동(Day·첫 종목), 무료는 플랜 버튼·PRO 는 통계 버튼,
 * 사람이 할 수 없는 속도로 끝난 세션에는 아무 말도 하지 않는다.
 */
class WorkoutDebriefTest {
    private val chatRepo = mockk<ChatMessageRepository>()
    private val profileRepo = mockk<UserProfileRepository>()
    private val settingsRepo = mockk<UserSettingsRepository>()
    private val planService = mockk<UserPlanService>()
    private val subscription = mockk<SubscriptionService>()
    private val planProvider = mockk<ObjectProvider<UserPlanService>>().also { every { it.ifAvailable } returns planService }
    private val subProvider = mockk<ObjectProvider<SubscriptionService>>().also { every { it.ifAvailable } returns subscription }
    private val service = TrainerMessageService(chatRepo, profileRepo, settingsRepo, planProvider, subProvider)
    private val user = User(id = 9L, email = "t@example.com", nickname = "민수")
    private val saved = slot<ChatMessage>()

    private fun given(lang: String, pro: Boolean = false, dayName: String = "하체 A", first: String = "스쿼트") {
        every { settingsRepo.findByUser_Id(9L) } returns Optional.of(UserSettings(user = user, language = lang))
        every { profileRepo.findByUser(user) } returns Optional.empty()
        every { chatRepo.save(capture(saved)) } answers { firstArg() }
        every { subscription.hasActiveSubscription(9L) } returns pro
        val plan = mockk<UserWorkoutPlan> { every { currentDay } returns 1; every { totalDays } returns 4 }
        every { planService.getActivePlan(9L) } returns plan
        every { planService.getDayWorkout(9L, 2, any(), lang) } returns DayWorkoutResponse(
            dayNumber = 2, dayName = dayName, workoutType = "LOWER", estimatedDuration = 40, completionCount = 0,
            exercises = listOf(DayExerciseDetail(
                exerciseId = 1, exerciseName = first, imageUrl = null, sets = 3, minReps = 8, maxReps = 10,
                restSeconds = 90, isCompound = true, targetRPE = 7.0, suggestedWeight = 60.0, notes = null,
            )),
        )
    }

    @Test
    @DisplayName("ko: 실제 세트·시간 반응 뒤에 다음 운동(Day 2 하체 A, 스쿼트부터), 무료 사용자는 플랜 버튼")
    fun koreanDebriefPreviewsNextSession() {
        given("ko")
        service.onWorkoutCompleted(user, totalSets = 12, totalVolume = 3_600.0, durationMinutes = 41)
        val text = saved.captured.aiResponse!!
        assertTrue(text.contains("12") && text.contains("41"), text)
        assertTrue(text.contains("Day 2") && text.contains("하체 A") && text.contains("스쿼트부터"), text)
        assertEquals(ChatAction.join(listOf(ChatAction.OPEN_PLAN)), saved.captured.actions)
    }

    @Test
    @DisplayName("ja·en 도 다음 운동을 말하고, PRO 사용자는 통계 버튼")
    fun otherLanguagesAndPro() {
        given("ja", pro = true, dayName = "下半身A", first = "スクワット")
        service.onWorkoutCompleted(user, 9, 2_000.0, 30)
        assertTrue(saved.captured.aiResponse!!.contains("次は Day 2 下半身A、スクワットから"), saved.captured.aiResponse!!)
        assertEquals(ChatAction.join(listOf(ChatAction.OPEN_STATS)), saved.captured.actions)

        given("en", dayName = "Lower A", first = "Squat")
        service.onWorkoutCompleted(user, 9, 2_000.0, 30)
        assertTrue(saved.captured.aiResponse!!.contains("Next up: Day 2 Lower A, starting with Squat."), saved.captured.aiResponse!!)
    }

    @Test
    @DisplayName("사람이 할 수 없는 속도로 끝난 세션·세트 0 은 아무 메시지도 남기지 않는다, 플랜을 못 읽으면 반응만")
    fun silentForImplausibleAndRobustWithoutPlan() {
        given("ko")
        service.onWorkoutCompleted(user, 15, 0.0, 0, implausible = true)
        service.onWorkoutCompleted(user, 0, 0.0, 10)
        verify(exactly = 0) { chatRepo.save(any()) }

        every { planService.getActivePlan(9L) } throws IllegalStateException("db down")
        service.onWorkoutCompleted(user, 6, 900.0, 20)
        assertTrue(saved.captured.aiResponse!!.isNotBlank() && !saved.captured.aiResponse!!.contains("Day"), saved.captured.aiResponse!!)
    }
}

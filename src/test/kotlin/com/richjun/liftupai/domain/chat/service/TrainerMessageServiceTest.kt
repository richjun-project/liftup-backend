package com.richjun.liftupai.domain.chat.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.Optional

class TrainerMessageServiceTest {

    private val chatRepo = mockk<ChatMessageRepository>()
    private val profileRepo = mockk<UserProfileRepository>()
    private val settingsRepo = mockk<UserSettingsRepository>()
    private val planService = mockk<UserPlanService>()
    private val provider = mockk<ObjectProvider<UserPlanService>>().also { every { it.ifAvailable } returns planService }
    private val service = TrainerMessageService(chatRepo, profileRepo, settingsRepo, provider)
    private val user = User(id = 9L, email = "t@example.com", nickname = "민수")
    private val saved = slot<ChatMessage>()

    init {
        every { settingsRepo.findByUser_Id(9L) } returns Optional.of(UserSettings(user = user, language = "ko"))
        every { profileRepo.findByUser(user) } returns Optional.empty()
        every { chatRepo.save(capture(saved)) } answers { firstArg() }
    }

    private fun stubPlan() {
        val plan = mockk<UserWorkoutPlan>()
        every { plan.planName } returns "상하체 분할"
        every { plan.currentDay } returns 1
        every { planService.getActivePlan(9L) } returns plan
        every { planService.getDayWorkout(9L, 1, any(), "ko") } returns DayWorkoutResponse(
            dayNumber = 1, dayName = "상체 A", workoutType = "UPPER", estimatedDuration = 48, completionCount = 0,
            exercises = (1..5).map {
                DayExerciseDetail(
                    exerciseId = it.toLong(), exerciseName = "운동$it", imageUrl = null, sets = 3, minReps = 8,
                    maxReps = 10, restSeconds = 90, isCompound = true, targetRPE = 7.0, suggestedWeight = 40.0, notes = null,
                )
            },
        )
    }

    @Test
    fun `first chat open greets with today's workout and start + reminder buttons`() {
        every { chatRepo.countByUser(user) } returns 0
        stubPlan()

        assertThat(service.ensureWelcome(user)).isTrue()
        assertThat(saved.captured.messageType).isEqualTo(MessageType.SYSTEM)
        assertThat(saved.captured.aiResponse).contains("민수").contains("상하체 분할").contains("상체 A").contains("48")
        assertThat(ChatAction.parseList(saved.captured.actions))
            .containsExactly(ChatAction.START_TODAY_WORKOUT, ChatAction.SET_REMINDER)
    }

    @Test
    fun `no welcome once there is any conversation`() {
        every { chatRepo.countByUser(user) } returns 3
        assertThat(service.ensureWelcome(user)).isFalse()
        verify(exactly = 0) { chatRepo.save(any()) }
    }

    @Test
    fun `no welcome without a plan`() {
        every { chatRepo.countByUser(user) } returns 0
        every { planService.getActivePlan(9L) } returns null
        assertThat(service.ensureWelcome(user)).isFalse()
    }

    @Test
    fun `workout feedback reacts to the result, but not to an empty session`() {
        service.onWorkoutCompleted(user, totalSets = 0, totalVolume = 0.0, durationMinutes = 5)
        verify(exactly = 0) { chatRepo.save(any()) }

        service.onWorkoutCompleted(user, totalSets = 12, totalVolume = 3450.0, durationMinutes = 41)
        assertThat(saved.captured.aiResponse).contains("민수").contains("12").contains("3,450").contains("41")
        assertThat(ChatAction.parseList(saved.captured.actions)).containsExactly(ChatAction.OPEN_STATS)
    }
}

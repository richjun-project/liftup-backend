package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.DayOfWeek
import com.richjun.liftupai.domain.notification.entity.NotificationSchedule
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Optional

class PTFirstWorkoutReminderTest {

    private val scheduleRepository = mockk<NotificationScheduleRepository>(relaxed = true)
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val workoutSessionRepository = mockk<WorkoutSessionRepository>()
    private val chatMessageRepository = mockk<ChatMessageRepository>()
    private val userProfileRepository = mockk<com.richjun.liftupai.domain.user.repository.UserProfileRepository>()

    private val service = PTScheduledMessageService(
        userRepository = mockk(relaxed = true),
        userProfileRepository = userProfileRepository,
        userSettingsRepository = userSettingsRepository,
        notificationScheduleRepository = scheduleRepository,
        notificationHistoryRepository = mockk(relaxed = true),
        workoutSessionRepository = workoutSessionRepository,
        chatMessageRepository = chatMessageRepository,
        notificationService = mockk(relaxed = true),
        ptMessageTemplates = PTMessageTemplates()
    )

    private fun sendWorkoutReminder(completedWorkouts: Long): String {
        val user = User(id = 5L, email = "new@example.com", nickname = "new").apply { lastLoginAt = AppTime.utcNow() }
        val schedule = NotificationSchedule(
            user = user,
            scheduleName = "workout_reminder",
            time = LocalTime.of(17, 30),
            message = "old snapshot",
            days = mutableSetOf(DayOfWeek.MON),
            notificationType = NotificationType.AI_MESSAGE,
            nextTriggerAt = AppTime.utcNow().minusMinutes(1)
        )
        val saved = slot<ChatMessage>()
        every { scheduleRepository.findByIdForUpdate(1L) } returns schedule
        every { userSettingsRepository.findByUser_Id(5L) } returns Optional.of(UserSettings(user = user, language = "ko"))
        every { workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED) } returns completedWorkouts
        every { chatMessageRepository.save(capture(saved)) } answers { firstArg() }
        every { userProfileRepository.findByUser(user) } returns Optional.empty()

        service.sendScheduledPTMessage(1L)
        return saved.captured.aiResponse!!
    }

    @Test
    fun `users who never finished a workout get the one-set first workout copy`() {
        val firstWorkout = NotificationLocalization.messages("pt.template.first_workout.default", "ko")
        assertThat(firstWorkout).hasSize(2)
        assertThat(sendWorkoutReminder(completedWorkouts = 0)).isIn(firstWorkout)
    }

    @Test
    fun `users with a finished workout keep the regular reminder`() {
        val regular = NotificationLocalization.messages("pt.template.workout_reminder.default", "ko")
        assertThat(sendWorkoutReminder(completedWorkouts = 3)).isIn(regular)
    }
}

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
import io.mockk.verify
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
        ptMessageTemplates = PTMessageTemplates(),
        appEventRepository = mockk(relaxed = true),
        notificationDeviceRepository = mockk(relaxed = true)
    )

    private fun sendWorkoutReminder(completedWorkouts: Long): String =
        send("workout_reminder", completedWorkouts)!!

    /** 저장된 채팅 메시지(= 실제로 보낸 문구). 정책이 막았으면 null */
    private fun send(
        scheduleName: String,
        completedWorkouts: Long,
        level: com.richjun.liftupai.domain.user.entity.ExperienceLevel? = null,
    ): String? {
        val user = User(id = 5L, email = "new@example.com", nickname = "new").apply { lastLoginAt = AppTime.utcNow() }
        val schedule = NotificationSchedule(
            user = user,
            scheduleName = scheduleName,
            time = LocalTime.of(17, 30),
            message = "old snapshot",
            days = mutableSetOf(DayOfWeek.MON),
            notificationType = NotificationType.AI_MESSAGE,
            nextTriggerAt = AppTime.utcNow().minusMinutes(1)
        )
        val saved = slot<ChatMessage>()
        every { scheduleRepository.findByIdForUpdate(1L) } returns schedule
        every { scheduleRepository.save(any<NotificationSchedule>()) } answers { firstArg() }
        every { userSettingsRepository.findByUser_Id(5L) } returns Optional.of(UserSettings(user = user, language = "ko"))
        every { workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED) } returns completedWorkouts
        every { chatMessageRepository.save(capture(saved)) } answers { firstArg() }
        every { userProfileRepository.findByUser(user) } returns
            if (level == null) Optional.empty()
            else Optional.of(com.richjun.liftupai.domain.user.entity.UserProfile(user = user, experienceLevel = level))

        service.sendScheduledPTMessage(1L)
        return if (saved.isCaptured) saved.captured.aiResponse else null
    }

    @Test
    fun `users who never finished a workout get the one-set first workout copy`() {
        val firstWorkout = NotificationLocalization.messages("pt.template.first_workout.default", "ko")
        assertThat(firstWorkout).hasSize(2)
        assertThat(sendWorkoutReminder(completedWorkouts = 0)).isIn(firstWorkout)
    }

    @Test
    fun `first workout copy promises the short first session`() {
        listOf("ko", "ja", "en").forEach { lang ->
            NotificationLocalization.messages("pt.template.first_workout.default", lang).forEach {
                assertThat(it).contains("30")
            }
        }
    }

    @Test
    fun `users who never finished a workout get no lunch meal check`() {
        assertThat(send("lunch_meal_check", completedWorkouts = 0)).isNull()
        verify(exactly = 0) { chatMessageRepository.save(any()) }
    }

    @Test
    fun `users with a finished workout still get the lunch meal check`() {
        val lunch = NotificationLocalization.messages("pt.template.lunch_meal.default", "ko")
        assertThat(send("lunch_meal_check", completedWorkouts = 1)).isIn(lunch)
    }

    @Test
    fun `users with a finished workout keep the regular reminder`() {
        val regular = NotificationLocalization.messages("pt.template.workout_reminder.default", "ko")
        assertThat(sendWorkoutReminder(completedWorkouts = 3)).isIn(regular)
    }

    @Test
    fun `advanced users before their first workout get the regular reminder, not the 30-minute promise`() {
        // 2026-11-12: 첫 세션 규칙은 상급·전문가에게 안 걸린다(저장된 90분 그대로) → "30분 안쪽으로 짜 놨어요"는 거짓이 된다
        // 프로필이 있으면 고른(기본) 트레이너 말투의 보통 알림 — 말투와 무관하게 '첫 운동' 문구가 아니어야 한다
        listOf(
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.ADVANCED,
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.EXPERT,
        ).forEach {
            val msg = send("workout_reminder", completedWorkouts = 0, level = it)
            assertThat(msg).doesNotContain("첫 운동").contains("30분 전")
        }
        // 중급은 그대로 첫 운동 문구
        val firstWorkout = NotificationLocalization.messages("pt.template.first_workout.default", "ko")
        assertThat(send("workout_reminder", completedWorkouts = 0,
            level = com.richjun.liftupai.domain.user.entity.ExperienceLevel.INTERMEDIATE)).isIn(firstWorkout)
    }
}

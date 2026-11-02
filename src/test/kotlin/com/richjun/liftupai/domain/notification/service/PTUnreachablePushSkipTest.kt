package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.analytics.AppEventRepository
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.DayOfWeek
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.entity.NotificationSchedule
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Optional

/**
 * 푸시가 닿을 곳이 없는 PT 메시지는 만들지 않는다(2026-11 로그: 코칭 13통 중 4통이 Google 테스트 기기, 3통이 기기 없는 휴면 사용자).
 * 앱을 매일 여는 사용자는 기기가 없어도(알림 거부) 채팅에서 보므로 그대로 만든다.
 */
class PTUnreachablePushSkipTest {

    private val scheduleRepository = mockk<NotificationScheduleRepository>(relaxed = true)
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val chatMessageRepository = mockk<ChatMessageRepository>(relaxed = true)
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val appEventRepository = mockk<AppEventRepository>()
    private val deviceRepository = mockk<NotificationDeviceRepository>()

    private val service = PTScheduledMessageService(
        userRepository = mockk(relaxed = true),
        userProfileRepository = userProfileRepository,
        userSettingsRepository = userSettingsRepository,
        notificationScheduleRepository = scheduleRepository,
        notificationHistoryRepository = mockk(relaxed = true),
        workoutSessionRepository = mockk<WorkoutSessionRepository>(relaxed = true),
        chatMessageRepository = chatMessageRepository,
        notificationService = notificationService,
        ptMessageTemplates = PTMessageTemplates(),
        appEventRepository = appEventRepository,
        notificationDeviceRepository = deviceRepository
    )

    /** 재참여 날(미접속 2일)이거나 오늘 접속한 사용자의 운동 알림 하나를 처리하고, 만들어졌는지 돌려준다 */
    private fun run(daysInactive: Long, testDevice: Boolean, hasDevice: Boolean): Boolean {
        val user = User(id = 7L, email = "u@example.com", nickname = "u").apply {
            lastLoginAt = AppTime.utcNow().minusDays(daysInactive).minusHours(1)
        }
        val schedule = NotificationSchedule(
            user = user,
            scheduleName = "workout_reminder",
            time = LocalTime.of(17, 30),
            message = "snapshot",
            days = mutableSetOf(DayOfWeek.MON),
            notificationType = NotificationType.AI_MESSAGE,
            nextTriggerAt = AppTime.utcNow().minusMinutes(1)
        )
        every { scheduleRepository.findByIdForUpdate(1L) } returns schedule
        every { scheduleRepository.save(any<NotificationSchedule>()) } answers { firstArg() }
        every { chatMessageRepository.save(any<ChatMessage>()) } answers { firstArg() }
        every { userSettingsRepository.findByUser_Id(7L) } returns Optional.of(UserSettings(user = user, language = "ko"))
        every { userProfileRepository.findByUser(user) } returns Optional.empty()
        every { appEventRepository.existsByUserIdAndTestDeviceTrue(7L) } returns testDevice
        every { deviceRepository.findActiveDevicesByUser(user) } returns
            if (hasDevice) listOf(NotificationDevice(user = user, deviceToken = "t", platform = DevicePlatform.IOS)) else emptyList()

        service.sendScheduledPTMessage(1L)

        val sent = runCatching { verify(exactly = 1) { notificationService.sendScheduledNotificationWithFcm(schedule, any(), any()) } }.isSuccess
        val chatSaved = runCatching { verify(exactly = 1) { chatMessageRepository.save(any()) } }.isSuccess
        assertThat(chatSaved).isEqualTo(sent)
        if (!sent) {
            // 건너뛰어도 다음 발송 시각은 넘긴다 — 아니면 1분마다 같은 일정을 다시 집는다
            verify { notificationService.recalculateNextTrigger(schedule) }
            verify { scheduleRepository.save(schedule) }
        }
        return sent
    }

    @Test
    fun `Google test device users get nothing even when active with a device`() {
        assertThat(run(daysInactive = 0, testDevice = true, hasDevice = true)).isFalse()
    }

    @Test
    fun `dormant users without an active device are skipped`() {
        assertThat(run(daysInactive = 2, testDevice = false, hasDevice = false)).isFalse()
    }

    @Test
    fun `dormant users with a device still get the re-engagement push`() {
        assertThat(run(daysInactive = 2, testDevice = false, hasDevice = true)).isTrue()
    }

    @Test
    fun `daily users without a device still get the message in chat`() {
        assertThat(run(daysInactive = 0, testDevice = false, hasDevice = false)).isTrue()
    }
}

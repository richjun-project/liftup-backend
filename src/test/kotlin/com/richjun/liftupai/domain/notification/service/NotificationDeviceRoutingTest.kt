package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * 멀티기기 알림 라우팅 회귀 테스트.
 * 활성 기기가 여러 대여도 알림은 "가장 최근 사용한(=실제로 쓰는) 기기 1대"에만 가야 하고,
 * 그 기기 토큰이 죽었을 때만 다음으로 폴백한다. (챗/운동완료 등 종류 무관하게 동일 경로)
 */
class NotificationDeviceRoutingTest {

    private val userRepository = mockk<UserRepository>()
    private val userSettingsRepository = mockk<UserSettingsRepository>(relaxed = true)
    private val notificationDeviceRepository = mockk<NotificationDeviceRepository>()
    private val notificationSettingsRepository = mockk<NotificationSettingsRepository>(relaxed = true)
    private val notificationScheduleRepository = mockk<NotificationScheduleRepository>(relaxed = true)
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>(relaxed = true)
    private val fcm = mockk<FcmNotificationService>()

    private val service = NotificationService(
        userRepository,
        userSettingsRepository,
        notificationDeviceRepository,
        notificationSettingsRepository,
        notificationScheduleRepository,
        notificationHistoryRepository,
        fcm
    )

    private val user = User(id = 1, nickname = "tester")

    private fun device(id: Long, token: String, lastUsed: LocalDateTime, platform: DevicePlatform) =
        NotificationDevice(
            id = id,
            user = user,
            deviceToken = token,
            platform = platform,
            registeredAt = lastUsed,
            lastUsedAt = lastUsed
        )

    private fun request() = PushNotificationRequest(userId = 1, title = "t", body = "b")

    private fun setup(vararg devicesMostRecentFirst: NotificationDevice) {
        every { userRepository.findById(1) } returns Optional.of(user)
        every { userSettingsRepository.findByUser_Id(1) } returns Optional.empty()
        // repository가 lastUsedAt DESC로 정렬해 돌려준다는 계약을 그대로 재현
        every { notificationDeviceRepository.findActiveDevicesByUser(user) } returns devicesMostRecentFirst.toList()
    }

    @Test
    fun `활성 기기가 여러 대여도 가장 최근 사용 기기 1대에만 전송한다`() {
        val recent = device(10, "recent-token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        val old = device(11, "old-token", LocalDateTime.of(2026, 7, 30, 13, 0), DevicePlatform.IOS)
        setup(recent, old)
        every { fcm.sendNotification(recent, any(), any(), any(), any()) } returns true

        val res = service.sendPushNotification(request())

        assertThat(res.success).isTrue()
        assertThat(res.sentCount).isEqualTo(1)
        // 최근 기기에만, 오래된 기기에는 절대 보내지 않는다
        verify(exactly = 1) { fcm.sendNotification(recent, any(), any(), any(), any()) }
        verify(exactly = 0) { fcm.sendNotification(old, any(), any(), any(), any()) }
    }

    @Test
    fun `최근 기기 토큰이 죽어 있으면 다음 최근 기기로 폴백한다`() {
        val recentDead = device(10, "dead-token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        val next = device(11, "live-token", LocalDateTime.of(2026, 7, 30, 13, 0), DevicePlatform.IOS)
        setup(recentDead, next)
        // sendNotification=false 이면 sendToDevice가 예외를 던져 다음으로 넘어간다
        every { fcm.sendNotification(recentDead, any(), any(), any(), any()) } returns false
        every { fcm.sendNotification(next, any(), any(), any(), any()) } returns true

        val res = service.sendPushNotification(request())

        assertThat(res.success).isTrue()
        assertThat(res.sentCount).isEqualTo(1)
        assertThat(res.failedCount).isEqualTo(1)
        verify(exactly = 1) { fcm.sendNotification(recentDead, any(), any(), any(), any()) }
        verify(exactly = 1) { fcm.sendNotification(next, any(), any(), any(), any()) }
    }

    @Test
    fun `활성 기기가 없으면 전송하지 않는다`() {
        setup()
        val res = service.sendPushNotification(request())
        assertThat(res.success).isFalse()
        assertThat(res.sentCount).isEqualTo(0)
        verify(exactly = 0) { fcm.sendNotification(any(), any(), any(), any(), any()) }
    }
}

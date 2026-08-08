package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.entity.NotificationSettings
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
 * 여러 기기에 로그인해 있으면 활성 기기 전부가 알림을 받아야 하고(fan-out),
 * 일부 기기 토큰이 죽어도 나머지 기기 전송은 계속돼야 한다.
 * (챗/운동완료/스케줄 등 종류 무관하게 동일 경로)
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
        // 기본: 알림 설정 행 없음 → 모든 타입 허용 (개별 테스트에서 덮어씀)
        every { notificationSettingsRepository.findByUser(user) } returns Optional.empty()
    }

    @Test
    fun `활성 기기가 여러 대면 전부에 전송한다`() {
        val recent = device(10, "recent-token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        val old = device(11, "old-token", LocalDateTime.of(2026, 7, 30, 13, 0), DevicePlatform.IOS)
        setup(recent, old)
        every { fcm.sendNotification(recent, any(), any(), any(), any()) } returns true
        every { fcm.sendNotification(old, any(), any(), any(), any()) } returns true

        val res = service.sendPushNotification(request())

        assertThat(res.success).isTrue()
        assertThat(res.sentCount).isEqualTo(2)
        assertThat(res.failedCount).isEqualTo(0)
        verify(exactly = 1) { fcm.sendNotification(recent, any(), any(), any(), any()) }
        verify(exactly = 1) { fcm.sendNotification(old, any(), any(), any(), any()) }
    }

    @Test
    fun `일부 기기 토큰이 죽어 있어도 나머지 기기에는 전송한다`() {
        val dead = device(10, "dead-token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        val live = device(11, "live-token", LocalDateTime.of(2026, 7, 30, 13, 0), DevicePlatform.IOS)
        setup(dead, live)
        // sendNotification=false 이면 sendToDevice가 예외를 던지지만 루프는 계속된다
        every { fcm.sendNotification(dead, any(), any(), any(), any()) } returns false
        every { fcm.sendNotification(live, any(), any(), any(), any()) } returns true

        val res = service.sendPushNotification(request())

        assertThat(res.success).isTrue()
        assertThat(res.sentCount).isEqualTo(1)
        assertThat(res.failedCount).isEqualTo(1)
        verify(exactly = 1) { fcm.sendNotification(dead, any(), any(), any(), any()) }
        verify(exactly = 1) { fcm.sendNotification(live, any(), any(), any(), any()) }
    }

    @Test
    fun `해당 타입 토글이 꺼져 있으면 기기가 있어도 전송하지 않는다`() {
        val dev = device(10, "token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        setup(dev)
        every { notificationSettingsRepository.findByUser(user) } returns
            Optional.of(NotificationSettings(user = user, achievements = false))

        val res = service.sendPushNotification(
            PushNotificationRequest(userId = 1, title = "t", body = "b", data = mapOf("type" to "workout_complete"))
        )

        assertThat(res.success).isFalse()
        assertThat(res.sentCount).isEqualTo(0)
        verify(exactly = 0) { fcm.sendNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `토글이 켜져 있으면 해당 타입도 정상 전송한다`() {
        val dev = device(10, "token", LocalDateTime.of(2026, 8, 1, 10, 0), DevicePlatform.ANDROID)
        setup(dev)
        every { notificationSettingsRepository.findByUser(user) } returns
            Optional.of(NotificationSettings(user = user, achievements = true))
        every { fcm.sendNotification(dev, any(), any(), any(), any()) } returns true

        val res = service.sendPushNotification(
            PushNotificationRequest(userId = 1, title = "t", body = "b", data = mapOf("type" to "workout_complete"))
        )

        assertThat(res.success).isTrue()
        assertThat(res.sentCount).isEqualTo(1)
        verify(exactly = 1) { fcm.sendNotification(dev, any(), any(), any(), any()) }
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

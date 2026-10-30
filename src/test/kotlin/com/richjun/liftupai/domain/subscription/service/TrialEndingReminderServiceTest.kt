package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.dto.PushNotificationResponse
import com.richjun.liftupai.domain.notification.entity.NotificationHistory
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.subscription.entity.PaymentStatus
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * 체험 종료 하루 전 알림: 현지 날짜로 "내일" 끝나는 체험에만, 현지 낮에, 한 번, 실제 결제가 없을 때만 보낸다.
 * 결제 화면이 이 알림을 약속하므로 빠지면 안 되고, 결제한 사람·이미 보낸 사람에겐 가면 안 된다.
 */
class TrialEndingReminderServiceTest {

    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val paymentHistoryRepository = mockk<PaymentHistoryRepository>()
    private val notificationHistoryRepository = mockk<NotificationHistoryRepository>()
    private val notificationService = mockk<NotificationService>()
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val workoutSessionRepository = mockk<WorkoutSessionRepository>()

    private val service = TrialEndingReminderService(
        subscriptionRepository,
        paymentHistoryRepository,
        notificationHistoryRepository,
        notificationService,
        userSettingsRepository,
        workoutSessionRepository,
    )

    private val user = User(id = 7, nickname = "tester")
    private val saved = slot<NotificationHistory>()
    private val pushed = slot<PushNotificationRequest>()

    // 2026-11-01 01:00 UTC = 서울 10:00. 체험은 10-26 03:00 UTC 시작 → 11-02 03:00 UTC(서울 11-02 12:00) 만료
    private val now = LocalDateTime.of(2026, 11, 1, 1, 0)
    private val start = LocalDateTime.of(2026, 10, 26, 3, 0)
    private val expiry = LocalDateTime.of(2026, 11, 2, 3, 0)

    private fun trial(autoRenew: Boolean = true, store: String = "APP_STORE", expiresAt: LocalDateTime = expiry) =
        Subscription(
            user = user,
            plan = SubscriptionPlan.PRO,
            status = SubscriptionStatus.ACTIVE,
            startDate = start,
            expiryDate = expiresAt,
            autoRenew = autoRenew,
            store = store,
        )

    private fun given(sub: Subscription, language: String = "ko", timeZone: String = "Asia/Seoul", paid: Boolean = false, days: Int = 3) {
        every { subscriptionRepository.findStoreSubscriptionsExpiringBetween(SubscriptionStatus.ACTIVE, any(), any(), any()) } returns listOf(sub)
        every { userSettingsRepository.findByUser_Id(7) } returns Optional.of(UserSettings(user = user, language = language, timeZone = timeZone))
        every { paymentHistoryRepository.existsByUserAndStatusAndAmountGreaterThan(user, PaymentStatus.SUCCESS, 0.0) } returns paid
        every { workoutSessionRepository.countByUserAndDateRange(user, start, any()) } returns days
    }

    @BeforeEach
    fun setUp() {
        every { notificationHistoryRepository.findByNotificationId(any()) } returns Optional.empty()
        every { notificationHistoryRepository.save(capture(saved)) } answers { firstArg() }
        every { notificationService.sendPushNotification(capture(pushed)) } returns
            PushNotificationResponse(success = true, sentCount = 1, failedCount = 0, message = "ok")
    }

    @Test
    fun `현지 날짜로 내일 끝나는 체험이면 낮에 한 번 보내고 기록을 먼저 남긴다`() {
        given(trial())

        assertThat(service.sendDue(now)).containsExactly(7L)

        assertThat(saved.captured.notificationId).isEqualTo("trial_end:7:${expiry.toEpochSecond(java.time.ZoneOffset.UTC)}")
        assertThat(saved.captured.type).isEqualTo(NotificationType.AI_MESSAGE)
        assertThat(pushed.captured.title).isEqualTo("무료 체험이 내일 끝나요")
        assertThat(pushed.captured.body).isEqualTo("체험 동안 3일 운동했어요. 그대로 두면 Pro가 이어지고, 해지는 App Store 구독 설정에서 할 수 있어요.")
        assertThat(pushed.captured.data).containsEntry("type", "trial_ending")
    }

    @Test
    fun `이미 보낸 체험이면 다시 보내지 않는다`() {
        given(trial())
        every { notificationHistoryRepository.findByNotificationId(any()) } returns Optional.of(mockk())

        assertThat(service.sendDue(now)).isEmpty()
        verify(exactly = 0) { notificationService.sendPushNotification(any()) }
    }

    @Test
    fun `실제로 결제한 적이 있으면 체험이 아니므로 보내지 않는다`() {
        given(trial(), paid = true)

        assertThat(service.sendDue(now)).isEmpty()
        verify(exactly = 0) { notificationService.sendPushNotification(any()) }
    }

    @Test
    fun `현지 밤에는 보내지 않는다`() {
        given(trial())
        // 13:00 UTC = 서울 22:00, 만료(서울 11-02 12:00)는 여전히 현지 "내일"
        assertThat(service.sendDue(LocalDateTime.of(2026, 11, 1, 13, 0))).isEmpty()
    }

    @Test
    fun `만료가 모레면 아직 보내지 않는다`() {
        // 서울 11-03 05:00 만료 — 지금(서울 11-01 10:00)부터 43시간이라 후보엔 들지만 "내일"이 아니다
        given(trial(expiresAt = LocalDateTime.of(2026, 11, 2, 20, 0)))

        assertThat(service.sendDue(now)).isEmpty()
    }

    @Test
    fun `사용자 시간대로 내일과 낮을 판단한다`() {
        // 도쿄 10:00 = 01:00 UTC. 만료 11-02 03:00 UTC = 도쿄 11-02 12:00 → 내일
        given(trial(autoRenew = false), language = "ja", timeZone = "Asia/Tokyo", days = 0)

        assertThat(service.sendDue(now)).containsExactly(7L)
        assertThat(pushed.captured.title).isEqualTo("無料体験は明日で終了します")
        assertThat(pushed.captured.body).isEqualTo("体験が終わると無料プランに戻ります。Proを続ける場合はApp Storeのサブスクリプション設定で再開してください。")
    }

    @Test
    fun `문구는 자동 갱신 여부와 운동한 날 수, 스토어에 맞춘다`() {
        assertThat(TrialEndingReminderService.bodyOf("en", true, 1, "APP_STORE"))
            .isEqualTo("You worked out on 1 day during your trial. Pro continues automatically. To cancel, open your App Store subscription settings.")
        assertThat(TrialEndingReminderService.bodyOf("en", false, 3, "PLAY_STORE"))
            .isEqualTo("You worked out on 3 days during your trial. After it ends you go back to the free plan. To keep Pro, turn your subscription back on in Google Play settings.")
        assertThat(TrialEndingReminderService.bodyOf("ko", false, 2, "APP_STORE"))
            .isEqualTo("체험 동안 2일 운동했어요. 체험이 끝나면 무료 플랜으로 돌아가요. Pro를 계속 쓰려면 App Store 구독 설정에서 다시 켜 주세요.")
        assertThat(TrialEndingReminderService.bodyOf("ja", true, 4, "APP_STORE"))
            .isEqualTo("体験中に4日トレーニングしました。このままProが続きます。解約はApp Storeのサブスクリプション設定からできます。")
    }

    @Test
    fun `세 언어 모두 모든 문구가 있다`() {
        val keys = listOf("title", "renew", "renew_none", "lapse", "lapse_none").map { "notification.trial_ending.$it" }
        for (locale in listOf("ko", "ja", "en")) {
            for (key in keys) {
                assertThat(NotificationLocalization.message(key, locale)).describedAs("$locale $key").isNotEqualTo(key)
            }
        }
        // 한국어·일본어가 영어로 빠지지 않는다
        assertThat(NotificationLocalization.message("notification.trial_ending.title", "ja")).isNotEqualTo("Your free trial ends tomorrow")
        assertThat(NotificationLocalization.message("notification.trial_ending.title", "ko")).isNotEqualTo("Your free trial ends tomorrow")
    }
}

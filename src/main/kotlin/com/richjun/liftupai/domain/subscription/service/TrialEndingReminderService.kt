package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.entity.NotificationHistory
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.subscription.entity.PaymentStatus
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 무료 체험 종료 하루 전 알림(2026-10-30).
 *
 * 결제 화면이 "끝나기 하루 전에 알림으로 알려 드려요"라고 약속한다 — 잊고 결제될까 봐 체험을 시작하자마자
 * 자동 갱신을 끄는 사용자를 줄이려는 것(10-17 일본 체험 1건이 시작 32분 뒤 자동 갱신을 껐다).
 *
 * - 대상: 스토어 구독(APP_STORE·PLAY_STORE)이 ACTIVE 이고 실제 결제(금액 > 0)가 한 번도 없는 사용자 = 체험 중.
 *   운영 KPI 의 in_trial 과 같은 정의. 샌드박스·프로모션·평생(만료 없음)은 store/만료 조건에서 빠진다.
 * - 때: 만료일이 사용자 현지 날짜로 "내일"이고 지금이 현지 [SEND_HOURS] 일 때 한 번. 매시 돌므로 그날 안에 반드시 걸린다.
 * - 문구: 자동 갱신이 켜져 있으면 "그대로 두면 이어지고 해지는 스토어에서", 꺼져 있으면 "다시 켜는 곳" — 체험 중 운동한 날 수를 붙인다.
 * - 중복: notification_history.notificationId(trial_end:<사용자>:<만료 epoch>)가 유일하다. 기록을 먼저 남기고 보낸다
 *   (스케줄 알림과 같은 규칙 — 중복보다 누락이 안전). 기록 종류는 앱 알림함이 아는 AI_MESSAGE(코치 메시지)로 둔다
 *   — 새 enum 값은 운영 컬럼(MySQL enum) 변경과 옛 앱의 알 수 없는 값 처리가 따라온다.
 * - 푸시 data.type 은 trial_ending — 사용자의 마케팅성 알림 토글(aiMessages 등)과 무관한 계정 안내라 끄지 않는다.
 */
@Service
class TrialEndingReminderService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentHistoryRepository: PaymentHistoryRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository,
    private val notificationService: NotificationService,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    fun run() {
        try {
            val sent = sendDue(AppTime.utcNow())
            if (sent.isNotEmpty()) logger.info("[TrialReminder] sent to users $sent")
        } catch (e: Exception) {
            logger.error("[TrialReminder] run failed", e)
        }
    }

    /** [now](UTC) 기준으로 보낼 체험 종료 알림을 보내고, 보낸 사용자 id 를 돌려준다. */
    fun sendDue(now: LocalDateTime): List<Long> {
        val candidates = subscriptionRepository.findStoreSubscriptionsExpiringBetween(
            SubscriptionStatus.ACTIVE, STORES, now, now.plusHours(LOOKAHEAD_HOURS)
        )
        return candidates.mapNotNull { sub ->
            try {
                if (remind(sub, now)) sub.user.id else null
            } catch (e: Exception) {
                logger.error("[TrialReminder] user ${sub.user.id} failed: ${e.message}", e)
                null
            }
        }
    }

    private fun remind(sub: Subscription, now: LocalDateTime): Boolean {
        val expiry = sub.expiryDate ?: return false
        val user = sub.user
        val settings = userSettingsRepository.findByUser_Id(user.id).orElse(null)
        val zone = AppTime.resolveZoneId(settings?.timeZone)
        val localNow = AppTime.toUserLocalDateTime(now, zone)
        if (localNow.hour !in SEND_HOURS) return false
        if (AppTime.toUserLocalDate(expiry, zone) != localNow.toLocalDate().plusDays(1)) return false
        if (paymentHistoryRepository.existsByUserAndStatusAndAmountGreaterThan(user, PaymentStatus.SUCCESS, 0.0)) return false

        val notificationId = "trial_end:${user.id}:${expiry.toEpochSecond(ZoneOffset.UTC)}"
        if (notificationHistoryRepository.findByNotificationId(notificationId).isPresent) return false

        val locale = settings?.language ?: "en"
        val workoutDays = workoutSessionRepository.countByUserAndDateRange(user, sub.startDate, now)
        val title = NotificationLocalization.message("notification.trial_ending.title", locale)
        val body = bodyOf(locale, sub.autoRenew, workoutDays, sub.store)
        val data = mapOf("type" to PUSH_TYPE)

        notificationHistoryRepository.save(
            NotificationHistory(
                user = user,
                notificationId = notificationId,
                type = NotificationType.AI_MESSAGE,
                title = title,
                body = body,
                data = data.toMutableMap()
            )
        )
        val result = notificationService.sendPushNotification(
            PushNotificationRequest(userId = user.id, title = title, body = body, data = data)
        )
        logger.info(
            "[TrialReminder] user=${user.id} autoRenew=${sub.autoRenew} expiry=$expiry workoutDays=$workoutDays " +
                "sent=${result.sentCount} failed=${result.failedCount}"
        )
        return true
    }

    companion object {
        val STORES = listOf("APP_STORE", "PLAY_STORE")
        const val PUSH_TYPE = "trial_ending"
        /** 현지 날짜로 "내일" 끝나는 체험은 지금부터 길어야 48시간 안에 끝난다 */
        const val LOOKAHEAD_HOURS = 48L
        /** 현지 10시~19시대에만 보낸다 */
        val SEND_HOURS = 10..19

        internal fun bodyOf(locale: String, autoRenew: Boolean, workoutDays: Int, store: String?): String {
            val variant = (if (autoRenew) "renew" else "lapse") + (if (workoutDays > 0) "" else "_none")
            val storeName = if (store == "PLAY_STORE") "Google Play" else "App Store"
            return NotificationLocalization.message("notification.trial_ending.$variant", locale, workoutDays, storeName)
        }
    }
}

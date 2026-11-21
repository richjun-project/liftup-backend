package com.richjun.liftupai.domain.ai.proactive

import com.richjun.liftupai.domain.analytics.AppEventRepository
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.entity.NotificationHistory
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.UUID

/**
 * 유료 구독자에게 월요일 아침 9시(사용자 시간)에 주간 리뷰를 먼저 보낸다(2026-11-21 대표 지시, 승인 6-e 주간 리뷰).
 * 한 주에 한 번(알림 기록 scheduleName=weekly_review 로 확인), '트레이너 메시지 끄기'·테스트 기기·푸시 닿을 곳 없음은 건너뛴다.
 * LLM 이 못 쓰거나 검사에서 떨어지면 보내지 않는다(주간 리뷰는 대신할 템플릿이 없다).
 */
@Component
class WeeklyReviewScheduler(
    private val subscriptionRepository: SubscriptionRepository,
    private val proactive: ProactiveCoachService,
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository,
    private val notificationDeviceRepository: NotificationDeviceRepository,
    private val appEventRepository: AppEventRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val notificationService: NotificationService,
    private val userRepository: com.richjun.liftupai.domain.auth.repository.UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    fun hourly() {
        runCatching { runAt(AppTime.utcNow()) }.onFailure { log.error("[PROACTIVE] weekly review run failed: {}", it.message, it) }
    }

    /** @return 보낸 수 */
    fun runAt(nowUtc: LocalDateTime): Int {
        var sent = 0
        val subs = subscriptionRepository.findAll().filter { it.status == SubscriptionStatus.ACTIVE && it.plan != SubscriptionPlan.FREE }
        for (sub in subs) {
            // 구독 행의 user 는 닫힌 세션의 지연 프록시 — id 만 읽고 사용자는 새로 읽는다(스케줄러 스레드엔 트랜잭션이 없다)
            val user = userRepository.findById(sub.user.id).orElse(null) ?: continue
            try {
                val settings = userSettingsRepository.findByUser_Id(user.id).orElse(null)
                val local = AppTime.toUserLocalDateTime(nowUtc, AppTime.resolveZoneId(settings?.timeZone))
                if (local.dayOfWeek != DayOfWeek.MONDAY || local.hour != 9) continue
                val skip = when {
                    notificationSettingsRepository.findByUser(user).orElse(null)?.aiMessages == false -> "ai messages off"
                    appEventRepository.existsByUserIdAndTestDeviceTrue(user.id) -> "test device"
                    notificationDeviceRepository.findActiveDevicesByUser(user).isEmpty() -> "no active device"
                    sentThisWeek(user, nowUtc) -> "already sent"
                    else -> null
                }
                if (skip != null) { log.info("[PROACTIVE] weekly review skipped user={}: {}", user.id, skip); continue }
                val msg = proactive.compose(user, ProactiveCoachService.Kind.WEEKLY_REVIEW) ?: continue
                deliver(user, msg, settings?.language ?: "en")
                sent++
            } catch (e: Exception) {
                log.warn("[PROACTIVE] weekly review failed user={}: {}", user.id, e.message)
            }
        }
        return sent
    }

    private fun sentThisWeek(user: com.richjun.liftupai.domain.auth.entity.User, nowUtc: LocalDateTime): Boolean =
        notificationHistoryRepository.findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 30)).content
            .any { it.data["scheduleName"] == SCHEDULE_NAME && it.createdAt.isAfter(nowUtc.minusDays(6)) }

    private fun deliver(user: com.richjun.liftupai.domain.auth.entity.User, msg: ProactiveCoachService.Composed, locale: String) {
        chatMessageRepository.save(ChatMessage(
            user = user, userMessage = "", aiResponse = msg.text, messageType = MessageType.SYSTEM,
            status = MessageStatus.COMPLETED, actions = ChatAction.join(msg.actions),
        ))
        val title = NotificationLocalization.message(NotificationLocalization.titleKey(NotificationType.AI_MESSAGE), locale)
        val data = hashMapOf("scheduleName" to SCHEDULE_NAME, "type" to NotificationType.AI_MESSAGE.name.lowercase())
        notificationHistoryRepository.save(NotificationHistory(
            user = user, notificationId = UUID.randomUUID().toString(), type = NotificationType.AI_MESSAGE,
            title = title, body = msg.text, data = data.toMutableMap(),
        ))
        runCatching { notificationService.sendPushNotification(PushNotificationRequest(userId = user.id, title = title, body = msg.text, data = data)) }
            .onFailure { log.warn("[PROACTIVE] weekly review push failed user={}: {}", user.id, it.message) }
        log.info("[PROACTIVE] weekly review sent user={}", user.id)
    }

    companion object { const val SCHEDULE_NAME = "weekly_review" }
}

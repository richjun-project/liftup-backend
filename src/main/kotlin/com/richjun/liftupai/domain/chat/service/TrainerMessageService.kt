package com.richjun.liftupai.domain.chat.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.service.UserPlanService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 고른 트레이너가 채팅에서 먼저 말을 거는 메시지들.
 *
 * - 첫 인사: 채팅을 처음 열었을 때(대화 0건, 플랜 있음) 오늘 운동을 소개하고 [지금 시작][알림 받기] 를 붙인다.
 * - 운동 피드백: 운동을 마치면 결과에 그 말투로 반응한다.
 * 문구는 템플릿(캐릭터 10명 × ko/en/ja)이라 즉시 나가고, 모델 호출 실패에 영향받지 않는다.
 */
@Service
class TrainerMessageService(
    private val chatMessageRepository: ChatMessageRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    // 운동 완료 경로(WorkoutSessionService)에서도 불리므로 지연 주입해 순환 의존을 피한다
    private val userPlanService: ObjectProvider<UserPlanService>,
    /** PRO 이면 통계 버튼, 아니면 플랜 버튼(통계 탭은 PRO 전용이라 무료 사용자에겐 잠겨 있다) — 없으면 무료로 본다(단위 테스트) */
    private val subscriptionService: ObjectProvider<com.richjun.liftupai.domain.subscription.service.SubscriptionService>? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 대화가 하나도 없고 플랜이 있으면 트레이너 첫 인사를 남긴다. 남겼으면 true.
     *  읽기 전용일 수 있는 조회 트랜잭션 안에서 불리므로 따로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureWelcome(user: User): Boolean {
        if (chatMessageRepository.countByUser(user) > 0) return false
        val planService = userPlanService.ifAvailable ?: return false
        val plan = planService.getActivePlan(user.id) ?: return false
        val locale = locale(user)
        val day = try {
            planService.getDayWorkout(user.id, plan.currentDay, locale = locale)
        } catch (e: Exception) {
            log.warn("[Trainer] welcome skipped for user {}: day workout unavailable ({})", user.id, e.message)
            return false
        }
        if (day.exercises.isEmpty()) return false
        val text = styled(
            "pt.welcome", user, locale,
            user.nickname, plan.planName, day.dayName, day.exercises.size, day.estimatedDuration,
        )
        save(user, text, listOf(ChatAction.START_TODAY_WORKOUT, ChatAction.SET_REMINDER))
        log.info("[Trainer] welcome sent to user {}", user.id)
        return true
    }

    /**
     * 운동을 마친 직후 트레이너의 반응(플레이북 2 '운동 후 정리', 2026-11-14). 세트를 하나도 끝내지 않았거나 사람이 할 수 없는
     * 속도로 끝낸 세션(SessionQuality)이면 남기지 않는다 — 누르기만 한 15세트를 칭찬하지 않는다.
     * 그 말투의 반응 뒤에 다음 운동(Day·첫 종목)을 한 줄 붙인다(실제 플랜 값, 운동 완료 → Day 넘김 순서라 지금 Day 의 다음 Day).
     * 버튼: PRO 는 통계, 무료는 플랜(통계 탭은 PRO 전용 — 예전엔 무료 사용자에게 잠긴 화면으로 보냈다).
     * 실패해도 운동 저장이 롤백되지 않게 따로 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onWorkoutCompleted(user: User, totalSets: Int, totalVolume: Double, durationMinutes: Int, implausible: Boolean = false) {
        if (totalSets <= 0 || implausible) return
        val locale = locale(user)
        val reaction = styled(
            "pt.feedback", user, locale,
            user.nickname, totalSets, "%,.0f".format(totalVolume), durationMinutes,
        )
        val next = runCatching { nextSessionLine(user, locale) }
            .onFailure { log.warn("[Trainer] next session preview failed user={}: {}", user.id, it.message) }.getOrNull()
        val pro = runCatching { subscriptionService?.ifAvailable?.hasActiveSubscription(user.id) == true }.getOrDefault(false)
        save(user, if (next != null) "$reaction\n\n$next" else reaction, listOf(if (pro) ChatAction.OPEN_STATS else ChatAction.OPEN_PLAN))
    }

    /** "다음 운동은 Day 2 하체, 스쿼트부터예요." 플랜이 없거나 종목을 모르면 null */
    private fun nextSessionLine(user: User, locale: String): String? {
        val plans = userPlanService.ifAvailable ?: return null
        val plan = plans.getActivePlan(user.id) ?: return null
        if (plan.totalDays <= 0) return null
        val nextDay = plan.currentDay % plan.totalDays + 1
        val day = plans.getDayWorkout(user.id, nextDay, locale = locale)
        val first = day.exercises.firstOrNull()?.exerciseName?.takeIf { it.isNotBlank() } ?: return null
        return NotificationLocalization.message("pt.feedback.next", locale, day.dayNumber, day.dayName, first)
    }

    private fun styled(prefix: String, user: User, locale: String, vararg args: Any?): String {
        val style = userProfileRepository.findByUser(user).orElse(null)?.ptStyle?.name?.lowercase()
        val key = style?.let { "$prefix.$it" }?.takeIf { NotificationLocalization.has(it, locale) } ?: "$prefix.default"
        return NotificationLocalization.message(key, locale, *args)
    }

    private fun save(user: User, text: String, actions: List<ChatAction>) {
        chatMessageRepository.save(
            ChatMessage(
                user = user,
                userMessage = "",
                aiResponse = text,
                messageType = MessageType.SYSTEM,
                status = MessageStatus.COMPLETED,
                actions = ChatAction.join(actions),
            )
        )
    }

    private fun locale(user: User): String =
        userSettingsRepository.findByUser_Id(user.id).orElse(null)?.language ?: "en"
}

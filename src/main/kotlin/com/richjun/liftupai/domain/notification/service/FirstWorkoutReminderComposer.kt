package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.domain.workout.service.UserTrainingContextResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 첫 운동 전 사용자의 운동 알림을 그 사람의 오늘 플랜으로 만든다(2026-11-14 coach-autopilot-r1, 플레이북 1).
 *
 * 예전 문구는 모두에게 같았다("첫 운동은 30분 안쪽으로 가볍게 짜 놨어요"). 304 처럼 집에서 기구 없이 하는 사람에게도
 * 헬스장 플랜 그대로 같은 말이 갔다. 이제 오늘 첫 종목(그 사람 언어의 이름)·예상 시간·장소(기구 없음)를 넣는다.
 * LLM 없이 문구 틀에 실제 값만 채운다 — 숫자를 지어낼 수 없고, 발송 횟수·시각은 그대로다(PtMessagePolicy).
 * 플랜이 없거나 값이 이상하면 null → 호출자가 예전 문구를 쓴다.
 *
 * 스위치: ai.coach.autopilot.mode off | allowlist | on (allowlist 는 ai.coach.autopilot.allowlist 의 사용자 id).
 */
@Component
class FirstWorkoutReminderComposer(
    private val userPlanService: UserPlanService,
    private val trainingContext: UserTrainingContextResolver,
) {
    @Value("\${ai.coach.autopilot.mode:on}")
    var mode: String = "on"

    @Value("\${ai.coach.autopilot.allowlist:}")
    var allowlist: String = ""

    data class Composed(val text: String, val variant: String, val exerciseName: String, val minutes: Int)

    fun enabledFor(userId: Long): Boolean = when (mode.trim().lowercase()) {
        "on" -> true
        "allowlist" -> allowlist.split(',').mapNotNull { it.trim().toLongOrNull() }.contains(userId)
        else -> false
    }

    /**
     * 호출자(PT 메시지 발송)의 트랜잭션과 분리한다 — 플랜 조회가 실패해도 발송 트랜잭션이 rollback-only 로 오염되지 않는다.
     * 호출자는 runCatching 으로 감싸고, null 이면 예전 문구를 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun compose(user: User, locale: String): Composed? {
        if (!enabledFor(user.id)) return null
        val plan = userPlanService.getActivePlan(user.id) ?: return null
        val day = userPlanService.getDayWorkout(user.id, plan.currentDay, locale = locale)
        val first = day.exercises.firstOrNull()?.exerciseName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
        val minutes = day.estimatedDuration.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: return null
        val equipment = runCatching { trainingContext.resolve(user.id).equipment }.getOrDefault(emptySet())
        val variant = if (bodyweightOnly(equipment)) "bodyweight" else "default"
        val pattern = NotificationLocalization.messages("pt.template.first_workout_personal.$variant", locale).random()
        val text = pattern.replace("{exercise}", first).replace("{minutes}", minutes.toString())
        if ('{' in text) return null // 틀이 비었거나 번역이 깨졌다 — 예전 문구로
        return Composed(text, variant, first, minutes)
    }

    companion object {
        const val MIN_MINUTES = 5
        const val MAX_MINUTES = 120
        const val MAX_NAME = 40

        /** 기구 없이 하는 사람(맨몸만). 장비를 모르면(빈 값) 아니다 */
        fun bodyweightOnly(equipment: Collection<String>): Boolean =
            equipment.isNotEmpty() && equipment.all { it.trim().uppercase() in setOf("BODYWEIGHT", "NONE") }
    }
}

package com.richjun.liftupai.domain.notification.util

/**
 * PT 체크인 발송 정책.
 * 예전에는 모든 사용자에게 매일 5통(07:30·12:30·운동 30분 전·19:00·22:00)을 보냈다.
 * 2026-09 실측: 수신자 27명 중 최근 7일 활동은 3명뿐이었고, 휴면 사용자에게 하루 5통은 알림 차단·삭제로 이어진다.
 *
 * - 최근 활동(마지막 활동 후 1일 이내): 운동 30분 전 + 점심 식단 체크, 하루 2통
 * - 2~30일 미접속: 운동 알림만, 미접속 2·4·7·14·30일째에 한 번씩
 * - 30일 넘게 미접속: 보내지 않는다(다시 접속하면 lastLoginAt 이 갱신돼 자동으로 재개)
 * 이 정책이 모르는 스케줄 이름은 그대로 보낸다.
 */
object PtMessagePolicy {
    const val WORKOUT_REMINDER = "workout_reminder"
    const val LUNCH_MEAL_CHECK = "lunch_meal_check"

    private val PT_SCHEDULES = setOf(
        "morning_meal_check", LUNCH_MEAL_CHECK, WORKOUT_REMINDER, "dinner_meal_check", "sleep_prep"
    )
    private val ACTIVE_SCHEDULES = setOf(WORKOUT_REMINDER, LUNCH_MEAL_CHECK)
    private const val ACTIVE_WITHIN_DAYS = 1L
    private val REENGAGEMENT_DAYS = setOf(2L, 4L, 7L, 14L, 30L)

    fun shouldSend(scheduleName: String, daysInactive: Long): Boolean = when {
        scheduleName !in PT_SCHEDULES -> true
        daysInactive <= ACTIVE_WITHIN_DAYS -> scheduleName in ACTIVE_SCHEDULES
        daysInactive in REENGAGEMENT_DAYS -> scheduleName == WORKOUT_REMINDER
        else -> false
    }
}

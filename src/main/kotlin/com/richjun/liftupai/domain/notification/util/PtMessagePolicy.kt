package com.richjun.liftupai.domain.notification.util

/**
 * PT 체크인 발송 정책.
 * 예전에는 모든 사용자에게 매일 5통(07:30·12:30·운동 30분 전·19:00·22:00)을 보냈다.
 * 2026-09 실측: 수신자 27명 중 최근 7일 활동은 3명뿐이었고, 휴면 사용자에게 하루 5통은 알림 차단·삭제로 이어진다.
 *
 * - 최근 활동(마지막 활동 후 1일 이내): 운동 30분 전 + 점심 식단 체크, 하루 2통
 * - 2~30일 미접속: 운동 알림만, 미접속 2·4·7·14·30일째에 한 번씩
 * - 30일 넘게 미접속: 보내지 않는다(다시 접속하면 lastLoginAt 이 갱신돼 자동으로 재개)
 * - 첫 운동 전(완료 운동 0회): 식사 체크는 보내지 않는다 — 운동 알림(첫 운동 문구) 하나만.
 *   2026-11 실측: 10-14 이후 실가입 6명 모두 다음 날 앱을 다시 열지 않았고, 이들이 받은 하루 2통 중 1통은
 *   식단 기록 버튼이 달린 점심 식사 체크였다(아직 아무것도 안 해 본 사람에게 "한낮 보급 시간이다").
 * 이 정책이 모르는 스케줄 이름은 그대로 보낸다.
 */
object PtMessagePolicy {
    const val WORKOUT_REMINDER = "workout_reminder"
    const val LUNCH_MEAL_CHECK = "lunch_meal_check"

    private val MEAL_CHECKS = setOf("morning_meal_check", LUNCH_MEAL_CHECK, "dinner_meal_check")
    private val PT_SCHEDULES = MEAL_CHECKS + setOf(WORKOUT_REMINDER, "sleep_prep")
    private val ACTIVE_SCHEDULES = setOf(WORKOUT_REMINDER, LUNCH_MEAL_CHECK)
    private const val ACTIVE_WITHIN_DAYS = 1L
    private val REENGAGEMENT_DAYS = setOf(2L, 4L, 7L, 14L, 30L)

    fun isMealCheck(scheduleName: String): Boolean = scheduleName in MEAL_CHECKS

    /** [firstWorkoutPending] 완료한 운동이 하나도 없는 사용자면 true — 식사 체크를 막는다. */
    fun shouldSend(scheduleName: String, daysInactive: Long, firstWorkoutPending: Boolean = false): Boolean = when {
        scheduleName !in PT_SCHEDULES -> true
        firstWorkoutPending && isMealCheck(scheduleName) -> false
        daysInactive <= ACTIVE_WITHIN_DAYS -> scheduleName in ACTIVE_SCHEDULES
        daysInactive in REENGAGEMENT_DAYS -> scheduleName == WORKOUT_REMINDER
        else -> false
    }

    /**
     * 앱을 하루 넘게 열지 않은 사용자에게 가는 메시지(재참여)는 푸시로만 닿는다 — 채팅에 쌓아 둬도 볼 사람이 없다.
     * 매일 여는 사용자는 알림을 꺼 둬도 채팅에서 PT 메시지를 본다.
     */
    fun reachesOnlyByPush(daysInactive: Long): Boolean = daysInactive > ACTIVE_WITHIN_DAYS
}

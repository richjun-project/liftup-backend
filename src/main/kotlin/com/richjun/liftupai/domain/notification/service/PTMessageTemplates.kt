package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import org.springframework.stereotype.Component

/**
 * 알림 메시지는 단일 노멀 페르소나(default 템플릿)로 고정한다.
 * PT 스타일별 페르소나와 AI 동적 생성은 채팅에서만 사용하고, 푸시/스케줄 메시지에는 쓰지 않는다.
 */
@Component
class PTMessageTemplates {

    fun getMorningMealMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("morning_meal", locale)

    fun getLunchMealMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("lunch_meal", locale)

    fun getWorkoutReminderMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("workout_reminder", locale)

    /** 완료한 운동이 하나도 없는 사용자용 운동 알림 — 첫 세트까지의 문턱을 낮춘다. */
    fun getFirstWorkoutMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("first_workout", locale)

    fun getDinnerMealMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("dinner_meal", locale)

    fun getSleepPrepMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("sleep_prep", locale)

    fun getWorkoutCompleteMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("workout_complete", locale)

    fun getRestDayMessage(locale: String = "en"): String =
        NotificationLocalization.ptTemplate("rest_day", locale)
}

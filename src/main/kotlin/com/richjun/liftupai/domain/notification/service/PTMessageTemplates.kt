package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.entity.PTStyle
import org.springframework.stereotype.Component

@Component
class PTMessageTemplates {

    fun getMorningMealMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("morning_meal", style, locale)

    fun getLunchMealMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("lunch_meal", style, locale)

    fun getWorkoutReminderMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("workout_reminder", style, locale)

    fun getDinnerMealMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("dinner_meal", style, locale)

    fun getSleepPrepMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("sleep_prep", style, locale)

    fun getWorkoutCompleteMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("workout_complete", style, locale)

    fun getRestDayMessage(style: PTStyle, locale: String = "en"): String =
        NotificationLocalization.ptTemplate("rest_day", style, locale)
}

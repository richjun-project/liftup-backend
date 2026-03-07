package com.richjun.liftupai.domain.notification.util

import com.richjun.liftupai.domain.notification.entity.DayOfWeek
import com.richjun.liftupai.global.time.AppTime
import java.time.LocalDateTime
import java.time.LocalTime

object NotificationScheduleTimeCalculator {
    fun calculateNextTrigger(
        days: Set<DayOfWeek>,
        time: LocalTime,
        timeZone: String?,
        nowUtc: LocalDateTime = AppTime.utcNow()
    ): LocalDateTime {
        val zoneId = AppTime.resolveZoneId(timeZone)
        val now = AppTime.toUserZonedDateTime(nowUtc, zoneId)
        val today = now.dayOfWeek
        val todayTime = now.toLocalTime()
        val javaDays = days.map(::toJavaDayOfWeek).toSet()

        if (javaDays.contains(today) && !todayTime.isAfter(time)) {
            return AppTime.toUtc(now.toLocalDate().atTime(time), zoneId)
        }

        for (offset in 1..7) {
            val nextDay = today.plus(offset.toLong())
            if (javaDays.contains(nextDay)) {
                return AppTime.toUtc(now.toLocalDate().plusDays(offset.toLong()).atTime(time), zoneId)
            }
        }

        return AppTime.toUtc(now.toLocalDate().plusDays(1).atTime(time), zoneId)
    }

    private fun toJavaDayOfWeek(day: DayOfWeek): java.time.DayOfWeek {
        return when (day) {
            DayOfWeek.MON -> java.time.DayOfWeek.MONDAY
            DayOfWeek.TUE -> java.time.DayOfWeek.TUESDAY
            DayOfWeek.WED -> java.time.DayOfWeek.WEDNESDAY
            DayOfWeek.THU -> java.time.DayOfWeek.THURSDAY
            DayOfWeek.FRI -> java.time.DayOfWeek.FRIDAY
            DayOfWeek.SAT -> java.time.DayOfWeek.SATURDAY
            DayOfWeek.SUN -> java.time.DayOfWeek.SUNDAY
        }
    }
}

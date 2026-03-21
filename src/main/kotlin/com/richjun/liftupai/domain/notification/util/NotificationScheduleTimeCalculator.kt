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

        // 오늘이 대상 요일이고 아직 시간이 안 지났으면 오늘 발송
        if (javaDays.contains(today) && !todayTime.isAfter(time)) {
            // ZonedDateTime 산술로 DST gap/overlap 자동 처리
            val target = now.with(time)
            return target.withZoneSameInstant(AppTime.UTC_ZONE).toLocalDateTime()
        }

        // 다음 대상 요일 찾기
        for (offset in 1..7) {
            val nextDay = today.plus(offset.toLong())
            if (javaDays.contains(nextDay)) {
                val target = now.plusDays(offset.toLong()).with(time)
                return target.withZoneSameInstant(AppTime.UTC_ZONE).toLocalDateTime()
            }
        }

        // 폴백: 내일 같은 시간
        val target = now.plusDays(1).with(time)
        return target.withZoneSameInstant(AppTime.UTC_ZONE).toLocalDateTime()
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

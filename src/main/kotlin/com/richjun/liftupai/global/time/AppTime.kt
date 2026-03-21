package com.richjun.liftupai.global.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object AppTime {
    private val utcOffset: ZoneOffset = ZoneOffset.UTC
    private val apiFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun utcNow(): LocalDateTime = LocalDateTime.now(utcOffset)

    fun utcToday(): LocalDate = LocalDate.now(utcOffset)

    fun resolveZoneId(rawTimeZone: String?): ZoneId {
        val normalized = rawTimeZone?.trim().takeIf { !it.isNullOrBlank() } ?: return DEFAULT_USER_ZONE
        return try {
            ZoneId.of(normalized)
        } catch (_: Exception) {
            DEFAULT_USER_ZONE
        }
    }

    fun requireZoneId(rawTimeZone: String?): ZoneId {
        val normalized = rawTimeZone?.trim().takeIf { !it.isNullOrBlank() }
            ?: throw IllegalArgumentException("Time zone is required")
        return ZoneId.of(normalized)
    }

    fun formatUtc(dateTime: LocalDateTime?): String? {
        return dateTime?.atOffset(utcOffset)?.format(apiFormatter)
    }

    fun formatUtcRequired(dateTime: LocalDateTime): String = formatUtc(dateTime)
        ?: throw IllegalArgumentException("dateTime is required")

    fun toUserZonedDateTime(utcDateTime: LocalDateTime, zoneId: ZoneId): ZonedDateTime {
        return utcDateTime.atOffset(utcOffset).atZoneSameInstant(zoneId)
    }

    fun toUserLocalDateTime(utcDateTime: LocalDateTime, zoneId: ZoneId): LocalDateTime {
        return toUserZonedDateTime(utcDateTime, zoneId).toLocalDateTime()
    }

    fun toUserLocalDate(utcDateTime: LocalDateTime, zoneId: ZoneId): LocalDate {
        return toUserZonedDateTime(utcDateTime, zoneId).toLocalDate()
    }

    fun currentUserDate(zoneId: ZoneId): LocalDate = utcNow().atOffset(utcOffset).atZoneSameInstant(zoneId).toLocalDate()

    fun toUtc(localDateTime: LocalDateTime, zoneId: ZoneId): LocalDateTime {
        return localDateTime.atZone(zoneId).withZoneSameInstant(utcOffset).toLocalDateTime()
    }

    fun utcRangeForLocalDate(date: LocalDate, zoneId: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val start = date.atStartOfDay(zoneId).withZoneSameInstant(utcOffset).toLocalDateTime()
        val end = date.plusDays(1).atStartOfDay(zoneId).withZoneSameInstant(utcOffset).toLocalDateTime()
        return start to end
    }

    fun parseClientDateTime(source: String, userZoneId: ZoneId = DEFAULT_USER_ZONE): LocalDateTime {
        try {
            return OffsetDateTime.parse(source, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .withOffsetSameInstant(utcOffset)
                .toLocalDateTime()
        } catch (_: DateTimeParseException) {
        }

        try {
            return Instant.parse(source).atOffset(utcOffset).toLocalDateTime()
        } catch (_: DateTimeParseException) {
        }

        val localDateTime = LocalDateTime.parse(source, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return toUtc(localDateTime, userZoneId)
    }

    val UTC_ZONE: ZoneId = ZoneId.of("UTC")

    const val DEFAULT_TIME_ZONE: String = "Asia/Seoul"
    val DEFAULT_USER_ZONE: ZoneId = ZoneId.of(DEFAULT_TIME_ZONE)

    fun isValidTimeZone(timeZone: String?): Boolean {
        if (timeZone.isNullOrBlank()) return false
        return try {
            ZoneId.of(timeZone.trim())
            true
        } catch (_: Exception) {
            false
        }
    }
}

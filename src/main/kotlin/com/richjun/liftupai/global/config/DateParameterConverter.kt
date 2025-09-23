package com.richjun.liftupai.global.config

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Custom converter to handle flexible date parameter formats
 * Supports both date-only (2024-01-20) and ISO 8601 (2024-01-20T00:00:00Z) formats
 */
@Component
class StringToLocalDateTimeConverter : Converter<String, LocalDateTime> {

    private val dateOnlyFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME
    private val dateTimeOffsetFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override fun convert(source: String): LocalDateTime {
        // Try parsing as full ISO 8601 with timezone first
        try {
            return LocalDateTime.parse(source, dateTimeOffsetFormatter)
        } catch (e: DateTimeParseException) {
            // Ignore and try next format
        }

        // Try parsing as ISO date-time without timezone
        try {
            return LocalDateTime.parse(source, dateTimeFormatter)
        } catch (e: DateTimeParseException) {
            // Ignore and try next format
        }

        // Try parsing as date-only and convert to LocalDateTime at start of day
        try {
            val date = LocalDate.parse(source, dateOnlyFormatter)
            return date.atStartOfDay()
        } catch (e: DateTimeParseException) {
            // If all formats fail, throw exception
            throw IllegalArgumentException(
                "Invalid date format: $source. Supported formats: " +
                "YYYY-MM-DD, YYYY-MM-DDTHH:MM:SS, YYYY-MM-DDTHH:MM:SSZ"
            )
        }
    }
}

@Component
class StringToLocalDateConverter : Converter<String, LocalDate> {

    private val dateOnlyFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

    override fun convert(source: String): LocalDate {
        // Try parsing as date-only first
        try {
            return LocalDate.parse(source, dateOnlyFormatter)
        } catch (e: DateTimeParseException) {
            // Ignore and try next format
        }

        // Try parsing as ISO date-time and extract date part
        try {
            val dateTime = LocalDateTime.parse(source, dateTimeFormatter)
            return dateTime.toLocalDate()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException(
                "Invalid date format: $source. Supported formats: " +
                "YYYY-MM-DD, YYYY-MM-DDTHH:MM:SS"
            )
        }
    }
}
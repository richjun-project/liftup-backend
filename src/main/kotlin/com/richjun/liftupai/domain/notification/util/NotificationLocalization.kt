package com.richjun.liftupai.domain.notification.util

import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.user.entity.PTStyle
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object NotificationLocalization {
    private const val BASENAME = "i18n.notification_messages"

    fun normalizeLocale(rawLocale: String?): String {
        val locale = rawLocale
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')

        return if (locale.isNullOrBlank()) "en" else locale
    }

    fun toLocale(rawLocale: String?): Locale {
        val normalized = normalizeLocale(rawLocale)
        return Locale.forLanguageTag(normalized).takeIf { it.language.isNotBlank() } ?: Locale.ENGLISH
    }

    fun message(key: String, locale: String = "en", vararg args: Any?): String {
        val normalizedLocale = normalizeLocale(locale)
        val pattern = lookup(key, normalizedLocale)
        return MessageFormat(pattern, toLocale(normalizedLocale)).format(args)
    }

    fun messages(key: String, locale: String = "en"): List<String> {
        return message(key, locale)
            .split("||")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(key) }
    }

    fun titleKey(type: NotificationType): String {
        return when (type) {
            NotificationType.WORKOUT_REMINDER -> "notification.title.workout_reminder"
            NotificationType.ACHIEVEMENT -> "notification.title.achievement"
            NotificationType.STREAK -> "notification.title.streak"
            NotificationType.REST_DAY -> "notification.title.rest_day"
            NotificationType.RECOVERY_ALERT -> "notification.title.recovery"
            NotificationType.AI_MESSAGE -> "notification.title.ai_message"
        }
    }

    fun ptTemplate(context: String, style: PTStyle, locale: String = "en"): String {
        val styleKey = when (style) {
            PTStyle.SPARTAN -> "spartan"
            PTStyle.BURNOUT -> "burnout"
            PTStyle.GAME_MASTER -> "game_master"
            PTStyle.INFLUENCER -> "influencer"
            else -> "default"
        }

        return messages("pt.template.$context.$styleKey", locale).random()
    }

    private fun lookup(key: String, locale: String): String {
        val requested = bundle(locale)
        if (requested.containsKey(key)) {
            return requested.getString(key)
        }

        val base = bundle("en")
        return if (base.containsKey(key)) base.getString(key) else key
    }

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale))
    }
}

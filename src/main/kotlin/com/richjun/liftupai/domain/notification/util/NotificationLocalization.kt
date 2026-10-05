package com.richjun.liftupai.domain.notification.util

import com.richjun.liftupai.domain.notification.entity.NotificationType
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
        // 인자가 없으면 MessageFormat 을 거치지 않는다 — 거치면 "don't" 의 아포스트로피가 인용 기호로 먹혀 사라진다.
        if (args.isEmpty()) return pattern
        return MessageFormat(pattern, toLocale(normalizedLocale)).format(args)
    }

    /** 키가 번들에 있는가(요청 언어 또는 영어). */
    fun has(key: String, locale: String = "en"): Boolean = lookup(key, normalizeLocale(locale)) != key

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

    /** 푸시/스케줄 메시지는 단일 노멀 페르소나(default 템플릿)로 고정 */
    /** 사용자가 고른 트레이너 말투의 PT 문구. 그 말투 문구가 없으면 기본 문구. */
    fun ptTemplate(context: String, locale: String = "en", style: String? = null): String {
        val styled = style?.let { "pt.template.$context.${it.lowercase()}" }
        val key = if (styled != null && has(styled, locale)) styled else "pt.template.$context.default"
        return messages(key, locale).random()
    }

    private fun lookup(key: String, locale: String): String {
        val requested = bundle(locale)
        if (requested.containsKey(key)) {
            return requested.getString(key)
        }

        val base = bundle("en")
        return if (base.containsKey(key)) base.getString(key) else key
    }

    private val noFallback: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private fun bundle(locale: String): ResourceBundle {
        // 영어 전용 파일이 없어 기본 파일(영어)을 써야 하는데, 기본 Control 은 JVM 기본 로케일(한국 맥에서 ko)로
        // 먼저 넘어가 영어 사용자에게 한국어 문구를 준다. WorkoutLocalization 과 같은 이유로 폴백을 끈다.
        return ResourceBundle.getBundle(BASENAME, toLocale(locale), noFallback)
    }
}

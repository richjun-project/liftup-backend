package com.richjun.liftupai.global.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object ErrorLocalization {
    private const val BASENAME = "i18n.error_messages"

    fun message(key: String, locale: String = "en", vararg args: Any?): String {
        val resolvedLocale = normalizeLocale(locale)
        val pattern = try {
            bundle(resolvedLocale).getString(key)
        } catch (e: Exception) {
            try {
                bundle("en").getString(key)
            } catch (e2: Exception) {
                return key
            }
        }
        return if (args.isEmpty()) pattern
        else MessageFormat(pattern, toLocale(resolvedLocale)).format(args)
    }

    private fun normalizeLocale(rawLocale: String?): String {
        val locale = rawLocale
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
        return if (locale.isNullOrBlank()) "en" else locale
    }

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale))
    }

    private fun toLocale(locale: String): Locale {
        val normalized = normalizeLocale(locale)
        return when {
            normalized.startsWith("ko") -> Locale.KOREAN
            normalized.startsWith("ja") -> Locale.JAPANESE
            else -> Locale.ENGLISH
        }
    }
}

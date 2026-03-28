package com.richjun.liftupai.global.i18n

import java.util.Locale
import java.util.ResourceBundle

object ErrorLocalization {
    private const val BASENAME = "i18n.error_messages"

    fun message(key: String, locale: String = "en"): String {
        return try {
            bundle(locale).getString(key)
        } catch (e: Exception) {
            bundle("en").getString(key)
        }
    }

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale))
    }

    private fun toLocale(locale: String): Locale {
        return when (locale.lowercase()) {
            "ko" -> Locale.KOREAN
            "ja" -> Locale.JAPANESE
            else -> Locale.ENGLISH
        }
    }
}

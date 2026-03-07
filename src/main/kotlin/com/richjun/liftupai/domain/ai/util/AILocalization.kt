package com.richjun.liftupai.domain.ai.util

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object AILocalization {
    private const val BASENAME = "i18n.ai_messages"

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

    fun maybeMessage(key: String, locale: String = "en"): String? {
        val normalizedLocale = normalizeLocale(locale)
        if (!hasKey(key, normalizedLocale)) return null
        return lookup(key, normalizedLocale)
    }

    fun keywordAliases(key: String): List<String> {
        val locales = listOf("en", "ko")
        return locales
            .flatMap { locale ->
                maybeMessage(key, locale)
                    ?.split(",")
                    ?.map { token -> token.trim() }
                    ?.filter { token -> token.isNotBlank() }
                    ?: emptyList()
            }
            .distinct()
    }

    fun responseLanguage(locale: String): String {
        val resolved = toLocale(locale)
        val language = resolved.getDisplayLanguage(Locale.ENGLISH)
        return language.takeIf { it.isNotBlank() } ?: "English"
    }

    private fun lookup(key: String, locale: String): String {
        val bundle = bundle(locale)
        if (bundle.containsKey(key)) return bundle.getString(key)

        val englishBundle = bundle("en")
        if (englishBundle.containsKey(key)) return englishBundle.getString(key)

        return key
    }

    private fun hasKey(key: String, locale: String): Boolean {
        return bundle(locale).containsKey(key) || bundle("en").containsKey(key)
    }

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale))
    }
}

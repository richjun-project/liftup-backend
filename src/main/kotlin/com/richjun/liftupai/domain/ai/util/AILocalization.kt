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
        // Must cover every locale that ai_messages_*.properties ships keywords for (en/ko/ja), otherwise
        // getFallbackResponse()'s keyword classification silently ignores a locale's keywords even though
        // its bundle already defines them (e.g. ja was missing here despite ai_messages_ja.properties
        // having fallback.keyword.* entries).
        val locales = listOf("en", "ko", "ja")
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

    // 기본 fallback 컨트롤은 요청 로케일 번들이 없으면 JVM 기본 로케일(예: ko_KR 머신의 ko) 번들을
    // 먼저 집는다 — 영어 사용자가 ko_KR 서버에서 한국어 폴백을 받게 되는 함정. 기본(base) 번들로만 폴백한다.
    private val noFallback: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale), noFallback)
    }
}

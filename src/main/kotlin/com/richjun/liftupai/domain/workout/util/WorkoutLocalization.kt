package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object WorkoutLocalization {
    private const val BASENAME = "i18n.workout_messages"

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

    fun maybeMessage(key: String, locale: String = "en", vararg args: Any?): String? {
        val normalizedLocale = normalizeLocale(locale)
        if (!hasKey(key, normalizedLocale)) return null
        return message(key, normalizedLocale, *args)
    }

    fun focusName(focus: WorkoutFocus, locale: String = "en"): String {
        return message("focus.${WorkoutTargetResolver.key(focus)}", locale)
    }

    fun muscleGroupName(muscleGroup: MuscleGroup, locale: String = "en"): String {
        return message("muscle.${muscleGroup.name.lowercase()}", locale)
    }

    fun targetDisplayName(rawTarget: String?, locale: String = "en"): String {
        val normalized = rawTarget
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.takeIf { it.isNotBlank() }
            ?: return ""

        maybeMessage("target.$normalized", locale)?.let { return it }

        WorkoutTargetResolver.resolveMuscleGroup(normalized)?.let {
            return muscleGroupName(it, locale)
        }

        WorkoutTargetResolver.resolveFocus(normalized)?.let {
            return focusName(it, locale)
        }

        return humanize(normalized)
    }

    fun durationLabel(minutes: Int, locale: String = "en"): String {
        return message("duration.minutes", locale, minutes)
    }

    fun difficultyKey(rawDifficulty: String?): String {
        val normalized = rawDifficulty?.trim()?.lowercase()
        return when {
            normalized == null -> "intermediate"
            normalized in WorkoutAliasCatalog.list("difficulty.alias.beginner").map { it.lowercase() } -> "beginner"
            normalized in WorkoutAliasCatalog.list("difficulty.alias.advanced").map { it.lowercase() } -> "advanced"
            else -> "intermediate"
        }
    }

    fun difficultyDisplayName(rawDifficulty: String?, locale: String = "en"): String {
        return message("difficulty.${difficultyKey(rawDifficulty)}", locale)
    }

    @Deprecated("Use targetDisplayName instead")
    fun targetName(rawTarget: String, locale: String = "en"): String? {
        val normalized = rawTarget
            .trim()
            .lowercase()
            .replace("-", "_")
            .replace(" ", "_")
            .takeIf { it.isNotBlank() }
            ?: return null

        return maybeMessage("target.$normalized", locale)
    }

    fun equipmentName(rawEquipment: String?, locale: String = "en"): String {
        val normalized = rawEquipment
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.takeIf { it.isNotBlank() }
            ?: return ""

        return maybeMessage("equipment.$normalized", locale) ?: humanize(normalized)
    }

    fun difficultyName(key: String, locale: String = "en"): String {
        return message("difficulty.$key", locale)
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

    fun splitKey(rawSplit: String?): String {
        return when (
            rawSplit
                ?.trim()
                ?.lowercase()
                ?.replace("-", "_")
                ?.replace("/", "_")
                ?.replace(" ", "_")
        ) {
            "push_pull_legs", "ppl" -> "ppl"
            "push_pull" -> "push_pull"
            "upper_lower" -> "upper_lower"
            "full_body", "full", "fullbody" -> "full_body"
            "bro_split", "5_split", "5split" -> "bro_split"
            "pplul" -> "pplul"
            "ppl_x2", "pplx2" -> "ppl_x2"
            else -> rawSplit
                ?.trim()
                ?.lowercase()
                ?.replace("-", "_")
                ?.replace("/", "_")
                ?.replace(" ", "_")
                ?: "ppl"
        }
    }

    fun splitName(rawSplit: String?, locale: String = "en"): String {
        val key = splitKey(rawSplit)
        return maybeMessage("split.$key", locale) ?: humanize(key)
    }

    fun workoutTypeName(type: WorkoutType, locale: String = "en"): String {
        return focusName(WorkoutTargetResolver.primaryFocusForWorkoutType(type), locale)
    }

    private fun lookup(key: String, locale: String): String {
        val bundle = bundle(locale)
        if (bundle.containsKey(key)) {
            return bundle.getString(key)
        }

        val englishBundle = bundle("en")
        if (englishBundle.containsKey(key)) {
            return englishBundle.getString(key)
        }

        return key
    }

    private fun hasKey(key: String, locale: String): Boolean {
        return bundle(locale).containsKey(key) || bundle("en").containsKey(key)
    }

    private fun bundle(locale: String): ResourceBundle {
        return ResourceBundle.getBundle(BASENAME, toLocale(locale))
    }

    private fun humanize(normalized: String): String {
        return normalized
            .split("_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString()
                }
            }
    }
}

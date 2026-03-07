package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.AchievementType

data class AchievementDefinition(
    val code: String,
    val type: AchievementType,
    val icon: String
)

object WorkoutAchievementCatalog {
    private val definitions = listOf(
        AchievementDefinition("first_workout", AchievementType.MILESTONE, "\uD83C\uDF1F"),
        AchievementDefinition("workout_10", AchievementType.WORKOUT_COUNT, "\uD83D\uDCAA"),
        AchievementDefinition("workout_50", AchievementType.WORKOUT_COUNT, "\uD83C\uDFC5"),
        AchievementDefinition("workout_100", AchievementType.WORKOUT_COUNT, "\uD83C\uDFC6"),
        AchievementDefinition("workout_200", AchievementType.WORKOUT_COUNT, "\uD83D\uDC51"),
        AchievementDefinition("week_streak_7", AchievementType.STREAK, "\uD83D\uDD25"),
        AchievementDefinition("week_streak_14", AchievementType.STREAK, "\uD83C\uDFAF"),
        AchievementDefinition("month_streak_30", AchievementType.STREAK, "\uD83C\uDFC6"),
        AchievementDefinition("month_streak_60", AchievementType.STREAK, "\u2B50"),
        AchievementDefinition("volume_10000", AchievementType.VOLUME, "\uD83D\uDCAA"),
        AchievementDefinition("volume_20000", AchievementType.VOLUME, "\uD83E\uDDBE"),
        AchievementDefinition("duration_60", AchievementType.CONSISTENCY, "\u23F1\uFE0F"),
        AchievementDefinition("duration_90", AchievementType.CONSISTENCY, "\u26A1")
    ).associateBy { it.code }

    fun definition(code: String): AchievementDefinition? = definitions[code]

    fun name(code: String, locale: String): String {
        return WorkoutLocalization.message("achievement.$code.name", locale)
    }

    fun description(code: String, locale: String): String {
        return WorkoutLocalization.message("achievement.$code.description", locale)
    }

    fun contains(code: String?): Boolean {
        return !code.isNullOrBlank() && definitions.containsKey(code)
    }
}

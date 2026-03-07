package com.richjun.liftupai.domain.workout.util

import java.util.Locale
import java.util.ResourceBundle

object WorkoutAliasCatalog {
    private const val BASENAME = "i18n.workout_aliases"
    private val bundle: ResourceBundle = ResourceBundle.getBundle(BASENAME, Locale.ROOT)

    fun list(key: String): List<String> {
        if (!bundle.containsKey(key)) return emptyList()
        return bundle.getString(key)
            .split(",")
            .map { token -> token.trim() }
            .filter { token -> token.isNotBlank() }
            .distinct()
    }

    fun mapping(key: String): Map<String, String> {
        return list(key)
            .mapNotNull { entry ->
                val parts = entry.split("=>", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val source = parts[0].trim()
                val target = parts[1].trim()
                if (source.isBlank() || target.isBlank()) return@mapNotNull null
                source to target
            }
            .toMap()
    }
}

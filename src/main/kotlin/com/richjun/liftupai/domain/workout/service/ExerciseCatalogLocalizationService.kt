package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import org.springframework.stereotype.Service

@Service
class ExerciseCatalogLocalizationService(
    private val exerciseTranslationRepository: ExerciseTranslationRepository
) {

    fun normalizeLocale(rawLocale: String?): String {
        val locale = rawLocale
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')

        return if (locale.isNullOrBlank()) "en" else locale
    }

    fun translationMap(exercises: Collection<Exercise>, locale: String): Map<Long, ExerciseTranslation> {
        if (exercises.isEmpty()) return emptyMap()

        val exerciseIds = exercises.map { it.id }.toSet()
        val normalizedLocale = normalizeLocale(locale)
        val localesToTry = buildList {
            add(normalizedLocale)
            val baseLanguage = normalizedLocale.substringBefore('-')
            if (baseLanguage != normalizedLocale) {
                add(baseLanguage)
            }
            if (!contains("en")) {
                add("en")
            }
        }

        val resolvedTranslations = linkedMapOf<Long, ExerciseTranslation>()
        var missingIds = exerciseIds

        localesToTry.forEach { currentLocale ->
            if (missingIds.isEmpty()) return@forEach

            val translations = exerciseTranslationRepository.findByExerciseIdInAndLocale(missingIds, currentLocale)
            translations.forEach { translation ->
                resolvedTranslations.putIfAbsent(translation.exercise.id, translation)
            }
            missingIds -= translations.map { it.exercise.id }.toSet()
        }

        return resolvedTranslations
    }

    fun displayName(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation> = emptyMap()
    ): String {
        return translations[exercise.id]?.displayName ?: exercise.name
    }

    fun instructions(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation> = emptyMap()
    ): String? {
        return translations[exercise.id]?.instructions ?: exercise.instructions
    }

    fun tips(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation> = emptyMap()
    ): String? {
        return translations[exercise.id]?.tips
    }
}

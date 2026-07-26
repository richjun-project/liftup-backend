package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Component

/**
 * WorkoutServiceV2 분리 이후 여러 서비스(WorkoutSessionService, WorkoutStatsService,
 * ExerciseCatalogDetailService, WorkoutRecommendationServiceV2)에서 공통으로 사용하는
 * 로케일 해석 / 운동 번역 / 세션 조회 헬퍼.
 *
 * 원래 WorkoutServiceV2의 private 메서드였던 로직을 그대로 옮긴 것으로 동작 변경이 없다.
 */
@Component
class WorkoutV2SharedSupport(
    private val userSettingsRepository: UserSettingsRepository,
    private val exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService,
    private val workoutSessionRepository: WorkoutSessionRepository
) {

    fun resolveLocale(userId: Long?, localeOverride: String? = null): String {
        if (!localeOverride.isNullOrBlank()) {
            return exerciseCatalogLocalizationService.normalizeLocale(localeOverride)
        }

        val userLocale = userId
            ?.let { userSettingsRepository.findByUser_Id(it).orElse(null)?.language }

        return exerciseCatalogLocalizationService.normalizeLocale(userLocale)
    }

    fun resolveTimeZone(userId: Long): java.time.ZoneId {
        val timeZone = userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone
        return AppTime.resolveZoneId(timeZone)
    }

    fun translationMap(exercises: Collection<Exercise>, locale: String): Map<Long, ExerciseTranslation> {
        return exerciseCatalogLocalizationService.translationMap(exercises, locale)
    }

    fun localizedName(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String {
        return exerciseCatalogLocalizationService.displayName(exercise, locale, translations)
    }

    fun localizedInstructions(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String? {
        return exerciseCatalogLocalizationService.instructions(exercise, locale, translations)
    }

    fun findUserSession(userId: Long, sessionId: Long): WorkoutSession {
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("WORKOUT002: Workout session not found") }

        if (session.user.id != userId) {
            throw org.springframework.security.access.AccessDeniedException("Access denied")
        }

        return session
    }
}

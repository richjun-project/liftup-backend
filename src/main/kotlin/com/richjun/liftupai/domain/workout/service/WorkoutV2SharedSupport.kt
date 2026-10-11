package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
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
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository? = null
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

    /**
     * 운동 번역을 로케일 폴백(요청 → 기본 언어 → en)까지 한 번에 읽는다 — 쿼리 1개.
     * (이전에는 로케일마다 쿼리를 따로 날려 목록 한 페이지에 최대 3개를 썼다)
     */
    fun translationMap(exercises: Collection<Exercise>, locale: String): Map<Long, ExerciseTranslation> {
        val repo = exerciseTranslationRepository
            ?: return exerciseCatalogLocalizationService.translationMap(exercises, locale)
        if (exercises.isEmpty()) return emptyMap()

        val normalized = exerciseCatalogLocalizationService.normalizeLocale(locale)
        val order = buildList {
            add(normalized)
            val base = normalized.substringBefore('-')
            if (base != normalized) add(base)
            if (!contains("en")) add("en")
        }
        val rows = repo.findByExerciseIdInAndLocaleIn(exercises.map { it.id }.toSet(), order)
        val rank = order.withIndex().associate { it.value to it.index }
        val resolved = HashMap<Long, ExerciseTranslation>(rows.size * 2)
        rows.sortedBy { rank[it.locale.lowercase()] ?: Int.MAX_VALUE }.forEach { resolved.putIfAbsent(it.exercise.id, it) }
        return resolved
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

    /** [findUserSession] 과 같지만 세션 행을 잠근다(업데이트·완료가 겹치지 않게). 트랜잭션의 첫 읽기로 부른다. */
    fun findUserSessionForUpdate(userId: Long, sessionId: Long): WorkoutSession {
        val session = workoutSessionRepository.findByIdForUpdate(sessionId)
            .orElseThrow { ResourceNotFoundException("WORKOUT002: Workout session not found") }
        if (session.user.id != userId) {
            throw org.springframework.security.access.AccessDeniedException("Access denied")
        }
        return session
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

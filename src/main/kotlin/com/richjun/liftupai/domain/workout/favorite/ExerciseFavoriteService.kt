package com.richjun.liftupai.domain.workout.favorite

import com.richjun.liftupai.domain.workout.dto.ExerciseDetailV2
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogCache
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogDetailService
import com.richjun.liftupai.domain.workout.service.WorkoutV2SharedSupport
import com.richjun.liftupai.global.exception.BadRequestException
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 운동 즐겨찾기와 최근 운동. 추가·해제는 멱등이다(이미 있는 것을 또 추가해도, 없는 것을 또 지워도 같은 결과).
 */
@Service
class ExerciseFavoriteService(
    private val favoriteRepository: ExerciseFavoriteRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseRepository: ExerciseRepository,
    private val catalogCache: ExerciseCatalogCache,
    private val detailService: ExerciseCatalogDetailService,
    private val sharedSupport: WorkoutV2SharedSupport
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /** 즐겨찾기한 운동 — 최근에 추가한 순. 각 항목의 is_favorite 는 true */
    @Transactional(readOnly = true)
    fun favorites(userId: Long, localeOverride: String?): List<ExerciseDetailV2> {
        val locale = sharedSupport.resolveLocale(userId, localeOverride)
        val ids = favoriteRepository.findExerciseIdsByUserId(userId).take(MAX_FAVORITES)
        val exercises = load(ids)
        return detailService.toExerciseDetails(exercises, locale, favoriteIds = ids.toSet())
    }

    /** 최근에 실제로 한 운동 — 중복 없이 최신순, 최대 limit 개 */
    @Transactional(readOnly = true)
    fun recent(userId: Long, limit: Int, localeOverride: String?): List<ExerciseDetailV2> {
        val locale = sharedSupport.resolveLocale(userId, localeOverride)
        val ids = workoutExerciseRepository.findRecentExerciseIds(userId, PageRequest.of(0, limit.coerceIn(1, MAX_RECENT)))
        val exercises = load(ids)
        val favoriteIds = if (exercises.isEmpty()) emptySet() else favoriteRepository.findFavoriteIdsIn(userId, exercises.map { it.id }.toSet()).toSet()
        return detailService.toExerciseDetails(exercises, locale, favoriteIds = favoriteIds)
    }

    /** 즐겨찾기 추가 (이미 있으면 그대로 true). 없는 운동은 404, 상한을 넘으면 400 */
    fun add(userId: Long, exerciseId: Long): Boolean {
        if (!exists(exerciseId)) throw ResourceNotFoundException("Exercise not found")
        if (favoriteRepository.existsByUserIdAndExerciseId(userId, exerciseId)) return true
        if (favoriteRepository.countByUserId(userId) >= MAX_FAVORITES) {
            throw BadRequestException("Too many favorites (max $MAX_FAVORITES)")
        }
        try {
            favoriteRepository.save(ExerciseFavorite(userId = userId, exerciseId = exerciseId))
        } catch (e: DataIntegrityViolationException) {
            // 동시에 같은 요청이 먼저 들어간 경우 — 결과는 같다. 그런데 행이 없다면 중복이 아니라 FK 위반이다
            // (카탈로그 캐시는 마이그레이션이 지운 운동을 최대 10분간 들고 있다) — 저장되지 않은 별을 성공으로 알리지 않는다.
            if (!favoriteRepository.existsByUserIdAndExerciseId(userId, exerciseId)) {
                logger.warn("Favorite insert failed and no row exists: user={}, exercise={} — {}", userId, exerciseId, e.message)
                throw ResourceNotFoundException("Exercise not found")
            }
            logger.debug("Favorite already inserted concurrently: user={}, exercise={}", userId, exerciseId)
        }
        return true
    }

    /** 즐겨찾기 해제 (없어도 false 로 성공) */
    fun remove(userId: Long, exerciseId: Long): Boolean {
        favoriteRepository.deleteByUserIdAndExerciseId(userId, exerciseId)
        return false
    }

    private fun exists(exerciseId: Long): Boolean =
        catalogCache.get().byId.containsKey(exerciseId) || exerciseRepository.existsById(exerciseId)

    private fun load(ids: List<Long>): List<Exercise> {
        if (ids.isEmpty()) return emptyList()
        val cached = catalogCache.get().byId
        val missing = ids.filter { it !in cached }
        val extra = if (missing.isEmpty()) emptyMap() else exerciseRepository.findAllById(missing).associateBy { it.id }
        return ids.mapNotNull { cached[it] ?: extra[it] }
    }

    companion object {
        const val MAX_FAVORITES = 500
        const val DEFAULT_RECENT = 10
        const val MAX_RECENT = 30
    }
}

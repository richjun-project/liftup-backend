package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Recommendation-only service for filtering, recovery checks, and ordering.
 */
@Service
class ExerciseRecommendationService(
    private val exerciseRepository: ExerciseRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // 설정 상수
        private const val MIN_EXERCISES_THRESHOLD = 3  // 회복 필터 후 최소 운동 개수 (6→3: 회복 우선)
        private const val RECOVERY_THRESHOLD_PERCENT = 50
        private const val RECENT_WORKOUT_HOURS = 24
    }

    /**
     * 메인 추천 메서드 - 운동 목록 반환
     */
    fun getRecommendedExercises(
        user: User,
        targetMuscle: String? = null,
        equipment: String? = null,
        duration: Int = 30,
        limit: Int = 10
    ): List<Exercise> {
        logger.info("Recommendation request started - user: ${user.id}, target: $targetMuscle, equipment: $equipment, duration: $duration")

        val allExercises = exerciseRepository.findAll()
        val coreRecommendations = buildRecommendationCandidates(
            exercises = allExercises,
            user = user,
            targetMuscle = targetMuscle,
            equipment = equipment,
            coreOnly = true
        )

        val fallbackRecommendations = if (coreRecommendations.size >= limit) {
            emptyList()
        } else {
            buildRecommendationCandidates(
                exercises = allExercises,
                user = user,
                targetMuscle = targetMuscle,
                equipment = equipment,
                coreOnly = false
            ).filterNot { candidate -> coreRecommendations.any { it.id == candidate.id } }
        }

        return (coreRecommendations + fallbackRecommendations)
            .distinctBy { it.id }
            .take(limit)
            .also {
                logger.info("Core candidates: ${coreRecommendations.size}, final recommendations: ${it.size}")
            }
    }

    private fun buildRecommendationCandidates(
        exercises: List<Exercise>,
        user: User,
        targetMuscle: String?,
        equipment: String?,
        coreOnly: Boolean
    ): List<Exercise> {
        val targetCategory = resolveTargetCategory(targetMuscle)
        return exercises
            .let { filterByRecommendationPool(it, coreOnly) }
            .let { filterByRecoveryStatus(it, user) }
            .let { filterByEquipment(it, equipment) }
            .let { filterByTargetMuscle(it, targetMuscle) }
            .let { removeDuplicatePatterns(it) }
            .let { orderByPriority(it, targetCategory) }
    }

    /**
     * 타겟 근육을 주 카테고리로 변환
     */
    private fun resolveTargetCategory(targetMuscle: String?): ExerciseCategory? {
        if (targetMuscle == null) return null
        val key = WorkoutTargetResolver.recommendationKey(targetMuscle) ?: return null
        return when (key.lowercase()) {
            "chest" -> ExerciseCategory.CHEST
            "back" -> ExerciseCategory.BACK
            "legs", "lower" -> ExerciseCategory.LEGS
            "shoulders" -> ExerciseCategory.SHOULDERS
            "arms" -> ExerciseCategory.ARMS
            "core" -> ExerciseCategory.CORE
            else -> null
        }
    }

    // === 필터링 메서드들 ===

    /**
     * 추천 후보군 필터링
     * - 기본값: ESSENTIAL + isBasicExercise
     * - 부족할 때만 STANDARD로 확장
     */
    private fun filterByRecommendationPool(exercises: List<Exercise>, coreOnly: Boolean): List<Exercise> {
        val filtered = exercises.filter { exercise ->
            if (coreOnly) {
                RecommendationExerciseRanking.isCoreCandidate(exercise)
            } else {
                RecommendationExerciseRanking.isGeneralCandidate(exercise)
            }
        }

        return filtered.also {
            logger.debug(
                if (coreOnly) "Core pool filtered: ${it.size}"
                else "General pool filtered: ${it.size}"
            )
        }
    }

    /**
     * 회복 상태 기반 필터링
     * - 24시간 이내 운동한 근육 제외
     * - 회복률 30% 미만인 근육 제외
     * - 최소 운동 개수 보장 (6개 미만이면 필터 스킵)
     */
    private fun filterByRecoveryStatus(exercises: List<Exercise>, user: User): List<Exercise> {
        val avoidMuscles = getMusclesToAvoid(user)

        if (avoidMuscles.isEmpty()) {
            logger.debug("Recovery filter skipped: no muscles to avoid")
            return exercises
        }

        val filtered = exercises.filter { exercise ->
            exercise.muscleGroups.none { it in avoidMuscles }
        }

        // 최소 운동 개수 보장
        return if (filtered.size >= MIN_EXERCISES_THRESHOLD) {
            logger.debug("Recovery filter result: ${filtered.size} (avoided: ${avoidMuscles.joinToString()})")
            filtered
        } else {
            logger.warn("Recovery filter skipped: insufficient exercises (${filtered.size} < $MIN_EXERCISES_THRESHOLD)")
            exercises
        }
    }

    /**
     * 회피해야 할 근육군 조회
     */
    private fun getMusclesToAvoid(user: User): Set<MuscleGroup> {
        val recentlyWorked = getRecentlyWorkedMuscles(user, RECENT_WORKOUT_HOURS)
        val recovering = getRecoveringMuscles(user)
        return recentlyWorked + recovering
    }

    /**
     * 최근 N시간 내 운동한 근육군
     */
    private fun getRecentlyWorkedMuscles(user: User, hours: Int): Set<MuscleGroup> {
        return try {
            val cutoff = AppTime.utcNow().minusHours(hours.toLong())
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
            if (sessions.isEmpty()) return emptySet()

            val sessionIds = sessions.map { it.id }
            workoutExerciseRepository.findBySessionIdInWithExercise(sessionIds)
                .flatMap { it.exercise.muscleGroups }
                .toSet()
        } catch (e: Exception) {
            logger.warn("Failed to load recently trained muscles: ${e.message}")
            emptySet()
        }
    }

    /**
     * 회복 중인 근육군 (회복률 30% 미만)
     */
    private fun getRecoveringMuscles(user: User): Set<MuscleGroup> {
        return try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < RECOVERY_THRESHOLD_PERCENT }
                .flatMap { recovery -> WorkoutTargetResolver.muscleGroupsFor(recovery.muscleGroup) }
                .toSet()
        } catch (e: Exception) {
            logger.warn("Failed to load recovery status: ${e.message}")
            emptySet()
        }
    }

    /**
     * 장비 필터링
     */
    private fun filterByEquipment(exercises: List<Exercise>, equipment: String?): List<Exercise> {
        if (equipment == null) return exercises

        val equipmentEnum = try {
            Equipment.valueOf(equipment.uppercase().replace(" ", "_"))
        } catch (e: IllegalArgumentException) {
            logger.warn("Unknown equipment value: $equipment")
            return exercises
        }

        return exercises.filter { it.equipment == equipmentEnum }
            .also { logger.debug("Equipment filter result: ${it.size} ($equipment)") }
    }

    /**
     * 타겟 근육 필터링
     */
    private fun filterByTargetMuscle(exercises: List<Exercise>, targetMuscle: String?): List<Exercise> {
        if (targetMuscle == null) return exercises

        val normalizedTarget = WorkoutTargetResolver.recommendationKey(targetMuscle) ?: return exercises
        val targetMuscleGroups = getMuscleGroupsForTarget(normalizedTarget)
        if (targetMuscleGroups.isEmpty()) return exercises

        return exercises.filter { exercise ->
            exercise.muscleGroups.any { it in targetMuscleGroups }
        }.also { logger.debug("Target filter result: ${it.size} ($normalizedTarget)") }
    }

    /**
     * 타겟 이름을 근육군 집합으로 변환
     */
    private fun getMuscleGroupsForTarget(target: String): Set<MuscleGroup> {
        return when (target.lowercase()) {
            "full_body" -> emptySet()
            "legs", "lower" -> setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            "upper" -> setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS)
            "chest" -> setOf(MuscleGroup.CHEST)
            "back" -> setOf(MuscleGroup.BACK, MuscleGroup.LATS)
            "shoulders" -> setOf(MuscleGroup.SHOULDERS)
            "arms" -> setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS)
            "core" -> setOf(MuscleGroup.ABS, MuscleGroup.CORE)
            else -> emptySet()
        }
    }

    /**
     * 패턴 중복 제거
     * 같은 패턴에서 핵심도와 활용도가 높은 운동 1개만 선택
     */
    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (pattern, group) ->
                group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())?.also {
                    if (group.size > 1) {
                        logger.debug("[$pattern] selected ${it.name}, excluded: ${group.filter { e -> e != it }.map { e -> e.name }}")
                    }
                }
            }
            .also { logger.debug("After pattern deduplication: ${it.size}") }
    }

    /**
     * 카테고리 균형 정렬 (라운드 로빈)
     *
     * 단순 카테고리 우선순위 정렬 대신, 각 카테고리에서 최고 운동을 하나씩
     * 돌아가며 배치합니다. 이렇게 하면 전신 추천 시 다리/등/가슴/어깨/팔/코어가
     * 골고루 포함됩니다.
     *
     * 카테고리 순서: LEGS → BACK → CHEST → SHOULDERS → ARMS → CORE → CARDIO
     * 각 카테고리 내부: 복합운동 > 고립운동, ESSENTIAL > STANDARD, 인기도 순
     */
    /**
     * 카테고리 균형 정렬 (라운드 로빈 + 타겟 우선)
     *
     * 타겟이 지정되면 해당 카테고리를 최우선으로 배치하고,
     * 타겟이 없으면(전신) 기본 순서로 라운드 로빈합니다.
     *
     * 전신: LEGS → BACK → CHEST → SHOULDERS → ARMS → CORE → CARDIO
     * 가슴데이: CHEST → SHOULDERS → ARMS → LEGS → BACK → CORE
     * 등데이: BACK → ARMS → LEGS → CHEST → SHOULDERS → CORE
     */
    private fun orderByPriority(exercises: List<Exercise>, targetCategory: ExerciseCategory? = null): List<Exercise> {
        val byCategory = exercises
            .groupBy { it.category }
            .mapValues { (_, group) ->
                group.sortedWith(RecommendationExerciseRanking.displayOrderComparator())
                    .toMutableList()
            }

        // 타겟 카테고리가 있으면 그것을 맨 앞으로
        val defaultOrder = listOf(
            ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
            ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY
        )
        val categoryOrder = if (targetCategory != null) {
            listOf(targetCategory) + defaultOrder.filter { it != targetCategory }
        } else {
            defaultOrder
        }

        val result = mutableListOf<Exercise>()
        var hasMore = true
        while (hasMore) {
            hasMore = false
            for (category in categoryOrder) {
                val queue = byCategory[category]
                if (queue != null && queue.isNotEmpty()) {
                    result.add(queue.removeFirst())
                    hasMore = true
                }
            }
        }

        return result
    }
}

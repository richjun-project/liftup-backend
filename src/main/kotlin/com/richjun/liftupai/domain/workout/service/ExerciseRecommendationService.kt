package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 운동 추천 서비스
 *
 * 단일 책임 원칙(SRP)에 따라 운동 추천 로직만 담당
 * - 운동 필터링
 * - 패턴 중복 제거
 * - 회복 상태 기반 필터링
 * - 운동 순서 정렬
 */
@Service
class ExerciseRecommendationService(
    private val exerciseRepository: ExerciseRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val userProfileRepository: UserProfileRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // 설정 상수
        private const val MIN_EXERCISES_THRESHOLD = 6
        private const val RECOVERY_THRESHOLD_PERCENT = 30
        private const val RECENT_WORKOUT_HOURS = 24
        private const val FAMILIAR_EXERCISE_WEEKS = 4
        private const val FAMILIAR_EXERCISE_MIN_COUNT = 2
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
        logger.info("운동 추천 시작 - user: ${user.id}, target: $targetMuscle, equipment: $equipment, duration: $duration")

        return exerciseRepository.findAll()
            .let { filterByRecommendationTier(it) }
            .let { filterByRecoveryStatus(it, user) }
            .let { filterByEquipment(it, equipment) }
            .let { filterByTargetMuscle(it, targetMuscle) }
            .let { removeDuplicatePatterns(it) }
            .let { ensureExerciseVariety(it, user) }
            .let { orderByPriority(it) }
            .take(limit)
            .also { logger.info("최종 추천 운동 수: ${it.size}") }
    }

    // === 필터링 메서드들 ===

    /**
     * 추천 등급 필터링 (ESSENTIAL + STANDARD만)
     */
    private fun filterByRecommendationTier(exercises: List<Exercise>): List<Exercise> {
        val allowedTiers = setOf(RecommendationTier.ESSENTIAL, RecommendationTier.STANDARD)
        return exercises.filter { it.recommendationTier in allowedTiers }
            .also { logger.debug("추천 등급 필터 후: ${it.size}개") }
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
            logger.debug("회복 필터: 제외할 근육 없음")
            return exercises
        }

        val filtered = exercises.filter { exercise ->
            val primaryMuscle = exercise.muscleGroups.firstOrNull()
            primaryMuscle == null || primaryMuscle !in avoidMuscles
        }

        // 최소 운동 개수 보장
        return if (filtered.size >= MIN_EXERCISES_THRESHOLD) {
            logger.debug("회복 필터 후: ${filtered.size}개 (제외 근육: ${avoidMuscles.joinToString()})")
            filtered
        } else {
            logger.warn("회복 필터 스킵: 운동 부족 (${filtered.size}개 < $MIN_EXERCISES_THRESHOLD)")
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
            val cutoff = LocalDateTime.now().minusHours(hours.toLong())
            workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
                .flatMap { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                        .flatMap { it.exercise.muscleGroups }
                }
                .toSet()
        } catch (e: Exception) {
            logger.warn("최근 운동 근육 조회 실패: ${e.message}")
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
                .mapNotNull { recovery ->
                    try {
                        MuscleGroup.valueOf(recovery.muscleGroup.uppercase())
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                .toSet()
        } catch (e: Exception) {
            logger.warn("회복 상태 조회 실패: ${e.message}")
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
            logger.warn("알 수 없는 장비: $equipment")
            return exercises
        }

        return exercises.filter { it.equipment == equipmentEnum }
            .also { logger.debug("장비 필터 후: ${it.size}개 ($equipment)") }
    }

    /**
     * 타겟 근육 필터링
     */
    private fun filterByTargetMuscle(exercises: List<Exercise>, targetMuscle: String?): List<Exercise> {
        if (targetMuscle == null) return exercises

        val targetMuscleGroups = getMuscleGroupsForTarget(targetMuscle)
        if (targetMuscleGroups.isEmpty()) return exercises

        return exercises.filter { exercise ->
            exercise.muscleGroups.any { it in targetMuscleGroups }
        }.also { logger.debug("근육 필터 후: ${it.size}개 ($targetMuscle)") }
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
     * 같은 패턴에서 가장 쉽고 인기 있는 운동 1개만 선택
     */
    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (pattern, group) ->
                group.minWithOrNull(
                    compareBy<Exercise> { it.difficulty }
                        .thenByDescending { it.popularity }
                        .thenByDescending { it.isBasicExercise }
                )?.also {
                    if (group.size > 1) {
                        logger.debug("[$pattern] ${it.name} 선택, 제외: ${group.filter { e -> e != it }.map { e -> e.name }}")
                    }
                }
            }
            .also { logger.debug("패턴 중복 제거 후: ${it.size}개") }
    }

    /**
     * 운동 다양성 보장 (익숙한 운동 + 새로운 운동 비율 조정)
     */
    private fun ensureExerciseVariety(exercises: List<Exercise>, user: User): List<Exercise> {
        val profile = userProfileRepository.findByUser(user).orElse(null)
        val (familiarRatio, newRatio) = getVarietyRatio(profile?.experienceLevel)

        val recentExerciseIds = getRecentExerciseIds(user)

        val familiar = exercises.filter { recentExerciseIds.getOrDefault(it.id, 0) >= FAMILIAR_EXERCISE_MIN_COUNT }
            .sortedByDescending { recentExerciseIds[it.id] }

        val newExercises = exercises.filter { it.id !in recentExerciseIds.keys }
            .sortedWith(
                compareByDescending<Exercise> { it.isBasicExercise }
                    .thenByDescending { it.popularity }
                    .thenBy { it.difficulty }
            )

        return (familiar.take(familiarRatio) + newExercises.take(newRatio))
            .distinctBy { it.id }
            .ifEmpty { exercises }
    }

    /**
     * 경험 레벨에 따른 익숙/새로운 운동 비율
     */
    private fun getVarietyRatio(level: ExperienceLevel?): Pair<Int, Int> {
        return when (level) {
            ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE -> 8 to 2
            ExperienceLevel.INTERMEDIATE -> 6 to 4
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> 4 to 6
            else -> 6 to 4
        }
    }

    /**
     * 최근 4주간 운동한 운동 ID와 횟수
     */
    private fun getRecentExerciseIds(user: User): Map<Long, Int> {
        val cutoff = LocalDateTime.now().minusDays(FAMILIAR_EXERCISE_WEEKS * 7L)
        return try {
            workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
                .flatMap { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(it.id) }
                .map { it.exercise.id }
                .groupingBy { it }
                .eachCount()
        } catch (e: Exception) {
            logger.warn("최근 운동 조회 실패: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 운동 우선순위 정렬 (큰 근육 → 작은 근육, 복합 → 고립)
     */
    private fun orderByPriority(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(
            compareBy<Exercise> { getCategoryPriority(it.category) }
                .thenBy { if (isCompoundExercise(it)) 0 else 1 }
        )
    }

    private fun getCategoryPriority(category: ExerciseCategory): Int {
        return when (category) {
            ExerciseCategory.LEGS -> 1
            ExerciseCategory.BACK -> 2
            ExerciseCategory.CHEST -> 3
            ExerciseCategory.SHOULDERS -> 4
            ExerciseCategory.ARMS -> 5
            ExerciseCategory.CORE -> 6
            else -> 7
        }
    }

    private fun isCompoundExercise(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true
        val name = exercise.name.lowercase()
        val compoundKeywords = listOf("프레스", "스쿼트", "데드리프트", "로우", "풀업", "친업", "딥스", "런지")
        return compoundKeywords.any { name.contains(it) }
    }
}

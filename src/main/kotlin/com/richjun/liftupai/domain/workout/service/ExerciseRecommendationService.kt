package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Recommendation-only service for filtering, recovery checks, and ordering.
 */
@Service
class ExerciseRecommendationService(
    private val exerciseRepository: ExerciseRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val userProfileRepository: UserProfileRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /** In-memory TTL cache: userId -> (timestampMs, volumeMap) */
    private val volumeCache = ConcurrentHashMap<Long, Pair<Long, Map<MuscleGroup, Int>>>()

    companion object {
        private const val MIN_EXERCISES_THRESHOLD = RecommendationConstants.SAFETY_MIN_EXERCISES
        private const val RECOVERY_THRESHOLD_PERCENT = RecommendationConstants.RECOVERY_THRESHOLD_PERCENT
        private const val RECENT_WORKOUT_HOURS = RecommendationConstants.RECENT_WORKOUT_HOURS
        private const val VOLUME_CACHE_TTL_MS = 5 * 60 * 1000L // 5분
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

        // 사용자 프로필 조회 (경험 수준 + 목표)
        val profile = userProfileRepository.findByUser(user).orElse(null)
        val experienceLevel = profile?.experienceLevel ?: ExperienceLevel.BEGINNER
        val userGoals: Set<FitnessGoal> = profile?.goals ?: emptySet()
        logger.info("User experience level: $experienceLevel, goals: $userGoals")

        // 주간 볼륨 1회 계산 (캐시 적용, 모든 파이프라인에서 재사용)
        val weeklyVolume = getWeeklyMuscleVolumeCached(user)
        if (weeklyVolume.isNotEmpty()) {
            logger.info("Weekly volume: ${weeklyVolume.entries.joinToString { "${it.key}=${it.value}" }}")
        }

        // 목표별 복합운동 비율
        val compoundRatio = RecommendationConstants.getCompoundRatio(userGoals)
        logger.info("Target compound ratio: $compoundRatio (goals: $userGoals)")

        // 1차: 전체 필터 적용 (core → general 폴백)
        val result = buildWithFallback(allExercises, user, targetMuscle, equipment, limit, weeklyVolume, experienceLevel)

        // 2차: 결과 부족 시 장비 필터 제거
        if (result.size < limit && equipment != null) {
            logger.info("Fallback: removing equipment filter (had ${result.size}/$limit)")
            val withoutEquipment = buildWithFallback(allExercises, user, targetMuscle, null, limit, weeklyVolume, experienceLevel)
            if (withoutEquipment.size > result.size) {
                return enforceCompoundRatio(withoutEquipment, compoundRatio, limit).also {
                    logger.info("After equipment fallback: ${it.size} recommendations")
                }
            }
        }

        // 3차: 여전히 부족하면 회복 필터 없이 재시도
        if (result.size < limit) {
            logger.info("Fallback: rebuilding without recovery filter (had ${result.size}/$limit)")
            val targetCategory = resolveTargetCategory(targetMuscle)
            val relaxed = allExercises
                .let { filterByRecommendationPool(it, false) }
                .let { filterByEquipment(it, equipment) }
                .let { filterByTargetMuscle(it, targetMuscle) }
                .let { removeDuplicatePatterns(it) }
                .let { prioritizeByVolume(it, weeklyVolume, experienceLevel) }
                .let { orderByPriority(it, targetCategory) }
            if (relaxed.size > result.size) {
                return enforceCompoundRatio(relaxed, compoundRatio, limit).also {
                    logger.info("After recovery fallback: ${it.size} recommendations")
                }
            }
        }

        return enforceCompoundRatio(result, compoundRatio, limit).also {
            if (it.size < limit) {
                logger.warn("Recommendation shortfall: delivered ${it.size}/$limit exercises")
            }
        }
    }

    private fun buildWithFallback(
        allExercises: List<Exercise>,
        user: User,
        targetMuscle: String?,
        equipment: String?,
        limit: Int,
        weeklyVolume: Map<MuscleGroup, Int>,
        experienceLevel: ExperienceLevel
    ): List<Exercise> {
        val coreRecommendations = buildRecommendationCandidates(
            exercises = allExercises,
            user = user,
            targetMuscle = targetMuscle,
            equipment = equipment,
            coreOnly = true,
            weeklyVolume = weeklyVolume,
            experienceLevel = experienceLevel
        )

        val fallbackRecommendations = if (coreRecommendations.size >= limit) {
            emptyList()
        } else {
            buildRecommendationCandidates(
                exercises = allExercises,
                user = user,
                targetMuscle = targetMuscle,
                equipment = equipment,
                coreOnly = false,
                weeklyVolume = weeklyVolume,
                experienceLevel = experienceLevel
            ).filterNot { candidate -> coreRecommendations.any { it.id == candidate.id } }
        }

        return (coreRecommendations + fallbackRecommendations)
            .distinctBy { it.id }
            .also {
                logger.info("Core candidates: ${coreRecommendations.size}, total: ${it.size}")
            }
    }

    private fun buildRecommendationCandidates(
        exercises: List<Exercise>,
        user: User,
        targetMuscle: String?,
        equipment: String?,
        coreOnly: Boolean,
        weeklyVolume: Map<MuscleGroup, Int>,
        experienceLevel: ExperienceLevel
    ): List<Exercise> {
        val targetCategory = resolveTargetCategory(targetMuscle)
        return exercises
            .let { filterByRecommendationPool(it, coreOnly) }
            .let { filterByRecoveryStatus(it, user) }
            .let { filterByEquipment(it, equipment) }
            .let { filterByTargetMuscle(it, targetMuscle) }
            .let { removeDuplicatePatterns(it) }
            .let { prioritizeByVolume(it, weeklyVolume, experienceLevel) }
            .let { orderByPriority(it, targetCategory) }
    }

    /**
     * 타겟 근육을 주 카테고리로 변환
     */
    private fun resolveTargetCategory(targetMuscle: String?): ExerciseCategory? {
        if (targetMuscle == null) return null
        val key = WorkoutTargetResolver.recommendationKey(targetMuscle) ?: return null
        return when (key.lowercase()) {
            "push", "chest" -> ExerciseCategory.CHEST
            "pull", "back" -> ExerciseCategory.BACK
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
     * - 회복률 50% 미만인 근육 제외
     * - 최소 운동 개수 보장 (3개 미만이면 필터 스킵)
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
     * 근육군별 회복 시간 기반으로 아직 회복되지 않은 근육군 반환.
     * 가장 긴 회복 시간(대근육 60h) 기준으로 세션을 조회한 뒤,
     * 각 근육군별로 필요한 회복 시간이 경과했는지 개별 판단한다.
     */
    private fun getRecentlyWorkedMuscles(user: User, defaultHours: Int): Set<MuscleGroup> {
        return try {
            // 가장 긴 회복 시간 기준으로 세션 조회 (72시간)
            val maxCutoff = AppTime.utcNow().minusHours(72)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, maxCutoff)
            if (sessions.isEmpty()) return emptySet()

            val sessionIds = sessions.map { it.id }
            val exerciseData = workoutExerciseRepository.findBySessionIdInWithExercise(sessionIds)

            // 각 세션의 시작 시간과 근육군을 매핑
            val sessionTimeMap = sessions.associateBy { it.id }

            exerciseData.flatMap { we ->
                val sessionTime = sessionTimeMap[we.session.id]?.startTime ?: return@flatMap emptyList()
                val hoursSince = ChronoUnit.HOURS.between(sessionTime, AppTime.utcNow())

                we.exercise.muscleGroups.filter { muscle ->
                    val requiredHours = RecommendationConstants.MUSCLE_RECOVERY_HOURS[muscle] ?: defaultHours
                    hoursSince < requiredHours
                }
            }.toSet()
        } catch (e: Exception) {
            logger.warn("Failed to load recently trained muscles: ${e.message}")
            emptySet()
        }
    }

    /**
     * 회복 중인 근육군 (회복률 50% 미만)
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

    private fun getMuscleGroupsForTarget(target: String): Set<MuscleGroup> {
        return WorkoutTargetResolver.muscleGroupsForKey(target)
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

    // === 주간 볼륨 추적 메서드들 ===

    /**
     * 캐시된 주간 볼륨 조회 (5분 TTL)
     * 같은 사용자가 짧은 시간 내 반복 요청 시 DB 부하 방지
     */
    private fun getWeeklyMuscleVolumeCached(user: User): Map<MuscleGroup, Int> {
        val now = System.currentTimeMillis()
        val cached = volumeCache[user.id]
        if (cached != null && now - cached.first < VOLUME_CACHE_TTL_MS) {
            logger.debug("Weekly volume cache hit for user ${user.id}")
            return cached.second
        }
        val volume = getWeeklyMuscleVolume(user)
        volumeCache[user.id] = now to volume
        logger.debug("Weekly volume cache miss for user ${user.id}, computed and cached")
        return volume
    }

    /**
     * 최근 7일간 근육군별 총 완료 세트 수 계산
     * 추천 시작 시 1회만 호출하여 모든 파이프라인에서 재사용
     */
    private fun getWeeklyMuscleVolume(user: User): Map<MuscleGroup, Int> {
        return try {
            val weekStart = AppTime.utcNow().minusDays(7)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, weekStart)
                .filter { it.status == SessionStatus.COMPLETED }

            if (sessions.isEmpty()) return emptyMap()

            val sessionIds = sessions.map { it.id }
            val exercises = workoutExerciseRepository.findBySessionIdInWithExercise(sessionIds)

            if (exercises.isEmpty()) return emptyMap()

            // 배치로 모든 세트 로드 (N+1 방지)
            val exerciseIds = exercises.map { it.id }
            val allSets = exerciseSetRepository.findByWorkoutExerciseIdIn(exerciseIds)
            val completedSetsByExercise = allSets
                .filter { it.completed }
                .groupBy { it.workoutExercise.id }
                .mapValues { (_, sets) -> sets.size }

            val volumeMap = mutableMapOf<MuscleGroup, Int>()
            exercises.forEach { we ->
                val setCount = completedSetsByExercise[we.id] ?: 0
                if (setCount > 0) {
                    we.exercise.muscleGroups.forEach { muscle ->
                        volumeMap[muscle] = (volumeMap[muscle] ?: 0) + setCount
                    }
                }
            }

            // 시너지 간접 볼륨 가산
            val directVolume = volumeMap.toMap()
            val totalVolume = directVolume.toMutableMap()
            directVolume.forEach { (muscle, sets) ->
                RecommendationConstants.MUSCLE_SYNERGY[muscle]?.forEach { (synergyMuscle, ratio) ->
                    totalVolume[synergyMuscle] = (totalVolume[synergyMuscle] ?: 0) + (sets * ratio).toInt()
                }
            }
            totalVolume
        } catch (e: Exception) {
            logger.warn("Failed to calculate weekly muscle volume: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 주간 볼륨 기반 운동 정렬: 부족한 근육 우선, 과량 근육 후순위
     * 경험 수준별 동적 임계값 사용:
     * - 과량(> range.last): 후순위 (priority 2)
     * - 적정(range): 중간 (priority 1)
     * - 부족(< range.first): 최우선 (priority 0)
     *
     * 볼륨 데이터가 없으면 원래 순서 유지 (신규 사용자 등)
     */
    private fun prioritizeByVolume(
        exercises: List<Exercise>,
        weeklyVolume: Map<MuscleGroup, Int>,
        experienceLevel: ExperienceLevel
    ): List<Exercise> {
        if (weeklyVolume.isEmpty()) return exercises

        val volumeRange = RecommendationConstants.getWeeklyVolumeRange(experienceLevel)

        return exercises.sortedWith(compareBy { exercise ->
            val maxVolume = exercise.muscleGroups.maxOfOrNull { weeklyVolume[it] ?: 0 } ?: 0
            when {
                maxVolume > volumeRange.last -> 2    // 과량: 후순위
                maxVolume < volumeRange.first -> 0   // 부족: 최우선
                else -> 1                            // 적정: 중간
            }
        })
    }

    // === 복합/고립 운동 비율 적용 ===

    /** 복합운동 판별 키워드 — RecommendationExerciseRanking의 기준과 동일 */
    private val compoundKeywords = listOf(
        "press", "squat", "deadlift", "row", "pullup", "pull-up",
        "chinup", "chin-up", "dip", "lunge"
    )

    /**
     * 복합운동 여부 판별
     * - muscleGroups.size >= 2 → 다관절 운동
     * - 이름에 복합운동 키워드 포함 (press, squat, deadlift 등)
     */
    private fun isCompoundExercise(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true
        val name = exercise.name.lowercase()
        return compoundKeywords.any { keyword -> name.contains(keyword) }
    }

    /**
     * 목표별 복합/고립 운동 비율 적용
     *
     * 파이프라인 최종 단계에서 실행되며, 목표에 맞는 복합운동 비율을 강제합니다.
     * - 복합운동과 고립운동을 분류한 뒤 목표 비율에 맞게 조합
     * - 비율 적용 후에도 SAFETY_MIN_EXERCISES 이상 보장
     * - 후보가 부족하면 가능한 만큼만 채움
     *
     * @param exercises 비율 적용 전 정렬된 후보 리스트 (기존 정렬 순서 유지)
     * @param targetRatio 복합운동 목표 비율 (0.0-1.0)
     * @param totalCount 최종 반환할 운동 개수 (limit)
     */
    private fun enforceCompoundRatio(
        exercises: List<Exercise>,
        targetRatio: Double,
        totalCount: Int
    ): List<Exercise> {
        if (exercises.isEmpty()) return exercises

        val compounds = exercises.filter { isCompoundExercise(it) }
        val isolations = exercises.filterNot { isCompoundExercise(it) }

        val targetCompoundCount = (totalCount * targetRatio).toInt().coerceAtLeast(1)
        val targetIsolationCount = totalCount - targetCompoundCount

        val selectedCompounds = compounds.take(targetCompoundCount)
        val selectedIsolations = isolations.take(targetIsolationCount)
        var result = selectedCompounds + selectedIsolations

        // 후보가 부족한 경우: 반대 타입으로 보충
        if (result.size < totalCount) {
            val selectedIds = result.map { it.id }.toSet()
            val remaining = exercises.filter { it.id !in selectedIds }
            result = result + remaining.take(totalCount - result.size)
        }

        // SAFETY_MIN_EXERCISES 보장
        if (result.size < MIN_EXERCISES_THRESHOLD && exercises.size >= MIN_EXERCISES_THRESHOLD) {
            logger.warn("Compound ratio enforcement produced too few exercises (${result.size}), using original list")
            return exercises.take(totalCount)
        }

        val actualCompounds = result.count { isCompoundExercise(it) }
        val actualRatio = if (result.isNotEmpty()) actualCompounds.toDouble() / result.size else 0.0
        logger.info(
            "Compound ratio enforced: target=${String.format("%.0f", targetRatio * 100)}%, " +
                    "actual=${String.format("%.0f", actualRatio * 100)}% " +
                    "($actualCompounds/${result.size} compounds)"
        )

        return result.take(totalCount)
    }
}

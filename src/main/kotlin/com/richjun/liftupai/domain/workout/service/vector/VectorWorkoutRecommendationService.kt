package com.richjun.liftupai.domain.workout.service.vector

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier
import com.richjun.liftupai.domain.workout.service.ExerciseRecommendationService
import com.richjun.liftupai.domain.workout.service.RecommendationConstants
import com.richjun.liftupai.domain.workout.service.RecommendationExerciseRanking
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.richjun.liftupai.global.time.AppTime

/**
 * 벡터 기반 운동 추천 서비스
 *
 * Qdrant 벡터 검색을 활용한 시맨틱 기반 운동 추천
 * 실패 시 ExerciseRecommendationService로 폴백
 */
@Service
@Transactional
class VectorWorkoutRecommendationService(
    private val exerciseRepository: ExerciseRepository,
    private val exerciseVectorService: ExerciseVectorService,
    private val exerciseQdrantService: ExerciseQdrantService,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val exerciseRecommendationService: ExerciseRecommendationService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val VECTOR_SEARCH_MULTIPLIER = 3
        private const val MIN_SIMILARITY_SCORE = 0.55f
        private const val RECOVERY_THRESHOLD = RecommendationConstants.RECOVERY_THRESHOLD_PERCENT
        private const val SAFETY_MIN = RecommendationConstants.SAFETY_MIN_EXERCISES
    }

    /**
     * 모든 운동을 벡터화하여 Qdrant에 저장
     */
    fun indexAllExercises() {
        val exercises = exerciseRepository.findAll()
        var indexed = 0
        var failed = 0

        exercises.forEach { exercise ->
            try {
                if (exercise.vectorId != null) {
                    logger.debug("Exercise ${exercise.name} already indexed, skipping")
                    return@forEach
                }

                val text = exerciseVectorService.exerciseToText(exercise)
                val embedding = exerciseVectorService.generateEmbedding(text)
                val metadata = mapOf("name" to exercise.name, "category" to exercise.category.name)

                val vectorId = exerciseQdrantService.upsertExercise(
                    exerciseId = exercise.id,
                    vector = embedding,
                    metadata = metadata
                )

                exercise.vectorId = vectorId
                exerciseRepository.save(exercise)
                indexed++
                logger.info("[$indexed/${exercises.size}] Indexed: ${exercise.name}")
            } catch (e: Exception) {
                failed++
                logger.warn("Failed to index ${exercise.name}: ${e.message}")
            }
        }

        logger.info("Indexing complete: $indexed indexed, $failed failed")
    }

    /**
     * 벡터 기반 운동 추천
     */
    fun recommendExercises(
        user: User,
        profile: UserProfile?,
        duration: Int? = null,
        targetMuscle: String? = null,
        equipment: String? = null,
        difficulty: String? = null,
        workoutType: WorkoutType? = null,
        limit: Int = 10
    ): List<Exercise> {
        return try {
            vectorBasedRecommendation(user, profile, duration, targetMuscle, equipment, difficulty, workoutType, limit)
        } catch (e: Exception) {
            logger.warn("Vector search failed: ${e.message}, using fallback")
            fallbackRecommendation(user, targetMuscle, equipment, limit)
        }
    }

    private fun vectorBasedRecommendation(
        user: User,
        profile: UserProfile?,
        duration: Int?,
        targetMuscle: String?,
        equipment: String?,
        difficulty: String?,
        workoutType: WorkoutType?,
        limit: Int
    ): List<Exercise> {
        // 사용자 컨텍스트 생성
        val context = buildUserContext(user, profile, targetMuscle, workoutType)

        // 벡터 검색용 텍스트 생성
        val requestText = exerciseVectorService.userRequestToText(
            userGoals = context.goals,
            targetMuscles = context.targetMuscles,
            equipment = equipment,
            difficulty = difficulty,
            duration = duration,
            userLevel = context.level,
            workoutHistory = context.workoutHistory,
            avoidMuscles = context.avoidMuscles,
            weeklyVolume = context.weeklyVolume
        )

        logger.debug("Vector search query: $requestText")

        // 벡터 검색 (제로 벡터 감지 시 즉시 폴백)
        val queryVector = exerciseVectorService.generateEmbedding(requestText)
        if (queryVector.all { it == 0.0f }) {
            throw IllegalStateException("Embedding API returned zero vector, triggering fallback")
        }
        val searchResults = exerciseQdrantService.searchSimilarExercises(
            queryVector = queryVector,
            limit = limit * VECTOR_SEARCH_MULTIPLIER,
            scoreThreshold = MIN_SIMILARITY_SCORE
        )

        logger.info("Vector search returned ${searchResults.size} results")

        // 결과를 Exercise로 변환 — 배치 조회로 N+1 쿼리 방지
        val exerciseIds = searchResults.map { it.first }
        val exerciseMap = exerciseRepository.findAllById(exerciseIds).associateBy { it.id }
        val exercises = searchResults.mapNotNull { (id, _) -> exerciseMap[id] }

        // 회복/안전 필터를 먼저 적용하여 대안 운동이 소실되지 않도록 함
        return exercises
            .let { filterByRecovery(it, context.recoveringMuscles) }
            .let { filterByTier(it) }
            .let { filterByTargetMuscle(it, targetMuscle) }
            .let { filterByEquipment(it, equipment) }
            .let { filterByGoal(it, profile?.goals) }
            .let { filterByDifficulty(it, profile?.experienceLevel) }
            .let { sortByRelevance(it) }
            .let { removeDuplicatePatterns(it) }
            .let { orderByPriority(it) }
            .distinctBy { it.id }
            .take(limit)
    }

    /**
     * 폴백: ExerciseRecommendationService 사용
     */
    private fun fallbackRecommendation(user: User, targetMuscle: String?, equipment: String?, limit: Int): List<Exercise> {
        logger.info("Using fallback recommendation service")
        return exerciseRecommendationService.getRecommendedExercises(
            user = user,
            targetMuscle = targetMuscle,
            equipment = equipment,
            limit = limit
        )
    }

    // === 컨텍스트 빌더 ===

    private data class UserContext(
        val goals: String,
        val level: String,
        val targetMuscles: List<String>,
        val avoidMuscles: List<String>,
        val weeklyVolume: Map<String, Int>,
        val recoveringMuscles: Set<MuscleGroup>,
        val workoutHistory: String? = null
    )

    private fun buildUserContext(user: User, profile: UserProfile?, targetMuscle: String?, workoutType: WorkoutType?): UserContext {
        val locale = resolveLocale(user.id)
        val goals = profile?.goals?.joinToString(", ") { it.name } ?: "general fitness"
        val level = profile?.experienceLevel?.name ?: "BEGINNER"

        val targetMuscles = when {
            targetMuscle != null -> listOf(targetMuscle)
            workoutType != null -> getTargetMusclesForWorkoutType(workoutType, locale)
            else -> emptyList()
        }

        val recoveringMuscles = getRecoveringMuscles(user)
        val avoidMuscles = recoveringMuscles.map { translateMuscleGroup(it, locale) }
        val weeklyVolume = getWeeklyVolume(user, locale)
        val workoutHistory = buildWorkoutHistorySummary(user)

        return UserContext(goals, level, targetMuscles, avoidMuscles, weeklyVolume, recoveringMuscles, workoutHistory)
    }

    /**
     * 사용자 최근 운동 이력을 시맨틱 검색용 텍스트로 요약
     * - 자주 하는 운동 → 변형 운동 추천 유도
     * - 정체기 운동 → 대체 운동 추천 유도
     * - 안 하는 패턴 → 패턴 갭 보완 유도
     */
    private fun buildWorkoutHistorySummary(user: User): String? {
        return try {
            val stats = collectExerciseStats(user) ?: return null
            val parts = mutableListOf<String>()

            analyzeFrequency(stats, parts)
            analyzePlateau(stats, parts)
            analyzeRPE(stats, parts)
            analyzeCompletionRate(stats, parts)
            analyzeMissingPatterns(stats, parts)

            if (parts.isEmpty()) null else parts.joinToString(". ")
        } catch (e: Exception) {
            logger.warn("Failed to build workout history summary: ${e.message}")
            null
        }
    }

    /** 최근 28일 운동 이력을 운동별 통계로 집계 */
    private data class ExerciseStats(
        val frequency: Map<String, Int>,
        val weights: Map<String, List<Double>>,
        val rpes: Map<String, List<Double>>,
        val completionRate: Map<String, Pair<Int, Int>>,  // completed / total
        val doneBroadPatterns: Set<String>
    )

    private fun collectExerciseStats(user: User): ExerciseStats? {
        val cutoff = AppTime.utcNow().minusDays(28)
        val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
        if (sessions.isEmpty()) return null

        val sessionIds = sessions.map { it.id }
        val allWorkoutExercises = workoutExerciseRepository.findBySessionIdInWithExercise(sessionIds)
        if (allWorkoutExercises.isEmpty()) return null

        val weIds = allWorkoutExercises.map { it.id }
        val allSets = exerciseSetRepository.findByWorkoutExerciseIdIn(weIds)
        val setsByWeId = allSets.groupBy { it.workoutExercise.id }

        val frequency = mutableMapOf<String, Int>()
        val weights = mutableMapOf<String, MutableList<Double>>()
        val rpes = mutableMapOf<String, MutableList<Double>>()
        val completionRate = mutableMapOf<String, Pair<Int, Int>>()

        allWorkoutExercises.forEach { we ->
            val name = we.exercise.name
            frequency[name] = (frequency[name] ?: 0) + 1
            val sets = setsByWeId[we.id] ?: emptyList()
            if (sets.isNotEmpty()) {
                weights.getOrPut(name) { mutableListOf() }.add(sets.maxOf { it.weight })
                sets.mapNotNull { it.rpe?.toDouble() }.let { r ->
                    if (r.isNotEmpty()) rpes.getOrPut(name) { mutableListOf() }.addAll(r)
                }
                val completed = sets.count { it.completed }
                val prev = completionRate[name] ?: Pair(0, 0)
                completionRate[name] = Pair(prev.first + completed, prev.second + sets.size)
            }
        }

        val doneBroadPatterns = allWorkoutExercises
            .map { broadPatternCategory(exercisePatternClassifier.classifyExercise(it.exercise)) }
            .toSet()

        return ExerciseStats(frequency, weights, rpes, completionRate, doneBroadPatterns)
    }

    /** 자주 하는 운동 상위 5개 */
    private fun analyzeFrequency(stats: ExerciseStats, parts: MutableList<String>) {
        val frequent = stats.frequency.entries.sortedByDescending { it.value }.take(5)
        if (frequent.isNotEmpty()) {
            parts.add("Frequently done: ${frequent.joinToString(", ") { "${it.key} (${it.value}x)" }}")
        }
    }

    /** 정체기 감지: 무게 정체 + RPE 미감소 = 진행 중단 */
    private fun analyzePlateau(stats: ExerciseStats, parts: MutableList<String>) {
        val plateaued = stats.weights.filter { (name, weights) ->
            if (weights.size < 3) return@filter false
            val weightStagnant = weights.takeLast(3).distinct().size == 1
            val rpes = stats.rpes[name]
            val rpeDecreasing = rpes != null && rpes.size >= 3 &&
                rpes.takeLast(3).let { it[2] < it[0] }
            weightStagnant && !rpeDecreasing
        }.keys.take(3)
        if (plateaued.isNotEmpty()) {
            parts.add("Plateaued exercises (need variation): ${plateaued.joinToString(", ")}")
        }
    }

    /** RPE 분석: 너무 어려운/쉬운 운동 감지 */
    private fun analyzeRPE(stats: ExerciseStats, parts: MutableList<String>) {
        val hard = stats.rpes.filter { (_, r) -> r.size >= 2 && r.average() >= 8.5 }.keys.take(3)
        if (hard.isNotEmpty()) {
            parts.add("User finds these very hard (consider easier alternatives): ${hard.joinToString(", ")}")
        }
        val easy = stats.rpes.filter { (_, r) -> r.size >= 2 && r.average() <= 5.0 }.keys.take(3)
        if (easy.isNotEmpty()) {
            parts.add("User finds these too easy (suggest harder variations): ${easy.joinToString(", ")}")
        }
    }

    /** 낮은 완료율 (< 70%): 사용자 기피 운동 */
    private fun analyzeCompletionRate(stats: ExerciseStats, parts: MutableList<String>) {
        val avoided = stats.completionRate.filter { (_, pair) ->
            pair.second >= 4 && (pair.first.toDouble() / pair.second) < 0.7
        }.keys.take(3)
        if (avoided.isNotEmpty()) {
            parts.add("Low completion rate (user may dislike): ${avoided.joinToString(", ")}")
        }
    }

    /** 최근 28일간 안 한 동작 패턴 감지 */
    private fun analyzeMissingPatterns(stats: ExerciseStats, parts: MutableList<String>) {
        val allBroadPatterns = setOf(
            "HORIZONTAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PUSH", "VERTICAL_PULL",
            "HIP_HINGE", "SQUAT", "LUNGE", "CARRY", "ROTATION", "ISOLATION", "CORE"
        )
        val missing = allBroadPatterns - stats.doneBroadPatterns
        if (missing.isNotEmpty() && missing.size < allBroadPatterns.size) {
            parts.add("Missing movement patterns (recommend): ${missing.take(3).joinToString(", ")}")
        }
    }

    // === 필터링 메서드 ===

    private fun filterByTier(exercises: List<Exercise>): List<Exercise> {
        return exercises.filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
    }

    /**
     * 목표 기반 하드 필터:
     * - STRENGTH: 복합운동 우선, 고립 운동 비율 제한
     * - WEIGHT_LOSS/ENDURANCE: 전신/유산소/복합 운동 우선
     * - MUSCLE_GAIN: 고립+복합 균형, CARDIO 제외
     * - GENERAL_FITNESS/ATHLETIC: 필터 없음 (다양성 유지)
     */
    private fun filterByGoal(exercises: List<Exercise>, goals: Set<com.richjun.liftupai.domain.user.entity.FitnessGoal>?): List<Exercise> {
        if (goals.isNullOrEmpty()) return exercises

        val primaryGoal = goals.first()
        val filtered = when (primaryGoal) {
            com.richjun.liftupai.domain.user.entity.FitnessGoal.STRENGTH -> {
                // 근력: 복합운동 70% 이상 보장 — CARDIO 제외, compound 우선
                val nonCardio = exercises.filter { it.category != ExerciseCategory.CARDIO }
                val compounds = nonCardio.filter { it.muscleGroups.size >= 2 }
                val isolations = nonCardio.filter { it.muscleGroups.size < 2 }
                // compound 70% 비율 보장
                val maxIsolation = (compounds.size * 3) / 7  // compound:isolation = 7:3
                val result = compounds + isolations.take(maxIsolation)
                if (result.size >= SAFETY_MIN) result else nonCardio.ifEmpty { exercises }
            }
            com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS, com.richjun.liftupai.domain.user.entity.FitnessGoal.ENDURANCE -> {
                // 체중감량/지구력: 전신/유산소 우선, 고난이도 고립 제외
                exercises.filter { it.difficulty <= 70 }
            }
            com.richjun.liftupai.domain.user.entity.FitnessGoal.MUSCLE_GAIN -> {
                // 근비대: CARDIO 제외 (별도 유산소 세션에서)
                exercises.filter { it.category != ExerciseCategory.CARDIO }
            }
            else -> exercises // GENERAL_FITNESS, ATHLETIC_PERFORMANCE: 다양성 유지
        }

        return if (filtered.size >= SAFETY_MIN) filtered else exercises
    }

    private fun filterByDifficulty(exercises: List<Exercise>, level: ExperienceLevel?): List<Exercise> {
        val range = when (level) {
            ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE -> 1..40
            ExperienceLevel.INTERMEDIATE -> 20..70
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> 30..100
            else -> 20..70
        }
        val filtered = exercises.filter { it.difficulty in range }
        return if (filtered.size >= SAFETY_MIN) filtered else exercises
    }

    private fun sortByRelevance(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(RecommendationExerciseRanking.patternSelectionComparator())
    }

    private fun filterByRecovery(exercises: List<Exercise>, recoveringMuscles: Set<MuscleGroup>): List<Exercise> {
        if (recoveringMuscles.isEmpty()) return exercises
        return exercises.filter { exercise ->
            // 모든 근육 그룹 체크 (primary + secondary)
            exercise.muscleGroups.none { it in recoveringMuscles }
        }
    }

    private fun filterByTargetMuscle(exercises: List<Exercise>, targetMuscle: String?): List<Exercise> {
        if (targetMuscle == null) return exercises
        val key = WorkoutTargetResolver.recommendationKey(targetMuscle) ?: return exercises
        val targetGroups = WorkoutTargetResolver.muscleGroupsForKey(key)
        if (targetGroups.isEmpty()) return exercises

        val filtered = exercises.filter { exercise ->
            exercise.muscleGroups.any { it in targetGroups }
        }
        return if (filtered.size >= SAFETY_MIN) filtered else exercises
    }

    private fun filterByEquipment(exercises: List<Exercise>, equipment: String?): List<Exercise> {
        if (equipment == null) return exercises
        val equipmentEnum = try {
            Equipment.valueOf(equipment.uppercase().replace(" ", "_"))
        } catch (e: IllegalArgumentException) {
            return exercises
        }
        val filtered = exercises.filter { it.equipment == equipmentEnum }
        return if (filtered.size >= SAFETY_MIN) filtered else exercises
    }

    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (_, group) ->
                group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())
            }
    }

    /**
     * 카테고리 균형 라운드 로빈 정렬
     * 각 카테고리에서 최고 운동을 하나씩 돌아가며 배치
     */
    private fun orderByPriority(exercises: List<Exercise>): List<Exercise> {
        val byCategory = exercises
            .groupBy { it.category }
            .mapValues { (_, group) ->
                group.sortedWith(RecommendationExerciseRanking.displayOrderComparator())
                    .toMutableList()
            }

        val categoryOrder = listOf(
            ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
            ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY
        )

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

    // === 헬퍼 메서드 ===

    private fun getRecoveringMuscles(user: User): Set<MuscleGroup> {
        return try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < RECOVERY_THRESHOLD }
                .flatMap { recovery -> WorkoutTargetResolver.muscleGroupsFor(recovery.muscleGroup) }
                .toSet()
        } catch (e: Exception) {
            logger.warn("Failed to get recovering muscles: ${e.message}")
            emptySet()
        }
    }

    private fun getWeeklyVolume(user: User, locale: String = "en"): Map<String, Int> {
        return try {
            val cutoff = AppTime.utcNow().minusDays(7)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
            if (sessions.isEmpty()) return emptyMap()

            val sessionIds = sessions.map { it.id }
            val allWorkoutExercises = workoutExerciseRepository.findBySessionIdInWithExercise(sessionIds)
            if (allWorkoutExercises.isEmpty()) return emptyMap()

            val weIds = allWorkoutExercises.map { it.id }
            val allSets = exerciseSetRepository.findByWorkoutExerciseIdIn(weIds)
            val setCountByWeId = allSets.groupBy { it.workoutExercise.id }.mapValues { it.value.size }

            MuscleGroup.values().associate { muscle ->
                val totalSets = allWorkoutExercises
                    .filter { we -> we.exercise.muscleGroups.contains(muscle) }
                    .sumOf { we -> setCountByWeId[we.id] ?: 0 }
                translateMuscleGroup(muscle, locale) to totalSets
            }.filter { it.value > 0 }
        } catch (e: Exception) {
            logger.warn("Failed to get weekly volume: ${e.message}")
            emptyMap()
        }
    }

    private fun getTargetMusclesForWorkoutType(type: WorkoutType, locale: String = "en"): List<String> {
        return WorkoutTargetResolver.displayNamesForWorkoutType(type, locale = locale)
    }

    private fun translateMuscleGroup(muscle: MuscleGroup, locale: String = "en"): String {
        return WorkoutTargetResolver.displayName(muscle, locale = locale)
    }

    /**
     * 세분화된 MovementPattern을 broad 카테고리로 매핑
     */
    private fun broadPatternCategory(pattern: ExercisePatternClassifier.MovementPattern): String {
        return when (pattern) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_MACHINE,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.DECLINE_PRESS,
            ExercisePatternClassifier.MovementPattern.DIPS,
            ExercisePatternClassifier.MovementPattern.PUSHUP,
            ExercisePatternClassifier.MovementPattern.FLY -> "HORIZONTAL_PUSH"

            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_MACHINE -> "VERTICAL_PUSH"

            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.INVERTED_ROW -> "HORIZONTAL_PULL"

            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN -> "VERTICAL_PULL"

            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> "HIP_HINGE"

            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> "SQUAT"

            ExercisePatternClassifier.MovementPattern.LUNGE -> "LUNGE"
            ExercisePatternClassifier.MovementPattern.CARRY -> "CARRY"
            ExercisePatternClassifier.MovementPattern.ROTATION -> "ROTATION"

            ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL,
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_CABLE,
            ExercisePatternClassifier.MovementPattern.TRICEP_OVERHEAD,
            ExercisePatternClassifier.MovementPattern.TRICEP_LYING,
            ExercisePatternClassifier.MovementPattern.TRICEP_PUSHDOWN,
            ExercisePatternClassifier.MovementPattern.LATERAL_RAISE,
            ExercisePatternClassifier.MovementPattern.FRONT_RAISE,
            ExercisePatternClassifier.MovementPattern.REAR_DELT,
            ExercisePatternClassifier.MovementPattern.FACE_PULL,
            ExercisePatternClassifier.MovementPattern.UPRIGHT_ROW,
            ExercisePatternClassifier.MovementPattern.SHRUG,
            ExercisePatternClassifier.MovementPattern.LEG_CURL,
            ExercisePatternClassifier.MovementPattern.LEG_EXTENSION,
            ExercisePatternClassifier.MovementPattern.GLUTE_FOCUSED,
            ExercisePatternClassifier.MovementPattern.CALF -> "ISOLATION"

            ExercisePatternClassifier.MovementPattern.CRUNCH,
            ExercisePatternClassifier.MovementPattern.LEG_RAISE,
            ExercisePatternClassifier.MovementPattern.PLANK,
            ExercisePatternClassifier.MovementPattern.ROLLOUT -> "CORE"

            else -> "OTHER"
        }
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }
}

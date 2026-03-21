package com.richjun.liftupai.domain.workout.service.vector

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier
import com.richjun.liftupai.domain.workout.service.ExerciseRecommendationService
import com.richjun.liftupai.domain.workout.service.RecommendationExerciseRanking
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

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
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val exerciseRecommendationService: ExerciseRecommendationService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val VECTOR_SEARCH_MULTIPLIER = 3  // 필터링 후 부족할 수 있으므로 더 많이 검색
        private const val MIN_SIMILARITY_SCORE = 0.4f
        private const val RECOVERY_THRESHOLD = 80
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

        // 결과를 Exercise로 변환 및 필터링
        return searchResults
            .mapNotNull { (id, _) -> exerciseRepository.findById(id).orElse(null) }
            .let { filterByTier(it) }
            .let { filterByGoal(it, profile?.goals) }
            .let { filterByDifficulty(it, profile?.experienceLevel) }
            .let { sortByRelevance(it) }
            .let { filterByRecovery(it, context.recoveringMuscles) }
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
        val goals = profile?.goals?.joinToString(", ") { it.name } ?: "general fitness"
        val level = profile?.experienceLevel?.name ?: "BEGINNER"

        val targetMuscles = when {
            targetMuscle != null -> listOf(targetMuscle)
            workoutType != null -> getTargetMusclesForWorkoutType(workoutType)
            else -> emptyList()
        }

        val recoveringMuscles = getRecoveringMuscles(user)
        val avoidMuscles = recoveringMuscles.map { translateMuscleGroup(it) }
        val weeklyVolume = getWeeklyVolume(user)
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
            val cutoff = LocalDateTime.now().minusDays(28)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)
            if (sessions.isEmpty()) return null

            val exerciseFrequency = mutableMapOf<String, Int>()
            val exerciseWeights = mutableMapOf<String, MutableList<Double>>()
            val exerciseRPEs = mutableMapOf<String, MutableList<Double>>()
            val exerciseCompletionRate = mutableMapOf<String, Pair<Int, Int>>() // completed sets / total sets

            sessions.forEach { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).forEach { we ->
                    val name = we.exercise.name
                    exerciseFrequency[name] = (exerciseFrequency[name] ?: 0) + 1
                    val sets = exerciseSetRepository.findByWorkoutExerciseId(we.id)
                    if (sets.isNotEmpty()) {
                        exerciseWeights.getOrPut(name) { mutableListOf() }
                            .add(sets.maxOf { it.weight })
                        // RPE 추적
                        sets.mapNotNull { it.rpe?.toDouble() }.let { rpes ->
                            if (rpes.isNotEmpty()) {
                                exerciseRPEs.getOrPut(name) { mutableListOf() }.addAll(rpes)
                            }
                        }
                        // 완료율 추적
                        val completed = sets.count { it.completed }
                        val prev = exerciseCompletionRate[name] ?: Pair(0, 0)
                        exerciseCompletionRate[name] = Pair(prev.first + completed, prev.second + sets.size)
                    }
                }
            }

            val parts = mutableListOf<String>()

            // 자주 하는 운동 상위 5개
            val frequent = exerciseFrequency.entries.sortedByDescending { it.value }.take(5)
            if (frequent.isNotEmpty()) {
                parts.add("Frequently done: ${frequent.joinToString(", ") { "${it.key} (${it.value}x)" }}")
            }

            // 정체기 감지: 최근 3회 이상 같은 무게
            val plateaued = exerciseWeights.filter { (_, weights) ->
                weights.size >= 3 && weights.takeLast(3).distinct().size == 1
            }.keys.take(3)
            if (plateaued.isNotEmpty()) {
                parts.add("Plateaued exercises (need variation): ${plateaued.joinToString(", ")}")
            }

            // 힘들어하는 운동 (평균 RPE >= 8.5): 대체 운동 고려
            val hardExercises = exerciseRPEs.filter { (_, rpes) ->
                rpes.size >= 2 && rpes.average() >= 8.5
            }.keys.take(3)
            if (hardExercises.isNotEmpty()) {
                parts.add("User finds these very hard (consider easier alternatives): ${hardExercises.joinToString(", ")}")
            }

            // 편하게 하는 운동 (평균 RPE <= 5): 더 도전적인 변형 추천
            val easyExercises = exerciseRPEs.filter { (_, rpes) ->
                rpes.size >= 2 && rpes.average() <= 5.0
            }.keys.take(3)
            if (easyExercises.isNotEmpty()) {
                parts.add("User finds these too easy (suggest harder variations): ${easyExercises.joinToString(", ")}")
            }

            // 낮은 완료율 운동 (< 70%): 사용자가 기피하는 운동
            val avoidedExercises = exerciseCompletionRate.filter { (_, pair) ->
                pair.second >= 4 && (pair.first.toDouble() / pair.second) < 0.7
            }.keys.take(3)
            if (avoidedExercises.isNotEmpty()) {
                parts.add("Low completion rate (user may dislike): ${avoidedExercises.joinToString(", ")}")
            }

            // 최근 안 한 패턴 감지 (세분화된 enum → broad 카테고리로 매핑)
            val doneBroadPatterns = sessions.flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .map { broadPatternCategory(exercisePatternClassifier.classifyExercise(it.exercise)) }
            }.toSet()

            val allBroadPatterns = setOf("HORIZONTAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PUSH", "VERTICAL_PULL",
                "HIP_HINGE", "SQUAT", "LUNGE", "CARRY", "ROTATION", "ISOLATION")
            val missingPatterns = allBroadPatterns - doneBroadPatterns
            if (missingPatterns.isNotEmpty() && missingPatterns.size < allBroadPatterns.size) {
                parts.add("Missing movement patterns (recommend): ${missingPatterns.take(3).joinToString(", ")}")
            }

            if (parts.isEmpty()) null else parts.joinToString(". ")
        } catch (e: Exception) {
            logger.warn("Failed to build workout history summary: ${e.message}")
            null
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
                // 근력: 복합운동 70% 이상 보장 — CARDIO 제외, 고립 비율 제한
                val compounds = exercises.filter { it.category != ExerciseCategory.CARDIO && it.difficulty >= 30 }
                if (compounds.size >= 3) compounds else exercises
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

        // 필터 후 너무 적으면 원본 반환 (안전 장치)
        return if (filtered.size >= 5) filtered else exercises
    }

    private fun filterByDifficulty(exercises: List<Exercise>, level: ExperienceLevel?): List<Exercise> {
        val range = when (level) {
            ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE -> 1..40
            ExperienceLevel.INTERMEDIATE -> 20..70
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> 30..100
            else -> 20..70
        }
        return exercises.filter { it.difficulty in range }
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

    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (_, group) ->
                group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())
            }
    }

    private fun orderByPriority(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(RecommendationExerciseRanking.displayOrderComparator())
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

    private fun getWeeklyVolume(user: User): Map<String, Int> {
        return try {
            val cutoff = LocalDateTime.now().minusDays(7)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoff)

            MuscleGroup.values().associate { muscle ->
                var totalSets = 0
                sessions.forEach { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                        .filter { we -> we.exercise.muscleGroups.contains(muscle) }
                        .forEach { we ->
                            totalSets += exerciseSetRepository.findByWorkoutExerciseId(we.id).size
                        }
                }
                translateMuscleGroup(muscle) to totalSets
            }.filter { it.value > 0 }
        } catch (e: Exception) {
            logger.warn("Failed to get weekly volume: ${e.message}")
            emptyMap()
        }
    }

    private fun getTargetMusclesForWorkoutType(type: WorkoutType): List<String> {
        return WorkoutTargetResolver.displayNamesForWorkoutType(type, locale = "ko")
    }

    private fun translateMuscleGroup(muscle: MuscleGroup): String {
        return WorkoutTargetResolver.displayName(muscle, locale = "ko")
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
}

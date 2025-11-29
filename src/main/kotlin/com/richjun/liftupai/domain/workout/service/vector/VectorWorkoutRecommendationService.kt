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
        private const val MIN_SIMILARITY_SCORE = 0.2f
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
            avoidMuscles = context.avoidMuscles,
            weeklyVolume = context.weeklyVolume
        )

        logger.debug("Vector search query: $requestText")

        // 벡터 검색
        val queryVector = exerciseVectorService.generateEmbedding(requestText)
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
        val recoveringMuscles: Set<MuscleGroup>
    )

    private fun buildUserContext(user: User, profile: UserProfile?, targetMuscle: String?, workoutType: WorkoutType?): UserContext {
        val goals = profile?.goals?.joinToString(", ") { it.name } ?: "전반적인 체력 향상"
        val level = profile?.experienceLevel?.name ?: "BEGINNER"

        val targetMuscles = when {
            targetMuscle != null -> listOf(targetMuscle)
            workoutType != null -> getTargetMusclesForWorkoutType(workoutType)
            else -> emptyList()
        }

        val recoveringMuscles = getRecoveringMuscles(user)
        val avoidMuscles = recoveringMuscles.map { translateMuscleGroup(it) }
        val weeklyVolume = getWeeklyVolume(user)

        return UserContext(goals, level, targetMuscles, avoidMuscles, weeklyVolume, recoveringMuscles)
    }

    // === 필터링 메서드 ===

    private fun filterByTier(exercises: List<Exercise>): List<Exercise> {
        val allowedTiers = setOf(RecommendationTier.ESSENTIAL, RecommendationTier.STANDARD)
        return exercises.filter { it.recommendationTier in allowedTiers }
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
        return exercises.sortedWith(
            compareByDescending<Exercise> { it.isBasicExercise }
                .thenByDescending { it.popularity }
                .thenBy { it.difficulty }
        )
    }

    private fun filterByRecovery(exercises: List<Exercise>, recoveringMuscles: Set<MuscleGroup>): List<Exercise> {
        if (recoveringMuscles.isEmpty()) return exercises
        return exercises.filter { exercise ->
            val primaryMuscle = exercise.muscleGroups.firstOrNull()
            primaryMuscle == null || primaryMuscle !in recoveringMuscles
        }
    }

    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (_, group) ->
                group.minWithOrNull(
                    compareBy<Exercise> { it.difficulty }
                        .thenByDescending { it.popularity }
                        .thenByDescending { it.isBasicExercise }
                )
            }
    }

    private fun orderByPriority(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(
            compareBy<Exercise> { getCategoryPriority(it.category) }
                .thenBy { if (isCompound(it)) 0 else 1 }
        )
    }

    // === 헬퍼 메서드 ===

    private fun getRecoveringMuscles(user: User): Set<MuscleGroup> {
        return try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < RECOVERY_THRESHOLD }
                .mapNotNull { r ->
                    try { MuscleGroup.valueOf(r.muscleGroup.uppercase()) } catch (e: Exception) { null }
                }
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
        return when (type) {
            WorkoutType.PUSH -> listOf("가슴", "어깨", "삼두")
            WorkoutType.PULL -> listOf("등", "이두")
            WorkoutType.LEGS -> listOf("하체", "대퇴사두", "햄스트링", "둔근")
            WorkoutType.UPPER -> listOf("가슴", "등", "어깨", "팔")
            WorkoutType.LOWER -> listOf("하체", "대퇴사두", "햄스트링", "둔근", "종아리")
            WorkoutType.CHEST -> listOf("가슴")
            WorkoutType.BACK -> listOf("등")
            WorkoutType.SHOULDERS -> listOf("어깨")
            WorkoutType.ARMS -> listOf("이두", "삼두")
            else -> emptyList()
        }
    }

    private fun translateMuscleGroup(muscle: MuscleGroup): String {
        return when (muscle) {
            MuscleGroup.CHEST -> "가슴"
            MuscleGroup.BACK -> "등"
            MuscleGroup.SHOULDERS -> "어깨"
            MuscleGroup.BICEPS -> "이두"
            MuscleGroup.TRICEPS -> "삼두"
            MuscleGroup.LEGS -> "다리"
            MuscleGroup.CORE, MuscleGroup.ABS -> "코어"
            MuscleGroup.GLUTES -> "둔근"
            MuscleGroup.CALVES -> "종아리"
            MuscleGroup.FOREARMS -> "전완"
            MuscleGroup.QUADRICEPS -> "대퇴사두"
            MuscleGroup.HAMSTRINGS -> "햄스트링"
            MuscleGroup.LATS -> "광배근"
            MuscleGroup.TRAPS -> "승모근"
            else -> muscle.name
        }
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

    private fun isCompound(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true
        val keywords = listOf("프레스", "스쿼트", "데드리프트", "로우", "풀업", "친업", "딥스", "런지")
        return keywords.any { exercise.name.lowercase().contains(it) }
    }
}

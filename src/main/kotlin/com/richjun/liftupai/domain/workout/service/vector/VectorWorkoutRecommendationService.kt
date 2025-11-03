package com.richjun.liftupai.domain.workout.service.vector

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class VectorWorkoutRecommendationService(
    private val exerciseRepository: ExerciseRepository,
    private val exerciseVectorService: ExerciseVectorService,
    private val exerciseQdrantService: ExerciseQdrantService,
    private val workoutSessionRepository: com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository,
    private val workoutExerciseRepository: com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository,
    private val exerciseSetRepository: com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository,
    private val muscleRecoveryRepository: com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
) {

    /**
     * 모든 운동을 벡터화하여 Qdrant에 저장
     * (초기화나 배치 작업용)
     */
    fun indexAllExercises() {
        val exercises = exerciseRepository.findAll()
        var indexed = 0
        var failed = 0

        exercises.forEach { exercise ->
            try {
                // 이미 벡터가 있으면 스킵
                if (exercise.vectorId != null) {
                    println("Exercise ${exercise.name} already has vectorId, skipping")
                    return@forEach
                }

                // 텍스트 생성
                val text = exerciseVectorService.exerciseToText(exercise)

                // 벡터 임베딩 생성
                val embedding = exerciseVectorService.generateEmbedding(text)

                // 메타데이터 준비
                val metadata = mapOf(
                    "name" to exercise.name,
                    "category" to exercise.category.name
                )

                // Qdrant에 저장
                val vectorId = exerciseQdrantService.upsertExercise(
                    exerciseId = exercise.id,
                    vector = embedding,
                    metadata = metadata
                )

                // Exercise 엔티티에 vectorId 저장
                exercise.vectorId = vectorId
                exerciseRepository.save(exercise)

                indexed++
                println("[$indexed/${exercises.size}] Exercise indexed: ${exercise.name}")
            } catch (e: Exception) {
                failed++
                println("Failed to index exercise ${exercise.name}: ${e.message}")
            }
        }

        println("Indexing completed: $indexed indexed, $failed failed out of ${exercises.size} total")
    }

    /**
     * 사용자 요구사항 기반 운동 추천 (벡터 검색)
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
        try {
            // 사용자 요구사항을 텍스트로 변환
            val userGoals = profile?.goals?.joinToString(", ") { it.name } ?: "전반적인 체력 향상"
            val userLevel = profile?.experienceLevel?.name ?: "BEGINNER"

            // 타겟 근육 결정
            val targetMuscles = if (targetMuscle != null) {
                listOf(targetMuscle)
            } else if (workoutType != null) {
                getTargetMusclesForWorkoutType(workoutType)
            } else {
                emptyList()
            }

            // 헬스 트레이너 정보: 회복 중인 근육, 주간 볼륨
            val recentlyWorkedMuscles = getRecentlyWorkedMusclesVector(user, 48)
            val recoveringMuscles = getRecoveringMusclesVector(user)
            val avoidMuscles = (recentlyWorkedMuscles + recoveringMuscles).map { translateMuscleGroupToKoreanVector(it) }

            val weeklyVolume = getWeeklyVolumeMapVector(user)

            val requestText = exerciseVectorService.userRequestToText(
                userGoals = userGoals,
                targetMuscles = targetMuscles,
                equipment = equipment,
                difficulty = difficulty,
                duration = duration,
                userLevel = userLevel,
                avoidMuscles = avoidMuscles,
                weeklyVolume = weeklyVolume
            )

            println("User request text (with trainer info): $requestText")

            // 벡터 임베딩 생성
            val queryVector = exerciseVectorService.generateEmbedding(requestText)

            // Qdrant에서 유사한 운동 검색
            val similarResults = exerciseQdrantService.searchSimilarExercises(
                queryVector = queryVector,
                limit = limit * 3, // 필터링 후 부족할 수 있으므로 더 많이 가져옴
                scoreThreshold = 0.2f
            )

            println("Found ${similarResults.size} similar exercises from Qdrant")

            // 운동 정보 조회
            var exercises = similarResults.mapNotNull { (exerciseId, score) ->
                exerciseRepository.findById(exerciseId).orElse(null)?.also {
                    println("  - ${it.name} (score: $score)")
                }
            }

            // 회복 중인 근육 필터링
            exercises = exercises.filter { exercise ->
                exercise.muscleGroups.none { it in recentlyWorkedMuscles || it in recoveringMuscles }
            }
            println("After recovery filtering: ${exercises.size} exercises")

            // 운동 순서 정렬 (복합운동 → 고립운동, 큰 근육 → 작은 근육)
            exercises = orderExercisesByPriorityVector(exercises)
            println("After ordering: exercises sorted by priority")

            // 중복 제거 및 제한
            return exercises.distinctBy { it.id }.take(limit)

        } catch (e: Exception) {
            println("Vector search failed: ${e.message}, falling back to traditional search")
            e.printStackTrace()

            // 실패 시 기존 방식으로 폴백
            return fallbackRecommendation(user, targetMuscle, equipment, limit)
        }
    }

    /**
     * 벡터 검색 실패 시 대체 추천 (헬스 트레이너 관점 적용)
     */
    private fun fallbackRecommendation(
        user: User,
        targetMuscle: String?,
        equipment: String?,
        limit: Int
    ): List<Exercise> {
        var exercises = exerciseRepository.findAll()

        // 1. 회복 중인 근육 필터링
        val recentlyWorkedMuscles = getRecentlyWorkedMusclesVector(user, 48)
        val recoveringMuscles = getRecoveringMusclesVector(user)
        exercises = exercises.filter { exercise ->
            exercise.muscleGroups.none { it in recentlyWorkedMuscles || it in recoveringMuscles }
        }

        // 2. 주간 볼륨 필터링 (20세트 이상인 근육 회피)
        val weeklyVolume = getWeeklyVolumeMapVector(user)
        val overtrainedMuscles = weeklyVolume.filter { it.value > 20 }.keys
        exercises = exercises.filter { exercise ->
            exercise.muscleGroups.none { muscle ->
                overtrainedMuscles.contains(translateMuscleGroupToKoreanVector(muscle))
            }
        }

        // 3. 타겟 근육 필터링
        if (targetMuscle != null) {
            exercises = exercises.filter { exercise ->
                exercise.muscleGroups.any { muscle ->
                    muscle.name.contains(targetMuscle, ignoreCase = true)
                }
            }
        }

        // 4. 장비 필터링
        if (equipment != null) {
            exercises = exercises.filter { it.equipment?.name == equipment }
        }

        // 5. 운동 순서 정렬 (복합운동 → 고립운동, 큰 근육 → 작은 근육)
        exercises = orderExercisesByPriorityVector(exercises)

        return exercises.take(limit)
    }

    private fun getTargetMusclesForWorkoutType(workoutType: WorkoutType): List<String> {
        return when (workoutType) {
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

    // ========== 헬스 트레이너 관점 헬퍼 메서드 ==========

    /**
     * 최근 N시간 내 운동한 근육군 조회
     * 48시간 = 회복 기간
     */
    private fun getRecentlyWorkedMusclesVector(user: User, hours: Int): Set<com.richjun.liftupai.domain.workout.entity.MuscleGroup> {
        return try {
            val cutoffTime = java.time.LocalDateTime.now().minusHours(hours.toLong())
            workoutSessionRepository
                .findByUserAndStartTimeAfter(user, cutoffTime)
                .flatMap { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                        .flatMap { it.exercise.muscleGroups }
                }
                .toSet()
        } catch (e: Exception) {
            println("⚠️ 최근 운동 근육 조회 실패: ${e.message}")
            emptySet()
        }
    }

    /**
     * MuscleRecovery 엔티티 기반 회복 중인 근육 조회
     * 회복률 80% 미만인 근육
     */
    private fun getRecoveringMusclesVector(user: User): Set<com.richjun.liftupai.domain.workout.entity.MuscleGroup> {
        return try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < 80 }
                .mapNotNull { recovery ->
                    try {
                        com.richjun.liftupai.domain.workout.entity.MuscleGroup.valueOf(recovery.muscleGroup.uppercase())
                    } catch (e: IllegalArgumentException) {
                        println("⚠️ MuscleGroup 변환 실패: ${recovery.muscleGroup}")
                        null
                    }
                }
                .toSet()
        } catch (e: Exception) {
            println("⚠️ MuscleRecovery 조회 실패: ${e.message}")
            emptySet()
        }
    }

    /**
     * MuscleGroup enum을 한국어로 변환
     */
    private fun translateMuscleGroupToKoreanVector(muscleGroup: com.richjun.liftupai.domain.workout.entity.MuscleGroup): String {
        return when (muscleGroup) {
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CHEST -> "가슴"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.BACK -> "등"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.SHOULDERS -> "어깨"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.BICEPS -> "이두"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.TRICEPS -> "삼두"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.LEGS -> "다리"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CORE -> "코어"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.ABS -> "복근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.GLUTES -> "둔근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CALVES -> "종아리"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.FOREARMS -> "전완"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.NECK -> "목"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.QUADRICEPS -> "대퇴사두"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.HAMSTRINGS -> "햄스트링"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.LATS -> "광배근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.TRAPS -> "승모근"
        }
    }

    /**
     * 주간 볼륨 계산 (특정 근육군의 최근 7일간 세트 수)
     */
    private fun calculateWeeklyVolumeVector(user: User, muscleGroup: com.richjun.liftupai.domain.workout.entity.MuscleGroup): Int {
        return try {
            val oneWeekAgo = java.time.LocalDateTime.now().minusDays(7)
            val sessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, oneWeekAgo)

            var totalSets = 0
            sessions.forEach { session ->
                val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .filter { workoutExercise ->
                        workoutExercise.exercise.muscleGroups.contains(muscleGroup)
                    }

                workoutExercises.forEach { workoutExercise ->
                    val sets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
                    totalSets += sets.size
                }
            }

            totalSets
        } catch (e: Exception) {
            println("⚠️ 주간 볼륨 계산 실패 (${muscleGroup.name}): ${e.message}")
            0
        }
    }

    /**
     * 모든 근육군의 주간 볼륨 맵 생성
     */
    private fun getWeeklyVolumeMapVector(user: User): Map<String, Int> {
        return try {
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.values()
                .associate { muscleGroup ->
                    translateMuscleGroupToKoreanVector(muscleGroup) to calculateWeeklyVolumeVector(user, muscleGroup)
                }
                .filter { it.value > 0 } // 볼륨이 0인 근육은 제외
        } catch (e: Exception) {
            println("⚠️ 주간 볼륨 맵 생성 실패: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 복합운동 여부 판단
     */
    private fun isCompoundExerciseVector(exercise: Exercise): Boolean {
        // 2개 이상의 근육군을 사용하면 복합운동
        if (exercise.muscleGroups.size >= 2) {
            return true
        }

        // 운동명 기반 판단
        val name = exercise.name.lowercase()
        val compoundKeywords = listOf(
            "프레스", "스쿼트", "데드리프트", "로우", "풀업", "친업",
            "딥스", "런지", "클린", "스내치", "푸시업"
        )

        return compoundKeywords.any { keyword -> name.contains(keyword) }
    }

    /**
     * 운동 순서 우선순위 정렬
     * 큰 근육 → 작은 근육, 복합운동 → 고립운동
     */
    private fun orderExercisesByPriorityVector(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(
            compareBy<Exercise> { exercise ->
                // 1순위: 근육 크기 (카테고리 기반)
                when (exercise.category) {
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.LEGS -> 1
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.BACK -> 2
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CHEST -> 3
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.SHOULDERS -> 4
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.ARMS -> 5
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CORE -> 6
                    com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CARDIO -> 7
                    else -> 8
                }
            }.thenBy { exercise ->
                // 2순위: 복합운동이 먼저
                if (isCompoundExerciseVector(exercise)) 0 else 1
            }
        )
    }
}

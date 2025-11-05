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
            // 최근 운동 필터링은 제거 (너무 엄격함)
            val recoveringMuscles = getRecoveringMusclesVector(user)
            val avoidMuscles = recoveringMuscles.map { translateMuscleGroupToKoreanVector(it) }

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

            // 추천 등급 필터링 (ESSENTIAL만 추천, 나머지는 검색 전용)
            // STANDARD, ADVANCED, SPECIALIZED는 검색에서만 노출
            val beforeTierFilter = exercises.size
            exercises = exercises.filter { it.recommendationTier == com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL }
            println("After recommendation tier filtering (ESSENTIAL only): ${exercises.size} exercises (filtered ${beforeTierFilter - exercises.size} non-essential exercises)")

            // 경험도별 난이도 필터링 (생소한 운동 방지)
            val experienceLevel = profile?.experienceLevel ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER
            val difficultyRange = when (experienceLevel) {
                com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER -> 1..40  // 초보자: 쉬운 운동만
                com.richjun.liftupai.domain.user.entity.ExperienceLevel.INTERMEDIATE -> 20..70  // 중급자: 넓은 범위
                com.richjun.liftupai.domain.user.entity.ExperienceLevel.ADVANCED,
                com.richjun.liftupai.domain.user.entity.ExperienceLevel.EXPERT -> 30..100  // 고급자: 모든 범위
                else -> 20..70
            }

            val beforeDifficultyFilter = exercises.size
            exercises = exercises.filter { it.difficulty in difficultyRange }
            println("After difficulty filtering: ${exercises.size} exercises (filtered ${beforeDifficultyFilter - exercises.size} too hard/easy)")

            // 인기도 기반 정렬 추가 (인기 있는 운동 우선)
            exercises = exercises.sortedWith(
                compareByDescending<Exercise> { it.isBasicExercise }  // 1순위: 기본 운동
                    .thenByDescending { it.popularity }  // 2순위: 인기도
                    .thenBy { it.difficulty }  // 3순위: 난이도 (쉬운 것 우선)
            )
            println("After popularity sorting: basic exercises and popular ones first")

            // 회복 중인 근육 필터링 (너무 엄격하지 않게: 주요 근육만 체크)
            val initialSize = exercises.size
            exercises = exercises.filter { exercise ->
                // 주요 근육 그룹(첫 번째)만 회복 체크
                val primaryMuscle = exercise.muscleGroups.firstOrNull()
                primaryMuscle == null || (primaryMuscle !in recoveringMuscles)
            }
            println("After recovery filtering: ${exercises.size} exercises (filtered ${initialSize - exercises.size} recovering muscles)")

            // 운동 순서 정렬 (복합운동 → 고립운동, 큰 근육 → 작은 근육)
            exercises = orderExercisesByPriorityVector(exercises)
            println("After ordering: exercises sorted by priority")

            // 유사 운동 필터링 (중복 방지)
            exercises = filterSimilarExercises(exercises)
            println("After similar exercise filtering: ${exercises.size} exercises (removed similar variants)")

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
        println("Fallback: Total exercises in DB: ${exercises.size}")

        // 1. 회복 중인 근육만 필터링 (최근 운동 제외)
        val recoveringMuscles = getRecoveringMusclesVector(user)
        if (recoveringMuscles.isNotEmpty()) {
            exercises = exercises.filter { exercise ->
                val primaryMuscle = exercise.muscleGroups.firstOrNull()
                primaryMuscle == null || (primaryMuscle !in recoveringMuscles)
            }
            println("Fallback: After recovery filtering: ${exercises.size}")
        }

        // 2. 타겟 근육 필터링 (있으면)
        if (targetMuscle != null) {
            val filtered = exercises.filter { exercise ->
                exercise.muscleGroups.any { muscle ->
                    muscle.name.contains(targetMuscle, ignoreCase = true)
                }
            }
            // 필터 결과가 있으면 적용, 없으면 무시
            if (filtered.isNotEmpty()) {
                exercises = filtered
                println("Fallback: After target muscle filtering: ${exercises.size}")
            } else {
                println("Fallback: Target muscle filter too strict, keeping all exercises")
            }
        }

        // 3. 장비 필터링 (있으면)
        if (equipment != null) {
            val filtered = exercises.filter { it.equipment?.name == equipment }
            // 필터 결과가 있으면 적용, 없으면 무시
            if (filtered.isNotEmpty()) {
                exercises = filtered
                println("Fallback: After equipment filtering: ${exercises.size}")
            } else {
                println("Fallback: Equipment filter too strict, keeping all exercises")
            }
        }

        // 4. 운동 순서 정렬 (복합운동 → 고립운동, 큰 근육 → 작은 근육)
        exercises = orderExercisesByPriorityVector(exercises)

        println("Fallback: Returning ${exercises.take(limit).size} exercises")
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

    /**
     * 유사한 운동들을 필터링하여 중복 방지
     * 예: "스모 데드리프트"와 "데드리프트"가 동시에 추천되지 않도록
     *
     * 전략:
     * 1. 기본 운동을 우선 선택
     * 2. 변형 운동은 기본 운동이 없을 때만 선택
     */
    private fun filterSimilarExercises(exercises: List<Exercise>): List<Exercise> {
        val result = mutableListOf<Exercise>()
        val usedBaseNames = mutableSetOf<String>()

        // 1단계: 기본 운동을 우선 선택
        exercises.filter { it.isBasicExercise }.forEach { exercise ->
            val baseName = extractBaseName(exercise.name)
            if (baseName !in usedBaseNames) {
                result.add(exercise)
                usedBaseNames.add(baseName)
            }
        }

        // 2단계: 남은 운동 중 중복되지 않은 것만 추가
        exercises.filterNot { it.isBasicExercise }.forEach { exercise ->
            val baseName = extractBaseName(exercise.name)
            if (baseName !in usedBaseNames) {
                result.add(exercise)
                usedBaseNames.add(baseName)
            }
        }

        return result
    }

    /**
     * 운동명에서 기본 이름 추출 (변형어 제거)
     * 예: "인클라인 바벨 벤치프레스" → "벤치프레스"
     *
     * 총 70개 이상의 변형어를 인식하여 중복 방지
     */
    private fun extractBaseName(name: String): String {
        // 변형어 목록 (카테고리별 정리)
        val modifiers = listOf(
            // 각도/위치 변형 (9개)
            "인클라인", "디클라인", "플로어", "45도", "90도",
            "하이", "로우", "미드", "업라이트",

            // 그립 변형 (13개)
            "클로즈그립", "와이드그립", "클로즈", "와이드",
            "오버핸드", "언더핸드", "리버스", "해머", "뉴트럴",
            "언더그립", "오버그립", "그립", "넓은",

            // 스탠스/포지션 변형 (14개)
            "스모", "컨벤셔널", "하이바", "로우바", "프론트", "백",
            "불가리안", "스플릿", "벤트오버", "시티드", "스탠딩",
            "싱글", "원레그", "포즈",

            // 장비 종류 (12개)
            "바벨", "덤벨", "케이블", "머신", "스미스머신", "케틀벨",
            "EZ-바", "T-바", "V-바", "로프", "체인", "밴드",

            // 데드리프트 변형 (4개)
            "루마니안", "스티프", "랙", "스내치",

            // 스쿼트 변형 (6개)
            "고블릿", "제르처", "오버헤드", "펜들럼", "해킹", "해크",
            "박스", "점프", "점핑",

            // 로우 변형 (5개)
            "펜들레이", "시일", "메도우", "크록", "체스트 서포티드",

            // 프레스 변형 (6개)
            "밀리터리", "비하인드 넥", "푸시", "저크", "아놀드", "길로틴",

            // 컬/익스텐션 변형 (7개)
            "프리처", "드래그", "컨센트레이션", "스파이더",
            "스컬크러셔", "얼터네이팅", "킥백",

            // 팔/다리 관련 (8개)
            "원암", "투암", "싱글암", "싱글레그", "레그",
            "동키", "피스톨", "시시",

            // 어깨/등 특수 (5개)
            "페이스", "리어", "사이드", "프론트", "래터럴",

            // 기타 변형 (10개)
            "글루트 햄", "어덕터", "어브덕터", "티비알",
            "해머스트렝스", "슈러그", "슈퍼맨", "인버티드",
            "리컴번트", "어시스티드", "월", "행", "에어"
        )

        var baseName = name

        // 괄호 제거 (예: "벤치프레스 (바벨)")
        baseName = baseName.replace(Regex("\\([^)]*\\)"), "").trim()

        // 변형어 제거
        modifiers.forEach { modifier ->
            baseName = baseName.replace(modifier, "", ignoreCase = true).trim()
        }

        // 연속된 공백 제거
        baseName = baseName.replace(Regex("\\s+"), " ").trim()

        // 빈 문자열이면 원본 반환
        if (baseName.isEmpty()) {
            return name.lowercase()
        }

        return baseName.lowercase()
    }
}

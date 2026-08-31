package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

/**
 * Shared ranking rules for recommendation ordering.
 */
object RecommendationExerciseRanking {
    private val generalRecommendationTiers = setOf(
        RecommendationTier.ESSENTIAL,
        RecommendationTier.STANDARD
    )

    private val compoundKeywords = listOf(
        "press",
        "squat",
        "deadlift",
        "row",
        "pullup",
        "chinup",
        "dip",
        "lunge"
    )

    /**
     * 장비 우선순위 — 부하(load)가 높은 장비가 메인 리프트로 적합
     * 바벨 > 덤벨 > 케이블 > 머신 > 기타
     */
    private fun equipmentPriority(exercise: Exercise): Int {
        return when (exercise.equipment) {
            Equipment.BARBELL -> 0
            Equipment.DUMBBELL -> 1
            Equipment.CABLE -> 2
            Equipment.MACHINE -> 3
            Equipment.KETTLEBELL -> 4
            Equipment.BODYWEIGHT -> 5
            Equipment.RESISTANCE_BAND -> 6
            else -> 7
        }
    }

    fun isCoreCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier == RecommendationTier.ESSENTIAL || exercise.isBasicExercise
    }

    fun isGeneralCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier in generalRecommendationTiers || exercise.isBasicExercise
    }

    /**
     * 같은 패턴 내에서 "대표 운동"을 선택하는 비교자.
     *
     * 선택 기준 (우선순위 순):
     * 1. ESSENTIAL > STANDARD (추천 등급)
     * 2. 복합운동 > 고립운동
     * 3. 바벨 > 덤벨 > 케이블 > 머신 (장비 — 부하 높은 장비가 메인 리프트)
     * 4. 인기도 높은 것 우선
     * 5. 난이도 높은 것 우선 (부하가 큰 정통 운동이 변형보다 난이도 높음)
     * 6. 이름 사전순 (최종 타이브레이킹)
     */
    fun patternSelectionComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { recommendationPriority(it) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { equipmentPriority(it) },
            { -it.popularity },
            { -it.difficulty },
            { it.name.length },
            { it.name }
        )
    }

    fun displayOrderComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { categoryPriority(it.category) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { recommendationPriority(it) },
            { equipmentPriority(it) },
            { -it.popularity },
            { -it.difficulty },
            { it.name }
        )
    }

    private fun recommendationPriority(exercise: Exercise): Int {
        return when {
            exercise.recommendationTier == RecommendationTier.ESSENTIAL && exercise.isBasicExercise -> 0
            exercise.recommendationTier == RecommendationTier.ESSENTIAL -> 1
            exercise.isBasicExercise -> 2
            exercise.recommendationTier == RecommendationTier.STANDARD -> 3
            else -> 4
        }
    }

    /**
     * 타겟 부위가 지정됐을 때 한 라운드에서 타겟 카테고리에 배정하는 슬롯 수.
     * 1이면 "가슴 데이 5개"가 가슴 1개 + 무관한 부위 4개가 되어버린다.
     */
    private const val TARGET_SLOTS_PER_ROUND = 3

    /** 타겟 부위와 함께 훈련하기 자연스러운 보조 부위 순서 (해부학적 인접성) */
    private fun supportingCategoryOrder(target: ExerciseCategory): List<ExerciseCategory> = when (target) {
        ExerciseCategory.CHEST -> listOf(ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE)
        ExerciseCategory.BACK -> listOf(ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS, ExerciseCategory.CORE)
        ExerciseCategory.SHOULDERS -> listOf(ExerciseCategory.ARMS, ExerciseCategory.CHEST, ExerciseCategory.CORE)
        ExerciseCategory.ARMS -> listOf(ExerciseCategory.SHOULDERS, ExerciseCategory.CHEST, ExerciseCategory.BACK)
        ExerciseCategory.LEGS -> listOf(ExerciseCategory.CORE, ExerciseCategory.BACK)
        ExerciseCategory.CORE -> listOf(ExerciseCategory.LEGS, ExerciseCategory.BACK)
        else -> emptyList()
    }

    private val DEFAULT_CATEGORY_ORDER = listOf(
        ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
        ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
        ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY
    )

    /**
     * 카테고리 균형 배분.
     *
     * - 카테고리 **안에서는 들어온 순서를 보존**한다. 여기서 재정렬하면 상위 단계의
     *   개인화(주간 볼륨) 또는 벡터 유사도 순서가 통째로 폐기된다.
     * - 타겟이 없으면(전신) 부위별로 하나씩 돌아가며 배치한다.
     * - 타겟이 있으면 타겟 부위에 라운드당 [TARGET_SLOTS_PER_ROUND] 슬롯을 주고,
     *   나머지는 해부학적으로 인접한 보조 부위부터 채운다. 그래야 "가슴 5개" 요청이
     *   가슴 3 + 어깨/삼두 2 처럼 실제 가슴 데이 구성이 된다.
     */
    fun balanceByCategory(
        exercises: List<Exercise>,
        targetCategory: ExerciseCategory? = null
    ): List<Exercise> {
        val queues = exercises
            .groupBy { it.category }
            .mapValues { (_, group) -> ArrayDeque(group) }

        val others = if (targetCategory == null) {
            DEFAULT_CATEGORY_ORDER
        } else {
            val supporting = supportingCategoryOrder(targetCategory)
            (supporting + DEFAULT_CATEGORY_ORDER).distinct().filter { it != targetCategory }
        }

        val result = mutableListOf<Exercise>()
        var progressed = true
        while (progressed) {
            progressed = false
            if (targetCategory != null) {
                val queue = queues[targetCategory]
                repeat(TARGET_SLOTS_PER_ROUND) {
                    if (queue != null && queue.isNotEmpty()) {
                        result.add(queue.removeFirst())
                        progressed = true
                    }
                }
            }
            for (category in others) {
                val queue = queues[category]
                if (queue != null && queue.isNotEmpty()) {
                    result.add(queue.removeFirst())
                    progressed = true
                }
            }
        }
        return result
    }

    private fun categoryPriority(category: ExerciseCategory): Int {
        return when (category) {
            ExerciseCategory.LEGS -> 1
            ExerciseCategory.BACK -> 2
            ExerciseCategory.CHEST -> 3
            ExerciseCategory.SHOULDERS -> 4
            ExerciseCategory.ARMS -> 5
            ExerciseCategory.CORE -> 6
            ExerciseCategory.CARDIO -> 7
            ExerciseCategory.FULL_BODY -> 8
        }
    }

    private fun isCompoundExercise(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true

        val name = exercise.name.lowercase()
        return compoundKeywords.any { keyword -> name.contains(keyword) }
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.dto.ExerciseDetailV4
import com.richjun.liftupai.domain.user.dto.SetDetail
import com.richjun.liftupai.domain.workout.entity.*
import kotlin.math.roundToInt

// 확장 함수와 헬퍼 메서드들
fun WorkoutPlanService.calculateWeightForUser(
    user: User?,
    exercise: Exercise,
    template: ExerciseTemplate,
    bodyWeight: Double
): Double {
    // 1. 사용자 개인 기록 확인
    user?.let {
        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)
        personalRecord?.let {
            // 개인 기록의 80-85% 사용
            return it.weight * 0.8
        }
    }

    // 2. 템플릿의 체중 비율 사용
    template.weightPercentage?.let { percentage ->
        return bodyWeight * percentage
    }

    // 3. 카테고리별 기본 체중 비율
    return when (exercise.category) {
        ExerciseCategory.CHEST -> bodyWeight * 0.5
        ExerciseCategory.BACK -> bodyWeight * 0.6
        ExerciseCategory.LEGS -> bodyWeight * 0.8
        ExerciseCategory.SHOULDERS -> bodyWeight * 0.3
        ExerciseCategory.ARMS -> bodyWeight * 0.2
        ExerciseCategory.CORE -> 0.0 // 맨몸
        else -> bodyWeight * 0.4
    }
}

fun WorkoutPlanService.createSetsFromTemplate(
    template: ExerciseTemplate,
    weight: Double
): List<SetDetail> {
    val sets = mutableListOf<SetDetail>()
    val repsRange = (template.minReps..template.maxReps)

    // 웜업 세트 추가
    if (template.setType == SetType.WORKING && weight > 20) {
        sets.add(SetDetail(
            setNumber = 1,
            reps = template.maxReps,
            weight = weight * 0.5,
            type = "warm_up"
        ))
    }

    // 워킹 세트 추가
    val startingSetNumber = if (sets.isNotEmpty()) 2 else 1
    for (i in 0 until template.sets) {
        val setReps = if (i == 0) {
            template.maxReps
        } else {
            // 세트가 진행될수록 반복수 감소
            (template.maxReps - i).coerceAtLeast(template.minReps)
        }

        sets.add(SetDetail(
            setNumber = startingSetNumber + i,
            reps = setReps,
            weight = weight + (i * 5), // 점진적 중량 증가
            type = "working"
        ))
    }

    return sets
}

fun WorkoutPlanService.getExercisesForCategory(
    category: ExerciseCategory,
    limit: Int = 3
): List<Exercise> {
    val allExercises = exerciseRepository.findByCategory(category)

    // 다양성을 위해 랜덤하게 선택
    return allExercises.shuffled().take(limit)
}

fun WorkoutPlanService.getUserRecentWorkoutData(
    userId: Long,
    exerciseId: Long,
    days: Int = 30
): Pair<Double?, Int?> {
    // 최근 운동 기록에서 평균 중량과 반복수 계산
    val recentWorkouts = exerciseSetRepository.findRecentByUserAndExercise(
        userId, exerciseId, days
    )

    if (recentWorkouts.isEmpty()) {
        return Pair(null, null)
    }

    val avgWeight = recentWorkouts.map { it.weight }.average()
    val avgReps = recentWorkouts.map { it.reps }.average().roundToInt()

    return Pair(avgWeight, avgReps)
}
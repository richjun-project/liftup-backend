package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

@Service
class WeightRecommendationService(
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userProfileRepository: UserProfileRepository
) {
    private val log = LoggerFactory.getLogger(WeightRecommendationService::class.java)

    /**
     * 추천 무게 계산. 항상 non-null 반환.
     * 1순위: 개인 기록 기반 (Brzycki)
     * 2순위: 체중 비율 기반 기본 추천
     */
    fun calculateSuggestedWeight(userId: Long, exerciseId: Long, targetReps: Int): Double {
        // 1순위: 개인 기록 기반
        val fromPR = calculateFromPR(userId, exerciseId, targetReps)
        if (fromPR != null) return fromPR

        // 2순위: 운동 특성 + 사용자 체중 기반
        val exercise = exerciseRepository.findById(exerciseId).orElse(null)
            ?: return 20.0 // 운동 정보 없으면 최소 기본값

        return calculateDefaultWeight(userId, exercise)
    }

    private fun calculateFromPR(userId: Long, exerciseId: Long, targetReps: Int): Double? {
        val pr = personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(userId, exerciseId)
            ?: return null

        if (pr.reps >= 37 || targetReps >= 37) return null

        val estimated1RM = if (pr.reps <= 10) {
            pr.weight * (36.0 / (37.0 - pr.reps))
        } else {
            pr.weight * (1 + pr.reps / 30.0)
        }

        val workingWeight = estimated1RM * (37.0 - targetReps) / 36.0
        return roundTo2_5(workingWeight)
    }

    /**
     * 체중 비율 기반 초보자 기본 추천 무게.
     * 장비/부위별로 체중 대비 비율이 다름.
     */
    private fun calculateDefaultWeight(userId: Long, exercise: Exercise): Double {
        val bodyWeight = userProfileRepository.findByUser_Id(userId).orElse(null)
            ?.bodyInfo?.weight ?: 70.0

        val ratio = getDefaultWeightRatio(exercise)
        val raw = bodyWeight * ratio

        if (raw <= 0.0) return 0.0

        return roundTo2_5(raw).coerceAtLeast(2.5) // 최소 2.5kg
    }

    /**
     * 운동 특성별 체중 대비 추천 비율 (초보자 기준).
     * 예: 바벨 벤치프레스 = 체중 × 0.4 → 70kg 사람 = 30kg (빈 바 수준)
     */
    private fun getDefaultWeightRatio(exercise: Exercise): Double {
        // 맨몸 운동은 무게 0 (체중 자체가 부하)
        if (exercise.equipment == Equipment.BODYWEIGHT) return 0.0

        return when (exercise.category) {
            ExerciseCategory.LEGS -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.5   // 바벨 스쿼트: 70kg → 35kg
                Equipment.MACHINE -> 0.6   // 레그프레스: 70kg → 42.5kg
                Equipment.DUMBBELL -> 0.15 // 덤벨 런지: 70kg → 10kg (한 손)
                else -> 0.3
            }
            ExerciseCategory.CHEST -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.4   // 벤치프레스: 70kg → 30kg
                Equipment.DUMBBELL -> 0.15 // 덤벨프레스: 70kg → 10kg (한 손)
                Equipment.MACHINE -> 0.3   // 체스트프레스: 70kg → 22.5kg
                Equipment.CABLE -> 0.15
                else -> 0.2
            }
            ExerciseCategory.BACK -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.4   // 바벨로우: 70kg → 30kg
                Equipment.CABLE -> 0.35    // 랫풀다운: 70kg → 25kg
                Equipment.DUMBBELL -> 0.15
                Equipment.MACHINE -> 0.35
                else -> 0.25
            }
            ExerciseCategory.SHOULDERS -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.25  // 밀리터리프레스: 70kg → 17.5kg
                Equipment.DUMBBELL -> 0.1  // 숄더프레스: 70kg → 7.5kg (한 손)
                Equipment.CABLE -> 0.1
                else -> 0.15
            }
            ExerciseCategory.ARMS -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.2   // 바벨컬: 70kg → 15kg
                Equipment.DUMBBELL -> 0.1  // 덤벨컬: 70kg → 7.5kg
                Equipment.CABLE -> 0.15
                else -> 0.1
            }
            ExerciseCategory.CORE -> 0.0   // 코어 운동은 보통 맨몸
            ExerciseCategory.CARDIO -> 0.0
            ExerciseCategory.FULL_BODY -> when (exercise.equipment) {
                Equipment.BARBELL -> 0.35  // 데드리프트 등
                Equipment.KETTLEBELL -> 0.15
                else -> 0.2
            }
        }
    }

    private fun roundTo2_5(value: Double): Double {
        return (value / 2.5).roundToInt() * 2.5
    }
}

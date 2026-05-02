package com.richjun.liftupai.domain.nutrition.util

import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.UserProfile

/**
 * 사용자 프로필 기반 일일 칼로리/매크로 타깃 계산.
 * 기존 GeminiAIService의 private 메서드를 단일 책임 util로 추출.
 *
 * BMR: Mifflin-St Jeor (성별 분기), TDEE = BMR × 1.55 (좌식 활동 계수).
 * 운동 칼로리 합산은 calculateTargetCalories에서 옵션으로 처리.
 */
object NutritionTargetCalculator {

    private const val DEFAULT_TDEE = 2000
    private const val MIN_TARGET_CALORIES = 1200
    private const val ACTIVITY_FACTOR = 1.55

    fun calculateBmi(weight: Double?, height: Double?): Double? {
        if (weight == null || height == null || height == 0.0) return null
        val heightMeters = height / 100.0
        return weight / (heightMeters * heightMeters)
    }

    fun calculateTDEE(profile: UserProfile?): Int? {
        profile ?: return null
        val bodyInfo = profile.bodyInfo ?: return null
        val weight = bodyInfo.weight ?: return null
        val height = bodyInfo.height ?: return null
        val age = profile.age ?: 30

        val bmr = if (profile.gender == "male" || profile.gender == null) {
            88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age)
        } else {
            447.593 + (9.247 * weight) + (3.098 * height) - (4.330 * age)
        }

        return (bmr * ACTIVITY_FACTOR).toInt()
    }

    /**
     * 일일 권장 칼로리. workoutBurnedToday > 0이면 그만큼 합산해 반환.
     * (헬스앱 표준: 운동으로 소비한 만큼 추가 섭취 권장)
     */
    fun calculateTargetCalories(profile: UserProfile?, workoutBurnedToday: Int = 0): Int {
        val tdee = calculateTDEE(profile) ?: DEFAULT_TDEE
        val goals = profile?.goals ?: emptySet()
        val baseTarget = when {
            FitnessGoal.WEIGHT_LOSS in goals -> (tdee - 500).coerceAtLeast(MIN_TARGET_CALORIES)
            FitnessGoal.MUSCLE_GAIN in goals -> tdee + 300
            else -> tdee
        }
        return baseTarget + workoutBurnedToday.coerceAtLeast(0)
    }

    fun calculateProteinTarget(profile: UserProfile?): Double {
        val goals = profile?.goals ?: emptySet()
        val weight = profile?.bodyInfo?.weight
        return when {
            FitnessGoal.MUSCLE_GAIN in goals -> weight?.times(2.2) ?: 80.0
            FitnessGoal.WEIGHT_LOSS in goals -> weight?.times(2.0) ?: 70.0
            else -> weight?.times(1.5) ?: 60.0
        }
    }

    fun calculateCarbTarget(profile: UserProfile?, calories: Int): Double {
        val goals = profile?.goals ?: emptySet()
        val carbRatio = when {
            FitnessGoal.ENDURANCE in goals -> 0.5
            FitnessGoal.WEIGHT_LOSS in goals -> 0.35
            else -> 0.4
        }
        return calories * carbRatio / 4.0
    }

    fun calculateFatTarget(calories: Int): Double {
        return calories * 0.25 / 9.0
    }
}

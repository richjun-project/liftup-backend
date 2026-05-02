package com.richjun.liftupai.domain.nutrition.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate

/**
 * 사용자별 일일 영양/운동 컨텍스트 단일 source.
 * 채팅 AI 프롬프트, today-summary API, 동적 PT 알림이 모두 여기서 데이터 가져감.
 */
@Service
@Transactional(readOnly = true)
class NutritionContextService(
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val mealLogRepository: MealLogRepository,
    private val workoutSessionRepository: WorkoutSessionRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun buildTodayContext(user: User): DailyNutritionContext {
        val zoneId = userSettingsRepository.findByUser(user).orElse(null)?.timeZone
            ?.let { AppTime.resolveZoneId(it) }
            ?: AppTime.resolveZoneId(null)

        val today = AppTime.currentUserDate(zoneId)
        val (startUtc, endUtc) = AppTime.utcRangeForLocalDate(today, zoneId)

        val profile = userProfileRepository.findByUser(user).orElse(null)

        // 운동 칼로리 합산 — caloriesBurned가 null이면 0 처리
        val workouts = workoutSessionRepository.findByUserAndStartTimeBetween(user, startUtc, endUtc)
        val workoutBurned = workouts.sumOf { it.caloriesBurned ?: 0 }

        // 식단 기록
        val meals = mealLogRepository.findByUserAndTimestampBetween(user, startUtc, endUtc)
        val mealsByType: Map<MealType, List<MealLog>> = MealType.values()
            .associateWith { type -> meals.filter { it.mealType == type } }

        val consumedKcal = meals.sumOf { it.calories }
        val consumedMacros = Macros(
            carbs = meals.sumOf { it.carbs }.toInt(),
            protein = meals.sumOf { it.protein }.toInt(),
            fat = meals.sumOf { it.fat }.toInt()
        )

        // 타깃 (운동 칼로리 합산 적용)
        val targetKcal = NutritionTargetCalculator.calculateTargetCalories(profile, workoutBurned)
        val targetMacros = Macros(
            carbs = NutritionTargetCalculator.calculateCarbTarget(profile, targetKcal).toInt(),
            protein = NutritionTargetCalculator.calculateProteinTarget(profile).toInt(),
            fat = NutritionTargetCalculator.calculateFatTarget(targetKcal).toInt()
        )

        // 마지막 운동으로부터 경과 시간 (시간 단위)
        val lastWorkout = workouts.maxByOrNull { it.startTime }
        val hoursSinceLastWorkout = lastWorkout?.let {
            Duration.between(it.startTime, AppTime.utcNow()).toHours()
        }

        return DailyNutritionContext(
            date = today,
            targetKcal = targetKcal,
            consumedKcal = consumedKcal,
            remainingKcal = (targetKcal - consumedKcal).coerceAtLeast(0),
            targetMacros = targetMacros,
            consumedMacros = consumedMacros,
            mealsByType = mealsByType,
            workoutBurnedKcal = workoutBurned,
            hoursSinceLastWorkout = hoursSinceLastWorkout
        )
    }
}

data class DailyNutritionContext(
    val date: LocalDate,
    val targetKcal: Int,
    val consumedKcal: Int,
    val remainingKcal: Int,
    val targetMacros: Macros,
    val consumedMacros: Macros,
    val mealsByType: Map<MealType, List<MealLog>>,
    val workoutBurnedKcal: Int,
    val hoursSinceLastWorkout: Long?
) {
    val progressPercent: Int
        get() = if (targetKcal == 0) 0 else (consumedKcal * 100 / targetKcal)
}

data class Macros(
    val carbs: Int,
    val protein: Int,
    val fat: Int
)

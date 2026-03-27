package com.richjun.liftupai.domain.recovery.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.recovery.dto.*
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivity
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivityType
import com.richjun.liftupai.domain.recovery.entity.RecoveryIntensity
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.recovery.repository.RecoveryActivityRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Service
@Transactional
class RecoveryService(
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val recoveryActivityRepository: RecoveryActivityRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun getRecoveryStatus(userId: Long, localeOverride: String? = null): RecoveryStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        logger.debug("getRecoveryStatus - userId: {}", userId)

        updateRecoveryPercentages(user)

        val muscles = muscleRecoveryRepository.findByUser(user)
        logger.debug("getRecoveryStatus - found {} muscle recovery records", muscles.size)

        val muscleStatuses = muscles.map { muscle ->
            toMuscleRecoveryStatus(muscle, locale)
        }.plus(getDefaultMuscleGroups(muscles, locale))

        logger.debug("getRecoveryStatus - total muscle statuses: {}", muscleStatuses.size)

        return RecoveryStatusResponse(muscles = muscleStatuses)
    }

    fun updateRecovery(userId: Long, request: UpdateRecoveryRequest, localeOverride: String? = null): UpdateRecoveryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)
        val muscleKey = canonicalMuscleKey(request.muscleGroup)

        val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, muscleKey)
            .orElseGet {
                MuscleRecovery(
                    user = user,
                    muscleGroup = muscleKey,
                    lastWorked = AppTime.utcNow()
                )
            }

        muscleRecovery.feelingScore = request.feelingScore.coerceIn(1, 10)
        muscleRecovery.soreness = request.soreness.coerceIn(0, 10)
        muscleRecovery.recoveryPercentage = calculateRecoveryPercentage(
            muscleRecovery.lastWorked,
            request.feelingScore,
            request.soreness
        )
        muscleRecovery.updatedAt = AppTime.utcNow()

        val saved = muscleRecoveryRepository.save(muscleRecovery)

        return UpdateRecoveryResponse(
            success = true,
            updatedStatus = toMuscleRecoveryStatus(saved, locale)
        )
    }

    @Transactional(readOnly = true)
    fun getRecoveryRecommendations(userId: Long, localeOverride: String? = null): RecoveryRecommendationsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        val readyMuscles = muscleRecoveryRepository.findReadyMuscles(user, 80)
            .map { displayMuscleName(it.muscleGroup, locale) }

        val recoveryExercises = generateRecoveryExercises(user, locale)
        val nutritionTips = generateNutritionTips(locale)

        return RecoveryRecommendationsResponse(
            readyMuscles = readyMuscles.ifEmpty { getDefaultReadyMuscles(locale) },
            recoveryExercises = recoveryExercises,
            nutritionTips = nutritionTips
        )
    }

    fun updateMuscleWorkout(userId: Long, muscleGroup: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val muscleKey = canonicalMuscleKey(muscleGroup)

        val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, muscleKey)
            .orElseGet {
                MuscleRecovery(
                    user = user,
                    muscleGroup = muscleKey,
                    lastWorked = AppTime.utcNow()
                )
            }

        muscleRecovery.lastWorked = AppTime.utcNow()
        muscleRecovery.recoveryPercentage = 0
        muscleRecovery.updatedAt = AppTime.utcNow()

        muscleRecoveryRepository.save(muscleRecovery)
    }

    // 운동 완료 시 여러 근육 그룹 업데이트
    fun updateMuscleWorkoutForExercise(userId: Long, exercise: com.richjun.liftupai.domain.workout.entity.Exercise) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        exercise.muscleGroups.forEach { muscleEnum ->
            updateMuscleWorkout(userId, muscleEnum.name.lowercase())
        }

        if (exercise.muscleGroups.isEmpty()) {
            val categoryMuscle = mapCategoryToMuscleGroup(exercise.category)
            updateMuscleWorkout(userId, categoryMuscle)
        }
    }

    private fun mapCategoryToMuscleGroup(category: com.richjun.liftupai.domain.workout.entity.ExerciseCategory): String {
        return WorkoutTargetResolver.focusForCategory(category)
            ?.let { WorkoutTargetResolver.key(it) }
            ?: "full_body"
    }

    private fun updateRecoveryPercentages(user: com.richjun.liftupai.domain.auth.entity.User) {
        val muscles = muscleRecoveryRepository.findByUser(user)
        if (muscles.isEmpty()) return

        muscles.forEach { muscle ->
            val hoursSinceWorkout = ChronoUnit.HOURS.between(muscle.lastWorked, AppTime.utcNow())
            val baseRecovery = calculateBaseRecovery(hoursSinceWorkout, muscle.muscleGroup)
            val adjustedRecovery = adjustRecoveryBySoreness(baseRecovery, muscle.soreness)
            muscle.recoveryPercentage = minOf(adjustedRecovery, 100)
        }

        // 배치 저장 — 루프 내 개별 save() 대신 한 번에 저장
        muscleRecoveryRepository.saveAll(muscles)
    }

    /**
     * 근육별 차별화된 회복 곡선
     *
     * 과학적 근거:
     * - 대근육(대퇴사두, 둔근): 72-96시간 (NSCA Essentials)
     * - 중간 근육(가슴, 등, 어깨): 48-72시간
     * - 소근육(이두, 삼두, 종아리): 24-48시간
     * - 복근/코어: 24시간 (높은 지근 비율)
     */
    /**
     * 연속 S자 회복 곡선 — smoothstep 보간으로 경계값 불연속 제거
     *
     * smoothstep: 3t² - 2t³
     * - ratio 0.0 → 0% (운동 직후)
     * - ratio 0.5 → 50% (중간 회복)
     * - ratio 1.0 → 100% (완전 회복)
     * - 경계에서 기울기 연속 (불연속 점프 없음)
     */
    private fun calculateBaseRecovery(hoursSinceWorkout: Long, muscleGroup: String? = null): Int {
        val fullRecoveryHours = getFullRecoveryHours(muscleGroup)
        val t = (hoursSinceWorkout.toDouble() / fullRecoveryHours).coerceIn(0.0, 1.0)
        val smooth = t * t * (3 - 2 * t)
        return (smooth * 100).toInt().coerceIn(0, 100)
    }

    private fun getFullRecoveryHours(muscleGroup: String?): Int {
        if (muscleGroup == null) return 48  // 기본값

        return when (muscleGroup.lowercase()) {
            // 대근육: 72-96시간
            "quadriceps", "hamstrings", "glutes" -> 84
            "legs" -> 84
            // 중간 근육: 48-72시간
            "chest", "back", "lats" -> 60
            "shoulders", "traps" -> 56
            // 소근육: 24-48시간
            "biceps", "triceps" -> 36
            "forearms" -> 36
            "calves" -> 36
            "neck" -> 36
            // 코어: 24시간
            "abs", "core" -> 24
            else -> 48
        }
    }

    private fun adjustRecoveryBySoreness(baseRecovery: Int, soreness: Int): Int {
        val clampedSoreness = soreness.coerceIn(0, 10)
        val sorenessMultiplier = (1.0 - (clampedSoreness * 0.015)).coerceIn(0.0, 1.0)
        return (baseRecovery * sorenessMultiplier).toInt().coerceIn(0, 100)
    }

    /**
     * 수동 업데이트 시 회복률 계산 — updateRecoveryPercentages()와 동일 공식 기반
     *
     * feeling은 가산 보정(±15%)으로 적용:
     * - feeling 5 (보통) → 0% 보정
     * - feeling 10 (매우 좋음) → +15%
     * - feeling 1 (매우 나쁨) → -12%
     *
     * 기존 곱셈 방식(feeling/10)은 feeling=1일 때 회복률이 10%로 급락하는 문제가 있었음
     */
    private fun calculateRecoveryPercentage(lastWorked: LocalDateTime, feelingScore: Int, soreness: Int): Int {
        val hoursSinceWorkout = ChronoUnit.HOURS.between(lastWorked, AppTime.utcNow())
        val baseRecovery = calculateBaseRecovery(hoursSinceWorkout)
        val afterSoreness = adjustRecoveryBySoreness(baseRecovery, soreness)

        val clampedFeeling = feelingScore.coerceIn(1, 10)
        val feelingBonus = ((clampedFeeling - 5) * 3).coerceIn(-12, 15)

        return (afterSoreness + feelingBonus).coerceIn(0, 100)
    }

    /**
     * 남은 회복 시간 추정 — smoothstep 역함수 기반
     *
     * smoothstep: recovery = 3t² - 2t³ (t = hours / fullRecoveryHours)
     * 현재 회복률에서 100%까지 남은 시간을 근육별 fullRecoveryHours 기준으로 계산
     */
    private fun calculateEstimatedRecoveryTime(muscle: MuscleRecovery): Int {
        if (muscle.recoveryPercentage >= 100) return 0

        val fullRecoveryHours = getFullRecoveryHours(muscle.muscleGroup)
        val currentRecovery = muscle.recoveryPercentage.coerceIn(0, 99) / 100.0

        // smoothstep 역함수: f(t) = 3t² - 2t³ 에서 f(t) = currentRecovery 일 때의 t를 구함
        // 해석적 역함수가 복잡하므로 이진 탐색 사용 (20회 → 오차 < 0.0001%)
        var low = 0.0
        var high = 1.0
        repeat(20) {
            val mid = (low + high) / 2
            val smoothAtMid = mid * mid * (3 - 2 * mid)
            if (smoothAtMid < currentRecovery) low = mid else high = mid
        }
        val currentT = (low + high) / 2
        val remainingHours = ((1.0 - currentT) * fullRecoveryHours).toInt()

        return remainingHours.coerceAtLeast(0)
    }

    private fun determineRecoveryStatusKey(recoveryPercentage: Int): String {
        return when {
            recoveryPercentage >= 100 -> "full"
            recoveryPercentage >= 80 -> "ready"
            recoveryPercentage >= 60 -> "light"
            recoveryPercentage >= 40 -> "recovering"
            else -> "rest"
        }
    }

    private fun getDefaultMuscleGroups(existingMuscles: List<MuscleRecovery>, locale: String): List<MuscleRecoveryStatus> {
        val defaultGroups = listOf(
            "chest",
            "back",
            "shoulders",
            "biceps",
            "triceps",
            "legs",
            "core",
            "abs",
            "glutes",
            "calves",
            "forearms",
            "neck",
            "quadriceps",
            "hamstrings",
            "lats",
            "traps"
        )
        val existingGroups = existingMuscles.map { it.muscleGroup }.toSet()

        return defaultGroups.filter { it !in existingGroups }.map { group ->
            MuscleRecoveryStatus(
                code = group,
                name = displayMuscleName(group, locale),
                recoveryPercentage = 100,
                lastWorked = null,
                estimatedRecoveryTime = 0,
                status = WorkoutLocalization.message("recovery.status.full", locale)
            )
        }
    }

    private fun getDefaultReadyMuscles(locale: String): List<String> {
        return listOf("chest", "back", "shoulders", "arms", "legs", "abs")
            .map { displayMuscleName(it, locale) }
    }

    private fun generateRecoveryExercises(user: com.richjun.liftupai.domain.auth.entity.User, locale: String): List<RecoveryExercise> {
        val recoveringMuscles = muscleRecoveryRepository.findRecoveringMuscles(user)

        val exercises = mutableListOf<RecoveryExercise>()

        if (recoveringMuscles.any { it.soreness >= 5 }) {
            exercises.add(
                RecoveryExercise(
                    name = WorkoutLocalization.message("recovery.exercise.light_stretching.name", locale),
                    description = WorkoutLocalization.message("recovery.exercise.light_stretching.description", locale),
                    duration = 15,
                    type = WorkoutLocalization.message("recovery.exercise.light_stretching.type", locale)
                )
            )
        }

        exercises.addAll(listOf(
            RecoveryExercise(
                name = WorkoutLocalization.message("recovery.exercise.foam_rolling.name", locale),
                description = WorkoutLocalization.message("recovery.exercise.foam_rolling.description", locale),
                duration = 10,
                type = WorkoutLocalization.message("recovery.exercise.foam_rolling.type", locale)
            ),
            RecoveryExercise(
                name = WorkoutLocalization.message("recovery.exercise.light_cardio.name", locale),
                description = WorkoutLocalization.message("recovery.exercise.light_cardio.description", locale),
                duration = 25,
                type = WorkoutLocalization.message("recovery.exercise.light_cardio.type", locale)
            ),
            RecoveryExercise(
                name = WorkoutLocalization.message("recovery.exercise.yoga.name", locale),
                description = WorkoutLocalization.message("recovery.exercise.yoga.description", locale),
                duration = 30,
                type = WorkoutLocalization.message("recovery.exercise.yoga.type", locale)
            )
        ))

        return exercises
    }

    /**
     * 회복 활동 부스트 적용
     *
     * 시간 기반 상한선: smoothstep 곡선의 현재 기대 회복률을 넘지 못하도록 제한
     * → calculateBaseRecovery와 동일 공식으로 일관성 유지
     */
    fun boostMuscleRecovery(userId: Long, muscleGroup: String, boostPercentage: Int) {
        val muscleRecoveries = muscleRecoveryRepository.findByUser_Id(userId)
        val muscleKey = canonicalMuscleKey(muscleGroup)

        val recovery = muscleRecoveries.find {
            it.muscleGroup.equals(muscleKey, ignoreCase = true)
        } ?: return

        // 시간 기반 회복 상한선 — smoothstep 기대값 + 여유분(15%)으로 제한
        // 부스트가 시간 기반 자연 회복을 크게 초과하지 못하도록 함
        val hoursSinceWorkout = ChronoUnit.HOURS.between(recovery.lastWorked, AppTime.utcNow())
        val naturalRecovery = calculateBaseRecovery(hoursSinceWorkout, muscleKey)
        val maxRecoveryForTime = (naturalRecovery + 15).coerceAtMost(100)

        val newRecoveryPercentage = (recovery.recoveryPercentage + boostPercentage).coerceIn(0, maxRecoveryForTime)
        recovery.recoveryPercentage = newRecoveryPercentage
        recovery.updatedAt = AppTime.utcNow()

        muscleRecoveryRepository.save(recovery)
        logger.info("Boosted recovery for $muscleKey by $boostPercentage% (cap: $maxRecoveryForTime%) for user $userId")
    }

    private fun generateNutritionTips(locale: String): List<NutritionTip> {
        return listOf(
            NutritionTip(
                tip = WorkoutLocalization.message("recovery.nutrition.protein.tip", locale),
                reason = WorkoutLocalization.message("recovery.nutrition.protein.reason", locale)
            ),
            NutritionTip(
                tip = WorkoutLocalization.message("recovery.nutrition.hydration.tip", locale),
                reason = WorkoutLocalization.message("recovery.nutrition.hydration.reason", locale)
            ),
            NutritionTip(
                tip = WorkoutLocalization.message("recovery.nutrition.bcaa.tip", locale),
                reason = WorkoutLocalization.message("recovery.nutrition.bcaa.reason", locale)
            ),
            NutritionTip(
                tip = WorkoutLocalization.message("recovery.nutrition.sleep.tip", locale),
                reason = WorkoutLocalization.message("recovery.nutrition.sleep.reason", locale)
            ),
            NutritionTip(
                tip = WorkoutLocalization.message("recovery.nutrition.antioxidants.tip", locale),
                reason = WorkoutLocalization.message("recovery.nutrition.antioxidants.reason", locale)
            )
        )
    }

    // RecoveryActivityService methods
    fun recordActivity(userId: Long, request: RecordActivityRequest, localeOverride: String? = null): RecordActivityResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)
        val bodyPartKeys = request.bodyParts.map(::canonicalMuscleKey).toMutableSet()

        val recoveryScore = calculateRecoveryScore(request)
        val boostValue = calculateRecoveryBoostValue(request)
        val recoveryBoost = "+${boostValue}%"

        val activity = RecoveryActivity(
            user = user,
            activityType = request.activityType,
            duration = request.duration,
            intensity = request.intensity,
            notes = request.notes,
            bodyParts = bodyPartKeys,
            performedAt = request.performedAt,
            recoveryScore = recoveryScore,
            recoveryBoost = recoveryBoost
        )

        val saved = recoveryActivityRepository.save(activity)

        applyRecoveryBoost(userId, bodyPartKeys, boostValue)

        val nextRecommendation = generateNextRecommendation(request.activityType, locale)

        logger.info("Recovery activity recorded for user $userId: ${request.activityType}")

        return RecordActivityResponse(
            activityId = saved.id.toString(),
            recoveryScore = recoveryScore,
            recoveryBoost = recoveryBoost,
            nextRecommendation = nextRecommendation,
            recorded = true
        )
    }

    @Transactional(readOnly = true)
    fun getRecoveryHistory(
        userId: Long,
        startDate: String,
        endDate: String,
        activityType: String?,
        localeOverride: String? = null
    ): RecoveryHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        val start = java.time.LocalDate.parse(startDate).atStartOfDay()
        val end = java.time.LocalDate.parse(endDate).atTime(23, 59, 59)

        val activities = if (activityType == null || activityType == "all") {
            recoveryActivityRepository.findByUserAndPerformedAtBetween(user, start, end)
        } else {
            try {
                val type = RecoveryActivityType.valueOf(activityType.uppercase())
                recoveryActivityRepository.findByUserAndActivityType(user, type)
                    .filter { it.performedAt in start..end }
            } catch (e: IllegalArgumentException) {
                recoveryActivityRepository.findByUserAndPerformedAtBetween(user, start, end)
            }
        }

        // Group activities by date
        val groupedActivities = activities.groupBy { it.performedAt.toLocalDate() }

        val history = groupedActivities.map { (date, dayActivities) ->
            val dayScore = calculateDailyRecoveryScore(dayActivities)
            val muscleSoreness = getMuscleStressorForDate(userId, date, locale)

            DailyRecoveryHistory(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                activities = dayActivities.map { activity ->
                    RecoveryActivityDetail(
                        activityId = activity.id.toString(),
                        activityType = activity.activityType,
                        duration = activity.duration,
                        intensity = activity.intensity,
                        performedAt = activity.performedAt,
                        recoveryImpact = activity.recoveryBoost ?: "+0%"
                    )
                },
                dailyRecoveryScore = dayScore,
                muscleSoreness = muscleSoreness
            )
        }.sortedBy { it.date }

        val summary = createRecoverySummary(userId, activities, start, end)

        return RecoveryHistoryResponse(
            history = history,
            summary = summary
        )
    }

    private fun calculateRecoveryScore(request: RecordActivityRequest): Int {
        var score = 50 // Base score

        // Activity type bonus
        score += when (request.activityType) {
            RecoveryActivityType.SLEEP -> 30
            RecoveryActivityType.MASSAGE -> 25
            RecoveryActivityType.STRETCHING -> 20
            RecoveryActivityType.FOAM_ROLLING -> 20
            RecoveryActivityType.COLD_BATH -> 15
            RecoveryActivityType.SAUNA -> 15
        }

        // Duration bonus
        score += when {
            request.duration >= 60 -> 20
            request.duration >= 30 -> 15
            request.duration >= 15 -> 10
            else -> 5
        }

        // Intensity adjustment
        score += when (request.intensity) {
            RecoveryIntensity.INTENSE -> 10
            RecoveryIntensity.MODERATE -> 5
            RecoveryIntensity.LIGHT -> 0
        }

        return score.coerceIn(0, 100)
    }

    private fun calculateRecoveryBoostValue(request: RecordActivityRequest): Int {
        return when (request.activityType) {
            RecoveryActivityType.SLEEP -> if (request.duration >= 420) 15 else 10
            RecoveryActivityType.MASSAGE -> 12
            RecoveryActivityType.STRETCHING -> when (request.intensity) {
                RecoveryIntensity.INTENSE -> 8
                RecoveryIntensity.MODERATE -> 5
                RecoveryIntensity.LIGHT -> 3
            }
            RecoveryActivityType.FOAM_ROLLING -> 7
            RecoveryActivityType.COLD_BATH -> 6
            RecoveryActivityType.SAUNA -> 5
        }
    }

    private fun applyRecoveryBoost(userId: Long, bodyParts: Set<String>, boostValue: Int) {
        if (bodyParts.isEmpty()) return

        bodyParts.forEach { bodyPart ->
            boostMuscleRecovery(userId, bodyPart, boostValue.coerceIn(1, 50))
        }
    }

    private fun generateNextRecommendation(lastActivity: RecoveryActivityType, locale: String): String {
        return when (lastActivity) {
            RecoveryActivityType.STRETCHING -> WorkoutLocalization.message("recovery.next.stretching", locale)
            RecoveryActivityType.FOAM_ROLLING -> WorkoutLocalization.message("recovery.next.foam_rolling", locale)
            RecoveryActivityType.MASSAGE -> WorkoutLocalization.message("recovery.next.massage", locale)
            RecoveryActivityType.COLD_BATH -> WorkoutLocalization.message("recovery.next.cold_bath", locale)
            RecoveryActivityType.SAUNA -> WorkoutLocalization.message("recovery.next.sauna", locale)
            RecoveryActivityType.SLEEP -> WorkoutLocalization.message("recovery.next.sleep", locale)
        }
    }

    private fun calculateDailyRecoveryScore(activities: List<RecoveryActivity>): Int {
        if (activities.isEmpty()) return 50

        val averageScore = activities
            .mapNotNull { it.recoveryScore }
            .average()

        return if (averageScore.isNaN()) 50 else averageScore.toInt()
    }

    private fun getMuscleStressorForDate(userId: Long, date: java.time.LocalDate, locale: String): MuscleSoreness {
        val muscleRecoveries = muscleRecoveryRepository.findByUser_Id(userId)

        val details = muscleRecoveries.associate { recovery ->
            displayMuscleName(recovery.muscleGroup, locale) to (100 - recovery.recoveryPercentage).coerceIn(0, 10) / 10
        }

        val overall = if (details.isNotEmpty()) {
            details.values.average().toInt()
        } else {
            0
        }

        return MuscleSoreness(
            overall = overall,
            details = details
        )
    }

    private fun createRecoverySummary(
        userId: Long,
        activities: List<RecoveryActivity>,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): RecoverySummary {
        val totalActivities = activities.size

        val mostFrequent = if (activities.isNotEmpty()) {
            val frequencies = recoveryActivityRepository.findMostFrequentActivityType(userId, startDate, endDate)
            if (frequencies.isNotEmpty()) {
                frequencies.first()[0].toString().lowercase()
            } else {
                "none"
            }
        } else {
            "none"
        }

        val averageScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, startDate, endDate)
            ?.toInt() ?: 0

        val trend = determineTrend(userId, startDate, endDate)

        return RecoverySummary(
            totalActivities = totalActivities,
            mostFrequent = mostFrequent,
            averageRecoveryScore = averageScore,
            trend = trend
        )
    }

    private fun determineTrend(userId: Long, startDate: LocalDateTime, endDate: LocalDateTime): String {
        val midPoint = startDate.plusDays((endDate.toLocalDate().toEpochDay() - startDate.toLocalDate().toEpochDay()) / 2)

        val firstHalfScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, startDate, midPoint)
        val secondHalfScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, midPoint, endDate)

        return when {
            firstHalfScore == null || secondHalfScore == null -> "stable"
            secondHalfScore > firstHalfScore + 5 -> "improving"
            secondHalfScore < firstHalfScore - 5 -> "declining"
            else -> "stable"
        }
    }

    private fun resolveLocale(userId: Long, localeOverride: String?): String {
        if (!localeOverride.isNullOrBlank()) {
            return WorkoutLocalization.normalizeLocale(localeOverride)
        }

        val userLocale = userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        return WorkoutLocalization.normalizeLocale(userLocale)
    }

    private fun canonicalMuscleKey(raw: String): String {
        return WorkoutTargetResolver.resolveMuscleGroup(raw)?.name?.lowercase()
            ?: WorkoutTargetResolver.recommendationKey(raw)
            ?: raw.trim().lowercase().replace("-", "_").replace(" ", "_")
    }

    private fun displayMuscleName(raw: String, locale: String): String {
        return WorkoutLocalization.targetDisplayName(raw, locale)
    }

    private fun toMuscleRecoveryStatus(muscle: MuscleRecovery, locale: String): MuscleRecoveryStatus {
        val statusKey = determineRecoveryStatusKey(muscle.recoveryPercentage)
        return MuscleRecoveryStatus(
            code = canonicalMuscleKey(muscle.muscleGroup),
            name = displayMuscleName(muscle.muscleGroup, locale),
            recoveryPercentage = muscle.recoveryPercentage,
            lastWorked = muscle.lastWorked.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            estimatedRecoveryTime = calculateEstimatedRecoveryTime(muscle),
            status = WorkoutLocalization.message("recovery.status.$statusKey", locale)
        )
    }
}

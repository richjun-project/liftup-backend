package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class PlanWorkoutEnricher(
    private val exerciseRepository: ExerciseRepository,
    private val userPlanDayRepository: UserPlanDayRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val exerciseSubstitutionService: ExerciseSubstitutionService,
    private val exerciseTrainingProfileResolver: ExerciseTrainingProfileResolver,
    private val userProfileRepository: com.richjun.liftupai.domain.user.repository.UserProfileRepository,
    private val userSettingsRepository: com.richjun.liftupai.domain.user.repository.UserSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(PlanWorkoutEnricher::class.java)

    /**
     * Enrich a basic DayWorkoutResponse with:
     * 1. Week number & cycle tracking
     * 2. Deload week detection
     * 3. Periodization phase
     * 4. Readiness scoring (subjective + recent RPE)
     * 5. Age-based adjustments (weight multiplier, rest, extra warmup)
     * 6. Exercise substitutes
     * 7. Phase-adjusted sets/reps/rest
     * 8. Weekly volume status (MEV/MAV)
     */
    fun enrich(
        response: DayWorkoutResponse,
        plan: UserWorkoutPlan,
        userAge: Int?,
        subjectiveReadiness: Int? = null
    ): DayWorkoutResponse {
        // 1. Calculate week number from total completions across all days
        val allDays = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val totalCompletions = allDays.sumOf { it.totalCompletions }
        val weekNumber = (totalCompletions / plan.totalDays) + 1

        // 2. Deload week detection
        val isDeloadWeek = plan.deloadEveryNWeeks > 0 &&
            weekNumber > 1 &&
            (weekNumber % plan.deloadEveryNWeeks == 0)

        // 3. Periodization phase
        val periodizationPhase = resolvePeriodizationPhase(
            plan.progressionModel, weekNumber, response.dayNumber, isDeloadWeek
        )

        // 4. Readiness score (with recent RPE from workout history)
        val readiness = computeReadiness(plan.user, subjectiveReadiness, isDeloadWeek)

        // 5. Age multiplier
        val ageMultiplier = computeAgeMultiplier(userAge)
        val ageRestMultiplier = computeAgeRestMultiplier(userAge)

        // 6. Phase-based adjustments
        val phaseAdj = getPhaseAdjustment(plan.progressionModel, weekNumber, isDeloadWeek)

        // Pre-fetch all exercises for the day to avoid N+1 in findSubstitutes
        val exerciseIds = response.exercises.map { it.exerciseId }
        val exerciseMap = exerciseRepository.findAllById(exerciseIds).associateBy { it.id }

        // 7. Enrich each exercise
        val enrichedExercises = response.exercises.map { ex ->
            enrichExercise(ex, readiness, ageMultiplier, ageRestMultiplier, phaseAdj, isDeloadWeek, userAge, exerciseMap[ex.exerciseId])
        }

        // 7-b. 목표별 반복 범위 + 세션 시간 — 추천 경로와 같은 규칙(골든셋 2026-09-27: AI 플랜 35건 중
        //      근력 목표 6건이 보조 종목 10-12회, 10건이 요청 시간의 50~80% 분량)
        val profile = try { userProfileRepository.findByUser_Id(plan.user.id).orElse(null) } catch (e: Exception) { null }
        val settings = try { userSettingsRepository.findByUser_Id(plan.user.id).orElse(null) } catch (e: Exception) { null }
        val goalAdjusted = if (isDeloadWeek) enrichedExercises
            else applyGoalWindows(enrichedExercises, profile?.goals.orEmpty(), exerciseMap)
        val targetMinutes = settings?.workoutDuration ?: profile?.workoutDuration
        val (finalExercises, estimated) = fitToSessionDuration(goalAdjusted, targetMinutes)

        // 8. Weekly volume estimate
        val volumeStatus = estimateWeeklyVolume(plan.user, finalExercises)

        return response.copy(
            estimatedDuration = estimated ?: response.estimatedDuration,
            weekNumber = weekNumber,
            isDeloadWeek = isDeloadWeek,
            periodizationPhase = periodizationPhase,
            readinessScore = readiness,
            weeklyVolume = volumeStatus,
            exercises = finalExercises
        )
    }

    private fun applyGoalWindows(
        exercises: List<DayExerciseDetail>,
        goals: Set<com.richjun.liftupai.domain.user.entity.FitnessGoal>,
        exerciseMap: Map<Long, Exercise>,
    ): List<DayExerciseDetail> {
        if (goals.isEmpty()) return exercises
        return exercises.map { ex ->
            val entity = exerciseMap[ex.exerciseId] ?: return@map ex
            val window = exerciseTrainingProfileResolver.goalWindow(exerciseTrainingProfileResolver.resolveProfile(entity), goals)
                ?: return@map ex
            val fitted = exerciseTrainingProfileResolver.fitReps("${ex.minReps}-${ex.maxReps}", window)
            val parts = fitted.split("-").mapNotNull { it.trim().toIntOrNull() }
            val rest = window.maxRestSeconds?.let { ex.restSeconds.coerceAtMost(it) } ?: ex.restSeconds
            if (parts.size != 2) ex.copy(restSeconds = rest) else ex.copy(minReps = parts[0], maxReps = parts[1], restSeconds = rest)
        }
    }

    /** 종목은 그대로 두고 세트·휴식만 세션 시간에 맞춘다. @return (조정된 종목, 추정 분) */
    private fun fitToSessionDuration(exercises: List<DayExerciseDetail>, targetMinutes: Int?): Pair<List<DayExerciseDetail>, Int?> {
        if (targetMinutes == null || targetMinutes <= 0 || exercises.isEmpty()) return exercises to null
        val slots = exercises.map {
            com.richjun.liftupai.domain.workout.util.SessionDurationFitter.Slot(
                it.sets, "${it.minReps}-${it.maxReps}", it.restSeconds, (it.restSeconds * 0.75).roundToInt().coerceAtLeast(30).coerceAtMost(it.restSeconds)
            )
        }
        val fitted = com.richjun.liftupai.domain.workout.util.SessionDurationFitter.fit(slots, targetMinutes, allowDrop = false)
        val adjusted = exercises.zip(fitted).map { (ex, slot) -> ex.copy(sets = slot.sets, restSeconds = slot.restSeconds) }
        return adjusted to com.richjun.liftupai.domain.workout.util.SessionDurationFitter.estimateMinutes(fitted)
    }

    private fun resolvePeriodizationPhase(
        model: ProgressionModel, week: Int, dayInCycle: Int, isDeload: Boolean
    ): String {
        if (isDeload) return "DELOAD"
        return when (model) {
            ProgressionModel.LINEAR -> "LINEAR_PROGRESSION"
            ProgressionModel.UNDULATING -> when (dayInCycle % 3) {
                1 -> "HEAVY"
                2 -> "MEDIUM"
                else -> "LIGHT"
            }
            ProgressionModel.BLOCK -> {
                val weekInBlock = ((week - 1) % 7)
                when {
                    weekInBlock < 2 -> "ACCUMULATION"
                    weekInBlock < 4 -> "INTENSIFICATION"
                    weekInBlock < 6 -> "REALIZATION"
                    else -> "DELOAD"
                }
            }
        }
    }

    private fun computeReadiness(
        user: com.richjun.liftupai.domain.auth.entity.User,
        subjectiveReadiness: Int?,
        isDeload: Boolean
    ): ReadinessDto {
        var score = 1.0
        val factors = mutableListOf<String>()

        // Factor 1: Days since last workout (single query, no N+1)
        try {
            val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
                user, listOf(SessionStatus.COMPLETED)
            ).take(1)

            val lastSession = recentSessions.firstOrNull()
            if (lastSession != null) {
                val daysSinceLast = java.time.temporal.ChronoUnit.DAYS.between(
                    lastSession.startTime.toLocalDate(), java.time.LocalDate.now()
                )
                when {
                    daysSinceLast == 0L -> { score -= 0.1; factors.add("오늘 이미 운동함 - 회복 부족 가능") }
                    daysSinceLast == 1L -> { factors.add("적절한 회복 시간") }
                    daysSinceLast in 2..3 -> { factors.add("충분한 회복") }
                    daysSinceLast > 3 -> { score -= 0.05; factors.add("${daysSinceLast}일 공백 - 가볍게 시작 권장") }
                }
            } else {
                factors.add("정상 컨디션")
            }
        } catch (e: Exception) {
            log.debug("Could not compute readiness from session history: {}", e.message)
            factors.add("정상 컨디션")
        }

        // Factor 2: Subjective readiness
        if (subjectiveReadiness != null) {
            when (subjectiveReadiness) {
                1 -> { score -= 0.20; factors.add("컨디션 매우 나쁨") }
                2 -> { score -= 0.10; factors.add("컨디션 나쁨") }
                3 -> { factors.add("컨디션 보통") }
                4 -> { score += 0.05; factors.add("컨디션 좋음") }
                5 -> { score += 0.10; factors.add("컨디션 매우 좋음") }
            }
        }

        if (isDeload) {
            score -= 0.10
            factors.add("디로드 주간")
        }

        score = score.coerceIn(0.5, 1.1)

        val intensityMultiplier = when {
            score >= 1.0 -> 1.05
            score >= 0.95 -> 1.0
            score >= 0.85 -> 0.95
            score >= 0.75 -> 0.90
            else -> 0.85
        }

        return ReadinessDto(
            score = score,
            intensityMultiplier = intensityMultiplier,
            factors = factors.ifEmpty { listOf("기본 컨디션") }
        )
    }

    private fun computeAgeMultiplier(age: Int?): Double {
        if (age == null || age < 40) return 1.0
        return (1.0 - (age - 40) * 0.015).coerceAtLeast(0.70)
    }

    private fun computeAgeRestMultiplier(age: Int?): Double {
        if (age == null) return 1.0
        return when {
            age >= 50 -> 1.2
            age >= 40 -> 1.1
            else -> 1.0
        }
    }

    data class PhaseAdjustment(
        val setsMultiplier: Double = 1.0,
        val restMultiplier: Double = 1.0,
        val repRangeLow: Int? = null,
        val repRangeHigh: Int? = null
    )

    private fun getPhaseAdjustment(
        model: ProgressionModel, week: Int, isDeload: Boolean
    ): PhaseAdjustment {
        if (isDeload && model != ProgressionModel.BLOCK) {
            return PhaseAdjustment(setsMultiplier = 0.60, restMultiplier = 1.0)
        }

        return when (model) {
            ProgressionModel.BLOCK -> {
                val weekInBlock = ((week - 1) % 7)
                when {
                    weekInBlock < 2 -> PhaseAdjustment(1.0, 1.0, 10, 12)   // Accumulation
                    weekInBlock < 4 -> PhaseAdjustment(0.85, 1.2, 6, 8)    // Intensification
                    weekInBlock < 6 -> PhaseAdjustment(0.70, 1.5, 3, 5)    // Realization
                    else -> PhaseAdjustment(0.50, 1.0, 12, 15)             // Deload
                }
            }
            else -> PhaseAdjustment()
        }
    }

    private fun enrichExercise(
        ex: DayExerciseDetail,
        readiness: ReadinessDto,
        ageMultiplier: Double,
        ageRestMultiplier: Double,
        phaseAdj: PhaseAdjustment,
        isDeload: Boolean,
        userAge: Int?,
        prefetchedExercise: Exercise? = null
    ): DayExerciseDetail {
        // Adjust weight with readiness + age
        val adjustedWeight = ex.suggestedWeight?.let {
            val w = it * readiness.intensityMultiplier * ageMultiplier
            (w / 2.5).roundToInt() * 2.5
        }

        // Adjust sets based on phase
        val adjustedSets = (ex.sets * phaseAdj.setsMultiplier).roundToInt().coerceAtLeast(2)

        // Adjust reps based on phase (BLOCK model overrides)
        val adjustedMinReps = phaseAdj.repRangeLow ?: ex.minReps
        val adjustedMaxReps = phaseAdj.repRangeHigh ?: ex.maxReps

        // Adjust rest with phase + age
        val adjustedRest = (ex.restSeconds * phaseAdj.restMultiplier * ageRestMultiplier).roundToInt()

        // Regenerate warmups with adjusted weight
        val warmups = if (ex.isCompound && adjustedWeight != null && adjustedWeight > 20.0) {
            generateEnrichedWarmupSets(adjustedWeight, userAge)
        } else {
            ex.warmupSets
        }

        // Find substitutes (up to 3 alternatives) using pre-fetched exercise data
        val substitutes = findSubstitutes(ex.exerciseId, 3, prefetchedExercise)

        return ex.copy(
            suggestedWeight = adjustedWeight,
            sets = adjustedSets,
            minReps = adjustedMinReps,
            maxReps = adjustedMaxReps,
            restSeconds = adjustedRest,
            warmupSets = warmups,
            substitutes = substitutes
        )
    }

    private fun generateEnrichedWarmupSets(workingWeight: Double, age: Int?): List<WarmupSetDto> {
        val sets = mutableListOf<WarmupSetDto>()
        // Extra warmup for 50+ users
        if (age != null && age >= 50) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.20).roundToNearest(2.5), reps = 15))
        }
        sets.add(WarmupSetDto(weight = 20.0, reps = 10)) // Empty bar
        if (workingWeight > 40.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.50).roundToNearest(2.5), reps = 8))
        }
        if (workingWeight > 60.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.70).roundToNearest(2.5), reps = 5))
        }
        if (workingWeight > 80.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.85).roundToNearest(2.5), reps = 3))
        }
        return sets.sortedBy { it.weight }
    }

    private fun Double.roundToNearest(step: Double): Double {
        return (this / step).roundToInt() * step
    }

    /**
     * 대체 운동 후보를 전이 효과(transfer efficiency) 순으로 반환한다.
     *
     * findAlternativeExercises 는 `ORDER BY e.name` 이라 그대로 take(3) 하면 이름 알파벳
     * 최상위(숫자로 시작하는 생소한 변형)가 뽑힌다. 이미 있는 전이 점수 계산을 사용해
     * 실제로 비슷한 효과를 내는 운동이 위로 오게 한다.
     */
    private fun findSubstitutes(exerciseId: Long, limit: Int, prefetchedExercise: Exercise? = null): List<SubstituteExerciseDto> {
        val exercise = prefetchedExercise ?: return emptyList()
        if (exercise.muscleGroups.isEmpty()) return emptyList()

        val alternatives = exerciseRepository.findAlternativeExercises(
            exerciseId, exercise.category, exercise.muscleGroups.toList()
        )
            // 추천 가능한 운동만 (SPECIALIZED/노이즈 제외)
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .sortedWith(
                compareByDescending<Exercise> {
                    exerciseSubstitutionService.calculateTransferScore(exercise, it)
                }.thenByDescending { it.popularity }
                    .thenBy { it.id }
            )
            .take(limit)

        return alternatives.map { alt ->
            SubstituteExerciseDto(
                exerciseId = alt.id,
                exerciseName = alt.name,
                imageUrl = null,
                reason = if (alt.equipment != exercise.equipment) "EQUIPMENT" else "EQUIVALENT"
            )
        }
    }

    // MEV/MAV volume estimation using Israetel values
    // TODO: Implement batch query for weekly volume to avoid N+1 (sessions -> exercises -> sets).
    //       The MEV/MAV constants below are still useful as static reference data.
    private fun estimateWeeklyVolume(
        user: com.richjun.liftupai.domain.auth.entity.User,
        todayExercises: List<DayExerciseDetail>
    ): List<VolumeStatusDto> {
        return emptyList()
    }

    // MEV/MAV constants (Israetel) — mirrors ProgramProgressionService
    private fun getMEV(muscleGroup: String): Int = when (muscleGroup.lowercase()) {
        "chest" -> 8
        "back", "lats" -> 10
        "quadriceps", "quads", "legs" -> 8
        "hamstrings" -> 6
        "glutes" -> 6
        "shoulders" -> 8
        "biceps" -> 6
        "triceps" -> 6
        "core", "abs" -> 6
        "calves" -> 8
        "forearms" -> 4
        "rear_delts" -> 6
        "lateral_delts" -> 8
        else -> 8
    }

    private fun getMAV(muscleGroup: String): Int = when (muscleGroup.lowercase()) {
        "chest" -> 20
        "back", "lats" -> 25
        "quadriceps", "quads", "legs" -> 20
        "hamstrings" -> 16
        "glutes" -> 16
        "shoulders" -> 22
        "biceps" -> 14
        "triceps" -> 14
        "core", "abs" -> 16
        "calves" -> 16
        "forearms" -> 10
        "rear_delts" -> 18
        "lateral_delts" -> 22
        else -> 16
    }
}

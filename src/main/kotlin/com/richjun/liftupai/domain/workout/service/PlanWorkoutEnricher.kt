package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
    /** 장비·부상·경험·나이·세션 시간의 단일 출처 */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
    /** 인라인 대체 운동을 대체 후보 API 와 같은 순위(SubstituteRanker)로 뽑는다 */
    @Autowired(required = false)
    private val catalogCache: ExerciseCatalogCache? = null,
    @Autowired(required = false)
    private val injuryFilterService: InjuryFilterService? = null,
    @Autowired(required = false)
    private val localizationService: ExerciseCatalogLocalizationService? = null,
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
        subjectiveReadiness: Int? = null,
        locale: String = "ko",
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
        val readiness = computeReadiness(plan.user, subjectiveReadiness, isDeloadWeek, locale)

        // 5. Age rest multiplier. 무게는 나이로 다시 줄이지 않는다 — 기본값 표(WeightRecommendationService)가 이미 나이를 한 번 반영했고,
        //    기록(PR)·직접 입력 무게는 본인 실측이라 건드리지 않는다. 예전엔 같은 사람의 무게를 나이로 두 번 깎았다.
        val ageRestMultiplier = computeAgeRestMultiplier(userAge)

        // 6. Phase-based adjustments
        val phaseAdj = getPhaseAdjustment(plan.progressionModel, weekNumber, isDeloadWeek)

        // Pre-fetch all exercises for the day to avoid N+1 in findSubstitutes
        val exerciseIds = response.exercises.map { it.exerciseId }
        val exerciseMap = exerciseRepository.findAllById(exerciseIds).associateBy { it.id }

        val ctx = try { trainingContext.resolve(plan.user.id) } catch (e: Exception) { UserTrainingContextResolver.Context.EMPTY }
        val level = ctx.level
        val beginner = level != null && RecoRules.isBeginnerBand(level)
        val effectiveAge = userAge ?: ctx.age

        // 7. Enrich each exercise
        val enrichedExercises = response.exercises.map { ex ->
            enrichExercise(ex, readiness, ageRestMultiplier, phaseAdj, isDeloadWeek, effectiveAge, exerciseMap[ex.exerciseId])
        }

        // 7-b. 목표별 반복 범위 + 세션 시간 — 추천 경로와 같은 규칙(골든셋 2026-09-27: AI 플랜 35건 중
        //      근력 목표 6건이 보조 종목 10-12회, 10건이 요청 시간의 50~80% 분량)
        val profile = try { userProfileRepository.findByUser_Id(plan.user.id).orElse(null) } catch (e: Exception) { null }
        val goalAdjusted = if (isDeloadWeek) enrichedExercises
            else applyGoalWindows(enrichedExercises, profile?.goals.orEmpty(), exerciseMap)
        // 초보·노비스의 첫 4주: 주기화 단계(BLOCK 의 6-8회·3-5회)와 목표 창이 주력 복합을 낮은 반복으로 내리지 못하게 한다
        val protectedExercises = if (beginner && weekNumber <= BEGINNER_PROTECT_WEEKS) {
            goalAdjusted.map { ex -> exerciseMap[ex.exerciseId]?.let { protectBeginner(ex, it) } ?: ex }
        } else goalAdjusted
        val (finalExercises, estimated) = fitToSessionDuration(protectedExercises, ctx.sessionMinutes, exerciseMap, level, effectiveAge)

        // 7-c. 대체 운동: 대체 후보 API(C3)와 같은 순위·같은 장비/부상/수준 규칙, 사용자 언어 이름
        val withSubstitutes = finalExercises.map { ex ->
            ex.copy(substitutes = findSubstitutes(ex.exerciseId, 3, exerciseMap[ex.exerciseId], ctx, locale))
        }

        // 8. Weekly volume estimate
        val volumeStatus = estimateWeeklyVolume(plan.user, withSubstitutes)

        return response.copy(
            estimatedDuration = estimated ?: response.estimatedDuration,
            weekNumber = weekNumber,
            isDeloadWeek = isDeloadWeek,
            periodizationPhase = periodizationPhase,
            readinessScore = readiness,
            weeklyVolume = volumeStatus,
            exercises = withSubstitutes
        )
    }

    /** 초보 보호: 복합 8회 이상, 주력(바벨) 복합 3세트 이하·휴식 90초 이상 */
    private fun protectBeginner(ex: DayExerciseDetail, entity: Exercise): DayExerciseDetail {
        val rx = RecoRules.beginnerRx(entity, RecoRules.Rx(ex.sets, ex.minReps, ex.maxReps, ex.restSeconds), ex.isCompound)
        return if (rx.sets == ex.sets && rx.minReps == ex.minReps && rx.maxReps == ex.maxReps && rx.restSeconds == ex.restSeconds) ex
        else ex.copy(sets = rx.sets, minReps = rx.minReps, maxReps = rx.maxReps, restSeconds = rx.restSeconds)
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

    /**
     * 종목은 그대로 두고 세트·휴식만 세션 시간에 맞춘다(저장된 플랜의 종목을 화면에서 지우지 않는다). @return (조정된 종목, 추정 분)
     * 종목별 세트 상한(초보 주력 복합 3세트, 연령 상한)을 지킨다 — 시간을 채우려고 첫 스쿼트를 5세트로 올리지 않는다.
     */
    private fun fitToSessionDuration(
        exercises: List<DayExerciseDetail>,
        targetMinutes: Int?,
        exerciseMap: Map<Long, Exercise>,
        level: com.richjun.liftupai.domain.user.entity.ExperienceLevel?,
        age: Int?,
    ): Pair<List<DayExerciseDetail>, Int?> {
        if (targetMinutes == null || targetMinutes <= 0 || exercises.isEmpty()) return exercises to null
        val slots = exercises.map { ex ->
            val entity = exerciseMap[ex.exerciseId]
            val cap = if (entity != null) RecoRules.maxSetsFor(entity, level, age, ex.isCompound, targetMinutes) else 5
            val restUp = if (entity != null) RecoRules.maxRestFor(entity, ex.isCompound, ex.minReps) else if (ex.isCompound) 150 else 90
            SessionFit.Slot(
                sets = ex.sets, reps = "${ex.minReps}-${ex.maxReps}", restSeconds = ex.restSeconds,
                minRestSeconds = (ex.restSeconds * 0.75).roundToInt().coerceAtLeast(30).coerceAtMost(ex.restSeconds),
                maxRestSeconds = restUp.coerceAtLeast(ex.restSeconds), maxSets = cap.coerceAtLeast(ex.sets),
            )
        }
        val fitted = SessionFit.fit(slots, targetMinutes).slots
        val adjusted = exercises.zip(fitted).map { (ex, slot) -> ex.copy(sets = slot.sets, restSeconds = slot.restSeconds) }
        return adjusted to SessionFit.minutes(fitted)
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
        isDeload: Boolean,
        locale: String = "ko",
    ): ReadinessDto {
        var score = 1.0
        val factors = mutableListOf<String>()
        fun msg(key: String, vararg args: Any?) = WorkoutLocalization.message(key, locale, *args)

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
                    daysSinceLast == 0L -> { score -= 0.1; factors.add(msg("readiness.worked_today")) }
                    daysSinceLast == 1L -> { factors.add(msg("readiness.recovery_ok")) }
                    daysSinceLast in 2..3 -> { factors.add(msg("readiness.recovery_good")) }
                    daysSinceLast > 3 -> { score -= 0.05; factors.add(msg("readiness.gap_days", daysSinceLast)) }
                }
            } else {
                factors.add(msg("readiness.normal"))
            }
        } catch (e: Exception) {
            log.debug("Could not compute readiness from session history: {}", e.message)
            factors.add(msg("readiness.normal"))
        }

        // Factor 2: Subjective readiness
        if (subjectiveReadiness != null) {
            when (subjectiveReadiness) {
                1 -> { score -= 0.20; factors.add(msg("readiness.subjective.1")) }
                2 -> { score -= 0.10; factors.add(msg("readiness.subjective.2")) }
                3 -> { factors.add(msg("readiness.subjective.3")) }
                4 -> { score += 0.05; factors.add(msg("readiness.subjective.4")) }
                5 -> { score += 0.10; factors.add(msg("readiness.subjective.5")) }
            }
        }

        if (isDeload) {
            score -= 0.10
            factors.add(msg("readiness.deload"))
        }

        score = score.coerceIn(0.5, 1.1)

        // 아무 신호가 없는 평범한 날(score 1.0)은 무게를 올리지 않는다(예전엔 +5% 라 같은 사람에게 화면마다 다른 첫 무게가 나갔다).
        // 컨디션이 좋다고 직접 답한 날(score > 1.0)만 +5%.
        val intensityMultiplier = when {
            score > 1.0 -> 1.05
            score >= 0.95 -> 1.0
            score >= 0.85 -> 0.95
            score >= 0.75 -> 0.90
            else -> 0.85
        }

        return ReadinessDto(
            score = score,
            intensityMultiplier = intensityMultiplier,
            factors = factors.ifEmpty { listOf(msg("readiness.default")) }
        )
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
        ageRestMultiplier: Double,
        phaseAdj: PhaseAdjustment,
        isDeload: Boolean,
        userAge: Int?,
        prefetchedExercise: Exercise? = null
    ): DayExerciseDetail {
        // 무게: 준비도만 반영하고 2.5kg 단위·바벨 20kg 하한은 단일 관문(RecoRules.finalizeWeight)을 거친다
        val adjustedWeight = ex.suggestedWeight?.let { base ->
            val w = base * readiness.intensityMultiplier
            if (prefetchedExercise != null) RecoRules.finalizeWeight(prefetchedExercise, w) else (w / 2.5).roundToInt() * 2.5
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

        return ex.copy(
            suggestedWeight = adjustedWeight,
            sets = adjustedSets,
            minReps = adjustedMinReps,
            maxReps = adjustedMaxReps,
            restSeconds = adjustedRest,
            warmupSets = warmups,
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
     * 대체 운동 후보(최대 [limit]개). 대체 후보 API(ProgramService.getExerciseSubstitutes)와 같은 순위 함수
     * [SubstituteRanker.rankForUser] 를 쓰므로 같은 사용자·같은 운동이면 같은 후보가 나온다 — 보유 장비, 부상 부위, 수준(철봉·너무 어려운 동작)을 지킨다.
     * 이름은 사용자 언어로 내려 준다(예전엔 영어 이름이 그대로 나갔다).
     */
    private fun findSubstitutes(
        exerciseId: Long,
        limit: Int,
        prefetchedExercise: Exercise? = null,
        ctx: UserTrainingContextResolver.Context = UserTrainingContextResolver.Context.EMPTY,
        locale: String = "ko",
    ): List<SubstituteExerciseDto> {
        val exercise = prefetchedExercise ?: return emptyList()
        if (exercise.muscleGroups.isEmpty()) return emptyList()

        val cache = catalogCache
        val alternatives: List<Exercise> = if (cache != null) {
            val curated = try { exerciseSubstitutionService.getSubstitutes(exerciseId).map { it.substituteExercise.id }.toSet() } catch (e: Exception) { emptySet() }
            val restricted = injuryFilterService?.restrictionChecker(ctx.injuries) ?: { _: Exercise -> false }
            SubstituteRanker.rankForUser(exercise, cache.get().all, curated, ctx, restricted, limit).map { it.exercise }
        } else {
            // 카탈로그 캐시가 없는 환경(단위 테스트): 이전 방식 + 같은 장비·수준 규칙
            val suitable = ctx.suitability()
            exerciseRepository.findAlternativeExercises(exerciseId, exercise.category, exercise.muscleGroups.toList())
                .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
                .filter { TemplatePlanAdapter.equipmentAllowed(it, ctx.equipment) && suitable(it) }
                .filter { injuryFilterService == null || ctx.injuries.isEmpty() || !injuryFilterService.restrictionChecker(ctx.injuries)(it) }
                .sortedWith(
                    compareByDescending<Exercise> { exerciseSubstitutionService.calculateTransferScore(exercise, it) }
                        .thenByDescending { it.popularity }
                        .thenBy { it.id }
                )
                .take(limit)
        }
        val names = if (locale == "en" || localizationService == null || alternatives.isEmpty()) emptyMap()
            else try { localizationService.translationMap(alternatives, locale) } catch (e: Exception) { emptyMap() }

        return alternatives.map { alt ->
            SubstituteExerciseDto(
                exerciseId = alt.id,
                exerciseName = names[alt.id]?.displayName ?: alt.name,
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

    private companion object {
        /** 초보·노비스 보호 처방을 적용하는 처음 몇 주 */
        const val BEGINNER_PROTECT_WEEKS = 4
    }
}

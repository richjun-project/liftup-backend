package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules
import com.richjun.liftupai.domain.workout.service.new.SessionFit

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.ExerciseSuitability
import com.richjun.liftupai.domain.workout.util.SessionDurationFitter
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

/**
 * WorkoutServiceV2에서 분리된 운동 추천 오케스트레이션 서비스.
 * 기본/빠른 추천 조립, 다음 세트 조정, 휴식 타이머를 담당하며,
 * 세트·렙·무게·주기화 계산 자체는 PTRecommendationEngine에 위임한다.
 *
 * getWorkoutFromRecommendationId / applyWorkoutAdjustments 는
 * WorkoutSessionService.startQuickWorkout()에서 재사용하므로 public으로 노출한다.
 */
@Service
@Transactional
class WorkoutRecommendationServiceV2(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutProgressTracker: WorkoutProgressTracker,
    private val exerciseRecommendationService: ExerciseRecommendationService,
    private val autoProgramSelector: AutoProgramSelector,
    @Autowired(required = false)
    private val vectorRecommendationService: VectorWorkoutRecommendationService?,
    private val sharedSupport: WorkoutV2SharedSupport,
    private val ptEngine: PTRecommendationEngine,
    private val injuryFilterService: InjuryFilterService,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    @Autowired(required = false)
    private val exerciseRepository: com.richjun.liftupai.domain.workout.repository.ExerciseRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private data class RecommendationProgramContext(
        val programDays: Int,
        val programType: String,
        val workoutSequence: List<WorkoutType>
    )

    fun adjustNextSet(userId: Long, request: AdjustNextSetRequest, localeOverride: String? = null): AdjustNextSetResponse {
        val locale = sharedSupport.resolveLocale(userId, localeOverride)
        val fatigueMultiplier = when (request.fatigue) {
            "high" -> 0.8
            "medium" -> 0.9
            else -> 1.0
        }

        val rpeAdjustment = when {
            request.previousSet.rpe >= 9 -> 0.9
            request.previousSet.rpe >= 8 -> 0.95
            else -> 1.0
        }

        val recommendedWeight = request.previousSet.weight * fatigueMultiplier * rpeAdjustment
        val recommendedReps = (request.previousSet.reps * fatigueMultiplier).roundToInt()

        val restSeconds = when (request.fatigue) {
            "high" -> 180
            "medium" -> 120
            else -> 90
        }

        val reason = when {
            request.fatigue == "high" -> WorkoutLocalization.message("adjust.reason.fatigue_high", locale)
            request.previousSet.rpe >= 9 -> WorkoutLocalization.message("adjust.reason.rpe_high", locale)
            else -> WorkoutLocalization.message("adjust.reason.standard", locale)
        }

        return AdjustNextSetResponse(
            recommendation = SetRecommendation(
                weight = recommendedWeight,
                reps = recommendedReps,
                restSeconds = restSeconds,
                reason = reason
            ),
            alternatives = listOf(
                AlternativeSet(
                    type = "drop_set",
                    weight = request.previousSet.weight * 0.7,
                    reps = request.previousSet.reps + 2,
                    description = WorkoutLocalization.message("adjust.alternative.drop_set", locale)
                ),
                AlternativeSet(
                    type = "rest_pause",
                    weight = request.previousSet.weight,
                    reps = request.previousSet.reps / 2,
                    description = WorkoutLocalization.message("adjust.alternative.rest_pause", locale)
                )
            )
        )
    }

    fun getRestTimer(userId: Long, exerciseType: String, intensity: String, setNumber: Int, localeOverride: String? = null): RestTimerResponse {
        val locale = sharedSupport.resolveLocale(userId, localeOverride)
        val baseRest = when (exerciseType) {
            "compound" -> 180
            "isolation" -> 90
            else -> 120
        }

        val intensityMultiplier = when (intensity) {
            "high" -> 1.5
            "low" -> 0.7
            else -> 1.0
        }

        val setMultiplier = when {
            setNumber >= 4 -> 1.2
            setNumber >= 3 -> 1.1
            else -> 1.0
        }

        val recommendedRest = (baseRest * intensityMultiplier * setMultiplier).toInt()

        return RestTimerResponse(
            recommendedRest = recommendedRest,
            minRest = (recommendedRest * 0.7).toInt(),
            maxRest = (recommendedRest * 1.5).toInt(),
            factors = RestFactors(
                exerciseType = WorkoutLocalization.message("rest.exercise_type.${if (exerciseType == "compound" || exerciseType == "isolation") exerciseType else "general"}", locale),
                intensity = WorkoutLocalization.message("rest.intensity.${if (intensity == "high" || intensity == "low") intensity else "medium"}", locale),
                setNumber = WorkoutLocalization.message("rest.set_phase.${if (setNumber >= 4) "late" else "early"}", locale)
            )
        )
    }

    // Basic workout recommendation methods
    @Transactional(readOnly = true)
    fun getBasicWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        localeOverride: String? = null
    ): QuickWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId, localeOverride)

        // Check if user has an active session
        val activeSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS).orElse(null)

        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
        // 세션 시간: 요청 → 저장된 설정 → 프로필 → 45분. 채팅 추천(/api/chat/recommendations/workout)과 같은 해석이라 두 화면의 시간이 같다.
        val userSettings = try { userSettingsRepository.findByUser_Id(userId).orElse(null) } catch (e: Exception) { null }
        val resolvedDuration = UserTrainingContextResolver.of(userSettings, userProfile, UserTrainingContextResolver.Overrides(sessionMinutes = duration))
            .sessionMinutesOrDefault()
        val programContext = resolveRecommendationProgramContext(userId, user, userProfile)
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programContext.programDays)
        val normalizedTargetMuscle = normalizeRecommendationTarget(targetMuscle)

        // Determine target muscle based on current session or program position
        // 사용자가 명시적으로 근육을 지정하지 않으면 현재 세션 또는 프로그램 상태를 따름
        val adjustedTargetMuscle = if (normalizedTargetMuscle == null) {
            if (activeSession != null) {
                // 오래된 세션(6시간 이상) 무시 — 프로그램 순서 사용
                val sessionAge = java.time.Duration.between(activeSession.startTime, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))
                if (sessionAge.toHours() >= 6) {
                    logger.info("Ignoring stale IN_PROGRESS session (age: ${sessionAge.toHours()}h), using program position")
                    null // fall through to program position
                } else {
                    val currentWorkoutType = activeSession.workoutType ?: WorkoutType.FULL_BODY
                    val sessionMuscle = WorkoutTargetResolver.recommendationTargetForType(currentWorkoutType)
                    logger.debug("Selected target muscle from current session: {} (current type: {})", sessionMuscle, currentWorkoutType)
                    sessionMuscle
                }
            } else {
                null // fall through to program position
            } ?: run {
                // 진행 중인 세션이 없거나 오래된 경우 프로그램 위치에 따라 선택
                val workoutType = programContext.workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY
                val programMuscle = WorkoutTargetResolver.recommendationTargetForType(workoutType)
                logger.debug("Selected target muscle from program state: {} (next: {}, day {})", programMuscle, workoutType, programPosition.day)
                programMuscle
            }
        } else {
            logger.debug("Using user-selected target muscle: {}", normalizedTargetMuscle)
            normalizedTargetMuscle
        }

        // Generate main recommendation based on filters
        val recommendation = generateQuickRecommendation(user, resolvedDuration, equipment, adjustedTargetMuscle, locale)

        // Generate alternatives
        val alternatives = generateAlternativeWorkouts(resolvedDuration, equipment, adjustedTargetMuscle, locale)

        return QuickWorkoutRecommendationResponse(
            recommendation = recommendation,
            alternatives = alternatives
        )
    }

    @Deprecated("Use getBasicWorkoutRecommendation instead")
    @Transactional(readOnly = true)
    fun getQuickWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        localeOverride: String? = null
    ): QuickWorkoutRecommendationResponse {
        return getBasicWorkoutRecommendation(userId, duration, equipment, targetMuscle, localeOverride)
    }

    private fun generateQuickRecommendation(
        user: com.richjun.liftupai.domain.auth.entity.User,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): WorkoutRecommendationDetail {
        // Get user profile to determine difficulty
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val userSettings = try { userSettingsRepository.findByUser_Id(user.id).orElse(null) } catch (e: Exception) { null }
        val ctx = UserTrainingContextResolver.of(userSettings, userProfile, UserTrainingContextResolver.Overrides(sessionMinutes = duration))
        val workoutDuration = (duration ?: ctx.sessionMinutesOrDefault()).coerceIn(10, 180)
        val workoutId = generateWorkoutId(workoutDuration, equipment, targetMuscle)
        val difficultyKey = when (userProfile?.experienceLevel) {
            ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE -> "beginner"
            ExperienceLevel.INTERMEDIATE -> "intermediate"
            ExperienceLevel.ADVANCED -> "advanced"
            ExperienceLevel.EXPERT -> "advanced"
            else -> "intermediate" // default
        }
        val difficulty = WorkoutLocalization.difficultyDisplayName(difficultyKey, locale)

        // Calculate optimal exercise count based on duration
        val targetExerciseCount = getTargetExerciseCount(workoutDuration)

        // 벡터 추천 우선 시도, 실패 시 rule-based 폴백
        // 벡터 결과가 목표의 절반 미만이면 rule-based로 폴백 (인덱싱 미완료 등 대비)
        val minAcceptableCount = (targetExerciseCount + 1) / 2
        val rawExercises = try {
            vectorRecommendationService?.recommendExercises(
                user = user,
                profile = userProfile,
                duration = workoutDuration,
                targetMuscle = targetMuscle,
                equipment = equipment,
                difficulty = difficultyKey,
                limit = targetExerciseCount
            )?.takeIf { it.size >= minAcceptableCount }
        } catch (e: Exception) {
            logger.warn("Vector recommendation failed, falling back to rule-based: ${e.message}")
            null
        } ?: exerciseRecommendationService.getRecommendedExercises(
            user = user,
            targetMuscle = targetMuscle,
            equipment = equipment,
            duration = workoutDuration,
            limit = targetExerciseCount
        )
        // 추천 출처(벡터/규칙)와 무관하게 마지막에 한 번 더 거른다 — 부상·장비·회복·중복
        val safe = applySafetyFilters(user, userProfile, rawExercises, minAcceptableCount, targetExerciseCount)
        // 집중 부위가 하나도 없으면 안전한 후보로 하나 보장하고, 복합 → 고립 · 대표(ESSENTIAL) · 집중 부위 순으로 정렬한다
        val exercises = orderForSession(ensureFocus(safe, ctx, targetExerciseCount), ctx)
        val translations = sharedSupport.translationMap(exercises, locale)

        logger.info("Workout recommendation completed: ${exercises.size} exercises (target: $targetExerciseCount)")

        // Create quick exercise details
        val prescribed = exercises.take(targetExerciseCount).mapIndexed { index, exercise ->
            // PT 시스템으로 세트/렙/무게/휴식 전부 계산
            val ptRec = try {
                ptEngine.calculateFullPTRecommendation(user, exercise)
            } catch (e: Exception) {
                null
            }

            (ptRec?.minRestSeconds ?: 45).coerceAtMost(ptRec?.restSeconds ?: 60) to QuickExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = sharedSupport.localizedName(exercise, locale, translations),
                category = exercise.category.name,
                equipment = exercise.equipment?.name,
                sets = ptRec?.sets ?: 3,
                reps = ptRec?.reps ?: "10-12",
                rest = ptRec?.restSeconds ?: 60,
                order = index + 1,
                // 맨몸·밴드 종목에 무게를 붙이면 "푸시업 7.5kg" 같은 말이 안 되는 추천이 나간다
                suggestedWeight = if (isWeightlessExercise(exercise)) null
                    else (ptRec?.weight ?: try { ptEngine.calculateSuggestedWeight(user, exercise) } catch (e: Exception) { 0.0 }),
                // 운동별 근육군 — 없으면 클라이언트가 추천 전체의 근육을 모든 운동에 동일하게 붙여 표시한다
                targetMuscles = exercise.muscleGroups.map { WorkoutTargetResolver.displayName(it, locale) }
            )
        }
        val quickExercises = fitToDuration(
            prescribed, workoutDuration,
            maxSetsById = exercises.associate { it.id.toString() to RecoRules.maxSetsFor(it, ctx.level, ctx.age, sessionMinutes = workoutDuration) },
            exerciseById = exercises.associateBy { it.id }, focus = ctx.focusAreas,
        )

        val targetMuscles = exercises
            .flatMap { exercise -> exercise.muscleGroups.map { muscle -> WorkoutTargetResolver.displayName(muscle, locale) } }
            .distinct()
        val equipmentList = exercises
            .mapNotNull { exercise -> exercise.equipment?.let { localizeEquipment(it, locale) } }
            .distinct()

        return WorkoutRecommendationDetail(
            workoutId = workoutId,
            name = generateWorkoutName(workoutDuration, targetMuscle, equipment, locale),
            duration = workoutDuration,
            difficulty = difficulty,
            exercises = quickExercises,
            estimatedCalories = calculateEstimatedCalories(workoutDuration, quickExercises.size),
            targetMuscles = targetMuscles,
            equipment = equipmentList
        )
    }

    private fun generateAlternativeWorkouts(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): List<AlternativeWorkout> {
        val alternatives = mutableListOf<AlternativeWorkout>()

        // Duration alternatives
        val baseDuration = duration ?: 30
        if (baseDuration >= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(20, equipment, "core"),
                name = generateWorkoutName(20, "core", equipment, locale),
                duration = 20
            ))
        }

        if (baseDuration <= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(45, equipment, "full_body"),
                name = generateWorkoutName(45, "full_body", equipment, locale),
                duration = 45
            ))
        }

        // Equipment alternatives
        if (equipment != "bodyweight") {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(baseDuration, "bodyweight", targetMuscle),
                name = generateWorkoutName(baseDuration, targetMuscle, "bodyweight", locale),
                duration = baseDuration
            ))
        }

        return alternatives.take(2)
    }

    /**
     * 세트·휴식을 요청한 세션 시간(85~110%)에 맞춘다. 종목별 세트 상한(초보 주력 복합 3세트 등)을 지키고,
     * 줄여야 하면 복합·집중 부위·대표 운동이 아닌 종목부터 뺀다.
     */
    internal fun fitToDuration(
        prescribed: List<Pair<Int, QuickExerciseDetail>>,
        durationMinutes: Int,
        maxSetsById: Map<String, Int> = emptyMap(),
        exerciseById: Map<Long, Exercise> = emptyMap(),
        focus: List<FocusArea> = emptyList(),
    ): List<QuickExerciseDetail> {
        val slots = prescribed.map { (minRest, e) ->
            val ex = exerciseById[e.exerciseId.toLongOrNull() ?: 0L]
            val restUp = if (ex != null) RecoRules.maxRestFor(ex, RecoRules.isCompound(ex), e.reps.substringBefore('-').trim().toIntOrNull() ?: 10) else 90
            SessionFit.Slot(
                sets = e.sets, reps = e.reps, restSeconds = e.rest, minRestSeconds = minRest.coerceAtMost(e.rest),
                maxRestSeconds = restUp.coerceAtLeast(e.rest), maxSets = (maxSetsById[e.exerciseId] ?: 5).coerceAtLeast(e.sets),
            )
        }
        val result = SessionFit.fit(slots, durationMinutes, minExercises = MIN_SESSION_EXERCISES, pickDrop = { kept, _ ->
            (1 until kept.size).toList().minWithOrNull(compareBy<Int>({ pos ->
                val ex = exerciseById[prescribed[kept[pos]].second.exerciseId.toLongOrNull() ?: 0L]
                if (ex == null) -1.0 else RecoRules.keepValue(ex, RecoRules.isCompound(ex), focus)
            }, { pos -> -pos }))
        })
        return result.keptIndices.mapIndexed { pos, orig ->
            prescribed[orig].second.copy(sets = result.slots[pos].sets, rest = result.slots[pos].restSeconds, order = pos + 1)
        }
    }

    /** 대표(ESSENTIAL) 복합운동이 앞, 코어·유산소는 뒤. 집중 부위 운동은 같은 등급 안에서 앞. 나머지는 추천 순서 유지. */
    internal fun orderForSession(exercises: List<Exercise>, ctx: UserTrainingContextResolver.Context): List<Exercise> {
        val focus = ctx.focusAreas
        return exercises.withIndex().sortedWith(
            compareBy<IndexedValue<Exercise>>(
                { if (it.value.category == ExerciseCategory.CORE || it.value.category == ExerciseCategory.CARDIO) 1 else 0 },
                { if (RecoRules.isCompound(it.value)) 0 else 1 },
                { if (it.value.recommendationTier == RecommendationTier.ESSENTIAL) 0 else 1 },
                { if (focus.any { a -> a.hits(it.value) }) 0 else 1 },
                { it.index },
            )
        ).map { it.value }
    }

    /**
     * 집중 부위(굵은 팔·힙업 …)를 하나도 다루지 않는 추천이면 안전한 후보(부상·장비·수준 규칙 통과) 하나를 끼워 넣는다.
     * 목표 종목 수가 이미 찼으면 고립 종목 하나를 바꾼다. 후보가 없거나 이미 포함돼 있으면 그대로.
     */
    internal fun ensureFocus(exercises: List<Exercise>, ctx: UserTrainingContextResolver.Context, targetCount: Int): List<Exercise> {
        val focus = ctx.focusAreas
        val repo = exerciseRepository
        if (focus.isEmpty() || repo == null || exercises.any { ex -> focus.any { it.hits(ex) } }) return exercises
        val restricted = injuryFilterService.restrictionChecker(ctx.injuries)
        val suitable = ctx.suitability()
        val used = exercises.map { it.id }.toSet()
        val usedNames = exercises.map { it.name.trim().lowercase() }.toSet()
        for (area in focus) {
            val pick = area.candidateCategories().flatMap { cat ->
                try { repo.findPlanEligibleByCategoryOrderByPopularity(cat) } catch (e: Exception) { emptyList() }
            }.filter { c ->
                area.hits(c) && c.id !in used && c.name.trim().lowercase() !in usedNames &&
                    TemplatePlanAdapter.equipmentAllowed(c, ctx.equipment) && suitable(c) && !restricted(c)
            }.minWithOrNull(compareBy<Exercise>({ if (it.recommendationTier == RecommendationTier.ESSENTIAL) 0 else 1 }, { -it.popularity }, { it.id })) ?: continue
            if (exercises.size < targetCount) return exercises + pick
            val swap = exercises.withIndex().filter { !RecoRules.isCompound(it.value) }.minByOrNull { it.value.popularity }?.index
                ?: return exercises
            return exercises.toMutableList().also { it[swap] = pick }
        }
        return exercises
    }

    /**
     * 운동 시간에 따른 목표 운동 개수 계산
     */
    private fun getTargetExerciseCount(duration: Int): Int {
        return when {
            duration <= 30 -> 4
            duration <= 45 -> 5
            duration <= 60 -> 6
            duration <= 75 -> 7
            duration <= 90 -> 8
            duration <= 105 -> 9
            else -> 10
        }
    }

    /**
     * WorkoutSessionService.startQuickWorkout()에서도 호출되므로 public.
     */
    fun getWorkoutFromRecommendationId(
        user: com.richjun.liftupai.domain.auth.entity.User,
        recommendationId: String,
        locale: String
    ): WorkoutRecommendationDetail {
        val query = parseWorkoutRecommendationId(recommendationId)
        return generateQuickRecommendation(
            user = user,
            duration = query.duration,
            equipment = query.equipment,
            targetMuscle = query.targetMuscle,
            locale = locale
        )
    }

    /**
     * WorkoutSessionService.startQuickWorkout()에서도 호출되므로 public.
     */
    fun applyWorkoutAdjustments(
        workout: WorkoutRecommendationDetail,
        adjustments: WorkoutAdjustments
    ): WorkoutRecommendationDetail {
        var adjustedExercises = workout.exercises

        // Apply skip exercises
        if (adjustments.skipExercises.isNotEmpty()) {
            adjustedExercises = adjustedExercises.filter {
                !adjustments.skipExercises.contains(it.exerciseId)
            }
        }

        // Apply exercise substitutions
        adjustments.substituteExercises.forEach { (original, substitute) ->
            adjustedExercises = adjustedExercises.map { exercise ->
                if (exercise.exerciseId == original) {
                    exercise.copy(exerciseId = substitute)
                } else {
                    exercise
                }
            }
        }

        return workout.copy(
            exercises = adjustedExercises,
            duration = adjustments.duration ?: workout.duration
        )
    }

    private data class WorkoutRecommendationQuery(
        val duration: Int,
        val equipment: String?,
        val targetMuscle: String?
    )

    private fun parseWorkoutRecommendationId(recommendationId: String): WorkoutRecommendationQuery {
        if (recommendationId.startsWith("quick|")) {
            val parts = recommendationId.split("|")
            return WorkoutRecommendationQuery(
                duration = parts.getOrNull(1)?.toIntOrNull() ?: 30,
                targetMuscle = normalizeRecommendationTarget(parts.getOrNull(2)),
                equipment = normalizeRecommendationEquipment(parts.getOrNull(3))
            )
        }

        val legacy = recommendationId.removePrefix("quick_")
        val durationToken = legacy.substringBefore("_", "30min")
        val remainder = legacy.substringAfter("_", "full_body_general")
        val equipmentToken = knownRecommendationEquipmentTokens
            .firstOrNull { remainder == it || remainder.endsWith("_$it") }

        val targetToken = when {
            equipmentToken == null -> remainder
            remainder == equipmentToken -> "full_body"
            else -> remainder.removeSuffix("_$equipmentToken")
        }

        return WorkoutRecommendationQuery(
            duration = durationToken.removeSuffix("min").filter { it.isDigit() }.toIntOrNull() ?: 30,
            targetMuscle = normalizeRecommendationTarget(targetToken),
            equipment = normalizeRecommendationEquipment(equipmentToken)
        )
    }

    private fun resolveRecommendationProgramContext(
        userId: Long,
        user: com.richjun.liftupai.domain.auth.entity.User,
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile? = userProfileRepository.findByUser_Id(userId).orElse(null)
    ): RecommendationProgramContext {
        val userSettings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val configuredProgramType = userSettings?.workoutSplit
            ?: userProfile?.workoutSplit
            ?: RecommendationConstants.DEFAULT_PROGRAM_TYPE
        val programDays = userSettings?.weeklyWorkoutDays
            ?: userProfile?.weeklyWorkoutDays
            ?: 3
        val autoRecommendation = if (configuredProgramType.equals("AUTO", ignoreCase = true)) {
            autoProgramSelector.selectProgram(user)
        } else {
            null
        }

        return RecommendationProgramContext(
            programDays = programDays,
            programType = autoRecommendation?.programType ?: configuredProgramType,
            workoutSequence = autoRecommendation?.workoutSequence
                ?: workoutProgressTracker.getWorkoutTypeSequence(configuredProgramType)
        )
    }

    private fun generateWorkoutId(duration: Int, equipment: String?, targetMuscle: String?): String {
        val equipmentPart = sanitizeRecommendationToken(equipment ?: "general")
        val musclePart = sanitizeRecommendationToken(targetMuscle ?: "full_body")
        return "quick|$duration|$musclePart|$equipmentPart"
    }

    private fun generateWorkoutName(duration: Int, targetMuscle: String?, equipment: String?, locale: String): String {
        val focus = WorkoutTargetResolver.resolveFocus(targetMuscle) ?: WorkoutFocus.FULL_BODY
        val muscleText = WorkoutTargetResolver.displayName(focus, locale = locale)
        val equipmentText = localizeEquipment(equipment, locale)
        val durationText = WorkoutLocalization.durationLabel(duration, locale)

        return if (equipmentText.isBlank()) {
            WorkoutLocalization.message("basic.workout.name_no_equipment", locale, durationText, muscleText)
        } else {
            WorkoutLocalization.message("basic.workout.name", locale, durationText, muscleText, equipmentText)
        }
    }

    private fun sanitizeRecommendationToken(rawValue: String): String {
        return rawValue
            .trim()
            .lowercase()
            .replace("-", "_")
            .replace(" ", "_")
    }

    private fun normalizeRecommendationTarget(rawValue: String?): String? {
        return WorkoutTargetResolver.recommendationKey(rawValue)
    }

    private fun normalizeRecommendationEquipment(rawValue: String?): String? {
        val normalized = rawValue
            ?.let(::sanitizeRecommendationToken)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "general", "none" -> null
            else -> normalized
        }
    }

    private fun localizeEquipment(equipment: Equipment, locale: String): String {
        return localizeEquipment(equipment.name, locale)
    }

    private fun localizeEquipment(equipment: String?, locale: String): String {
        return WorkoutLocalization.equipmentName(equipment, locale)
    }

    companion object {
        /** "헬스장 전부" 를 뜻하는 장비 토큰 — 이게 있으면 장비 필터를 걸지 않는다 */
        private val FULL_GYM_TOKENS = setOf("GYM", "FULL_GYM", "ALL", "FULL", "COMMERCIAL_GYM", "EVERYTHING")
        private const val MIN_SESSION_EXERCISES = 3

        private val knownRecommendationEquipmentTokens = listOf(
            "resistance_band",
            "bodyweight",
            "kettlebell",
            "dumbbells",
            "dumbbell",
            "barbell",
            "machine",
            "cable",
            "general"
        )
    }

    /**
     * 운동 전 추정 칼로리 (예측값)
     * 기본 5kcal/min + 운동 수 보정 (completeWorkout과 동일 기준)
     */
    private fun calculateEstimatedCalories(duration: Int, exerciseCount: Int): Int {
        val baseCalories = duration * 5
        val exerciseBonus = exerciseCount * 8
        return baseCalories + exerciseBonus
    }

    /**
     * 최종 안전망. 벡터/규칙 추천이 무엇을 돌려주든 부상 부위 운동·보유하지 않은 장비·회복 안 된 근육·중복은
     * 여기서 걸러진다.
     *
     * 규칙 추천 서비스(ExerciseRecommendationService)는 후보가 모자라면 부상 필터를 통째로 되돌리는 폴백이 있고
     * (filtered < 10 → 원본 사용), 벡터 추천은 부상을 보지 않는다. 그래서 상류만 믿을 수 없다.
     * 부상 필터는 절대 완화하지 않는다. 장비·회복 필터는 결과가 minAcceptableCount 미만이 되면 그 필터만 푼다
     * (추천이 비는 것보다 낫다 — 다치게 하는 것보다는 아니다).
     */
    internal fun applySafetyFilters(
        user: com.richjun.liftupai.domain.auth.entity.User,
        profile: UserProfile?,
        exercises: List<Exercise>,
        minAcceptableCount: Int,
        targetCount: Int = minAcceptableCount,
    ): List<Exercise> {
        val settings = try { userSettingsRepository.findByUser_Id(user.id).orElse(null) } catch (e: Exception) { null }
        val ctx = UserTrainingContextResolver.of(settings, profile)

        var result = exercises
            .distinctBy { it.id }
            .distinctBy { it.name.trim().lowercase() }

        // 1. 부상 — 완화 없음. 프로필 ∪ 설정(단일 해석기): 예전엔 설정에 값이 있으면 프로필의 부상을 통째로 무시했다.
        val injuries: Set<String> = ctx.injuries
        if (injuries.isNotEmpty()) {
            val before = result.size
            result = injuryFilterService.filterExercises(result, injuries)
            if (result.size < before) {
                logger.info("Safety filter(injury): $before -> ${result.size} (injuries=$injuries) for user ${user.id}")
            }
        }

        // 2. 보유 장비 — 맨몸/밴드류는 항상 허용
        val available = ctx.equipment

        // 2-a. 실제로 할 수 있는가 — 철봉이 필요한 맨몸 동작(홈트), 초보·고령·미성년에게 맨몸 풀업·평행봉 딥스·점프
        val level = ctx.level
        val age = ctx.age
        fun suitable(ex: Exercise) = RecoRules.suitable(ex, available, level, age)
        val feasible = result.filter(::suitable)
        if (feasible.size < result.size) {
            logger.info("Safety filter(suitability): ${result.size} -> ${feasible.size} (level=$level, age=$age, available=$available) for user ${user.id}")
            result = feasible
        }
        if (available.isNotEmpty() && available.none { it in FULL_GYM_TOKENS }) {
            val byEquipment = result.filter { allowedByEquipment(it, available) }
            if (byEquipment.size < result.size) {
                // 걸러낸 만큼 같은 부위의 "보유 장비로 할 수 있는" 운동으로 채운다.
                // 2026-09-16 운영: 덤벨만 있는 사용자에게 후보 5개 중 2개만 맞아 필터를 "완화" →
                // 바벨 벤치·바벨 스컬크러셔가 그대로 추천됐다. 완화는 채울 후보가 정말 없을 때만.
                val removed = result.filter { it !in byEquipment }
                val recoveringNow: Set<MuscleGroup> = try {
                    muscleRecoveryRepository.findByUser(user)
                        .filter { it.recoveryPercentage < RecommendationConstants.RECOVERY_THRESHOLD_PERCENT }
                        .flatMap { WorkoutTargetResolver.muscleGroupsFor(it.muscleGroup) }.toSet()
                } catch (e: Exception) { emptySet() }
                // 같은 부위 대체로도 모자라면, 장비 필터를 풀기 전에 다른 부위의 할 수 있는 운동으로 먼저 채운다
                // (골든셋 2026-09-25: 맨몸 사용자에게 바벨 스쿼트·벤치가 "완화"로 나갔다)
                val topped = topUpByEquipment(byEquipment, removed, available, injuries, result.size, recoveringNow, ::suitable)
                    .let { kept ->
                        if (kept.size >= minAcceptableCount) kept
                        else fillWithSafeExercises(kept, result.map { it.category }.distinct(), minAcceptableCount, available, injuries, recoveringNow, ::suitable)
                    }
                if (topped.size >= minAcceptableCount) {
                    logger.info("Safety filter(equipment): ${result.size} -> ${byEquipment.size} + topped up to ${topped.size} (available=$available) for user ${user.id}")
                    result = topped
                } else {
                    logger.warn("Safety filter(equipment) relaxed: only ${topped.size} < $minAcceptableCount match available=$available for user ${user.id}")
                }
            }
        }

        // 3. 회복률 50% 미만 근육
        val recovering: Set<MuscleGroup> = try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < RecommendationConstants.RECOVERY_THRESHOLD_PERCENT }
                .flatMap { WorkoutTargetResolver.muscleGroupsFor(it.muscleGroup) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        if (recovering.isNotEmpty()) {
            val rested = result.filter { ex -> ex.muscleGroups.none { it in recovering } }
            if (rested.size >= minAcceptableCount) {
                if (rested.size < result.size) {
                    logger.info("Safety filter(recovery): ${result.size} -> ${rested.size} (recovering=$recovering) for user ${user.id}")
                }
                result = rested
            } else {
                logger.warn("Safety filter(recovery) relaxed: only ${rested.size} < $minAcceptableCount rested for user ${user.id}")
            }
        }

        // 4. 부상·장비로 줄어 목표 개수(최소 3)에 못 미치면 안전한 다른 종목으로 채운다.
        //    골든셋 2026-09-25: 무릎 부상 + 밴드만 있는 72세 사용자에게 전신 추천이 2종목만 나갔고,
        //    맨몸 사용자 45~60분 요청에 3~4종목만 나가 시간의 70% 도 못 채웠다.
        val floor = maxOf(MIN_SESSION_EXERCISES, minAcceptableCount, targetCount)
        if (result.size < floor) {
            val filled = fillWithSafeExercises(result, exercises.map { it.category }.distinct(), floor, available, injuries, recovering, ::suitable)
            if (filled.size > result.size) {
                logger.info("Safety filter(fill): ${result.size} -> ${filled.size} for user ${user.id}")
                result = filled
            }
        }

        return result
    }

    /** 원래 부위 → 코어·상체 순으로, 보유 장비·부상·회복 조건을 지키는 plan-eligible 종목을 인기순으로 채운다. */
    private fun fillWithSafeExercises(
        kept: List<Exercise>,
        preferred: List<ExerciseCategory>,
        target: Int,
        available: Set<String>,
        injuries: Set<String>,
        recovering: Set<MuscleGroup>,
        suitable: (Exercise) -> Boolean = { true },
    ): List<Exercise> {
        val repo = exerciseRepository ?: return kept
        val out = kept.toMutableList()
        val usedIds = out.map { it.id }.toMutableSet()
        val usedNames = out.map { it.name.trim().lowercase() }.toMutableSet()
        val anyEquipment = available.isEmpty() || available.any { it in FULL_GYM_TOKENS }
        val order = (preferred + listOf(ExerciseCategory.CORE, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.LEGS)).distinct()
        for (category in order) {
            if (out.size >= target) break
            val candidates = try { repo.findPlanEligibleByCategoryOrderByPopularity(category) } catch (e: Exception) { emptyList() }
            candidates.firstOrNull { c ->
                c.id !in usedIds && c.name.trim().lowercase() !in usedNames &&
                    (anyEquipment || allowedByEquipment(c, available)) && suitable(c) &&
                    c.muscleGroups.none { it in recovering } &&
                    (injuries.isEmpty() || injuryFilterService.filterExercises(listOf(c), injuries).isNotEmpty())
            }?.let { out += it; usedIds += it.id; usedNames += it.name.trim().lowercase() }
        }
        return out
    }

    private fun normalizeEquipmentTokens(raw: Collection<String>): Set<String> = raw
        .map { it.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') }
        .filter { it.isNotEmpty() }
        .map {
            when (it) {
                "BAND", "BANDS", "RESISTANCE_BANDS", "RESISTANCE_BAND" -> "RESISTANCE_BAND"
                "KETTLEBELLS" -> "KETTLEBELL"
                "DUMBBELLS" -> "DUMBBELL"
                "BARBELLS" -> "BARBELL"
                "MACHINES" -> "MACHINE"
                "CABLES", "CABLE_MACHINE" -> "CABLE"
                "NONE", "NO_EQUIPMENT", "BODY_WEIGHT" -> "BODYWEIGHT"
                else -> it
            }
        }
        .toSet()

    /** 장비가 안 맞아 빠진 운동을 같은 카테고리·보유 장비·부상 안전 운동으로 대체한다(인기순). */
    private fun topUpByEquipment(
        kept: List<Exercise>,
        removed: List<Exercise>,
        available: Set<String>,
        injuries: Set<String>,
        target: Int,
        recovering: Set<MuscleGroup> = emptySet(),
        suitable: (Exercise) -> Boolean = { true },
    ): List<Exercise> {
        val repo = exerciseRepository ?: return kept
        val out = kept.toMutableList()
        val usedIds = out.map { it.id }.toMutableSet()
        val usedNames = out.map { it.name.trim().lowercase() }.toMutableSet()
        for (gone in removed) {
            if (out.size >= target) break
            val candidates = try { repo.findPlanEligibleByCategoryOrderByPopularity(gone.category) } catch (e: Exception) { emptyList() }
            val pick = candidates.firstOrNull { c ->
                c.id !in usedIds && c.name.trim().lowercase() !in usedNames &&
                    allowedByEquipment(c, available) && suitable(c) &&
                    c.muscleGroups.none { it in recovering } &&
                    (injuries.isEmpty() || injuryFilterService.filterExercises(listOf(c), injuries).isNotEmpty())
            } ?: continue
            usedIds += pick.id; usedNames += pick.name.trim().lowercase(); out += pick
        }
        return out
    }

    private fun allowedByEquipment(exercise: Exercise, available: Set<String>): Boolean {
        val eq = exercise.equipment ?: return true
        if (eq == Equipment.BODYWEIGHT || eq == Equipment.OTHER) return true
        return eq.name in available
    }

    /** 맨몸·밴드 종목은 무게를 지정할 수 없다 — 표시 자체를 없앤다. */
    private fun isWeightlessExercise(exercise: Exercise): Boolean {
        val eq = exercise.equipment
        if (eq == Equipment.BODYWEIGHT || eq == Equipment.RESISTANCE_BAND) return true
        val name = exercise.name.lowercase()
        return listOf("맨몸", "bodyweight", "플랭크", "plank", "버피", "burpee",
                      "마운틴 클라이머", "mountain climber", "점핑잭", "jumping jack")
            .any { name.contains(it) }
    }

}

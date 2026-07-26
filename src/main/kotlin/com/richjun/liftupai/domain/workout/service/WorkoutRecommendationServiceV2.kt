package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
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
    private val ptEngine: PTRecommendationEngine
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
        val recommendation = generateQuickRecommendation(user, duration, equipment, adjustedTargetMuscle, locale)

        // Generate alternatives
        val alternatives = generateAlternativeWorkouts(duration, equipment, adjustedTargetMuscle, locale)

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
        val workoutDuration = (duration ?: 30).coerceIn(10, 180)
        val workoutId = generateWorkoutId(workoutDuration, equipment, targetMuscle)

        // Get user profile to determine difficulty
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val difficultyKey = when (userProfile?.experienceLevel) {
            ExperienceLevel.BEGINNER -> "beginner"
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
        val exercises = try {
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
        val translations = sharedSupport.translationMap(exercises, locale)

        logger.info("Workout recommendation completed: ${exercises.size} exercises (target: $targetExerciseCount)")

        // Create quick exercise details
        val quickExercises = exercises.take(targetExerciseCount).mapIndexed { index, exercise ->
            // PT 시스템으로 세트/렙/무게/휴식 전부 계산
            val ptRec = try {
                ptEngine.calculateFullPTRecommendation(user, exercise)
            } catch (e: Exception) {
                null
            }

            QuickExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = sharedSupport.localizedName(exercise, locale, translations),
                category = exercise.category.name,
                equipment = exercise.equipment?.name,
                sets = ptRec?.sets ?: 3,
                reps = ptRec?.reps ?: "10-12",
                rest = ptRec?.restSeconds ?: 60,
                order = index + 1,
                suggestedWeight = ptRec?.weight ?: try { ptEngine.calculateSuggestedWeight(user, exercise) } catch (e: Exception) { 0.0 }
            )
        }

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
     * 운동 시간에 따른 목표 운동 개수 계산
     */
    private fun getTargetExerciseCount(duration: Int): Int {
        return when {
            duration <= 30 -> 4
            duration <= 45 -> 5
            duration <= 60 -> 6
            duration <= 75 -> 7
            else -> 8
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
}

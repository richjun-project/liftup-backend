package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.GeneratedWorkout
import com.richjun.liftupai.domain.workout.dto.GraduationStatusDto
import com.richjun.liftupai.domain.workout.dto.ProgramGeneratedExercise
import com.richjun.liftupai.domain.workout.dto.ProgramSubstituteExercise
import com.richjun.liftupai.domain.workout.dto.ProgramWarmupSet
import com.richjun.liftupai.domain.workout.dto.ReadinessScore
import com.richjun.liftupai.domain.workout.dto.WeeklyVolumeStatus
import com.richjun.liftupai.domain.workout.entity.InjurySeverity
import com.richjun.liftupai.domain.workout.entity.ProgressionModel
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class ProgramWorkoutGeneratorService(
    private val programEnrollmentService: ProgramEnrollmentService,
    private val programDayRepository: ProgramDayRepository,
    private val programDayExerciseRepository: ProgramDayExerciseRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val progressiveOverloadService: ProgramProgressiveOverloadService,
    private val exerciseSubstitutionService: ExerciseSubstitutionService,
    private val graduationService: ProgramGraduationService,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val progressionService: ProgramProgressionService,
    private val injuryExerciseRestrictionRepository: InjuryExerciseRestrictionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun generateTodayWorkout(user: User): GeneratedWorkout {
        // 1. Get current enrollment
        val enrollment = programEnrollmentService.getCurrentEnrollment(user)
            ?: throw ResourceNotFoundException("No active program enrollment for user: ${user.id}")

        // 2. Get current position
        val position = programEnrollmentService.getCurrentPosition(enrollment)

        val program = enrollment.program

        // 3. Get the ProgramDay for current dayInCycle
        val programDays = programDayRepository.findByProgramIdOrderByDayNumber(program.id)
        val programDay = programDays.find { it.dayNumber == position.dayInCycle }
            ?: programDays.firstOrNull()
            ?: throw ResourceNotFoundException("No program days found for program: ${program.code}")

        // 4. Load ProgramDayExercises via JOIN FETCH
        val dayExercises = programDayExerciseRepository.findByDayIdWithExercises(programDay.id)

        // 5. Load user exercise overrides
        val overrideMap = userExerciseOverrideRepository
            .findByEnrollmentId(enrollment.id)
            .associateBy { it.originalExercise.id }

        // 6. Load injuries once (outside the loop)
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val profileInjuries = profile?.injuries ?: emptySet()
        val settingsInjuries = userSettingsRepository.findByUser_Id(user.id).orElse(null)?.injuries ?: emptySet()
        val injuries = profileInjuries + settingsInjuries

        // Load all SEVERE restrictions for the user's injuries
        val injuryRestrictions = injuries.flatMap { injury ->
            injuryExerciseRestrictionRepository.findByInjuryTypeAndSeverityIn(
                injury,
                listOf(InjurySeverity.SEVERE)
            )
        }

        // FIX 3: Compute readiness score
        val readiness = computeReadiness(user, enrollment)

        // FIX 5: Compute age-based multiplier
        val userAge = profile?.age
        val ageMultiplier = when {
            userAge == null -> 1.0
            userAge >= 60 -> 0.85   // 60+: 15% intensity reduction
            userAge >= 50 -> 0.90   // 50-59: 10% reduction
            userAge >= 40 -> 0.95   // 40-49: 5% reduction
            else -> 1.0
        }

        // FIX 1: BLOCK phase adjustment
        val blockPhaseAdj = if (program.progressionModel == ProgressionModel.BLOCK) {
            progressiveOverloadService.getBlockPhaseAdjustment(position)
        } else null

        // Map from exerciseId to muscle groups (for volume-reactive set adjustment)
        val exerciseMuscleGroupsMap = mutableMapOf<Long, Set<String>>()

        // 7 & 8. Build exercise list applying overrides, SEVERE auto-substitution, weights, warmups
        val generatedExercises = dayExercises.map { pde ->
            val override = overrideMap[pde.exercise.id]
            var actualExercise = override?.substituteExercise ?: pde.exercise

            // Auto-substitute if SEVERE injury restriction applies
            val severeRestriction = injuryRestrictions.find {
                it.restrictedExercise.id == actualExercise.id && it.suggestedSubstitute != null
            }
            if (severeRestriction != null) {
                val substitute = severeRestriction.suggestedSubstitute!!
                logger.info("Auto-substituted ${actualExercise.name} → ${substitute.name} (SEVERE injury restriction)")
                actualExercise = substitute
            }

            // FIX 4: Exercise rotation for isolation exercises every 4 weeks
            val substitutes = exerciseSubstitutionService
                .getSubstitutesForInjury(actualExercise.id, injuries)
            if (!pde.isCompound && substitutes.isNotEmpty()) {
                val rotationIndex = ((position.week - 1) / 4) % (substitutes.size + 1)
                if (rotationIndex > 0 && rotationIndex <= substitutes.size) {
                    val rotatedExercise = exerciseRepository.findById(substitutes[rotationIndex - 1].substituteExercise.id).orElse(null)
                    if (rotatedExercise != null) {
                        logger.info("Rotated isolation exercise: ${actualExercise.name} → ${rotatedExercise.name} (week ${position.week}, rotation #$rotationIndex)")
                        actualExercise = rotatedExercise
                    }
                }
            }

            // Record muscle groups for this exercise (keyed by original pde exercise id for volume map lookup)
            exerciseMuscleGroupsMap[pde.exercise.id] = actualExercise.muscleGroups.map { it.name.lowercase() }.toSet()

            // Calculate suggested weight
            val rawWeight = progressiveOverloadService.calculateWeight(
                user = user,
                exercise = actualExercise,
                enrollment = enrollment,
                position = position,
                dayExercise = pde
            )

            // FIX 3 + FIX 5: Apply readiness and age multipliers to suggested weight
            val suggestedWeight = rawWeight?.let { it * readiness.intensityMultiplier * ageMultiplier }

            // FIX 1: Apply BLOCK phase sets/reps
            val adjustedSets = if (blockPhaseAdj != null) {
                (pde.sets * blockPhaseAdj.setsMultiplier).roundToInt().coerceAtLeast(2)
            } else pde.sets
            val adjustedMinReps = blockPhaseAdj?.repRangeLow ?: pde.minReps
            val adjustedMaxReps = blockPhaseAdj?.repRangeHigh ?: pde.maxReps

            // Generate warmup sets for compound exercises
            var warmupSets = if (pde.isCompound && suggestedWeight != null && suggestedWeight > 20.0) {
                generateWarmupSets(suggestedWeight)
            } else {
                emptyList()
            }

            // FIX 5: Add extra warmup set for users 50+
            if (userAge != null && userAge >= 50 && warmupSets.isNotEmpty()) {
                warmupSets = listOf(ProgramWarmupSet(weight = 10.0, reps = 15)) + warmupSets
            }

            // Build substitute list (injury-aware) — reuse already-fetched substitutes
            val substituteResponses = substitutes.map { sub ->
                ProgramSubstituteExercise(
                    exerciseId = sub.substituteExercise.id,
                    name = sub.substituteExercise.name,
                    reason = sub.reason.name
                )
            }

            ProgramGeneratedExercise(
                exerciseId = actualExercise.id,
                name = actualExercise.name,
                sets = adjustedSets,
                minReps = adjustedMinReps,
                maxReps = adjustedMaxReps,
                restSeconds = pde.restSeconds,
                suggestedWeight = suggestedWeight,
                targetRPE = pde.targetRPE,
                isCompound = pde.isCompound,
                warmupSets = warmupSets,
                substitutes = substituteResponses
            )
        }

        // 9. Check graduation status
        val graduationStatus = graduationService.checkGraduation(enrollment).let { gs ->
            GraduationStatusDto(
                shouldGraduate = gs.shouldGraduate,
                completionRate = gs.completionRate,
                nextProgramCode = gs.nextProgramCode,
                nextProgramName = gs.nextProgramName,
                message = gs.reason
            )
        }

        // 10. Compute weekly volume status
        val weeklyVolume = computeWeeklyVolumeStatus(user, dayExercises, generatedExercises)

        // 11. Volume-reactive set adjustment: add/remove 1 set based on BELOW_MEV / ABOVE_MAV
        val adjustedExercises = generatedExercises.mapIndexed { index, exercise ->
            val pde = dayExercises[index]
            val muscleGroups = exerciseMuscleGroupsMap[pde.exercise.id] ?: emptySet()

            val belowMev = weeklyVolume.any { vol ->
                muscleGroups.any { it.contains(vol.muscleGroup, ignoreCase = true) || vol.muscleGroup.contains(it, ignoreCase = true) } &&
                    vol.status == "BELOW_MEV"
            }
            val aboveMav = weeklyVolume.any { vol ->
                muscleGroups.any { it.contains(vol.muscleGroup, ignoreCase = true) || vol.muscleGroup.contains(it, ignoreCase = true) } &&
                    vol.status == "ABOVE_MAV"
            }

            when {
                belowMev && exercise.sets < 5 -> exercise.copy(sets = exercise.sets + 1)
                aboveMav && exercise.sets > 2 -> exercise.copy(sets = exercise.sets - 1)
                else -> exercise
            }
        }

        // 12. Return GeneratedWorkout
        return GeneratedWorkout(
            programName = program.name,
            weekNumber = position.week,
            dayNumber = position.dayInCycle,
            dayName = programDay.name,
            isDeloadWeek = position.isDeloadWeek,
            periodizationPhase = resolvePeriodizationPhase(program.progressionModel, position),
            workoutType = programDay.workoutType,
            estimatedDuration = programDay.estimatedDurationMinutes,
            exercises = adjustedExercises,
            graduationStatus = if (graduationStatus.shouldGraduate) graduationStatus else null,
            weeklyVolume = weeklyVolume,
            readinessScore = readiness
        )
    }

    private fun computeReadiness(user: User, enrollment: UserProgramEnrollment): ReadinessScore {
        val factors = mutableListOf<String>()
        var score = 1.0

        // Factor 1: Days since last workout
        val daysSinceLast = if (enrollment.lastActiveDate != null) {
            ChronoUnit.DAYS.between(enrollment.lastActiveDate, AppTime.utcNow())
        } else 3

        when {
            daysSinceLast == 0L -> { score -= 0.1; factors.add("오늘 이미 운동함 - 회복 부족 가능") }
            daysSinceLast == 1L -> { factors.add("적절한 회복 시간") }
            daysSinceLast in 2..3 -> { factors.add("충분한 회복") }
            daysSinceLast > 3 -> { score -= 0.05; factors.add("${daysSinceLast}일 공백 - 가볍게 시작 권장") }
        }

        // Factor 2: Recent RPE trend (from last 3 sessions)
        val recentSessions = workoutSessionRepository.findByUserAndStatusInOrderByStartTimeDesc(
            user, listOf(SessionStatus.COMPLETED), PageRequest.of(0, 3)
        )
        val recentRPEs = recentSessions.flatMap { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                .flatMap { we -> exerciseSetRepository.findByWorkoutExerciseId(we.id) }
                .mapNotNull { it.rpe?.toDouble() }
        }
        val avgRecentRPE = if (recentRPEs.isNotEmpty()) recentRPEs.average() else 7.0

        when {
            avgRecentRPE >= 9.0 -> { score -= 0.15; factors.add("최근 고강도 훈련 - 강도 조절 권장") }
            avgRecentRPE >= 8.0 -> { score -= 0.05; factors.add("최근 적절한 강도") }
            avgRecentRPE < 6.0 -> { score += 0.05; factors.add("최근 낮은 강도 - 여유 있음") }
            else -> { factors.add("정상 컨디션") }
        }

        score = score.coerceIn(0.5, 1.1)

        val intensityMultiplier = when {
            score >= 0.95 -> 1.0
            score >= 0.85 -> 0.95
            score >= 0.75 -> 0.90
            else -> 0.85
        }

        return ReadinessScore(
            score = score,
            factors = factors,
            intensityMultiplier = intensityMultiplier
        )
    }

    private fun computeWeeklyVolumeStatus(
        user: User,
        dayExercises: List<com.richjun.liftupai.domain.workout.entity.ProgramDayExercise>,
        generatedExercises: List<ProgramGeneratedExercise>
    ): List<WeeklyVolumeStatus> {
        // Count sets per muscle group from this week's completed sessions
        val weekStart = AppTime.utcNow().with(java.time.DayOfWeek.MONDAY).toLocalDate().atStartOfDay()
        val thisWeekSessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, weekStart)
            .filter { it.status == SessionStatus.COMPLETED }

        val completedSetsPerMuscle = mutableMapOf<String, Int>()
        thisWeekSessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { we ->
                val setCount = we.sets.size
                we.exercise.muscleGroups.forEach { mg ->
                    val key = mg.name.lowercase()
                    completedSetsPerMuscle[key] = completedSetsPerMuscle.getOrDefault(key, 0) + setCount
                }
            }
        }

        // Add today's planned sets per muscle group (from dayExercises which have the Exercise entity)
        val plannedSetsPerMuscle = mutableMapOf<String, Int>()
        val plannedSetsByExerciseId = generatedExercises.associateBy({ it.exerciseId }, { it.sets })
        dayExercises.forEach { pde ->
            val sets = plannedSetsByExerciseId[pde.exercise.id] ?: pde.sets
            pde.exercise.muscleGroups.forEach { mg ->
                val key = mg.name.lowercase()
                plannedSetsPerMuscle[key] = plannedSetsPerMuscle.getOrDefault(key, 0) + sets
            }
        }

        // Merge completed + planned
        val allMuscles = (completedSetsPerMuscle.keys + plannedSetsPerMuscle.keys).toSet()
        return allMuscles.map { muscle ->
            val totalSets = completedSetsPerMuscle.getOrDefault(muscle, 0) +
                plannedSetsPerMuscle.getOrDefault(muscle, 0)
            val mev = progressionService.getMEV(muscle)
            val mav = progressionService.getMAV(muscle)
            val status = when {
                totalSets < mev -> "BELOW_MEV"
                totalSets > mav -> "ABOVE_MAV"
                else -> "ON_TARGET"
            }
            WeeklyVolumeStatus(
                muscleGroup = muscle,
                currentSets = totalSets,
                mevSets = mev,
                mavSets = mav,
                status = status
            )
        }.sortedBy { it.muscleGroup }
    }

    private fun generateWarmupSets(workingWeight: Double): List<ProgramWarmupSet> {
        val sets = mutableListOf<ProgramWarmupSet>()

        // Empty bar (20kg) × 10 — always included when working weight > 20
        sets.add(ProgramWarmupSet(weight = 20.0, reps = 10))

        if (workingWeight > 40.0) {
            sets.add(ProgramWarmupSet(weight = roundToNearest25(workingWeight * 0.40), reps = 8))
        }
        if (workingWeight > 60.0) {
            sets.add(ProgramWarmupSet(weight = roundToNearest25(workingWeight * 0.60), reps = 5))
        }
        if (workingWeight > 80.0) {
            sets.add(ProgramWarmupSet(weight = roundToNearest25(workingWeight * 0.75), reps = 3))
        }

        return sets
    }

    private fun roundToNearest25(weight: Double): Double {
        return (weight / 2.5).toLong() * 2.5
    }

    private fun resolvePeriodizationPhase(model: ProgressionModel, position: ProgramPosition): String {
        if (position.isDeloadWeek) return "DELOAD"
        return when (model) {
            ProgressionModel.LINEAR -> "LINEAR_PROGRESSION"
            ProgressionModel.UNDULATING -> when (position.dayInCycle % 3) {
                1 -> "HEAVY"
                2 -> "MEDIUM"
                else -> "LIGHT"
            }
            ProgressionModel.BLOCK -> when ((position.week - 1) % 7) {
                0, 1 -> "ACCUMULATION"
                2, 3 -> "INTENSIFICATION"
                4, 5 -> "REALIZATION"
                else -> "DELOAD"
            }
        }
    }
}

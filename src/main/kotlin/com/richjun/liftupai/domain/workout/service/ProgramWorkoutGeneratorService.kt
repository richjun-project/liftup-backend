package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.GeneratedWorkout
import com.richjun.liftupai.domain.workout.dto.GraduationStatusDto
import com.richjun.liftupai.domain.workout.dto.ProgramGeneratedExercise
import com.richjun.liftupai.domain.workout.dto.ProgramSubstituteExercise
import com.richjun.liftupai.domain.workout.dto.ProgramWarmupSet
import com.richjun.liftupai.domain.workout.dto.WeeklyVolumeStatus
import com.richjun.liftupai.domain.workout.entity.ProgressionModel
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    private val progressionService: ProgramProgressionService
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

        // 6 & 7 & 8. Build exercise list applying overrides, substitutions, weights, warmups
        val generatedExercises = dayExercises.map { pde ->
            val override = overrideMap[pde.exercise.id]
            val actualExercise = override?.substituteExercise ?: pde.exercise

            // 7. Calculate suggested weight
            val suggestedWeight = progressiveOverloadService.calculateWeight(
                user = user,
                exercise = actualExercise,
                enrollment = enrollment,
                position = position,
                dayExercise = pde
            )

            // 8. Generate warmup sets for compound exercises
            val warmupSets = if (pde.isCompound && suggestedWeight != null && suggestedWeight > 20.0) {
                generateWarmupSets(suggestedWeight)
            } else {
                emptyList()
            }

            // Build substitute list (injury-aware)
            val profileInjuries = userProfileRepository.findByUser_Id(user.id).orElse(null)?.injuries ?: emptySet()
            val settingsInjuries = userSettingsRepository.findByUser_Id(user.id).orElse(null)?.injuries ?: emptySet()
            val injuries = profileInjuries + settingsInjuries
            val substitutes = exerciseSubstitutionService
                .getSubstitutesForInjury(actualExercise.id, injuries)
                .map { sub ->
                    ProgramSubstituteExercise(
                        exerciseId = sub.substituteExercise.id,
                        name = sub.substituteExercise.name,
                        reason = sub.reason.name
                    )
                }

            ProgramGeneratedExercise(
                exerciseId = actualExercise.id,
                name = actualExercise.name,
                sets = pde.sets,
                minReps = pde.minReps,
                maxReps = pde.maxReps,
                restSeconds = pde.restSeconds,
                suggestedWeight = suggestedWeight,
                targetRPE = pde.targetRPE,
                isCompound = pde.isCompound,
                warmupSets = warmupSets,
                substitutes = substitutes
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

        // 11. Return GeneratedWorkout
        return GeneratedWorkout(
            programName = program.name,
            weekNumber = position.week,
            dayNumber = position.dayInCycle,
            dayName = programDay.name,
            isDeloadWeek = position.isDeloadWeek,
            periodizationPhase = resolvePeriodizationPhase(program.progressionModel, position),
            workoutType = programDay.workoutType,
            estimatedDuration = programDay.estimatedDurationMinutes,
            exercises = generatedExercises,
            graduationStatus = if (graduationStatus.shouldGraduate) graduationStatus else null,
            weeklyVolume = weeklyVolume
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

package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.ProgramDayExercise
import com.richjun.liftupai.domain.workout.entity.ProgressionModel
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.roundToInt

data class BlockPhaseAdjustment(
    val intensityPercent: Double,
    val setsMultiplier: Double,  // 1.0 = keep seed data sets, 0.75 = reduce 25%
    val repRangeLow: Int,
    val repRangeHigh: Int
)

@Service
@Transactional(readOnly = true)
class ProgramProgressiveOverloadService(
    private val exerciseSetRepository: ExerciseSetRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val userProfileRepository: UserProfileRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Calculate suggested weight for the given exercise in the current program position.
     * Returns null when there is no history (frontend shows "자유 선택").
     */
    fun calculateWeight(
        user: User,
        exercise: Exercise,
        enrollment: UserProgramEnrollment,
        position: ProgramPosition,
        dayExercise: ProgramDayExercise
    ): Double? {
        val lastSets = getLastWorkoutSets(user, exercise)
        if (lastSets.isEmpty()) {
            // Try to use estimatedMaxes from profile
            val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
            val estimatedMaxes = profile?.estimatedMaxes
            if (estimatedMaxes != null) {
                try {
                    val maxesMap = objectMapper.readValue<Map<String, Double>>(estimatedMaxes)
                    val exerciseKey = exercise.slug
                    val est1RM = maxesMap[exerciseKey]
                        ?: maxesMap.entries.firstOrNull { exerciseKey.contains(it.key) || it.key.contains(exerciseKey) }?.value
                    if (est1RM != null && est1RM > 0) {
                        return roundWeight(exercise, est1RM)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse estimatedMaxes: ${e.message}")
                }
            }
            return null
        }

        val lastWeight = lastSets.maxOf { it.weight }
        val lastRepsAchieved = lastSets.maxOf { it.reps }
        val lastRPE = lastSets.mapNotNull { it.rpe?.toDouble() }.average().let {
            if (it.isNaN()) 7.0 else it
        }

        val progressionModel = enrollment.program.progressionModel

        // Deload week: BLOCK uses its own 7-week block deload only; other models use the generic flag
        val isDeload = when (progressionModel) {
            ProgressionModel.BLOCK -> isBlockDeloadWeek(progressionModel, position)
            else -> position.isDeloadWeek
        }
        if (isDeload) {
            return roundWeight(exercise, calculateDeloadWeight(lastWeight, progressionModel))
        }

        var targetWeight: Double = when (progressionModel) {
            ProgressionModel.LINEAR -> calculateLinear(
                exercise, dayExercise, lastWeight, lastRepsAchieved, lastRPE
            )
            ProgressionModel.UNDULATING -> calculateUndulating(
                user, exercise, position, lastWeight, lastRPE
            )
            ProgressionModel.BLOCK -> calculateBlock(
                user, exercise, position, lastWeight
            )
        }

        // RPE auto-regulation (all models)
        targetWeight = when {
            lastRPE >= 9.0 -> targetWeight * 0.95
            lastRPE <= 5.0 -> targetWeight * 1.05
            else -> targetWeight
        }

        // Plateau detection: same weight for 3+ sessions → reduce 10% and rebuild
        val plateauSessionCount = 3
        val plateauSetCount = plateauSessionCount * 3  // ~3 sets per session
        if (lastSets.size >= plateauSetCount) {
            val recentWeights = lastSets.take(plateauSetCount).map { it.weight }.distinct()
            if (recentWeights.size == 1) {
                logger.info(
                    "Plateau detected for exercise {}: {}kg for {}+ sessions — reducing 10%",
                    exercise.name, recentWeights.first(), plateauSessionCount
                )
                targetWeight = lastWeight * 0.90
            }
        }

        // Clamp: ±10% / ±20% of last weight
        targetWeight = targetWeight.coerceIn(lastWeight * 0.80, lastWeight * 1.10)

        return roundWeight(exercise, targetWeight)
    }

    // ---- LINEAR (beginner) ----

    private fun calculateLinear(
        exercise: Exercise,
        dayExercise: ProgramDayExercise,
        lastWeight: Double,
        lastRepsAchieved: Int,
        lastRPE: Double
    ): Double {
        val minReps = dayExercise.minReps
        val maxReps = dayExercise.maxReps

        return when {
            // Hit top of rep range with good RPE → add weight (double progression)
            lastRepsAchieved >= maxReps && lastRPE <= 8.0 -> {
                val increment = when {
                    exercise.category == ExerciseCategory.LEGS -> 5.0
                    exercise.movementPattern?.uppercase() in listOf("HIP_HINGE", "DEADLIFT") -> 5.0
                    else -> 2.5  // All upper body including back rows/pulldowns
                }
                lastWeight + increment
            }
            // RPE too high → keep weight (don't increase even if reps met)
            lastRPE > 9.0 -> lastWeight
            // Mid-range reps → keep weight, user should aim for more reps next session
            lastRepsAchieved >= minReps -> lastWeight  // Stay, try to add 1 rep next time
            // Below minReps → weight might be too heavy, reduce slightly
            else -> lastWeight * 0.95
        }
    }

    // ---- UNDULATING (intermediate) ----

    private fun calculateUndulating(
        user: User,
        exercise: Exercise,
        position: ProgramPosition,
        lastWeight: Double,
        lastRPE: Double
    ): Double {
        val estimated1RM = getEstimated1RM(user, exercise) ?: return lastWeight

        val dayIntensity = when (position.dayInCycle % 3) {
            1 -> 0.85   // HEAVY
            2 -> 0.725  // MEDIUM
            else -> 0.625 // LIGHT (0)
        }

        var targetWeight = estimated1RM * dayIntensity

        // Weekly progression: if avg RPE < 7.5, increase by 1.5%
        if (lastRPE < 7.5) {
            targetWeight *= 1.015
        }

        return targetWeight
    }

    // ---- BLOCK (advanced) ----

    private fun calculateBlock(
        user: User,
        exercise: Exercise,
        position: ProgramPosition,
        lastWeight: Double
    ): Double {
        val estimated1RM = getEstimated1RM(user, exercise) ?: return lastWeight

        // 7-week blocks: 2+2+2+1deload (deload already handled above)
        val blockPhase = (position.week - 1) % 7
        val intensityFactor = when (blockPhase) {
            0, 1 -> 0.70   // ACCUMULATION
            2, 3 -> 0.80   // INTENSIFICATION
            4, 5 -> 0.88   // REALIZATION
            else -> 0.55   // deload — already handled but just in case
        }

        return estimated1RM * intensityFactor
    }

    // ---- Helpers ----

    private fun getLastWorkoutSets(user: User, exercise: Exercise): List<ExerciseSet> {
        val since = AppTime.utcNow().minusWeeks(8)
        return exerciseSetRepository.findCompletedSetsByUserAndExercise(
            userId = user.id,
            exerciseId = exercise.id,
            since = since
        ).take(10)
    }

    private fun getEstimated1RM(user: User, exercise: Exercise): Double? {
        // Try personal record first
        val pr = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)
        if (pr != null && pr.reps > 0) {
            // Epley formula: 1RM = weight * (1 + reps/30)
            return pr.weight * (1.0 + pr.reps / 30.0)
        }

        // Fall back to recent sets
        val recentSets = getLastWorkoutSets(user, exercise)
        if (recentSets.isEmpty()) return null

        return recentSets.maxOf { set ->
            set.weight * (1.0 + set.reps / 30.0)
        }
    }

    private fun calculateDeloadWeight(
        lastWeight: Double,
        progressionModel: ProgressionModel
    ): Double = when (progressionModel) {
        ProgressionModel.LINEAR -> lastWeight * 0.55      // Full deload for beginners
        ProgressionModel.UNDULATING -> lastWeight * 0.75  // Volume deload: same intensity, fewer sets
        ProgressionModel.BLOCK -> lastWeight * 0.70       // Intensity deload: preserve neural drive
    }

    /**
     * For BLOCK periodization, week 7 of a 7-week block is the deload week.
     * This takes priority over the modulo-based isDeloadWeek flag.
     */
    /**
     * Returns the block phase adjustment (intensity %, sets multiplier, rep range) for the given position.
     * Used by ProgramWorkoutGeneratorService to apply volume waving within BLOCK phases.
     */
    fun getBlockPhaseAdjustment(position: ProgramPosition): BlockPhaseAdjustment {
        val weekInBlock = (position.week - 1) % 7
        return when {
            weekInBlock in 0..1 -> BlockPhaseAdjustment(  // Accumulation
                intensityPercent = 0.70,
                setsMultiplier = 1.0,     // Full volume
                repRangeLow = 10, repRangeHigh = 12
            )
            weekInBlock in 2..3 -> BlockPhaseAdjustment(  // Intensification
                intensityPercent = 0.80,
                setsMultiplier = 0.85,    // Reduce volume 15%
                repRangeLow = 6, repRangeHigh = 8
            )
            weekInBlock in 4..5 -> BlockPhaseAdjustment(  // Realization
                intensityPercent = 0.88,
                setsMultiplier = 0.70,    // Reduce volume 30%
                repRangeLow = 3, repRangeHigh = 5
            )
            else -> BlockPhaseAdjustment(  // Deload week 7
                intensityPercent = 0.55,
                setsMultiplier = 0.50,
                repRangeLow = 12, repRangeHigh = 15
            )
        }
    }

    private fun isBlockDeloadWeek(model: ProgressionModel, position: ProgramPosition): Boolean {
        if (model != ProgressionModel.BLOCK) return false
        val blockPhase = (position.week - 1) % 7
        return blockPhase == 6  // week 7 of 7-week block
    }

    private fun roundWeight(exercise: Exercise, weight: Double): Double {
        return when (exercise.equipment) {
            Equipment.DUMBBELL -> (weight / 2.0).roundToInt() * 2.0
            else -> (weight / 2.5).roundToInt() * 2.5  // barbell default
        }
    }
}

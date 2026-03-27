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
        val lastRepsAchieved = lastSets.map { it.reps }.average().toInt()
        val lastRPE = lastSets.mapNotNull { it.rpe?.toDouble() }.average().let {
            if (it.isNaN()) 7.0 else it
        }

        val genderProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val isFemale = genderProfile?.gender?.lowercase() == "female"

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
                exercise, dayExercise, lastWeight, lastRepsAchieved, lastRPE, isFemale
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

        // Plateau detection: volume (weight × avg reps) 기반
        // 무게만 비교하면 100kg×6→100kg×8→100kg×10 (진행 중)을 정체로 오판함
        val sessionVolumes = lastSets
            .groupBy { it.workoutExercise.session.id }
            .map { (_, sets) ->
                val maxWeight = sets.maxOf { it.weight }
                val avgReps = sets.map { it.reps }.average()
                maxWeight * avgReps  // 볼륨 = 무게 × 평균 렙수
            }
            .take(3)

        if (sessionVolumes.size >= 3) {
            val volumeRange = sessionVolumes.max() - sessionVolumes.min()
            val avgVolume = sessionVolumes.average()
            // 볼륨 변동이 평균의 3% 이내이면 정체
            if (avgVolume > 0 && volumeRange / avgVolume < 0.03) {
                logger.info("Plateau detected: volume ~${avgVolume.roundToInt()} for ${sessionVolumes.size} sessions")
                targetWeight = lastWeight * 0.90
            }
        }

        // LINEAR: reload if below minReps for 2 consecutive sessions
        if (progressionModel == ProgressionModel.LINEAR) {
            val recentMaxReps = lastSets.take(6)
                .groupBy { it.workoutExercise.session.id }
                .values
                .map { sets -> sets.map { it.reps }.average().toInt() }

            if (recentMaxReps.size >= 2 && recentMaxReps.all { it < dayExercise.minReps }) {
                targetWeight = lastWeight * 0.85
                logger.info("LINEAR reload: ${exercise.name} failed minReps for 2 sessions, reducing to 85%")
            }
        }

        // Clamp: ±10% / ±20% of last weight
        targetWeight = targetWeight.coerceIn(lastWeight * 0.80, lastWeight * 1.10)

        // Bodyweight-relative safety ceiling — 경험 수준에 따라 상한 조정
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val bodyWeight = profile?.bodyInfo?.weight ?: 70.0
        val experienceMultiplier = when (profile?.experienceLevel?.name?.uppercase()) {
            "BEGINNER", "NOVICE" -> 0.7  // 초보자는 상한 30% 하향
            "INTERMEDIATE" -> 1.0
            "ADVANCED", "EXPERT" -> 1.2  // 상급자는 상한 20% 상향
            else -> 0.85
        }
        val maxSafeMultiplier = experienceMultiplier * when (exercise.category) {
            ExerciseCategory.LEGS -> 2.0
            ExerciseCategory.BACK -> 2.0
            ExerciseCategory.CHEST -> 1.5
            ExerciseCategory.SHOULDERS -> 1.0
            else -> 1.0
        }
        targetWeight = targetWeight.coerceAtMost(bodyWeight * maxSafeMultiplier)

        return roundWeight(exercise, targetWeight)
    }

    // ---- LINEAR (beginner) ----

    private fun calculateLinear(
        exercise: Exercise,
        dayExercise: ProgramDayExercise,
        lastWeight: Double,
        lastRepsAchieved: Int,
        lastRPE: Double,
        isFemale: Boolean = false
    ): Double {
        val minReps = dayExercise.minReps
        val maxReps = dayExercise.maxReps

        return when {
            // Hit top of rep range with good RPE → add weight (double progression)
            lastRepsAchieved >= maxReps && lastRPE <= 8.0 -> {
                val isIsolation = exercise.movementPattern?.uppercase()?.let {
                    it.contains("CURL") || it.contains("EXTENSION") || it.contains("RAISE") ||
                        it.contains("PUSHDOWN") || it.contains("FLY") || it.contains("KICKBACK")
                } ?: false
                val increment = when {
                    exercise.category == ExerciseCategory.LEGS -> if (isFemale) 2.5 else 5.0
                    exercise.movementPattern?.uppercase() in listOf("HIP_HINGE", "DEADLIFT") -> if (isFemale) 2.5 else 5.0
                    isIsolation -> if (isFemale) 1.0 else 1.25
                    else -> if (isFemale) 1.25 else 2.5  // Compound upper body
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

        // Week-over-week ramp: +2% per week within the mesocycle
        val weekInMesocycle = ((position.week - 1) % 4).coerceAtMost(3)  // 0-3
        val weeklyRamp = 1.0 + (weekInMesocycle * 0.02)  // 1.0, 1.02, 1.04, 1.06
        targetWeight *= weeklyRamp

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
            return getEstimated1RM(pr.weight, pr.reps)
        }

        // Fall back to recent sets
        val recentSets = getLastWorkoutSets(user, exercise)
        if (recentSets.isEmpty()) return null

        return recentSets.maxOf { set ->
            getEstimated1RM(set.weight, set.reps, set.rpe?.toDouble())
        }
    }

    private fun getEstimated1RM(weight: Double, reps: Int, rpe: Double? = null): Double {
        val raw1RM = if (reps <= 0) weight
            else if (reps == 1) weight
            else if (reps <= 10) weight * (36.0 / (37.0 - reps))  // Brzycki
            else weight * (1 + reps / 30.0)  // Epley for high reps

        // RPE adjustment: if RPE < 10, user had reps in reserve
        val rpeMultiplier = if (rpe != null && rpe < 10.0) {
            1.0 + (10.0 - rpe) * 0.025  // Each RIR adds ~2.5% to true 1RM
        } else 1.0

        return raw1RM * rpeMultiplier
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
     * Returns the block phase adjustment (intensity %, sets multiplier, rep range) for the given position.
     * Used by ProgramWorkoutGeneratorService to apply volume waving within BLOCK phases.
     * For BLOCK periodization, week 7 of a 7-week block is the deload week.
     */
    fun getBlockPhaseAdjustment(position: ProgramPosition): BlockPhaseAdjustment {
        val weekInBlock = (position.week - 1) % 7
        return when {
            weekInBlock in 0..1 -> {
                val weekInPhase = weekInBlock  // 0 or 1
                BlockPhaseAdjustment(
                    intensityPercent = 0.68 + (weekInPhase * 0.04),  // 0.68 → 0.72
                    setsMultiplier = 1.0,
                    repRangeLow = 10, repRangeHigh = 12
                )
            }
            weekInBlock in 2..3 -> {
                val weekInPhase = weekInBlock - 2
                BlockPhaseAdjustment(
                    intensityPercent = 0.78 + (weekInPhase * 0.04),  // 0.78 → 0.82
                    setsMultiplier = 0.85,
                    repRangeLow = 6, repRangeHigh = 8
                )
            }
            weekInBlock in 4..5 -> {
                val weekInPhase = weekInBlock - 4
                BlockPhaseAdjustment(
                    intensityPercent = 0.86 + (weekInPhase * 0.04),  // 0.86 → 0.90
                    setsMultiplier = 0.70,
                    repRangeLow = 3, repRangeHigh = 5
                )
            }
            else -> BlockPhaseAdjustment(0.55, 0.50, 12, 15)  // Deload
        }
    }

    private fun isBlockDeloadWeek(model: ProgressionModel, position: ProgramPosition): Boolean {
        if (model != ProgressionModel.BLOCK) return false
        val blockPhase = (position.week - 1) % 7
        return blockPhase == 6  // week 7 of 7-week block
    }

    private fun roundWeight(exercise: Exercise, weight: Double): Double {
        val increment = when {
            exercise.equipment == Equipment.DUMBBELL -> 2.0
            weight < 20.0 -> 1.25
            else -> 2.5
        }
        return (weight / increment).roundToInt() * increment
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.CanonicalProgram
import com.richjun.liftupai.domain.workout.entity.WorkoutGoal
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CanonicalProgramService(
    private val canonicalProgramRepository: CanonicalProgramRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun getAllActivePrograms(): List<CanonicalProgram> {
        return canonicalProgramRepository.findByIsActiveTrue()
    }

    fun getProgramByCode(code: String): CanonicalProgram {
        return canonicalProgramRepository.findByCode(code)
            ?: throw ResourceNotFoundException("Program not found with code: $code")
    }

    fun getRecommendedProgram(user: User): CanonicalProgram {
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val settings = userSettingsRepository.findByUser_Id(user.id).orElse(null)

        val experienceLevel = profile?.experienceLevel ?: ExperienceLevel.BEGINNER
        val weeklyDays = settings?.weeklyWorkoutDays ?: profile?.weeklyWorkoutDays ?: 3

        // Map FitnessGoal to WorkoutGoal — use MUSCLE_GAIN as default if no mapping found
        val workoutGoal = resolveWorkoutGoal(profile?.goals)

        logger.debug(
            "Finding recommended program for user={} level={} goal={} days={}",
            user.id, experienceLevel, workoutGoal, weeklyDays
        )

        // Exact match by experience + goal
        val exactMatches = canonicalProgramRepository
            .findByTargetExperienceLevelAndTargetGoalAndIsActiveTrue(experienceLevel, workoutGoal)

        if (exactMatches.isNotEmpty()) {
            return exactMatches.minByOrNull { Math.abs(it.daysPerWeek - weeklyDays) }!!
        }

        // Fallback: try same experience level with any goal
        val allActive = canonicalProgramRepository.findByIsActiveTrue()
        val byLevel = allActive.filter { it.targetExperienceLevel == experienceLevel }
        if (byLevel.isNotEmpty()) {
            return byLevel.minByOrNull { Math.abs(it.daysPerWeek - weeklyDays) }!!
        }

        // Final fallback: closest experience level then goal
        val fallback = allActive
            .sortedWith(compareBy(
                { experienceLevelDistance(it.targetExperienceLevel, experienceLevel) },
                { Math.abs(it.daysPerWeek - weeklyDays) }
            ))
        return fallback.firstOrNull()
            ?: throw ResourceNotFoundException("No active canonical programs found")
    }

    private fun resolveWorkoutGoal(goals: Set<com.richjun.liftupai.domain.user.entity.FitnessGoal>?): WorkoutGoal {
        if (goals.isNullOrEmpty()) return WorkoutGoal.GENERAL_FITNESS
        return when {
            com.richjun.liftupai.domain.user.entity.FitnessGoal.STRENGTH in goals -> WorkoutGoal.STRENGTH
            com.richjun.liftupai.domain.user.entity.FitnessGoal.MUSCLE_GAIN in goals -> WorkoutGoal.MUSCLE_GAIN
            com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS in goals -> WorkoutGoal.FAT_LOSS
            com.richjun.liftupai.domain.user.entity.FitnessGoal.ENDURANCE in goals -> WorkoutGoal.ENDURANCE
            else -> WorkoutGoal.GENERAL_FITNESS
        }
    }

    private fun experienceLevelDistance(a: ExperienceLevel, b: ExperienceLevel): Int {
        val order = listOf(
            ExperienceLevel.BEGINNER,
            ExperienceLevel.NOVICE,
            ExperienceLevel.INTERMEDIATE,
            ExperienceLevel.ADVANCED,
            ExperienceLevel.EXPERT
        )
        return Math.abs(order.indexOf(a) - order.indexOf(b))
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.InjuryExerciseRestriction
import com.richjun.liftupai.domain.workout.entity.InjurySeverity
import com.richjun.liftupai.domain.workout.entity.SubstitutionReason
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.domain.workout.entity.UserExerciseOverride
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class InjuryFilterService(
    private val injuryExerciseRestrictionRepository: InjuryExerciseRestrictionRepository,
    private val programDayRepository: ProgramDayRepository,
    private val programDayExerciseRepository: ProgramDayExerciseRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val exerciseRepository: ExerciseRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun getRestrictedExercises(injuries: Set<String>): List<InjuryExerciseRestriction> {
        if (injuries.isEmpty()) return emptyList()
        return injuries.flatMap { injury ->
            val injuryType = injury.substringBefore(":")
            val userSeverity = injury.substringAfter(":", "ALL")

            // MILD: only block the most dangerous exercises (SEVERE restrictions)
            // MODERATE: block MODERATE and SEVERE
            // SEVERE or ALL (legacy format without severity): block all restrictions
            val applicableSeverities = when (userSeverity.uppercase()) {
                "MILD" -> listOf(InjurySeverity.SEVERE)
                "MODERATE" -> listOf(InjurySeverity.MODERATE, InjurySeverity.SEVERE)
                else -> InjurySeverity.values().toList()
            }

            injuryExerciseRestrictionRepository.findByInjuryTypeAndSeverityIn(injuryType, applicableSeverities)
        }.distinctBy { it.id }
    }

    fun filterExercises(exercises: List<Exercise>, injuries: Set<String>): List<Exercise> {
        if (injuries.isEmpty()) return exercises
        val restrictions = getRestrictedExercises(injuries)
        val restrictedIds = restrictions.map { it.restrictedExercise.id }.toSet()
        return exercises.filter { it.id !in restrictedIds }
    }

    @Transactional
    fun autoApplyOverrides(enrollment: UserProgramEnrollment, injuries: Set<String>) {
        if (injuries.isEmpty()) return

        val restrictions = getRestrictedExercises(injuries)
        if (restrictions.isEmpty()) return

        val restrictedById = restrictions.groupBy { it.restrictedExercise.id }

        // Get all program days for this enrollment's program
        val programDays = programDayRepository.findByProgramIdOrderByDayNumber(enrollment.program.id)

        for (day in programDays) {
            val dayExercises = programDayExerciseRepository.findByDayIdWithExercises(day.id)
            for (pde in dayExercises) {
                val exerciseId = pde.exercise.id
                val matchingRestrictions = restrictedById[exerciseId] ?: continue

                // Already has an override? Skip
                val existingOverride = userExerciseOverrideRepository
                    .findByEnrollmentIdAndOriginalExerciseId(enrollment.id, exerciseId)
                if (existingOverride != null) continue

                // Use the first restriction that has a suggested substitute
                val restriction = matchingRestrictions.firstOrNull { it.suggestedSubstitute != null }
                    ?: matchingRestrictions.first()

                val substituteExercise = restriction.suggestedSubstitute ?: run {
                    logger.warn(
                        "No substitute for restricted exercise={} injury={}",
                        exerciseId, restriction.injuryType
                    )
                    return@run null
                } ?: continue

                val override = UserExerciseOverride(
                    enrollment = enrollment,
                    originalExercise = pde.exercise,
                    substituteExercise = substituteExercise,
                    reason = SubstitutionReason.INJURY
                )
                userExerciseOverrideRepository.save(override)
                logger.info(
                    "Auto-applied injury override: enrollment={} original={} substitute={} injury={}",
                    enrollment.id, exerciseId, substituteExercise.id, restriction.injuryType
                )
            }
        }
    }
}

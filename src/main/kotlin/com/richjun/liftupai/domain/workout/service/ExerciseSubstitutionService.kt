package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.ExerciseSubstitution
import com.richjun.liftupai.domain.workout.entity.SubstitutionReason
import com.richjun.liftupai.domain.workout.entity.UserExerciseOverride
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSubstitutionRepository
import com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ExerciseSubstitutionService(
    private val exerciseSubstitutionRepository: ExerciseSubstitutionRepository,
    private val injuryExerciseRestrictionRepository: InjuryExerciseRestrictionRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val exerciseRepository: ExerciseRepository
) {

    fun getSubstitutes(exerciseId: Long): List<ExerciseSubstitution> {
        return exerciseSubstitutionRepository.findByOriginalExerciseIdOrderByPriority(exerciseId)
    }

    fun getSubstitutesForInjury(exerciseId: Long, injuries: Set<String>): List<ExerciseSubstitution> {
        if (injuries.isEmpty()) return getSubstitutes(exerciseId)

        // Collect substitute IDs suggested by injury restrictions for this exercise
        val injurySubstituteIds = injuries.flatMap { injury ->
            injuryExerciseRestrictionRepository.findByInjuryType(injury)
                .filter { it.restrictedExercise.id == exerciseId }
                .mapNotNull { it.suggestedSubstitute?.id }
        }.toSet()

        // Get general substitutes
        val generalSubs = exerciseSubstitutionRepository.findByOriginalExerciseIdOrderByPriority(exerciseId)

        // Prioritize injury-specific substitutes first
        return generalSubs.sortedBy { if (it.substituteExercise.id in injurySubstituteIds) 0 else 1 }
    }

    @Transactional
    fun applyOverride(
        enrollment: UserProgramEnrollment,
        originalExerciseId: Long,
        substituteExerciseId: Long,
        reason: SubstitutionReason
    ): UserExerciseOverride {
        val originalExercise = exerciseRepository.findById(originalExerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found: $originalExerciseId") }
        val substituteExercise = exerciseRepository.findById(substituteExerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found: $substituteExerciseId") }

        // Check if override already exists — update it
        val existing = userExerciseOverrideRepository
            .findByEnrollmentIdAndOriginalExerciseId(enrollment.id, originalExerciseId)

        if (existing != null) {
            // JPA entities are immutable val fields for reason; delete and recreate
            userExerciseOverrideRepository.delete(existing)
        }

        val override = UserExerciseOverride(
            enrollment = enrollment,
            originalExercise = originalExercise,
            substituteExercise = substituteExercise,
            reason = reason
        )
        return userExerciseOverrideRepository.save(override)
    }
}

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

    /**
     * 여러 부상이 있을 때 각 부상별 제한 운동을 합산(union)
     * → 어깨 부상 + 무릎 부상이면 어깨 제한 운동 ∪ 무릎 제한 운동 모두 제외
     *
     * 복합 부상 시 더 보수적으로 동작 (안전 우선)
     */
    fun getRestrictedExercises(injuries: Set<String>): List<InjuryExerciseRestriction> {
        if (injuries.isEmpty()) return emptyList()

        val allRestrictions = mutableMapOf<Long, InjuryExerciseRestriction>()

        injuries.forEach { injury ->
            val injuryType = injury.substringBefore(":")
            val userSeverity = injury.substringAfter(":", "ALL")

            // 부상 심각도별 제한 운동 필터링 기준:
            // - MILD 부상 → SEVERE 제한 운동만 차단 (가벼운 부상이므로 가장 위험한 운동만 피함)
            // - MODERATE 부상 → MODERATE+SEVERE 제한 운동 차단
            // - SEVERE 부상 → 모든 제한 운동 차단
            // 즉, 부상이 심할수록 더 많은 운동이 차단됨 (보수적 안전 원칙)
            val applicableSeverities = when (userSeverity.uppercase()) {
                "MILD" -> listOf(InjurySeverity.SEVERE)
                "MODERATE" -> listOf(InjurySeverity.MODERATE, InjurySeverity.SEVERE)
                else -> InjurySeverity.values().toList()
            }

            val restrictions = injuryExerciseRestrictionRepository.findByInjuryTypeAndSeverityIn(injuryType, applicableSeverities)
            restrictions.forEach { restriction ->
                val exerciseId = restriction.restrictedExercise.id
                val existing = allRestrictions[exerciseId]
                // 같은 운동이 여러 부상에 의해 제한되면, 더 높은 심각도를 유지
                if (existing == null || restriction.severity.ordinal > existing.severity.ordinal) {
                    allRestrictions[exerciseId] = restriction
                }
            }
        }

        return allRestrictions.values.toList()
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

                // 대체 운동 검증: DB에 존재하는지 + 같은 부상에 의해 제한되지 않는지
                val substituteExists = exerciseRepository.findById(substituteExercise.id).isPresent
                val substituteAlsoRestricted = restrictedById.containsKey(substituteExercise.id)
                if (!substituteExists || substituteAlsoRestricted) {
                    logger.warn(
                        "Substitute exercise invalid: id={} exists={} alsoRestricted={} for injury={}",
                        substituteExercise.id, substituteExists, substituteAlsoRestricted, restriction.injuryType
                    )
                    continue
                }

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

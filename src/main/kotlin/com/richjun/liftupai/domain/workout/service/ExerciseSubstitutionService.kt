package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
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
import kotlin.math.abs

@Service
@Transactional(readOnly = true)
class ExerciseSubstitutionService(
    private val exerciseSubstitutionRepository: ExerciseSubstitutionRepository,
    private val injuryExerciseRestrictionRepository: InjuryExerciseRestrictionRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val exerciseRepository: ExerciseRepository,
    private val injuryFilterService: InjuryFilterService
) {

    fun getSubstitutes(exerciseId: Long): List<ExerciseSubstitution> {
        val originalExercise = exerciseRepository.findById(exerciseId).orElse(null)
            ?: return exerciseSubstitutionRepository.findByOriginalExerciseIdOrderByPriority(exerciseId)

        val substitutes = exerciseSubstitutionRepository.findByOriginalExerciseIdOrderByPriority(exerciseId)

        // Sort by transfer efficiency score (descending) for science-based ranking
        return substitutes.sortedByDescending { sub ->
            calculateTransferScore(originalExercise, sub.substituteExercise)
        }
    }

    fun getSubstitutesForInjury(exerciseId: Long, injuries: Set<String>): List<ExerciseSubstitution> {
        if (injuries.isEmpty()) return getSubstitutes(exerciseId)

        val originalExercise = exerciseRepository.findById(exerciseId).orElse(null)

        // Collect substitute IDs suggested by injury restrictions for this exercise
        val injurySubstituteIds = injuries.flatMap { injury ->
            injuryExerciseRestrictionRepository.findByInjuryType(injury)
                .filter { it.restrictedExercise.id == exerciseId }
                .mapNotNull { it.suggestedSubstitute?.id }
        }.toSet()

        // Get general substitutes
        val generalSubs = exerciseSubstitutionRepository.findByOriginalExerciseIdOrderByPriority(exerciseId)

        // Safety filter: exclude substitutes that conflict with user's injuries
        val safeSubs = generalSubs.filter { sub ->
            !injuryFilterService.isExerciseRestricted(sub.substituteExercise.id, injuries)
        }

        // Sort by: injury-specific first, then by transfer efficiency score
        return safeSubs.sortedWith(
            compareBy<ExerciseSubstitution> { if (it.substituteExercise.id in injurySubstituteIds) 0 else 1 }
                .thenByDescending { sub ->
                    if (originalExercise != null) calculateTransferScore(originalExercise, sub.substituteExercise) else 0.0
                }
        )
    }

    /**
     * 대체 운동의 전이 효과(transfer efficiency) 점수를 계산한다.
     *
     * 점수 구성 (0.0 ~ 1.0):
     * - 근육군 겹침 (0~0.4): 같은 근육을 자극할수록 전이 효과가 높음
     * - 같은 카테고리 (0.2): 동일 카테고리(Barbell/Dumbbell 등)면 동작 패턴 유사
     * - 같은 장비 유형 (0.1): 동일 장비면 환경 전환 비용 없음
     * - 복합/고립 일치 (0.15): 복합 운동↔복합 운동, 고립↔고립 매칭이 전이에 유리
     * - 난이도 유사성 (0.15): 난이도 차이가 적을수록 사용자 적응도 높음
     *
     * DB 조회 없이 메모리에서만 계산 (성능 보장)
     */
    private fun calculateTransferScore(original: Exercise, substitute: Exercise): Double {
        var score = 0.0

        // 1. 근육군 겹침 (0-0.4)
        val muscleOverlap = original.muscleGroups.intersect(substitute.muscleGroups).size.toDouble() /
            original.muscleGroups.size.coerceAtLeast(1)
        score += muscleOverlap * 0.4

        // 2. 같은 카테고리 (0.2)
        if (original.category == substitute.category) score += 0.2

        // 3. 같은 장비 유형 (0.1)
        if (original.equipment == substitute.equipment) score += 0.1

        // 4. 복합/고립 일치 (0.15)
        val originalCompound = original.muscleGroups.size >= 2
        val substituteCompound = substitute.muscleGroups.size >= 2
        if (originalCompound == substituteCompound) score += 0.15

        // 5. 난이도 유사성 (0.15)
        val difficultyDiff = abs(original.difficulty - substitute.difficulty)
        if (difficultyDiff <= 15) score += 0.15
        else if (difficultyDiff <= 30) score += 0.08

        return score.coerceIn(0.0, 1.0)
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

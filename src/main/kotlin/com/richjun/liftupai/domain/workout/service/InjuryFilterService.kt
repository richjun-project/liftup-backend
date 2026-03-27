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
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 부상 회복 단계 (단계적 복귀 프로토콜)
 *
 * 부상 등록일(injuryDate)이 포함된 경우, 경과 주차에 따라 운동 제약이 점진적으로 완화됩니다.
 * 등록일이 없는 경우 기존 severity 기반 로직으로 안전하게 폴백합니다.
 */
enum class RecoveryPhase {
    ACUTE,           // 0-2주: 관련 운동 모두 제외 (기존 severity 로직 그대로)
    EARLY_REHAB,     // 2-4주: SEVERE 제한만 적용 (MILD/MODERATE 허용)
    LATE_REHAB,      // 4-8주: SEVERE 제한만 적용
    RETURN_TO_SPORT  // 8주+: 제약 해제, 로그 경고만 남김
}

/**
 * 부상 문자열에서 파싱한 구조화된 부상 정보
 * 형식: "bodyPart:severity" 또는 "bodyPart:severity:yyyy-MM-dd"
 */
data class ParsedInjury(
    val injuryType: String,
    val severity: String,
    val injuryDate: LocalDateTime?
)

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

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    // ─── 회복 단계 판정 ───────────────────────────────────────────────

    /**
     * 부상 등록일로부터 경과 주차를 계산하여 회복 단계를 반환합니다.
     */
    fun getRecoveryPhase(injuryDate: LocalDateTime): RecoveryPhase {
        val weeksSince = ChronoUnit.WEEKS.between(injuryDate, AppTime.utcNow())
        return when {
            weeksSince < 2 -> RecoveryPhase.ACUTE
            weeksSince < 4 -> RecoveryPhase.EARLY_REHAB
            weeksSince < 8 -> RecoveryPhase.LATE_REHAB
            else -> RecoveryPhase.RETURN_TO_SPORT
        }
    }

    /**
     * 부상 문자열을 파싱합니다.
     * 지원 형식:
     *   - "bodyPart:severity"              (날짜 없음 → severity 기반 폴백)
     *   - "bodyPart:severity:yyyy-MM-dd"   (날짜 있음 → 단계적 복귀)
     */
    fun parseInjury(injury: String): ParsedInjury {
        val parts = injury.split(":")
        val injuryType = parts[0]
        val severity = if (parts.size > 1) parts[1] else "ALL"
        val injuryDate = if (parts.size > 2) {
            try {
                LocalDate.parse(parts[2], DATE_FORMATTER).atStartOfDay()
            } catch (e: DateTimeParseException) {
                logger.warn("Invalid injury date format: '{}' in injury='{}', falling back to severity-only", parts[2], injury)
                null
            }
        } else null
        return ParsedInjury(injuryType, severity, injuryDate)
    }

    // ─── 제한 운동 조회 (단계적 복귀 적용) ─────────────────────────────

    /**
     * 여러 부상이 있을 때 각 부상별 제한 운동을 합산(union)
     * → 어깨 부상 + 무릎 부상이면 어깨 제한 운동 ∪ 무릎 제한 운동 모두 제외
     *
     * 복합 부상 시 더 보수적으로 동작 (안전 우선)
     *
     * 단계적 복귀 프로토콜:
     * - 부상 등록일이 포함된 경우(형식: "bodyPart:severity:yyyy-MM-dd"):
     *   - ACUTE (0-2주): 기존 severity 로직 그대로 적용 (모든 해당 운동 제외)
     *   - EARLY_REHAB (2-4주): SEVERE 제한만 적용 (MILD/MODERATE 허용)
     *   - LATE_REHAB (4-8주): SEVERE 제한만 적용
     *   - RETURN_TO_SPORT (8주+): 모든 운동 허용 (제약 해제, 로그만 남김)
     * - 부상 등록일이 없는 경우: 기존 severity 기반 로직 유지 (안전 우선)
     */
    fun getRestrictedExercises(injuries: Set<String>): List<InjuryExerciseRestriction> {
        if (injuries.isEmpty()) return emptyList()

        val allRestrictions = mutableMapOf<Long, InjuryExerciseRestriction>()

        injuries.forEach { injury ->
            val parsed = parseInjury(injury)
            val phase = parsed.injuryDate?.let { getRecoveryPhase(it) }

            if (phase != null) {
                logger.debug(
                    "Injury '{}' recovery phase: {} (registered: {})",
                    parsed.injuryType, phase, parsed.injuryDate
                )
            }

            // 단계적 복귀: RETURN_TO_SPORT 단계면 해당 부상의 운동 제약을 모두 해제
            if (phase == RecoveryPhase.RETURN_TO_SPORT) {
                logger.info(
                    "Injury '{}' reached RETURN_TO_SPORT phase (8+ weeks). All restrictions lifted.",
                    parsed.injuryType
                )
                return@forEach
            }

            val applicableSeverities = resolveApplicableSeverities(parsed.severity, phase)

            val restrictions = injuryExerciseRestrictionRepository.findByInjuryTypeAndSeverityIn(
                parsed.injuryType, applicableSeverities
            )
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

    /**
     * 회복 단계와 사용자 부상 심각도에 따라 적용할 제한 심각도 목록을 결정합니다.
     *
     * - phase가 null(날짜 없음)이면 기존 severity 기반 로직 그대로 적용
     * - ACUTE: 기존 severity 기반 로직 그대로 (보수적 안전 원칙)
     * - EARLY_REHAB / LATE_REHAB: SEVERE 제한만 적용 (MILD/MODERATE 허용)
     */
    private fun resolveApplicableSeverities(
        userSeverity: String,
        phase: RecoveryPhase?
    ): List<InjurySeverity> {
        // 날짜 없거나 ACUTE 단계 → 기존 severity 기반 로직 유지
        if (phase == null || phase == RecoveryPhase.ACUTE) {
            return when (userSeverity.uppercase()) {
                "MILD" -> listOf(InjurySeverity.SEVERE)
                "MODERATE" -> listOf(InjurySeverity.MODERATE, InjurySeverity.SEVERE)
                else -> InjurySeverity.values().toList()
            }
        }

        // EARLY_REHAB / LATE_REHAB → SEVERE 제한만 적용 (점진적 완화)
        // 날짜가 있는 경우에만 이 단계에 도달하므로, 사용자가 의도적으로 날짜를 등록한 것
        return listOf(InjurySeverity.SEVERE)
    }

    // ─── 운동 필터링 ─────────────────────────────────────────────────

    fun filterExercises(exercises: List<Exercise>, injuries: Set<String>): List<Exercise> {
        if (injuries.isEmpty()) return exercises
        val restrictions = getRestrictedExercises(injuries)
        val restrictedIds = restrictions.map { it.restrictedExercise.id }.toSet()
        return exercises.filter { it.id !in restrictedIds }
    }

    /**
     * 특정 운동이 주어진 부상 제약에 의해 제한되는지 확인
     */
    fun isExerciseRestricted(exerciseId: Long, injuries: Set<String>): Boolean {
        if (injuries.isEmpty()) return false
        val restrictions = getRestrictedExercises(injuries)
        val restrictedIds = restrictions.map { it.restrictedExercise.id }.toSet()
        return exerciseId in restrictedIds
    }

    // ─── 자동 대체 운동 적용 ─────────────────────────────────────────

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

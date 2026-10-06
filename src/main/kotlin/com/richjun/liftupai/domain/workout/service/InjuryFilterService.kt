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
        /** 큐레이션 테이블 조회 캐시 TTL. 테이블은 사실상 정적이고(현재 0행) 부상 세트도 몇 종류 안 된다. */
        private const val RESTRICTION_CACHE_TTL_MS = 60_000L
        private const val RESTRICTION_CACHE_MAX = 256
    }

    /** injuries 집합 → (계산 시각, 제한 운동 id). 플랜 가드가 후보 수백 개를 순회하며 isExerciseRestricted 를 부르는 동안 DB 를 한 번만 친다. */
    private val restrictionIdCache = java.util.concurrent.ConcurrentHashMap<Set<String>, Pair<Long, Set<Long>>>()

    /**
     * 부상 세트에 대한 큐레이션 테이블 제한 운동 id. 60초 캐시.
     * 종목 속성 규칙(isRestrictedByRule)은 DB 를 안 타므로 여기 포함하지 않는다 — [restrictionChecker] 가 둘을 합친다.
     */
    fun restrictedExerciseIds(injuries: Set<String>): Set<Long> {
        if (injuries.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        restrictionIdCache[injuries]?.let { (at, ids) -> if (now - at < RESTRICTION_CACHE_TTL_MS) return ids }
        val ids = getRestrictedExercises(injuries).map { it.restrictedExercise.id }.toSet()
        if (restrictionIdCache.size >= RESTRICTION_CACHE_MAX) restrictionIdCache.clear()
        restrictionIdCache[injuries] = now to ids
        return ids
    }

    /**
     * 요청 단위로 한 번 만들어 후보마다 호출하는 검사기. 테이블 id 는 한 번만 계산하고, 속성 규칙은 메모리에서 판정한다.
     * 예: `val restricted = injuryFilterService.restrictionChecker(injuries); candidates.filterNot(restricted)`
     */
    fun restrictionChecker(injuries: Set<String>): (Exercise) -> Boolean {
        if (injuries.isEmpty()) return { false }
        val tableIds = restrictedExerciseIds(injuries)
        return { exercise -> exercise.id in tableIds || isRestrictedByRule(exercise, injuries) }
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
        val restricted = restrictionChecker(injuries)
        return exercises.filterNot(restricted)
    }

    /**
     * 특정 운동이 주어진 부상 제약에 의해 제한되는지 확인
     */
    fun isExerciseRestricted(exerciseId: Long, injuries: Set<String>): Boolean {
        if (injuries.isEmpty()) return false
        if (exerciseId in restrictedExerciseIds(injuries)) return true
        // 큐레이션 테이블(injury_exercise_restrictions)은 현재 비어 있다.
        // 테이블만 믿으면 필터가 조용히 아무 것도 거르지 않으므로(실제로 무릎 수술자에게
        // 스쿼트·런지·레그프레스가 그대로 나갔다) 종목 속성 기반 규칙으로 폴백한다.
        val exercise = exerciseRepository.findById(exerciseId).orElse(null) ?: return false
        return isRestrictedByRule(exercise, injuries)
    }

    /**
     * 종목의 부위/동작 패턴으로 부상 제한 여부를 판정한다.
     *
     * 큐레이션 데이터가 채워지면 위의 테이블 조회가 먼저 잡아내고, 이 규칙은 미등록 종목의
     * 안전망으로 남는다. 애매하면 제한하는 쪽(보수적)으로 판단한다 — 부상 부위를 쓰는 운동을
     * 한 번 빼먹는 비용이, 잘못 넣어 다치게 하는 비용보다 훨씬 싸다.
     */
    fun isRestrictedByRule(exercise: Exercise, injuries: Set<String>): Boolean {
        val parts = injuries.map { it.substringBefore(':').trim().lowercase() }.toSet()
        val pattern = exercise.movementPattern?.uppercase().orEmpty()
        val muscles = exercise.muscleGroups.map { it.name }.toSet()

        fun hitsPattern(vararg p: String) = p.any { pattern == it }
        fun hitsMuscle(vararg m: String) = m.any { it in muscles }

        for (part in parts) {
            val restricted = when (part) {
                // 힙쓰러스트·글루트 브릿지는 무릎 굽힘 하중이 거의 없어 무릎 재활에서도 쓰는 둔근 운동이다.
                // 햄스트링 보조 동원 때문에 통째로 막히던 것을 풀되, 패턴 태그만 믿지 않고 종목명(슬러그) 허용 목록으로만 푼다.
                // 무릎 꿇기·네발 자세(킥백·힙쓰러스트 온 니즈)는 무릎 관절에 하중이 실리고, 일부는 트라이셉스 킥백이 잘못 태깅된 것이다.
                "knee" -> (hitsPattern("SQUAT", "LUNGE", "LEG_PRESS", "LEG_EXTENSION", "LEG_CURL", "PLYOMETRIC") ||
                    hitsMuscle("QUADRICEPS", "HAMSTRINGS", "CALVES")) &&
                    !isKneeSafeGluteBridge(exercise, pattern, muscles)
                "hip" -> hitsPattern("SQUAT", "LUNGE", "HIP_HINGE", "DEADLIFT", "GLUTE_FOCUSED", "LEG_PRESS", "PLYOMETRIC") ||
                    hitsMuscle("GLUTES", "HAMSTRINGS")
                // 허리는 "선 자세로 척추에 하중이 실리는" 동작 전반이 문제다.
                // 런지와 스탠딩 바벨 오버헤드 프레스도 여기 포함된다(치환 검토 중 누락 발견).
                "lower_back", "back", "lowerback" ->
                    hitsPattern(
                        "DEADLIFT", "HIP_HINGE", "BARBELL_ROW", "SQUAT", "LUNGE",
                        "CLEAN", "SNATCH", "ROTATION", "CARRY", "COMPOUND_COMPLEX",
                        "VERTICAL_PRESS_BARBELL"
                    ) || isUnsupportedBentOverRow(exercise, pattern)
                "shoulder" -> hitsPattern(
                    "VERTICAL_PRESS_BARBELL", "VERTICAL_PRESS_DUMBBELL", "VERTICAL_PRESS_MACHINE",
                    "INCLINE_PRESS_BARBELL", "INCLINE_PRESS_DUMBBELL", "HORIZONTAL_PRESS_BARBELL",
                    "HORIZONTAL_PRESS_DUMBBELL", "DIPS", "PUSHUP", "FLY", "LATERAL_RAISE", "FRONT_RAISE",
                    "UPRIGHT_ROW", "PULLUP_CHINUP", "SNATCH", "CLEAN"
                ) || hitsMuscle("SHOULDERS")
                "elbow" -> hitsPattern(
                    "BICEP_CURL_BARBELL", "BICEP_CURL_DUMBBELL", "BICEP_CURL_CABLE",
                    "TRICEP_OVERHEAD", "TRICEP_LYING", "TRICEP_PUSHDOWN", "DIPS", "PUSHUP", "PULLUP_CHINUP"
                ) || hitsMuscle("BICEPS", "TRICEPS")
                "wrist" -> hitsPattern(
                    "PUSHUP", "PLANK", "ROLLOUT", "CLEAN", "SNATCH", "CARRY",
                    "HORIZONTAL_PRESS_BARBELL", "VERTICAL_PRESS_BARBELL"
                ) || hitsMuscle("FOREARMS")
                else -> false
            }
            if (restricted) return true
        }
        return false
    }

    /**
     * 무릎 재활에서도 쓰는 둔근 운동(힙쓰러스트·글루트 브릿지)만 허용한다. 애매하면 제한하는 쪽이다.
     * 대퇴사두·종아리를 함께 쓰는 변형과, 무릎 꿇기·네발 자세·킥백류는 허용하지 않는다.
     */
    private fun isKneeSafeGluteBridge(exercise: Exercise, pattern: String, muscles: Set<String>): Boolean {
        if (pattern != "GLUTE_FOCUSED") return false
        if ("QUADRICEPS" in muscles || "CALVES" in muscles) return false
        val slug = exercise.slug.lowercase()
        if (!(slug.contains("hip-thrust") || slug.contains("glute-bridge"))) return false
        return listOf("kneel", "on-knees", "knee", "kickback", "donkey", "quadruped").none { slug.contains(it) }
    }

    /**
     * 가슴 지지 없이 상체를 숙여 버티는 로우(덤벨·케이블 벤트오버 로우)도 허리에 바벨 로우와 같은 부하가 걸린다.
     * 패턴이 DUMBBELL_ROW/CABLE_ROW 라 위 목록에 안 걸려 허리 부상자에게 추천됐다(골든셋 2026-09-25).
     * 벤치에 손·무릎을 대는 원암 로우, 체스트 서포티드 로우는 그대로 허용한다.
     */
    private fun isUnsupportedBentOverRow(exercise: Exercise, pattern: String): Boolean =
        pattern.endsWith("_ROW") && exercise.slug.contains("bent-over") &&
            !exercise.slug.contains("rear-delt") && !exercise.slug.contains("supported")

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

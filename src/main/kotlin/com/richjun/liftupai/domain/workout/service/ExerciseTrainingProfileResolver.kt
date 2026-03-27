package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import org.springframework.stereotype.Service

/**
 * 운동의 훈련 특성(Training Profile)을 판별하고,
 * PeriodizationPhase × ExerciseTrainingProfile 매트릭스로
 * 운동별 맞춤 세트/횟수/휴식시간을 결정하는 서비스.
 *
 * 전문 PT의 운동 처방 원칙:
 * - 복합 주력 운동(Big 3): 고중량 저반복, 긴 휴식
 * - 복합 보조 운동: 중강도 중반복
 * - 고립 운동: 저중량 고반복, 짧은 휴식
 * - 코어/안정화: 시간 기반
 * - 올림픽 리프팅: 폭발력 위주, 초저반복
 * - 플라이오메트릭: 폭발력, 저반복
 */
@Service
class ExerciseTrainingProfileResolver(
    private val exercisePatternClassifier: ExercisePatternClassifier
) {

    /**
     * 운동의 훈련 분류 — 세트/횟수/휴식 처방의 기준
     */
    enum class ExerciseTrainingProfile {
        /** Big 3 + 기본 바벨 복합 운동 (스쿼트, 데드리프트, 벤치프레스, 오버헤드 프레스) */
        PRIMARY_COMPOUND,

        /** 보조 복합 운동 (프론트스쿼트, 인클라인벤치, 바벨로우, 풀업, 런지, 딥스 등) */
        SECONDARY_COMPOUND,

        /** 고립 운동 (컬, 레이즈, 익스텐션, 푸시다운, 플라이, 슈러그 등) */
        ISOLATION,

        /** 코어/안정화 운동 (플랭크, 크런치, 레그레이즈, 로테이션, 롤아웃) */
        CORE_STABILITY,

        /** 올림픽 리프팅 (클린, 스내치) */
        OLYMPIC_LIFT,

        /** 플라이오메트릭 (점프, 박스점프, 버피) */
        PLYOMETRIC,

        /** 유산소 */
        CARDIO
    }

    /**
     * PeriodizationPhase × ExerciseTrainingProfile 조합별 처방 설정
     */
    data class PhaseExerciseConfig(
        val sets: Int,
        val reps: String,
        val restSeconds: Int,
        val minRestSeconds: Int,
        val maxRestSeconds: Int
    )

    // ── 올림픽/플라이오/코어 패턴 집합 ──────────────────────────────

    private val olympicPatterns = setOf(
        ExercisePatternClassifier.MovementPattern.CLEAN,
        ExercisePatternClassifier.MovementPattern.SNATCH
    )

    private val corePatterns = setOf(
        ExercisePatternClassifier.MovementPattern.PLANK,
        ExercisePatternClassifier.MovementPattern.CRUNCH,
        ExercisePatternClassifier.MovementPattern.LEG_RAISE,
        ExercisePatternClassifier.MovementPattern.ROTATION,
        ExercisePatternClassifier.MovementPattern.ROLLOUT
    )

    private val primaryCompoundPatterns = setOf(
        ExercisePatternClassifier.MovementPattern.SQUAT,
        ExercisePatternClassifier.MovementPattern.DEADLIFT,
        ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
        ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL
    )

    private val isolationPatterns = setOf(
        ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL,
        ExercisePatternClassifier.MovementPattern.BICEP_CURL_DUMBBELL,
        ExercisePatternClassifier.MovementPattern.BICEP_CURL_CABLE,
        ExercisePatternClassifier.MovementPattern.TRICEP_OVERHEAD,
        ExercisePatternClassifier.MovementPattern.TRICEP_LYING,
        ExercisePatternClassifier.MovementPattern.TRICEP_PUSHDOWN,
        ExercisePatternClassifier.MovementPattern.LATERAL_RAISE,
        ExercisePatternClassifier.MovementPattern.FRONT_RAISE,
        ExercisePatternClassifier.MovementPattern.REAR_DELT,
        ExercisePatternClassifier.MovementPattern.FLY,
        ExercisePatternClassifier.MovementPattern.CALF,
        ExercisePatternClassifier.MovementPattern.LEG_EXTENSION,
        ExercisePatternClassifier.MovementPattern.LEG_CURL,
        ExercisePatternClassifier.MovementPattern.SHRUG,
        ExercisePatternClassifier.MovementPattern.FACE_PULL,
        ExercisePatternClassifier.MovementPattern.UPRIGHT_ROW
    )

    // ── 복합운동 키워드 (RecommendationExerciseRanking과 동일) ──────

    private val compoundKeywords = listOf(
        "press", "squat", "deadlift", "row",
        "pullup", "chinup", "dip", "lunge"
    )

    // ── PeriodizationPhase × ExerciseTrainingProfile 매트릭스 (28셀) ──

    companion object {
        /**
         * 전문 PT 처방 매트릭스
         *
         * 과학적 근거:
         * - NSCA Essentials of Strength Training and Conditioning (4th ed.)
         * - Schoenfeld, B.J. (2010). The Mechanisms of Muscle Hypertrophy
         * - Issurin, V. (2010). New Horizons for the Methodology of Training
         */
        val CONFIG_MATRIX: Map<Pair<WorkoutServiceV2.PeriodizationPhase, ExerciseTrainingProfile>, PhaseExerciseConfig> = mapOf(
            // ═══ PRIMARY_COMPOUND ═══
            // Big 3: 고중량 저반복, 긴 휴식 (CNS 회복 필요)
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.PRIMARY_COMPOUND) to
                PhaseExerciseConfig(sets = 4, reps = "6-8", restSeconds = 180, minRestSeconds = 150, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.PRIMARY_COMPOUND) to
                PhaseExerciseConfig(sets = 5, reps = "4-6", restSeconds = 210, minRestSeconds = 180, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.PRIMARY_COMPOUND) to
                PhaseExerciseConfig(sets = 5, reps = "1-3", restSeconds = 240, minRestSeconds = 210, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.PRIMARY_COMPOUND) to
                PhaseExerciseConfig(sets = 3, reps = "8-10", restSeconds = 120, minRestSeconds = 90, maxRestSeconds = 180),

            // ═══ SECONDARY_COMPOUND ═══
            // 보조 복합: 중강도, 근비대 위주
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.SECONDARY_COMPOUND) to
                PhaseExerciseConfig(sets = 4, reps = "8-10", restSeconds = 120, minRestSeconds = 90, maxRestSeconds = 180),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.SECONDARY_COMPOUND) to
                PhaseExerciseConfig(sets = 4, reps = "6-8", restSeconds = 150, minRestSeconds = 120, maxRestSeconds = 180),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.SECONDARY_COMPOUND) to
                PhaseExerciseConfig(sets = 4, reps = "3-5", restSeconds = 180, minRestSeconds = 150, maxRestSeconds = 240),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.SECONDARY_COMPOUND) to
                PhaseExerciseConfig(sets = 3, reps = "10-12", restSeconds = 90, minRestSeconds = 60, maxRestSeconds = 120),

            // ═══ ISOLATION ═══
            // 고립: 저중량 고반복, 짧은 휴식 (대사 스트레스 극대화)
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.ISOLATION) to
                PhaseExerciseConfig(sets = 3, reps = "12-15", restSeconds = 60, minRestSeconds = 45, maxRestSeconds = 90),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.ISOLATION) to
                PhaseExerciseConfig(sets = 3, reps = "10-12", restSeconds = 75, minRestSeconds = 60, maxRestSeconds = 90),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.ISOLATION) to
                PhaseExerciseConfig(sets = 3, reps = "8-10", restSeconds = 90, minRestSeconds = 60, maxRestSeconds = 90),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.ISOLATION) to
                PhaseExerciseConfig(sets = 2, reps = "15-20", restSeconds = 45, minRestSeconds = 30, maxRestSeconds = 60),

            // ═══ CORE_STABILITY ═══
            // 코어: 시간 기반 (초 단위), 짧은 휴식
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.CORE_STABILITY) to
                PhaseExerciseConfig(sets = 3, reps = "30-60s", restSeconds = 45, minRestSeconds = 30, maxRestSeconds = 60),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.CORE_STABILITY) to
                PhaseExerciseConfig(sets = 3, reps = "30-45s", restSeconds = 45, minRestSeconds = 30, maxRestSeconds = 60),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.CORE_STABILITY) to
                PhaseExerciseConfig(sets = 3, reps = "20-30s", restSeconds = 30, minRestSeconds = 20, maxRestSeconds = 45),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.CORE_STABILITY) to
                PhaseExerciseConfig(sets = 2, reps = "20-30s", restSeconds = 30, minRestSeconds = 20, maxRestSeconds = 45),

            // ═══ OLYMPIC_LIFT ═══
            // 올림픽: 폭발력, 초저반복, 긴 휴식 (CNS + 기술 집중)
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.OLYMPIC_LIFT) to
                PhaseExerciseConfig(sets = 5, reps = "3", restSeconds = 180, minRestSeconds = 150, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.OLYMPIC_LIFT) to
                PhaseExerciseConfig(sets = 5, reps = "2", restSeconds = 210, minRestSeconds = 180, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.OLYMPIC_LIFT) to
                PhaseExerciseConfig(sets = 6, reps = "1", restSeconds = 240, minRestSeconds = 210, maxRestSeconds = 300),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.OLYMPIC_LIFT) to
                PhaseExerciseConfig(sets = 3, reps = "3", restSeconds = 120, minRestSeconds = 90, maxRestSeconds = 180),

            // ═══ PLYOMETRIC ═══
            // 플라이오: 폭발력, 저반복, 충분한 휴식 (관절 부하 관리)
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.PLYOMETRIC) to
                PhaseExerciseConfig(sets = 3, reps = "5-8", restSeconds = 90, minRestSeconds = 60, maxRestSeconds = 120),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.PLYOMETRIC) to
                PhaseExerciseConfig(sets = 3, reps = "3-5", restSeconds = 120, minRestSeconds = 90, maxRestSeconds = 180),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.PLYOMETRIC) to
                PhaseExerciseConfig(sets = 4, reps = "2-3", restSeconds = 150, minRestSeconds = 120, maxRestSeconds = 180),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.PLYOMETRIC) to
                PhaseExerciseConfig(sets = 2, reps = "5", restSeconds = 60, minRestSeconds = 45, maxRestSeconds = 90),

            // ═══ CARDIO ═══
            // 유산소: 시간 기반, 휴식 없음
            Pair(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.CARDIO) to
                PhaseExerciseConfig(sets = 1, reps = "time-based", restSeconds = 0, minRestSeconds = 0, maxRestSeconds = 0),
            Pair(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.CARDIO) to
                PhaseExerciseConfig(sets = 1, reps = "time-based", restSeconds = 0, minRestSeconds = 0, maxRestSeconds = 0),
            Pair(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.CARDIO) to
                PhaseExerciseConfig(sets = 1, reps = "time-based", restSeconds = 0, minRestSeconds = 0, maxRestSeconds = 0),
            Pair(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.CARDIO) to
                PhaseExerciseConfig(sets = 1, reps = "time-based", restSeconds = 0, minRestSeconds = 0, maxRestSeconds = 0)
        )

        /** 매트릭스에 매핑이 없을 때 사용하는 안전 기본값 */
        private val DEFAULT_CONFIG = PhaseExerciseConfig(
            sets = 3, reps = "10-12", restSeconds = 90, minRestSeconds = 60, maxRestSeconds = 120
        )
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Exercise → ExerciseTrainingProfile 분류
     *
     * 우선순위 체인: 특수 카테고리(올림픽/플라이오/유산소/코어) 먼저,
     * 그 다음 Primary Compound(가장 좁은 필터), Isolation, 마지막으로 Secondary Compound(catch-all)
     */
    fun resolveProfile(exercise: Exercise): ExerciseTrainingProfile {
        val pattern = exercisePatternClassifier.classifyExercise(exercise)

        // 1. 올림픽 리프팅 (최우선)
        if (pattern in olympicPatterns) return ExerciseTrainingProfile.OLYMPIC_LIFT

        // 2. 플라이오메트릭
        if (pattern == ExercisePatternClassifier.MovementPattern.PLYOMETRIC) return ExerciseTrainingProfile.PLYOMETRIC

        // 3. 유산소
        if (pattern == ExercisePatternClassifier.MovementPattern.CARDIO || exercise.category == ExerciseCategory.CARDIO) {
            return ExerciseTrainingProfile.CARDIO
        }

        // 4. 코어/안정화
        if (pattern in corePatterns || exercise.category == ExerciseCategory.CORE) {
            return ExerciseTrainingProfile.CORE_STABILITY
        }

        // 5. Primary Compound (Big 3 + 기본 바벨 복합)
        if (exercise.isBasicExercise && exercise.equipment == Equipment.BARBELL && pattern in primaryCompoundPatterns) {
            return ExerciseTrainingProfile.PRIMARY_COMPOUND
        }

        // 6. Isolation (단일 관절 / 고립 패턴)
        if (pattern in isolationPatterns) return ExerciseTrainingProfile.ISOLATION
        if (exercise.muscleGroups.size < 2 && !isCompoundByKeyword(exercise)) {
            return ExerciseTrainingProfile.ISOLATION
        }

        // 7. Secondary Compound (catch-all: 나머지 복합 운동)
        return ExerciseTrainingProfile.SECONDARY_COMPOUND
    }

    /**
     * PeriodizationPhase × ExerciseTrainingProfile → 처방 설정 조회
     */
    fun getConfig(phase: WorkoutServiceV2.PeriodizationPhase, profile: ExerciseTrainingProfile): PhaseExerciseConfig {
        return CONFIG_MATRIX[Pair(phase, profile)] ?: DEFAULT_CONFIG
    }

    /**
     * Exercise + PeriodizationPhase → 처방 설정 (편의 메서드)
     */
    fun resolveConfig(exercise: Exercise, phase: WorkoutServiceV2.PeriodizationPhase): PhaseExerciseConfig {
        return getConfig(phase, resolveProfile(exercise))
    }

    // ── Private helpers ─────────────────────────────────────────────

    private fun isCompoundByKeyword(exercise: Exercise): Boolean {
        val name = exercise.name.lowercase()
        return compoundKeywords.any { keyword -> name.contains(keyword) }
    }
}

package com.richjun.liftupai.domain.user.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel

/**
 * 온보딩 맨몸 테스트(푸시업·풀업·스쿼트 반복수)로 주요 종목의 **1RM** 을 추정한다.
 *
 * 저장되는 값은 1RM 이다(UserProfile.estimatedMaxes 의 계약). 소비자(WeightRecommendationService,
 * PTRecommendationEngine, ProgramProgressiveOverloadService)가 목표 반복수에 맞는 작업 무게로 변환한다.
 * 예전엔 여기서 0.65 를 곱한 뒤 체중×0.5 로 잘라 "작업 무게 비슷한 값"을 1RM 자리에 넣었고,
 * 상한이 푸시업 약 7회부터 걸려 운영 사용자 전원의 벤치 추정이 정확히 체중×0.5 였다.
 *
 * 상한은 경험·성별·나이별 근력 기준(일반적 스트렝스 스탠다드의 보수적 근사)으로 둔다 —
 * 맨몸 반복수는 근지구력 지표라 1RM 을 과대추정하기 쉽고, 미성년·고령은 더 보수적으로 잡는다.
 */
object StrengthAssessmentEstimator {

    /** 추정 대상 주요 리프트. 저장 키는 [keysFor] 참고. */
    enum class Lift { BENCH, OVERHEAD_PRESS, BARBELL_ROW, LAT_PULLDOWN, DUMBBELL_ROW, CABLE_ROW,
        SQUAT, DEADLIFT, LEG_PRESS, GOBLET_SQUAT, LUNGE,
        DUMBBELL_BENCH, INCLINE_DUMBBELL_PRESS, MACHINE_CHEST_PRESS, DUMBBELL_SHOULDER_PRESS, MACHINE_SHOULDER_PRESS }

    /**
     * 리프트별 저장 키. 세 계열을 모두 쓴다:
     *  1) 동작 패턴 키(ExercisePatternClassifier.MovementPattern 이름) — 패턴 기반 조회용
     *  2) 실제 카탈로그 slug — ProgramProgressiveOverloadService 가 slug 정확 일치로 읽는다
     *  3) 예전 키("bench-press" 등) — 기존 클라이언트/응답 호환
     * 예전 키만 있던 시절엔 카탈로그 slug 와 거의 일치하지 않아(bench-press ≠ barbell-bench-press)
     * 저장된 1RM 이 어디에서도 읽히지 않았다.
     */
    fun keysFor(lift: Lift): List<String> = when (lift) {
        Lift.BENCH -> listOf("HORIZONTAL_PRESS_BARBELL", "barbell-bench-press", "bench-press", "barbell-bench")
        Lift.DUMBBELL_BENCH -> listOf("HORIZONTAL_PRESS_DUMBBELL", "dumbbell-bench-press")
        Lift.INCLINE_DUMBBELL_PRESS -> listOf("INCLINE_PRESS_DUMBBELL", "dumbbell-incline-bench-press", "incline-dumbbell-press")
        Lift.MACHINE_CHEST_PRESS -> listOf("HORIZONTAL_PRESS_MACHINE", "machine-chest-press")
        Lift.OVERHEAD_PRESS -> listOf("VERTICAL_PRESS_BARBELL", "barbell-standing-military-press", "seated-shoulder-press-barbell", "overhead-press")
        Lift.DUMBBELL_SHOULDER_PRESS -> listOf("VERTICAL_PRESS_DUMBBELL", "dumbbell-seated-shoulder-press", "dumbbell-standing-overhead-press", "dumbbell-shoulder-press")
        Lift.MACHINE_SHOULDER_PRESS -> listOf("VERTICAL_PRESS_MACHINE", "machine-shoulder-press")
        Lift.BARBELL_ROW -> listOf("BARBELL_ROW", "barbell-bent-over-row-pronated-grip", "barbell-row")
        Lift.DUMBBELL_ROW -> listOf("DUMBBELL_ROW", "dumbbell-bent-over-row", "dumbbell-row")
        Lift.CABLE_ROW -> listOf("CABLE_ROW", "cable-seated-row", "seated-cable-row")
        Lift.LAT_PULLDOWN -> listOf("LAT_PULLDOWN", "cable-pulldown", "cable-bar-lateral-pulldown", "lat-pulldown")
        Lift.SQUAT -> listOf("SQUAT", "barbell-full-squat", "barbell-back-squat", "barbell-squat")
        Lift.DEADLIFT -> listOf("DEADLIFT", "barbell-deadlift", "deadlift")
        Lift.LEG_PRESS -> listOf("LEG_PRESS", "leg-press-machine-normal-stance", "leg-press")
        Lift.GOBLET_SQUAT -> listOf("dumbbell-goblet-squat", "goblet-squat")
        Lift.LUNGE -> listOf("LUNGE", "barbell-lunge", "dumbbell-lunge")
    }

    /** 온보딩 assessment 맵에서 직접 입력된 1RM 을 읽을 때 쓰는 키 */
    private val EXPLICIT_1RM_KEYS: Map<Lift, List<String>> = mapOf(
        Lift.BENCH to listOf("bench_1rm", "bench1rm", "benchPress1rm", "bench_press_1rm", "benchPress"),
        Lift.SQUAT to listOf("squat_1rm", "squat1rm", "backSquat1rm", "back_squat_1rm"),
        Lift.DEADLIFT to listOf("deadlift_1rm", "deadlift1rm"),
        Lift.OVERHEAD_PRESS to listOf("ohp_1rm", "ohp1rm", "overhead_press_1rm", "overheadPress1rm"),
        Lift.BARBELL_ROW to listOf("row_1rm", "row1rm", "barbell_row_1rm", "barbellRow1rm"),
    )

    /**
     * @param assessment 온보딩 맨몸 테스트 결과(pushup_reps/pullup_reps/squat_reps, camelCase 도 허용)
     *                   + 선택적 직접 입력 1RM(bench_1rm 등). 직접 입력이 있으면 그 리프트는 그 값을 우선한다.
     */
    fun estimateMaxes(
        assessment: Map<String, Any>?,
        bodyWeightKg: Double?,
        gender: String? = null,
        age: Int? = null,
        experienceLevel: ExperienceLevel? = null,
    ): Map<String, Double> {
        if (assessment.isNullOrEmpty()) return emptyMap()

        val bodyWeight = bodyWeightKg?.takeIf { it > 0.0 } ?: 65.0
        val level = experienceLevel ?: ExperienceLevel.BEGINNER
        val female = gender?.trim()?.lowercase() in setOf("female", "f", "woman", "여성", "여")
        val ageFactor = ageFactor(age)

        val pushups = readNonNegativeInt(assessment, "pushup_reps", "pushupReps")
        val pullups = readNonNegativeInt(assessment, "pullup_reps", "pullupReps")
        val squats = readNonNegativeInt(assessment, "squat_reps", "squatReps")

        val lifts = linkedMapOf<Lift, Double>()

        fun cap(kind: Kind) = bodyWeight * capRatio(kind, level, female) * ageFactor

        if (pushups > 0) {
            // 표준 푸시업은 체중의 약 64% 를 든다(Ebben 2011). Epley 로 1RM 추정 후 상한.
            val reps = minOf(pushups, 30)
            val bench = (bodyWeight * 0.64 * (1 + reps / 30.0)).coerceAtMost(cap(Kind.PRESS))
            lifts[Lift.BENCH] = bench
            lifts[Lift.DUMBBELL_BENCH] = bench * 0.40          // 한 손 기준
            lifts[Lift.INCLINE_DUMBBELL_PRESS] = bench * 0.34
            lifts[Lift.MACHINE_CHEST_PRESS] = bench * 1.05
            val ohp = bench * 0.62                             // OHP/벤치 비율 약 0.6-0.65
            lifts[Lift.OVERHEAD_PRESS] = ohp
            lifts[Lift.DUMBBELL_SHOULDER_PRESS] = ohp * 0.42
            lifts[Lift.MACHINE_SHOULDER_PRESS] = ohp * 1.05
        }

        if (pullups > 0) {
            val reps = minOf(pullups, 30)
            val pull1RM = (bodyWeight * (1 + reps / 30.0)).coerceAtMost(cap(Kind.PULL))
            lifts[Lift.LAT_PULLDOWN] = pull1RM * 0.90
            lifts[Lift.BARBELL_ROW] = pull1RM * 0.80
            lifts[Lift.CABLE_ROW] = pull1RM * 0.85
            lifts[Lift.DUMBBELL_ROW] = pull1RM * 0.40         // 한 손 기준
        }

        if (squats > 0) {
            // 맨몸 스쿼트는 체중의 약 85%(다리 제외 상체+몸통)를 움직인다. 반복수가 많아도 근지구력이라
            // 상한을 반드시 건다 — 30회로 1RM 1.7×체중이 나오면 초보에게 위험하다.
            val reps = minOf(squats, 30)
            val squat = (bodyWeight * 0.85 * (1 + reps / 30.0)).coerceAtMost(cap(Kind.SQUAT))
            lifts[Lift.SQUAT] = squat
            lifts[Lift.DEADLIFT] = (squat * 1.2).coerceAtMost(cap(Kind.SQUAT) * 1.2)
            lifts[Lift.LEG_PRESS] = squat * 1.5
            lifts[Lift.GOBLET_SQUAT] = squat * 0.40
            lifts[Lift.LUNGE] = squat * 0.25                   // 덤벨 한 손 기준
        }

        // 직접 입력한 1RM 이 있으면 우선한다(자기 기록이 맨몸 테스트 추정보다 정확하다).
        EXPLICIT_1RM_KEYS.forEach { (lift, keys) ->
            val explicit = readPositiveDouble(assessment, *keys.toTypedArray())
            if (explicit != null) lifts[lift] = explicit
        }

        val out = linkedMapOf<String, Double>()
        lifts.forEach { (lift, value) ->
            val rounded = Math.round(value * 10) / 10.0
            keysFor(lift).forEach { key -> out[key] = rounded }
        }
        return out
    }

    internal enum class Kind { PRESS, PULL, SQUAT }

    /**
     * 1RM 상한(체중 배수). 경험 수준별 일반 스트렝스 스탠다드의 보수적 근사.
     * 남성 벤치: 초보 0.75 / 입문 0.9 / 중급 1.25 / 상급 1.6 / 고급 1.9 × 체중. 여성은 벤치 0.6배, 스쿼트 0.75배, 풀 0.7배.
     */
    internal fun capRatio(kind: Kind, level: ExperienceLevel, female: Boolean): Double {
        val male = when (kind) {
            Kind.PRESS -> when (level) {
                ExperienceLevel.BEGINNER -> 0.75
                ExperienceLevel.NOVICE -> 0.90
                ExperienceLevel.INTERMEDIATE -> 1.25
                ExperienceLevel.ADVANCED -> 1.60
                ExperienceLevel.EXPERT -> 1.90
            }
            Kind.PULL -> when (level) {
                ExperienceLevel.BEGINNER -> 1.00
                ExperienceLevel.NOVICE -> 1.15
                ExperienceLevel.INTERMEDIATE -> 1.40
                ExperienceLevel.ADVANCED -> 1.70
                ExperienceLevel.EXPERT -> 2.00
            }
            Kind.SQUAT -> when (level) {
                ExperienceLevel.BEGINNER -> 1.00
                ExperienceLevel.NOVICE -> 1.25
                ExperienceLevel.INTERMEDIATE -> 1.75
                ExperienceLevel.ADVANCED -> 2.25
                ExperienceLevel.EXPERT -> 2.60
            }
        }
        val genderFactor = if (!female) 1.0 else when (kind) {
            Kind.PRESS -> 0.60
            Kind.PULL -> 0.70
            Kind.SQUAT -> 0.75
        }
        return male * genderFactor
    }

    /** 미성년(성장판)·고령(관절·회복)은 상한을 낮춘다. */
    internal fun ageFactor(age: Int?): Double = when {
        age == null -> 1.0
        age < 18 -> 0.75
        age < 50 -> 1.0
        age < 60 -> 0.9
        else -> 0.8
    }

    private fun readNonNegativeInt(assessment: Map<String, Any>, vararg keys: String): Int {
        val value = keys.firstNotNullOfOrNull { key -> assessment[key] } ?: return 0
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun readPositiveDouble(assessment: Map<String, Any>, vararg keys: String): Double? {
        val value = keys.firstNotNullOfOrNull { key -> assessment[key] } ?: return null
        val d = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return null
        return d.takeIf { it.isFinite() && it > 0.0 }
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.PersonalRecord
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier.MovementPattern
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

/**
 * 추천 무게의 **단일 출처**. PTRecommendationEngine, UserPlanService, ProgramWorkoutGeneratorService 가
 * 모두 여기를 거치므로 같은 사용자·같은 종목이면 어느 화면에서든 같은 기준 무게가 나온다.
 *
 * 우선순위
 *  1. 개인 기록(PR) → Brzycki 로 목표 반복수 작업 무게
 *  2. 프로필 workingWeights(사용자가 직접 입력한 작업 무게)
 *  3. 프로필 estimatedMaxes(온보딩 맨몸 테스트/자기 신고 1RM) → 목표 반복수 작업 무게
 *  4. 동작 패턴 × 체중 × 성별 × 경험 × 나이 기본값
 *
 * 예전엔 3번을 아무도 읽지 않았고(키가 카탈로그 slug 와 불일치), 4번은 경험 수준을 무시한 초보 기준이라
 * 벤치 1RM 100kg 사용자에게 20kg 이 추천됐다.
 */
@Service
class WeightRecommendationService(
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userProfileRepository: UserProfileRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(WeightRecommendationService::class.java)

    /** 무게 추정 근거 — 로그·테스트에서 어느 경로가 쓰였는지 확인용 */
    enum class Source { PERSONAL_RECORD, WORKING_WEIGHT, ESTIMATED_MAX, DEFAULT }

    data class Suggestion(val weight: Double, val source: Source)

    /**
     * 추천 무게 계산. 항상 non-null 반환.
     */
    fun calculateSuggestedWeight(userId: Long, exerciseId: Long, targetReps: Int): Double {
        val exercise = exerciseRepository.findById(exerciseId).orElse(null)
        val fromPR = calculateFromPR(userId, exerciseId, targetReps)
        // 모든 경로가 같은 마지막 관문을 거친다: 2.5kg 단위, 바벨은 빈 봉(20kg) 아래로 내려가지 않는다
        if (fromPR != null) return if (exercise != null) RecoRules.finalizeWeight(exercise, fromPR) else fromPR

        if (exercise == null) return 20.0 // 운동 정보 없으면 최소 기본값
        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        return suggestFromProfile(profile, exercise, targetReps).weight
    }

    /**
     * 첫 세션(이력·PR 없음)의 무게 — 플랜 화면·채팅/퀵 추천(PT 엔진)·하루 운동(enricher)·프로그램이 모두 이 값에서 출발한다.
     * 나이·성별·경험은 기본값 표(defaultWorkingWeight)에서 한 번만 반영되고, 이후 경로가 다시 곱하지 않는다
     * (예전엔 enricher 가 나이를 한 번 더, 준비도를 +5% 로 곱해 같은 사람이 화면마다 다른 무게를 봤다).
     */
    fun firstSessionWeight(profile: UserProfile?, exercise: Exercise, targetReps: Int): Double =
        suggestFromProfile(profile, exercise, targetReps).weight

    /**
     * PR 을 제외한 프로필 기반 추천(2→3→4). PT 엔진처럼 PR/이력을 따로 다루는 호출자가 쓴다.
     */
    fun suggestFromProfile(profile: UserProfile?, exercise: Exercise, targetReps: Int): Suggestion {
        if (isWeightless(exercise)) return Suggestion(0.0, Source.DEFAULT)

        resolveWorkingWeight(profile, exercise)?.let {
            return Suggestion(RecoRules.finalizeWeight(exercise, it), Source.WORKING_WEIGHT)
        }
        resolveEstimated1RM(profile, exercise)?.let { oneRm ->
            return Suggestion(RecoRules.finalizeWeight(exercise, workingWeightFor1RM(oneRm, targetReps)), Source.ESTIMATED_MAX)
        }
        return Suggestion(RecoRules.finalizeWeight(exercise, defaultWorkingWeight(profile, exercise).coerceAtLeast(2.5)), Source.DEFAULT)
    }

    /** 1RM(개인 기록 또는 추정) — PT 엔진이 %1RM 밴드 계산에 쓴다. */
    fun resolveEstimated1RM(profile: UserProfile?, exercise: Exercise): Double? {
        val maxes = parseMap(profile?.estimatedMaxes) ?: return null
        return lookup(maxes, exercise)
    }

    /** 사용자가 직접 입력한 작업 무게 */
    fun resolveWorkingWeight(profile: UserProfile?, exercise: Exercise): Double? {
        val weights = parseMap(profile?.workingWeights) ?: return null
        return lookup(weights, exercise)
    }

    /**
     * Brzycki 역변환: 1RM 에서 목표 반복수의 작업 무게.
     * PR 경로(calculateFromPR)와 같은 식이라 두 경로의 결과가 같은 척도에 놓인다.
     */
    fun workingWeightFor1RM(oneRm: Double, targetReps: Int): Double {
        val reps = targetReps.coerceIn(1, 36)
        return oneRm * (37.0 - reps) / 36.0
    }

    /**
     * 프로필 기본값(경로 4). 동작 패턴 기준 비율 × 제지방 체중 × 성별 × 경험 × 나이.
     * "중급 남성"이 기준(1.0)이고 초보는 그 절반이다 — 초보 기준 표에 경험 배수를 곱하지 않던 예전 방식은
     * 상급자에게도 초보 무게를 줬다.
     */
    fun defaultWorkingWeight(profile: UserProfile?, exercise: Exercise): Double {
        if (isWeightless(exercise)) return 0.0
        val bodyWeight = profile?.bodyInfo?.weight?.takeIf { it > 0 } ?: 70.0
        val bodyFat = profile?.bodyInfo?.bodyFat
        val effectiveWeight = if (bodyFat != null && bodyFat in 5.0..50.0) bodyWeight * (1.0 - bodyFat / 100.0) else bodyWeight * 0.80
        val pattern = exercisePatternClassifier.classifyExercise(exercise)
        val base = effectiveWeight * patternBaseRatio(pattern, exercise.category)
        return base *
            genderMultiplier(profile?.gender, exercise.category) *
            experienceMultiplier(profile?.experienceLevel ?: ExperienceLevel.BEGINNER) *
            ageMultiplier(profile?.age)
    }

    private fun calculateFromPR(userId: Long, exerciseId: Long, targetReps: Int): Double? {
        val pr = personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(userId, exerciseId)
            ?: return null
        return workingWeightFromPR(pr, targetReps)
    }

    fun workingWeightFromPR(pr: PersonalRecord, targetReps: Int): Double? {
        if (pr.reps >= 37 || targetReps >= 37) return null
        val estimated1RM = if (pr.reps <= 10) {
            pr.weight * (36.0 / (37.0 - pr.reps))
        } else {
            pr.weight * (1 + pr.reps / 30.0)
        }
        return roundTo2_5(workingWeightFor1RM(estimated1RM, targetReps))
    }

    // ── 프로필 맵 조회 ─────────────────────────────────────────────

    private fun parseMap(json: String?): Map<String, Double>? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue<Map<String, Any?>>(json)
                .mapNotNull { (k, v) -> (v as? Number)?.toDouble()?.takeIf { it > 0 && it.isFinite() }?.let { k to it } }
                .toMap()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn("Failed to parse strength map: ${e.message}")
            null
        }
    }

    /**
     * 조회 순서: 정확한 slug → 동작 패턴 키 → 예전 키 별칭.
     * 부분 문자열 매칭은 하지 않는다("bench" 가 "barbell-bench-squat" 에 걸리던 사고).
     */
    private fun lookup(map: Map<String, Double>, exercise: Exercise): Double? {
        map[exercise.slug]?.let { return it }
        val pattern = exercisePatternClassifier.classifyExercise(exercise)
        map[pattern.name]?.let { return it }
        legacyAliases(pattern).firstNotNullOfOrNull { map[it] }?.let { return it }
        return null
    }

    private fun legacyAliases(pattern: MovementPattern): List<String> = when (pattern) {
        MovementPattern.HORIZONTAL_PRESS_BARBELL -> listOf("barbell-bench-press", "bench-press")
        MovementPattern.HORIZONTAL_PRESS_DUMBBELL -> listOf("dumbbell-bench-press")
        MovementPattern.INCLINE_PRESS_DUMBBELL -> listOf("incline-dumbbell-press")
        MovementPattern.HORIZONTAL_PRESS_MACHINE -> listOf("machine-chest-press")
        MovementPattern.VERTICAL_PRESS_BARBELL -> listOf("overhead-press")
        MovementPattern.VERTICAL_PRESS_DUMBBELL -> listOf("dumbbell-shoulder-press")
        MovementPattern.VERTICAL_PRESS_MACHINE -> listOf("machine-shoulder-press")
        MovementPattern.BARBELL_ROW -> listOf("barbell-row")
        MovementPattern.DUMBBELL_ROW -> listOf("dumbbell-row")
        MovementPattern.CABLE_ROW -> listOf("seated-cable-row")
        MovementPattern.LAT_PULLDOWN -> listOf("lat-pulldown")
        MovementPattern.SQUAT -> listOf("barbell-back-squat", "goblet-squat")
        MovementPattern.DEADLIFT, MovementPattern.HIP_HINGE -> listOf("deadlift")
        MovementPattern.LEG_PRESS -> listOf("leg-press")
        MovementPattern.LUNGE -> listOf("dumbbell-lunge")
        else -> emptyList()
    }

    // ── 기본값 계수 (PT 엔진과 공유) ─────────────────────────────

    /** 맨몸·밴드 종목은 무게가 없다 */
    fun isWeightless(exercise: Exercise): Boolean =
        exercise.equipment == Equipment.BODYWEIGHT || exercise.equipment == Equipment.RESISTANCE_BAND

    /** 성별 배수. 여성은 상체 0.7, 하체 0.85 (체중 대비 근력 비율 통계). */
    fun genderMultiplier(gender: String?, category: ExerciseCategory): Double =
        if (gender?.trim()?.lowercase() in setOf("female", "f", "woman", "여성", "여")) {
            when (category) {
                ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS -> 0.7
                ExerciseCategory.LEGS -> 0.85
                else -> 0.75
            }
        } else 1.0

    /**
     * 경험 배수 — 패턴 표는 "중급 남성" 기준(1.0)이다.
     * 예전엔 INTERMEDIATE 가 0.75 라 표가 사실상 상급 기준이었고, 중급 78kg 남성 벤치가 35kg 로 나왔다(실사용 항의).
     * 중급 = 벤치 약 0.65×체중, 초보 = 그 60%(빈 바 부근), 상급 = 1.25배.
     */
    fun experienceMultiplier(level: ExperienceLevel): Double = when (level) {
        ExperienceLevel.BEGINNER -> 0.6
        ExperienceLevel.NOVICE -> 0.75
        ExperienceLevel.INTERMEDIATE -> 1.0
        ExperienceLevel.ADVANCED -> 1.25
        ExperienceLevel.EXPERT -> 1.4
    }

    /** 미성년·고령 보수 계수(기본값에만 적용, PR/자기 신고는 본인 실측이라 건드리지 않는다). */
    fun ageMultiplier(age: Int?): Double = when {
        age == null -> 1.0
        age < com.richjun.liftupai.domain.ai.service.ChatSafetyService.MINOR_AGE_LIMIT -> 0.75
        age < 50 -> 1.0
        age < 60 -> 0.9
        else -> 0.8
    }

    /**
     * 동작 패턴별 체중 대비 비율(중급 남성, 제지방 체중 기준 작업 무게). PT 경험 데이터 기반.
     * PTRecommendationEngine 에 있던 표를 여기로 옮겨 두 엔진이 같은 값을 쓴다.
     */
    fun patternBaseRatio(pattern: MovementPattern, category: ExerciseCategory): Double = when (pattern) {
        MovementPattern.HORIZONTAL_PRESS_BARBELL -> 0.8   // 중급 남성 벤치 작업 무게 ≈ 제지방 0.8 ≈ 체중 0.64
        MovementPattern.HORIZONTAL_PRESS_DUMBBELL -> 0.3
        MovementPattern.HORIZONTAL_PRESS_MACHINE -> 0.7
        MovementPattern.INCLINE_PRESS_BARBELL -> 0.65
        MovementPattern.INCLINE_PRESS_DUMBBELL -> 0.25
        MovementPattern.DECLINE_PRESS -> 0.8
        MovementPattern.VERTICAL_PRESS_BARBELL -> 0.5
        MovementPattern.VERTICAL_PRESS_DUMBBELL -> 0.22
        MovementPattern.VERTICAL_PRESS_MACHINE -> 0.45
        MovementPattern.DIPS, MovementPattern.PUSHUP -> 0.0
        MovementPattern.FLY -> 0.2
        MovementPattern.PULLUP_CHINUP, MovementPattern.INVERTED_ROW -> 0.0
        MovementPattern.LAT_PULLDOWN -> 0.6
        MovementPattern.BARBELL_ROW -> 0.75
        MovementPattern.DUMBBELL_ROW -> 0.35
        MovementPattern.CABLE_ROW -> 0.65
        MovementPattern.DEADLIFT -> 1.3
        MovementPattern.HIP_HINGE -> 0.8
        MovementPattern.SQUAT -> 1.1
        MovementPattern.LUNGE -> 0.4
        MovementPattern.LEG_PRESS -> 1.8
        MovementPattern.LEG_CURL -> 0.4
        MovementPattern.LEG_EXTENSION -> 0.5
        MovementPattern.GLUTE_FOCUSED -> 0.7
        MovementPattern.CALF -> 0.8
        MovementPattern.LATERAL_RAISE -> 0.1
        MovementPattern.FRONT_RAISE -> 0.12
        MovementPattern.REAR_DELT -> 0.08
        MovementPattern.FACE_PULL -> 0.2
        MovementPattern.UPRIGHT_ROW -> 0.4
        MovementPattern.SHRUG -> 0.6
        MovementPattern.BICEP_CURL_BARBELL -> 0.35
        MovementPattern.BICEP_CURL_DUMBBELL -> 0.15
        MovementPattern.BICEP_CURL_CABLE -> 0.3
        MovementPattern.TRICEP_OVERHEAD -> 0.35
        MovementPattern.TRICEP_LYING -> 0.3
        MovementPattern.TRICEP_PUSHDOWN -> 0.4
        MovementPattern.CRUNCH, MovementPattern.LEG_RAISE, MovementPattern.PLANK, MovementPattern.ROLLOUT,
        MovementPattern.CARDIO, MovementPattern.PLYOMETRIC, MovementPattern.STRETCHING -> 0.0
        MovementPattern.ROTATION -> 0.15
        else -> when (category) {
            ExerciseCategory.CHEST -> 0.5
            ExerciseCategory.BACK -> 0.6
            ExerciseCategory.LEGS -> 0.7
            ExerciseCategory.SHOULDERS -> 0.35
            ExerciseCategory.ARMS -> 0.25
            ExerciseCategory.CORE, ExerciseCategory.CARDIO -> 0.0
            else -> 0.4
        }
    }

    private fun roundTo2_5(value: Double): Double = (value / 2.5).roundToInt() * 2.5
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.util.ExerciseSuitability
import kotlin.math.roundToInt

/**
 * 운동을 고르는 모든 경로(템플릿 추천·적용, 오늘의 운동(채팅·퀵), AI 플랜, 프로그램, 제안, 대체, 맞춤 플랜)가
 * 같은 사람에게 같은 규칙을 쓰도록 한곳에 모은 순수 규칙 (DB·Spring 비의존).
 *
 *  - 부상·장비 표기 정규화 (예전에는 경로마다 정규화가 달라 부상이 조용히 빠졌다)
 *  - 수준에 맞는지(철봉 필요·너무 어려운 동작) 판정
 *  - 복합운동 판정, 초보 처방 상한, 바벨 무게 하한
 */
object RecoRules {

    /** 올림픽 바벨 빈 봉 무게(kg) */
    const val BAR_WEIGHT = 20.0

    /** "헬스장 전부 이용" 의미 토큰 — 장비 제한이 없다는 뜻으로 읽는다 */
    private val FULL_GYM_TOKENS = setOf("GYM", "FULL_GYM", "ALL", "FULL", "COMMERCIAL_GYM", "EVERYTHING")

    /** 앱·기기 가입에서 흘러온 장비 표기 별칭 → Equipment enum 이름 */
    private val EQUIPMENT_ALIASES = mapOf(
        "DUMBBELLS" to "DUMBBELL", "BARBELLS" to "BARBELL", "KETTLEBELLS" to "KETTLEBELL", "MACHINES" to "MACHINE",
        "CABLES" to "CABLE", "CABLE_MACHINE" to "CABLE", "BAND" to "RESISTANCE_BAND", "BANDS" to "RESISTANCE_BAND",
        "RESISTANCE_BANDS" to "RESISTANCE_BAND", "NONE" to "BODYWEIGHT", "NO_EQUIPMENT" to "BODYWEIGHT", "BODY_WEIGHT" to "BODYWEIGHT",
    )

    /**
     * 부상 표기 → 부위 소문자 집합. "knee:severe:2026-01-01" → "knee", "none"·빈 값 제거.
     * 모든 경로가 이 함수를 거친다 — 정규화가 다르면 같은 사람에게 서로 다른 운동 제한이 걸린다.
     */
    fun normalizeInjuries(raw: Collection<String?>?): Set<String> =
        raw.orEmpty().asSequence()
            .mapNotNull { it?.substringBefore(':')?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') }
            .filter { it.isNotEmpty() && it != "none" }
            .toSet()

    /**
     * 보유 장비 표기 → Equipment enum 이름 집합. 비어 있으면 "제한 없음".
     * 헬스장 전체 토큰이 하나라도 있으면 제한 없음(빈 집합)으로 본다 — 예전엔 "GYM" 이 그대로 남아 맨몸만 걸러지거나 전부 걸러졌다.
     */
    fun normalizeEquipment(raw: Collection<String?>?): Set<String> {
        val norm = raw.orEmpty().asSequence()
            .mapNotNull { it?.trim()?.uppercase()?.replace(Regex("[^A-Z0-9]+"), "_")?.trim('_') }
            .filter { it.isNotEmpty() }
            .map { EQUIPMENT_ALIASES[it] ?: it }
            .toSet()
        return if (norm.any { it in FULL_GYM_TOKENS }) emptySet() else norm
    }

    /** 앱 장비 표기는 `OTHER` 가 "철봉" 이다. 철봉 접근 판정에는 원본 토큰이 아니라 정규화된 집합을 쓴다. */
    fun hasBarAccess(equipment: Collection<String>): Boolean = ExerciseSuitability.hasBarAccess(equipment)

    fun parseLevel(raw: String?): ExperienceLevel? =
        raw?.trim()?.uppercase()?.let { v -> ExperienceLevel.values().firstOrNull { it.name == v } }

    /** 초보와 노비스는 같은 보호 규칙을 쓴다 (ExerciseSuitability 는 BEGINNER 만 초보로 본다). */
    fun isBeginnerBand(level: ExperienceLevel?): Boolean =
        level == null || level == ExperienceLevel.BEGINNER || level == ExperienceLevel.NOVICE

    private fun suitabilityLevel(level: ExperienceLevel?): ExperienceLevel? =
        if (level == ExperienceLevel.NOVICE) ExperienceLevel.BEGINNER else level

    /**
     * 이 사람이 실제로 할 수 있는 운동인가 — 철봉이 필요한 동작을 철봉 없는 사람에게, 너무 어려운 동작을 초보·고령·미성년에게 주지 않는다.
     * [level] 이 null 이면(정보 없음) 초보로 본다(ExerciseSuitability 와 같다).
     */
    fun suitable(ex: Exercise, equipment: Collection<String>, level: ExperienceLevel?, age: Int?): Boolean =
        ExerciseSuitability.suitable(ex, equipment, suitabilityLevel(level), age) && !barbellTooHeavyToStart(ex, level, age)

    /** 판정을 한 번 만들어 후보마다 호출하는 형태 */
    fun suitability(equipment: Collection<String>, level: ExperienceLevel?, age: Int?): (Exercise) -> Boolean {
        val eq = equipment.toSet()
        return { ex -> suitable(ex, eq, level, age) }
    }

    /**
     * 빈 봉(20kg)도 버거운 사람에게 바벨 종목을 주지 않는다. 바벨 종목 무게는 20kg 아래로 내려갈 수 없으니
     * (빈 봉이 20kg) 체구가 작은 고령·미성년 초보에게는 덤벨·머신 변형이 맞다.
     * 판정은 성별·연령·경험만 보는 보수적 규칙 — 체중 정보는 후보마다 알 수 없다.
     */
    private fun barbellTooHeavyToStart(ex: Exercise, level: ExperienceLevel?, age: Int?): Boolean {
        if (ex.equipment != Equipment.BARBELL) return false
        if (age == null || !(age < 16 || age >= 70)) return false
        return isBeginnerBand(level)
    }

    // ── 복합운동 ─────────────────────────────────────────────────────

    private val COMPOUND_PATTERNS = setOf(
        "SQUAT", "LUNGE", "LEG_PRESS", "DEADLIFT", "HIP_HINGE", "GLUTE_FOCUSED",
        "HORIZONTAL_PRESS_BARBELL", "HORIZONTAL_PRESS_DUMBBELL", "HORIZONTAL_PRESS_MACHINE",
        "INCLINE_PRESS_BARBELL", "INCLINE_PRESS_DUMBBELL", "DECLINE_PRESS",
        "VERTICAL_PRESS_BARBELL", "VERTICAL_PRESS_DUMBBELL", "VERTICAL_PRESS_MACHINE",
        "BARBELL_ROW", "DUMBBELL_ROW", "CABLE_ROW", "LAT_PULLDOWN", "PULLUP_CHINUP", "INVERTED_ROW",
        "PUSHUP", "DIPS", "CLEAN", "SNATCH", "COMPOUND_COMPLEX",
    )

    /** 복합운동 판정의 단일 규칙 — 맞춤 플랜·대체·AI 가 서로 다른 기준(근육 2개 이상, "compound" 포함 등)을 쓰던 것을 대신한다. */
    fun isCompound(ex: Exercise): Boolean {
        val pattern = ex.movementPattern?.uppercase().orEmpty()
        if (pattern in COMPOUND_PATTERNS) return true
        if (pattern.isNotEmpty() && pattern != "OTHER") return false
        // 패턴 정보가 없는 운동은 이름으로만 판단한다
        val n = ex.slug.lowercase()
        return listOf("squat", "deadlift", "press", "row", "pull-up", "pullup", "chin-up", "lunge", "dip").any { n.contains(it) }
    }

    /** 초보 보호 상한이 걸리는 주력 복합운동: 바벨 복합 종목(스쿼트·데드·벤치·오버헤드프레스·로우…) */
    fun isPrimaryCompound(ex: Exercise): Boolean = ex.equipment == Equipment.BARBELL && isCompound(ex)

    /**
     * 시간이 넘쳐 종목을 빼야 할 때의 "지킬 가치": 복합운동 > 집중 부위 > 대표(ESSENTIAL) 운동 > 인기 순.
     * 값이 낮은 종목부터 뺀다. 템플릿 적용·채팅 추천이 같은 기준으로 뺀다.
     */
    fun keepValue(ex: Exercise, compound: Boolean, focus: Collection<FocusArea>, fromTemplate: Boolean = false): Double {
        var v = ex.popularity / 10.0
        if (compound) v += 100
        if (focus.any { it.hits(ex) }) v += 60
        if (ex.recommendationTier == com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL) v += 30
        if (fromTemplate) v += 5
        return v
    }

    // ── 무게 ─────────────────────────────────────────────────────────

    /**
     * 추천 무게의 마지막 관문. 2.5kg 단위로 반올림하고, 바벨 종목은 빈 봉(20kg) 아래로 내려가지 않는다.
     * 무게가 없는 종목(맨몸·밴드)은 0 이다. 0 이하 입력은 0 으로 둔다 — 호출자가 "무게 없음"을 표현한다.
     */
    fun finalizeWeight(ex: Exercise, weight: Double): Double {
        if (ex.equipment == Equipment.BODYWEIGHT || ex.equipment == Equipment.RESISTANCE_BAND) return 0.0
        if (!weight.isFinite() || weight <= 0.0) return 0.0
        val rounded = ((weight / 2.5).roundToInt() * 2.5).coerceAtLeast(2.5)
        return if (ex.equipment == Equipment.BARBELL) rounded.coerceAtLeast(BAR_WEIGHT) else rounded
    }

    // ── 초보 처방 ────────────────────────────────────────────────────

    data class Rx(val sets: Int, val minReps: Int, val maxReps: Int, val restSeconds: Int)

    /** 초보가 처음 4주 동안 주력 복합운동에서 넘지 않는 세트 수 */
    const val BEGINNER_PRIMARY_MAX_SETS = 3

    /** 초보가 복합운동에서 내려가지 않는 최소 반복수 (낮은 반복 = 높은 중량 = 기술이 없을 때 부상 위험) */
    const val BEGINNER_COMPOUND_MIN_REPS = 8

    /** 초보 복합운동 휴식 하한: 8~12회 처방에 맞는 90초 */
    private const val BEGINNER_PRIMARY_MIN_REST = 90

    /**
     * 초보·노비스 처방 보정. 복합운동은 8회 이상, 주력 복합(바벨)은 3세트 이하로, 휴식은 90초 이상 확보한다.
     * 시간 기반(코어·유산소)과 고립운동은 건드리지 않는다. 이미 안전하면 그대로 돌려준다.
     */
    fun beginnerRx(ex: Exercise, rx: Rx, isCompoundFlag: Boolean = isCompound(ex)): Rx {
        val compound = isCompoundFlag || isCompound(ex)
        if (!compound) return rx
        var min = rx.minReps
        var max = rx.maxReps
        if (min < BEGINNER_COMPOUND_MIN_REPS) {
            val span = (max - min).coerceAtLeast(2)
            min = BEGINNER_COMPOUND_MIN_REPS
            max = (BEGINNER_COMPOUND_MIN_REPS + span).coerceAtMost(12).coerceAtLeast(BEGINNER_COMPOUND_MIN_REPS + 2)
        }
        if (max < min) max = min
        val primary = isPrimaryCompound(ex)
        val sets = if (primary) rx.sets.coerceAtMost(BEGINNER_PRIMARY_MAX_SETS) else rx.sets
        val rest = if (primary) rx.restSeconds.coerceIn(BEGINNER_PRIMARY_MIN_REST, 150) else rx.restSeconds
        return Rx(sets, min, max, rest)
    }

    /** 이 사람에게 이 운동에 허용되는 최대 세트 수 (연령 상한과 초보 주력 복합 상한 중 낮은 값) */
    fun maxSetsFor(ex: Exercise, level: ExperienceLevel?, age: Int?, isCompoundFlag: Boolean = isCompound(ex), sessionMinutes: Int? = null): Int {
        var cap = RecommendationConstants.getAgeMaxSets(age).coerceAtMost(5)
        if (level != null && isBeginnerBand(level)) {
            // 주력 복합은 늘 3세트까지. 나머지는 4세트 — 90분 이상 긴 세션은 시간을 채울 길이 세트뿐이라 5세트까지 허용한다
            val other = if ((sessionMinutes ?: 0) >= LONG_SESSION_MINUTES) 5 else 4
            cap = cap.coerceAtMost(if (isPrimaryCompound(ex)) BEGINNER_PRIMARY_MAX_SETS else other)
        }
        return cap.coerceAtLeast(2)
    }

    /** 이 분 이상이면 긴 세션으로 본다 */
    const val LONG_SESSION_MINUTES = 90

    /**
     * 세트 사이 휴식을 늘려도 되는 상한(초). 반복수에 맞는 휴식만 허용한다 — 12회 이상의 맨몸 스쿼트에 135초 휴식을 붙여 시간을 채우지 않는다.
     * 복합: 7회 이하 180초, 8~11회 바벨 주력 150초·그 외 120초, 12회 이상 90초 / 코어 60초 / 고립 90초.
     */
    fun maxRestFor(ex: Exercise, compound: Boolean, minReps: Int): Int = when {
        compound -> when {
            minReps < 8 -> 180
            minReps < 12 -> if (isPrimaryCompound(ex)) 150 else 120
            else -> 90
        }
        ex.category == com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CORE -> 60
        else -> 90
    }
}

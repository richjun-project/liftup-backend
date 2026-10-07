package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.util.ExerciseSuitability
import com.richjun.liftupai.domain.workout.util.SessionDurationFitter

/**
 * 추천 적합도 하드 체크 모음. RecoFitEvalTest(페르소나 전원)와 RecoFitNegativeControlTest(위반 사례에서 반드시 실패)가 같은 함수를 쓴다.
 * 각 함수는 위반 설명 목록을 돌려준다 — 비어 있으면 통과.
 */
object RecoFitChecks {
    /** 요청 시간 대비 하루 추정 시간의 허용 범위 (계약: 85~110%) */
    const val MIN_TIME_RATIO = 0.85
    const val MAX_TIME_RATIO = 1.10

    /** 같은 운동이 주에 이 횟수를 넘으면(후보가 충분한 집·부상 사용자) 위반 */
    const val MAX_WEEKLY_REPEATS = 2

    class Item(
        val exercise: Exercise,
        val sets: Int,
        val minReps: Int,
        val maxReps: Int,
        val restSeconds: Int,
        val isCompound: Boolean = RecoRules.isCompound(exercise),
    )

    class Day(val dayNumber: Int, val workoutType: String, val items: List<Item>)

    fun fromAdapted(days: List<TemplatePlanAdapter.AdaptedDay>): List<Day> = days.map { d ->
        Day(d.dayNumber, d.workoutType, d.exercises.map { p ->
            val s = p.source
            Item(s.exercise, s.sets, s.minReps, s.maxReps, s.restSeconds, s.isCompound)
        })
    }

    /** 하루의 추정 시간(초) — 서버가 쓰는 SessionDurationFitter 와 같은 식 */
    fun seconds(day: Day): Int = SessionDurationFitter.estimateSeconds(
        day.items.map { SessionDurationFitter.Slot(it.sets, "${it.minReps}-${it.maxReps}", it.restSeconds, it.restSeconds) }
    )

    /**
     * 모든 날이 요청 시간의 85~110% 안에 있어야 한다.
     * 모자란 날은 [canGrow] 가 true 일 때만 위반이다 — 부상·장비·수준으로 후보가 바닥났고 세트·휴식도 안전 상한이면 더 채울 수 없다("후보가 허용하는 한").
     */
    fun minuteFit(days: List<Day>, sessionMinutes: Int, canGrow: (Day) -> Boolean = { true }): List<String> = days.mapNotNull { d ->
        val ratio = seconds(d) / (sessionMinutes * 60.0)
        val short = ratio < MIN_TIME_RATIO
        if ((short && canGrow(d)) || ratio > MAX_TIME_RATIO)
            "day ${d.dayNumber} 시간 ${"%.0f".format(seconds(d) / 60.0)}분 = 요청 ${sessionMinutes}분의 ${"%.0f".format(ratio * 100)}% (허용 85~110%)"
        else null
    }

    /** 바벨 종목 무게는 빈 봉(20kg) 미만(0 초과)일 수 없다 */
    fun barbellWeights(weights: List<Pair<Exercise, Double>>): List<String> = weights
        .filter { (ex, w) -> ex.equipment == Equipment.BARBELL && w > 0.0 && w < RecoRules.BAR_WEIGHT }
        .map { (ex, w) -> "바벨 종목 ${ex.slug} 무게 ${w}kg (< 20kg 빈 봉)" }

    /** 철봉 없는 사람에게 철봉 동작, 초보·고령·미성년에게 너무 어려운 동작이 있으면 위반 */
    fun suitability(days: List<Day>, equipment: Set<String>, level: ExperienceLevel?, age: Int?): List<String> {
        val strict = if (level == ExperienceLevel.NOVICE) ExperienceLevel.BEGINNER else level
        val out = mutableListOf<String>()
        days.forEach { d -> d.items.forEach { i ->
            if (ExerciseSuitability.needsBar(i.exercise) && !ExerciseSuitability.hasBarAccess(equipment)) out += "철봉 없는데 ${i.exercise.slug}"
            if (ExerciseSuitability.tooAdvanced(i.exercise, strict, age)) out += "너무 어려운 동작 ${i.exercise.slug} (level=$level age=$age)"
        } }
        return out
    }

    private val resolver = ExerciseTrainingProfileResolver(ExercisePatternClassifier())

    private fun isPrimaryCompound(ex: Exercise) =
        RecoRules.isPrimaryCompound(ex) || resolver.resolveProfile(ex) == ExerciseTrainingProfileResolver.ExerciseTrainingProfile.PRIMARY_COMPOUND

    /** 초보·노비스: 주력 복합운동은 8회 미만도 3세트 초과도 안 된다 (처음 4주) */
    fun beginnerPrescription(days: List<Day>, level: ExperienceLevel?): List<String> {
        if (level != ExperienceLevel.BEGINNER && level != ExperienceLevel.NOVICE) return emptyList()
        val out = mutableListOf<String>()
        days.forEach { d -> d.items.filter { isPrimaryCompound(it.exercise) }.forEach { i ->
            if (i.minReps < 8) out += "초보 주력 복합 ${i.exercise.slug} 반복 ${i.minReps}-${i.maxReps} (< 8)"
            if (i.sets > 3) out += "초보 주력 복합 ${i.exercise.slug} ${i.sets}세트 (> 3)"
        } }
        return out
    }

    /**
     * 같은 운동이 주에 [cap] 회를 넘으면 위반 — 단, 바꿀 수 있는 후보가 없을 때(좁은 풀)는 면제한다.
     * [alternativesFor] 는 그 운동을 대신할 수 있는(같은 부위·보유 장비·부상·수준 규칙 통과, 집중 부위를 채우던 운동이면 같은 집중 부위, 플랜에 아직 없는) 후보.
     */
    fun repeats(days: List<Day>, alternativesFor: (Exercise, Set<Long>) -> List<Exercise>, cap: Int = MAX_WEEKLY_REPEATS): List<String> {
        val all = days.flatMap { d -> d.items.map { it.exercise } }
        val inPlan = all.map { it.id }.toSet()
        return all.groupBy { it.id }.filter { it.value.size > cap }.mapNotNull { (_, v) ->
            val alt = alternativesFor(v.first(), inPlan)
            if (alt.isEmpty()) null else "${v.first().slug} 주 ${v.size}회 (> $cap) — 대신할 수 있는 운동 ${alt.size}개 있음"
        }
    }

    /** 집중 부위의 주간 운동 수가 기준 이상이어야 한다 */
    fun focusVolume(days: List<Day>, areas: List<FocusArea>, need: Int): List<String> = areas.mapNotNull { area ->
        val hits = days.sumOf { d -> d.items.count { area.hits(it.exercise) } }
        if (hits < need) "집중 볼륨 부족 $area $hits < $need" else null
    }

    private fun isMain(ex: Exercise) =
        ex.category != com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CORE && ex.category != com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CARDIO

    fun leadsWithEssentialCompound(day: Day): Boolean {
        val first = day.items.firstOrNull() ?: return false
        return first.exercise.recommendationTier == RecommendationTier.ESSENTIAL && first.isCompound && isMain(first.exercise)
    }

    /** 하루의 첫 운동이 (등급과 무관하게) 코어·유산소가 아닌 복합운동인가 */
    fun leadsWithCompound(day: Day): Boolean {
        val first = day.items.firstOrNull() ?: return false
        return first.isCompound && isMain(first.exercise)
    }

    /**
     * 그날 안에 대표(ESSENTIAL) 복합운동이 있는데 첫 운동이 아니면 위반 — 순서 규칙의 하드 체크.
     * (대표 복합운동이 아예 없는 날[집·맨몸 템플릿]은 카탈로그의 등급 한계라 이 체크로 잡지 않는다.)
     */
    fun leadOrdering(days: List<Day>): List<String> = days.mapNotNull { d ->
        val hasEssentialCompound = d.items.any { it.exercise.recommendationTier == RecommendationTier.ESSENTIAL && it.isCompound && isMain(it.exercise) }
        if (hasEssentialCompound && !leadsWithEssentialCompound(d)) "day ${d.dayNumber} 대표 복합운동이 있는데 첫 운동이 ${d.items.first().exercise.slug}" else null
    }
}

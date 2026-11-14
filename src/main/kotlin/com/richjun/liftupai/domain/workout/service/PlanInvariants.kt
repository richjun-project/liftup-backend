package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.util.ExerciseRequirements

/**
 * 어떤 플랜이든 언제나 참이어야 하는 규칙(2026-11-14 coach-autopilot-r1).
 *
 * 304(일본·전문가·집·기구 없음)는 헬스장 분할을 받았고, 맨몸으로 바꾼 뒤에는 초보 맨몸 템플릿(3일 내내 푸시업, 등 운동 0)을 받았다.
 * 한 사람이 떠난 뒤에야 보이던 이런 문제를 추천 단계에서 기계적으로 잡는다. 페르소나 하네스(RecoFitEvalTest)가 배포 전에,
 * 운영은 플랜을 적용할 때 같은 함수를 쓴다(위반은 app_events 로 남겨 첫 경험 리포트가 읽는다).
 *
 * 규칙
 *  - REQUIRES: 모든 운동에 필요한 도구(철봉·평행봉·낮은 바 …)를 사용자가 갖췄다 — [ExerciseRequirements]
 *  - UPPER_BACK: 전신·상체·당기기 날에는 등 위쪽 운동(당기기·Y 레이즈·밴드 풀어파트·슈퍼맨 풀)이 하나 이상 있다
 *  - REAL_PULL: 당기기·등·상체 날에는, 당기기 도구(덤벨·밴드·철봉·헬스장)가 있으면 진짜 당기기(로우·풀업·풀다운)가 있다
 *  - PUSH_BACK_RATIO: 주간 밀기 운동 수가 등 위쪽 운동 수의 2배를 넘지 않는다(어깨 앞쪽만 키우는 플랜 방지)
 */
object PlanInvariants {

    data class Day(val dayNumber: Int, val workoutType: String, val exercises: List<Exercise>)

    data class Violation(val code: String, val dayNumber: Int?, val detail: String) {
        override fun toString() = if (dayNumber != null) "$code(day $dayNumber): $detail" else "$code: $detail"
    }

    const val MAX_PUSH_PER_BACK = 2.0

    private val REAL_PULL_PATTERNS = setOf(
        "PULLUP_CHINUP", "INVERTED_ROW", "BARBELL_ROW", "DUMBBELL_ROW", "CABLE_ROW", "LAT_PULLDOWN", "MACHINE_ROW",
    )
    private val UPPER_BACK_EXTRA_PATTERNS = setOf("FACE_PULL", "REAR_DELT")
    /** 등 위쪽을 쓰지만 패턴이 OTHER 인 맨몸 동작(팔을 당기며 날개뼈를 모은다) */
    private val UPPER_BACK_SLUGS = setOf("superman-pulls")
    private val PUSH_PATTERN_PREFIXES = listOf("HORIZONTAL_PRESS", "VERTICAL_PRESS", "INCLINE_PRESS", "DECLINE_PRESS")
    val BACK_DAY_TYPES = setOf("FULL_BODY", "UPPER", "PULL", "BACK")
    val PULL_DAY_TYPES = setOf("UPPER", "PULL", "BACK")
    private val PULL_CAPABLE = setOf(
        Equipment.DUMBBELL.name, Equipment.RESISTANCE_BAND.name, Equipment.CABLE.name, Equipment.MACHINE.name,
        Equipment.BARBELL.name, Equipment.KETTLEBELL.name,
    )

    fun isRealPull(ex: Exercise): Boolean = ex.movementPattern?.uppercase() in REAL_PULL_PATTERNS

    fun isUpperBack(ex: Exercise): Boolean {
        val pattern = ex.movementPattern?.uppercase().orEmpty()
        return isRealPull(ex) || pattern in UPPER_BACK_EXTRA_PATTERNS || ex.slug in UPPER_BACK_SLUGS ||
            (ex.category == ExerciseCategory.BACK && pattern == "OTHER")
    }

    fun isPush(ex: Exercise): Boolean {
        val pattern = ex.movementPattern?.uppercase().orEmpty()
        return ex.category == ExerciseCategory.CHEST || pattern == "PUSHUP" || pattern == "DIPS" ||
            PUSH_PATTERN_PREFIXES.any { pattern.startsWith(it) }
    }

    /** 진짜 당기기를 할 도구가 있나(덤벨·밴드·케틀벨·헬스장·철봉). 장비를 모르면(빈 값) 있다고 본다. */
    fun canDoRealPull(equipment: Collection<String>): Boolean {
        if (equipment.isEmpty()) return true
        val norm = equipment.map { it.trim().uppercase() }.toSet()
        return norm.any { it in PULL_CAPABLE } || ExerciseRequirements.hasGym(equipment) ||
            com.richjun.liftupai.domain.workout.util.ExerciseSuitability.hasBarAccess(equipment)
    }

    fun check(days: List<Day>, equipment: Collection<String>): List<Violation> {
        val out = mutableListOf<Violation>()
        days.forEach { d ->
            d.exercises.filterNot { ExerciseRequirements.allows(it, equipment) }.forEach { ex ->
                out += Violation("REQUIRES", d.dayNumber, "${ex.slug} needs ${ExerciseRequirements.of(ex)}")
            }
            val type = d.workoutType.uppercase()
            if (type in BACK_DAY_TYPES && d.exercises.isNotEmpty() && d.exercises.none(::isUpperBack)) {
                out += Violation("UPPER_BACK", d.dayNumber, "$type day has no upper-back exercise ${d.exercises.map { it.slug }}")
            }
            if (type in PULL_DAY_TYPES && canDoRealPull(equipment) && d.exercises.isNotEmpty() && d.exercises.none(::isRealPull)) {
                out += Violation("REAL_PULL", d.dayNumber, "$type day has no row/pull-up/pulldown although equipment allows one")
            }
        }
        val all = days.flatMap { it.exercises }
        val push = all.count(::isPush)
        val back = all.count(::isUpperBack)
        if (push >= 2 && push > MAX_PUSH_PER_BACK * back) {
            out += Violation("PUSH_BACK_RATIO", null, "weekly push $push vs upper-back $back (max ${MAX_PUSH_PER_BACK.toInt()}:1) " +
                "push=${all.filter(::isPush).map { it.slug }} back=${all.filter(::isUpperBack).map { it.slug }}")
        }
        return out
    }
}

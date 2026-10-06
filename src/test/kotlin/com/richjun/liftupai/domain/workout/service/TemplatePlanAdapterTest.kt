package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 템플릿 적용 규칙(부상·장비 필터 → 빈 날 채우기 → 집중 볼륨). UserPlanService 와 추천기가 공유하는 순수 로직.
 */
class TemplatePlanAdapterTest {

    private var nextId = 1L
    private fun ex(
        slug: String, cat: ExerciseCategory, eq: Equipment?, pattern: String? = null,
        muscles: Set<MuscleGroup> = emptySet(), popularity: Int = 50, difficulty: Int = 30,
    ) = Exercise(
        id = nextId++, slug = slug, name = slug, category = cat, equipment = eq, movementPattern = pattern,
        muscleGroups = muscles.toMutableSet(), popularity = popularity, difficulty = difficulty, isPlanEligible = true,
    )

    private val squat = ex("squat", ExerciseCategory.LEGS, Equipment.BARBELL, "SQUAT", setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), 98)
    private val bench = ex("bench", ExerciseCategory.CHEST, Equipment.BARBELL, "HORIZONTAL_PRESS_BARBELL", setOf(MuscleGroup.CHEST), 98)
    private val row = ex("row", ExerciseCategory.BACK, Equipment.CABLE, "CABLE_ROW", setOf(MuscleGroup.BACK), 90)
    private val plank = ex("plank", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "PLANK", setOf(MuscleGroup.CORE), 90)
    private val pushup = ex("pushup", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, "PUSHUP", setOf(MuscleGroup.CHEST), 80)
    private val goblet = ex("goblet", ExerciseCategory.LEGS, Equipment.DUMBBELL, "SQUAT", setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), 92)
    private val bridge = ex("bridge", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, "GLUTE_FOCUSED", setOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), 60)
    private val hipThrust = ex("hip-thrust", ExerciseCategory.LEGS, Equipment.BARBELL, "GLUTE_FOCUSED", setOf(MuscleGroup.GLUTES), 92)
    private val curl = ex("curl", ExerciseCategory.ARMS, Equipment.DUMBBELL, "BICEP_CURL_DUMBBELL", setOf(MuscleGroup.BICEPS), 92)
    private val crunch = ex("crunch", ExerciseCategory.CORE, Equipment.BODYWEIGHT, "CRUNCH", setOf(MuscleGroup.ABS, MuscleGroup.CORE), 70)
    private val deadlift = ex("deadlift", ExerciseCategory.BACK, Equipment.BARBELL, "DEADLIFT", setOf(MuscleGroup.BACK, MuscleGroup.GLUTES), 98, 65)

    private val all = listOf(squat, bench, row, plank, pushup, goblet, bridge, hipThrust, curl, crunch, deadlift)
    private val pool: (ExerciseCategory) -> List<Exercise> = { c -> all.filter { it.category == c }.sortedByDescending { it.popularity } }

    private fun src(e: Exercise, compound: Boolean = true) =
        TemplatePlanAdapter.SourceExercise(e, 3, 8, 12, 60, compound, 7.0)

    private fun adapter(restricted: (Exercise) -> Boolean = { false }) = TemplatePlanAdapter(pool, restricted)

    @Test
    fun `보유 장비로 못 하는 종목은 빠지고 맨몸·기타는 항상 남는다`() {
        val day = TemplatePlanAdapter.SourceDay(1, "FULL_BODY", listOf(src(squat), src(bench), src(plank, false), src(row)))
        val out = adapter().adapt(listOf(day), TemplatePlanAdapter.Context(allowedEquipment = setOf("DUMBBELL"))).single()
        val templateSlugs = out.exercises.filter { it.origin == TemplatePlanAdapter.Origin.TEMPLATE }.map { it.exercise.slug }
        assertEquals(listOf("plank"), templateSlugs)
        assertEquals(3, out.skippedForEquipment)
    }

    @Test
    fun `부상 제한 종목은 빠진다`() {
        val day = TemplatePlanAdapter.SourceDay(1, "LEGS", listOf(src(squat), src(plank, false)))
        val out = adapter { it.slug == "squat" }.adapt(listOf(day), TemplatePlanAdapter.Context(injuries = setOf("knee"))).single()
        assertTrue(out.exercises.none { it.exercise.slug == "squat" })
        assertEquals(1, out.skippedForInjury)
    }

    @Test
    fun `빠져서 헐거워진 날은 안전한 운동으로 5개까지 채우고 같은 날 중복이 없다`() {
        val day = TemplatePlanAdapter.SourceDay(1, "FULL_BODY", listOf(src(squat), src(bench), src(plank, false)))
        val out = adapter().adapt(listOf(day), TemplatePlanAdapter.Context(allowedEquipment = setOf("DUMBBELL"))).single()
        assertEquals(TemplatePlanAdapter.TARGET_EXERCISES_PER_DAY, out.exercises.size)
        assertEquals(out.exercises.size, out.exercises.map { it.exercise.id }.toSet().size)
        assertTrue(out.exercises.all { TemplatePlanAdapter.equipmentAllowed(it.exercise, setOf("DUMBBELL")) })
    }

    @Test
    fun `채워 넣는 운동은 다른 날에 이미 있는 운동을 피한다`() {
        val d1 = TemplatePlanAdapter.SourceDay(1, "FULL_BODY", listOf(src(plank, false), src(pushup), src(squat)))
        val d2 = TemplatePlanAdapter.SourceDay(2, "FULL_BODY", listOf(src(crunch, false), src(bench), src(deadlift)))
        val out = adapter().adapt(listOf(d1, d2), TemplatePlanAdapter.Context(allowedEquipment = setOf("DUMBBELL")))
        val d1Refill = out[0].exercises.filter { it.origin == TemplatePlanAdapter.Origin.REFILL }.map { it.exercise.id }.toSet()
        val d2Refill = out[1].exercises.filter { it.origin == TemplatePlanAdapter.Origin.REFILL }.map { it.exercise.id }.toSet()
        assertTrue(d1Refill.intersect(d2Refill).isEmpty(), "채움 운동이 날 사이에 겹쳤다")
        assertFalse(d1Refill.contains(pushup.id) || d1Refill.contains(plank.id))
    }

    @Test
    fun `난이도 상한을 넘는 운동은 채워 넣지 않는다`() {
        val day = TemplatePlanAdapter.SourceDay(1, "PULL", listOf(src(row)))
        val out = adapter().adapt(listOf(day), TemplatePlanAdapter.Context(allowedEquipment = setOf("DUMBBELL"), maxDifficulty = 40)).single()
        assertTrue(out.exercises.none { it.exercise.slug == "deadlift" })
    }

    @Test
    fun `집중 부위가 모자라면 해당 부위 운동을 보조 종목과 바꿔 넣는다`() {
        val d1 = TemplatePlanAdapter.SourceDay(1, "FULL_BODY", listOf(src(bench), src(row), src(pushup, false), src(crunch, false), src(curl, false)))
        val d2 = TemplatePlanAdapter.SourceDay(2, "FULL_BODY", listOf(src(bench), src(row), src(plank, false), src(crunch, false), src(curl, false)))
        val d3 = TemplatePlanAdapter.SourceDay(3, "FULL_BODY", listOf(src(bench), src(row), src(pushup, false), src(plank, false), src(curl, false)))
        val ctx = TemplatePlanAdapter.Context(focus = listOf(FocusArea.GLUTES))
        val out = adapter().adapt(listOf(d1, d2, d3), ctx)
        val hits = out.sumOf { d -> d.exercises.count { FocusArea.GLUTES.hits(it.exercise) } }
        assertTrue(hits >= 3, "둔근 운동이 주 $hits 개뿐이다")
        // 하루 운동 수는 늘지 않는다(보조 종목과 교체)
        assertTrue(out.all { it.exercises.size == 5 }, out.map { it.exercises.size }.toString())
        // 복합 운동(벤치·로우)은 지켜진다
        assertTrue(out.all { d -> d.exercises.any { it.exercise.slug == "bench" } && d.exercises.any { it.exercise.slug == "row" } })
    }

    @Test
    fun `집중 부위를 어색한 날에는 넣지 않는다`() {
        val push = TemplatePlanAdapter.SourceDay(1, "PUSH", listOf(src(bench), src(pushup, false), src(crunch, false)))
        val out = adapter().adapt(listOf(push), TemplatePlanAdapter.Context(focus = listOf(FocusArea.LEGS))).single()
        assertTrue(out.exercises.none { it.origin == TemplatePlanAdapter.Origin.FOCUS }, "밀기 날에 다리 운동이 들어갔다")
    }

    @Test
    fun `집중 운동도 부상 제한을 지킨다`() {
        val d = TemplatePlanAdapter.SourceDay(1, "LEGS", listOf(src(plank, false), src(crunch, false), src(curl, false)))
        val out = adapter { it.movementPattern == "SQUAT" || it.movementPattern == "GLUTE_FOCUSED" && it.slug == "hip-thrust" }
            .adapt(listOf(d), TemplatePlanAdapter.Context(injuries = setOf("knee"), focus = listOf(FocusArea.LEGS))).single()
        assertTrue(out.exercises.none { it.exercise.movementPattern == "SQUAT" || it.exercise.slug == "hip-thrust" })
    }
}

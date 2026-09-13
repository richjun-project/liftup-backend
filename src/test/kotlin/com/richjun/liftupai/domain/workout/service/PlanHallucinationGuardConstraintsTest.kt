package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * HallucinationGuardService 가 부상 제한·보유 장비·중복 조건을 지키면서 운동을 교체/제거하는지.
 * 예전에는 교체 후보가 없으면 원본을 그대로 남겨, 무릎 부상자 플랜에 스쿼트가 남거나
 * 덤벨만 있는 사용자에게 바벨 운동이 남았다.
 */
class PlanHallucinationGuardConstraintsTest {

    private val repo = mockk<ExerciseRepository>()
    private val guard = HallucinationGuardService(repo, ExercisePatternClassifier())

    private val squat = ex(1, "Barbell Back Squat", ExerciseCategory.LEGS, "SQUAT", setOf(MuscleGroup.QUADRICEPS), Equipment.BARBELL, 90)
    private val legPress = ex(2, "Leg Press", ExerciseCategory.LEGS, "LEG_PRESS", setOf(MuscleGroup.QUADRICEPS), Equipment.MACHINE, 85)
    private val hipThrust = ex(3, "Hip Thrust", ExerciseCategory.LEGS, "GLUTE_FOCUSED", setOf(MuscleGroup.GLUTES), Equipment.BARBELL, 80)
    private val bench = ex(4, "Barbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_BARBELL", setOf(MuscleGroup.CHEST), Equipment.BARBELL, 95)
    private val dbBench = ex(5, "Dumbbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_DUMBBELL", setOf(MuscleGroup.CHEST), Equipment.DUMBBELL, 88)
    private val pushup = ex(6, "Push Up", ExerciseCategory.CHEST, "PUSHUP", setOf(MuscleGroup.CHEST), Equipment.BODYWEIGHT, 70)
    private val row = ex(7, "Barbell Row", ExerciseCategory.BACK, "BARBELL_ROW", setOf(MuscleGroup.BACK), Equipment.BARBELL, 85)
    private val notEligible = ex(8, "3 Leg Dog Pose", ExerciseCategory.CORE, "STRETCHING", setOf(MuscleGroup.CORE), Equipment.BODYWEIGHT, 20, eligible = false)
    private val catalog = listOf(squat, legPress, hipThrust, bench, dbBench, pushup, row, notEligible)

    init {
        every { repo.findById(any()) } answers { Optional.ofNullable(catalog.firstOrNull { it.id == firstArg<Long>() }) }
        every { repo.findByNameIgnoreCase(any()) } answers { catalog.firstOrNull { it.name.equals(firstArg<String>(), true) } }
        every { repo.findPlanEligibleByNameContaining(any()) } answers {
            val q = firstArg<String>().lowercase(); catalog.filter { it.isPlanEligible && it.name.lowercase().contains(q) }.sortedByDescending { it.popularity }
        }
        every { repo.findPlanEligibleByCategoryOrderByPopularity(any()) } answers {
            val c = firstArg<ExerciseCategory>(); catalog.filter { it.isPlanEligible && it.category == c }.sortedByDescending { it.popularity }
        }
    }

    private fun ex(id: Long, name: String, cat: ExerciseCategory, pattern: String, muscles: Set<MuscleGroup>, eq: Equipment, pop: Int, eligible: Boolean = true) =
        Exercise(id = id, slug = name.lowercase().replace(' ', '-'), name = name, category = cat, movementPattern = pattern,
            muscleGroups = muscles.toMutableSet(), equipment = eq, isPlanEligible = eligible, popularity = pop)

    private fun entry(id: Long, name: String, order: Int) = AIPlanExerciseEntry(id, name, 3, 8, 12, 90, order, true)

    private fun planOf(type: String, vararg entries: AIPlanExerciseEntry) =
        AIPlanResponse("p", "d", "FULL_BODY", 1, 8, listOf(AIPlanDayEntry(1, "Day 1", type, 60, entries.toList())))

    /** 무릎 부상 규칙: 스쿼트/레그프레스/대퇴사두 운동 제한 */
    private val kneeRestricted: (Exercise) -> Boolean = { e ->
        e.movementPattern in setOf("SQUAT", "LUNGE", "LEG_PRESS") || MuscleGroup.QUADRICEPS in e.muscleGroups
    }

    @Test
    fun `무릎 부상자의 스쿼트는 제한 없는 하체 운동(힙 스러스트)으로 교체된다`() {
        val v = guard.validateAndRepairPlan(planOf("LEGS", entry(1, "Barbell Back Squat", 1), entry(7, "Barbell Row", 2)),
            PlanConstraints(injuries = setOf("knee"), isRestricted = kneeRestricted))
        val names = v.plan.days[0].exercises.map { it.exerciseName }
        assertEquals(listOf("Hip Thrust", "Barbell Row"), names.take(2))
        assertTrue(names.none { it == "Barbell Back Squat" || it == "Leg Press" }, names.toString())
        assertTrue(v.repairs.any { it.reason == RepairReason.CONSTRAINT_REPLACED && it.originalId == 1L })
    }

    @Test
    fun `제한 없는 대체가 전혀 없으면 운동이 제거되고 순서가 다시 매겨진다`() {
        // 힙 스러스트까지 제한(둔근)하면 하체 후보가 없다
        val all: (Exercise) -> Boolean = { e -> e.category == ExerciseCategory.LEGS }
        val v = guard.validateAndRepairPlan(planOf("LEGS", entry(1, "Barbell Back Squat", 1), entry(7, "Barbell Row", 2)),
            PlanConstraints(injuries = setOf("knee", "hip"), isRestricted = all))
        val ex = v.plan.days[0].exercises
        assertEquals("Barbell Row", ex[0].exerciseName)
        assertTrue(ex.none { it.exerciseId == 1L }, "스쿼트가 남으면 안 된다")
        assertTrue(ex.none { e -> catalog.first { it.id == e.exerciseId }.category == ExerciseCategory.LEGS }, "하체 운동은 전부 제한")
        assertEquals((1..ex.size).toList(), ex.map { it.order }, "순서 재부여")
        assertTrue(v.repairs.any { it.reason == RepairReason.REMOVED && it.originalId == 1L })
        assertTrue(ex.size >= HallucinationGuardService.MIN_EXERCISES_PER_DAY, "제거 후 안전한 운동으로 채워진다: ${ex.map { it.exerciseName }}")
    }

    @Test
    fun `덤벨만 있으면 바벨 벤치가 덤벨 벤치로 교체된다`() {
        val v = guard.validateAndRepairPlan(planOf("PUSH", entry(4, "Barbell Bench Press", 1)),
            PlanConstraints(allowedEquipment = setOf("DUMBBELL")))
        val names = v.plan.days[0].exercises.map { it.exerciseName }
        assertEquals("Dumbbell Bench Press", names[0])
        assertTrue(names.none { it.startsWith("Barbell") }, names.toString())
    }

    @Test
    fun `장비가 없으면 맨몸 운동으로 교체된다`() {
        val v = guard.validateAndRepairPlan(planOf("PUSH", entry(4, "Barbell Bench Press", 1)),
            PlanConstraints(allowedEquipment = setOf("NONE")))
        val names = v.plan.days[0].exercises.map { it.exerciseName }
        assertEquals("Push Up", names[0])
        assertTrue(names.none { it.contains("Barbell") || it.contains("Dumbbell") || it == "Leg Press" }, names.toString())
    }

    @Test
    fun `존재하지 않는 id 는 이름으로, 이름도 없으면 부위 인기 순으로 복구되며 조건을 지킨다`() {
        val v = guard.validateAndRepairPlan(planOf("PUSH", entry(999, "Dumbbell Bench Press", 1), entry(998, "Imaginary Chest Blaster", 2)),
            PlanConstraints(allowedEquipment = setOf("DUMBBELL")))
        val names = v.plan.days[0].exercises.map { it.exerciseName }
        assertEquals("Dumbbell Bench Press", names[0])
        // 두 번째: 가슴 인기 순 = Barbell Bench(제외: 바벨) → Dumbbell Bench(이미 사용, 같은 패턴 아님? id 중복) → Push Up
        assertEquals("Push Up", names[1])
        assertFalse(names.contains("Imaginary Chest Blaster"))
    }

    @Test
    fun `같은 날 같은 동작 패턴 중복은 다른 운동으로, 대체 없으면 제거된다`() {
        // 벤치 + 덤벨 벤치는 패턴이 다르므로 유지, 스쿼트 두 번은 중복 → 레그프레스로 교체
        val v = guard.validateAndRepairPlan(planOf("FULL_BODY", entry(1, "Barbell Back Squat", 1), entry(1, "Barbell Back Squat", 2)), PlanConstraints.NONE)
        assertEquals(listOf("Barbell Back Squat", "Leg Press"), v.plan.days[0].exercises.take(2).map { it.exerciseName })
        assertEquals(v.plan.days[0].exercises.size, v.plan.days[0].exercises.map { it.exerciseId }.toSet().size, "중복 없음")

        // 하체 후보를 전부 써 버리면 세 번째 스쿼트는 제거
        val v2 = guard.validateAndRepairPlan(planOf("LEGS", entry(1, "Barbell Back Squat", 1), entry(2, "Leg Press", 2), entry(3, "Hip Thrust", 3), entry(1, "Barbell Back Squat", 4)), PlanConstraints.NONE)
        val ids = v2.plan.days[0].exercises.map { it.exerciseId }
        assertEquals(ids.size, ids.toSet().size, "중복 없음")
        assertEquals(1, ids.count { it == 1L })
        assertTrue(v2.repairs.any { it.reason == RepairReason.REMOVED })
    }

    @Test
    fun `후보군 밖 운동(3 Leg Dog Pose)은 절대 남지 않는다`() {
        val v = guard.validateAndRepairPlan(planOf("CORE", entry(8, "3 Leg Dog Pose", 1)), PlanConstraints.NONE)
        assertFalse(v.plan.days[0].exercises.any { it.exerciseId == 8L })
    }

    /** 양성 대조 */
    @Test
    fun `조건을 다 만족하는 플랜은 손대지 않는다`() {
        val p = planOf("FULL_BODY", entry(4, "Barbell Bench Press", 1), entry(1, "Barbell Back Squat", 2), entry(7, "Barbell Row", 3))
        val v = guard.validateAndRepairPlan(p, PlanConstraints(allowedEquipment = setOf("BARBELL")))
        assertTrue(v.repairs.isEmpty(), v.repairs.toString())
        assertEquals(p.days[0].exercises, v.plan.days[0].exercises)
    }
}

/**
 * 운영 관찰(2026-09-13): 무릎 부상자의 day 3 이 'RDL, 힙스러스트, 고블릿 스쿼트, 카프레이즈' 로만 짜였고
 * 치환 실패로 전부 제거돼 **빈 날**로 저장됐다. 제거 후에는 안전한 운동으로 다시 채워야 한다.
 */
class PlanEmptyDayRefillTest {
    private val repo = mockk<ExerciseRepository>()
    private val guard = HallucinationGuardService(repo, ExercisePatternClassifier())

    private fun ex(id: Long, name: String, cat: ExerciseCategory, pattern: String, m: MuscleGroup, eq: Equipment = Equipment.BARBELL, pop: Int = 80) =
        Exercise(id = id, slug = name.lowercase().replace(' ', '-'), name = name, category = cat, movementPattern = pattern,
            muscleGroups = mutableSetOf(m), equipment = eq, isPlanEligible = true, popularity = pop)

    private val rdl = ex(11, "Barbell Romanian Deadlift", ExerciseCategory.LEGS, "HIP_HINGE", MuscleGroup.HAMSTRINGS)
    private val hipThrust = ex(12, "Barbell Hip Thrust", ExerciseCategory.LEGS, "GLUTE_FOCUSED", MuscleGroup.GLUTES)
    private val goblet = ex(13, "Dumbbell Goblet Squat", ExerciseCategory.LEGS, "SQUAT", MuscleGroup.QUADRICEPS, Equipment.DUMBBELL)
    private val calf = ex(14, "Dumbbell Standing Calf Raise", ExerciseCategory.LEGS, "CALF", MuscleGroup.CALVES, Equipment.DUMBBELL)
    private val plank = ex(21, "Plank", ExerciseCategory.CORE, "PLANK", MuscleGroup.CORE, Equipment.BODYWEIGHT, 90)
    private val crunch = ex(22, "Crunch", ExerciseCategory.CORE, "CRUNCH", MuscleGroup.ABS, Equipment.BODYWEIGHT, 85)
    private val legRaise = ex(23, "Hanging Leg Raise", ExerciseCategory.CORE, "LEG_RAISE", MuscleGroup.ABS, Equipment.BODYWEIGHT, 70)
    private val row = ex(31, "Barbell Row", ExerciseCategory.BACK, "BARBELL_ROW", MuscleGroup.BACK, Equipment.BARBELL, 88)
    private val bench = ex(32, "Barbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_BARBELL", MuscleGroup.CHEST, Equipment.BARBELL, 95)
    private val catalog = listOf(rdl, hipThrust, goblet, calf, plank, crunch, legRaise, row, bench)

    init {
        every { repo.findById(any()) } answers { Optional.ofNullable(catalog.firstOrNull { it.id == firstArg<Long>() }) }
        every { repo.findByNameIgnoreCase(any()) } answers { catalog.firstOrNull { it.name.equals(firstArg<String>(), true) } }
        every { repo.findPlanEligibleByNameContaining(any()) } returns emptyList()
        every { repo.findPlanEligibleByCategoryOrderByPopularity(any()) } answers {
            val c = firstArg<ExerciseCategory>(); catalog.filter { it.category == c }.sortedByDescending { it.popularity }
        }
    }

    /** 무릎 규칙(InjuryFilterService.isRestrictedByRule 과 동일 취지): 하체 패턴/근육 전부 제한 */
    private val kneeAndHip: (Exercise) -> Boolean = { e ->
        e.movementPattern in setOf("SQUAT", "LUNGE", "LEG_PRESS", "LEG_EXTENSION", "LEG_CURL", "HIP_HINGE", "GLUTE_FOCUSED", "PLYOMETRIC") ||
            e.muscleGroups.any { it in setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES, MuscleGroup.GLUTES) }
    }

    @Test
    fun `부상 부위 운동만으로 짜인 날은 비지 않고 코어·상체로 다시 채워진다`() {
        val plan = AIPlanResponse("p", "d", "FULL_BODY", 1, 8, listOf(AIPlanDayEntry(3, "Day 3", "LEGS", 45, listOf(
            AIPlanExerciseEntry(11, "Barbell Romanian Deadlift", 3, 8, 12, 90, 1, true),
            AIPlanExerciseEntry(12, "Barbell Hip Thrust", 3, 8, 12, 90, 2, true),
            AIPlanExerciseEntry(13, "Dumbbell Goblet Squat", 3, 8, 12, 90, 3, true),
            AIPlanExerciseEntry(14, "Dumbbell Standing Calf Raise", 3, 12, 15, 60, 4, false),
        ))))
        val v = guard.validateAndRepairPlan(plan, PlanConstraints(
            allowedEquipment = setOf("BARBELL", "DUMBBELL"), injuries = setOf("knee"), isRestricted = kneeAndHip))

        val day = v.plan.days[0].exercises
        assertTrue(day.size >= HallucinationGuardService.MIN_EXERCISES_PER_DAY, "채워진 후 운동 수: ${day.map { it.exerciseName }}")
        val restrictedLeft = day.filter { e -> catalog.first { it.id == e.exerciseId }.let(kneeAndHip) }
        assertTrue(restrictedLeft.isEmpty(), "부상 제한 운동이 남음: ${restrictedLeft.map { it.exerciseName }}")
        assertEquals((1..day.size).toList(), day.map { it.order }, "순서 재부여")
        // 네 개 모두 교체(코어로) 또는 제거됐다 — 원본 id 가 하나도 남지 않는다
        val touched = v.repairs.filter { it.reason == RepairReason.CONSTRAINT_REPLACED || it.reason == RepairReason.REMOVED }.map { it.originalId }.toSet()
        assertEquals(setOf(11L, 12L, 13L, 14L), touched)
        assertTrue(day.none { it.exerciseId in setOf(11L, 12L, 13L, 14L) })
        // 코어를 다 쓴 뒤 상체로 채워진다 (FILLED)
        assertTrue(v.repairs.any { it.reason == RepairReason.FILLED })
        assertEquals("Plank", day[0].exerciseName)
    }

    @Test
    fun `채울 후보도 조건을 지킨다 - 장비 없는 운동은 채워 넣지 않는다`() {
        val plan = AIPlanResponse("p", "d", "FULL_BODY", 1, 8, listOf(AIPlanDayEntry(1, "Day 1", "LEGS", 45, listOf(
            AIPlanExerciseEntry(13, "Dumbbell Goblet Squat", 3, 8, 12, 90, 1, true),
        ))))
        val v = guard.validateAndRepairPlan(plan, PlanConstraints(allowedEquipment = setOf("NONE"), injuries = setOf("knee"), isRestricted = kneeAndHip))
        val names = v.plan.days[0].exercises.map { it.exerciseName }
        assertTrue(names.none { it.contains("Barbell") }, names.toString())
        assertTrue(names.containsAll(listOf("Plank", "Crunch", "Hanging Leg Raise")), names.toString())
    }
}

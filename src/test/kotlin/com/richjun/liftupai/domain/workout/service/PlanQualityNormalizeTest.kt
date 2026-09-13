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

/**
 * PlanQualityValidator.normalize / validate — AI 가 규칙을 어긴 플랜을 서버가 결정적으로 보정·거부하는지.
 * 실제 Gemini 호출 없음. 카탈로그는 가짜 운동 몇 개로 대체.
 */
class PlanQualityNormalizeTest {

    private val repo = mockk<ExerciseRepository>()
    private val validator = PlanQualityValidator(repo, ExercisePatternClassifier())

    private val bench = ex(1, "Barbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_BARBELL", setOf(MuscleGroup.CHEST))
    private val row = ex(2, "Barbell Row", ExerciseCategory.BACK, "BARBELL_ROW", setOf(MuscleGroup.BACK))
    private val squat = ex(3, "Barbell Back Squat", ExerciseCategory.LEGS, "SQUAT", setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES))
    private val plank = ex(4, "Plank", ExerciseCategory.CORE, "PLANK", setOf(MuscleGroup.CORE), Equipment.BODYWEIGHT)
    private val curl = ex(5, "Dumbbell Curl", ExerciseCategory.ARMS, "BICEP_CURL_DUMBBELL", setOf(MuscleGroup.BICEPS), Equipment.DUMBBELL)
    private val boxJump = ex(6, "Box Jump", ExerciseCategory.LEGS, "PLYOMETRIC", setOf(MuscleGroup.QUADRICEPS), Equipment.BODYWEIGHT)
    private val ohp = ex(7, "Overhead Press", ExerciseCategory.SHOULDERS, "VERTICAL_PRESS_BARBELL", setOf(MuscleGroup.SHOULDERS))
    private val catalog = listOf(bench, row, squat, plank, curl, boxJump, ohp)

    init {
        every { repo.findAllById(any()) } answers {
            val ids = firstArg<Iterable<Long>>().toSet(); catalog.filter { it.id in ids }
        }
    }

    private fun ex(id: Long, name: String, cat: ExerciseCategory, pattern: String, muscles: Set<MuscleGroup>, eq: Equipment = Equipment.BARBELL) =
        Exercise(id = id, slug = name.lowercase().replace(' ', '-'), name = name, category = cat, movementPattern = pattern,
            muscleGroups = muscles.toMutableSet(), equipment = eq, isPlanEligible = true, popularity = 80)

    private fun entry(e: Exercise, sets: Int = 3, min: Int = 8, max: Int = 12, rest: Int = 90, order: Int = 1, compound: Boolean = true, rpe: Double = 7.0) =
        AIPlanExerciseEntry(e.id, e.name, sets, min, max, rest, order, compound, rpe)

    private fun day(n: Int, type: String, vararg entries: AIPlanExerciseEntry) =
        AIPlanDayEntry(n, "Day $n", type, 60, entries.mapIndexed { i, e -> e.copy(order = i + 1) })

    private fun plan(vararg days: AIPlanDayEntry, name: String = "근비대 플랜", desc: String = "설명", notes: String? = null) =
        AIPlanResponse(name, desc, "FULL_BODY", days.size, 8, days.toList(), notes)

    private fun targets(goal: PlanGoal = PlanGoal.HYPERTROPHY, days: Int = 3, duration: Int = 60, age: Int? = 30, bmi: Double? = 23.0, exp: String = "intermediate") =
        PlanTargets(goal, exp, days, duration, age, bmi)

    /** 양성 대조 — 규칙을 지킨 플랜은 보정 없이 그대로 통과 */
    @Test
    fun `정상 플랜은 보정 없이 통과한다`() {
        val p = plan(
            day(1, "FULL_BODY", entry(bench), entry(row), entry(squat), entry(plank, compound = false, min = 12, max = 15), entry(curl, compound = false, min = 10, max = 12)),
            day(2, "FULL_BODY", entry(squat), entry(ohp), entry(row), entry(plank, compound = false, min = 12, max = 15), entry(curl, compound = false, min = 10, max = 12)),
            day(3, "FULL_BODY", entry(bench), entry(squat), entry(row), entry(plank, compound = false, min = 12, max = 15), entry(curl, compound = false, min = 10, max = 12)),
        )
        // 이 플랜의 추정 소요시간은 약 40분이므로 목표도 40분으로 준다(±20% 안)
        val n = validator.normalize(p, targets(duration = 40))
        assertTrue(n.adjustments.isEmpty(), "unexpected adjustments: ${n.adjustments}")
        assertEquals(p.days.map { d -> d.exercises.map { it.copy(order = 0) } }, n.plan.days.map { d -> d.exercises.map { it.copy(order = 0) } })
        val r = validator.validate(n.plan, targets(duration = 40))
        assertTrue(r.isValid, r.errors.map { it.message }.toString())
    }

    @Test
    fun `근력 목표인데 12-15회로 왔으면 3-8 범위로 보정된다`() {
        val p = plan(day(1, "FULL_BODY", entry(bench, min = 12, max = 15), entry(squat, min = 10, max = 12), entry(row, min = 4, max = 6)))
        val n = validator.normalize(p, targets(goal = PlanGoal.STRENGTH))
        val e = n.plan.days[0].exercises
        assertEquals(4 to 6, e[0].minReps to e[0].maxReps, "둘 다 범위 밖 → 기본값")
        assertEquals(4 to 6, e[1].minReps to e[1].maxReps)
        assertEquals(4 to 6, e[2].minReps to e[2].maxReps, "정상 범위는 그대로")
        assertTrue(e.all { it.restSeconds >= 120 }, "근력 복합운동 휴식 ≥120s")
    }

    @Test
    fun `감량 목표는 휴식이 75초로 잘린다`() {
        val p = plan(day(1, "FULL_BODY", entry(bench, rest = 180, min = 12, max = 15), entry(squat, rest = 60, min = 12, max = 15), entry(row, rest = 45, min = 12, max = 15)))
        val n = validator.normalize(p, targets(goal = PlanGoal.WEIGHT_LOSS))
        assertEquals(listOf(75, 60, 45), n.plan.days[0].exercises.map { it.restSeconds })
    }

    @Test
    fun `미성년자는 점프 동작이 빠지고 5회 미만·RPE 8 이상이 낮아진다`() {
        val p = plan(day(1, "LEGS", entry(squat, min = 3, max = 5, rpe = 9.0), entry(boxJump), entry(row), entry(plank, compound = false, min = 12, max = 15)))
        val n = validator.normalize(p, targets(age = 15))
        val names = n.plan.days[0].exercises.map { it.exerciseName }
        assertFalse("Box Jump" in names, "플라이오메트릭 제거")
        val sq = n.plan.days[0].exercises.first { it.exerciseName == squat.name }
        assertTrue(sq.minReps >= 6, "미성년 최소 6회: ${sq.minReps}")
        assertTrue(sq.targetRPE <= 7.0, "미성년 RPE ≤7: ${sq.targetRPE}")
    }

    @Test
    fun `미성년 플랜의 감량 문구가 체력·건강 프레임으로 바뀐다`() {
        val p = plan(day(1, "FULL_BODY", entry(bench), entry(squat), entry(row)),
            name = "4주 체중 감량 다이어트 플랜", desc = "체지방 감소를 위한 루틴", notes = "Weight loss first! ダイエット頑張ろう")
        val n = validator.normalize(p, targets(age = 16, goal = PlanGoal.WEIGHT_LOSS))
        assertFalse(n.plan.planName.contains("감량") || n.plan.planName.contains("다이어트"), n.plan.planName)
        assertFalse(n.plan.planDescription.contains("체지방 감소"), n.plan.planDescription)
        assertFalse(n.plan.coachingNotes!!.contains("Weight loss", ignoreCase = true) || n.plan.coachingNotes!!.contains("ダイエット"), n.plan.coachingNotes!!)
        assertTrue(n.plan.planName.contains("체력·건강 향상"))
    }

    @Test
    fun `성인 플랜의 감량 문구는 그대로 둔다 (양성 대조)`() {
        val p = plan(day(1, "FULL_BODY", entry(bench), entry(squat), entry(row)), name = "4주 체중 감량 플랜")
        val n = validator.normalize(p, targets(age = 30, goal = PlanGoal.WEIGHT_LOSS))
        assertEquals("4주 체중 감량 플랜", n.plan.planName)
    }

    @Test
    fun `고령자는 주간 세트가 50 이하로 잘린다`() {
        val heavy = (1..3).map { d -> day(d, "FULL_BODY", entry(bench, sets = 5), entry(squat, sets = 5), entry(row, sets = 5), entry(ohp, sets = 5), entry(curl, sets = 5, compound = false)) }
        val n = validator.normalize(plan(*heavy.toTypedArray()), targets(age = 70, duration = 90))
        val weekly = n.plan.days.sumOf { d -> d.exercises.sumOf { it.sets } }
        assertTrue(weekly <= 50, "weekly sets $weekly")
        assertTrue(n.plan.days.all { it.exercises.size >= 3 })
    }

    @Test
    fun `세션 시간을 크게 넘으면 세트와 운동이 줄어 ±20 퍼센트 안에 들어온다`() {
        val long = day(1, "FULL_BODY", entry(bench, sets = 5, rest = 180), entry(squat, sets = 5, rest = 180), entry(row, sets = 5, rest = 180),
            entry(ohp, sets = 4, rest = 150), entry(curl, sets = 4, rest = 90, compound = false), entry(plank, sets = 4, rest = 90, compound = false, min = 12, max = 15))
        val before = validator.estimateDurationMinutes(long.exercises)
        assertTrue(before > 45 * 1.2, "전제: 원래 $before 분")
        val n = validator.normalize(plan(long), targets(duration = 45))
        val after = n.plan.days[0].estimatedDurationMinutes
        assertTrue(after <= 45 * 1.2, "after=$after")
        assertTrue(n.plan.days[0].exercises.size >= 3)
    }

    @Test
    fun `세션 시간이 너무 짧으면 복합운동 세트가 늘어난다`() {
        val short = day(1, "FULL_BODY", entry(bench, sets = 2, rest = 60), entry(squat, sets = 2, rest = 60), entry(row, sets = 2, rest = 60))
        val n = validator.normalize(plan(short), targets(duration = 60))
        assertTrue(n.plan.days[0].estimatedDurationMinutes > validator.estimateDurationMinutes(short.exercises))
        assertTrue(n.plan.days[0].exercises.any { it.sets > 2 })
    }

    @Test
    fun `주 3일인데 하체와 코어가 없으면 ERROR 와 수정 지시가 나온다`() {
        val p = plan(
            day(1, "PUSH", entry(bench), entry(ohp), entry(curl, compound = false)),
            day(2, "PULL", entry(row), entry(curl, compound = false), entry(ohp)),
            day(3, "PUSH", entry(bench), entry(ohp), entry(curl, compound = false)),
        )
        val r = validator.validate(p, targets(days = 3))
        assertFalse(r.isValid)
        val msgs = r.errors.map { it.message }
        assertTrue(msgs.any { it.contains("missing") && it.contains("lower body") && it.contains("core") }, msgs.toString())
        assertTrue(r.errors.any { it.fixInstruction?.contains("lower body") == true })
    }

    @Test
    fun `연속 이틀 같은 부위(PUSH-PUSH)는 ERROR`() {
        val p = plan(
            day(1, "PUSH", entry(bench), entry(ohp), entry(curl, compound = false)),
            day(2, "PUSH", entry(bench), entry(ohp), entry(curl, compound = false)),
            day(3, "LEGS", entry(squat), entry(plank, compound = false, min = 12, max = 15), entry(row)),
        )
        val r = validator.validate(p, targets(days = 3))
        assertTrue(r.errors.any { it.message.contains("consecutive days") }, r.errors.map { it.message }.toString())
    }

    @Test
    fun `연속 FULL_BODY 는 허용된다 (양성 대조)`() {
        val p = plan(
            day(1, "FULL_BODY", entry(bench), entry(squat), entry(row), entry(plank, compound = false, min = 12, max = 15)),
            day(2, "FULL_BODY", entry(ohp), entry(squat), entry(row), entry(plank, compound = false, min = 12, max = 15)),
            day(3, "FULL_BODY", entry(bench), entry(squat), entry(row), entry(plank, compound = false, min = 12, max = 15)),
        )
        val r = validator.validate(p, targets(days = 3))
        assertTrue(r.isValid, r.errors.map { it.message }.toString())
    }

    @Test
    fun `같은 날 같은 운동 두 번은 ERROR (기존 규칙 유지)`() {
        val p = plan(day(1, "FULL_BODY", entry(bench), entry(bench), entry(squat)))
        val r = validator.validate(p, targets(days = 1))
        assertTrue(r.errors.any { it.message.contains("appears 2 times in the same day") })
    }
}

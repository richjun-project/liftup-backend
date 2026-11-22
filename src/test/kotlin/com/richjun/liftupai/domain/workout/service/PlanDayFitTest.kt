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
 * 플랜 검사기 11-22 규칙 — 실측(complex_r4, 실제 모델 160회)에서 나온 것:
 *  ① 날 유형과 어긋난 종목(밀기 날 덤벨 슈러그, 하체 날 덤벨 벤트오버 로우) → 맞는 날로 옮기거나 뺀다
 *  ② 30분 세션이 34~35분(예전 +20% 허용) → 넘침은 +10% 까지
 *  ③ 플랭크 3×1-1 같은 처방은 목표 범위로 되돌린다(이미 되던 것 — 회귀 방지)
 */
class PlanDayFitTest {
    private val repo = mockk<ExerciseRepository>()
    private val validator = PlanQualityValidator(repo, ExercisePatternClassifier())

    private fun ex(id: Long, name: String, cat: ExerciseCategory, pattern: String, vararg m: MuscleGroup, eq: Equipment = Equipment.DUMBBELL) =
        Exercise(id = id, slug = "t-$id", name = name, category = cat, movementPattern = pattern, muscleGroups = m.toMutableSet(),
            equipment = eq, isPlanEligible = true, popularity = 80)

    // 실측에 나온 실제 운영 카탈로그 값(카테고리·동작 패턴)
    private val shrug = ex(825, "Dumbbell shrugs", ExerciseCategory.SHOULDERS, "SHRUG", MuscleGroup.TRAPS)
    private val bentRow = ex(646, "Dumbbell Bent-Over Row", ExerciseCategory.BACK, "DUMBBELL_ROW", MuscleGroup.BACK, MuscleGroup.LATS)
    private val facePull = ex(1143, "Resistance band face pull", ExerciseCategory.SHOULDERS, "FACE_PULL", MuscleGroup.SHOULDERS, MuscleGroup.TRAPS)
    private val lateral = ex(1043, "Lateral raises dumbbell", ExerciseCategory.SHOULDERS, "LATERAL_RAISE", MuscleGroup.SHOULDERS)
    private val curl = ex(663, "Dumbbell curls", ExerciseCategory.ARMS, "BICEP_CURL_DUMBBELL", MuscleGroup.BICEPS)
    private val bridge = ex(1322, "Glute bridge", ExerciseCategory.LEGS, "GLUTE_FOCUSED", MuscleGroup.GLUTES, eq = Equipment.BODYWEIGHT)
    private val rdl = ex(30, "Dumbbell Romanian Deadlift", ExerciseCategory.BACK, "HIP_HINGE", MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES)
    private val bench = ex(31, "Dumbbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_DUMBBELL", MuscleGroup.CHEST)
    private val ohp = ex(32, "Dumbbell Seated Shoulder Press", ExerciseCategory.SHOULDERS, "VERTICAL_PRESS_DUMBBELL", MuscleGroup.SHOULDERS)
    private val pushdown = ex(33, "Cable triceps pushdown", ExerciseCategory.ARMS, "TRICEP_PUSHDOWN", MuscleGroup.TRICEPS, eq = Equipment.CABLE)
    private val squat = ex(34, "Goblet squat", ExerciseCategory.LEGS, "SQUAT", MuscleGroup.QUADRICEPS)
    private val lunge = ex(35, "Dumbbell lunge", ExerciseCategory.LEGS, "LUNGE", MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES)
    private val plank = ex(36, "Plank", ExerciseCategory.CORE, "PLANK", MuscleGroup.CORE, eq = Equipment.BODYWEIGHT)
    private val crunch = ex(37, "Crunch", ExerciseCategory.CORE, "CRUNCH", MuscleGroup.ABS, eq = Equipment.BODYWEIGHT)
    private val pulldown = ex(38, "Lat pulldown", ExerciseCategory.BACK, "LAT_PULLDOWN", MuscleGroup.LATS, eq = Equipment.CABLE)
    private val pushup = ex(39, "Push up", ExerciseCategory.CHEST, "PUSHUP", MuscleGroup.CHEST, eq = Equipment.BODYWEIGHT)
    private val catalog = listOf(shrug, bentRow, facePull, lateral, curl, bridge, rdl, bench, ohp, pushdown, squat, lunge, plank, crunch, pulldown, pushup)

    init {
        every { repo.findAllById(any()) } answers { val ids = firstArg<Iterable<Long>>().toSet(); catalog.filter { it.id in ids } }
    }

    private fun e(x: Exercise, sets: Int = 3, min: Int = 8, max: Int = 12, rest: Int = 90, compound: Boolean = true) =
        AIPlanExerciseEntry(x.id, x.name, sets, min, max, rest, 0, compound, 7.0)

    private fun day(n: Int, type: String, vararg xs: AIPlanExerciseEntry) = AIPlanDayEntry(n, "Day $n", type, 45, xs.toList())
    private fun plan(vararg d: AIPlanDayEntry) = AIPlanResponse("p", "d", "PPL", d.size, 8, d.toList())
    private fun targets(minutes: Int = 60, goal: PlanGoal = PlanGoal.HYPERTROPHY) = PlanTargets(goal, "intermediate", 3, minutes, 30, 23.0)
    private fun names(d: AIPlanDayEntry) = d.exercises.map { it.exerciseName }

    @Test
    fun `밀기 날의 슈러그·페이스풀·컬은 당기기 날로 옮기고, 당기기 날의 벤치는 밀기 날로`() {
        val p = plan(
            day(1, "PUSH", e(bench), e(ohp), e(shrug), e(facePull), e(pushdown), e(lateral), e(crunch)),
            day(2, "PULL", e(bentRow), e(pulldown), e(curl), e(pushup)),
            day(3, "LEGS", e(squat), e(lunge), e(bridge), e(plank)),
        )
        val n = validator.normalize(p, targets())
        val (push, pull) = n.plan.days
        assertEquals(listOf("Dumbbell Bench Press", "Dumbbell Seated Shoulder Press", "Cable triceps pushdown", "Lateral raises dumbbell", "Crunch", "Push up"), names(push))
        assertTrue(names(pull).containsAll(listOf("Dumbbell shrugs", "Resistance band face pull")), names(pull).toString())
        assertFalse("Push up" in names(pull))
        assertTrue(n.adjustments.any { it.contains("moved 'Dumbbell shrugs'") }, n.adjustments.toString())
        assertTrue(validator.validate(n.plan, targets()).issues.none { it.message.contains("does not fit") })
    }

    @Test
    fun `하체 날의 벤트오버 로우는 상체 날로, 루마니안 데드리프트(힙힌지)·코어는 하체 날에 남는다`() {
        val p = plan(
            day(1, "LOWER", e(bridge), e(rdl), e(bentRow), e(squat), e(lunge), e(plank)),
            day(2, "UPPER", e(bench), e(pulldown), e(ohp), e(curl)),
        )
        val n = validator.normalize(p, targets())
        assertEquals(listOf("Glute bridge", "Dumbbell Romanian Deadlift", "Goblet squat", "Dumbbell lunge", "Plank"), names(n.plan.days[0]))
        assertTrue("Dumbbell Bent-Over Row" in names(n.plan.days[1]))
    }

    @Test
    fun `옮길 날이 없으면 빼되, 빼면 5종목 밑이 되는 날은 두고 경고한다(얇은 날 방지)`() {
        val many = validator.normalize(plan(day(1, "PUSH", e(bench), e(ohp), e(pushdown), e(lateral), e(pushup), e(shrug))), targets())
        assertFalse("Dumbbell shrugs" in names(many.plan.days[0]))
        assertTrue(many.adjustments.any { it.contains("removed 'Dumbbell shrugs'") })

        // 바꿀 것도 없는 5종목 날(당기기 날이 이미 같은 종목만 가짐) → 그대로 두고 경고
        val few = validator.normalize(plan(day(1, "PUSH", e(bench), e(ohp), e(pushdown), e(lateral), e(shrug)),
            day(2, "PULL", e(bentRow), e(pulldown), e(curl))), targets())
        val pullNames = names(few.plan.days[1])
        assertTrue("Dumbbell shrugs" in names(few.plan.days[0]) && pullNames.size == 3, "5종목 날은 얇게 만들지 않는다: $pullNames")
        assertTrue(validator.validate(few.plan, targets()).issues.any { it.message.contains("'Dumbbell shrugs' does not fit the day type") })
    }

    @Test
    fun `5종목 날은 맞바꾼다 — 밀기 날 슈러그 ↔ 당기기 날의 삼두(서로 어긋난 것 먼저), 다음은 코어`() {
        val p = plan(
            day(1, "PUSH", e(bench), e(ohp), e(shrug), e(lateral), e(crunch)),
            day(2, "PULL", e(bentRow), e(curl), e(pushdown), e(plank), e(pulldown)),
        )
        val n = validator.normalize(p, targets())
        assertEquals(listOf("Dumbbell Bench Press", "Dumbbell Seated Shoulder Press", "Cable triceps pushdown", "Lateral raises dumbbell", "Crunch"), names(n.plan.days[0]))
        assertEquals(listOf("Dumbbell Bent-Over Row", "Dumbbell curls", "Dumbbell shrugs", "Plank", "Lat pulldown"), names(n.plan.days[1]))
        // 실측 모양: 밀기 날 삼두가 이미 있어 당기기 날 삼두는 못 옮김 → 슈러그는 코어와 맞바꿈
        val q = validator.normalize(plan(
            day(1, "PUSH", e(pushdown), e(shrug), e(crunch), e(lateral), e(pushup)),
            day(2, "PULL", e(bentRow), e(curl), e(pushdown), e(plank), e(pulldown)),
        ), targets())
        assertTrue("Dumbbell shrugs" in names(q.plan.days[1]) && "Plank" in names(q.plan.days[0]), q.adjustments.toString())
        assertEquals(5, q.plan.days[0].exercises.size); assertEquals(5, q.plan.days[1].exercises.size)
    }

    @Test
    fun `양성 대조 — 전신·상체·하체·밀기 2분할의 스쿼트는 그대로`() {
        val p = plan(
            day(1, "FULL_BODY", e(squat), e(bench), e(bentRow), e(shrug), e(plank)),
            day(2, "PUSH", e(squat), e(bench), e(ohp), e(pushdown)),
            day(3, "PULL", e(rdl), e(bentRow), e(pulldown), e(curl), e(lateral)),
        )
        val n = validator.normalize(p, targets())
        assertTrue(n.adjustments.none { it.contains("moved") || it.contains("removed") }, n.adjustments.toString())
    }

    @Test
    fun `30분을 고르면 33분(10%)을 넘지 않는다 — 예전엔 34~35분이 그대로 나갔다`() {
        // 4종목 × 4세트 × (10회×3초 + 90초) + 준비 = 약 39분
        val p = plan(day(1, "FULL_BODY", e(squat, sets = 4, min = 8, max = 12), e(bench, sets = 4), e(bentRow, sets = 4), e(ohp, sets = 4)))
        val before = validator.estimateDurationMinutes(p.days[0].exercises)
        assertTrue(before in 34..45, "대조: 고치기 전 ${before}분")
        val n = validator.normalize(p, targets(minutes = 30))
        val after = n.plan.days[0].estimatedDurationMinutes
        assertTrue(after <= 33, "30분 목표에 ${after}분")
        assertTrue(validator.validate(n.plan, targets(minutes = 30)).issues.none { it.message.contains("estimated") })
    }

    @Test
    fun `플랭크 3×1-1 은 목표 반복 범위로 되돌린다(감량 목표 12-15)`() {
        val p = plan(day(1, "FULL_BODY", e(squat), e(bench), e(bentRow), e(plank, min = 1, max = 1, compound = false)))
        val n = validator.normalize(p, targets(goal = PlanGoal.WEIGHT_LOSS))
        val pl = n.plan.days[0].exercises.first { it.exerciseName == "Plank" }
        assertEquals(12 to 15, pl.minReps to pl.maxReps)
    }
}

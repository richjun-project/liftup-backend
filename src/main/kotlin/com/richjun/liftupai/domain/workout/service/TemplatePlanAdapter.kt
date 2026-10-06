package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

/**
 * 템플릿을 한 사람에게 맞게 고쳐 쓰는 순수 로직 (DB·Spring 비의존).
 *
 *  1) 부상 부위를 건드리거나 보유 장비로 못 하는 종목을 뺀다
 *  2) 빠져서 하루가 헐거워지면 안전한 운동으로 다시 채운다 (한 번 쓴 운동은 플랜 전체에서 다시 안 쓴다)
 *  3) 집중 부위(하체·둔근·팔 …)가 주간 볼륨에서 모자라면 해당 부위 운동을 끼워 넣는다
 *
 * UserPlanService.applyTemplate(실제 플랜 생성)과 TemplateRecommendationEngine(추천 점수 시뮬레이션)이
 * 같은 규칙을 쓰도록 한곳에 모았다. 추천기가 "이 템플릿은 이 사람에게 몇 %가 살아남나"를 재는 기준이 곧 실제 적용 결과다.
 */
class TemplatePlanAdapter(
    /** 카테고리별 플랜 후보 운동(인기도 내림차순) */
    private val poolByCategory: (ExerciseCategory) -> List<Exercise>,
    /** 부상 제한 여부 */
    private val isRestricted: (Exercise) -> Boolean,
) {

    data class SourceExercise(
        val exercise: Exercise,
        val sets: Int,
        val minReps: Int,
        val maxReps: Int,
        val restSeconds: Int,
        val isCompound: Boolean,
        val targetRPE: Double,
        val notes: String? = null,
    )

    data class SourceDay(val dayNumber: Int, val workoutType: String, val exercises: List<SourceExercise>)

    enum class Origin { TEMPLATE, REFILL, FOCUS }

    data class PlannedExercise(val source: SourceExercise, val origin: Origin) {
        val exercise: Exercise get() = source.exercise
    }

    data class AdaptedDay(
        val dayNumber: Int,
        val workoutType: String,
        val exercises: List<PlannedExercise>,
        val skippedForInjury: Int,
        val skippedForEquipment: Int,
    )

    data class Context(
        /** Equipment enum 이름(BARBELL 등). 비어 있으면 제한 없음 */
        val allowedEquipment: Set<String> = emptySet(),
        val injuries: Set<String> = emptySet(),
        val focus: List<FocusArea> = emptyList(),
        /** 채워 넣는 운동의 난이도 상한(1-100). null 이면 제한 없음 */
        val maxDifficulty: Int? = null,
    )

    fun adapt(days: List<SourceDay>, ctx: Context): List<AdaptedDay> {
        // 1단계: 부상·장비로 못 하는 종목을 뺀다
        class Filtered(val day: SourceDay, val kept: MutableList<PlannedExercise>, var injury: Int, var equipment: Int)
        val filtered = days.map { day ->
            val f = Filtered(day, mutableListOf(), 0, 0)
            for (src in day.exercises) {
                // 부상 부위를 건드리는 종목은 플랜에 넣지 않는다.
                //
                // 2026-08-26 실사용 사고: 무릎 수술을 신고한 사용자의 템플릿 플랜에 하체 운동 6개가 그대로 들어갔다.
                if (ctx.injuries.isNotEmpty() && isRestricted(src.exercise)) {
                    f.injury++
                    continue
                }
                // 보유 장비로 못 하는 종목도 넣지 않는다(덤벨만 있는 사용자에게 바벨 벤치 — 2026-09-16 운영 검증).
                if (!equipmentAllowed(src.exercise, ctx.allowedEquipment)) {
                    f.equipment++
                    continue
                }
                f.kept += PlannedExercise(src, Origin.TEMPLATE)
            }
            f
        }

        // 2단계: 빠져서 헐거워진 날을 안전한 운동으로 다시 채운다.
        // 2026-09-15 실사용: 무릎·고관절·허리 부상 사용자의 diet_circuit 플랜이 하루 2·3·4개로 줄었다.
        // 재구성으로 넣은 운동은 플랜 전체에서 한 번만(운영 계정 123: 3일 모두 같은 3개가 들어갔다).
        // 다른 날에 이미 남아 있는 템플릿 운동도 피한다(허리 부상 사용자 플랜에 같은 데드리프트 변형이 두 날 들어갔다).
        val planKept = filtered.flatMap { f -> f.kept.map { it.exercise.id } }.toSet()
        val refillUsedAcrossPlan = mutableSetOf<Long>()
        val adapted = filtered.map { f ->
            val removed = f.day.exercises.size - f.kept.size
            if (removed > 0 && f.kept.size < TARGET_EXERCISES_PER_DAY) {
                val needed = TARGET_EXERCISES_PER_DAY - f.kept.size
                val dayKept = f.kept.map { it.exercise.id }.toSet()
                fun pick(exclude: Set<Long>, n: Int) = pickRefill(
                    workoutType = f.day.workoutType, injuries = ctx.injuries, exclude = exclude, needed = n,
                    allowedEquipment = ctx.allowedEquipment, maxDifficulty = ctx.maxDifficulty,
                )
                var refill = pick(planKept + refillUsedAcrossPlan, needed)
                if (refill.size < needed) {
                    // 후보가 모자라는 좁은 장비(맨몸뿐 등)에서는 다른 날과 겹치더라도 하루를 채운다
                    refill = refill + pick(dayKept + refillUsedAcrossPlan + refill.map { it.id }, needed - refill.size)
                }
                refillUsedAcrossPlan += refill.map { it.id }
                refill.forEach { f.kept += PlannedExercise(refillSource(it), Origin.REFILL) }
            }
            AdaptedDay(f.day.dayNumber, f.day.workoutType, f.kept.toList(), f.injury, f.equipment)
        }
        return if (ctx.focus.isEmpty()) adapted else addFocusVolume(adapted, ctx)
    }

    /**
     * 부상 부위를 피하는 대체 운동. 그날의 부위 → 코어 → 상체 순으로 라운드로빈, 인기순.
     * 카탈로그에 후보가 없으면 빈 목록(그날은 있는 만큼만 간다).
     */
    fun pickRefill(
        workoutType: String,
        injuries: Set<String>,
        exclude: Set<Long>,
        needed: Int,
        allowedEquipment: Set<String> = emptySet(),
        maxDifficulty: Int? = null,
    ): List<Exercise> {
        if (needed <= 0) return emptyList()
        val categories = refillCategoriesFor(workoutType) + listOf(
            ExerciseCategory.CORE, ExerciseCategory.CHEST, ExerciseCategory.BACK,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS,
        )
        val picked = mutableListOf<Exercise>()
        val seen = exclude.toMutableSet()
        // 카테고리별 후보 큐를 만들고 라운드로빈으로 뽑는다 — 한 부위(가슴)만 몰리지 않게
        val queues = categories.distinct().map { category ->
            poolByCategory(category).filter { ex ->
                ex.id !in seen && equipmentAllowed(ex, allowedEquipment) &&
                    (maxDifficulty == null || ex.difficulty <= maxDifficulty) &&
                    (injuries.isEmpty() || !isRestricted(ex))
            }.iterator()
        }
        var progressed = true
        while (picked.size < needed && progressed) {
            progressed = false
            for (q in queues) {
                if (picked.size >= needed) break
                while (q.hasNext()) {
                    val ex = q.next()
                    if (ex.id in seen) continue
                    seen += ex.id; picked += ex; progressed = true; break
                }
            }
        }
        return picked
    }

    // ── 집중 부위 볼륨 ─────────────────────────────────────────────

    /**
     * 집중 부위의 주간 운동 수가 목표에 못 미치면 그 부위 운동을 끼워 넣는다.
     * - 그 부위를 하기에 어색하지 않은 날에만, 해당 부위 운동이 가장 적은 날부터
     * - 하루 운동 수는 늘리지 않고 보조 종목(비복합 중 인기 낮은 것)과 바꾼다. 바꿀 게 없으면 하루 [MAX_EXERCISES_PER_DAY]개까지 추가
     * - 부상·장비·난이도 필터를 그대로 통과한 운동만
     */
    private fun addFocusVolume(days: List<AdaptedDay>, ctx: Context): List<AdaptedDay> {
        val mutable = days.map { it.dayNumber to it.exercises.toMutableList() }.toMap(LinkedHashMap())
        val usedInPlan = days.flatMap { d -> d.exercises.map { it.exercise.id } }.toMutableSet()
        val patternsUsed = days.flatMap { d -> d.exercises.map { it.exercise.movementPattern } }.toMutableList()
        val allFocus = ctx.focus.toSet()
        val target = focusTargetPerWeek(days.size)

        for (area in ctx.focus) {
            var current = mutable.values.sumOf { list -> list.count { area.hits(it.exercise) } }
            val pool = focusPool(area, ctx)
            var guard = 0
            while (current < target && guard++ < 12) {
                // 이 부위가 가장 적은 날(어색하지 않은 날 중)
                val dayCandidates = days.filter { area.fitsDay(it.workoutType) }
                    .sortedWith(compareBy({ d -> mutable[d.dayNumber]!!.count { area.hits(it.exercise) } }, { it.dayNumber }))
                var placed = false
                for (day in dayCandidates) {
                    val list = mutable[day.dayNumber]!!
                    val inDay = list.map { it.exercise.id }.toSet()
                    val pick = chooseFocusExercise(pool, area, day.workoutType, inDay, usedInPlan, patternsUsed) ?: continue
                    val swapIdx = swappableIndex(list, allFocus) { a ->
                        // 다른 집중 부위가 목표를 넘겨 갖고 있으면 그 부위의 보조 종목은 바꿔도 된다
                        a != area && mutable.values.sumOf { l -> l.count { a.hits(it.exercise) } } > target
                    }
                    val planned = PlannedExercise(focusSource(pick), Origin.FOCUS)
                    if (swapIdx != null) {
                        list[swapIdx] = planned
                    } else if (list.size < MAX_EXERCISES_PER_DAY) {
                        list += planned
                    } else continue
                    usedInPlan += pick.id
                    patternsUsed += pick.movementPattern
                    current++
                    placed = true
                    break
                }
                if (!placed) break
            }
        }
        return days.map { it.copy(exercises = mutable[it.dayNumber]!!.toList()) }
    }

    private fun focusPool(area: FocusArea, ctx: Context): List<Exercise> =
        area.candidateCategories().flatMap { poolByCategory(it) }
            .filter { ex ->
                area.hits(ex) && equipmentAllowed(ex, ctx.allowedEquipment) &&
                    (ctx.maxDifficulty == null || ex.difficulty <= ctx.maxDifficulty) &&
                    (ctx.injuries.isEmpty() || !isRestricted(ex))
            }
            // 티어 → 인기도. 같은 인기도면 id 로 고정(재현성)
            .sortedWith(compareBy<Exercise>({ tierRank(it.recommendationTier) }, { -it.popularity }, { it.id }))

    /** 새 패턴 우선(스쿼트만 3개 같은 쏠림 방지), 플랜에 아직 없는 운동 우선, 같은 날 중복 금지 */
    private fun chooseFocusExercise(
        pool: List<Exercise>,
        area: FocusArea,
        workoutType: String,
        inDay: Set<Long>,
        usedInPlan: Set<Long>,
        patternsUsed: List<String?>,
    ): Exercise? {
        val fit = pool.filter { it.id !in inDay && area.fitsExerciseOnDay(it, workoutType) }
        return fit.firstOrNull { it.id !in usedInPlan && it.movementPattern !in patternsUsed }
            ?: fit.firstOrNull { it.id !in usedInPlan }
            ?: fit.firstOrNull()
    }

    /**
     * 바꿔도 되는 보조 종목: 복합이 아니고, 집중 부위가 아니거나 (집중 부위여도 그 부위가 [surplus]) 인 것 중 인기도가 가장 낮은 것.
     * 상체 위주 4개 부위를 집중하는 사용자처럼 모든 보조 종목이 집중 부위일 때도 모자란 부위를 채울 수 있게 한다.
     */
    private fun swappableIndex(list: List<PlannedExercise>, focus: Set<FocusArea>, surplus: (FocusArea) -> Boolean): Int? =
        list.withIndex()
            .filter { (_, p) ->
                !p.source.isCompound && p.origin != Origin.FOCUS &&
                    focus.filter { it.hits(p.exercise) }.all(surplus)
            }
            .minByOrNull { (_, p) -> p.exercise.popularity }
            ?.index

    private fun focusTargetPerWeek(planDays: Int) = if (planDays >= 5) 4 else 3

    private fun focusSource(ex: Exercise): SourceExercise {
        val compound = ex.movementPattern in COMPOUND_PATTERNS
        val core = ex.category == ExerciseCategory.CORE
        return SourceExercise(
            exercise = ex, sets = 3,
            minReps = if (core) 12 else 10, maxReps = if (core) 20 else 15,
            restSeconds = if (compound) 75 else 60, isCompound = compound, targetRPE = 7.0,
        )
    }

    private fun refillSource(ex: Exercise) = SourceExercise(
        exercise = ex, sets = 3, minReps = 10, maxReps = 15, restSeconds = 60, isCompound = false, targetRPE = 7.0,
    )

    private fun tierRank(t: RecommendationTier) = when (t) {
        RecommendationTier.ESSENTIAL -> 0
        RecommendationTier.STANDARD -> 1
        else -> 2
    }

    companion object {
        /** 템플릿 하루 운동 수 목표. 부상·장비로 빠진 만큼 여기까지 채운다. */
        const val TARGET_EXERCISES_PER_DAY = 5
        const val MAX_EXERCISES_PER_DAY = 6

        private val COMPOUND_PATTERNS = setOf("SQUAT", "LUNGE", "LEG_PRESS", "DEADLIFT", "HIP_HINGE", "GLUTE_FOCUSED")

        /** 보유 장비로 할 수 있는 종목인가. 장비 미지정·맨몸·기타는 항상 허용. */
        fun equipmentAllowed(ex: Exercise, allowedEquipment: Set<String>): Boolean {
            val eq = ex.equipment?.name ?: return true
            return allowedEquipment.isEmpty() || eq == "BODYWEIGHT" || eq == "OTHER" || eq in allowedEquipment
        }

        fun refillCategoriesFor(workoutType: String): List<ExerciseCategory> = when (workoutType.uppercase()) {
            "PUSH", "CHEST" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
            "PULL", "BACK" -> listOf(ExerciseCategory.BACK, ExerciseCategory.ARMS)
            "LEGS", "LOWER" -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CORE)
            "UPPER" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
            "SHOULDERS" -> listOf(ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
            "ARMS" -> listOf(ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS)
            "ABS", "CORE" -> listOf(ExerciseCategory.CORE)
            "CARDIO" -> listOf(ExerciseCategory.CARDIO, ExerciseCategory.LEGS)
            else -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.CORE)
        }

        /** 경험 수준 → 채워 넣는 운동의 난이도 상한(1-100). 초보에게 바벨 벤치·데드리프트 변형 같은 40+ 동작을 알아서 채워 넣지 않는다. */
        fun maxDifficultyFor(experience: String?): Int? = when (experience?.trim()?.lowercase()) {
            "beginner", "novice" -> 40
            "intermediate" -> 60
            else -> null
        }
    }
}

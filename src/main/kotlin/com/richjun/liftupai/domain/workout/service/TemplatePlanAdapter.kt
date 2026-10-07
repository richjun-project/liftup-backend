package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules
import com.richjun.liftupai.domain.workout.service.new.SessionFit

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import kotlin.math.roundToInt

/**
 * 템플릿을 한 사람에게 맞게 고쳐 쓰는 순수 로직 (DB·Spring 비의존).
 *
 *  1) 부상 부위를 건드리거나 보유 장비로 못 하는 종목, 이 사람에게 맞지 않는 종목(철봉 필요·너무 어려운 동작)을 뺀다
 *  2) 빠져서 하루가 헐거워지면 안전한 운동으로 다시 채운다 (한 번 쓴 운동은 플랜 전체에서 다시 안 쓴다)
 *  3) 집중 부위(하체·둔근·팔 …)가 주간 볼륨에서 모자라면 해당 부위 운동을 끼워 넣는다
 *  4) 집/부상 사용자의 플랜은 같은 운동이 주 2회를 넘지 않게 다양화한다
 *  5) 하루의 첫 운동은 그날의 대표 복합운동으로
 *  6) 초보·고령·미성년의 처방을 보수적으로 (주력 복합 3세트 이하·8회 이상, 연령별 세트 상한)
 *  7) 저장된 세션 시간의 85~110% 에 들어오게 세트·휴식을 맞추고, 모자라면 종목을 더한다 / 넘치면 덜 중요한 종목부터 뺀다
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
        val skippedForSuitability: Int = 0,
    ) {
        /** 이 하루의 추정 시간(분) — 세션 시간 맞춤에 쓴 것과 같은 식 */
        fun estimatedMinutes(): Int = SessionFit.minutes(exercises.map { slotOf(it.source, null, null, false) })
    }

    data class Context(
        /** Equipment enum 이름(BARBELL 등). 비어 있으면 제한 없음 */
        val allowedEquipment: Set<String> = emptySet(),
        val injuries: Set<String> = emptySet(),
        val focus: List<FocusArea> = emptyList(),
        /** 채워 넣는 운동의 난이도 상한(1-100). null 이면 제한 없음 */
        val maxDifficulty: Int? = null,
        /** 저장된 회당 운동 시간(분). null 이면 시간 맞춤을 하지 않는다 */
        val sessionMinutes: Int? = null,
        /** 이 사람이 실제로 할 수 있는 운동인가(철봉·난이도). 기본은 전부 허용 */
        val suitable: (Exercise) -> Boolean = { true },
        /** 경험 수준. null 이면 처방 보정을 하지 않는다 */
        val level: ExperienceLevel? = null,
        val age: Int? = null,
        /** 한 운동이 주에 이 횟수를 넘지 않게 다양화한다. null 이면 제한 없음(헬스장 템플릿은 같은 운동을 반복하는 게 정상) */
        val repeatCap: Int? = null,
    ) {
        internal val beginner: Boolean get() = level != null && RecoRules.isBeginnerBand(level)
    }

    fun adapt(days: List<SourceDay>, ctx: Context): List<AdaptedDay> {
        // 1단계: 부상·장비·적합성으로 못 하는 종목을 뺀다
        class Filtered(val day: SourceDay, val kept: MutableList<PlannedExercise>, var injury: Int, var equipment: Int, var suitability: Int)
        val filtered = days.map { day ->
            val f = Filtered(day, mutableListOf(), 0, 0, 0)
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
                // 철봉이 없는 사람에게 풀업, 초보에게 데드리프트 변형·고급 동작은 넣지 않는다 (template apply 에 이 규칙이 없었다)
                if (!ctx.suitable(src.exercise)) {
                    f.suitability++
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
                val dayPatterns = f.kept.mapNotNull { it.exercise.movementPattern }.toSet()
                fun pick(exclude: Set<Long>, n: Int, maxDifficulty: Int?) = pickRefill(
                    workoutType = f.day.workoutType, injuries = ctx.injuries, exclude = exclude, needed = n,
                    allowedEquipment = ctx.allowedEquipment, maxDifficulty = maxDifficulty,
                    suitable = ctx.suitable, avoidPatterns = dayPatterns,
                )
                var refill = pick(planKept + refillUsedAcrossPlan, needed, ctx.maxDifficulty)
                if (refill.size < needed && ctx.maxDifficulty != null) {
                    // 난이도 상한(초보 40)에 맞는 후보가 없는 부위(맨몸 등운동 0개)는 "맞는 운동" 규칙만 지키고 채운다
                    refill = refill + pick(planKept + refillUsedAcrossPlan + refill.map { it.id }, needed - refill.size, null)
                }
                if (refill.size < needed) {
                    // 후보가 모자라는 좁은 장비(맨몸뿐 등)에서는 다른 날과 겹치더라도 하루를 채운다
                    refill = refill + pick(dayKept + refillUsedAcrossPlan + refill.map { it.id }, needed - refill.size, null)
                }
                refillUsedAcrossPlan += refill.map { it.id }
                refill.forEach { f.kept += PlannedExercise(refillSource(it), Origin.REFILL) }
            }
            AdaptedDay(f.day.dayNumber, f.day.workoutType, f.kept.toList(), f.injury, f.equipment, f.suitability)
        }
        val focused = if (ctx.focus.isEmpty()) adapted else addFocusVolume(adapted, ctx)
        val varied = diversify(focused, ctx)
        val ordered = varied.map { it.copy(exercises = leadWithBestCompound(it.exercises)) }
        val finished = finishDays(ordered, ctx)
        // 시간 맞춤이 집중 부위 운동을 잘라 냈을 수 있다 — 모자라면 다시 채우고 한 번 더 맞춘다 (집중 볼륨은 시간 맞춤 뒤에도 지켜져야 한다)
        if (ctx.focus.isEmpty() || ctx.sessionMinutes == null) return finished
        val refocused = addFocusVolume(finished, ctx)
        return if (refocused == finished) finished
        else finishDays(refocused.map { it.copy(exercises = leadWithBestCompound(it.exercises)) }, ctx)
    }

    /**
     * 부상 부위를 피하는 대체 운동. 그날의 부위 → 코어 → 상체 순으로 라운드로빈, 인기순.
     * 카탈로그에 후보가 없으면 빈 목록(그날은 있는 만큼만 간다).
     * @param avoidPatterns 이미 하루에 있는 동작 패턴 — 후보가 충분하면 겹치지 않게 뒤로 미룬다(푸시업만 3개 같은 쏠림 방지)
     */
    fun pickRefill(
        workoutType: String,
        injuries: Set<String>,
        exclude: Set<Long>,
        needed: Int,
        allowedEquipment: Set<String> = emptySet(),
        maxDifficulty: Int? = null,
        suitable: (Exercise) -> Boolean = { true },
        avoidPatterns: Set<String> = emptySet(),
    ): List<Exercise> {
        if (needed <= 0) return emptyList()
        // 마지막 수단으로 다른 부위(하체 포함)까지 — 부상·장비로 그날의 부위 후보가 바닥난 긴 세션이 반쯤 빈 채 나가지 않게
        val categories = refillCategoriesFor(workoutType) + listOf(
            ExerciseCategory.CORE, ExerciseCategory.CHEST, ExerciseCategory.BACK,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.LEGS,
        )
        val picked = mutableListOf<Exercise>()
        val seen = exclude.toMutableSet()
        val patterns = avoidPatterns.toMutableSet()
        // 카테고리별 후보 큐를 만들고 라운드로빈으로 뽑는다 — 한 부위(가슴)만 몰리지 않게
        fun queues() = categories.distinct().map { category ->
            poolByCategory(category).filter { ex ->
                ex.id !in seen && equipmentAllowed(ex, allowedEquipment) &&
                    (maxDifficulty == null || ex.difficulty <= maxDifficulty) &&
                    suitable(ex) &&
                    (injuries.isEmpty() || !isRestricted(ex))
            }
        }
        // 1차: 이미 하루에 있는 동작 패턴과 겹치지 않는 후보. 2차: 모자라면 겹쳐도 채운다
        for (strict in listOf(true, false)) {
            if (picked.size >= needed) break
            val its = queues().map { list -> list.filter { !strict || it.movementPattern !in patterns }.iterator() }
            var progressed = true
            while (picked.size < needed && progressed) {
                progressed = false
                for (q in its) {
                    if (picked.size >= needed) break
                    while (q.hasNext()) {
                        val ex = q.next()
                        if (ex.id in seen) continue
                        seen += ex.id; picked += ex; progressed = true
                        ex.movementPattern?.let { patterns += it }
                        break
                    }
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
     * - 부상·장비·난이도·적합성 필터를 그대로 통과한 운동만
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
                    } else {
                        // 하루가 가득 차 있고 바꿀 보조 종목도 없으면, 채워 넣은(REFILL) 종목 중 집중 부위가 아닌 것 하나를 바꾼다 — 템플릿 본래 종목은 건드리지 않는다
                        val refillIdx = list.withIndex()
                            .filter { (i, p) -> i > 0 && p.origin == Origin.REFILL && allFocus.none { it.hits(p.exercise) } }
                            .minByOrNull { (_, p) -> p.exercise.popularity }?.index ?: continue
                        list[refillIdx] = planned
                    }
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
                    ctx.suitable(ex) &&
                    (ctx.injuries.isEmpty() || !isRestricted(ex))
            }
            // 난이도 상한(초보 40) 안의 운동을 먼저, 모자라면 상한 밖(적합성은 통과)도 쓴다 — 카탈로그 난이도는 초보 기준으로 믿기 어렵다
            // (바벨 로우 등이 상한 밖이라 등 집중 목표 초보에게 등 운동이 하나도 안 들어갔다). 그다음 티어 → 인기도, 같으면 id 로 고정(재현성)
            .sortedWith(compareBy<Exercise>({ if (ctx.maxDifficulty != null && it.difficulty > ctx.maxDifficulty) 1 else 0 }, { tierRank(it.recommendationTier) }, { -it.popularity }, { it.id }))

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


    private fun focusSource(ex: Exercise): SourceExercise {
        val compound = RecoRules.isCompound(ex)
        val core = ex.category == ExerciseCategory.CORE
        return SourceExercise(
            exercise = ex, sets = 3,
            minReps = if (core) 12 else 10, maxReps = if (core) 20 else 15,
            restSeconds = if (compound) 75 else 60, isCompound = compound, targetRPE = 7.0,
        )
    }

    private fun refillSource(ex: Exercise): SourceExercise {
        val compound = RecoRules.isCompound(ex)
        val core = ex.category == ExerciseCategory.CORE
        return SourceExercise(
            exercise = ex, sets = 3, minReps = if (core) 12 else 10, maxReps = if (core) 20 else 15,
            restSeconds = if (compound) 75 else 60, isCompound = compound, targetRPE = 7.0,
        )
    }

    private fun tierRank(t: RecommendationTier) = when (t) {
        RecommendationTier.ESSENTIAL -> 0
        RecommendationTier.STANDARD -> 1
        else -> 2
    }

    // ── 다양화 (집/부상 사용자의 같은 운동 반복) ─────────────────────────────

    /**
     * 같은 운동이 주에 [Context.repeatCap] 번을 넘으면 넘는 날의 운동을 같은 부위의 안 쓴 운동으로 바꾼다.
     * 처방(세트·반복·휴식)은 같은 성격이면 자리의 것을 쓰고(복합↔고립·코어 여부가 바뀌면 새 종목 기준), 맞는 후보가 없으면 그대로 둔다(후보가 모자란 좁은 장비).
     * 2026-10 탐색: 무릎 부상 홈트 사용자 플랜에서 글루트 브릿지 변형이 주 3회, 푸시업이 주 2~3회 반복됐다.
     */
    private fun diversify(days: List<AdaptedDay>, ctx: Context): List<AdaptedDay> {
        val cap = ctx.repeatCap ?: return days
        val seen = HashMap<Long, Int>()
        return days.map { day ->
            val list = day.exercises.toMutableList()
            val inDay = list.map { it.exercise.id }.toMutableSet()
            for (i in list.indices) {
                val p = list[i]
                val id = p.exercise.id
                if ((seen[id] ?: 0) < cap) { seen.merge(id, 1, Int::plus); continue }
                // 그날의 대표(ESSENTIAL) 복합운동은 되도록 같은 급 복합운동으로 바꾼다(없으면 반복 제한이 우선) — 다양화 때문에 첫 운동 품질이 떨어지지 않게
                val protectLead = p.exercise.recommendationTier == RecommendationTier.ESSENTIAL && p.source.isCompound
                val repl = (if (protectLead) pickReplacement(p.exercise, day.workoutType, inDay, seen, cap, ctx, requireEssentialCompound = true) else null)
                    ?: pickReplacement(p.exercise, day.workoutType, inDay, seen, cap, ctx)
                if (repl != null) {
                    val rx = p.source
                    val compound = RecoRules.isCompound(repl)
                    // 자리의 처방(예: 복합 3x8-10)은 같은 성격의 종목에만 어울린다. 복합↔고립 또는 코어↔비코어로 바뀌면
                    // 새 종목의 성격에 맞는 기본 처방으로 다시 짠다(플랭크에 복합 처방, 코어 자리에 바벨 복합이 가던 것을 막는다).
                    val sameKind = RecoRules.isCompound(p.exercise) == compound &&
                        (p.exercise.category == ExerciseCategory.CORE) == (repl.category == ExerciseCategory.CORE)
                    val next = if (sameKind) rx.copy(exercise = repl, isCompound = compound, notes = null)
                        else refillSource(repl).copy(targetRPE = rx.targetRPE)
                    list[i] = PlannedExercise(next, Origin.REFILL)
                    inDay += repl.id
                    seen.merge(repl.id, 1, Int::plus)
                } else {
                    seen.merge(id, 1, Int::plus)
                }
            }
            day.copy(exercises = list)
        }
    }

    private fun pickReplacement(
        original: Exercise,
        workoutType: String,
        inDay: Set<Long>,
        seen: Map<Long, Int>,
        cap: Int,
        ctx: Context,
        requireEssentialCompound: Boolean = false,
    ): Exercise? {
        val categories = (listOf(original.category) + refillCategoriesFor(workoutType) + listOf(
            ExerciseCategory.CORE, ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.LEGS,
        )).distinct()
        val originalCompound = RecoRules.isCompound(original)
        // 집중 부위 볼륨을 채우던 운동은 같은 집중 부위를 채우는 운동으로만 바꾼다(없으면 반복을 허용 — 집중 볼륨이 우선)
        val originalFocus = ctx.focus.filter { it.hits(original) }
        for (category in categories) {
            val candidates = poolByCategory(category).filter { ex ->
                ex.id !in inDay && (seen[ex.id] ?: 0) < cap &&
                    equipmentAllowed(ex, ctx.allowedEquipment) && ctx.suitable(ex) &&
                    (ctx.injuries.isEmpty() || !isRestricted(ex)) &&
                    (originalFocus.isEmpty() || originalFocus.any { it.hits(ex) }) &&
                    (!requireEssentialCompound || (ex.recommendationTier == RecommendationTier.ESSENTIAL && RecoRules.isCompound(ex)))
            }
            if (candidates.isEmpty()) continue
            // 같은 부위(카테고리) 안에서: 안 쓴 것 → 같은 복합/고립 성격 → 티어 → 인기도
            return candidates.minWithOrNull(
                compareBy<Exercise>({ seen[it.id] ?: 0 }, { if (RecoRules.isCompound(it) == originalCompound) 0 else 1 }, { tierRank(it.recommendationTier) }, { -it.popularity }, { it.id })
            ) ?: continue
        }
        return null
    }

    // ── 하루 구성 다듬기 ─────────────────────────────────────────────

    /**
     * 하루의 첫 운동이 그날의 대표 복합운동이 되게 한다: 대표(ESSENTIAL) 복합 → 그 외 복합 순으로 가장 인기 있는 것을 앞으로.
     * 복합운동이 하나도 없으면 코어·유산소가 아닌 운동을 앞으로(복근 운동으로 시작하는 날을 없앤다). 이미 그렇거나 후보가 없으면 그대로.
     */
    private fun leadWithBestCompound(list: List<PlannedExercise>): List<PlannedExercise> {
        if (list.size < 2) return list
        fun isMain(p: PlannedExercise) = p.exercise.category != ExerciseCategory.CORE && p.exercise.category != ExerciseCategory.CARDIO
        fun isEssentialLead(p: PlannedExercise) = p.exercise.recommendationTier == RecommendationTier.ESSENTIAL && p.source.isCompound && isMain(p)
        val first = list.first()
        if (isEssentialLead(first)) return list
        fun promote(index: Int): List<PlannedExercise> {
            val m = list.toMutableList()
            m.add(0, m.removeAt(index))
            return m
        }
        list.withIndex().filter { (_, p) -> isEssentialLead(p) }
            .maxWithOrNull(compareBy<IndexedValue<PlannedExercise>>({ it.value.exercise.popularity }, { -it.index }))
            ?.let { return promote(it.index) }
        if (first.source.isCompound && isMain(first)) return list
        list.withIndex().filter { (_, p) -> p.source.isCompound && isMain(p) }
            .maxWithOrNull(compareBy<IndexedValue<PlannedExercise>>({ it.value.exercise.popularity }, { -it.index }))
            ?.let { return promote(it.index) }
        if (!isMain(first)) list.indexOfFirst { isMain(it) }.takeIf { it > 0 }?.let { return promote(it) }
        return list
    }

    /**
     * 이미 정해진 하루(예: AI 플랜)에 처방 보정(초보·연령)과 세션 시간 맞춤만 적용한다. 종목 필터·채우기는 하지 않는다.
     * 시간이 모자라면 종목을 더하고 넘치면 덜 중요한 종목부터 뺀다.
     */
    fun personalize(days: List<AdaptedDay>, ctx: Context): List<AdaptedDay> = finishDays(days, ctx)

    private fun finishDays(days: List<AdaptedDay>, ctx: Context): List<AdaptedDay> {
        if (ctx.level == null && ctx.age == null && ctx.sessionMinutes == null) return days
        val planUse = HashMap<Long, Int>()
        days.forEach { d -> d.exercises.forEach { planUse.merge(it.exercise.id, 1, Int::plus) } }
        // 하루에 지켜야 할 집중 부위 운동 수 — 짧은 세션에서 시간을 맞추려고 집중 부위 운동부터 잘라 내지 않게 한다
        val dayCount = days.size.coerceAtLeast(1)
        val focusQuota = if (ctx.focus.isEmpty()) 0 else (focusTargetPerWeek(dayCount) + dayCount - 1) / dayCount
        return days.map { day ->
            val prescribed = day.exercises.map { prescribe(it, ctx) }
            val target = ctx.sessionMinutes
            val fitted = if (target != null) fitDay(day.workoutType, prescribed, ctx, target, planUse, focusQuota) else prescribed
            // 시간 맞춤으로 종목이 더해졌을 수 있다 — 그날의 대표 복합운동이 다시 첫 운동이 되게 한다
            day.copy(exercises = leadWithBestCompound(fitted))
        }
    }

    /** 초보·고령·미성년 처방 보정 (주력 복합 3세트 이하·8회 이상, 연령별 세트 상한, 미성년·고령 RPE ≤ 7) */
    private fun prescribe(p: PlannedExercise, ctx: Context): PlannedExercise {
        var s = p.source
        if (ctx.beginner) {
            val rx = RecoRules.beginnerRx(s.exercise, RecoRules.Rx(s.sets, s.minReps, s.maxReps, s.restSeconds), s.isCompound)
            s = s.copy(sets = rx.sets, minReps = rx.minReps, maxReps = rx.maxReps, restSeconds = rx.restSeconds)
        }
        val cap = setCap(s, ctx)
        if (s.sets > cap) s = s.copy(sets = cap)
        if (com.richjun.liftupai.domain.workout.util.ExerciseSuitability.isVulnerable(ctx.age) && s.targetRPE > 7.0) s = s.copy(targetRPE = 7.0)
        return if (s == p.source) p else p.copy(source = s)
    }

    private fun setCap(s: SourceExercise, ctx: Context): Int =
        RecoRules.maxSetsFor(s.exercise, ctx.level, ctx.age, s.isCompound, ctx.sessionMinutes)

    private fun slotOfPlanned(p: PlannedExercise, ctx: Context, softMaxSets: Int? = null): SessionFit.Slot {
        val cap = setCap(p.source, ctx)
        // 이미 그보다 많은 세트로 짜인 종목은 그 수를 유지한다(줄이는 것은 시간이 넘칠 때만)
        val limit = softMaxSets?.let { minOf(cap, maxOf(it, p.source.sets)) } ?: cap
        return slotOf(p.source, limit, null, true)
    }

    /**
     * 목표 시간에 맞춘다. 넘치면 세트·휴식을 줄이고 그래도 넘치면 덜 중요한 종목을 뺀다.
     * 모자라면 세트·휴식을 상한까지 늘리고, 그래도 모자라면 종목을 더한다.
     */
    private fun fitDay(workoutType: String, input: List<PlannedExercise>, ctx: Context, target: Int, planUse: MutableMap<Long, Int>, focusQuota: Int = 0): List<PlannedExercise> {
        var current = input
        // 20분 이하의 아주 짧은 세션은 2종목까지, 30분 이상은 4종목 미만으로 줄이지 않는다
        val minExercises = when {
            target >= 30 -> 4
            target > 20 -> 3
            else -> 2
        }
        val maxExercises = maxExercisesFor(target)
        var adds = 0
        // 1단계: 세트는 표준(3세트)까지만 늘리고, 모자라면 종목을 더한다 — 코치는 세트를 4~5로 올리기 전에 종목을 먼저 늘린다
        while (true) {
            val before = current
            val res = SessionFit.fit(
                current.map { slotOfPlanned(it, ctx, COMFORT_SETS) }, target, minExercises,
                pickDrop = { kept, _ -> dropCandidate(kept.map { before[it] }, ctx, focusQuota) },
            )
            current = res.keptIndices.mapIndexed { pos, orig -> applySlot(before[orig], res.slots[pos]) }
            val ratio = SessionFit.ratio(res.slots, target)
            if (ratio >= SessionFit.NEEDS_MORE || current.size >= maxExercises || adds >= MAX_ADDS_PER_DAY) break
            val extra = chooseAddition(workoutType, current, ctx, planUse) ?: break
            current = current + extra
            planUse.merge(extra.exercise.id, 1, Int::plus)
            adds++
        }
        // 2단계: 그래도 모자라면 세트·휴식을 종목별 상한까지 늘려 채운다
        if (SessionFit.ratio(current.map { slotOfPlanned(it, ctx) }, target) < SessionFit.LOWER) {
            val res = SessionFit.fit(current.map { slotOfPlanned(it, ctx) }, target, minExercises)
            current = current.mapIndexed { i, p -> applySlot(p, res.slots[i]) }
        }
        return current
    }

    private fun applySlot(p: PlannedExercise, slot: SessionFit.Slot): PlannedExercise =
        if (p.source.sets == slot.sets && p.source.restSeconds == slot.restSeconds) p
        else p.copy(source = p.source.copy(sets = slot.sets, restSeconds = slot.restSeconds))

    /** 시간이 넘칠 때 뺄 종목: 복합·집중 부위·ESSENTIAL 이 아닌 것 중 가치가 가장 낮은 것. 첫 종목은 지킨다. */
    private fun dropCandidate(list: List<PlannedExercise>, ctx: Context, focusQuota: Int = 0): Int? {
        if (list.size <= 1) return null
        // 그날 집중 부위 운동이 할당량 이하면 그 종목은 복합운동보다도 먼저 지킨다
        fun focusProtected(p: PlannedExercise): Boolean = focusQuota > 0 && ctx.focus.any { area ->
            area.hits(p.exercise) && list.count { area.hits(it.exercise) } <= focusQuota
        }
        fun keep(p: PlannedExercise): Double = RecoRules.keepValue(p.exercise, p.source.isCompound, ctx.focus, p.origin == Origin.TEMPLATE) +
            if (focusProtected(p)) 200.0 else 0.0
        return (1 until list.size).minWithOrNull(compareBy<Int>({ keep(list[it]) }, { -it }))
    }

    /** 시간이 모자랄 때 더할 종목: 집중 부위 → 그날의 부위 라운드로빈. 겹치지 않는 동작 패턴 우선, 플랜에서 덜 쓴 운동 우선. */
    private fun chooseAddition(workoutType: String, current: List<PlannedExercise>, ctx: Context, planUse: Map<Long, Int>): PlannedExercise? {
        val inDay = current.map { it.exercise.id }.toSet()
        val patterns = current.mapNotNull { it.exercise.movementPattern }.toSet()
        for (area in ctx.focus) {
            if (!area.fitsDay(workoutType)) continue
            // 한 날에 한 집중 부위로 도배하지 않는다(복근 운동만 여섯 개 같은 구성) — 부위당 하루 3개까지
            if (current.count { area.hits(it.exercise) } >= FOCUS_PER_DAY_CAP) continue
            val fit = focusPool(area, ctx).filter { it.id !in inDay && area.fitsExerciseOnDay(it, workoutType) }
            val pick = fit.minWithOrNull(compareBy<Exercise>({ planUse[it.id] ?: 0 }, { if (it.movementPattern in patterns) 1 else 0 }, { tierRank(it.recommendationTier) }, { -it.popularity }, { it.id }))
            if (pick != null && (ctx.repeatCap == null || (planUse[pick.id] ?: 0) < ctx.repeatCap)) return PlannedExercise(focusSource(pick), Origin.FOCUS)
        }
        val cap = ctx.repeatCap
        val used = planUse.filter { (_, n) -> cap != null && n >= cap }.keys
        // 1차: 주간 반복 상한을 지키는 후보. 2차: 후보가 바닥났으면(좁은 장비·부상) 반복을 허용하고서라도 요청한 시간을 채운다
        val picked = listOf(inDay + used, inDay).firstNotNullOfOrNull { exclude ->
            pickRefill(workoutType, ctx.injuries, exclude, 1, ctx.allowedEquipment, ctx.maxDifficulty, ctx.suitable, patterns).ifEmpty {
                pickRefill(workoutType, ctx.injuries, exclude, 1, ctx.allowedEquipment, null, ctx.suitable, patterns)
            }.firstOrNull()
        } ?: return null
        return PlannedExercise(refillSource(picked), Origin.REFILL)
    }

    companion object {
        /** 템플릿 하루 운동 수 목표. 부상·장비로 빠진 만큼 여기까지 채운다. */
        const val TARGET_EXERCISES_PER_DAY = 5
        const val MAX_EXERCISES_PER_DAY = 6
        private const val MAX_ADDS_PER_DAY = 10
        private const val COMFORT_SETS = 3
        private const val FOCUS_PER_DAY_CAP = 3

        /** 집중 부위의 주간 운동 수 목표 */
        fun focusTargetPerWeek(planDays: Int) = if (planDays >= 5) 4 else 3

        /** 세션 시간별 하루 종목 수 상한 */
        fun maxExercisesFor(minutes: Int): Int = when {
            minutes <= 45 -> MAX_EXERCISES_PER_DAY
            minutes <= 75 -> 7
            minutes <= 90 -> 8
            else -> 10
        }

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

        /**
         * 같은 운동이 주 몇 회까지인가. 집·부상 사용자는 후보가 좁아 같은 운동이 반복되기 쉬워 주 2회로 제한한다.
         * 헬스장 이용자의 풀바디 템플릿은 같은 주력 운동을 반복하는 것이 정상이라 제한하지 않는다(null).
         */
        fun repeatCapFor(equipment: Set<String>, injuries: Set<String>): Int? {
            val gym = equipment.isEmpty() || equipment.any { it in setOf("BARBELL", "MACHINE", "CABLE") }
            return if (injuries.isNotEmpty() || !gym) 2 else null
        }

        internal fun slotOf(s: SourceExercise, maxSets: Int?, maxRest: Int?, withLimits: Boolean): SessionFit.Slot {
            val minRest = (s.restSeconds * 0.75).roundToInt().coerceAtLeast(30).coerceAtMost(s.restSeconds)
            val upRest = if (!withLimits) s.restSeconds else maxRest ?: maxRestFor(s)
            return SessionFit.Slot(
                sets = s.sets, reps = "${s.minReps}-${s.maxReps}", restSeconds = s.restSeconds,
                minRestSeconds = minRest, maxRestSeconds = upRest.coerceAtLeast(s.restSeconds), maxSets = (maxSets ?: 5).coerceAtLeast(s.sets),
            )
        }

        /** 늘려도 되는 휴식 상한 (RecoRules.maxRestFor) */
        private fun maxRestFor(s: SourceExercise): Int = RecoRules.maxRestFor(s.exercise, s.isCompound, s.minReps)
    }
}

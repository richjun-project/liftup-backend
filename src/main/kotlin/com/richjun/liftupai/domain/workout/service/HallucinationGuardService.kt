package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class AIPlanExerciseEntry(
    @JsonProperty("exerciseId") @JsonAlias("exercise_id")
    var exerciseId: Long,
    @JsonProperty("exerciseName") @JsonAlias("exercise_name")
    var exerciseName: String,
    val sets: Int,
    @JsonProperty("minReps") @JsonAlias("min_reps")
    val minReps: Int,
    @JsonProperty("maxReps") @JsonAlias("max_reps")
    val maxReps: Int,
    @JsonProperty("restSeconds") @JsonAlias("rest_seconds")
    val restSeconds: Int,
    val order: Int,
    @JsonProperty("isCompound") @JsonAlias("is_compound")
    val isCompound: Boolean,
    @JsonProperty("targetRPE") @JsonAlias("target_rpe", "target_RPE")
    val targetRPE: Double = 7.0,
    val notes: String? = null
)

data class AIPlanDayEntry(
    @JsonProperty("dayNumber") @JsonAlias("day_number")
    val dayNumber: Int,
    @JsonProperty("dayName") @JsonAlias("day_name")
    val dayName: String,
    @JsonProperty("workoutType") @JsonAlias("workout_type")
    val workoutType: String,
    @JsonProperty("estimatedDurationMinutes") @JsonAlias("estimated_duration_minutes")
    val estimatedDurationMinutes: Int,
    var exercises: List<AIPlanExerciseEntry>
)

data class AIPlanResponse(
    @JsonProperty("planName") @JsonAlias("plan_name")
    val planName: String,
    @JsonProperty("planDescription") @JsonAlias("plan_description")
    val planDescription: String,
    @JsonProperty("splitType") @JsonAlias("split_type")
    val splitType: String,
    @JsonProperty("totalDays") @JsonAlias("total_days")
    val totalDays: Int,
    @JsonProperty("estimatedWeeks") @JsonAlias("estimated_weeks")
    val estimatedWeeks: Int,
    var days: List<AIPlanDayEntry>,
    @JsonProperty("coachingNotes") @JsonAlias("coaching_notes")
    val coachingNotes: String? = null,
    @JsonProperty("progressionModel") @JsonAlias("progression_model")
    val progressionModel: String = "LINEAR"
)

enum class RepairReason {
    ID_MISMATCH, HALLUCINATION_REPLACED, FALLBACK_POPULAR, DUPLICATE_PATTERN,
    /** 부상 제한 또는 보유하지 않은 장비 → 조건을 만족하는 운동으로 교체 */
    CONSTRAINT_REPLACED,
    /** 교체 후보가 없어 플랜에서 제거 (replacedWithId = -1) */
    REMOVED,
    /** 제거로 하루가 비어 안전한 운동으로 채움 (originalId = -1) */
    FILLED
}

data class RepairLog(
    val originalId: Long,
    val originalName: String,
    val replacedWithId: Long,
    val replacedWithName: String,
    val reason: RepairReason
)

data class ValidatedPlan(
    val plan: AIPlanResponse,
    val repairs: List<RepairLog>
)

/**
 * 플랜에 들어갈 수 있는 운동의 조건.
 *
 * - [allowedEquipment]: 사용자가 보유한 장비(정규화된 대문자). 비어 있으면 제한 없음.
 *   맨몸(BODYWEIGHT)/장비 미지정 운동은 항상 허용.
 * - [isRestricted]: 부상 제한 판정. 큐레이션 테이블이 비어 있어도 규칙 폴백이 동작하도록
 *   InjuryFilterService.isExerciseRestricted 를 그대로 넘긴다.
 */
data class PlanConstraints(
    val allowedEquipment: Set<String> = emptySet(),
    val injuries: Set<String> = emptySet(),
    val isRestricted: (Exercise) -> Boolean = { false },
    /** 이 사람이 실제로 할 수 있는 운동인가(철봉·너무 어려운 동작). 기본은 전부 허용 */
    val isSuitable: (Exercise) -> Boolean = { true },
) {
    fun equipmentAllowed(exercise: Exercise): Boolean {
        val name = exercise.equipment?.name ?: return true
        // 다른 경로(추천 V2·템플릿·개인화)와 같은 규칙: 맨몸·기타(밴드/볼) 는 항상 허용
        return allowedEquipment.isEmpty() || name == "BODYWEIGHT" || name == "OTHER" || name in allowedEquipment
    }

    fun accepts(exercise: Exercise): Boolean =
        exercise.isPlanEligible && equipmentAllowed(exercise) && !isRestricted(exercise) && isSuitable(exercise)

    companion object {
        val NONE = PlanConstraints()

        /** 모든 경로가 같은 정규화(RecoRules): 별칭 정리, 헬스장 전체 토큰은 "제한 없음" */
        fun normalizeEquipment(raw: Collection<String>): Set<String> = RecoRules.normalizeEquipment(raw)
    }
}

@Service
class HallucinationGuardService(
    private val exerciseRepository: ExerciseRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val log = LoggerFactory.getLogger(HallucinationGuardService::class.java)

    fun validateAndRepairPlan(plan: AIPlanResponse): ValidatedPlan =
        validateAndRepairPlan(plan, PlanConstraints.NONE)

    /**
     * AI가 반환한 플랜을 실제 카탈로그에 정합하도록 복구한다.
     *
     * 모든 복구 경로는 **플랜 후보군(isPlanEligible)** 안에서만, 그리고 [constraints]
     * (보유 장비, 부상 제한)를 만족하는 운동만 고른다.
     *
     * 과거에는 폴백이 `findByCategory(category).firstOrNull()` 였는데 ORDER BY 가 없어
     * id 최소값(= 이름 알파벳 최상위)이 뽑혔고, 그 결과 "3 Leg Dog Pose"(인기도 20)나
     * "4 Corners Curtsy" 같은 운동이 실제 사용자 플랜에 들어갔다.
     *
     * 하루 안에서 같은 운동/같은 동작 패턴이 중복되지 않도록 보정한다.
     * (프로덕션에서 하루에 어시스트 풀업 6종, 45도 하이퍼익스텐션 3종이 배치된 사례가 있었다)
     *
     * 어떤 경로로도 조건을 만족하는 운동을 찾지 못하면 그 항목은 **제거**한다.
     * 예전에는 원본을 그대로 남겨 부상 제한 운동이나 없는 장비의 운동이 플랜에 남았다.
     */
    fun validateAndRepairPlan(plan: AIPlanResponse, constraints: PlanConstraints): ValidatedPlan {
        val repairs = mutableListOf<RepairLog>()

        val validatedDays = plan.days.map { day ->
            val fallbackCategories = mapWorkoutTypeToCategories(day.workoutType)
            val usedIds = mutableSetOf<Long>()
            val usedPatterns = mutableSetOf<ExercisePatternClassifier.MovementPattern>()

            var removedInDay = 0
            val kept = day.exercises.mapNotNull { entry ->
                val resolved = resolveExercise(entry, fallbackCategories, constraints, repairs)
                val deduped = dedupeWithinDay(resolved, fallbackCategories, constraints, usedIds, usedPatterns, repairs)
                val exercise = deduped.exercise
                if (exercise == null) {
                    repairs.add(RepairLog(entry.exerciseId, entry.exerciseName, -1, "", RepairReason.REMOVED))
                    removedInDay++
                    null
                } else {
                    usedIds.add(exercise.id)
                    usedPatterns.add(exercisePatternClassifier.classifyExercise(exercise))
                    deduped.entry
                }
            }
            // 제거로 하루가 비면(부상 부위 운동만으로 짜인 날) 조건을 만족하는 운동으로 다시 채운다.
            // 예전에는 무릎 부상자의 하체 날이 통째로 빈 채 저장됐다.
            val filled = fillSparseDay(kept, removedInDay, day.workoutType, fallbackCategories, constraints, usedIds, usedPatterns, repairs)
            // 제거로 순서에 구멍이 생기면 다시 매긴다
            val renumbered = filled.sortedBy { it.order }.mapIndexed { i, e -> e.copy(order = i + 1) }
            day.copy(exercises = renumbered)
        }

        if (repairs.isNotEmpty()) {
            log.warn("Hallucination guard repaired ${repairs.size} exercises in plan '${plan.planName}'")
            repairs.forEach { r ->
                log.info("  Repaired: ${r.originalName}(${r.originalId}) -> ${r.replacedWithName}(${r.replacedWithId}) [${r.reason}]")
            }
        }

        return ValidatedPlan(
            plan = plan.copy(days = validatedDays),
            repairs = repairs
        )
    }

    /**
     * 하루 운동이 [MIN_EXERCISES_PER_DAY] 개 미만이거나, 제거가 일어나 [TARGET_EXERCISES_PER_DAY] 개 미만이 됐으면
     * [TARGET_EXERCISES_PER_DAY] 개까지 채운다. AI 가 의도적으로 3~4개로 짠 날은 건드리지 않는다.
     * 우선순위: 그날의 부위 → 코어 → 나머지 부위. 항상 [constraints] 와 중복 규칙을 지킨다.
     * 채워 넣는 운동의 세트/반복은 보수적 기본값이며, 이후 PlanQualityValidator.normalize 가 목표에 맞게 보정한다.
     */
    private fun fillSparseDay(
        kept: List<AIPlanExerciseEntry>,
        removedInDay: Int,
        workoutType: String,
        fallbackCategories: List<ExerciseCategory>,
        constraints: PlanConstraints,
        usedIds: MutableSet<Long>,
        usedPatterns: MutableSet<ExercisePatternClassifier.MovementPattern>,
        repairs: MutableList<RepairLog>
    ): List<AIPlanExerciseEntry> {
        val needsFill = kept.size < MIN_EXERCISES_PER_DAY || (removedInDay > 0 && kept.size < TARGET_EXERCISES_PER_DAY)
        if (!needsFill) return kept
        val result = kept.toMutableList()
        val searchOrder = (fallbackCategories + listOf(ExerciseCategory.CORE) + ALL_CATEGORIES).distinct()
        val candidates = searchOrder.asSequence()
            .flatMap { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(it).asSequence() }
        for (candidate in candidates) {
            if (result.size >= TARGET_EXERCISES_PER_DAY) break
            val pattern = exercisePatternClassifier.classifyExercise(candidate)
            if (candidate.id in usedIds || pattern in usedPatterns || !constraints.accepts(candidate)) continue
            usedIds.add(candidate.id); usedPatterns.add(pattern)
            result.add(
                AIPlanExerciseEntry(
                    exerciseId = candidate.id, exerciseName = candidate.name,
                    sets = 3, minReps = 10, maxReps = 12, restSeconds = 60,
                    order = result.size + 1, isCompound = false, targetRPE = 7.0,
                    notes = null
                )
            )
            repairs.add(RepairLog(-1, "", candidate.id, candidate.name, RepairReason.FILLED))
        }
        if (result.size < MIN_EXERCISES_PER_DAY) {
            log.warn("Day ($workoutType) still has only ${result.size} exercises after filling — no safe candidates left")
        } else if (result.size > kept.size) {
            log.info("Filled day ($workoutType) from ${kept.size} to ${result.size} exercises with constraint-safe alternatives")
        }
        return result
    }

    /** 해석 결과 — entry 는 항상 있고, exercise 는 카탈로그에서 확정된 경우에만 있다. */
    private data class Resolved(
        val entry: AIPlanExerciseEntry,
        val exercise: Exercise?
    )

    private fun resolveExercise(
        entry: AIPlanExerciseEntry,
        fallbackCategories: List<ExerciseCategory>,
        constraints: PlanConstraints,
        repairs: MutableList<RepairLog>
    ): Resolved {
        // Step 1: id 정확 일치 — 단, 프롬프트로 제시한 플랜 후보군에 속해야 인정한다.
        //         AI가 실재하지만 후보군 밖인 id를 지어내는 경우를 차단한다.
        val byId = exerciseRepository.findById(entry.exerciseId).orElse(null)
        if (byId != null && byId.isPlanEligible) {
            if (constraints.accepts(byId)) return Resolved(entry, byId)
            // 실재하지만 부상 제한/장비 불일치 → 같은 부위에서 조건에 맞는 운동으로 교체
            return constrainedFallback(entry, listOf(byId.category) + fallbackCategories, constraints, repairs,
                RepairReason.CONSTRAINT_REPLACED)
        }

        // Step 2: 이름 정확 일치 (후보군 한정)
        val exact = exerciseRepository.findByNameIgnoreCase(entry.exerciseName)
        if (exact != null && constraints.accepts(exact)) {
            return Resolved(replaced(entry, exact, repairs, RepairReason.ID_MISMATCH), exact)
        }

        // Step 3: 이름 부분 일치 (후보군 한정, 인기도 우선)
        val fuzzy = exerciseRepository.findPlanEligibleByNameContaining(entry.exerciseName)
            .firstOrNull { constraints.accepts(it) }
        if (fuzzy != null) {
            return Resolved(replaced(entry, fuzzy, repairs, RepairReason.HALLUCINATION_REPLACED), fuzzy)
        }

        // Step 4: 부위 폴백 — 후보군 안에서 조건을 만족하는 인기도 최상위
        return constrainedFallback(entry, fallbackCategories, constraints, repairs, RepairReason.FALLBACK_POPULAR)
    }

    private fun constrainedFallback(
        entry: AIPlanExerciseEntry,
        categories: List<ExerciseCategory>,
        constraints: PlanConstraints,
        repairs: MutableList<RepairLog>,
        reason: RepairReason
    ): Resolved {
        val fallback = categories.distinct()
            .asSequence()
            .flatMap { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(it).asSequence() }
            .firstOrNull { constraints.accepts(it) }
        if (fallback != null) {
            return Resolved(replaced(entry, fallback, repairs, reason), fallback)
        }
        // 조건을 만족하는 운동이 없다 — 호출자가 제거한다
        log.warn("No plan-eligible exercise satisfying constraints for: ${entry.exerciseName} (${entry.exerciseId})")
        return Resolved(entry, null)
    }

    /**
     * 같은 날에 이미 쓴 운동이거나 같은 동작 패턴이면 다른 운동으로 교체한다.
     * 교체 후보가 없으면 exercise = null 로 돌려 호출자가 제거하게 한다.
     */
    private fun dedupeWithinDay(
        resolved: Resolved,
        fallbackCategories: List<ExerciseCategory>,
        constraints: PlanConstraints,
        usedIds: Set<Long>,
        usedPatterns: Set<ExercisePatternClassifier.MovementPattern>,
        repairs: MutableList<RepairLog>
    ): Resolved {
        val current = resolved.exercise ?: return resolved
        val pattern = exercisePatternClassifier.classifyExercise(current)
        if (current.id !in usedIds && pattern !in usedPatterns) return resolved

        // 같은 부위를 우선 유지하되, 없으면 그날의 다른 부위에서 찾는다
        val searchOrder = (listOf(current.category) + fallbackCategories).distinct()
        val replacement = searchOrder
            .asSequence()
            .flatMap { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(it).asSequence() }
            .firstOrNull { candidate ->
                candidate.id !in usedIds &&
                    exercisePatternClassifier.classifyExercise(candidate) !in usedPatterns &&
                    constraints.accepts(candidate)
            }
        if (replacement == null) {
            log.warn("Could not dedupe ${current.name} (pattern=$pattern) — removing duplicate")
            return Resolved(resolved.entry, null)
        }

        return Resolved(
            replaced(resolved.entry, replacement, repairs, RepairReason.DUPLICATE_PATTERN),
            replacement
        )
    }

    private fun replaced(
        entry: AIPlanExerciseEntry,
        target: Exercise,
        repairs: MutableList<RepairLog>,
        reason: RepairReason
    ): AIPlanExerciseEntry {
        repairs.add(RepairLog(entry.exerciseId, entry.exerciseName, target.id, target.name, reason))
        return entry.copy(exerciseId = target.id, exerciseName = target.name)
    }

    /**
     * 폴백 시 시도할 부위 우선순위.
     * FULL_BODY 는 플랜 후보군이 없으므로 실제 부위 카테고리로만 체인을 구성한다.
     */
    private fun mapWorkoutTypeToCategories(workoutType: String): List<ExerciseCategory> {
        return when (workoutType.uppercase()) {
            "PUSH" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
            "PULL" -> listOf(ExerciseCategory.BACK, ExerciseCategory.ARMS)
            "LEGS", "LOWER" -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CORE)
            "UPPER" -> listOf(
                ExerciseCategory.CHEST, ExerciseCategory.BACK,
                ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS
            )
            "FULL_BODY" -> listOf(
                ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
                ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE
            )
            "CARDIO" -> listOf(ExerciseCategory.CARDIO, ExerciseCategory.LEGS)
            "CHEST" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS)
            "BACK" -> listOf(ExerciseCategory.BACK, ExerciseCategory.ARMS)
            "SHOULDERS" -> listOf(ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
            "ARMS" -> listOf(ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS)
            "CORE" -> listOf(ExerciseCategory.CORE, ExerciseCategory.LEGS)
            else -> listOf(
                ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
                ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE
            )
        }
    }

    /**
     * Public single-category mapping for external callers (e.g., AIPlanGenerationService fallback).
     * Returns the primary category for the given workout type.
     */
    fun mapWorkoutTypeToCategory(workoutType: String): ExerciseCategory {
        return mapWorkoutTypeToCategories(workoutType).first()
    }

    companion object {
        const val MIN_EXERCISES_PER_DAY = 3
        const val TARGET_EXERCISES_PER_DAY = 5
        private val ALL_CATEGORIES = listOf(
            ExerciseCategory.CORE, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.LEGS
        )
    }
}

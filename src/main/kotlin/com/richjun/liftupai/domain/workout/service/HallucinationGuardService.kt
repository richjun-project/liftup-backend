package com.richjun.liftupai.domain.workout.service

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

enum class RepairReason { ID_MISMATCH, HALLUCINATION_REPLACED, FALLBACK_POPULAR, DUPLICATE_PATTERN }

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

@Service
class HallucinationGuardService(
    private val exerciseRepository: ExerciseRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val log = LoggerFactory.getLogger(HallucinationGuardService::class.java)

    /**
     * AI가 반환한 플랜을 실제 카탈로그에 정합하도록 복구한다.
     *
     * 모든 복구 경로는 **플랜 후보군(isPlanEligible)** 안에서만 대체 운동을 고른다.
     * 과거에는 폴백이 `findByCategory(category).firstOrNull()` 였는데 ORDER BY 가 없어
     * id 최소값(= 이름 알파벳 최상위)이 뽑혔고, 그 결과 "3 Leg Dog Pose"(인기도 20)나
     * "4 Corners Curtsy" 같은 운동이 실제 사용자 플랜에 들어갔다.
     *
     * 또한 하루 안에서 같은 운동/같은 동작 패턴이 중복되지 않도록 보정한다.
     * (프로덕션에서 하루에 어시스트 풀업 6종, 45도 하이퍼익스텐션 3종이 배치된 사례가 있었다)
     */
    fun validateAndRepairPlan(plan: AIPlanResponse): ValidatedPlan {
        val repairs = mutableListOf<RepairLog>()

        val validatedDays = plan.days.map { day ->
            val fallbackCategories = mapWorkoutTypeToCategories(day.workoutType)
            val usedIds = mutableSetOf<Long>()
            val usedPatterns = mutableSetOf<ExercisePatternClassifier.MovementPattern>()

            val validatedExercises = day.exercises.map { entry ->
                val resolved = resolveExercise(entry, fallbackCategories, repairs)
                val deduped = dedupeWithinDay(resolved, fallbackCategories, usedIds, usedPatterns, repairs)
                deduped.exercise?.let {
                    usedIds.add(it.id)
                    usedPatterns.add(exercisePatternClassifier.classifyExercise(it))
                }
                deduped.entry
            }
            day.copy(exercises = validatedExercises)
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

    /** 해석 결과 — entry 는 항상 있고, exercise 는 카탈로그에서 확정된 경우에만 있다. */
    private data class Resolved(
        val entry: AIPlanExerciseEntry,
        val exercise: Exercise?
    )

    private fun resolveExercise(
        entry: AIPlanExerciseEntry,
        fallbackCategories: List<ExerciseCategory>,
        repairs: MutableList<RepairLog>
    ): Resolved {
        // Step 1: id 정확 일치 — 단, 프롬프트로 제시한 플랜 후보군에 속해야 인정한다.
        //         AI가 실재하지만 후보군 밖인 id를 지어내는 경우를 차단한다.
        val byId = exerciseRepository.findById(entry.exerciseId).orElse(null)
        if (byId != null && byId.isPlanEligible) return Resolved(entry, byId)

        // Step 2: 이름 정확 일치 (후보군 한정)
        val exact = exerciseRepository.findByNameIgnoreCase(entry.exerciseName)
        if (exact != null && exact.isPlanEligible) {
            return Resolved(replaced(entry, exact, repairs, RepairReason.ID_MISMATCH), exact)
        }

        // Step 3: 이름 부분 일치 (후보군 한정, 인기도 우선)
        val fuzzy = exerciseRepository.findPlanEligibleByNameContaining(entry.exerciseName).firstOrNull()
        if (fuzzy != null) {
            return Resolved(replaced(entry, fuzzy, repairs, RepairReason.HALLUCINATION_REPLACED), fuzzy)
        }

        // Step 4: 부위 폴백 — 후보군 안에서 인기도 최상위
        val fallback = fallbackCategories
            .asSequence()
            .mapNotNull { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(it).firstOrNull() }
            .firstOrNull()
        if (fallback != null) {
            return Resolved(replaced(entry, fallback, repairs, RepairReason.FALLBACK_POPULAR), fallback)
        }

        // 후보군이 비어 있는 비정상 상태 — 품질 검증기가 걸러낸다
        log.error("No plan-eligible fallback for exercise: ${entry.exerciseName} (${entry.exerciseId})")
        return Resolved(entry, null)
    }

    /**
     * 같은 날에 이미 쓴 운동이거나 같은 동작 패턴이면 다른 운동으로 교체한다.
     * 교체 후보가 없으면 원본을 유지하고 품질 검증기가 판단하게 한다.
     */
    private fun dedupeWithinDay(
        resolved: Resolved,
        fallbackCategories: List<ExerciseCategory>,
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
                    exercisePatternClassifier.classifyExercise(candidate) !in usedPatterns
            }
            ?: return resolved.also {
                log.warn("Could not dedupe ${current.name} (pattern=$pattern) — no distinct plan-eligible alternative")
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
}

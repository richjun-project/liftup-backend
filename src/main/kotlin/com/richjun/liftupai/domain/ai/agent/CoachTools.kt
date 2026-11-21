package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.JsonNode
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.service.NutritionContextService
import com.richjun.liftupai.domain.recovery.service.RecoveryService
import com.richjun.liftupai.domain.stats.service.StatsService
import com.richjun.liftupai.domain.user.dto.ProfileUpdateRequest
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogLocalizationService
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** 도구가 실행될 때 아는 것 — 누구의 대화인지, 어느 언어·시간대인지, 이번 메시지의 안전 판정. */
data class CoachToolContext(
    val user: User,
    val locale: String,
    val zoneId: ZoneId,
    val risk: ChatSafetyService.RiskProfile,
    /** 이번 답에서 search_exercises 를 부른 횟수 — 교체에서 검색만 반복하다 시간이 끝나던 것(실측 11-21)을 막는다 */
    val searchCalls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
)

/** 한 답에서 search_exercises 를 부를 수 있는 횟수 */
const val MAX_SEARCHES_PER_REPLY = 2

/** 모델이 지어내는 인자 이름(old_exercise_id 등 — 실측 11-21)도 받는다. 앞의 이름이 우선. */
internal fun JsonNode.firstPositiveLong(vararg names: String): Long? =
    names.asSequence().mapNotNull { n -> get(n)?.takeIf { !it.isNull }?.let { v -> if (v.isNumber) v.asLong() else v.asText().trim().toLongOrNull() } }
        .firstOrNull { it > 0 }

/**
 * AI PT 가 부를 수 있는 함수 하나. 결과는 모델에게 JSON 으로 돌려준다 — 숫자는 서버가 계산해 넣는다
 * (모델이 지어내지 않게). [mutating] 도구는 사용자의 앱 상태를 바꾼다(플랜·알림 시간·기억).
 */
interface CoachTool {
    val name: String
    val mutating: Boolean
    /** Gemini functionDeclarations 한 항목(name·description·parameters) */
    fun declaration(): Map<String, Any>
    fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?>
}

/** 도구를 이름으로 찾아 실행한다. 모르는 이름·잘못된 인자·예외는 모델이 읽을 수 있는 실패 결과로 바꾼다. */
@Component
class CoachToolbox(
    tools: List<CoachTool>,
    /** 플랜·설정을 바꾸는 도구의 전·후를 "코치가 한 일"로 남긴다(2026-11-14). 없으면 기록하지 않는다 — 단위 테스트 */
    private val recorder: CoachActionRecorder? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byName = tools.associateBy { it.name }

    data class Outcome(val name: String, val ok: Boolean, val changed: Boolean, val result: Map<String, Any?>, val elapsedMs: Long)

    fun declarations(): List<Map<String, Any>> = byName.values.sortedBy { it.name }.map { it.declaration() }

    fun execute(name: String, args: JsonNode, ctx: CoachToolContext): Outcome {
        val started = System.currentTimeMillis()
        val tool = byName[name]
            ?: return Outcome(name, false, false, mapOf("ok" to false, "error" to "unknown_tool"), 0)
        // 되돌릴 수 있게 바꾸기 전 상태를 잡는다 — 잡지 못해도 도구는 그대로 실행한다(기록만 빠진다)
        val rec = recorder
        val before = if (tool.mutating && rec != null && name in CoachActionService.UNDOABLE_TOOLS) {
            runCatching { rec.capture(ctx.user.id) }
                .onFailure { log.warn("[AI-AGENT] capture before {} failed user={}: {}", name, ctx.user.id, it.message) }.getOrNull()
        } else null
        return try {
            val result = tool.run(ctx, args)
            val ok = result["ok"] != false
            val changed = ok && result["changed"] == true
            if (changed && before != null) {
                recordWhenCommitted(ctx, name, before, result)
            }
            Outcome(name, ok, changed, result, System.currentTimeMillis() - started)
        } catch (e: ToolArgumentException) {
            Outcome(name, false, false, mapOf("ok" to false, "error" to "bad_arguments", "detail" to e.message), System.currentTimeMillis() - started)
        } catch (e: Exception) {
            log.warn("[AI-AGENT] tool {} failed user={}: {}", name, ctx.user.id, e.message)
            Outcome(name, false, false, mapOf("ok" to false, "error" to "tool_failed", "detail" to (e.message ?: e.javaClass.simpleName).take(200)), System.currentTimeMillis() - started)
        }
    }

    /**
     * 도구가 바깥 트랜잭션 안에서 돌았으면(채팅 AIAnalysisService.chat 은 클래스 전체가 @Transactional) 그 커밋 뒤에 기록한다.
     * 따로 커밋하는 기록이 커밋 전 변경을 못 봐 '바뀐 뒤' 플랜을 옛 플랜으로 적었다(2026-11-14 운영 스모크: 되돌리기 changed_since).
     * 커밋 뒤 콜백에서도 recorder(스프링 프록시)를 거쳐 불러야 REQUIRES_NEW 가 걸려 저장된다. 롤백되면 기록하지 않는다.
     */
    private fun recordWhenCommitted(ctx: CoachToolContext, name: String, before: CoachActionService.State, result: Map<String, Any?>) {
        val rec = recorder ?: return
        fun record() = runCatching { rec.record(ctx.user, name, before, result, ctx.locale) }
            .onFailure { log.warn("[AI-AGENT] coach action record failed user={} tool={}: {}", ctx.user.id, name, it.message) }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object : org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCommit() { record() }
                },
            )
        } else {
            record()
        }
    }
}

class ToolArgumentException(message: String) : RuntimeException(message)

// ── 공통 ────────────────────────────────────────────────────────────────────────────────────

private fun obj(properties: Map<String, Any>, required: List<String> = emptyList()): Map<String, Any> =
    buildMap {
        put("type", "OBJECT")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", required)
    }

private fun prop(type: String, description: String, enum: List<String>? = null): Map<String, Any> =
    buildMap {
        put("type", type)
        put("description", description)
        if (enum != null) put("enum", enum)
    }

private fun arrProp(description: String, itemEnum: List<String>): Map<String, Any> =
    mapOf("type" to "ARRAY", "description" to description, "items" to mapOf("type" to "STRING", "enum" to itemEnum))

private fun JsonNode.intArg(name: String, default: Int, range: IntRange): Int {
    val v = get(name)
    if (v == null || v.isNull) return default
    val n = if (v.isNumber) v.asInt() else v.asText().trim().toIntOrNull() ?: throw ToolArgumentException("$name must be a number")
    return n.coerceIn(range)
}

private fun JsonNode.textArg(name: String, maxLength: Int = 200): String? =
    get(name)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)

private fun JsonNode.requireText(name: String, maxLength: Int = 200): String =
    textArg(name, maxLength) ?: throw ToolArgumentException("$name is required")

internal fun kg(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ROOT, v)

/** Epley 추정 1RM (1회면 그대로). 12회 넘는 세트는 추정이 틀어지므로 null */
internal fun estimatedOneRm(weight: Double, reps: Int): Double? = when {
    weight <= 0 || reps <= 0 -> null
    reps == 1 -> weight
    reps > 12 -> null
    else -> weight * (1 + reps / 30.0)
}

private fun dayLabel(date: java.time.LocalDate, locale: String): String =
    "$date (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(locale))})"

// ── 읽기 도구 ───────────────────────────────────────────────────────────────────────────────

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class RecentWorkoutsTool(
    private val sessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val setRepository: ExerciseSetRepository,
    private val localization: ExerciseCatalogLocalizationService,
) : CoachTool {
    override val name = "get_recent_workouts"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "The user's recent finished workout sessions with every exercise and the sets they actually completed (weight x reps). " +
            "Use for questions about what they did, how a session went, consistency, or before giving feedback on their training.",
        "parameters" to obj(mapOf(
            "days" to prop("INTEGER", "How many days back to look (1-90). Default 14."),
            "limit" to prop("INTEGER", "Max sessions to return (1-10). Default 6."),
        )),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val days = args.intArg("days", 14, 1..90)
        val limit = args.intArg("limit", 6, 1..10)
        val sessions = sessionRepository.findByUserAndStartTimeAfter(ctx.user, AppTime.utcNow().minusDays(days.toLong()))
            .filter { it.status == SessionStatus.COMPLETED }
            .sortedByDescending { it.startTime }
            .take(limit)
        if (sessions.isEmpty()) return mapOf("ok" to true, "days" to days, "sessions" to emptyList<Any>(), "note" to "no finished workouts in this period")
        val exercises = workoutExerciseRepository.findBySessionIdInWithExercise(sessions.map { it.id })
        val sets = setRepository.findByWorkoutExerciseIdIn(exercises.map { it.id }).groupBy { it.workoutExercise.id }
        val names = localization.translationMap(exercises.map { it.exercise }.distinctBy { it.id }, ctx.locale)
        val bySession = exercises.groupBy { it.session.id }
        return mapOf(
            "ok" to true,
            "days" to days,
            "unit" to "kg",
            "sessions" to sessions.map { s ->
                val date = AppTime.toUserLocalDate(s.startTime, ctx.zoneId)
                mapOf(
                    "date" to dayLabel(date, ctx.locale),
                    "days_ago" to java.time.temporal.ChronoUnit.DAYS.between(date, AppTime.toUserLocalDate(AppTime.utcNow(), ctx.zoneId)),
                    "minutes" to s.duration,
                    "total_volume_kg" to s.totalVolume?.roundToInt(),
                    "exercises" to bySession[s.id].orEmpty().sortedBy { it.orderInSession }.mapNotNull { we ->
                        val done = sets[we.id].orEmpty().filter { it.completed }.sortedBy { it.setNumber }
                        if (done.isEmpty()) return@mapNotNull null
                        mapOf(
                            "name" to localization.displayName(we.exercise, ctx.locale, names),
                            "sets" to done.take(8).joinToString(", ") { if (it.weight > 0) "${kg(it.weight)}x${it.reps}" else "${it.reps} reps" },
                        )
                    },
                )
            },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class ExerciseHistoryTool(
    private val searchIndex: ExerciseSearchIndexService,
    private val exerciseRepository: ExerciseRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val setRepository: ExerciseSetRepository,
    private val localization: ExerciseCatalogLocalizationService,
) : CoachTool {
    override val name = "get_exercise_history"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "History of ONE exercise for this user over the last 6 months: per session the best set and estimated 1RM, plus their best ever. " +
            "Use for 'how is my bench going', 'what weight should I do for squats next', plateau or PR questions.",
        "parameters" to obj(mapOf(
            "exercise" to prop("STRING", "Exercise name in any language, e.g. 'bench press', '스쿼트', 'デッドリフト'."),
        ), listOf("exercise")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val query = args.requireText("exercise", 80)
        val candidates = searchIndex.engine().search(query, locale = ctx.locale).take(8).map { it.doc.id }
        if (candidates.isEmpty()) return mapOf("ok" to true, "found" to false, "note" to "no exercise matches '$query'")
        // 이름이 비슷한 후보 중 실제로 해 본 운동을 고른다(검색 1위가 처음 보는 변형일 수 있다)
        val performed = workoutExerciseRepository.findRecentExerciseIds(ctx.user.id, org.springframework.data.domain.PageRequest.of(0, 200)).toSet()
        val exerciseId = candidates.firstOrNull { it in performed } ?: candidates.first()
        val exercise = exerciseRepository.findById(exerciseId).orElse(null)
            ?: return mapOf("ok" to true, "found" to false)
        val names = localization.translationMap(listOf(exercise), ctx.locale)
        val display = localization.displayName(exercise, ctx.locale, names)
        val sets = setRepository.findCompletedSetsByUserAndExercise(ctx.user.id, exerciseId, AppTime.utcNow().minusDays(180))
        if (sets.isEmpty()) return mapOf("ok" to true, "found" to true, "exercise" to display, "sessions" to emptyList<Any>(), "note" to "they have no completed sets of this exercise in the last 6 months")
        val bySession = sets.groupBy { it.workoutExercise.session }
            .entries.sortedByDescending { it.key.startTime }
        val sessions = bySession.take(8).map { (session, s) ->
            val best = s.maxWith(compareBy({ it.weight }, { it.reps }))
            mapOf(
                "date" to dayLabel(AppTime.toUserLocalDate(session.startTime, ctx.zoneId), ctx.locale),
                "sets_done" to s.size,
                "best_set" to if (best.weight > 0) "${kg(best.weight)}kg x ${best.reps}" else "${best.reps} reps",
                "est_1rm_kg" to estimatedOneRm(best.weight, best.reps)?.let { kg((it * 2).roundToInt() / 2.0) },
            )
        }
        val top = sets.maxWith(compareBy({ it.weight }, { it.reps }))
        val bestE1rm = sets.mapNotNull { estimatedOneRm(it.weight, it.reps) }.maxOrNull()
        return mapOf(
            "ok" to true,
            "found" to true,
            "exercise" to display,
            "exercise_id" to exerciseId,
            "sessions_in_6_months" to bySession.size,
            "recent_sessions" to sessions,
            "heaviest_set" to "${kg(top.weight)}kg x ${top.reps} (${AppTime.toUserLocalDate(top.workoutExercise.session.startTime, ctx.zoneId)})",
            "best_est_1rm_kg" to bestE1rm?.let { kg((it * 2).roundToInt() / 2.0) },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class TodayWorkoutTool(
    private val userPlanService: UserPlanService,
    private val exerciseRepository: ExerciseRepository,
    private val localization: ExerciseCatalogLocalizationService,
    private val trainingContext: com.richjun.liftupai.domain.workout.service.UserTrainingContextResolver,
) : CoachTool {
    override val name = "get_today_workout"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Today's session from the user's active plan: each exercise with sets, rep range, suggested weight and rest. " +
            "Bodyweight exercises also carry harder_variant / easier_variant (the next step of the progression they can do with their equipment). " +
            "Use when they ask what to do today or want changes to today's session.",
        "parameters" to obj(emptyMap()),
    )

    /** 맨몸 변형 사다리의 한 단계 위·아래(그 사람의 도구로 할 수 있는 것만). 사다리에 없는 운동이면 빈 맵 */
    private fun ladderHints(exerciseIds: List<Long>, ctx: CoachToolContext): Map<Long, Map<String, Any?>> {
        val exercises = exerciseRepository.findAllById(exerciseIds).filter { com.richjun.liftupai.domain.workout.util.BodyweightLadder.ladderOf(it.slug) != null }
        if (exercises.isEmpty()) return emptyMap()
        val equipment = runCatching { trainingContext.resolve(ctx.user.id).equipment }.getOrDefault(emptySet())
        val bySlug = HashMap<String, com.richjun.liftupai.domain.workout.entity.Exercise?>()
        fun load(slug: String) = bySlug.getOrPut(slug) { exerciseRepository.findBySlug(slug) }
        fun canDo(slug: String): Boolean {
            val ex = load(slug) ?: return false
            return ex.isPlanEligible && com.richjun.liftupai.domain.workout.util.ExerciseRequirements.allows(ex, equipment)
        }
        val variants = exercises.associate { ex ->
            ex.id to listOf(
                "harder_variant" to com.richjun.liftupai.domain.workout.util.BodyweightLadder.harder(ex.slug, ::canDo)?.let(::load),
                "easier_variant" to com.richjun.liftupai.domain.workout.util.BodyweightLadder.easier(ex.slug, ::canDo)?.let(::load),
            )
        }
        val names = localization.translationMap(variants.values.flatMap { v -> v.mapNotNull { it.second } }, ctx.locale)
        return variants.mapValues { (_, v) ->
            v.filter { it.second != null }.associate { (key, ex) -> key to mapOf("exercise_id" to ex!!.id, "name" to localization.displayName(ex, ctx.locale, names)) }
        }
    }

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val plan = userPlanService.getActivePlan(ctx.user.id)
            ?: return mapOf("ok" to true, "has_plan" to false, "note" to "no active plan — suggest choosing one (OPEN_PLAN)")
        val day = userPlanService.getDayWorkout(ctx.user.id, plan.currentDay, locale = ctx.locale)
        val ladder = runCatching { ladderHints(day.exercises.map { it.exerciseId }, ctx) }
            .onFailure { LoggerFactory.getLogger(javaClass).warn("[AI-AGENT] ladder hints failed user={}: {}", ctx.user.id, it.message) }
            .getOrDefault(emptyMap())
        return mapOf(
            "ok" to true,
            "has_plan" to true,
            "plan" to plan.planName,
            "day" to day.dayNumber,
            "day_name" to day.dayName,
            "estimated_minutes" to day.estimatedDuration,
            "deload_week" to day.isDeloadWeek,
            "exercises" to day.exercises.map { e ->
                mapOf(
                    "exercise_id" to e.exerciseId,
                    "name" to e.exerciseName,
                    "sets" to e.sets,
                    "reps" to if (e.minReps == e.maxReps) "${e.minReps}" else "${e.minReps}-${e.maxReps}",
                    "suggested_kg" to e.suggestedWeight?.takeIf { it > 0 }?.let { kg(it) },
                    "rest_seconds" to e.restSeconds,
                ) + ladder[e.exerciseId].orEmpty()
            },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class PlanOverviewTool(private val userPlanService: UserPlanService) : CoachTool {
    override val name = "get_plan_overview"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "The user's whole active workout plan: name, split, every day and how many times each day was completed.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        if (userPlanService.getActivePlan(ctx.user.id) == null) return mapOf("ok" to true, "has_plan" to false)
        val d = userPlanService.getPlanDashboard(ctx.user.id, ctx.locale)
        return mapOf(
            "ok" to true,
            "has_plan" to true,
            "plan" to d.planName,
            "split" to d.splitType,
            "current_day" to d.currentDay,
            "total_days" to d.totalDays,
            "workouts_completed_in_plan" to d.totalWorkoutsCompleted,
            "days" to d.days.map { mapOf("day" to it.dayNumber, "name" to it.dayName, "minutes" to it.estimatedDuration) },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class RecoveryTool(private val recoveryService: RecoveryService) : CoachTool {
    override val name = "get_recovery_status"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Estimated recovery % of each muscle group from their recent training. Use for 'can I train legs today', soreness, or which muscles are fresh.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val status = recoveryService.getRecoveryStatus(ctx.user.id, ctx.locale)
        return mapOf(
            "ok" to true,
            "muscles" to status.muscles.sortedBy { it.recoveryPercentage }.map {
                mapOf("muscle" to it.name, "recovery_pct" to it.recoveryPercentage, "last_worked" to it.lastWorked, "status" to it.status)
            },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class NutritionTodayTool(private val nutritionContextService: NutritionContextService) : CoachTool {
    override val name = "get_nutrition_today"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "What the user logged eating today (meals) and, when appropriate, calories and macros vs their targets.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val c = nutritionContextService.buildTodayContext(ctx.user)
        val meals = c.mealsByType.mapKeys { it.key.name.lowercase() }
            .mapValues { (_, logs) -> logs.joinToString(" / ") { it.foods.take(80) } }
            .filterValues { it.isNotBlank() }
        // 취약 사용자(미성년·저체중·섭식장애 신호)에게는 목표·섭취 칼로리 숫자를 주지 않는다 — 단발 경로와 같은 규칙
        if (ctx.risk.noIntakeNumbers) return mapOf("ok" to true, "meals_logged" to meals, "numbers" to "withheld for safety")
        return mapOf(
            "ok" to true,
            "meals_logged" to meals,
            "consumed_kcal" to c.consumedKcal,
            "target_kcal" to c.targetKcal,
            "remaining_kcal" to c.remainingKcal,
            "protein_g" to "${c.consumedMacros.protein}/${c.targetMacros.protein}",
            "carbs_g" to "${c.consumedMacros.carbs}/${c.targetMacros.carbs}",
            "fat_g" to "${c.consumedMacros.fat}/${c.targetMacros.fat}",
            "workout_burned_kcal" to c.workoutBurnedKcal,
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class ProgressStatsTool(private val statsService: StatsService) : CoachTool {
    override val name = "get_progress_stats"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Training totals for the last week or month (workouts, minutes, volume, current streak) and their personal records.",
        "parameters" to obj(mapOf(
            "period" to prop("STRING", "week or month. Default week.", listOf("week", "month")),
        )),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val period = args.textArg("period")?.lowercase()?.takeIf { it in setOf("week", "month") } ?: "week"
        val o = statsService.getOverview(ctx.user.id, period)
        val prs = statsService.getPersonalRecords(ctx.user.id).records.take(8)
        return mapOf(
            "ok" to true,
            "period" to period,
            "workouts" to o.totalWorkouts,
            "total_minutes" to o.totalDuration,
            "total_volume_kg" to o.totalVolume.roundToInt(),
            "streak_days" to o.streak,
            "personal_records" to prs.map { "${it.exerciseName}: ${kg(it.weight)}kg x ${it.reps} (${it.date.take(10)})" },
        )
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class SearchExercisesTool(
    private val searchIndex: ExerciseSearchIndexService,
) : CoachTool {
    override val name = "search_exercises"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Search the exercise catalog (any language, names or intents like 'knee-friendly leg exercise', 'dumbbell chest'). " +
            "Returns exercise_id values — use one with swap_exercise_in_plan.",
        "parameters" to obj(mapOf(
            "query" to prop("STRING", "What to look for."),
        ), listOf("query")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        if (ctx.searchCalls.incrementAndGet() > MAX_SEARCHES_PER_REPLY) {
            return mapOf(
                "ok" to false, "error" to "search_limit",
                "detail" to "You already searched $MAX_SEARCHES_PER_REPLY times in this reply. Do not search again. Pick the best fitting exercise " +
                    "from the earlier results and call swap_exercise_in_plan, or name 2-3 of those options to the user and ask which one they want.",
            )
        }
        val query = args.requireText("query", 80)
        val hits = searchIndex.engine().search(query, locale = ctx.locale).take(6)
        return mapOf(
            "ok" to true,
            "results" to hits.map { h ->
                val d = h.doc
                mapOf(
                    "exercise_id" to d.id,
                    "name" to (d.names[ctx.locale] ?: d.names["en"] ?: d.slug),
                    "equipment" to d.equipment?.name?.lowercase(),
                    "muscles" to d.muscleGroups.map { it.name.lowercase() },
                )
            },
        )
    }
}

// ── 행동 도구 ───────────────────────────────────────────────────────────────────────────────

@Component
class SwapExerciseTool(
    private val userPlanService: UserPlanService,
    private val exerciseRepository: ExerciseRepository,
    private val localization: ExerciseCatalogLocalizationService,
) : CoachTool {
    override val name = "swap_exercise_in_plan"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S PLAN: replaces one exercise of their active plan with another (every day it appears). " +
            "Only when the user asked for this swap or agreed to your proposal. Get from_exercise_id from get_today_workout or " +
            "get_plan_overview and to_exercise_id from search_exercises. The server refuses swaps that clash with their injuries, equipment or level.",
        "parameters" to obj(mapOf(
            "from_exercise_id" to prop("INTEGER", "exercise_id currently in the plan"),
            "to_exercise_id" to prop("INTEGER", "exercise_id to put in its place"),
        ), listOf("from_exercise_id", "to_exercise_id")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val from = args.firstPositiveLong("from_exercise_id", "old_exercise_id", "current_exercise_id", "exercise_id", "from")
            ?: throw ToolArgumentException("from_exercise_id is required")
        val to = args.firstPositiveLong("to_exercise_id", "new_exercise_id", "replacement_exercise_id", "target_exercise_id", "to")
            ?: throw ToolArgumentException("to_exercise_id is required")
        val exercises = exerciseRepository.findAllById(listOf(from, to)).associateBy { it.id }
        val names = localization.translationMap(exercises.values, ctx.locale)
        fun nameOf(id: Long) = exercises[id]?.let { localization.displayName(it, ctx.locale, names) } ?: "#$id"
        // 같은 날에 대체 운동이 이미 있으면 서비스는 그날을 건너뛴다 — 오늘 세션이 실제로 바뀌었는지 따로 알려 준다
        fun todayIds(): Set<Long> = try {
            userPlanService.getActivePlan(ctx.user.id)?.let { plan ->
                userPlanService.getDayWorkout(ctx.user.id, plan.currentDay, locale = ctx.locale).exercises.map { it.exerciseId }.toSet()
            }.orEmpty()
        } catch (e: Exception) { emptySet() }
        val before = todayIds()
        return try {
            val replaced = userPlanService.substituteExerciseInActivePlan(ctx.user.id, from, to)
            val after = todayIds()
            val todayChanged = from in before && from !in after && to in after
            buildMap {
                put("ok", true); put("changed", true); put("replaced_rows", replaced)
                put("from", nameOf(from)); put("to", nameOf(to)); put("today_session_changed", todayChanged)
                if (from in before && !todayChanged) put("note", "today's session already had ${nameOf(to)}, so today's session was left as is; other days were changed")
            }
        } catch (e: com.richjun.liftupai.global.exception.BadRequestException) {
            mapOf("ok" to false, "error" to "refused", "detail" to e.message)
        } catch (e: com.richjun.liftupai.global.exception.ResourceNotFoundException) {
            mapOf("ok" to false, "error" to "no_active_plan")
        }
    }
}

/**
 * 오늘 할 플랜 Day 를 바꾼다(앱의 PUT /plans/active/current-day 와 같은 동작 — 저장된 플랜 내용은 그대로).
 * 2026-11-13: 일본 상급 사용자(296)가 "오늘 풀 데이"라고 두 번 말했는데 트레이너가 "오늘은 푸시"라며 반박하고
 * '플랜 설정에서 바꾸라'고만 했다 — 순서는 사용자가 정하는 것이고, 말하면 바뀌어야 한다.
 */
@Component
class SetTodayWorkoutDayTool(private val userPlanService: UserPlanService) : CoachTool {
    override val name = "set_today_workout_day"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S PLAN PROGRESS: makes another day of their active plan today's workout (e.g. Pull instead of Push). " +
            "The plan's content is not changed; after they finish it the plan continues from that day. " +
            "Use when the user says they want to (or will) train a different day of their plan today. Get day numbers from get_plan_overview.",
        "parameters" to obj(mapOf(
            "day" to prop("INTEGER", "day number from get_plan_overview"),
        ), listOf("day")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val plan = userPlanService.getActivePlan(ctx.user.id)
            ?: return mapOf("ok" to false, "error" to "no_active_plan", "detail" to "suggest choosing a plan (OPEN_PLAN)")
        val day = args.intArg("day", 0, 0..plan.totalDays.coerceAtLeast(1) + 1)
        if (day !in 1..plan.totalDays) throw ToolArgumentException("day must be between 1 and ${plan.totalDays}")
        val previous = plan.currentDay
        if (day != previous) {
            userPlanService.setCurrentDay(ctx.user.id, com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest(day))
        }
        val today = userPlanService.getDayWorkout(ctx.user.id, day, locale = ctx.locale)
        return buildMap {
            put("ok", true)
            put("changed", day != previous)
            put("previous_day", previous)
            put("day", today.dayNumber)
            put("day_name", today.dayName)
            put("estimated_minutes", today.estimatedDuration)
            put("exercises", today.exercises.map { it.exerciseName })
            if (day == previous) put("note", "that day was already today's workout")
        }
    }
}

/**
 * 보유 장비를 바꾸고 활성 플랜을 그 장비에 맞춘다(이제 없는 장비가 필요한 종목만 같은 부위의 할 수 있는 종목으로).
 * 앱 프로필의 장비 저장과 같은 경로(updateProfileV4) + UserPlanService.refitActivePlanToEquipment.
 * 2026-11-13: 일본 실가입 304 가 "器具はありません"이라고 했는데 트레이너가 '플랜 설정에서 새 플랜을 받으라'고만 했고,
 * 프로필에서 장비를 바꿔도 플랜(덤벨·머신·케이블)은 그대로였다.
 */
@Component
class SetEquipmentTool(
    private val userService: UserService,
    private val userPlanService: UserPlanService,
    private val equipmentPlanFit: com.richjun.liftupai.domain.workout.service.EquipmentPlanFitService,
) : CoachTool {
    override val name = "set_available_equipment"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S SETTINGS AND PLAN: saves the equipment they can actually use and fits their active plan to it — " +
            "exercises that need equipment they no longer have are replaced with ones they can do (same body part); the plan and its days stay. " +
            "Use when the user tells you what they can train with (\"I have no equipment\", \"only dumbbells at home\", \"I joined a gym\"). " +
            "Pass the FULL list they can use now. BODYWEIGHT means no equipment. A full gym = all items.",
        "parameters" to obj(mapOf(
            "equipment" to arrProp(
                "Everything they can use now. OTHER = pull-up bar. No equipment at all = [\"BODYWEIGHT\"].",
                EQUIPMENT_VALUES,
            ),
        ), listOf("equipment")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val node = args.get("equipment")
        val raw = when {
            node == null || node.isNull -> emptyList()
            node.isArray -> node.map { it.asText() }
            else -> node.asText().split(',', ' ')
        }
        val requested = raw.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val unknown = requested.filter { it !in EQUIPMENT_VALUES }
        if (unknown.isNotEmpty()) throw ToolArgumentException("unknown equipment: ${unknown.joinToString()} (use ${EQUIPMENT_VALUES.joinToString()})")
        // 맨몸은 언제나 할 수 있다 — 목록이 비면 서버가 '제한 없음'으로 읽으므로 항상 넣어 둔다
        val equipment = (requested + "BODYWEIGHT").distinct()
        val before = userPlanService.currentEquipment(ctx.user.id)
        userService.updateProfileV4(ctx.user.id, ProfileUpdateRequest(availableEquipment = equipment))
        val after = userPlanService.currentEquipment(ctx.user.id)
        val fit = equipmentPlanFit.fitToCurrentEquipment(ctx.user, ctx.locale)
        return buildMap {
            put("ok", true)
            put("changed", before != after || fit.planChanged)
            put("equipment", equipment)
            if (fit.mode == com.richjun.liftupai.domain.workout.service.EquipmentPlanFitService.Mode.NO_PLAN) {
                put("plan", "no active plan")
            } else {
                when (fit.mode) {
                    com.richjun.liftupai.domain.workout.service.EquipmentPlanFitService.Mode.NEW_TEMPLATE ->
                        put("plan", "most of their old plan needed equipment they don't have, so it was replaced by a plan built for this equipment (starts at day 1)")
                    com.richjun.liftupai.domain.workout.service.EquipmentPlanFitService.Mode.REFIT -> {
                        put("plan", "exercises that need equipment they don't have were replaced; same plan and day")
                        put("plan_days_changed", fit.daysChanged)
                    }
                    else -> put("plan", "their plan already fits this equipment — nothing in it had to change")
                }
                put("exercises_replaced", fit.exercisesReplaced)
                val plan = userPlanService.getActivePlan(ctx.user.id)
                if (plan != null) {
                    put("plan_name", plan.planName)
                    runCatching { userPlanService.getDayWorkout(ctx.user.id, plan.currentDay, locale = ctx.locale) }.getOrNull()?.let { today ->
                        put("today", mapOf(
                            "day" to today.dayNumber, "day_name" to today.dayName,
                            "estimated_minutes" to today.estimatedDuration,
                            "exercises" to today.exercises.map { it.exerciseName },
                        ))
                    }
                }
            }
        }
    }

    companion object {
        val EQUIPMENT_VALUES = com.richjun.liftupai.domain.workout.entity.Equipment.values().map { it.name }
    }
}

/**
 * 트레이너가 먼저 보내는 메시지(PT 체크인·첫 운동 알림 같은 AI_MESSAGE 푸시)를 끄고 켠다(2026-11-14).
 * 앱에는 알림 설정 화면이 없어 끄는 방법이 OS 알림 전체 끄기뿐이었다 — 그러면 운동 알림까지 사라진다.
 * PT 스케줄러가 읽는 NotificationSettings.aiMessages 를 기존 설정 서비스로 바꾼다(UserSettings 쪽이 아니다).
 */
@Component
class CoachMessagesTool(private val notificationService: com.richjun.liftupai.domain.notification.service.NotificationService) : CoachTool {
    override val name = "set_coach_messages"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S SETTINGS: turns the trainer's own check-in messages (push check-ins you send first) off or on. " +
            "Workout reminders they set themselves are not affected. Only when they ask you to stop or resume messaging them.",
        "parameters" to obj(mapOf(
            "enabled" to prop("BOOLEAN", "false to stop the trainer's check-in messages, true to resume them"),
        ), listOf("enabled")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val node = args.get("enabled") ?: throw ToolArgumentException("enabled is required")
        if (!node.isBoolean) throw ToolArgumentException("enabled must be true or false")
        val enabled = node.asBoolean()
        val before = notificationService.getNotificationSettings(ctx.user.id).aiMessages
        if (before != enabled) {
            notificationService.updateNotificationSettings(
                ctx.user.id,
                com.richjun.liftupai.domain.notification.dto.UpdateNotificationSettingsRequest(aiMessages = enabled),
            )
        }
        return mapOf("ok" to true, "changed" to (before != enabled), "coach_messages" to if (enabled) "on" else "off")
    }
}

@Component
class WorkoutTimeTool(private val userService: UserService) : CoachTool {
    override val name = "set_workout_time"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S SETTINGS: sets the time of day they usually train (local time). Their workout reminder moves to 30 minutes before. " +
            "Only when they ask to change their workout/reminder time.",
        "parameters" to obj(mapOf(
            "time" to prop("STRING", "24-hour local time HH:mm, e.g. 07:30 or 19:00"),
        ), listOf("time")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val raw = args.requireText("time", 10)
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(raw) ?: throw ToolArgumentException("time must be HH:mm")
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) throw ToolArgumentException("time out of range")
        val time = "%02d:%02d".format(h, min)
        userService.updateProfileV4(ctx.user.id, ProfileUpdateRequest(preferredWorkoutTime = time))
        val reminder = java.time.LocalTime.of(h, min).minusMinutes(30)
        return mapOf("ok" to true, "changed" to true, "workout_time" to time, "reminder_at" to reminder.format(DateTimeFormatter.ofPattern("HH:mm")))
    }
}

@Component
class RememberTool(private val memoryRepository: CoachMemoryRepository) : CoachTool {
    override val name = "remember"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Save one durable fact about the user for future conversations (schedule, home equipment, preferences, injury/health history, life context, goal details). " +
            "Short third-person note in the user's language. Not for one-off moods, today's workout details, or anything about other people.",
        "parameters" to obj(mapOf(
            "fact" to prop("STRING", "The fact, max 150 characters, e.g. '주말에는 운동할 시간이 없다'"),
            "category" to prop("STRING", "Kind of fact", CoachMemory.CATEGORIES.toList()),
        ), listOf("fact", "category")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val fact = args.requireText("fact", 150)
        val category = args.textArg("category")?.lowercase()?.takeIf { it in CoachMemory.CATEGORIES } ?: "other"
        val existing = memoryRepository.findByUserIdOrderByUpdatedAtDesc(ctx.user.id)
        existing.firstOrNull { it.content.equals(fact, ignoreCase = true) }?.let {
            it.updatedAt = AppTime.utcNow()
            memoryRepository.save(it)
            return mapOf("ok" to true, "changed" to false, "memory_id" to it.id, "note" to "already remembered")
        }
        // 오래된 것부터 덜어 상한을 지킨다
        existing.drop(CoachMemory.MAX_PER_USER - 1).forEach { memoryRepository.delete(it) }
        val saved = memoryRepository.save(CoachMemory(userId = ctx.user.id, category = category, content = fact, createdAt = AppTime.utcNow(), updatedAt = AppTime.utcNow()))
        return mapOf("ok" to true, "changed" to true, "memory_id" to saved.id)
    }
}

@Component
class ForgetTool(private val memoryRepository: CoachMemoryRepository) : CoachTool {
    override val name = "forget"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Delete one remembered fact (by memory_id from WHAT YOU REMEMBER) when the user says it is wrong, outdated, or asks you to forget it.",
        "parameters" to obj(mapOf(
            "memory_id" to prop("INTEGER", "id of the remembered fact"),
        ), listOf("memory_id")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val id = args.get("memory_id")?.asLong()?.takeIf { it > 0 } ?: throw ToolArgumentException("memory_id is required")
        val memory = memoryRepository.findByIdAndUserId(id, ctx.user.id)
            ?: return mapOf("ok" to false, "error" to "not_found")
        memoryRepository.delete(memory)
        return mapOf("ok" to true, "changed" to true, "forgotten" to memory.content)
    }
}

// ── 채팅으로 다 되게(2026-11-21 대표 "우리 기능은 챗으로 안 되는 게 없게, 에이전트 서비스답게") ─────────────────────
// 기능 목록과 도구 대응표: growth/coach/FEATURES.md. 새 행동 도구는 앱 refreshAfterCoachChanges·칩 라벨도 같이 바꾼다.

private fun todayNutrition(nutritionContextService: NutritionContextService, ctx: CoachToolContext): Map<String, Any?> =
    runCatching { NutritionTodayTool(nutritionContextService).run(ctx, com.fasterxml.jackson.databind.node.NullNode.instance) }
        .getOrElse { mapOf("ok" to false) }.filterKeys { it != "ok" }

@Component
class LogMealTool(
    private val nutritionService: com.richjun.liftupai.domain.nutrition.service.NutritionService,
    private val nutritionContextService: NutritionContextService,
) : CoachTool {
    override val name = "log_meal"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "SAVES A MEAL to the user's food log from what they told you they ate (\"점심에 김치찌개랑 밥 한 공기\", \"I had two eggs and toast\", " +
            "\"お昼はラーメン\"). The server estimates calories and macros and returns them with today's totals. Telling you what they ate IS the request — " +
            "do not send them to the meal screen. Not for food they only plan to eat. One call per meal. Photos are analysed separately — do not call this for a photo.",
        "parameters" to obj(mapOf(
            "description" to prop("STRING", "What they ate, with amounts in their own words."),
            "meal_type" to prop("STRING", "Leave out to use the time of day.", listOf("breakfast", "lunch", "dinner", "snack")),
        ), listOf("description")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val description = args.requireText("description", 400)
        val type = args.get("meal_type")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        val meal = nutritionService.logMealFromText(ctx.user.id, description, type)
            ?: return mapOf("ok" to false, "error" to "could_not_estimate", "detail" to "Could not estimate this meal. Ask what and how much they ate.")
        return mapOf(
            "ok" to true, "changed" to true, "meal_log_id" to meal.mealLogId, "meal_type" to meal.mealType.lowercase(),
            "meal" to meal.name, "items" to meal.items, "portion" to meal.portion,
            "calories_kcal" to meal.calories, "protein_g" to meal.protein.roundToInt(), "carbs_g" to meal.carbs.roundToInt(), "fat_g" to meal.fat.roundToInt(),
            "today" to todayNutrition(nutritionContextService, ctx),
            "note" to "Numbers are estimates. If they correct the amount, call delete_meal_log with this meal_log_id, then log_meal again.",
        )
    }
}

@Component
class DeleteMealLogTool(
    private val nutritionService: com.richjun.liftupai.domain.nutrition.service.NutritionService,
    private val nutritionContextService: NutritionContextService,
) : CoachTool {
    override val name = "delete_meal_log"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "REMOVES ONE MEAL from the user's food log — to fix a wrong entry or when they say they did not eat it. " +
            "Pass meal_log_id from log_meal; leave it out to remove their most recent meal.",
        "parameters" to obj(mapOf("meal_log_id" to prop("INTEGER", "meal_log_id to remove (optional)."))),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val id = args.firstPositiveLong("meal_log_id", "id") ?: nutritionService.latestMealLogId(ctx.user.id)
            ?: return mapOf("ok" to false, "error" to "no_meal", "detail" to "There is no meal in their log.")
        return try {
            nutritionService.deleteMealLog(ctx.user.id, id)
            mapOf("ok" to true, "changed" to true, "removed_meal_log_id" to id, "today" to todayNutrition(nutritionContextService, ctx))
        } catch (e: com.richjun.liftupai.global.exception.ResourceNotFoundException) {
            mapOf("ok" to false, "error" to "not_found", "detail" to "That meal is not in their log.")
        }
    }
}

@Component
class UpdateProfileTool(
    private val userService: UserService,
    private val userPlanService: UserPlanService,
) : CoachTool {
    override val name = "update_my_profile"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "CHANGES THE USER'S PROFILE: body weight, height, age, goals, experience level, training days per week, minutes per session, " +
            "injuries (the FULL current list — [] when none; their plan swaps out exercises that load a new injury), or your trainer persona. " +
            "Use when they tell you any of these facts (\"I weigh 72 kg now\", \"무릎을 다쳤어\", \"I can only train 3 days\", \"말투를 바꿔줘\"). " +
            "Send only the fields that changed.",
        "parameters" to obj(mapOf(
            "weight_kg" to prop("NUMBER", "Body weight in kg."),
            "height_cm" to prop("NUMBER", "Height in cm."),
            "age" to prop("INTEGER", "Age in years."),
            "goals" to arrProp("Their goals now (full list).", GOAL_VALUES),
            "experience_level" to prop("STRING", "Training experience.", LEVEL_VALUES),
            "weekly_workout_days" to prop("INTEGER", "Training days per week (1-7)."),
            "session_minutes" to prop("INTEGER", "Minutes per session (10-180)."),
            "injuries" to arrProp("FULL list of current injuries; [] = none.", INJURY_VALUES),
            "trainer_style" to prop("STRING", "Your persona/voice.", PT_STYLE_VALUES),
        )),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        fun num(n: String) = args.get(n)?.takeIf { !it.isNull }?.let { if (it.isNumber) it.asDouble() else it.asText().trim().toDoubleOrNull() }
        fun list(n: String): List<String>? = args.get(n)?.takeIf { !it.isNull }?.let { v -> if (v.isArray) v.map { it.asText() } else v.asText().split(',') }
            ?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        fun inRange(n: String, v: Double?, lo: Double, hi: Double) = v?.also { if (it < lo || it > hi) throw ToolArgumentException("$n must be between $lo and $hi") }
        val weight = inRange("weight_kg", num("weight_kg"), 25.0, 300.0)
        val height = inRange("height_cm", num("height_cm"), 100.0, 250.0)
        val age = inRange("age", num("age"), 13.0, 100.0)?.toInt()
        val days = inRange("weekly_workout_days", num("weekly_workout_days"), 1.0, 7.0)?.toInt()
        val minutes = inRange("session_minutes", num("session_minutes"), 10.0, 180.0)?.toInt()
        val goals = list("goals")?.onEach { if (it !in GOAL_VALUES) throw ToolArgumentException("unknown goal $it (use ${GOAL_VALUES.joinToString()})") }
        val injuries = list("injuries")?.onEach { if (it !in INJURY_VALUES) throw ToolArgumentException("unknown injury $it (use ${INJURY_VALUES.joinToString()})") }
        val level = args.get("experience_level")?.asText()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.also { if (it !in LEVEL_VALUES) throw ToolArgumentException("unknown experience_level $it") }
        val style = args.get("trainer_style")?.asText()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?.also { if (it !in PT_STYLE_VALUES) throw ToolArgumentException("unknown trainer_style $it") }
        val changedFields = listOfNotNull(
            weight?.let { "weight_kg" }, height?.let { "height_cm" }, age?.let { "age" }, goals?.let { "goals" }, level?.let { "experience_level" },
            days?.let { "weekly_workout_days" }, minutes?.let { "session_minutes" }, injuries?.let { "injuries" }, style?.let { "trainer_style" },
        )
        if (changedFields.isEmpty()) throw ToolArgumentException("send at least one field to change")
        val injuriesBefore = if (injuries != null) userService.getProfileV4(ctx.user.id).let { p -> runCatching { p.injuries.toSet() }.getOrDefault(emptySet()) } else null
        userService.updateProfileV4(ctx.user.id, ProfileUpdateRequest(
            weight = weight, height = height, age = age, goals = goals, experienceLevel = level,
            weeklyWorkoutDays = days, workoutDuration = minutes, injuries = injuries, ptStyle = style,
        ))
        // 새 부상이 생기면 그 부위에 부담인 운동을 플랜에서 바꾼다(장비 맞춤과 같은 경로, 부상 기준 포함)
        val refit = if (injuries != null && injuries.toSet() != injuriesBefore) {
            runCatching { userPlanService.refitActivePlanToEquipment(ctx.user.id, includeInjuries = true) }.getOrNull()
        } else null
        return buildMap {
            put("ok", true); put("changed", true); put("updated", changedFields)
            if (refit != null) put("plan_days_changed", refit.daysChanged.size).also { put("exercises_replaced_for_injury_or_equipment", refit.removed) }
            if (days != null) put("note", "If their plan has a different number of days, offer get_plan_options / change_plan — do not change the plan unless they agree.")
            if (style != null) put("note_style", "Your persona changes from the next message.")
        }
    }

    companion object {
        val GOAL_VALUES = listOf("muscle_gain", "weight_loss", "strength", "endurance", "general_fitness", "athletic_performance")
        val LEVEL_VALUES = listOf("beginner", "novice", "intermediate", "advanced", "expert")
        val INJURY_VALUES = listOf("shoulder", "knee", "lower_back", "hip", "wrist", "elbow")
        val PT_STYLE_VALUES = com.richjun.liftupai.domain.user.entity.PTStyle.values().map { it.name }
    }
}

@Component
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class PlanOptionsTool(
    private val templateRecommendationService: com.richjun.liftupai.domain.workout.service.TemplateRecommendationService,
    private val templateService: com.richjun.liftupai.domain.workout.service.TemplateService,
    private val userPlanService: UserPlanService,
) : CoachTool {
    override val name = "get_plan_options"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Lists the plans that fit this user best right now (recommended first) with template_code, name, days per week and level, " +
            "and marks their current plan. Use before change_plan, or when they ask for a different / easier / shorter plan.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val rec = templateRecommendationService.recommend(ctx.user, com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest(locale = ctx.locale), ctx.locale)
        val current = userPlanService.getActivePlan(ctx.user.id)?.sourceId
        val codes = (listOf(rec.templateCode) + rec.alternatives).distinct().take(4)
        return mapOf(
            "ok" to true, "current_template_code" to current, "why_first" to rec.reason,
            "options" to codes.mapNotNull { code ->
                runCatching { templateService.getTemplateDetail(code, ctx.locale) }.getOrNull()?.let { t ->
                    mapOf("template_code" to t.code, "name" to t.name, "days_per_week" to t.totalDays, "level" to t.targetExperience,
                        "goal" to t.targetGoal, "is_current" to (t.code == current))
                }
            },
        )
    }
}

@Component
class ChangePlanTool(private val userPlanService: UserPlanService) : CoachTool {
    override val name = "change_plan"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "REPLACES THE USER'S ACTIVE PLAN with another plan (template_code from get_plan_options). Only after they agree to that plan. " +
            "Their workout history stays; today's session becomes Day 1 of the new plan.",
        "parameters" to obj(mapOf("template_code" to prop("STRING", "template_code from get_plan_options")), listOf("template_code")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val code = args.requireText("template_code", 80)
        val dash = try { userPlanService.applyTemplate(ctx.user, code, null, ctx.locale) }
        catch (e: com.richjun.liftupai.global.exception.ResourceNotFoundException) {
            return mapOf("ok" to false, "error" to "unknown_template", "detail" to "No plan with that template_code. Call get_plan_options.")
        }
        val plan = userPlanService.getActivePlan(ctx.user.id)
        return mapOf("ok" to true, "changed" to true, "template_code" to code, "plan_name" to plan?.planName, "days_per_week" to plan?.totalDays,
            "today_day" to plan?.currentDay, "dashboard_ok" to (dash != null))
    }
}

@Component
class PausePlanTool(private val userPlanService: UserPlanService) : CoachTool {
    override val name = "pause_or_resume_plan"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "PAUSES or RESUMES the user's plan (travel, illness, a busy week). Paused plans keep their place; reminders for it stop until resumed.",
        "parameters" to obj(mapOf("paused" to prop("BOOLEAN", "true = pause, false = resume")), listOf("paused")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val paused = args.get("paused")?.takeIf { !it.isNull }?.let { if (it.isBoolean) it.asBoolean() else it.asText().trim().lowercase() in setOf("true", "1", "yes") }
            ?: throw ToolArgumentException("paused is required")
        // 쉬는 중인 플랜은 getActivePlan 에 안 잡힌다 — 서비스가 상태별로 찾게 두고 없으면 사실대로 돌려준다
        return try {
            if (paused) userPlanService.pausePlan(ctx.user.id) else userPlanService.resumePlan(ctx.user.id)
            mapOf("ok" to true, "changed" to true, "paused" to paused)
        } catch (e: com.richjun.liftupai.global.exception.ResourceNotFoundException) {
            mapOf("ok" to false, "error" to if (paused) "no_active_plan" else "no_paused_plan",
                "detail" to if (paused) "They have no active plan to pause." else "Their plan is not paused.")
        }
    }
}

@Component
class LogWorkoutTool(
    private val workoutSessionService: com.richjun.liftupai.domain.workout.service.WorkoutSessionService,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val exerciseRepository: ExerciseRepository,
) : CoachTool {
    override val name = "log_completed_workout"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "SAVES A WORKOUT the user already did TODAY outside the app (\"오늘 헬스장에서 스쿼트 100kg 5회 5세트 했어\") into their history, " +
            "so stats, PRs and recovery count it. Get exercise_id values from search_exercises or get_today_workout. Today only.",
        "parameters" to obj(mapOf(
            "exercises" to mapOf("type" to "ARRAY", "description" to "Exercises done, in order.", "items" to obj(mapOf(
                "exercise_id" to prop("INTEGER", "exercise_id"),
                "sets" to mapOf("type" to "ARRAY", "description" to "Each set done.", "items" to obj(mapOf(
                    "weight_kg" to prop("NUMBER", "0 for bodyweight"),
                    "reps" to prop("INTEGER", "reps"),
                ), listOf("reps"))),
            ), listOf("exercise_id", "sets"))),
            "duration_minutes" to prop("INTEGER", "How long the workout took."),
        ), listOf("exercises", "duration_minutes")),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val items = args.get("exercises")?.takeIf { it.isArray && it.size() > 0 } ?: throw ToolArgumentException("exercises is required")
        if (items.size() > 15) throw ToolArgumentException("at most 15 exercises")
        val minutes = args.firstPositiveLong("duration_minutes")?.toInt()?.coerceIn(5, 300) ?: throw ToolArgumentException("duration_minutes is required")
        val done = items.map { e ->
            val id = e.firstPositiveLong("exercise_id") ?: throw ToolArgumentException("exercise_id is required")
            val sets = e.get("sets")?.takeIf { it.isArray && it.size() in 1..20 } ?: throw ToolArgumentException("1-20 sets per exercise")
            com.richjun.liftupai.domain.workout.dto.CompletedExerciseV2(id, sets.map { s ->
                val reps = s.firstPositiveLong("reps")?.toInt()?.takeIf { it <= 100 } ?: throw ToolArgumentException("reps must be 1-100")
                val w = s.get("weight_kg")?.takeIf { !it.isNull }?.asDouble() ?: 0.0
                if (w < 0 || w > 500) throw ToolArgumentException("weight_kg must be 0-500")
                com.richjun.liftupai.domain.workout.dto.ExerciseSetV2(weight = w, reps = reps)
            })
        }
        val known = exerciseRepository.findAllById(done.map { it.exerciseId }).map { it.id }.toSet()
        val unknown = done.map { it.exerciseId }.filter { it !in known }
        if (unknown.isNotEmpty()) throw ToolArgumentException("unknown exercise_id ${unknown.joinToString()} — use search_exercises")
        if (workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(ctx.user, SessionStatus.IN_PROGRESS).isPresent) {
            return mapOf("ok" to false, "error" to "workout_open", "detail" to "They have a workout open in the app. Ask them to finish or close it there first.")
        }
        val started = workoutSessionService.startNewWorkout(ctx.user.id, com.richjun.liftupai.domain.workout.dto.StartWorkoutRequestV2(workoutType = "empty"))
        val result = workoutSessionService.completeWorkout(ctx.user.id, started.sessionId,
            com.richjun.liftupai.domain.workout.dto.CompleteWorkoutRequestV2(exercises = done, duration = minutes, notes = "logged by AI PT chat"))
        return mapOf("ok" to true, "changed" to true, "session_id" to started.sessionId,
            "total_sets" to result.summary.totalSets, "total_volume_kg" to result.summary.totalVolume.roundToInt(), "exercises" to result.summary.exerciseCount)
    }
}

@Component
class UndoCoachChangeTool(
    private val coachActionService: CoachActionService,
    private val coachActionRepository: CoachActionRepository,
) : CoachTool {
    override val name = "undo_last_coach_change"
    override val mutating = true
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "UNDOES the most recent change you (the coach) made to their plan or settings (swap, today's day, equipment, workout time, " +
            "check-in messages), when they ask to put it back. Only the latest change, within its time window.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val latest = coachActionRepository.findFirstByUserIdOrderByIdDesc(ctx.user.id)
            ?: return mapOf("ok" to false, "error" to "nothing_to_undo", "detail" to "You have not changed anything for them yet.")
        val r = coachActionService.undo(ctx.user.id, latest.id, ctx.locale)
            ?: return mapOf("ok" to false, "error" to "nothing_to_undo")
        return mapOf("ok" to r.ok, "changed" to r.ok, "undone" to latest.summary, "code" to r.code, "message" to r.message)
    }
}

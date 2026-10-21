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
)

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
class CoachToolbox(tools: List<CoachTool>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byName = tools.associateBy { it.name }

    data class Outcome(val name: String, val ok: Boolean, val changed: Boolean, val result: Map<String, Any?>, val elapsedMs: Long)

    fun declarations(): List<Map<String, Any>> = byName.values.sortedBy { it.name }.map { it.declaration() }

    fun execute(name: String, args: JsonNode, ctx: CoachToolContext): Outcome {
        val started = System.currentTimeMillis()
        val tool = byName[name]
            ?: return Outcome(name, false, false, mapOf("ok" to false, "error" to "unknown_tool"), 0)
        return try {
            val result = tool.run(ctx, args)
            val ok = result["ok"] != false
            Outcome(name, ok, ok && result["changed"] == true, result, System.currentTimeMillis() - started)
        } catch (e: ToolArgumentException) {
            Outcome(name, false, false, mapOf("ok" to false, "error" to "bad_arguments", "detail" to e.message), System.currentTimeMillis() - started)
        } catch (e: Exception) {
            log.warn("[AI-AGENT] tool {} failed user={}: {}", name, ctx.user.id, e.message)
            Outcome(name, false, false, mapOf("ok" to false, "error" to "tool_failed", "detail" to (e.message ?: e.javaClass.simpleName).take(200)), System.currentTimeMillis() - started)
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
class TodayWorkoutTool(private val userPlanService: UserPlanService) : CoachTool {
    override val name = "get_today_workout"
    override val mutating = false
    override fun declaration() = mapOf(
        "name" to name,
        "description" to "Today's session from the user's active plan: each exercise with sets, rep range, suggested weight and rest. " +
            "Use when they ask what to do today or want changes to today's session.",
        "parameters" to obj(emptyMap()),
    )

    override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> {
        val plan = userPlanService.getActivePlan(ctx.user.id)
            ?: return mapOf("ok" to true, "has_plan" to false, "note" to "no active plan — suggest choosing one (OPEN_PLAN)")
        val day = userPlanService.getDayWorkout(ctx.user.id, plan.currentDay, locale = ctx.locale)
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
                )
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
        val from = args.get("from_exercise_id")?.asLong()?.takeIf { it > 0 } ?: throw ToolArgumentException("from_exercise_id is required")
        val to = args.get("to_exercise_id")?.asLong()?.takeIf { it > 0 } ?: throw ToolArgumentException("to_exercise_id is required")
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

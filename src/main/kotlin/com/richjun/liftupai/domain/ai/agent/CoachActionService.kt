package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.dto.UpdateNotificationSettingsRequest
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.user.dto.ProfileUpdateRequest
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.domain.workout.entity.PlanStatus
import com.richjun.liftupai.domain.workout.entity.UserPlanDayExercise
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.UserPlanDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.UserPlanDayRepository
import com.richjun.liftupai.domain.workout.repository.UserWorkoutPlanRepository
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration

/** 코치 도구가 바꾸기 전 상태를 잡고, 바꾼 뒤 기록을 남기는 자리(CoachToolbox 가 부른다). 단위 테스트에선 없어도 된다. */
interface CoachActionRecorder {
    fun capture(userId: Long): CoachActionService.State
    fun record(user: User, tool: String, before: CoachActionService.State, result: Map<String, Any?>, locale: String, source: String = "chat"): Long?

}

/**
 * "코치가 한 일" — 트레이너(채팅·자동 조정)가 바꾼 플랜·설정의 기록과 되돌리기(2026-11-14 coach-autopilot-r2).
 *
 * 알아서 해 주는 코치의 전제는 사람이 보고 되돌릴 수 있는 것이다. 바꾸기 전 상태 전체(활성 플랜의 모든 종목 행·현재 Day·장비·
 * 운동 시간·메시지 설정)를 남기고, 되돌리면 그대로 복원한다. 플랜이 통째로 바뀐 경우(장비 → 새 템플릿)는 예전 플랜을 다시 켠다.
 *
 * 안전 규칙 — 되돌리기는 가장 최근 기록 하나만, 7일 안, 그 뒤 플랜이 바뀌지 않았을 때만(사용자가 직접 바꾼 것을 덮지 않는다).
 */
@Service
class CoachActionService(
    private val repository: CoachActionRepository,
    private val userPlanService: UserPlanService,
    private val planRepository: UserWorkoutPlanRepository,
    private val planDayRepository: UserPlanDayRepository,
    private val planDayExerciseRepository: UserPlanDayExerciseRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val userService: UserService,
    private val notificationService: NotificationService,
    private val objectMapper: ObjectMapper,
) : CoachActionRecorder {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Row(
        val dayId: Long, val orderInDay: Int, val exerciseId: Long, val sets: Int, val minReps: Int, val maxReps: Int,
        val restSeconds: Int, val compound: Boolean, val rpe: Double, val notes: String?,
    )

    data class PlanState(val planId: Long, val currentDay: Int, val rows: List<Row>, val dayMinutes: Map<Long, Int> = emptyMap()) {
        /** 종목 행 지문 — 그 뒤 플랜이 바뀌었는지 본다(현재 Day 는 운동을 마치면 넘어가므로 넣지 않는다) */
        fun fingerprint(): String = sha256(rows.sortedWith(compareBy({ it.dayId }, { it.orderInDay }))
            .joinToString(";") { "${it.dayId}:${it.orderInDay}:${it.exerciseId}:${it.sets}:${it.minReps}:${it.maxReps}:${it.restSeconds}" })
    }

    data class State(val plan: PlanState?, val equipment: List<String>, val workoutTime: String?, val aiMessages: Boolean?)

    data class Snapshot(val before: State, val afterPlanId: Long?, val afterFingerprint: String?, val afterCurrentDay: Int?)

    data class Item(val action: CoachAction, val undoable: Boolean)

    data class UndoResult(val ok: Boolean, val code: String, val message: String)

    @Transactional(readOnly = true)
    override fun capture(userId: Long): State = State(
        plan = capturePlan(userId),
        equipment = runCatching { userPlanService.currentEquipment(userId).sorted() }.getOrDefault(emptyList()),
        workoutTime = userSettingsRepository.findByUser_Id(userId).orElse(null)?.preferredWorkoutTime,
        aiMessages = runCatching { notificationService.getNotificationSettings(userId).aiMessages }.getOrNull(),
    )

    private fun capturePlan(userId: Long): PlanState? {
        val plan = userPlanService.getActivePlan(userId) ?: return null
        val days = planDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val rows = days.flatMap { d ->
            planDayExerciseRepository.findByPlanDayIdOrderByOrderInDay(d.id).map { e ->
                Row(d.id, e.orderInDay, e.exercise.id, e.sets, e.minReps, e.maxReps, e.restSeconds, e.isCompound, e.targetRPE, e.notes)
            }
        }
        return PlanState(plan.id, plan.currentDay, rows, days.associate { it.id to it.estimatedDurationMinutes })
    }

    /** 바꾼 뒤 상태를 잡아 기록한다. 기록 실패가 도구 결과(이미 바뀐 것)를 되돌리지 않게 따로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(user: User, tool: String, before: State, result: Map<String, Any?>, locale: String, source: String): Long? {
        if (tool !in UNDOABLE_TOOLS) return null
        val after = capturePlan(user.id)
        val snapshot = Snapshot(before, after?.planId, after?.fingerprint(), after?.currentDay)
        val saved = repository.save(CoachAction(
            userId = user.id, tool = tool, source = source,
            summary = summarize(tool, result, locale).take(200),
            snapshot = objectMapper.writeValueAsString(snapshot),
            createdAt = AppTime.utcNow(),
        ))
        return saved.id
    }

    @Transactional(readOnly = true)
    fun list(userId: Long): List<Item> {
        val actions = repository.findTop20ByUserIdOrderByIdDesc(userId)
        val latest = actions.firstOrNull()
        return actions.map { a -> Item(a, a === latest && a.undoneAt == null && !expired(a)) }
    }

    /** 가장 최근 기록을 되돌린다. 조건이 안 맞으면 바꾸지 않고 이유 코드를 돌려준다(없는 기록은 null → 404). */
    @Transactional
    fun undo(userId: Long, actionId: Long, locale: String): UndoResult? {
        val action = repository.findByIdAndUserId(actionId, userId) ?: return null
        fun refuse(code: String) = UndoResult(false, code, WorkoutLocalization.message("coach.undo.$code", locale))
        if (action.undoneAt != null) return refuse("already")
        if (repository.findFirstByUserIdOrderByIdDesc(userId)?.id != action.id) return refuse("not_latest")
        if (expired(action)) return refuse("expired")
        val snap = objectMapper.readValue<Snapshot>(action.snapshot)
        val before = snap.before

        if (action.tool in PLAN_TOOLS) {
            // 그 뒤 플랜이 바뀌었으면(다른 플랜을 골랐거나 종목을 직접 바꿨으면) 덮지 않는다
            val now = capturePlan(userId)
            if (now?.planId != snap.afterPlanId || now?.fingerprint() != snap.afterFingerprint) return refuse("changed_since")
            if (action.tool == "set_today_workout_day" && now?.currentDay != snap.afterCurrentDay) return refuse("changed_since")
        }

        when (action.tool) {
            "swap_exercise_in_plan" -> before.plan?.let { restorePlan(userId, it, restoreCurrentDay = false) }
            "set_today_workout_day" -> before.plan?.let { p ->
                val plan = planRepository.findById(p.planId).orElse(null) ?: return refuse("changed_since")
                plan.currentDay = p.currentDay
                planRepository.save(plan)
            }
            "set_available_equipment" -> {
                // 장비를 먼저 되돌리고(앱 프로필 저장과 같은 경로) 플랜을 바꾸기 전 그대로 복원한다
                userService.updateProfileV4(userId, ProfileUpdateRequest(availableEquipment = before.equipment.ifEmpty { listOf("BODYWEIGHT") }))
                before.plan?.let { restorePlan(userId, it, restoreCurrentDay = it.planId != snap.afterPlanId) }
            }
            "set_workout_time" -> before.workoutTime?.let { userService.updateProfileV4(userId, ProfileUpdateRequest(preferredWorkoutTime = it)) }
            "set_coach_messages" -> before.aiMessages?.let {
                notificationService.updateNotificationSettings(userId, UpdateNotificationSettingsRequest(aiMessages = it))
            }
            else -> return refuse("not_undoable")
        }
        action.undoneAt = AppTime.utcNow()
        repository.save(action)
        log.info("[COACH-ACTION] undone user={} action={} tool={}", userId, action.id, action.tool)
        return UndoResult(true, "done", WorkoutLocalization.message("coach.undo.done", locale))
    }

    /**
     * 플랜을 바꾸기 전 그대로: 다른 플랜이 켜져 있으면 그것을 내려놓고 예전 플랜을 다시 켠 뒤, 종목 행 전체를 스냅샷으로 바꾼다.
     * 하루 예상 시간도 함께 되돌린다.
     */
    private fun restorePlan(userId: Long, state: PlanState, restoreCurrentDay: Boolean) {
        val target = planRepository.findById(state.planId).orElseThrow { IllegalStateException("plan ${state.planId} not found") }
        val active = userPlanService.getActivePlan(userId)
        if (active != null && active.id != target.id) {
            active.status = PlanStatus.ABANDONED
            planRepository.save(active)
        }
        target.status = PlanStatus.ACTIVE
        if (restoreCurrentDay) target.currentDay = state.currentDay
        planRepository.save(target)

        val days = planDayRepository.findByPlanIdOrderByDayNumber(target.id).associateBy { it.id }
        days.values.forEach { d -> planDayExerciseRepository.deleteAll(planDayExerciseRepository.findByPlanDayIdOrderByOrderInDay(d.id)) }
        planDayExerciseRepository.flush()
        val exercises = exerciseRepository.findAllById(state.rows.map { it.exerciseId }.distinct()).associateBy { it.id }
        planDayExerciseRepository.saveAll(state.rows.mapNotNull { r ->
            val day = days[r.dayId] ?: return@mapNotNull null
            val ex = exercises[r.exerciseId] ?: return@mapNotNull null
            UserPlanDayExercise(
                planDay = day, exercise = ex, orderInDay = r.orderInDay, sets = r.sets, minReps = r.minReps, maxReps = r.maxReps,
                restSeconds = r.restSeconds, isCompound = r.compound, targetRPE = r.rpe, notes = r.notes,
            )
        })
        state.dayMinutes.forEach { (dayId, minutes) ->
            days[dayId]?.let { if (it.estimatedDurationMinutes != minutes) { it.estimatedDurationMinutes = minutes; planDayRepository.save(it) } }
        }
    }

    private fun expired(a: CoachAction) = Duration.between(a.createdAt, AppTime.utcNow()) > UNDO_WINDOW

    /** 사용자 언어로 한 줄 — 도구 결과의 실제 값만 쓴다 */
    internal fun summarize(tool: String, r: Map<String, Any?>, locale: String): String {
        fun s(key: String) = r[key]?.toString().orEmpty()
        return when (tool) {
            "swap_exercise_in_plan" -> WorkoutLocalization.message("coach.action.swap", locale, s("from"), s("to"))
            "set_today_workout_day" -> WorkoutLocalization.message("coach.action.today_day", locale, s("day"), s("day_name"))
            "set_available_equipment" -> {
                @Suppress("UNCHECKED_CAST")
                val names = (r["equipment"] as? List<Any?>).orEmpty().map { WorkoutLocalization.equipmentName(it?.toString(), locale) }.distinct()
                val planText = s("plan")
                val planPart = when {
                    planText.startsWith("most of their old plan") -> WorkoutLocalization.message("coach.action.plan.new", locale, s("plan_name"))
                    (r["exercises_replaced"] as? Number)?.toInt()?.let { it > 0 } == true ->
                        WorkoutLocalization.message("coach.action.plan.refit", locale, (r["exercises_replaced"] as Number).toInt())
                    else -> WorkoutLocalization.message("coach.action.plan.same", locale)
                }
                WorkoutLocalization.message("coach.action.equipment", locale, names.joinToString(", "), planPart)
            }
            "set_workout_time" -> WorkoutLocalization.message("coach.action.workout_time", locale, s("workout_time"))
            "set_coach_messages" -> WorkoutLocalization.message(if (s("coach_messages") == "off") "coach.action.messages_off" else "coach.action.messages_on", locale)
            else -> tool
        }
    }

    companion object {
        val PLAN_TOOLS = setOf("swap_exercise_in_plan", "set_today_workout_day", "set_available_equipment")
        val UNDOABLE_TOOLS = PLAN_TOOLS + setOf("set_workout_time", "set_coach_messages")
        val UNDO_WINDOW: Duration = Duration.ofDays(7)

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

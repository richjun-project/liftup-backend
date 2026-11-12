package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.FirstSessionRule
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 채팅 트레이너가 "이 사용자의 실제 운동 상황" 을 알고 말하게 하는 프롬프트 블록.
 *
 * 2026-10 이전에는 오늘 플랜·최근 운동이 프롬프트에 없어서, 트레이너가 사용자의 루틴을 모른 채
 * 일반론만 말했고 "오늘 운동 피드백" 칩도 무엇을 했는지 볼 수 없었다.
 */
@Component
class TrainerContextBuilder(
    private val userPlanService: UserPlanService,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Snapshot(
        val hasActivePlan: Boolean,
        val completedWorkouts: Long,
        val block: String,
    )

    fun build(user: User, locale: String): Snapshot {
        val lines = mutableListOf<String>()
        val plan = userPlanService.getActivePlan(user.id)
        if (plan == null) {
            lines += "- No active workout plan yet. If they want to train, attach [[OPEN_PLAN]] so they can pick one."
        } else {
            try {
                val day = userPlanService.getDayWorkout(user.id, plan.currentDay, locale = locale)
                lines += "- Active plan: \"${plan.planName}\". Today's session = Day ${day.dayNumber} \"${day.dayName}\", about ${day.estimatedDuration} min:"
                day.exercises.take(8).forEachIndexed { i, e ->
                    val reps = if (e.minReps == e.maxReps) "${e.minReps}" else "${e.minReps}-${e.maxReps}"
                    val weight = e.suggestedWeight?.takeIf { it > 0 }?.let { " @ ${formatKg(it)} kg" } ?: ""
                    lines += "  ${i + 1}. ${e.exerciseName} — ${e.sets} sets × $reps reps$weight"
                }
            } catch (e: Exception) {
                log.warn("[AI-CHAT] today's plan day unavailable for user {}: {}", user.id, e.message)
                lines += "- Active plan: \"${plan.planName}\" (Day ${plan.currentDay})."
            }
        }

        val completed = workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED)
        if (completed == 0L) {
            val level = try { userProfileRepository.findByUser_Id(user.id).orElse(null)?.experienceLevel } catch (e: Exception) { null }
            lines += firstWorkoutLine(level)
        } else {
            lines += "- Completed workouts so far: $completed."
            workoutSessionRepository.findTopByUserOrderByStartTimeDesc(user)
                ?.takeIf { it.status == SessionStatus.COMPLETED }
                ?.let { last ->
                    val days = Duration.between(last.startTime, AppTime.utcNow()).toDays()
                    val volume = last.totalVolume?.takeIf { it > 0 }?.let { ", total volume ${formatKg(it)} kg" } ?: ""
                    val minutes = last.duration?.takeIf { it in 1..600 }?.let { ", $it min" } ?: ""
                    lines += "- Last workout: ${if (days == 0L) "today" else "$days days ago"}$minutes$volume."
                }
        }
        val weekAgo = AppTime.utcNow().minusDays(7)
        lines += "- Workouts started in the last 7 days: ${workoutSessionRepository.countByUserAndStartTimeAfter(user, weekAgo)}."

        val block = (listOf("## TRAINING CONTEXT (real data from the app — use it; never invent sessions, weights or dates)") + lines)
            .joinToString("\n")
        return Snapshot(hasActivePlan = plan != null, completedWorkouts = completed, block = block)
    }

    companion object {
        /**
         * 완료 0회 사용자 안내 줄. 첫 세션 규칙 대상이면 '가볍게 시작', 상급·전문가(규칙 밖, 2026-11-12)는 오늘 플랜 그대로.
         * 11-11 상급·90분 실가입자에게 트레이너가 "30分だけでも"·"初日は3種目だけ"라고 해 본인이 종목을 손으로 더했다.
         */
        internal fun firstWorkoutLine(level: ExperienceLevel?): String =
            if (FirstSessionRule.appliesTo(level)) {
                "- Completed workouts so far: 0 — they have NEVER finished a workout. Your top job is getting them " +
                    "through the first one: encourage starting today's session now (the app offers a light start — the first " +
                    "3 exercises, 2 sets each) and attach [[START_TODAY_WORKOUT]] when it fits."
            } else {
                "- Completed workouts so far: 0 in this app, but they reported ${level?.name} training experience — today's " +
                    "session above is their full plan at their chosen length. Treat them as an experienced lifter: do not tell " +
                    "them to cut it short, do only a few exercises or keep it to 30 minutes. Encourage logging today's session " +
                    "and attach [[START_TODAY_WORKOUT]] when it fits."
            }
    }

    private fun formatKg(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
}

package com.richjun.liftupai.domain.progress.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.progress.calc.*
import com.richjun.liftupai.domain.progress.dto.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class ProgressService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val mealLogRepository: MealLogRepository
) {
    /** 저장소에서 읽은 이력을 계산기 입력으로 바꾼다. excludeSessionId 는 "이 세션 제외" 계산용. */
    private data class Loaded(val input: XpInput)

    private fun load(userId: Long): XpInput {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("User not found") }
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val bodyweight = userProfileRepository.findByUser_Id(userId).orElse(null)?.bodyInfo?.weight
        val sessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        val exercisesBySession = if (sessions.isEmpty()) emptyMap()
        else workoutExerciseRepository.findBySessionIdInWithExercise(sessions.map { it.id }).groupBy { it.session.id }
        val inputs = sessions.map { s ->
            XpSessionInput(
                id = s.id,
                completedAt = s.endTime ?: s.startTime,
                exercises = (exercisesBySession[s.id] ?: emptyList()).map { we ->
                    XpExerciseInput(we.exercise.id, we.sets.map { XpSetInput(it.weight, it.reps, it.completed) })
                }
            )
        }
        val meals = mealLogRepository.findByUserAndTimestampBetween(
            user, LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2100, 1, 1, 0, 0)
        ).map { it.timestamp }
        return XpInput(
            sessions = inputs,
            mealTimestamps = meals,
            bodyweightKg = bodyweight,
            weeklyGoal = settings?.weeklyWorkoutDays,
            zone = AppTime.resolveZoneId(settings?.timeZone)
        )
    }

    fun getLevel(userId: Long): LevelResponse {
        val input = load(userId)
        val result = XpCalculator.calculate(input)
        val total = result.totalXp
        val level = LevelCurve.levelFor(total)
        val today = AppTime.currentUserDate(input.zone)
        val evo = LevelCurve.nextEvolutionLevel(level)
        return LevelResponse(
            level = level,
            totalXp = total,
            currentLevelXp = LevelCurve.cumulativeXp(level),
            nextLevelXp = LevelCurve.cumulativeXp(level + 1),
            classKey = LevelCurve.classKey(level),
            nextEvolutionLevel = evo,
            xpToNextEvolution = evo?.let { LevelCurve.cumulativeXp(it) - total },
            activeWeekStreak = result.streakWeeksOn(today),
            multiplier = result.multiplierFor(today),
            todayXp = result.cappedXpOn(today),
            dailyCap = XpRules.DAILY_CAP
        )
    }

    /** 완료된 세션 하나의 XP 결과. before = 이 세션을 뺀 이력, after = 포함한 이력. */
    fun getWorkoutXp(userId: Long, sessionId: Long): WorkoutXpResult? {
        val input = load(userId)
        val after = XpCalculator.calculate(input)
        val sx = after.sessions[sessionId] ?: return null
        val before = XpCalculator.calculate(input.copy(sessions = input.sessions.filter { it.id != sessionId }))
        val beforeLevel = LevelCurve.levelFor(before.totalXp)
        val afterLevel = LevelCurve.levelFor(after.totalXp)
        val beforeClass = LevelCurve.classKey(beforeLevel)
        val afterClass = LevelCurve.classKey(afterLevel)
        val breakdown = buildList {
            add(XpBreakdownItem(XpType.SET.name, sx.setXp, sx.setCount))
            add(XpBreakdownItem(XpType.WORKOUT_COMPLETE.name, sx.completeXp, 1))
            if (sx.prCount > 0) add(XpBreakdownItem(XpType.PERSONAL_RECORD.name, sx.prXp, sx.prCount))
            if (sx.weeklyGoalXp > 0) add(XpBreakdownItem(XpType.WEEKLY_GOAL.name, sx.weeklyGoalXp, 1))
        }
        val day = after.days.getValue(sx.date)
        return WorkoutXpResult(
            gained = after.totalXp - before.totalXp,
            breakdown = breakdown,
            before = XpState(beforeLevel, before.totalXp, beforeClass),
            after = XpState(afterLevel, after.totalXp, afterClass),
            leveledUp = afterLevel > beforeLevel,
            evolved = afterClass != beforeClass,
            multiplier = day.multiplier,
            dailyCapApplied = Math.round(day.rawXp * day.multiplier).toInt() > XpRules.DAILY_CAP
        )
    }
}

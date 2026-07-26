package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.Achievement
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.AchievementRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutStreakRepository
import com.richjun.liftupai.domain.workout.util.WorkoutAchievementCatalog
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * WorkoutServiceV2에서 분리된 통계/캘린더 서비스.
 * 운동 완료 통계, 월별 캘린더, 스트릭/업적 요약을 담당한다.
 *
 * calculateWorkoutStats / calculateCurrentStreak 는 WorkoutSessionService의
 * completeWorkout / checkMilestones 에서도 재사용되므로 public으로 노출한다.
 */
@Service
@Transactional
class WorkoutStatsService(
    private val userRepository: UserRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val achievementRepository: AchievementRepository,
    private val workoutStreakRepository: WorkoutStreakRepository,
    private val sharedSupport: WorkoutV2SharedSupport
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Transactional(readOnly = true)
    fun getWorkoutCompletionStats(userId: Long, sessionId: Long?, localeOverride: String? = null): WorkoutCompletionStats {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId, localeOverride)

        val session = sessionId?.let { sharedSupport.findUserSession(userId, it) }

        // 세션 통계
        val sessionStats = session?.let {
            SessionStats(
                duration = it.duration ?: 0,
                totalVolume = it.totalVolume ?: 0.0,
                totalSets = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(it.id).sumOf { ex -> ex.sets.size },
                exerciseCount = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(it.id).size
            )
        } ?: SessionStats(0, 0.0, 0, 0)

        // 히스토리 통계
        val totalWorkouts = workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED)
        val memberSince = user.joinDate
        val zoneId = sharedSupport.resolveTimeZone(userId)
        val completedSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        val weeksSinceJoin = ChronoUnit.WEEKS.between(memberSince, AppTime.utcNow())
        val avgWorkoutsPerWeek = if (weeksSinceJoin > 0) totalWorkouts.toDouble() / weeksSinceJoin else 0.0

        val historyStats = HistoryStats(
            totalWorkoutDays = completedSessions.map { AppTime.toUserLocalDate(it.startTime, zoneId) }.distinct().count(),
            totalWorkouts = totalWorkouts.toInt(),
            memberSince = memberSince.format(DateTimeFormatter.ISO_LOCAL_DATE),
            averageWorkoutsPerWeek = avgWorkoutsPerWeek
        )

        // 스트릭 통계
        val currentStreak = calculateCurrentStreak(user, zoneId)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0
        val weeklyCount = calculateWeeklyWorkoutCount(user, zoneId)
        val monthlyCount = calculateMonthlyWorkoutCount(user)

        val streakStats = StreakStats(
            current = currentStreak,
            longest = longestStreak,
            weeklyCount = weeklyCount,
            weeklyGoal = 5,
            monthlyCount = monthlyCount,
            monthlyGoal = 20
        )

        // 업적
        val achievements = achievementRepository.findByUser_Id(userId).map { ach ->
            com.richjun.liftupai.domain.workout.dto.Achievement(
                id = ach.id.toString(),
                code = ach.code,
                name = localizedAchievementName(ach, locale),
                description = localizedAchievementDescription(ach, locale),
                unlockedAt = ach.unlockedAt.format(DateTimeFormatter.ISO_LOCAL_DATE),
                icon = ach.icon
            )
        }

        // 비교 통계
        val lastWeekVolume = calculateLastWeekAverageVolume(user)
        val volumeChange = if (lastWeekVolume > 0 && session != null) {
            val change = ((session.totalVolume ?: 0.0) - lastWeekVolume) / lastWeekVolume * 100
            "${if (change >= 0) "+" else ""}${change.roundToInt()}%"
        } else "N/A"

        val comparisonStats = ComparisonStats(
            volumeChange = volumeChange,
            durationChange = WorkoutLocalization.message("duration.change.minutes", locale, 5),
            comparedTo = "lastWeekAverage"
        )

        return WorkoutCompletionStats(
            session = sessionStats,
            history = historyStats,
            streaks = streakStats,
            achievements = achievements,
            comparison = comparisonStats
        )
    }

    @Transactional(readOnly = true)
    fun getWorkoutCalendar(userId: Long, year: Int, month: Int, localeOverride: String? = null): WorkoutCalendarResponse {
        val logger = LoggerFactory.getLogger(this::class.java)

        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId, localeOverride)

        val yearMonth = YearMonth.of(year, month)
        val zoneId = sharedSupport.resolveTimeZone(userId)
        val (startDate, _) = AppTime.utcRangeForLocalDate(yearMonth.atDay(1), zoneId)
        val (_, endDate) = AppTime.utcRangeForLocalDate(yearMonth.atEndOfMonth(), zoneId)

        logger.info("Fetching workout sessions for user ${user.id} from $startDate to $endDate")

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)

        logger.info("Found ${sessions.size} sessions for user ${user.id} in ${yearMonth.year}-${yearMonth.monthValue}")
        sessions.forEach { session ->
            logger.debug("Session ID: ${session.id}, Date: ${session.startTime}, Status: ${session.status}, Volume: ${session.totalVolume}")
        }

        val calendarDays = (1..yearMonth.lengthOfMonth()).map { day ->
            val date = LocalDate.of(year, month, day)
            val daySessions = sessions.filter { AppTime.toUserLocalDate(it.startTime, zoneId) == date }

            if (daySessions.isNotEmpty()) {
                logger.debug("getWorkoutCalendar: Date {} has {} sessions", date, daySessions.size)
                daySessions.forEach { session ->
                    logger.debug("- Session ID: {}, Volume: {}, Status: {}", session.id, session.totalVolume, session.status)
                }
            }

            CalendarDay(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                hasWorkout = daySessions.isNotEmpty(),
                workoutCount = daySessions.size,
                totalVolume = daySessions.sumOf { it.totalVolume ?: 0.0 },
                primaryMuscles = daySessions.flatMap { session ->
                    val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    exercises.flatMap { it.exercise.muscleGroups.map { mg -> WorkoutLocalization.muscleGroupName(mg, locale) } }
                }.distinct()
            )
        }

        val totalDays = calendarDays.count { it.hasWorkout }
        val restDays = yearMonth.lengthOfMonth() - totalDays
        val averageVolume = if (totalDays > 0) {
            calendarDays.filter { it.hasWorkout }.sumOf { it.totalVolume ?: 0.0 } / totalDays
        } else 0.0

        val mostFrequentDay = sessions
            .groupBy { AppTime.toUserLocalDate(it.startTime, zoneId).dayOfWeek }
            .maxByOrNull { it.value.size }
            ?.key
            ?.let { WorkoutLocalization.message("day.${it.name.lowercase()}", locale) }
            ?: "n/a"

        return WorkoutCalendarResponse(
            calendar = calendarDays,
            summary = CalendarSummary(
                totalDays = totalDays,
                restDays = restDays,
                averageVolume = averageVolume,
                mostFrequentDay = mostFrequentDay
            )
        )
    }

    /**
     * WorkoutSessionService.completeWorkout()에서도 호출되므로 public.
     */
    fun calculateWorkoutStats(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStats {
        val zoneId = sharedSupport.resolveTimeZone(user.id)
        val completedSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        val totalWorkoutDays = completedSessions.map { AppTime.toUserLocalDate(it.startTime, zoneId) }.distinct().count()
        val currentWeekCount = calculateWeeklyWorkoutCount(user, zoneId)
        val currentStreak = calculateCurrentStreak(user, zoneId)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0

        return WorkoutStats(
            totalWorkoutDays = totalWorkoutDays,
            currentWeekCount = currentWeekCount,
            weeklyGoal = 5,
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

    /**
     * WorkoutSessionService.checkMilestones()에서도 호출되므로 public.
     */
    fun calculateCurrentStreak(user: com.richjun.liftupai.domain.auth.entity.User, zoneId: java.time.ZoneId): Int {
        val workoutDays = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
            .map { AppTime.toUserLocalDate(it.startTime, zoneId) }
            .toSet()
        val today = AppTime.currentUserDate(zoneId)
        var streak = 0
        var currentDate = today

        val maxStreakDays = 365
        var iterations = 0
        while (iterations < maxStreakDays) {
            val hasWorkout = workoutDays.contains(currentDate)
            if (hasWorkout) {
                streak++
                currentDate = currentDate.minusDays(1)
            } else if (currentDate == today) {
                currentDate = currentDate.minusDays(1)
            } else {
                break
            }
            iterations++
        }

        return streak
    }

    private fun calculateWeeklyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User, zoneId: java.time.ZoneId): Int {
        val today = AppTime.currentUserDate(zoneId)
        val startOfWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
        val (startUtc, _) = AppTime.utcRangeForLocalDate(startOfWeek, zoneId)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startUtc,
            AppTime.utcNow()
        )
    }

    private fun calculateMonthlyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val zoneId = sharedSupport.resolveTimeZone(user.id)
        val today = AppTime.currentUserDate(zoneId)
        val startOfMonth = today.withDayOfMonth(1)
        val (startUtc, _) = AppTime.utcRangeForLocalDate(startOfMonth, zoneId)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startUtc,
            AppTime.utcNow()
        )
    }

    private fun calculateLastWeekAverageVolume(user: com.richjun.liftupai.domain.auth.entity.User): Double {
        val zoneId = sharedSupport.resolveTimeZone(user.id)
        val today = AppTime.currentUserDate(zoneId)
        val (lastWeekStart, _) = AppTime.utcRangeForLocalDate(today.minusWeeks(1), zoneId)
        val (lastWeekEnd, _) = AppTime.utcRangeForLocalDate(today, zoneId)

        val lastWeekSessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            lastWeekStart,
            lastWeekEnd
        )

        return if (lastWeekSessions.isNotEmpty()) {
            lastWeekSessions.sumOf { it.totalVolume ?: 0.0 } / lastWeekSessions.size
        } else 0.0
    }

    private fun localizedAchievementName(achievement: Achievement, locale: String): String {
        val code = achievement.code ?: return achievement.name
        return if (WorkoutAchievementCatalog.contains(code)) {
            WorkoutAchievementCatalog.name(code, locale)
        } else {
            achievement.name
        }
    }

    private fun localizedAchievementDescription(achievement: Achievement, locale: String): String {
        val code = achievement.code ?: return achievement.description
        return if (WorkoutAchievementCatalog.contains(code)) {
            WorkoutAchievementCatalog.description(code, locale)
        } else {
            achievement.description
        }
    }
}

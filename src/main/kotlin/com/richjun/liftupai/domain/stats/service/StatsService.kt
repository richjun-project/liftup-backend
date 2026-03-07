package com.richjun.liftupai.domain.stats.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.stats.dto.*
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
@Transactional(readOnly = true)
class StatsService(
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository
) {

    fun getOverview(userId: Long, period: String): StatsOverviewResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = resolveTimeZone(userId)
        val (startDate, endDate) = getDateRange(period, zoneId)

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)
            .filter { it.status == SessionStatus.COMPLETED }

        val totalWorkouts = sessions.size
        val totalDuration = sessions.sumOf { it.duration ?: 0 }
        val totalVolume = sessions.sumOf { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).sumOf { workoutExercise ->
                workoutExercise.sets.filter { it.completed }.sumOf { set -> set.weight * set.reps }
            }
        }

        return StatsOverviewResponse(
            totalWorkouts = totalWorkouts,
            totalDuration = totalDuration,
            totalVolume = totalVolume,
            averageDuration = if (totalWorkouts > 0) totalDuration / totalWorkouts else 0,
            streak = calculateStreak(user, zoneId)
        )
    }

    fun getVolumeStats(userId: Long, period: String, startDate: String?): VolumeStatsResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = resolveTimeZone(userId)

        val start = if (startDate != null) {
            AppTime.utcRangeForLocalDate(LocalDate.parse(startDate), zoneId).first
        } else {
            AppTime.utcNow().minusWeeks(1)
        }

        val end = when (period) {
            "week" -> if (startDate != null) AppTime.toUtc(AppTime.toUserLocalDateTime(start, zoneId).plusWeeks(1), zoneId) else AppTime.utcNow()
            "month" -> if (startDate != null) AppTime.toUtc(AppTime.toUserLocalDateTime(start, zoneId).plusMonths(1), zoneId) else AppTime.utcNow()
            else -> if (startDate != null) AppTime.toUtc(AppTime.toUserLocalDateTime(start, zoneId).plusWeeks(1), zoneId) else AppTime.utcNow()
        }

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, start, end)
            .filter { it.status == SessionStatus.COMPLETED }

        val volumeByDate = sessions.groupBy { AppTime.toUserLocalDate(it.startTime, zoneId) }
            .map { (date, dateSessions) ->
                val dayVolume = dateSessions.sumOf { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).sumOf { workoutExercise ->
                        workoutExercise.sets.filter { it.completed }.sumOf { set -> set.weight * set.reps }
                    }
                }

                VolumeDataPoint(
                    date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    volume = dayVolume
                )
            }
            .sortedBy { it.date }

        return VolumeStatsResponse(data = volumeByDate)
    }

    fun getMuscleDistribution(userId: Long, period: String): MuscleDistributionResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = resolveTimeZone(userId)
        val (startDate, endDate) = getDateRange(period, zoneId)

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)
            .filter { it.status == SessionStatus.COMPLETED }

        val muscleGroups = mutableMapOf<String, Int>()

        sessions.forEach { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).forEach { workoutExercise ->
                val exercise = workoutExercise.exercise

                if (exercise.muscleGroups.isNotEmpty()) {
                    exercise.muscleGroups.forEach { muscleGroup ->
                        val name = WorkoutLocalization.muscleGroupName(muscleGroup, locale)
                        muscleGroups[name] = muscleGroups.getOrDefault(name, 0) + 1
                    }
                } else {
                    val name = WorkoutLocalization.targetDisplayName(exercise.category.name, locale)
                    muscleGroups[name] = muscleGroups.getOrDefault(name, 0) + 1
                }
            }
        }

        val total = muscleGroups.values.sum().toDouble()
        val distribution = muscleGroups.map { (muscle, count) ->
            MuscleDistribution(
                muscleGroup = muscle,
                percentage = if (total > 0) count / total * 100 else 0.0,
                sessions = count
            )
        }.sortedByDescending { it.percentage }

        return MuscleDistributionResponse(distribution = distribution)
    }

    fun getPersonalRecords(userId: Long): PersonalRecordsResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = resolveTimeZone(userId)

        val allSessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            AppTime.utcNow().minusYears(1),
            AppTime.utcNow()
        ).filter { it.status == SessionStatus.COMPLETED }

        val recordsByExercise = mutableMapOf<Long, PersonalRecord>()

        allSessions.forEach { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).forEach { workoutExercise ->
                val exerciseId = workoutExercise.exercise.id

                workoutExercise.sets.filter { it.completed }.forEach { set ->
                    val current = recordsByExercise[exerciseId]
                    if (current == null || set.weight > current.weight) {
                        recordsByExercise[exerciseId] = PersonalRecord(
                            exerciseId = exerciseId,
                            exerciseName = workoutExercise.exercise.name,
                            weight = set.weight,
                            reps = set.reps,
                            date = AppTime.toUserLocalDate(session.startTime, zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        )
                    }
                }
            }
        }

        return PersonalRecordsResponse(records = recordsByExercise.values.toList())
    }

    fun getProgress(userId: Long, metric: String, period: String): ProgressResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = resolveTimeZone(userId)

        val months = when (period) {
            "3months" -> 3L
            "6months" -> 6L
            "year" -> 12L
            else -> 3L
        }

        val startDate = AppTime.toUtc(AppTime.toUserLocalDateTime(AppTime.utcNow(), zoneId).minusMonths(months), zoneId)
        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, AppTime.utcNow())
            .filter { it.status == SessionStatus.COMPLETED }

        val progress = when (metric) {
            "weight" -> calculateWeightProgress(sessions, zoneId)
            "volume" -> calculateVolumeProgress(sessions, zoneId)
            "strength" -> calculateStrengthProgress(sessions, zoneId)
            else -> emptyList()
        }

        return ProgressResponse(progress = progress)
    }

    private fun getDateRange(period: String, zoneId: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val now = AppTime.toUserLocalDateTime(AppTime.utcNow(), zoneId)
        val startDate = when (period) {
            "week" -> AppTime.toUtc(now.minusWeeks(1), zoneId)
            "month" -> AppTime.toUtc(now.minusMonths(1), zoneId)
            "year" -> AppTime.toUtc(now.minusYears(1), zoneId)
            else -> AppTime.toUtc(now.minusWeeks(1), zoneId)
        }

        return startDate to AppTime.utcNow()
    }

    private fun calculateStreak(user: com.richjun.liftupai.domain.auth.entity.User, zoneId: ZoneId): Int {
        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            AppTime.utcNow().minusMonths(1),
            AppTime.utcNow()
        ).filter { it.status == SessionStatus.COMPLETED }

        if (sessions.isEmpty()) {
            return 0
        }

        val workoutDays = sessions.map { AppTime.toUserLocalDate(it.startTime, zoneId) }.toSet()
        var streak = 0
        var currentDate = AppTime.currentUserDate(zoneId)

        if (!workoutDays.contains(currentDate)) {
            currentDate = currentDate.minusDays(1)
        }

        while (workoutDays.contains(currentDate)) {
            streak++
            currentDate = currentDate.minusDays(1)
        }

        return streak
    }

    private fun calculateWeightProgress(
        sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>,
        zoneId: ZoneId
    ): List<ProgressDataPoint> {
        val points = sessions.groupBy { AppTime.toUserLocalDate(it.startTime, zoneId).withDayOfMonth(1) }
            .map { (month, monthlySessions) ->
                val allSets = monthlySessions.flatMap { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                        .flatMap { workoutExercise -> workoutExercise.sets.filter { set -> set.completed } }
                }

                ProgressDataPoint(
                    date = month.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    value = allSets.map { it.weight }.average().takeUnless { it.isNaN() } ?: 0.0,
                    change = 0.0
                )
            }
            .sortedBy { it.date }
            .toMutableList()

        for (i in 1 until points.size) {
            val prevValue = points[i - 1].value
            val currentValue = points[i].value
            val changePercent = if (prevValue != 0.0) ((currentValue - prevValue) / prevValue * 100) else 0.0
            points[i] = points[i].copy(change = changePercent)
        }

        return points
    }

    private fun calculateVolumeProgress(
        sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>,
        zoneId: ZoneId
    ): List<ProgressDataPoint> {
        val points = sessions.groupBy { AppTime.toUserLocalDate(it.startTime, zoneId).withDayOfMonth(1) }
            .map { (month, monthlySessions) ->
                val totalVolume = monthlySessions.sumOf { session ->
                    workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).sumOf { workoutExercise ->
                        workoutExercise.sets.filter { it.completed }.sumOf { set -> set.weight * set.reps }
                    }
                }

                ProgressDataPoint(
                    date = month.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    value = totalVolume,
                    change = 0.0
                )
            }
            .sortedBy { it.date }
            .toMutableList()

        for (i in 1 until points.size) {
            val prevValue = points[i - 1].value
            val currentValue = points[i].value
            val changePercent = if (prevValue != 0.0) ((currentValue - prevValue) / prevValue * 100) else 0.0
            points[i] = points[i].copy(change = changePercent)
        }

        return points
    }

    private fun calculateStrengthProgress(
        sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>,
        zoneId: ZoneId
    ): List<ProgressDataPoint> {
        return calculateWeightProgress(sessions, zoneId)
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }

    private fun resolveTimeZone(userId: Long): ZoneId {
        return AppTime.resolveZoneId(userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone)
    }
}

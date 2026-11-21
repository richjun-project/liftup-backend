package com.richjun.liftupai.domain.stats.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.stats.dto.*
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogLocalizationService
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
    private val exerciseSetRepository: ExerciseSetRepository,
    private val exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService
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

        val (muscles, groups) = muscleSets(sessions)
        return MuscleDistributionResponse(distribution = distribution, muscles = muscles, groups = groups)
    }

    /**
     * 완료한 세트를 부위별로 센다(2026-11-21 통계 디자인 A — 몸 지도 색·부위별 세트 막대).
     * 운동 분류(category)에 속한 부위는 주 부위 1세트, 나머지 부위는 보조 0.5세트.
     * 묶음은 한 세트가 그 묶음의 여러 부위에 걸쳐도 가장 큰 값 한 번만 센다(스쿼트 1세트 = 하체 1세트, 3.5세트가 아니다).
     */
    internal fun muscleSets(sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): Pair<List<MuscleSets>, List<MuscleSets>> {
        val perMuscle = mutableMapOf<MuscleGroup, Double>()
        val perGroup = mutableMapOf<String, Double>()
        sessions.forEach { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).forEach { we ->
                val done = we.sets.count { it.completed }
                if (done == 0) return@forEach
                val credit = we.exercise.muscleGroups.associateWith { m -> if (m in PRIMARY[we.exercise.category].orEmpty()) 1.0 else 0.5 }
                // 같은 부위의 다른 이름(BACK·LATS, LEGS·QUADRICEPS, CORE·ABS)은 한 부위로 — 한 세트를 두 번 세지 않는다
                credit.entries.groupBy { CANONICAL[it.key] ?: it.key }.forEach { (m, es) -> perMuscle.merge(m, es.maxOf { it.value } * done, Double::plus) }
                credit.entries.groupBy { GROUP_OF[it.key] }.forEach { (g, es) ->
                    if (g != null) perGroup.merge(g, es.maxOf { it.value } * done, Double::plus)
                }
            }
        }
        return perMuscle.map { MuscleSets(it.key.name, it.value) }.sortedByDescending { it.sets } to
            GROUP_ORDER.map { MuscleSets(it, perGroup[it] ?: 0.0) }
    }

    companion object {
        private val PRIMARY: Map<ExerciseCategory, Set<MuscleGroup>> = mapOf(
            ExerciseCategory.CHEST to setOf(MuscleGroup.CHEST),
            ExerciseCategory.BACK to setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.TRAPS),
            ExerciseCategory.SHOULDERS to setOf(MuscleGroup.SHOULDERS),
            ExerciseCategory.LEGS to setOf(MuscleGroup.LEGS, MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES),
            ExerciseCategory.ARMS to setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS),
            ExerciseCategory.CORE to setOf(MuscleGroup.CORE, MuscleGroup.ABS),
            ExerciseCategory.FULL_BODY to MuscleGroup.entries.toSet(),
        )
        val GROUP_ORDER = listOf("chest", "back", "shoulders", "legs", "arms", "core")
        /** 몸 지도에서 같은 자리인 이름들 — 앱 지도 13부위와 맞춘다 */
        private val CANONICAL: Map<MuscleGroup, MuscleGroup> = mapOf(
            MuscleGroup.BACK to MuscleGroup.LATS, MuscleGroup.LEGS to MuscleGroup.QUADRICEPS, MuscleGroup.CORE to MuscleGroup.ABS,
        )
        private val GROUP_OF: Map<MuscleGroup, String> = mapOf(
            MuscleGroup.CHEST to "chest",
            MuscleGroup.BACK to "back", MuscleGroup.LATS to "back", MuscleGroup.TRAPS to "back", MuscleGroup.NECK to "back",
            MuscleGroup.SHOULDERS to "shoulders",
            MuscleGroup.LEGS to "legs", MuscleGroup.QUADRICEPS to "legs", MuscleGroup.HAMSTRINGS to "legs", MuscleGroup.GLUTES to "legs", MuscleGroup.CALVES to "legs",
            MuscleGroup.BICEPS to "arms", MuscleGroup.TRICEPS to "arms", MuscleGroup.FOREARMS to "arms",
            MuscleGroup.CORE to "core", MuscleGroup.ABS to "core",
        )
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
        val exerciseEntities = mutableMapOf<Long, com.richjun.liftupai.domain.workout.entity.Exercise>()

        allSessions.forEach { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).forEach { workoutExercise ->
                val exerciseId = workoutExercise.exercise.id
                exerciseEntities.putIfAbsent(exerciseId, workoutExercise.exercise)

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

        // 운동 이름을 사용자 locale에 맞게 번역
        val translations = exerciseCatalogLocalizationService.translationMap(exerciseEntities.values, locale)
        val localizedRecords = recordsByExercise.values.map { record ->
            val exercise = exerciseEntities[record.exerciseId]
            if (exercise != null) {
                record.copy(exerciseName = exerciseCatalogLocalizationService.displayName(exercise, locale, translations))
            } else {
                record
            }
        }

        return PersonalRecordsResponse(records = localizedRecords)
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

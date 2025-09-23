package com.richjun.liftupai.domain.stats.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.stats.dto.*
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Service
@Transactional(readOnly = true)
class StatsService(
    private val userRepository: UserRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository
) {

    fun getOverview(userId: Long, period: String): StatsOverviewResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val (startDate, endDate) = getDateRange(period)
        println("DEBUG StatsService.getOverview - userId: $userId, period: $period")
        println("DEBUG StatsService.getOverview - dateRange: $startDate to $endDate")

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user, startDate, endDate
        ).filter { it.status == SessionStatus.COMPLETED }
        println("DEBUG StatsService.getOverview - found ${sessions.size} completed sessions")

        val totalWorkouts = sessions.size
        val totalDuration = sessions.sumOf { it.duration ?: 0 }
        val totalVolume = sessions.sumOf { session ->
            // WorkoutExercise를 통해 ExerciseSet 조회
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            println("DEBUG StatsService.getOverview - session ${session.id}: found ${workoutExercises.size} exercises")

            val sessionVolume = workoutExercises.sumOf { workoutExercise ->
                val completedSets = workoutExercise.sets.filter { it.completed }
                println("DEBUG StatsService.getOverview - exercise ${workoutExercise.exercise.name}: ${completedSets.size} completed sets out of ${workoutExercise.sets.size}")

                val exerciseVolume = completedSets.sumOf { set ->
                    val setVolume = set.weight * set.reps
                    println("DEBUG StatsService.getOverview - set: ${set.weight}kg x ${set.reps}reps = ${setVolume}kg")
                    setVolume
                }
                println("DEBUG StatsService.getOverview - exercise total volume: ${exerciseVolume}kg")
                exerciseVolume
            }
            println("DEBUG StatsService.getOverview - session ${session.id} total volume: ${sessionVolume}kg")
            sessionVolume
        }
        val averageDuration = if (totalWorkouts > 0) totalDuration / totalWorkouts else 0
        val streak = calculateStreak(user)

        println("DEBUG StatsService.getOverview - FINAL: totalWorkouts=$totalWorkouts, totalDuration=$totalDuration, totalVolume=$totalVolume, avgDuration=$averageDuration, streak=$streak")

        return StatsOverviewResponse(
            totalWorkouts = totalWorkouts,
            totalDuration = totalDuration,
            totalVolume = totalVolume,
            averageDuration = averageDuration,
            streak = streak
        )
    }

    fun getVolumeStats(userId: Long, period: String, startDate: String?): VolumeStatsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val start = if (startDate != null) {
            LocalDate.parse(startDate).atStartOfDay()
        } else {
            LocalDateTime.now().minusWeeks(1)
        }

        val end = when (period) {
            "week" -> start.plusWeeks(1)
            "month" -> start.plusMonths(1)
            else -> start.plusWeeks(1)
        }

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, start, end)
            .filter { it.status == SessionStatus.COMPLETED }

        val volumeByDate = sessions.groupBy { it.startTime.toLocalDate() }
            .map { (date, sessions) ->
                val dayVolume = sessions.sumOf { session ->
                    val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    workoutExercises.sumOf { workoutExercise ->
                        workoutExercise.sets.filter { it.completed }.sumOf { set ->
                            set.weight * set.reps
                        }
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
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val (startDate, endDate) = getDateRange(period)
        println("DEBUG StatsService.getMuscleDistribution - userId: $userId, period: $period")
        println("DEBUG StatsService.getMuscleDistribution - dateRange: $startDate to $endDate")

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user, startDate, endDate
        ).filter { it.status == SessionStatus.COMPLETED }
        println("DEBUG StatsService.getMuscleDistribution - found ${sessions.size} completed sessions")

        val muscleGroups = mutableMapOf<String, Int>()
        sessions.forEach { session ->
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            println("DEBUG StatsService.getMuscleDistribution - session ${session.id}: found ${workoutExercises.size} exercises")

            workoutExercises.forEach { workoutExercise ->
                val exercise = workoutExercise.exercise
                println("DEBUG StatsService.getMuscleDistribution - exercise: ${exercise.name}, muscleGroups: ${exercise.muscleGroups.map { it.name }}")

                // muscleGroups Set 확인
                if (exercise.muscleGroups.isNotEmpty()) {
                    exercise.muscleGroups.forEach { muscleEnum ->
                        val muscleName = mapMuscleGroupToKorean(muscleEnum.name)
                        muscleGroups[muscleName] = muscleGroups.getOrDefault(muscleName, 0) + 1
                        println("DEBUG StatsService.getMuscleDistribution - added muscle: $muscleName")
                    }
                } else {
                    // muscleGroups가 없으면 카테고리 기반으로 설정
                    val categoryMuscle = mapCategoryToMuscleGroup(exercise.category.name)
                    muscleGroups[categoryMuscle] = muscleGroups.getOrDefault(categoryMuscle, 0) + 1
                    println("DEBUG StatsService.getMuscleDistribution - no muscles, using category: ${exercise.category.name} -> $categoryMuscle")
                }
            }
        }
        println("DEBUG StatsService.getMuscleDistribution - final muscle groups: $muscleGroups")

        val total = muscleGroups.values.sum().toDouble()
        val distribution = muscleGroups.map { (muscle, count) ->
            MuscleDistribution(
                muscleGroup = muscle,
                percentage = if (total > 0) (count / total * 100) else 0.0,
                sessions = count
            )
        }.sortedByDescending { it.percentage }

        return MuscleDistributionResponse(distribution = distribution)
    }

    private fun mapMuscleGroupToKorean(muscleEnum: String): String {
        // Flutter 프론트엔드와 일치하는 16개 근육 그룹 매핑
        return when (muscleEnum) {
            "CHEST" -> "가슴"
            "BACK" -> "등"
            "SHOULDERS" -> "어깨"
            "BICEPS" -> "이두근"
            "TRICEPS" -> "삼두근"
            "LEGS" -> "다리"
            "CORE" -> "코어"
            "ABS" -> "복근"
            "GLUTES" -> "둔근"
            "CALVES" -> "종아리"
            "FOREARMS" -> "전완근"
            "NECK" -> "목"
            "QUADRICEPS" -> "대퇴사두근"
            "HAMSTRINGS" -> "햄스트링"
            "LATS" -> "광배근"
            "TRAPS" -> "승모근"
            else -> muscleEnum
        }
    }

    private fun mapCategoryToMuscleGroup(category: String): String {
        return when (category) {
            "CHEST" -> "가슴"
            "BACK" -> "등"
            "LEGS" -> "하체"
            "SHOULDERS" -> "어깨"
            "ARMS" -> "팔"
            "CORE" -> "복근"
            "CARDIO" -> "유산소"
            "FULL_BODY" -> "전신"
            else -> "기타"
        }
    }

    fun getPersonalRecords(userId: Long): PersonalRecordsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val allSessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            LocalDateTime.now().minusYears(1),
            LocalDateTime.now()
        ).filter { it.status == SessionStatus.COMPLETED }

        val recordsByExercise = mutableMapOf<Long, PersonalRecord>()

        allSessions.forEach { session ->
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            workoutExercises.forEach { workoutExercise ->
                val exercise = workoutExercise.exercise
                val exerciseId = exercise.id

                workoutExercise.sets.filter { it.completed }.forEach { set ->
                    val current = recordsByExercise[exerciseId]
                    val weight = set.weight

                    if (current == null || weight > current.weight) {
                        recordsByExercise[exerciseId] = PersonalRecord(
                            exerciseId = exerciseId,
                            exerciseName = exercise.name,
                            weight = weight,
                            reps = set.reps,
                            date = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        )
                    }
                }
            }
        }

        return PersonalRecordsResponse(records = recordsByExercise.values.toList())
    }

    fun getProgress(userId: Long, metric: String, period: String): ProgressResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val months = when (period) {
            "3months" -> 3L
            "6months" -> 6L
            "year" -> 12L
            else -> 3L
        }

        val startDate = LocalDateTime.now().minusMonths(months)
        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user, startDate, LocalDateTime.now()
        ).filter { it.status == SessionStatus.COMPLETED }

        val progress = when (metric) {
            "weight" -> calculateWeightProgress(sessions)
            "volume" -> calculateVolumeProgress(sessions)
            "strength" -> calculateStrengthProgress(sessions)
            else -> emptyList()
        }

        return ProgressResponse(progress = progress)
    }

    private fun getDateRange(period: String): Pair<LocalDateTime, LocalDateTime> {
        val now = LocalDateTime.now()
        val startDate = when (period) {
            "week" -> now.minusWeeks(1)
            "month" -> now.minusMonths(1)
            "year" -> now.minusYears(1)
            else -> now.minusWeeks(1)
        }
        return Pair(startDate, now)
    }

    private fun calculateStreak(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            LocalDateTime.now().minusMonths(1),
            LocalDateTime.now()
        ).filter { it.status == SessionStatus.COMPLETED }
            .sortedByDescending { it.startTime }

        if (sessions.isEmpty()) return 0

        var streak = 1
        var lastDate = sessions.first().startTime.toLocalDate()

        for (i in 1 until sessions.size) {
            val currentDate = sessions[i].startTime.toLocalDate()
            val daysBetween = ChronoUnit.DAYS.between(currentDate, lastDate)

            if (daysBetween <= 1) {
                if (daysBetween == 1L) streak++
                lastDate = currentDate
            } else {
                break
            }
        }

        return streak
    }

    private fun calculateWeightProgress(sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): List<ProgressDataPoint> {
        val points = sessions.groupBy { it.startTime.toLocalDate().withDayOfMonth(1) }
            .map { (month, monthlySessions) ->
                val allSets = monthlySessions.flatMap { session ->
                    val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    workoutExercises.flatMap { it.sets.filter { set -> set.completed } }
                }
                val avgWeight = if (allSets.isNotEmpty()) {
                    allSets.map { it.weight }.average()
                } else 0.0

                ProgressDataPoint(
                    date = month.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    value = avgWeight,
                    change = 0.0
                )
            }
            .sortedBy { it.date }
            .toMutableList()

        for (i in 1 until points.size) {
            val prevValue = points[i - 1].value
            val currentValue = points[i].value
            val changePercent = if (prevValue != 0.0) {
                ((currentValue - prevValue) / prevValue * 100)
            } else 0.0

            points[i] = points[i].copy(change = changePercent)
        }

        return points
    }

    private fun calculateVolumeProgress(sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): List<ProgressDataPoint> {
        val points = sessions.groupBy { it.startTime.toLocalDate().withDayOfMonth(1) }
            .map { (month, monthlySessions) ->
                val totalVolume = monthlySessions.sumOf { session ->
                    val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    workoutExercises.sumOf { workoutExercise ->
                        workoutExercise.sets.filter { it.completed }.sumOf { set ->
                            set.weight * set.reps
                        }
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
            val changePercent = if (prevValue != 0.0) {
                ((currentValue - prevValue) / prevValue * 100)
            } else 0.0

            points[i] = points[i].copy(change = changePercent)
        }

        return points
    }

    private fun calculateStrengthProgress(sessions: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): List<ProgressDataPoint> {
        return calculateWeightProgress(sessions)
    }
}
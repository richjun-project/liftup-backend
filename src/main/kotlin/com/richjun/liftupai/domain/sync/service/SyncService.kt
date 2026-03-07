package com.richjun.liftupai.domain.sync.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.sync.dto.*
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.WorkoutLog
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutLogRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional
class SyncService(
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutLogRepository: WorkoutLogRepository,
    private val exerciseRepository: ExerciseRepository
) {

    fun syncOfflineWorkouts(userId: Long, request: OfflineWorkoutSyncRequest): OfflineWorkoutSyncResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }
        val zoneId = AppTime.resolveZoneId(request.timeZone ?: resolveTimeZone(userId))

        var syncedCount = 0
        var failedCount = 0
        val conflicts = mutableListOf<SyncConflict>()

        request.workouts.forEach { offlineWorkout ->
            try {
                val workoutDate = LocalDate.parse(offlineWorkout.date)
                val (startTime, endOfDay) = AppTime.utcRangeForLocalDate(workoutDate, zoneId)

                val existingSession = workoutSessionRepository.findByUserAndStartTimeBetween(
                    user,
                    startTime,
                    endOfDay
                ).firstOrNull()

                if (existingSession != null) {
                    conflicts.add(
                        SyncConflict(
                            localId = offlineWorkout.localId,
                            reason = NotificationLocalization.message("sync.conflict.same_day", locale),
                            resolution = NotificationLocalization.message("sync.conflict.keep_server", locale)
                        )
                    )
                    failedCount++
                } else {
                    val session = WorkoutSession(
                        user = user,
                        name = NotificationLocalization.message("sync.offline_workout_name", locale),
                        startTime = startTime,
                        endTime = startTime.plusSeconds(offlineWorkout.duration.toLong()),
                        isActive = false,
                        syncedFromOffline = true
                    )

                    val savedSession = workoutSessionRepository.save(session)

                    offlineWorkout.exercises.forEach { offlineExercise ->
                        val exercise = exerciseRepository.findById(offlineExercise.exerciseId)
                            .orElseThrow {
                                ResourceNotFoundException(
                                    NotificationLocalization.message("sync.exercise_not_found", locale, offlineExercise.exerciseId)
                                )
                            }

                        offlineExercise.sets.forEachIndexed { index, offlineSet ->
                            val log = WorkoutLog(
                                session = savedSession,
                                exercise = exercise,
                                setNumber = index + 1,
                                weight = offlineSet.weight,
                                reps = offlineSet.reps,
                                restTime = offlineSet.restTime,
                                timestamp = startTime.plusMinutes((index * 2).toLong())
                            )
                            workoutLogRepository.save(log)
                        }
                    }

                    syncedCount++
                }
            } catch (e: Exception) {
                conflicts.add(
                    SyncConflict(
                        localId = offlineWorkout.localId,
                        reason = NotificationLocalization.message("sync.error", locale, e.message ?: "unknown"),
                        resolution = NotificationLocalization.message("sync.retry_later", locale)
                    )
                )
                failedCount++
            }
        }

        return OfflineWorkoutSyncResponse(
            synced = syncedCount,
            failed = failedCount,
            conflicts = conflicts,
            serverTime = AppTime.formatUtcRequired(AppTime.utcNow())
        )
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }

    private fun resolveTimeZone(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone ?: AppTime.DEFAULT_TIME_ZONE
    }
}

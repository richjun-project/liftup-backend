package com.richjun.liftupai.domain.sync.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.sync.dto.*
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.WorkoutLog
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutLogRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
@Transactional
class SyncService(
    private val userRepository: UserRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutLogRepository: WorkoutLogRepository,
    private val exerciseRepository: ExerciseRepository
) {

    fun syncOfflineWorkouts(userId: Long, request: OfflineWorkoutSyncRequest): OfflineWorkoutSyncResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        var syncedCount = 0
        var failedCount = 0
        val conflicts = mutableListOf<SyncConflict>()

        request.workouts.forEach { offlineWorkout ->
            try {
                val workoutDate = LocalDate.parse(offlineWorkout.date)
                val startTime = workoutDate.atStartOfDay()

                val existingSession = workoutSessionRepository.findByUserAndStartTimeBetween(
                    user,
                    startTime,
                    startTime.plusDays(1)
                ).firstOrNull()

                if (existingSession != null) {
                    conflicts.add(
                        SyncConflict(
                            localId = offlineWorkout.localId,
                            reason = "이미 같은 날짜에 운동 세션이 존재합니다",
                            resolution = "서버 데이터를 유지합니다"
                        )
                    )
                    failedCount++
                } else {
                    val session = WorkoutSession(
                        user = user,
                        name = "오프라인 운동",
                        startTime = startTime,
                        endTime = startTime.plusSeconds(offlineWorkout.duration.toLong()),
                        isActive = false,
                        syncedFromOffline = true
                    )

                    val savedSession = workoutSessionRepository.save(session)

                    offlineWorkout.exercises.forEach { offlineExercise ->
                        val exercise = exerciseRepository.findById(offlineExercise.exerciseId)
                            .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다: ${offlineExercise.exerciseId}") }

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
                        reason = "동기화 중 오류 발생: ${e.message}",
                        resolution = "나중에 다시 시도하세요"
                    )
                )
                failedCount++
            }
        }

        return OfflineWorkoutSyncResponse(
            synced = syncedCount,
            failed = failedCount,
            conflicts = conflicts,
            serverTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }
}
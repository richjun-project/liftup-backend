package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class WorkoutProgressTracker(
    private val workoutSessionRepository: WorkoutSessionRepository
) {

    fun getNextWorkoutInProgram(user: User, programDays: Int): WorkoutProgramPosition {
        // 최근 운동 세션 조회 (시작한 것도 포함)
        val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS, SessionStatus.CANCELLED)
        )

        // 오늘 날짜 확인
        val todayStart = LocalDateTime.now().toLocalDate().atStartOfDay()

        // 오늘 이미 수행한 프로그램 세션 확인 (첫 번째 것만 인정)
        val todayProgramSession = recentSessions
            .filter { it.startTime >= todayStart && it.programDay != null }
            .minByOrNull { it.startTime }  // 오늘 중 가장 이른 세션

        // 오늘 이미 프로그램 운동을 했으면 같은 회차와 사이클 반환
        if (todayProgramSession != null) {
            return WorkoutProgramPosition(
                day = todayProgramSession.programDay!!,
                cycle = todayProgramSession.programCycle ?: 1,
                isNewCycle = false
            )
        }

        // 프로그램 일차가 기록된 가장 최근 세션 찾기 (오늘 이전)
        val lastProgramSession = recentSessions.firstOrNull {
            it.programDay != null && it.startTime < todayStart
        }

        return if (lastProgramSession == null) {
            // 첫 운동 시작
            WorkoutProgramPosition(
                day = 1,
                cycle = 1,
                isNewCycle = true
            )
        } else {
            // 현재 사이클 확인 (null이면 1로 초기화)
            val currentCycle = lastProgramSession.programCycle ?: 1

            // 다음 회차 계산 (요일과 무관하게 순서만 관리)
            val nextDay = if (lastProgramSession.programDay!! >= programDays) {
                1  // 사이클 완료, 다시 1회차로
            } else {
                lastProgramSession.programDay!! + 1  // 다음 회차로
            }

            val nextCycle = if (nextDay == 1 && lastProgramSession.programDay!! >= programDays) {
                // 실제로 프로그램을 완료하고 새 사이클로 가는 경우만 증가
                currentCycle + 1
            } else {
                // 그 외의 경우는 현재 사이클 유지
                currentCycle
            }

            WorkoutProgramPosition(
                day = nextDay,
                cycle = nextCycle,
                isNewCycle = nextDay == 1 && lastProgramSession.programDay!! >= programDays
            )
        }
    }

    fun getLastWorkoutType(user: User): WorkoutType? {
        val recentSession = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS)
        ).firstOrNull()

        return recentSession?.workoutType
    }

    fun hasStartedWorkoutToday(user: User): Boolean {
        val todayStart = LocalDateTime.now().toLocalDate().atStartOfDay()

        return workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.IN_PROGRESS)
            .any { it.startTime >= todayStart } ||
        workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
            .any { it.startTime >= todayStart }
    }

    fun getWorkoutTypeSequence(programType: String): List<WorkoutType> {
        return when (programType.uppercase()) {
            "PPL", "PUSH_PULL_LEGS" -> listOf(WorkoutType.PUSH, WorkoutType.PULL, WorkoutType.LEGS)
            "UPPER_LOWER", "UPPER/LOWER" -> listOf(WorkoutType.UPPER, WorkoutType.LOWER, WorkoutType.UPPER, WorkoutType.LOWER)
            "FULL_BODY", "FULL" -> listOf(WorkoutType.FULL_BODY, WorkoutType.FULL_BODY, WorkoutType.FULL_BODY)
            "BRO_SPLIT", "5_SPLIT", "5-SPLIT" -> listOf(
                WorkoutType.CHEST,
                WorkoutType.BACK,
                WorkoutType.SHOULDERS,
                WorkoutType.ARMS,
                WorkoutType.LEGS
            )
            "AUTO", "" -> listOf(WorkoutType.PUSH, WorkoutType.PULL, WorkoutType.LEGS)
            else -> listOf(WorkoutType.PUSH, WorkoutType.PULL, WorkoutType.LEGS)
        }
    }

    fun determineWorkoutType(targetMuscles: List<String>): WorkoutType {
        val muscleSet = targetMuscles.map { it.lowercase() }.toSet()

        return when {
            muscleSet.containsAll(listOf("가슴", "삼두", "어깨")) ||
            muscleSet.containsAll(listOf("chest", "triceps", "shoulders")) -> WorkoutType.PUSH

            muscleSet.containsAll(listOf("등", "이두")) ||
            muscleSet.containsAll(listOf("back", "biceps")) -> WorkoutType.PULL

            muscleSet.contains("하체") || muscleSet.contains("legs") ||
            muscleSet.contains("대퇴사두근") || muscleSet.contains("햄스트링") -> WorkoutType.LEGS

            muscleSet.contains("가슴") || muscleSet.contains("chest") -> WorkoutType.CHEST
            muscleSet.contains("등") || muscleSet.contains("back") -> WorkoutType.BACK
            muscleSet.contains("어깨") || muscleSet.contains("shoulders") -> WorkoutType.SHOULDERS
            muscleSet.contains("팔") || muscleSet.contains("arms") -> WorkoutType.ARMS

            muscleSet.size >= 3 -> WorkoutType.FULL_BODY

            else -> WorkoutType.FULL_BODY
        }
    }

    fun getProgramSequenceDescription(programType: String, currentDay: Int): String {
        val sequence = getWorkoutTypeSequence(programType)
        val totalDays = sequence.size
        val currentWorkout = sequence.getOrNull(currentDay - 1)

        val workoutName = when (currentWorkout) {
            WorkoutType.PUSH -> "밀기 운동 (가슴/삼두/어깨)"
            WorkoutType.PULL -> "당기기 운동 (등/이두)"
            WorkoutType.LEGS -> "하체 운동"
            WorkoutType.UPPER -> "상체 운동"
            WorkoutType.LOWER -> "하체 운동"
            WorkoutType.CHEST -> "가슴 운동"
            WorkoutType.BACK -> "등 운동"
            WorkoutType.SHOULDERS -> "어깨 운동"
            WorkoutType.ARMS -> "팔 운동"
            WorkoutType.FULL_BODY -> "전신 운동"
            else -> "운동"
        }

        return "주 ${totalDays}회 프로그램 중 ${currentDay}회차: $workoutName"
    }

    /**
     * 주어진 운동 시퀀스로부터 프로그램 설명을 생성합니다.
     * nextWorkoutType과 nextWorkoutDescription이 일치하도록 동일한 시퀀스를 사용해야 합니다.
     */
    fun getProgramSequenceDescriptionFromSequence(
        sequence: List<WorkoutType>,
        currentDay: Int,
        totalDays: Int
    ): String {
        val currentWorkout = sequence.getOrNull(currentDay - 1)

        val workoutName = when (currentWorkout) {
            WorkoutType.PUSH -> "밀기 운동 (가슴/삼두/어깨)"
            WorkoutType.PULL -> "당기기 운동 (등/이두)"
            WorkoutType.LEGS -> "하체 운동"
            WorkoutType.UPPER -> "상체 운동"
            WorkoutType.LOWER -> "하체 운동"
            WorkoutType.CHEST -> "가슴 운동"
            WorkoutType.BACK -> "등 운동"
            WorkoutType.SHOULDERS -> "어깨 운동"
            WorkoutType.ARMS -> "팔 운동"
            WorkoutType.FULL_BODY -> "전신 운동"
            else -> "운동"
        }

        return "주 ${totalDays}회 프로그램 중 ${currentDay}회차: $workoutName"
    }
}

data class WorkoutProgramPosition(
    val day: Int,
    val cycle: Int,
    val isNewCycle: Boolean
)
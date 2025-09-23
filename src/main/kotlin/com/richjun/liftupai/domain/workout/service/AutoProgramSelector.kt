package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class AutoProgramSelector(
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository
) {

    data class ProgramRecommendation(
        val programType: String,
        val workoutSequence: List<WorkoutType>,
        val reason: String,
        val confidence: Double
    )

    fun selectProgram(user: User): ProgramRecommendation {
        // 사용자 프로필 및 설정 조회
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val settings = userSettingsRepository.findByUser_Id(user.id).orElse(null)

        // 주당 운동 일수
        val weeklyDays = settings?.weeklyWorkoutDays
            ?: profile?.weeklyWorkoutDays
            ?: estimateWeeklyDaysFromHistory(user)

        // 경험 수준
        val experienceLevel = profile?.experienceLevel ?: ExperienceLevel.BEGINNER

        // 최근 운동 패턴 분석
        val recentPattern = analyzeRecentPattern(user)

        // 프로그램 선택 로직
        return when {
            // 초보자는 전신 운동으로 시작
            experienceLevel == ExperienceLevel.BEGINNER -> {
                ProgramRecommendation(
                    programType = "FULL_BODY",
                    workoutSequence = listOf(
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY
                    ),
                    reason = "초보자에게는 전신 운동이 기초 체력과 폼을 익히는데 최적입니다",
                    confidence = 0.9
                )
            }

            // 주 2-3회 운동
            weeklyDays in 2..3 -> {
                when (experienceLevel) {
                    ExperienceLevel.INTERMEDIATE, ExperienceLevel.ADVANCED -> {
                        // 중급자 이상은 밀기/당기기/하체 추천
                        ProgramRecommendation(
                            programType = "PPL",
                            workoutSequence = listOf(
                                WorkoutType.PUSH,
                                WorkoutType.PULL,
                                WorkoutType.LEGS
                            ),
                            reason = "주 ${weeklyDays}회 운동에는 밀기/당기기/하체 분할이 효율적입니다",
                            confidence = 0.85
                        )
                    }
                    else -> {
                        // 초보자는 전신
                        ProgramRecommendation(
                            programType = "FULL_BODY",
                            workoutSequence = listOf(
                                WorkoutType.FULL_BODY,
                                WorkoutType.FULL_BODY,
                                WorkoutType.FULL_BODY
                            ),
                            reason = "주 ${weeklyDays}회 운동으로 전신을 균형있게 발달시킬 수 있습니다",
                            confidence = 0.8
                        )
                    }
                }
            }

            // 주 4회 운동
            weeklyDays == 4 -> {
                ProgramRecommendation(
                    programType = "UPPER_LOWER",
                    workoutSequence = listOf(
                        WorkoutType.UPPER,
                        WorkoutType.LOWER,
                        WorkoutType.UPPER,
                        WorkoutType.LOWER
                    ),
                    reason = "주 4회 운동에는 상하체 분할이 회복과 볼륨 관리에 이상적입니다",
                    confidence = 0.9
                )
            }

            // 주 5회 이상 + 고급자
            weeklyDays >= 5 && experienceLevel == ExperienceLevel.ADVANCED -> {
                ProgramRecommendation(
                    programType = "BRO_SPLIT",
                    workoutSequence = listOf(
                        WorkoutType.CHEST,
                        WorkoutType.BACK,
                        WorkoutType.SHOULDERS,
                        WorkoutType.ARMS,
                        WorkoutType.LEGS
                    ),
                    reason = "고급자의 주 ${weeklyDays}회 운동에는 5분할이 각 부위 집중에 효과적입니다",
                    confidence = 0.85
                )
            }

            // 주 5회 이상 + 중급자
            weeklyDays >= 5 && experienceLevel == ExperienceLevel.INTERMEDIATE -> {
                // 밀기-당기기-하체-상체-하체 하이브리드
                ProgramRecommendation(
                    programType = "PPL",
                    workoutSequence = listOf(
                        WorkoutType.PUSH,
                        WorkoutType.PULL,
                        WorkoutType.LEGS
                    ),
                    reason = "주 ${weeklyDays}회 운동은 밀기/당기기/하체를 반복하며 충분한 볼륨을 확보할 수 있습니다",
                    confidence = 0.8
                )
            }

            // 불규칙한 패턴
            recentPattern.isIrregular -> {
                ProgramRecommendation(
                    programType = "FULL_BODY",
                    workoutSequence = listOf(
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY
                    ),
                    reason = "불규칙한 운동 패턴에는 전신 운동이 유연하게 대응할 수 있습니다",
                    confidence = 0.7
                )
            }

            // 기본값: 밀기/당기기/하체
            else -> {
                ProgramRecommendation(
                    programType = "PPL",
                    workoutSequence = listOf(
                        WorkoutType.PUSH,
                        WorkoutType.PULL,
                        WorkoutType.LEGS
                    ),
                    reason = "균형잡힌 근육 발달을 위한 표준 밀기/당기기/하체 프로그램입니다",
                    confidence = 0.75
                )
            }
        }
    }

    private fun estimateWeeklyDaysFromHistory(user: User): Int {
        val fourWeeksAgo = LocalDateTime.now().minusWeeks(4)
        val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS)
        ).filter { it.startTime >= fourWeeksAgo }

        if (recentSessions.isEmpty()) {
            return 3 // 기본값
        }

        // 최근 4주간 평균 운동 일수 계산
        val weeksWithWorkout = recentSessions
            .groupBy { it.startTime.toLocalDate().with(java.time.DayOfWeek.MONDAY) }
            .map { (_, sessions) -> sessions.size }

        return if (weeksWithWorkout.isNotEmpty()) {
            weeksWithWorkout.average().toInt().coerceIn(1, 7)
        } else {
            3
        }
    }

    private fun analyzeRecentPattern(user: User): WorkoutPattern {
        val twoWeeksAgo = LocalDateTime.now().minusWeeks(2)
        val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS)
        ).filter { it.startTime >= twoWeeksAgo }

        if (recentSessions.size < 3) {
            return WorkoutPattern(isIrregular = true, consistency = 0.0)
        }

        // 운동 간격 계산
        val intervals = recentSessions.sortedBy { it.startTime }
            .zipWithNext { a, b ->
                ChronoUnit.DAYS.between(a.startTime, b.startTime)
            }

        if (intervals.isEmpty()) {
            return WorkoutPattern(isIrregular = false, consistency = 1.0)
        }

        // 간격의 표준편차로 규칙성 판단
        val avgInterval = intervals.average()
        val variance = intervals.map { interval -> (interval - avgInterval) * (interval - avgInterval) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // 표준편차가 2일 이상이면 불규칙으로 판단
        val isIrregular = stdDev > 2.0
        val consistency = if (stdDev > 0) (1.0 / (1.0 + stdDev)) else 1.0

        return WorkoutPattern(isIrregular, consistency)
    }

    private data class WorkoutPattern(
        val isIrregular: Boolean,
        val consistency: Double
    )
}
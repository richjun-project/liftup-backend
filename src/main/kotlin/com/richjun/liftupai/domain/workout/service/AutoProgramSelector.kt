package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.CanonicalProgram
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class AutoProgramSelector(
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val canonicalProgramRepository: CanonicalProgramRepository
) {

    data class ProgramRecommendation(
        val programType: String,
        val workoutSequence: List<WorkoutType>,
        val reason: String,
        val confidence: Double,
        val matchedProgram: CanonicalProgram? = null
    )

    fun selectProgram(user: User): ProgramRecommendation {
        val locale = resolveLocale(user.id)

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
        val recommendation = when {
            // 초보자 또는 주 1회 이하는 전신 운동
            experienceLevel == ExperienceLevel.BEGINNER || experienceLevel == ExperienceLevel.NOVICE || weeklyDays <= 1 -> {
                ProgramRecommendation(
                    programType = "FULL_BODY",
                    workoutSequence = listOf(
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY,
                        WorkoutType.FULL_BODY
                    ),
                    reason = WorkoutLocalization.message("auto_program.reason.beginner_full_body", locale),
                    confidence = 0.9
                )
            }

            // 주 2-3회 운동
            weeklyDays in 2..3 -> {
                when (experienceLevel) {
                    ExperienceLevel.INTERMEDIATE, ExperienceLevel.ADVANCED -> {
                        // 중급자 이상도 주 2-3회는 전신 운동 (PPL은 각 근육 주 1회로 빈도 부족)
                        ProgramRecommendation(
                            programType = "FULL_BODY",
                            workoutSequence = listOf(
                                WorkoutType.FULL_BODY,
                                WorkoutType.FULL_BODY,
                                WorkoutType.FULL_BODY
                            ),
                            reason = WorkoutLocalization.message("auto_program.reason.low_frequency_full_body", locale, weeklyDays),
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
                            reason = WorkoutLocalization.message("auto_program.reason.low_frequency_full_body", locale, weeklyDays),
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
                    reason = WorkoutLocalization.message("auto_program.reason.upper_lower", locale),
                    confidence = 0.9
                )
            }

            // 주 5회 이상 + 고급자
            weeklyDays >= 5 && experienceLevel == ExperienceLevel.ADVANCED -> {
                // PPLUL 하이브리드: Bro Split 대비 상체 빈도 2x 확보
                ProgramRecommendation(
                    programType = "PPLUL",
                    workoutSequence = listOf(
                        WorkoutType.PUSH,
                        WorkoutType.PULL,
                        WorkoutType.LEGS,
                        WorkoutType.UPPER,
                        WorkoutType.LOWER
                    ),
                    reason = WorkoutLocalization.message("auto_program.reason.advanced_bro_split", locale, weeklyDays),
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
                    reason = WorkoutLocalization.message("auto_program.reason.high_frequency_ppl", locale, weeklyDays),
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
                    reason = WorkoutLocalization.message("auto_program.reason.irregular_full_body", locale),
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
                    reason = WorkoutLocalization.message("auto_program.reason.default_ppl", locale),
                    confidence = 0.75
                )
            }
        }

        // Try to match a CanonicalProgram by programType code
        val matchedProgram = canonicalProgramRepository.findByCode(recommendation.programType)

        return recommendation.copy(matchedProgram = matchedProgram)
    }

    private fun resolveLocale(userId: Long): String {
        val language = userSettingsRepository.findByUser_Id(userId)
            .orElse(null)
            ?.language

        return WorkoutLocalization.normalizeLocale(language)
    }

    private fun estimateWeeklyDaysFromHistory(user: User): Int {
        val fourWeeksAgo = AppTime.utcNow().minusWeeks(4)
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
        val twoWeeksAgo = AppTime.utcNow().minusWeeks(2)
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

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class ProgramProgressionService(
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val userProfileRepository: UserProfileRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository
) {

    /**
     * 프로그램 진급 추천 분석
     */
    fun analyzeProgression(user: User): ProgramProgressionAnalysis {
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val recentSessions = getRecentCompletedSessions(user, 20)

        if (recentSessions.isEmpty()) {
            return ProgramProgressionAnalysis(
                currentProgram = profile?.workoutSplit ?: "PPL",
                currentDaysPerWeek = profile?.weeklyWorkoutDays ?: 3,
                completedCycles = 0,
                recommendation = null,
                performanceMetrics = PerformanceMetrics(),
                readyForProgression = false
            )
        }

        val completedCycles = calculateCompletedCycles(recentSessions, profile)
        val performanceMetrics = calculatePerformanceMetrics(recentSessions)
        val consistencyRate = calculateConsistencyRate(recentSessions, profile)
        val recoveryStatus = analyzeRecoveryStatus(recentSessions)

        val recommendation = generateProgressionRecommendation(
            profile = profile,
            completedCycles = completedCycles,
            performanceMetrics = performanceMetrics,
            consistencyRate = consistencyRate,
            recoveryStatus = recoveryStatus
        )

        return ProgramProgressionAnalysis(
            currentProgram = profile?.workoutSplit ?: "PPL",
            currentDaysPerWeek = profile?.weeklyWorkoutDays ?: 3,
            completedCycles = completedCycles,
            currentCycle = recentSessions.firstOrNull()?.programCycle ?: 1,
            recommendation = recommendation,
            performanceMetrics = performanceMetrics,
            readyForProgression = recommendation != null,
            consistencyRate = consistencyRate,
            recoveryStatus = recoveryStatus
        )
    }

    /**
     * 볼륨 최적화 추천
     */
    fun optimizeVolume(user: User): VolumeOptimizationRecommendation {
        val recentSessions = getRecentCompletedSessions(user, 10)
        if (recentSessions.isEmpty()) {
            return VolumeOptimizationRecommendation(
                currentVolume = VolumeMetrics(),
                recommendedVolume = VolumeMetrics(),
                adjustmentReason = "운동 기록이 부족합니다",
                muscleGroupVolumes = emptyMap()
            )
        }

        val currentVolume = calculateCurrentVolume(recentSessions)
        val volumeTrend = analyzeVolumeTrend(recentSessions)
        val rpeAverage = calculateAverageRPE(recentSessions)

        val recommendedVolume = calculateOptimalVolume(
            current = currentVolume,
            trend = volumeTrend,
            rpe = rpeAverage
        )

        val muscleGroupVolumes = analyzeMuscleGroupVolume(recentSessions)

        return VolumeOptimizationRecommendation(
            currentVolume = currentVolume,
            recommendedVolume = recommendedVolume,
            adjustmentReason = generateVolumeAdjustmentReason(currentVolume, recommendedVolume, rpeAverage),
            muscleGroupVolumes = muscleGroupVolumes,
            mevReached = checkMEVStatus(muscleGroupVolumes),
            mavExceeded = checkMAVStatus(muscleGroupVolumes)
        )
    }

    /**
     * 회복 상태 분석
     */
    fun analyzeRecovery(user: User): RecoveryAnalysis {
        val recentSessions = getRecentCompletedSessions(user, 14)
        val muscleRecoveryMap = mutableMapOf<String, MuscleRecoveryStatusProgression>()

        val now = LocalDateTime.now()

        // 근육군별 마지막 운동 시간 계산
        val muscleLastWorkout = mutableMapOf<String, LocalDateTime>()
        recentSessions.forEach { session ->
            session.workoutType?.let { type ->
                val muscles = getMusclesFromWorkoutType(type.toString())
                muscles.forEach { muscle ->
                    val existing = muscleLastWorkout[muscle]
                    if (existing == null || session.startTime.isAfter(existing)) {
                        muscleLastWorkout[muscle] = session.startTime
                    }
                }
            }
        }

        // 각 근육군 회복 상태 계산
        muscleLastWorkout.forEach { (muscle, lastWorkout) ->
            val hoursSinceWorkout = ChronoUnit.HOURS.between(lastWorkout, now)
            val recoveryPercentage = calculateRecoveryPercentage(hoursSinceWorkout)
            val readyForNextSession = hoursSinceWorkout >= getOptimalRecoveryHours(muscle)

            muscleRecoveryMap[muscle] = MuscleRecoveryStatusProgression(
                muscleName = muscle,
                lastWorkout = lastWorkout,
                hoursSinceWorkout = hoursSinceWorkout.toInt(),
                recoveryPercentage = recoveryPercentage,
                readyForNextSession = readyForNextSession,
                recommendedRestHours = getOptimalRecoveryHours(muscle) - hoursSinceWorkout.toInt()
            )
        }

        val needsDeload = checkDeloadNeed(recentSessions)
        val overallRecovery = muscleRecoveryMap.values.map { it.recoveryPercentage }.average().roundToInt()

        return RecoveryAnalysis(
            muscleGroups = muscleRecoveryMap,
            overallRecoveryScore = overallRecovery,
            needsDeloadWeek = needsDeload,
            deloadReason = if (needsDeload) generateDeloadReason(recentSessions) else null,
            nextRecommendedMuscles = recommendNextMuscles(muscleRecoveryMap)
        )
    }

    /**
     * 프로그램 전환 타이밍 체크
     */
    fun checkProgramTransition(user: User): ProgramTransitionRecommendation {
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val recentSessions = getRecentCompletedSessions(user, 30)

        if (recentSessions.isEmpty()) {
            return ProgramTransitionRecommendation(
                shouldTransition = false,
                currentProgramWeeks = 0,
                reason = "운동 기록이 부족합니다",
                suggestedPrograms = emptyList()
            )
        }

        val programStartDate = findProgramStartDate(recentSessions)
        val weeksOnProgram = ChronoUnit.WEEKS.between(programStartDate, LocalDateTime.now()).toInt()
        val plateauDetected = detectPlateau(recentSessions)
        val goalProgress = calculateGoalProgress(profile, recentSessions)

        val shouldTransition = weeksOnProgram >= 6 || plateauDetected || goalProgress >= 90

        val suggestedPrograms = if (shouldTransition) {
            generateProgramSuggestions(profile, recentSessions)
        } else {
            emptyList()
        }

        return ProgramTransitionRecommendation(
            shouldTransition = shouldTransition,
            currentProgramWeeks = weeksOnProgram,
            plateauDetected = plateauDetected,
            reason = generateTransitionReason(weeksOnProgram, plateauDetected, goalProgress),
            suggestedPrograms = suggestedPrograms,
            goalCompletionRate = goalProgress
        )
    }

    // Helper methods

    private fun getRecentCompletedSessions(user: User, limit: Int): List<WorkoutSession> {
        return workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED)
        ).take(limit)
    }

    private fun calculateCompletedCycles(sessions: List<WorkoutSession>, profile: UserProfile?): Int {
        val programDays = profile?.weeklyWorkoutDays ?: 3
        val sessionsWithProgramDay = sessions.filter { it.programDay != null }

        if (sessionsWithProgramDay.isEmpty()) return 0

        var cycles = 0
        var lastDay = 0

        sessionsWithProgramDay.reversed().forEach { session ->
            val currentDay = session.programDay ?: 0
            if (currentDay < lastDay) {
                // 새 사이클 시작 감지
                cycles++
            }
            lastDay = currentDay
        }

        // 현재 사이클 포함
        val currentCycle = sessions.firstOrNull()?.programCycle ?: 1
        return maxOf(cycles, currentCycle - 1)
    }

    private fun calculatePerformanceMetrics(sessions: List<WorkoutSession>): PerformanceMetrics {
        if (sessions.size < 2) {
            return PerformanceMetrics()
        }

        val recentVolume = sessions.take(5).mapNotNull { it.totalVolume }.average()
        val olderVolume = sessions.drop(5).take(5).mapNotNull { it.totalVolume }.average()

        val volumeIncrease = if (olderVolume > 0) {
            ((recentVolume - olderVolume) / olderVolume * 100).roundToInt()
        } else 0

        // 예상 1RM 향상도 (실제 구현 시 운동별 세트 데이터 필요)
        val strengthGain = estimateStrengthGain(sessions)

        // 평균 운동 시간
        val avgDuration = sessions.mapNotNull { it.duration }.average().roundToInt()

        return PerformanceMetrics(
            volumeIncreasePercent = volumeIncrease,
            strengthGainPercent = strengthGain,
            averageWorkoutDuration = avgDuration,
            totalWorkouts = sessions.size
        )
    }

    private fun calculateConsistencyRate(sessions: List<WorkoutSession>, profile: UserProfile?): Double {
        if (sessions.isEmpty()) return 0.0

        val targetDaysPerWeek = profile?.weeklyWorkoutDays ?: 3
        val weeksAnalyzed = 4
        val expectedWorkouts = targetDaysPerWeek * weeksAnalyzed

        val fourWeeksAgo = LocalDateTime.now().minusWeeks(weeksAnalyzed.toLong())
        val recentWorkouts = sessions.filter { it.startTime.isAfter(fourWeeksAgo) }.size

        return (recentWorkouts.toDouble() / expectedWorkouts * 100).coerceAtMost(100.0)
    }

    private fun analyzeRecoveryStatus(sessions: List<WorkoutSession>): RecoveryStatus {
        val lastWeekSessions = sessions.filter {
            it.startTime.isAfter(LocalDateTime.now().minusWeeks(1))
        }

        val intensity = if (lastWeekSessions.size >= 5) {
            RecoveryStatus.HIGH_INTENSITY
        } else if (lastWeekSessions.size >= 3) {
            RecoveryStatus.MODERATE
        } else {
            RecoveryStatus.WELL_RECOVERED
        }

        return intensity
    }

    private fun generateProgressionRecommendation(
        profile: UserProfile?,
        completedCycles: Int,
        performanceMetrics: PerformanceMetrics,
        consistencyRate: Double,
        recoveryStatus: RecoveryStatus
    ): ProgressionRecommendation? {

        // 진급 조건 체크
        val readyForProgression = completedCycles >= 2 &&
                                  consistencyRate >= 80 &&
                                  performanceMetrics.volumeIncreasePercent >= 10 &&
                                  recoveryStatus != RecoveryStatus.HIGH_INTENSITY

        if (!readyForProgression) {
            return null
        }

        val currentDays = profile?.weeklyWorkoutDays ?: 3
        val currentProgram = profile?.workoutSplit ?: "PPL"

        return when {
            currentDays == 3 && currentProgram == "PPL" -> {
                ProgressionRecommendation(
                    newProgram = "UPPER_LOWER",
                    newDaysPerWeek = 4,
                    reason = "일관성과 볼륨 증가를 보여 4일 프로그램으로 진급 준비가 되었습니다",
                    expectedBenefits = listOf(
                        "주당 운동 빈도 증가로 더 많은 볼륨 처리 가능",
                        "근육군별 더 집중적인 트레이닝",
                        "회복 시간 최적화"
                    )
                )
            }
            currentDays == 4 && currentProgram == "UPPER_LOWER" -> {
                ProgressionRecommendation(
                    newProgram = "BRO_SPLIT",
                    newDaysPerWeek = 5,
                    reason = "충분한 운동 역량을 보여 5일 분할 프로그램 진급 가능",
                    expectedBenefits = listOf(
                        "각 근육군별 전문화된 트레이닝",
                        "최대 볼륨 처리 가능",
                        "세부 근육 발달 극대화"
                    )
                )
            }
            else -> null
        }
    }

    private fun calculateCurrentVolume(sessions: List<WorkoutSession>): VolumeMetrics {
        val totalVolume = sessions.mapNotNull { it.totalVolume }.sum()

        // 실제 세트 데이터에서 계산
        var totalSets = 0
        var totalReps = 0

        sessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                totalSets += sets.size
                totalReps += sets.sumOf { it.reps }
            }
        }

        val avgSetsPerWorkout = if (sessions.isNotEmpty()) totalSets / sessions.size else 0
        val avgRepsPerSet = if (totalSets > 0) totalReps / totalSets else 10

        return VolumeMetrics(
            weeklyVolume = totalVolume,
            setsPerWeek = totalSets,
            repsPerWeek = totalReps
        )
    }

    private fun analyzeVolumeTrend(sessions: List<WorkoutSession>): VolumeTrend {
        if (sessions.size < 4) return VolumeTrend.STABLE

        val recent = sessions.take(2).mapNotNull { it.totalVolume }.average()
        val older = sessions.drop(2).take(2).mapNotNull { it.totalVolume }.average()

        return when {
            recent > older * 1.1 -> VolumeTrend.INCREASING
            recent < older * 0.9 -> VolumeTrend.DECREASING
            else -> VolumeTrend.STABLE
        }
    }

    private fun calculateAverageRPE(sessions: List<WorkoutSession>): Double {
        // 세트별 RPE 데이터에서 평균 계산
        val rpeValues = mutableListOf<Int>()

        sessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                sets.forEach { set ->
                    set.rpe?.let { rpeValues.add(it) }
                }
            }
        }

        return if (rpeValues.isNotEmpty()) {
            rpeValues.average()
        } else {
            7.0 // 기본값
        }
    }

    private fun calculateOptimalVolume(
        current: VolumeMetrics,
        trend: VolumeTrend,
        rpe: Double
    ): VolumeMetrics {
        val multiplier = when {
            rpe < 6 && trend == VolumeTrend.STABLE -> 1.2 // 여유 있으면 20% 증가
            rpe < 7 && trend == VolumeTrend.INCREASING -> 1.1 // 적당하면 10% 증가
            rpe > 8.5 -> 0.9 // 힘들면 10% 감소
            else -> 1.0 // 유지
        }

        return VolumeMetrics(
            weeklyVolume = current.weeklyVolume * multiplier,
            setsPerWeek = (current.setsPerWeek * multiplier).roundToInt(),
            repsPerWeek = (current.repsPerWeek * multiplier).roundToInt()
        )
    }

    private fun generateVolumeAdjustmentReason(
        current: VolumeMetrics,
        recommended: VolumeMetrics,
        rpe: Double
    ): String {
        return when {
            recommended.weeklyVolume > current.weeklyVolume -> {
                "RPE가 낮고 회복이 양호하여 볼륨 증가 권장"
            }
            recommended.weeklyVolume < current.weeklyVolume -> {
                "피로도가 높아 일시적 볼륨 감소 권장"
            }
            else -> "현재 볼륨이 적절합니다"
        }
    }

    private fun analyzeMuscleGroupVolume(sessions: List<WorkoutSession>): Map<String, Int> {
        val volumeMap = mutableMapOf<String, Int>()

        sessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                val setCount = sets.size

                // 운동별 타겟 근육군 가져오기
                exercise.exercise.muscleGroups.forEach { muscleGroup ->
                    val muscleName = muscleGroup.name
                    volumeMap[muscleName] = volumeMap.getOrDefault(muscleName, 0) + setCount
                }
            }
        }

        return volumeMap
    }

    private fun getMusclesFromWorkoutType(type: String): List<String> {
        return when (type) {
            "PUSH" -> listOf("가슴", "삼두", "어깨")
            "PULL" -> listOf("등", "이두")
            "LEGS" -> listOf("대퇴", "햄스트링", "종아리")
            "UPPER" -> listOf("가슴", "등", "어깨", "팔")
            "LOWER" -> listOf("대퇴", "햄스트링", "둔근", "종아리")
            else -> listOf("전신")
        }
    }

    private fun checkMEVStatus(volumes: Map<String, Int>): Boolean {
        // 최소 효과 볼륨 체크 (근육군별 주당 10세트 기준)
        return volumes.values.all { it >= 10 }
    }

    private fun checkMAVStatus(volumes: Map<String, Int>): Boolean {
        // 최대 적응 볼륨 초과 체크 (근육군별 주당 20세트 기준)
        return volumes.values.any { it > 20 }
    }

    private fun calculateRecoveryPercentage(hoursSinceWorkout: Long): Int {
        return when {
            hoursSinceWorkout >= 72 -> 100
            hoursSinceWorkout >= 48 -> 85
            hoursSinceWorkout >= 24 -> 60
            else -> (hoursSinceWorkout * 2.5).roundToInt()
        }
    }

    private fun getOptimalRecoveryHours(muscle: String): Int {
        return when (muscle) {
            "대퇴", "햄스트링", "등" -> 72
            "가슴", "어깨" -> 48
            "팔", "종아리" -> 24
            else -> 48
        }
    }

    private fun checkDeloadNeed(sessions: List<WorkoutSession>): Boolean {
        val twoWeeksSessions = sessions.filter {
            it.startTime.isAfter(LocalDateTime.now().minusWeeks(2))
        }

        // 2주간 8회 이상 운동했으면 디로드 필요
        return twoWeeksSessions.size >= 8
    }

    private fun generateDeloadReason(sessions: List<WorkoutSession>): String {
        val recentCount = sessions.filter {
            it.startTime.isAfter(LocalDateTime.now().minusWeeks(2))
        }.size

        return "최근 2주간 $recentCount 회 운동으로 누적 피로도가 높습니다. 강도를 50% 줄인 회복 주를 권장합니다."
    }

    private fun recommendNextMuscles(recoveryMap: Map<String, MuscleRecoveryStatusProgression>): List<String> {
        return recoveryMap
            .filter { it.value.readyForNextSession }
            .map { it.key }
            .sortedByDescending { recoveryMap[it]?.recoveryPercentage ?: 0 }
            .take(3)
    }

    private fun findProgramStartDate(sessions: List<WorkoutSession>): LocalDateTime {
        // 프로그램 사이클 1, 일차 1인 세션 찾기
        return sessions
            .filter { it.programCycle == 1 && it.programDay == 1 }
            .lastOrNull()?.startTime ?: sessions.lastOrNull()?.startTime ?: LocalDateTime.now()
    }

    private fun detectPlateau(sessions: List<WorkoutSession>): Boolean {
        if (sessions.size < 6) return false

        val recentVolumes = sessions.take(3).mapNotNull { it.totalVolume }
        val olderVolumes = sessions.drop(3).take(3).mapNotNull { it.totalVolume }

        if (recentVolumes.isEmpty() || olderVolumes.isEmpty()) return false

        val recentAvg = recentVolumes.average()
        val olderAvg = olderVolumes.average()

        // 3주간 볼륨 증가가 5% 미만이면 정체
        return (recentAvg - olderAvg) / olderAvg < 0.05
    }

    private fun calculateGoalProgress(profile: UserProfile?, sessions: List<WorkoutSession>): Int {
        // 목표별 진행도 계산
        if (profile == null || sessions.isEmpty()) return 0

        val targetWorkoutsPerWeek = profile.weeklyWorkoutDays ?: 3
        val fourWeeksAgo = LocalDateTime.now().minusWeeks(4)
        val recentSessions = sessions.filter { it.startTime.isAfter(fourWeeksAgo) }

        val actualWorkoutsPerWeek = recentSessions.size.toDouble() / 4
        val consistencyProgress = (actualWorkoutsPerWeek / targetWorkoutsPerWeek * 100).coerceAtMost(100.0)

        // 볼륨 진행도 확인
        val recentVolume = recentSessions.take(5).mapNotNull { it.totalVolume }.average()
        val olderVolume = sessions.drop(10).take(5).mapNotNull { it.totalVolume }.average()
        val volumeProgress = if (olderVolume > 0) {
            ((recentVolume - olderVolume) / olderVolume * 100).coerceIn(0.0, 100.0)
        } else 0.0

        // 일관성과 볼륨 진행도의 평균
        return ((consistencyProgress + volumeProgress) / 2).roundToInt()
    }

    private fun generateTransitionReason(weeks: Int, plateau: Boolean, goalProgress: Int): String {
        return when {
            plateau -> "3주 이상 진전이 없어 새로운 자극이 필요합니다"
            weeks >= 6 -> "$weeks 주간 같은 프로그램을 수행하여 변화가 필요합니다"
            goalProgress >= 90 -> "목표 달성에 근접하여 새로운 목표 설정이 필요합니다"
            else -> "현재 프로그램을 유지하세요"
        }
    }

    private fun generateProgramSuggestions(
        profile: UserProfile?,
        sessions: List<WorkoutSession>
    ): List<ProgramSuggestion> {
        val currentProgram = profile?.workoutSplit ?: "PPL"
        val experienceLevel = profile?.experienceLevel ?: ExperienceLevel.BEGINNER

        val suggestions = mutableListOf<ProgramSuggestion>()

        // 현재 프로그램과 경험에 따른 추천
        when (currentProgram) {
            "PPL" -> {
                suggestions.add(ProgramSuggestion(
                    programName = "Upper/Lower Split",
                    daysPerWeek = 4,
                    description = "상체/하체 분할로 근육 회복 최적화",
                    benefits = listOf("균형잡힌 발달", "충분한 회복 시간"),
                    difficulty = "중급"
                ))
                suggestions.add(ProgramSuggestion(
                    programName = "PPLUL",
                    daysPerWeek = 5,
                    description = "PPL + 상체/하체 혼합 프로그램",
                    benefits = listOf("높은 빈도", "다양한 자극"),
                    difficulty = "중상급"
                ))
            }
            "UPPER_LOWER" -> {
                suggestions.add(ProgramSuggestion(
                    programName = "5-Day Bro Split",
                    daysPerWeek = 5,
                    description = "근육군별 집중 트레이닝",
                    benefits = listOf("최대 볼륨", "세밀한 발달"),
                    difficulty = "상급"
                ))
                suggestions.add(ProgramSuggestion(
                    programName = "PPL x2",
                    daysPerWeek = 6,
                    description = "주 2회 PPL 반복",
                    benefits = listOf("높은 빈도", "빠른 성장"),
                    difficulty = "상급"
                ))
            }
            else -> {
                suggestions.add(ProgramSuggestion(
                    programName = "PPL",
                    daysPerWeek = 3,
                    description = "밀기/당기기/하체 기본 분할",
                    benefits = listOf("균형잡힌 프로그램", "충분한 회복"),
                    difficulty = "초중급"
                ))
            }
        }

        return suggestions
    }

    private fun estimateStrengthGain(sessions: List<WorkoutSession>): Int {
        // 세트별 무게 데이터로 1RM 추정 및 향상도 계산
        if (sessions.size < 2) return 0

        val recentSessions = sessions.take(3)
        val olderSessions = sessions.drop(sessions.size - 3).take(3)

        // 주요 운동들의 평균 무게 계산
        val recentAvgWeights = mutableListOf<Double>()
        val olderAvgWeights = mutableListOf<Double>()

        recentSessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                if (sets.isNotEmpty()) {
                    val avgWeight = sets.map { it.weight }.average()
                    recentAvgWeights.add(avgWeight)
                }
            }
        }

        olderSessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                if (sets.isNotEmpty()) {
                    val avgWeight = sets.map { it.weight }.average()
                    olderAvgWeights.add(avgWeight)
                }
            }
        }

        if (recentAvgWeights.isEmpty() || olderAvgWeights.isEmpty()) return 0

        val recentAvg = recentAvgWeights.average()
        val olderAvg = olderAvgWeights.average()

        return if (olderAvg > 0) {
            ((recentAvg - olderAvg) / olderAvg * 100).roundToInt().coerceIn(0, 50)
        } else 0
    }
}

// Enums
enum class RecoveryStatus {
    WELL_RECOVERED,
    MODERATE,
    HIGH_INTENSITY,
    NEEDS_DELOAD
}

enum class VolumeTrend {
    INCREASING,
    STABLE,
    DECREASING
}
package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.time.AppTime
import org.springframework.data.domain.PageRequest
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
    private val userSettingsRepository: UserSettingsRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val canonicalProgramRepository: CanonicalProgramRepository
) {

    /**
     * 프로그램 진급 추천 분석
     */
    fun analyzeProgression(user: User, localeOverride: String? = null): ProgramProgressionAnalysis {
        val locale = resolveLocale(user.id, localeOverride)
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
            recoveryStatus = recoveryStatus,
            locale = locale
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
    fun optimizeVolume(user: User, localeOverride: String? = null): VolumeOptimizationRecommendation {
        val locale = resolveLocale(user.id, localeOverride)
        val recentSessions = getRecentCompletedSessions(user, 10)
        if (recentSessions.isEmpty()) {
            return VolumeOptimizationRecommendation(
                currentVolume = VolumeMetrics(),
                recommendedVolume = VolumeMetrics(),
                adjustmentReason = WorkoutLocalization.message("progression.insufficient_history", locale),
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

        val muscleGroupVolumes = analyzeMuscleGroupVolume(recentSessions, locale)

        return VolumeOptimizationRecommendation(
            currentVolume = currentVolume,
            recommendedVolume = recommendedVolume,
            adjustmentReason = generateVolumeAdjustmentReason(currentVolume, recommendedVolume, rpeAverage, locale),
            muscleGroupVolumes = muscleGroupVolumes,
            mevReached = checkMEVStatus(muscleGroupVolumes),
            mavExceeded = checkMAVStatus(muscleGroupVolumes)
        )
    }

    /**
     * 회복 상태 분석
     */
    fun analyzeRecovery(user: User, localeOverride: String? = null): RecoveryAnalysis {
        val locale = resolveLocale(user.id, localeOverride)
        val recentSessions = getRecentCompletedSessions(user, 14)
        if (recentSessions.isEmpty()) {
            return RecoveryAnalysis(
                muscleGroups = emptyMap(),
                overallRecoveryScore = 100,
                needsDeloadWeek = false,
                nextRecommendedMuscles = emptyList()
            )
        }

        val recoveryByFocus = mutableMapOf<WorkoutFocus, MuscleRecoveryStatusProgression>()

        val now = AppTime.utcNow()

        // 근육군별 마지막 운동 시간 계산
        val muscleLastWorkout = mutableMapOf<WorkoutFocus, LocalDateTime>()
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

        // 근육군별 가장 최근 세션의 RPE/볼륨 계산
        val muscleSessionRPE = mutableMapOf<WorkoutFocus, Double?>()
        val muscleSessionVolume = mutableMapOf<WorkoutFocus, Int?>()
        muscleLastWorkout.keys.forEach { focus ->
            val lastSession = recentSessions.firstOrNull { session ->
                session.workoutType?.let { type ->
                    getMusclesFromWorkoutType(type.toString()).contains(focus)
                } ?: false
            }
            if (lastSession != null) {
                val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(lastSession.id)
                val sets = exercises.flatMap { exerciseSetRepository.findByWorkoutExerciseId(it.id) }
                val rpeValues = sets.mapNotNull { it.rpe?.toDouble() }
                muscleSessionRPE[focus] = if (rpeValues.isNotEmpty()) rpeValues.average() else null
                muscleSessionVolume[focus] = sets.size
            }
        }

        // 각 근육군 회복 상태 계산
        muscleLastWorkout.forEach { (focus, lastWorkout) ->
            val hoursSinceWorkout = ChronoUnit.HOURS.between(lastWorkout, now)
            val recoveryPercentage = calculateRecoveryPercentage(
                hoursSinceWorkout,
                sessionRPE = muscleSessionRPE[focus],
                sessionVolume = muscleSessionVolume[focus]
            )
            val optimalRecoveryHours = getOptimalRecoveryHours(focus)
            val readyForNextSession = hoursSinceWorkout >= optimalRecoveryHours

            recoveryByFocus[focus] = MuscleRecoveryStatusProgression(
                muscleName = WorkoutTargetResolver.displayName(focus, locale),
                lastWorkout = lastWorkout,
                hoursSinceWorkout = hoursSinceWorkout.toInt(),
                recoveryPercentage = recoveryPercentage,
                readyForNextSession = readyForNextSession,
                recommendedRestHours = maxOf(0, optimalRecoveryHours - hoursSinceWorkout.toInt())
            )
        }

        val needsDeload = checkDeloadNeed(recentSessions)
        val overallRecovery = recoveryByFocus.values.map { it.recoveryPercentage }.average().roundToInt()
        val muscleRecoveryMap = recoveryByFocus.mapKeys { (focus, _) ->
            WorkoutTargetResolver.displayName(focus, locale)
        }

        return RecoveryAnalysis(
            muscleGroups = muscleRecoveryMap,
            overallRecoveryScore = overallRecovery,
            needsDeloadWeek = needsDeload,
            deloadReason = if (needsDeload) generateDeloadReason(recentSessions, locale) else null,
            nextRecommendedMuscles = recommendNextMuscles(recoveryByFocus, locale)
        )
    }

    /**
     * 프로그램 전환 타이밍 체크
     */
    fun checkProgramTransition(user: User, localeOverride: String? = null): ProgramTransitionRecommendation {
        val locale = resolveLocale(user.id, localeOverride)
        val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val recentSessions = getRecentCompletedSessions(user, 30)

        if (recentSessions.isEmpty()) {
            return ProgramTransitionRecommendation(
                shouldTransition = false,
                currentProgramWeeks = 0,
                reason = WorkoutLocalization.message("progression.insufficient_history", locale),
                suggestedPrograms = emptyList()
            )
        }

        val programStartDate = findProgramStartDate(recentSessions)
        val weeksOnProgram = ChronoUnit.WEEKS.between(programStartDate, AppTime.utcNow()).toInt()
        val plateauDetected = detectPlateau(recentSessions)
        val goalProgress = calculateGoalProgress(profile, recentSessions)

        val shouldTransition = weeksOnProgram >= 6 || plateauDetected || goalProgress >= 90

        val suggestedPrograms = if (shouldTransition) {
            generateProgramSuggestions(profile, locale)
        } else {
            emptyList()
        }

        return ProgramTransitionRecommendation(
            shouldTransition = shouldTransition,
            currentProgramWeeks = weeksOnProgram,
            plateauDetected = plateauDetected,
            reason = generateTransitionReason(weeksOnProgram, plateauDetected, goalProgress, locale),
            suggestedPrograms = suggestedPrograms,
            goalCompletionRate = goalProgress
        )
    }

    // Helper methods

    private fun getRecentCompletedSessions(user: User, limit: Int): List<WorkoutSession> {
        return workoutSessionRepository.findByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED),
            PageRequest.of(0, limit)
        )
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

        val fourWeeksAgo = AppTime.utcNow().minusWeeks(weeksAnalyzed.toLong())
        val recentWorkouts = sessions.filter { it.startTime.isAfter(fourWeeksAgo) }.size

        return (recentWorkouts.toDouble() / expectedWorkouts * 100).coerceAtMost(100.0)
    }

    private fun analyzeRecoveryStatus(sessions: List<WorkoutSession>): RecoveryStatus {
        val lastWeekSessions = sessions.filter {
            it.startTime.isAfter(AppTime.utcNow().minusWeeks(1))
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
        recoveryStatus: RecoveryStatus,
        locale: String
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
                    reason = WorkoutLocalization.message("progression.recommendation.upper_lower.reason", locale),
                    expectedBenefits = listOf(
                        WorkoutLocalization.message("progression.recommendation.upper_lower.benefit.1", locale),
                        WorkoutLocalization.message("progression.recommendation.upper_lower.benefit.2", locale),
                        WorkoutLocalization.message("progression.recommendation.upper_lower.benefit.3", locale)
                    )
                )
            }
            currentDays == 4 && currentProgram == "UPPER_LOWER" -> {
                ProgressionRecommendation(
                    newProgram = "BRO_SPLIT",
                    newDaysPerWeek = 5,
                    reason = WorkoutLocalization.message("progression.recommendation.bro_split.reason", locale),
                    expectedBenefits = listOf(
                        WorkoutLocalization.message("progression.recommendation.bro_split.benefit.1", locale),
                        WorkoutLocalization.message("progression.recommendation.bro_split.benefit.2", locale),
                        WorkoutLocalization.message("progression.recommendation.bro_split.benefit.3", locale)
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
        rpe: Double,
        locale: String
    ): String {
        return when {
            recommended.weeklyVolume > current.weeklyVolume -> {
                WorkoutLocalization.message("progression.volume.adjust.increase", locale)
            }
            recommended.weeklyVolume < current.weeklyVolume -> {
                WorkoutLocalization.message("progression.volume.adjust.decrease", locale)
            }
            else -> WorkoutLocalization.message("progression.volume.adjust.maintain", locale)
        }
    }

    private fun analyzeMuscleGroupVolume(sessions: List<WorkoutSession>, locale: String): Map<String, Int> {
        val volumeMap = mutableMapOf<String, Int>()

        sessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { exercise ->
                val sets = exerciseSetRepository.findByWorkoutExerciseId(exercise.id)
                val setCount = sets.size

                // 운동별 타겟 근육군 가져오기
                exercise.exercise.muscleGroups.forEach { muscleGroup ->
                    val muscleName = WorkoutLocalization.muscleGroupName(muscleGroup, locale)
                    volumeMap[muscleName] = volumeMap.getOrDefault(muscleName, 0) + setCount
                }
            }
        }

        return volumeMap
    }

    private fun getMusclesFromWorkoutType(type: String): List<WorkoutFocus> {
        val workoutType = runCatching { WorkoutType.valueOf(type) }.getOrNull()
            ?: return listOf(WorkoutFocus.FULL_BODY)
        return WorkoutTargetResolver.targetsForWorkoutType(workoutType)
    }

    internal fun getMEV(muscleGroup: String): Int = when (muscleGroup.lowercase()) {
        "chest" -> 8
        "back", "lats" -> 10
        "quadriceps", "quads", "legs" -> 8
        "hamstrings" -> 6
        "glutes" -> 6
        "shoulders" -> 8
        "biceps" -> 6
        "triceps" -> 6
        "core", "abs" -> 6
        "calves" -> 8
        "forearms" -> 4
        "rear delts", "rear_delts" -> 6
        "side delts", "lateral_delts" -> 8
        else -> 8
    }

    internal fun getMAV(muscleGroup: String): Int = when (muscleGroup.lowercase()) {
        "chest" -> 20
        "back", "lats" -> 25
        "quadriceps", "quads", "legs" -> 20
        "hamstrings" -> 16
        "glutes" -> 16
        "shoulders" -> 22
        "biceps" -> 14
        "triceps" -> 14
        "core", "abs" -> 16
        "calves" -> 16
        "forearms" -> 10
        "rear delts", "rear_delts" -> 18
        "side delts", "lateral_delts" -> 22
        else -> 16
    }

    internal fun getMRV(muscleGroup: String, readinessScore: Double = 1.0): Int {
        val baseMAV = getMAV(muscleGroup)
        val recoveryBonus = when {
            readinessScore >= 1.0 -> 4
            readinessScore >= 0.9 -> 2
            readinessScore >= 0.8 -> 0
            else -> -2  // If low readiness, MRV drops below MAV
        }
        return (baseMAV + recoveryBonus).coerceAtLeast(getMEV(muscleGroup))
    }

    private fun checkMEVStatus(volumes: Map<String, Int>): Boolean {
        // 근육군별 MEV 기준 (Israetel) 충족 여부 체크
        return volumes.entries.all { (muscle, sets) -> sets >= getMEV(muscle) }
    }

    private fun checkMAVStatus(volumes: Map<String, Int>): Boolean {
        // 근육군별 MAV 기준 (Israetel) 초과 여부 체크
        return volumes.entries.any { (muscle, sets) -> sets > getMAV(muscle) }
    }

    fun calculateRecoveryPercentage(
        hoursSinceWorkout: Long,
        sessionRPE: Double? = null,
        sessionVolume: Int? = null
    ): Int {
        val baseRecovery = when {
            hoursSinceWorkout >= 72 -> 100
            hoursSinceWorkout >= 48 -> 85
            hoursSinceWorkout >= 24 -> 60
            else -> (hoursSinceWorkout * 2.5).roundToInt()
        }

        // RPE correction: higher RPE = slower recovery
        val rpeMultiplier = when {
            sessionRPE == null -> 1.0
            sessionRPE >= 9.5 -> 0.80  // very hard session, 20% slower recovery
            sessionRPE >= 8.5 -> 0.90
            sessionRPE >= 7.0 -> 1.0   // normal
            else -> 1.10               // easy session, 10% faster
        }

        // Volume correction: more sets = slower recovery
        val volumeMultiplier = when {
            sessionVolume == null -> 1.0
            sessionVolume >= 25 -> 0.85  // high volume session
            sessionVolume >= 15 -> 0.95
            else -> 1.0
        }

        return (baseRecovery * rpeMultiplier * volumeMultiplier).roundToInt().coerceIn(0, 100)
    }

    private fun getOptimalRecoveryHours(focus: WorkoutFocus): Int {
        return WorkoutTargetResolver.recoveryHours(focus)
    }

    private fun checkDeloadNeed(sessions: List<WorkoutSession>): Boolean {
        val twoWeeksSessions = sessions.filter {
            it.startTime.isAfter(AppTime.utcNow().minusWeeks(2))
        }

        // 2주간 8회 이상 운동했으면 디로드 필요
        return twoWeeksSessions.size >= 8
    }

    private fun generateDeloadReason(sessions: List<WorkoutSession>, locale: String): String {
        val recentCount = sessions.filter {
            it.startTime.isAfter(AppTime.utcNow().minusWeeks(2))
        }.size

        return WorkoutLocalization.message("progression.deload.reason", locale, recentCount)
    }

    private fun recommendNextMuscles(recoveryMap: Map<WorkoutFocus, MuscleRecoveryStatusProgression>, locale: String): List<String> {
        return recoveryMap
            .filter { it.value.readyForNextSession }
            .entries
            .sortedByDescending { it.value.recoveryPercentage }
            .map { (focus, _) -> WorkoutTargetResolver.displayName(focus, locale) }
            .take(3)
    }

    private fun findProgramStartDate(sessions: List<WorkoutSession>): LocalDateTime {
        // 프로그램 사이클 1, 일차 1인 세션 찾기
        return sessions
            .filter { it.programCycle == 1 && it.programDay == 1 }
            .lastOrNull()?.startTime ?: sessions.lastOrNull()?.startTime ?: AppTime.utcNow()
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
        val fourWeeksAgo = AppTime.utcNow().minusWeeks(4)
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

    private fun generateTransitionReason(weeks: Int, plateau: Boolean, goalProgress: Int, locale: String): String {
        return when {
            plateau -> WorkoutLocalization.message("progression.transition.reason.plateau", locale)
            weeks >= 6 -> WorkoutLocalization.message("progression.transition.reason.weeks", locale, weeks)
            goalProgress >= 90 -> WorkoutLocalization.message("progression.transition.reason.goal", locale)
            else -> WorkoutLocalization.message("progression.transition.reason.maintain", locale)
        }
    }

    private fun generateProgramSuggestions(profile: UserProfile?, locale: String): List<ProgramSuggestion> {
        val currentProgram = profile?.workoutSplit ?: "PPL"
        val experienceLevel = profile?.experienceLevel ?: ExperienceLevel.BEGINNER

        // Find programs that are one step up, excluding the current program
        val candidates = canonicalProgramRepository.findByIsActiveTrue()
            .filter { it.code != currentProgram }
            .sortedBy { it.daysPerWeek }

        return candidates.take(3).map { program ->
            ProgramSuggestion(
                programCode = program.code,
                programName = program.name,
                daysPerWeek = program.daysPerWeek,
                description = program.description ?: "",
                benefits = listOf(
                    "${program.progressionModel.name} 주기화",
                    "${program.daysPerWeek}일/주 훈련"
                ),
                difficulty = program.targetExperienceLevel.name.lowercase()
            )
        }
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

    private fun resolveLocale(userId: Long, localeOverride: String?): String {
        if (!localeOverride.isNullOrBlank()) {
            return WorkoutLocalization.normalizeLocale(localeOverride)
        }

        val userLocale = userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        return WorkoutLocalization.normalizeLocale(userLocale)
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

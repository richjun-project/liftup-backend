package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

/**
 * WorkoutRecommendationServiceV2에서 추가로 분리된 "10년차 PT" 세트/렙/무게/주기화 계산 엔진.
 * RPE, 피로도, 회복, 주기화(periodization)를 모두 고려해 세트당 처방을 산출한다.
 *
 * calculateSuggestedWeight / calculateFullPTRecommendation 은 AIAnalysisService 및
 * WorkoutRecommendationServiceV2.generateQuickRecommendation() 에서 호출되므로 public이다.
 */
@Service
@Transactional
class PTRecommendationEngine(
    private val userProfileRepository: UserProfileRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val userProgramEnrollmentRepository: UserProgramEnrollmentRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val exerciseTrainingProfileResolver: ExerciseTrainingProfileResolver,
    private val sharedSupport: WorkoutV2SharedSupport,
    private val weightRecommendationService: WeightRecommendationService,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun calculateSuggestedWeight(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise
    ): Double {
        val logger = LoggerFactory.getLogger(this::class.java)
        val locale = sharedSupport.resolveLocale(user.id)

        // 사용자 프로필 및 데이터 수집
        val userProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val bodyWeight = userProfile?.bodyInfo?.weight ?: 70.0
        val bodyFatPercentage = userProfile?.bodyInfo?.bodyFat
        val gender = userProfile?.gender?.lowercase() ?: "male"
        val experienceLevel = userProfile?.experienceLevel ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER

        // 최근 운동 기록 분석 (최근 4주)
        val recentSessions = getRecentWorkoutHistory(user, exercise, 28)
        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)

        // 10년차 PT의 전문적인 추천 시스템
        val suggested = calculateAdvancedPTRecommendation(
            user = user,
            exercise = exercise,
            bodyWeight = bodyWeight,
            gender = gender,
            experienceLevel = experienceLevel,
            personalRecord = personalRecord,
            recentHistory = recentSessions,
            locale = locale,
            bodyFatPercentage = bodyFatPercentage,
            age = userProfile?.age,
            profile = userProfile,
        )

        logger.info("Advanced PT System - Exercise: ${exercise.name}, " +
                "Previous: ${recentSessions.lastOrNull()?.weight}kg, " +
                "Suggested: ${suggested.weight}kg, " +
                "Reason: ${suggested.reason}")

        return suggested.weight
    }

    /**
     * 전체 PT 추천 데이터 반환 (세트/렙/휴식/무게 포함)
     * AI 추천 파이프라인에서 Gemini 대신 사용
     */
    fun calculateFullPTRecommendation(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise
    ): PTRecommendation {
        val logger = LoggerFactory.getLogger(this::class.java)
        val locale = sharedSupport.resolveLocale(user.id)

        val userProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val bodyWeight = userProfile?.bodyInfo?.weight ?: 70.0
        val bodyFatPercentage = userProfile?.bodyInfo?.bodyFat
        val gender = userProfile?.gender?.lowercase() ?: "male"
        val experienceLevel = userProfile?.experienceLevel ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER

        val recentSessions = getRecentWorkoutHistory(user, exercise, 28)
        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)

        // 프로그램 등록자이면 프로그램의 주기화 모델로 phase 결정
        val activeEnrollment = userProgramEnrollmentRepository
            .findFirstByUserAndStatusOrderByStartDateDesc(user, EnrollmentStatus.ACTIVE)

        val programPhaseOverride: PeriodizationPhase? = if (activeEnrollment != null) {
            val program = activeEnrollment.program
            val totalCompleted = activeEnrollment.totalCompletedWorkouts
            val programDays = program.daysPerWeek.coerceAtLeast(1)
            val currentWeek = (totalCompleted / programDays) + 1

            when (program.progressionModel) {
                ProgressionModel.BLOCK -> {
                    val blockPhase = (currentWeek - 1) % 7
                    when (blockPhase) {
                        0, 1 -> PeriodizationPhase.ACCUMULATION
                        2, 3 -> PeriodizationPhase.INTENSIFICATION
                        4, 5 -> PeriodizationPhase.REALIZATION
                        else -> PeriodizationPhase.DELOAD
                    }
                }
                ProgressionModel.UNDULATING -> {
                    // Undulating: 주 단위 강도 변화, 4주 후 deload
                    if (currentWeek % 4 == 0L.toInt()) PeriodizationPhase.DELOAD
                    else PeriodizationPhase.ACCUMULATION // 일별 변화는 운동 내에서 처리
                }
                ProgressionModel.LINEAR -> {
                    // Linear: 3주 진행 + 1주 deload
                    when ((currentWeek - 1) % 4) {
                        0, 1 -> PeriodizationPhase.ACCUMULATION
                        2 -> PeriodizationPhase.INTENSIFICATION
                        else -> PeriodizationPhase.DELOAD
                    }
                }
            }
        } else null

        return calculateAdvancedPTRecommendation(
            user = user,
            exercise = exercise,
            bodyWeight = bodyWeight,
            gender = gender,
            experienceLevel = experienceLevel,
            personalRecord = personalRecord,
            recentHistory = recentSessions,
            locale = locale,
            programPhaseOverride = programPhaseOverride,
            bodyFatPercentage = bodyFatPercentage,
            age = userProfile?.age,
            profile = userProfile,
        )
    }

    /**
     * 10년차 PT의 고급 추천 시스템
     * RPE, 피로도, 회복, 주기화를 모두 고려한 정교한 시스템
     */
    private fun calculateAdvancedPTRecommendation(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise,
        bodyWeight: Double,
        gender: String,
        experienceLevel: com.richjun.liftupai.domain.user.entity.ExperienceLevel,
        personalRecord: com.richjun.liftupai.domain.workout.entity.PersonalRecord?,
        recentHistory: List<WorkoutData>,
        locale: String,
        programPhaseOverride: PeriodizationPhase? = null,
        bodyFatPercentage: Double? = null,
        age: Int? = null,
        profile: com.richjun.liftupai.domain.user.entity.UserProfile? = null,
    ): PTRecommendation {

        // 1. 기본 무게 계산 (체조성·연령 보정 포함) — WeightRecommendationService 와 같은 표를 쓴다
        val baseWeight = calculateBaseWeight(exercise, bodyWeight, gender, experienceLevel, bodyFatPercentage, age)

        // 1-b. PR 이 없으면 프로필의 자기 신고/온보딩 추정치를 1RM·시작 무게로 쓴다.
        //      예전엔 이 값을 아무도 읽지 않아 벤치 1RM 100kg 사용자에게 초보 기본값(20kg)이 나갔다.
        val estimated1RM = if (personalRecord == null) weightRecommendationService.resolveEstimated1RM(profile, exercise) else null
        val declaredWorkingWeight = if (recentHistory.isEmpty()) weightRecommendationService.resolveWorkingWeight(profile, exercise) else null

        // 2. 최근 운동 분석
        val performanceAnalysis = analyzeRecentPerformance(recentHistory)

        // 3. 피로도 및 회복 상태 평가 (연령 보정 포함)
        val recoveryStatus = assessRecoveryStatus(user, exercise, recentHistory, age)

        // 4. 주기화 단계 결정 — 프로그램 등록자는 프로그램 phase 사용
        val periodizationPhase = programPhaseOverride ?: determinePeriodizationPhase(user, recentHistory, experienceLevel)

        // 5. 점진적 과부하 계획 (운동 특성별 분화된 처방, 연령 보정 포함)
        val progressionPlan = calculateProgressiveOverload(
            exercise = exercise,
            baseWeight = baseWeight,
            personalRecord = personalRecord,
            recentHistory = recentHistory,
            performanceAnalysis = performanceAnalysis,
            recoveryStatus = recoveryStatus,
            periodizationPhase = periodizationPhase,
            locale = locale,
            age = age,
            estimated1RM = estimated1RM,
            declaredWorkingWeight = declaredWorkingWeight,
            goals = profile?.goals.orEmpty(),
            profile = profile,
            experienceLevel = experienceLevel,
            hasPersonalRecord = personalRecord != null,
        )

        // 6. 최종 추천 생성
        return generateFinalRecommendation(
            exercise = exercise,
            progressionPlan = progressionPlan,
            periodizationPhase = periodizationPhase,
            recoveryStatus = recoveryStatus,
            performanceAnalysis = performanceAnalysis,
            locale = locale
        )
    }

    /**
     * 최근 운동 수행 능력 분석
     */
    private fun analyzeRecentPerformance(recentHistory: List<WorkoutData>): PerformanceAnalysis {
        if (recentHistory.isEmpty()) {
            return PerformanceAnalysis(
                trend = PerformanceTrend.NEW_EXERCISE,
                avgRPE = 7.0,
                consistency = 0.0,
                volumeTrend = 0.0
            )
        }

        // RPE 트렌드 분석 (개선: 강도 기반 RPE 추정)
        val recentEntries = recentHistory.take(3)
        val avgRPE = recentEntries.mapNotNull { it.rpe }.average().takeIf { !it.isNaN() }
            ?: estimateRPEFromIntensity(recentEntries)

        // 일관성 분석 (무게 변동성)
        val weights = recentHistory.map { it.weight }
        val weightConsistency = if (weights.size > 1) {
            val range = (weights.maxOrNull() ?: 0.0) - (weights.minOrNull() ?: 0.0)
            val avg = weights.average().takeIf { it != 0.0 } ?: 1.0
            (1.0 - range / avg).coerceIn(0.0, 1.0)
        } else 1.0

        // 볼륨 트렌드 (증가/감소/유지)
        val volumes = recentHistory.map { it.weight * it.reps * it.sets }
        val volumeTrend = if (volumes.size > 2) {
            val windowSize = (volumes.size / 2).coerceIn(1, 2)
            val recentVolume = volumes.take(windowSize).average()
            val olderVolume = volumes.takeLast(windowSize).average()
            (recentVolume - olderVolume) / volumes.average()
        } else 0.0

        // 수행 트렌드 결정
        val trend = when {
            avgRPE < 6 && volumeTrend > 0 -> PerformanceTrend.READY_TO_PROGRESS
            avgRPE > 8.5 -> PerformanceTrend.NEEDS_DELOAD
            weightConsistency < 0.7 -> PerformanceTrend.TECHNIQUE_FOCUS
            volumeTrend < -0.2 -> PerformanceTrend.DECLINING
            volumeTrend > 0.1 -> PerformanceTrend.IMPROVING
            else -> PerformanceTrend.MAINTAINING
        }

        return PerformanceAnalysis(trend, avgRPE, weightConsistency, volumeTrend)
    }

    /**
     * 회복 상태 평가
     */
    private fun assessRecoveryStatus(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise,
        recentHistory: List<WorkoutData>,
        age: Int? = null
    ): RecoveryStatus {
        val daysSinceSameExercise = recentHistory.firstOrNull()?.let {
            ChronoUnit.DAYS.between(it.completedAt, AppTime.utcNow())
        } ?: 7L

        val weekStart = AppTime.utcNow().minusDays(7)
        val targetedFrequency = workoutSessionRepository.findByUserAndStartTimeAfter(user, weekStart)
            .filter { it.status == SessionStatus.COMPLETED }
            .count { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).any { workoutExercise ->
                    workoutExercise.exercise.muscleGroups.any { it in exercise.muscleGroups }
                }
            }

        // 운동의 주요 근육군 중 가장 긴 회복 시간 적용 (보수적 접근)
        val baseRecoveryDays = exercise.muscleGroups
            .map { RecommendationConstants.getOptimalRecoveryDays(it) }
            .maxByOrNull { it.first }
            ?: when (exercise.category) {
                ExerciseCategory.LEGS -> 3..4
                ExerciseCategory.BACK, ExerciseCategory.CHEST -> 2..3
                else -> 1..2
            }

        // 연령 기반 회복 시간 보정 (40대 이상 회복 시간 증가)
        val ageMultiplier = RecommendationConstants.getAgeRecoveryMultiplier(age)
        val adjustedMinDays = (baseRecoveryDays.first * ageMultiplier).toInt()
        val adjustedMaxDays = (baseRecoveryDays.last * ageMultiplier).toInt()
        val optimalRecoveryDays = adjustedMinDays..adjustedMaxDays

        // 주당 같은 근육군 빈도 상한 (대근육 2-3회, 소근육 3-4회)
        val maxWeeklyFrequency = if (exercise.muscleGroups.any {
            it in RecommendationConstants.LARGE_MUSCLE_GROUPS
        }) 3 else 4

        return when {
            daysSinceSameExercise < optimalRecoveryDays.first -> RecoveryStatus.UNDER_RECOVERED
            daysSinceSameExercise > optimalRecoveryDays.last * 2 -> RecoveryStatus.DETRAINED
            targetedFrequency >= maxWeeklyFrequency -> RecoveryStatus.OVERREACHING
            daysSinceSameExercise in optimalRecoveryDays -> RecoveryStatus.OPTIMAL
            else -> RecoveryStatus.WELL_RECOVERED
        }
    }

    /**
     * 주기화 단계 결정 (실제 주간 운동 일정 기반)
     *
     * 과학적 근거: Block Periodization (Vladimir Issurin)
     * - 4주 메소사이클: 3주 로딩 + 1주 디로드
     * - 실제 날짜 기반 계산으로 휴식 기간 반영
     */
    private fun determinePeriodizationPhase(user: com.richjun.liftupai.domain.auth.entity.User, recentHistory: List<WorkoutData>, experienceLevel: ExperienceLevel = ExperienceLevel.BEGINNER): PeriodizationPhase {
        val now = AppTime.utcNow()

        // 마지막 운동 이후 경과 일수
        val daysSinceLastWorkout = recentHistory.firstOrNull()?.let { lastWorkout ->
            java.time.Duration.between(lastWorkout.completedAt, now).toDays()
        } ?: 0L

        // 디트레이닝 체크 (2주 이상 쉼)
        if (daysSinceLastWorkout >= 14) {
            return PeriodizationPhase.DELOAD
        }

        // 최근 8주 세션을 1회 쿼리로 가져와 주별로 분류
        val eightWeeksAgo = now.minusWeeks(8)
        val recentSessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, eightWeeksAgo)
            .filter { it.status == SessionStatus.COMPLETED }

        val last8Weeks = (0..7).map { weekOffset ->
            val weekStart = now.minusWeeks(weekOffset.toLong()).with(java.time.DayOfWeek.MONDAY).toLocalDate()
            val weekEnd = weekStart.plusDays(6)
            recentSessions.count { session ->
                val sessionDate = session.startTime.toLocalDate()
                sessionDate in weekStart..weekEnd
            }
        }

        // 과훈련 체크 (경험 수준별 주간 빈도 임계값 차등 적용)
        val recentAvg = last8Weeks.take(4).average()
        val maxWeeklyFrequency = when (experienceLevel) {
            ExperienceLevel.BEGINNER -> 3.5
            ExperienceLevel.NOVICE -> 4.0
            ExperienceLevel.INTERMEDIATE -> 5.0
            ExperienceLevel.ADVANCED -> 6.0
            ExperienceLevel.EXPERT -> 7.0
        }

        // 과훈련 단계별 대응
        val overtrainingRatio = recentAvg / maxWeeklyFrequency
        when {
            overtrainingRatio >= 1.2 -> {
                // 120% 이상: 강제 DELOAD
                logger.warn("User ${user.id} significantly overtrained (${recentAvg} vs max ${maxWeeklyFrequency}). Forcing DELOAD.")
                return PeriodizationPhase.DELOAD
            }
            overtrainingRatio >= 1.0 -> {
                // 100-120%: DELOAD 권고 (ACCUMULATION 대신 DELOAD)
                logger.info("User ${user.id} approaching overtraining (${recentAvg} vs max ${maxWeeklyFrequency}). Recommending DELOAD.")
                return PeriodizationPhase.DELOAD
            }
            overtrainingRatio >= 0.85 -> {
                // 85-100%: 주의 — 현재 사이클 유지하되 볼륨 감소 신호
                logger.debug("User ${user.id} nearing training limit (${recentAvg} vs max ${maxWeeklyFrequency}).")
                // 사이클 계산을 계속하되 REALIZATION은 피함
                // (아래 사이클 로직에서 REALIZATION이 나오면 INTENSIFICATION으로 다운그레이드)
            }
        }

        // 최근 28일 내 연속 활동 주 수 기반 사이클 계산 (전체 히스토리 X)
        val consecutiveActiveWeeks = last8Weeks.take(4).takeWhile { it > 0 }.size

        val cycleWeek = if (consecutiveActiveWeeks == 0) 1 else ((consecutiveActiveWeeks - 1) % 4) + 1

        val cyclePhase = when (cycleWeek) {
            1 -> PeriodizationPhase.ACCUMULATION
            2 -> PeriodizationPhase.INTENSIFICATION
            3 -> PeriodizationPhase.REALIZATION
            4 -> PeriodizationPhase.DELOAD
            else -> PeriodizationPhase.ACCUMULATION
        }

        // 85-100% 범위일 때 REALIZATION을 피함 (과부하 위험)
        val finalPhase = when {
            overtrainingRatio >= 0.85 && cyclePhase == PeriodizationPhase.REALIZATION -> {
                logger.info("Downgrading REALIZATION to INTENSIFICATION due to training load proximity (ratio: $overtrainingRatio)")
                PeriodizationPhase.INTENSIFICATION
            }
            else -> cyclePhase
        }
        return finalPhase
    }

    /**
     * 점진적 과부하 계획 수립
     */
    private fun calculateProgressiveOverload(
        exercise: Exercise,
        baseWeight: Double,
        personalRecord: com.richjun.liftupai.domain.workout.entity.PersonalRecord?,
        recentHistory: List<WorkoutData>,
        performanceAnalysis: PerformanceAnalysis,
        recoveryStatus: RecoveryStatus,
        periodizationPhase: PeriodizationPhase,
        locale: String,
        age: Int? = null,
        estimated1RM: Double? = null,
        declaredWorkingWeight: Double? = null,
        goals: Set<com.richjun.liftupai.domain.user.entity.FitnessGoal> = emptySet(),
        profile: com.richjun.liftupai.domain.user.entity.UserProfile? = null,
        experienceLevel: ExperienceLevel? = null,
        hasPersonalRecord: Boolean = personalRecord != null,
    ): ProgressionPlan {

        // 운동 특성별 분화된 세트/횟수/휴식 처방 (시작 무게를 목표 반복수에 맞추기 위해 먼저 푼다).
        // 주기화 매트릭스 위에 사용자 목표 창을 씌운다 — 근력 목표면 복합 4-6, 지구력 목표면 12-15.
        val trainingProfile = exerciseTrainingProfileResolver.resolveProfile(exercise)
        val goalWindow = exerciseTrainingProfileResolver.goalWindow(trainingProfile, goals)
        val config = exerciseTrainingProfileResolver.applyGoal(
            exerciseTrainingProfileResolver.getConfig(periodizationPhase, trainingProfile), trainingProfile, goals
        )
        val targetRepsForStart = parseUpperReps(config.reps) ?: 10

        // 시작 무게: 최근 이력 → 직접 입력 작업 무게 → 추정 1RM 의 목표 반복수 작업 무게 → 프로필 기본값
        val lastWeight = recentHistory.firstOrNull()?.weight
            ?: declaredWorkingWeight
            ?: estimated1RM?.let { weightRecommendationService.workingWeightFor1RM(it, targetRepsForStart) }
            ?: baseWeight
        // prWeight가 0이 되지 않도록 보장. PR 이 없어도 추정 1RM 이 있으면 그것이 %1RM 밴드의 기준이다.
        val prWeight = (personalRecord?.weight ?: estimated1RM ?: lastWeight * 1.2).takeIf { it > 0 } ?: baseWeight.takeIf { it > 0 } ?: 10.0

        // 주기화에 따른 강도 설정
        val intensityRange = when (periodizationPhase) {
            PeriodizationPhase.ACCUMULATION -> 0.65..0.75  // 65-75% 1RM
            PeriodizationPhase.INTENSIFICATION -> 0.75..0.85  // 75-85% 1RM
            PeriodizationPhase.REALIZATION -> 0.85..0.95  // 85-95% 1RM
            PeriodizationPhase.DELOAD -> 0.50..0.60  // 50-60% 1RM
        }

        // 수행 분석에 따른 조정 + 연령 기반 progression 보정
        val ageProgressionFactor = RecommendationConstants.getAgeProgressionMultiplier(age)
        val baseProgressionMultiplier = when (performanceAnalysis.trend) {
            PerformanceTrend.READY_TO_PROGRESS -> 1.05  // 5% 증가
            PerformanceTrend.IMPROVING -> 1.025  // 2.5% 증가
            PerformanceTrend.MAINTAINING -> 1.0  // 유지
            PerformanceTrend.NEEDS_DELOAD -> 0.8  // 20% 감소
            PerformanceTrend.TECHNIQUE_FOCUS -> 0.9  // 10% 감소
            PerformanceTrend.DECLINING -> 0.95  // 5% 감소
            PerformanceTrend.NEW_EXERCISE -> 1.0  // 기본값
        }
        // 증가 방향일 때만 연령 보정 적용 (감소 방향은 그대로 유지)
        val progressionMultiplier = if (baseProgressionMultiplier > 1.0) {
            1.0 + (baseProgressionMultiplier - 1.0) * ageProgressionFactor
        } else {
            baseProgressionMultiplier
        }

        // 회복 상태에 따른 조정
        val recoveryMultiplier = when (recoveryStatus) {
            RecoveryStatus.OPTIMAL -> 1.0
            RecoveryStatus.WELL_RECOVERED -> 1.02
            RecoveryStatus.UNDER_RECOVERED -> 0.9
            RecoveryStatus.OVERREACHING -> 0.8
            RecoveryStatus.DETRAINED -> 0.85
        }

        // 첫 세션(PR·최근 이력 모두 없음)은 플랜 화면·하루 운동과 같은 단일 출처 무게에서 출발한다.
        // 예전엔 가상의 PR(= 기본값 × 1.2)의 65~75% 밴드로 눌러 같은 사람에게 화면마다 다른 무게(기본값의 90%)를 줬다.
        val isFirstSession = !hasPersonalRecord && recentHistory.isEmpty()
        val firstSessionWeight = if (isFirstSession) weightRecommendationService.firstSessionWeight(profile, exercise, targetRepsForStart) else null

        // 최종 무게 계산 (NaN 방지 + 안전 마진 강화)
        var targetWeight = if (lastWeight > 0 && lastWeight.isFinite()) {
            val computed = lastWeight * progressionMultiplier * recoveryMultiplier
            if (computed.isFinite() && computed > 0) computed else prWeight * 0.7
        } else {
            (prWeight * 0.7).takeIf { it > 0 } ?: 10.0
        }

        // NaN/Infinity 최종 방어
        if (!targetWeight.isFinite() || targetWeight <= 0) {
            targetWeight = 10.0
        }

        // 안전 마진 적용 (과학적 근거: NSCA Guidelines)
        if (firstSessionWeight != null) {
            targetWeight = firstSessionWeight
        } else if (prWeight > 0) {
            // 1. 주기화 범위 내로 제한
            targetWeight = targetWeight.coerceIn(
                prWeight * intensityRange.start,
                prWeight * intensityRange.endInclusive
            )

            // 2. 급격한 증가 방지 (주당 최대 5% 증가)
            if (progressionMultiplier > 1.0 && lastWeight > 0 && lastWeight.isFinite()) {
                val maxWeeklyIncrease = lastWeight * 1.05
                if (targetWeight > maxWeeklyIncrease) {
                    targetWeight = maxWeeklyIncrease
                    logger.debug("Safety cap applied: maximum weekly increase is 5%")
                }
            }

            // 3. 절대 최소값 보장 (2.5kg)
            targetWeight = targetWeight.coerceAtLeast(2.5)

            // 4. PR 대비 안전 상한선 (110% 제한)
            targetWeight = targetWeight.coerceAtMost(prWeight * 1.1)

            // 5. 미성년·고령 강도 상한 (%1RM). 성장판/관절 보호 — 주기화 밴드보다 우선한다.
            val ageIntensityCap = RecommendationConstants.getAgeIntensityCap(age)
            if (ageIntensityCap < 1.0) {
                targetWeight = targetWeight.coerceAtMost(prWeight * ageIntensityCap)
            }
        }

        // 점진적 과부하 전략 결정: 무게→렙→세트→템포 순으로 로테이션
        val strategy = when (performanceAnalysis.trend) {
            PerformanceTrend.READY_TO_PROGRESS -> ProgressionStrategy.WEIGHT_INCREASE
            PerformanceTrend.IMPROVING -> ProgressionStrategy.WEIGHT_INCREASE
            PerformanceTrend.MAINTAINING -> ProgressionStrategy.REP_INCREASE      // 정체 시 렙 먼저
            PerformanceTrend.TECHNIQUE_FOCUS -> ProgressionStrategy.TEMPO_CHANGE
            PerformanceTrend.DECLINING -> ProgressionStrategy.REP_INCREASE        // 무게 줄이고 렙 증가
            PerformanceTrend.NEEDS_DELOAD -> ProgressionStrategy.WEIGHT_INCREASE  // 무게 감소 적용됨
            PerformanceTrend.NEW_EXERCISE -> ProgressionStrategy.REP_INCREASE
        }

        // 전략에 따른 세트/렙 조정
        val sets: Int
        val reps: String
        when (strategy) {
            ProgressionStrategy.WEIGHT_INCREASE -> {
                sets = config.sets
                reps = config.reps
            }
            ProgressionStrategy.REP_INCREASE -> {
                sets = config.sets
                reps = increaseRepRange(config.reps)
            }
            ProgressionStrategy.SET_INCREASE -> {
                sets = (config.sets + 1).coerceAtMost(6) // 절대 최대 6세트
                reps = config.reps
            }
            ProgressionStrategy.TEMPO_CHANGE -> {
                sets = config.sets
                reps = config.reps
            }
        }

        // 렙 증가 전략이 목표 창을 벗어나게 밀면(근력 목표 4-6 → 6-8) 창으로 되돌린다
        val goalReps = goalWindow?.let { exerciseTrainingProfileResolver.fitReps(reps, it) } ?: reps

        // 미성년·고령 볼륨 상한
        var cappedSets = sets.coerceAtMost(RecommendationConstants.getAgeMaxSets(age)).coerceAtLeast(1)
        var finalReps = goalReps
        var finalRest = config.restSeconds
        // 초보·노비스는 처음 몇 주 주력 복합운동을 8회 이상·3세트 이하로 — 첫 바벨 스쿼트가 4x6-8/180초로 나가던 것을 막는다(골든셋 F10)
        if (experienceLevel != null && RecoRules.isBeginnerBand(experienceLevel)) {
            val range = Regex("""^\s*(\d+)\s*(?:-\s*(\d+))?\s*$""").matchEntire(finalReps)
            if (range != null) {
                val lo = range.groupValues[1].toInt()
                val hi = range.groupValues[2].ifEmpty { range.groupValues[1] }.toInt()
                val rx = RecoRules.beginnerRx(exercise, RecoRules.Rx(cappedSets, lo, hi, finalRest))
                cappedSets = rx.sets
                finalReps = if (rx.minReps == rx.maxReps) "${rx.minReps}" else "${rx.minReps}-${rx.maxReps}"
                finalRest = rx.restSeconds
            }
        }

        // intensity 계산 시 NaN 방지
        val intensity = if (prWeight > 0 && targetWeight.isFinite()) {
            (targetWeight / prWeight * 100).roundToInt()
        } else {
            70 // 기본값: 70%
        }

        return ProgressionPlan(
            // 2.5kg 단위·바벨 20kg 하한은 모든 경로가 같은 함수(RecoRules.finalizeWeight)를 거친다. 무게 없는 종목(맨몸·밴드)은 0.
            weight = RecoRules.finalizeWeight(exercise, targetWeight),
            sets = cappedSets,
            reps = finalReps,
            restSeconds = finalRest,
            minRestSeconds = config.minRestSeconds.coerceAtMost(finalRest).coerceAtLeast(minOf(finalRest, if (finalRest > config.restSeconds) 90 else 0)),
            intensity = intensity,
            focus = determineFocus(performanceAnalysis, periodizationPhase, strategy, locale)
        )
    }

    /** "8-12" → 12, "10" → 10, 시간 기반("30s")은 null */
    private fun parseUpperReps(reps: String): Int? {
        if (reps.contains("s") || reps == "time-based") return null
        return reps.split("-").lastOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..36 }
    }

    /**
     * 렙 범위를 한 단계 올리는 헬퍼. 표준 구간으로 올린다: "6-8" → "8-10", "12-15" → "15-20".
     * 예전의 +2 방식은 "14-17", "16-19" 같은 어색한 처방을 만들었다(골든셋 2026-09-25).
     * 시간 기반("30s", "30-60s", "time-based")은 변경하지 않음
     */
    private fun increaseRepRange(reps: String): String {
        if (reps.contains("s") || reps == "time-based") return reps
        val low = reps.split("-").firstOrNull()?.trim()?.toIntOrNull() ?: return reps
        return STANDARD_REP_BRACKETS.firstOrNull { it.first > low }?.let { "${it.first}-${it.second}" } ?: reps
    }

    /**
     * 최종 추천 생성
     */
    private fun generateFinalRecommendation(
        exercise: Exercise,
        progressionPlan: ProgressionPlan,
        periodizationPhase: PeriodizationPhase,
        recoveryStatus: RecoveryStatus,
        performanceAnalysis: PerformanceAnalysis,
        locale: String
    ): PTRecommendation {

        // PT의 코칭 메시지 생성
        val coachingTip = generateCoachingTip(
            exercise = exercise,
            phase = periodizationPhase,
            recovery = recoveryStatus,
            performanceTrend = performanceAnalysis.trend,
            focus = progressionPlan.focus,
            locale = locale
        )

        // 추천 이유 설명
        val reason = buildString {
            append(when (periodizationPhase) {
                PeriodizationPhase.ACCUMULATION -> WorkoutLocalization.message("pt.phase.accumulation", locale)
                PeriodizationPhase.INTENSIFICATION -> WorkoutLocalization.message("pt.phase.intensification", locale)
                PeriodizationPhase.REALIZATION -> WorkoutLocalization.message("pt.phase.realization", locale)
                PeriodizationPhase.DELOAD -> WorkoutLocalization.message("pt.phase.deload", locale)
            })

            append(when (recoveryStatus) {
                RecoveryStatus.OPTIMAL -> WorkoutLocalization.message("pt.recovery.optimal", locale)
                RecoveryStatus.UNDER_RECOVERED -> WorkoutLocalization.message("pt.recovery.under_recovered", locale)
                RecoveryStatus.OVERREACHING -> WorkoutLocalization.message("pt.recovery.overreaching", locale)
                RecoveryStatus.DETRAINED -> WorkoutLocalization.message("pt.recovery.detrained", locale)
                else -> WorkoutLocalization.message("pt.recovery.default", locale)
            })
        }

        return PTRecommendation(
            weight = progressionPlan.weight,
            sets = progressionPlan.sets,
            reps = progressionPlan.reps,
            restSeconds = progressionPlan.restSeconds,
            minRestSeconds = progressionPlan.minRestSeconds,
            intensity = progressionPlan.intensity,
            reason = reason,
            coachingTip = coachingTip,
            focus = progressionPlan.focus
        )
    }

    // Helper 함수들
    private fun calculateBaseWeight(
        exercise: Exercise,
        bodyWeight: Double,
        gender: String,
        experienceLevel: com.richjun.liftupai.domain.user.entity.ExperienceLevel,
        bodyFatPercentage: Double? = null,
        age: Int? = null,
    ): Double {
        // 체조성 보정: 제지방량(LBM) 기반 계산
        val effectiveWeight = if (bodyFatPercentage != null && bodyFatPercentage in 5.0..50.0) {
            bodyWeight * (1.0 - bodyFatPercentage / 100.0)  // 제지방량
        } else {
            bodyWeight * 0.80  // 체지방률 미입력 시 기본 20% 가정
        }
        // 계수 표는 WeightRecommendationService 가 단일 출처다 — 플랜 화면과 홈 추천이 다른 무게를 내지 않게.
        return getExerciseBaseWeight(exercise, effectiveWeight) *
            weightRecommendationService.genderMultiplier(gender, exercise.category) *
            weightRecommendationService.experienceMultiplier(experienceLevel) *
            weightRecommendationService.ageMultiplier(age)
    }

    private fun getRecentWorkoutHistory(user: com.richjun.liftupai.domain.auth.entity.User, exercise: Exercise, days: Int): List<WorkoutData> {
        return try {
            val cutoffDate = AppTime.utcNow().minusDays(days.toLong())

            // 최근 N일 이내의 완료된 세션 조회
            val recentSessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, cutoffDate)
                .filter { it.status == SessionStatus.COMPLETED }

            // 해당 운동의 세트 기록 수집
            val workoutDataList = mutableListOf<WorkoutData>()

            recentSessions.forEach { session ->
                val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .filter { it.exercise.id == exercise.id }

                workoutExercises.forEach { workoutExercise ->
                    val sets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)

                    sets.forEach { set ->
                        if (set.weight > 0 && set.reps > 0 && set.completed) {
                            workoutDataList.add(
                                WorkoutData(
                                    weight = set.weight,
                                    reps = set.reps,
                                    sets = sets.size,
                                    rpe = set.rpe?.toDouble(),
                                    completedAt = session.startTime
                                )
                            )
                        }
                    }
                }
            }

            // 최신순 정렬 및 이상치 필터링
            workoutDataList
                .sortedByDescending { it.completedAt }
                .let { filterOutliers(it) }

        } catch (e: Exception) {
            logger.warn("Failed to load recent workout history: {}", e.message)
            emptyList()
        }
    }

    /**
     * 이상치 필터링 (IQR 방법)
     * 통계적으로 비정상적인 기록 제거
     */
    private fun filterOutliers(data: List<WorkoutData>): List<WorkoutData> {
        if (data.size < 4) return data

        val weights = data.map { it.weight }.sorted()
        val q1Index = (weights.size * 0.25).toInt()
        val q3Index = (weights.size * 0.75).toInt()

        val q1 = weights[q1Index]
        val q3 = weights[q3Index]
        val iqr = q3 - q1

        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr

        return data.filter { it.weight in lowerBound..upperBound }
    }

    /**
     * 강도 기반 RPE 추정
     *
     * 과학적 근거: Zourdos et al. (2016) - RPE-based Load Prescription
     * - RIR (Reps in Reserve) 개념 사용
     * - 반복 횟수로 RPE 역산
     *
     * @param history 최근 운동 기록
     * @return 추정 RPE (6.0-9.0)
     */
    private fun estimateRPEFromIntensity(history: List<WorkoutData>): Double {
        if (history.isEmpty()) return 7.0

        // recentHistory는 최신순으로 정렬되어 들어온다.
        val avgReps = history.take(3).map { it.reps }.average()

        // 반복수 기반 RPE 추정 (RIR 표 기반)
        val estimatedRPE = when {
            avgReps <= 3 -> 9.0  // 1-3 reps = RPE 9 (1-2 RIR)
            avgReps <= 5 -> 8.5  // 4-5 reps = RPE 8.5 (2-3 RIR)
            avgReps <= 8 -> 8.0  // 6-8 reps = RPE 8 (3-4 RIR)
            avgReps <= 10 -> 7.5  // 9-10 reps = RPE 7.5 (4-5 RIR)
            avgReps <= 12 -> 7.0  // 11-12 reps = RPE 7 (5+ RIR)
            else -> 6.5  // 13+ reps = RPE 6.5 (근지구력 영역)
        }

        return estimatedRPE
    }

    private fun roundToPlate(weight: Double): Double {
        return (Math.round(weight / 2.5) * 2.5).coerceAtLeast(2.5)
    }

    @Deprecated("Use ExerciseTrainingProfileResolver.resolveConfig() instead", ReplaceWith("exerciseTrainingProfileResolver.resolveConfig(exercise, phase).restSeconds"))
    private fun getRestTime(phase: PeriodizationPhase): Int {
        return when (phase) {
            PeriodizationPhase.ACCUMULATION -> 90
            PeriodizationPhase.INTENSIFICATION -> 120
            PeriodizationPhase.REALIZATION -> 180
            PeriodizationPhase.DELOAD -> 60
        }
    }

    private fun determineFocus(
        analysis: PerformanceAnalysis,
        phase: PeriodizationPhase,
        strategy: ProgressionStrategy = ProgressionStrategy.WEIGHT_INCREASE,
        locale: String
    ): String {
        val baseFocus = when {
            analysis.trend == PerformanceTrend.TECHNIQUE_FOCUS -> WorkoutLocalization.message("pt.focus.technique", locale)
            phase == PeriodizationPhase.DELOAD -> WorkoutLocalization.message("pt.focus.deload", locale)
            phase == PeriodizationPhase.REALIZATION -> WorkoutLocalization.message("pt.focus.realization", locale)
            analysis.avgRPE > 8 -> WorkoutLocalization.message("pt.focus.high_rpe", locale)
            else -> WorkoutLocalization.message("pt.focus.default", locale)
        }

        // TEMPO_CHANGE 전략일 때 템포 지시 추가
        return if (strategy == ProgressionStrategy.TEMPO_CHANGE) {
            "$baseFocus ${WorkoutLocalization.message("pt.focus.tempo_instruction", locale)}"
        } else {
            baseFocus
        }
    }

    private fun generateCoachingTip(
        exercise: Exercise,
        phase: PeriodizationPhase,
        recovery: RecoveryStatus,
        performanceTrend: PerformanceTrend,
        focus: String,
        locale: String
    ): String {
        // Layer 1: 운동 패턴별 기술 팁
        val pattern = exercisePatternClassifier.classifyExercise(exercise)
        val exerciseTip = when (pattern) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_MACHINE -> WorkoutLocalization.message("pt.coaching.press", locale)

            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LUNGE,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> WorkoutLocalization.message("pt.coaching.squat", locale)

            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> WorkoutLocalization.message("pt.coaching.hinge", locale)

            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN,
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP -> WorkoutLocalization.message("pt.coaching.row_pull", locale)

            ExercisePatternClassifier.MovementPattern.LATERAL_RAISE,
            ExercisePatternClassifier.MovementPattern.FRONT_RAISE,
            ExercisePatternClassifier.MovementPattern.REAR_DELT -> WorkoutLocalization.message("pt.coaching.shoulder", locale)

            ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL,
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_CABLE,
            ExercisePatternClassifier.MovementPattern.TRICEP_OVERHEAD,
            ExercisePatternClassifier.MovementPattern.TRICEP_LYING,
            ExercisePatternClassifier.MovementPattern.TRICEP_PUSHDOWN -> WorkoutLocalization.message("pt.coaching.arms", locale)

            ExercisePatternClassifier.MovementPattern.CRUNCH,
            ExercisePatternClassifier.MovementPattern.LEG_RAISE,
            ExercisePatternClassifier.MovementPattern.PLANK,
            ExercisePatternClassifier.MovementPattern.ROTATION,
            ExercisePatternClassifier.MovementPattern.ROLLOUT -> WorkoutLocalization.message("pt.coaching.core", locale)

            else -> WorkoutLocalization.message("pt.coaching.generic", locale)
        }

        // Layer 2: 주기화 단계별 팁
        val phaseTip = when (phase) {
            PeriodizationPhase.ACCUMULATION -> WorkoutLocalization.message("pt.coaching.phase.accumulation", locale)
            PeriodizationPhase.INTENSIFICATION -> WorkoutLocalization.message("pt.coaching.phase.intensification", locale)
            PeriodizationPhase.REALIZATION -> WorkoutLocalization.message("pt.coaching.phase.realization", locale)
            PeriodizationPhase.DELOAD -> WorkoutLocalization.message("pt.coaching.phase.deload", locale)
        }

        // Layer 3: 회복 상태별 팁 (OPTIMAL/WELL_RECOVERED는 별도 팁 없음)
        val recoveryTip = when (recovery) {
            RecoveryStatus.UNDER_RECOVERED -> WorkoutLocalization.message("pt.coaching.recovery.under_recovered", locale)
            RecoveryStatus.OVERREACHING -> WorkoutLocalization.message("pt.coaching.recovery.overreaching", locale)
            RecoveryStatus.DETRAINED -> WorkoutLocalization.message("pt.coaching.recovery.detrained", locale)
            RecoveryStatus.OPTIMAL, RecoveryStatus.WELL_RECOVERED -> null
        }

        // Layer 4: 퍼포먼스 트렌드별 팁 (MAINTAINING/NEW_EXERCISE는 별도 팁 없음)
        val performanceTip = when (performanceTrend) {
            PerformanceTrend.READY_TO_PROGRESS -> WorkoutLocalization.message("pt.coaching.performance.ready_to_progress", locale)
            PerformanceTrend.IMPROVING -> WorkoutLocalization.message("pt.coaching.performance.improving", locale)
            PerformanceTrend.DECLINING -> WorkoutLocalization.message("pt.coaching.performance.declining", locale)
            PerformanceTrend.NEEDS_DELOAD -> WorkoutLocalization.message("pt.coaching.performance.needs_deload", locale)
            PerformanceTrend.TECHNIQUE_FOCUS -> WorkoutLocalization.message("pt.coaching.performance.technique_focus", locale)
            PerformanceTrend.MAINTAINING, PerformanceTrend.NEW_EXERCISE -> null
        }

        // 조합: 운동 팁 + focus + 추가 팁 (우선순위: 회복 > 주기화 > 퍼포먼스, 최대 2개)
        val baseTip = WorkoutLocalization.message("pt.coaching.combined", locale, exerciseTip, focus)
        val additionalTips = listOfNotNull(recoveryTip, phaseTip, performanceTip).take(2)
        return if (additionalTips.isEmpty()) baseTip
        else "$baseTip ${additionalTips.joinToString(" ")}"
    }

    private companion object {
        val STANDARD_REP_BRACKETS = listOf(1 to 3, 3 to 5, 4 to 6, 6 to 8, 8 to 10, 10 to 12, 12 to 15, 15 to 20, 20 to 25)
    }

    // 데이터 클래스들
    data class PTRecommendation(
        val weight: Double,
        val sets: Int,
        val reps: String,
        val restSeconds: Int,
        val intensity: Int,  // % of 1RM
        val reason: String,
        val coachingTip: String,
        val focus: String,
        /** 세션 시간에 맞출 때 줄일 수 있는 휴식 하한 */
        val minRestSeconds: Int = restSeconds,
    )

    data class WorkoutData(
        val weight: Double,
        val reps: Int,
        val sets: Int,
        val rpe: Double?,
        val completedAt: LocalDateTime
    )

    data class PerformanceAnalysis(
        val trend: PerformanceTrend,
        val avgRPE: Double,
        val consistency: Double,
        val volumeTrend: Double
    )

    data class ProgressionPlan(
        val weight: Double,
        val sets: Int,
        val reps: String,
        val restSeconds: Int,
        val intensity: Int,
        val focus: String,
        val minRestSeconds: Int = restSeconds,
    )

    enum class PerformanceTrend {
        READY_TO_PROGRESS,
        IMPROVING,
        MAINTAINING,
        DECLINING,
        NEEDS_DELOAD,
        TECHNIQUE_FOCUS,
        NEW_EXERCISE
    }

    enum class ProgressionStrategy {
        WEIGHT_INCREASE,    // 무게 증가 (기본)
        REP_INCREASE,       // 같은 무게에서 렙 증가
        SET_INCREASE,       // 세트 수 증가
        TEMPO_CHANGE        // 템포 변경 (느린 네거티브 등)
    }

    enum class RecoveryStatus {
        OPTIMAL,
        WELL_RECOVERED,
        UNDER_RECOVERED,
        OVERREACHING,
        DETRAINED
    }

    enum class PeriodizationPhase {
        ACCUMULATION,     // 볼륨 증가
        INTENSIFICATION,  // 강도 증가
        REALIZATION,      // 피크
        DELOAD           // 회복
    }

    /**
     * 운동별 기본 무게 계산 (남성 중급자 기준). 표는 WeightRecommendationService.patternBaseRatio 에 있다.
     */
    private fun getExerciseBaseWeight(exercise: Exercise, bodyWeight: Double): Double {
        val pattern = exercisePatternClassifier.classifyExercise(exercise)
        return bodyWeight * weightRecommendationService.patternBaseRatio(pattern, exercise.category)
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.entity.Achievement
import com.richjun.liftupai.domain.workout.entity.AchievementType
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutAchievementCatalog
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import org.slf4j.LoggerFactory

/**
 * WorkoutServiceV2에서 분리된 운동 세션 생명주기 서비스.
 * 세션 시작(start/startNew/continue), 완료(complete), 실시간 세트/세션 업데이트를 담당한다.
 *
 * startQuickWorkout()은 WorkoutRecommendationServiceV2의 추천 로직을,
 * completeWorkout()/checkMilestones()는 WorkoutStatsService의 통계 로직을 재사용한다.
 */
@Service
@Transactional
class WorkoutSessionService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val achievementRepository: AchievementRepository,
    private val workoutStreakRepository: WorkoutStreakRepository,
    private val workoutProgressTracker: WorkoutProgressTracker,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val userProgramEnrollmentRepository: UserProgramEnrollmentRepository,
    @Autowired(required = false)
    private val notificationService: NotificationService?,
    private val sharedSupport: WorkoutV2SharedSupport,
    private val workoutRecommendationServiceV2: WorkoutRecommendationServiceV2,
    private val workoutStatsService: WorkoutStatsService,
    private val trainerMessageService: com.richjun.liftupai.domain.chat.service.TrainerMessageService? = null
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // 기존 메서드 (호환성 유지) - 진행 중인 세션이 있으면 반환, 없으면 새로 생성
    fun startWorkout(userId: Long, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId)

        // 진행 중인 세션이 있으면 그대로 반환
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
        if (existingSession.isPresent) {
            val session = existingSession.get()

            // WorkoutExercise를 직접 조회하여 운동 정보 가져오기
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            val translations = sharedSupport.translationMap(workoutExercises.map { it.exercise }, locale)

            val exercises = workoutExercises.map { workoutExercise ->
                toExerciseDto(workoutExercise.exercise, locale, translations)
            }

            return StartWorkoutResponseV2(
                sessionId = session.id,
                startTime = AppTime.formatUtcRequired(session.startTime),
                exercises = exercises,
                restTimerSettings = RestTimerSettings(
                    defaultRestSeconds = 90,
                    autoStartTimer = true
                )
            )
        }

        // 프로그램 진행 상황 계산
        val userSettings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val programDays = userSettings?.weeklyWorkoutDays
            ?: userProfile?.weeklyWorkoutDays
            ?: 3
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

        // 프로그램 타입에 따른 운동 타입 결정
        val programType = userSettings?.workoutSplit
            ?: userProfile?.workoutSplit
            ?: "PPL"
        val workoutSequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
        val workoutType = workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY

        // 진행 중인 세션이 없으면 새로 생성
        val session = WorkoutSession(
            user = user,
            startTime = AppTime.utcNow(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )

        val savedSession = workoutSessionRepository.save(session)
        val plannedExercises = request.plannedExercises.map { planned ->
            exerciseRepository.findById(planned.exerciseId)
                .orElseThrow { ResourceNotFoundException("EXERCISE001: Exercise not found") }
        }
        val translations = sharedSupport.translationMap(plannedExercises, locale)

        // 계획된 운동들 추가 및 WorkoutExercise 엔티티 생성
        val exercises = request.plannedExercises.mapIndexed { index, planned ->
            val exercise = plannedExercises[index]

            // WorkoutExercise 엔티티 생성 및 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = planned.orderIndex ?: index
            )
            val savedWorkoutExercise = workoutExerciseRepository.save(workoutExercise)
            logger.debug("Saved WorkoutExercise ID: {}, Session ID: {}, Exercise: {}", savedWorkoutExercise.id, savedSession.id, exercise.name)

            toExerciseDto(exercise, locale, translations)
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = AppTime.formatUtcRequired(savedSession.startTime),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    fun startNewWorkout(userId: Long, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        // workout_type에 따른 처리
        return when (request.workoutType) {
            "quick" -> startQuickWorkout(user, request)
            "ai" -> startAIWorkout(user, request)
            else -> startRegularWorkout(user, request)
        }
    }

    private fun startQuickWorkout(user: com.richjun.liftupai.domain.auth.entity.User, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        if (request.recommendationId == null) {
            throw IllegalArgumentException("recommendation_id is required to start a quick workout")
        }
        val locale = sharedSupport.resolveLocale(user.id)

        cancelExistingSessions(user)

        val workoutDetail = workoutRecommendationServiceV2.getWorkoutFromRecommendationId(user, request.recommendationId, locale)
        val adjustedWorkout = request.adjustments?.let { workoutRecommendationServiceV2.applyWorkoutAdjustments(workoutDetail, it) } ?: workoutDetail

        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = workoutProgressTracker.determineWorkoutType(adjustedWorkout.targetMuscles)

        val session = WorkoutSession(
            user = user,
            startTime = AppTime.utcNow(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )
        val savedSession = workoutSessionRepository.save(session)
        val exerciseEntities = adjustedWorkout.exercises.map { quickExercise ->
            exerciseRepository.findById(quickExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = sharedSupport.translationMap(exerciseEntities, locale)

        // 운동 정보 생성
        val exercises = adjustedWorkout.exercises.mapIndexed { index, quickExercise ->
            val exercise = exerciseEntities[index]

            // WorkoutExercise 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = quickExercise.order - 1
            )
            workoutExerciseRepository.save(workoutExercise)

            toExerciseDto(exercise, locale, translations)
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = AppTime.formatUtcRequired(savedSession.startTime),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    private fun startAIWorkout(user: com.richjun.liftupai.domain.auth.entity.User, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        if (request.aiWorkout == null) {
            throw IllegalArgumentException("ai_workout payload is required to start an AI workout")
        }
        val locale = sharedSupport.resolveLocale(user.id)

        // 진행 중인 세션 처리
        cancelExistingSessions(user)

        // 세션 생성
        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = workoutProgressTracker.determineWorkoutType(request.aiWorkout.targetMuscles)

        val session = WorkoutSession(
            user = user,
            startTime = AppTime.utcNow(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )
        val savedSession = workoutSessionRepository.save(session)
        val exerciseEntities = request.aiWorkout.exercises.map { aiExercise ->
            exerciseRepository.findById(aiExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = sharedSupport.translationMap(exerciseEntities, locale)

        // AI 추천 운동 정보 생성
        val exercises = request.aiWorkout.exercises.mapIndexed { index, aiExercise ->
            val exercise = exerciseEntities[index]

            // WorkoutExercise 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = aiExercise.order - 1
            )
            workoutExerciseRepository.save(workoutExercise)

            toExerciseDto(exercise, locale, translations)
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = AppTime.formatUtcRequired(savedSession.startTime),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    private fun startRegularWorkout(user: com.richjun.liftupai.domain.auth.entity.User, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        // 진행 중인 세션 처리
        cancelExistingSessions(user)
        val locale = sharedSupport.resolveLocale(user.id)

        // 프로그램 진행 상황 계산
        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = getWorkoutTypeForProgram(user, programPosition)

        // 새 세션 생성
        val session = WorkoutSession(
            user = user,
            startTime = AppTime.utcNow(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )

        val savedSession = workoutSessionRepository.save(session)
        val plannedExercises = request.plannedExercises.map { planned ->
            exerciseRepository.findById(planned.exerciseId)
                .orElseThrow { ResourceNotFoundException("EXERCISE001: Exercise not found") }
        }
        val translations = sharedSupport.translationMap(plannedExercises, locale)

        // 계획된 운동들 추가 및 WorkoutExercise 엔티티 생성
        val exercises = request.plannedExercises.mapIndexed { index, planned ->
            val exercise = plannedExercises[index]

            // WorkoutExercise 엔티티 생성 및 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = planned.orderIndex ?: index
            )
            workoutExerciseRepository.save(workoutExercise)

            toExerciseDto(exercise, locale, translations)
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = AppTime.formatUtcRequired(savedSession.startTime),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    private fun cancelExistingSessions(user: com.richjun.liftupai.domain.auth.entity.User) {
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusWithLock(user, SessionStatus.IN_PROGRESS)
        if (existingSession.isPresent) {
            val session = existingSession.get()
            session.status = SessionStatus.ABANDONED
            session.endTime = AppTime.utcNow()
            session.duration = ChronoUnit.MINUTES.between(session.startTime, session.endTime).toInt().coerceAtLeast(0)
            workoutSessionRepository.save(session)
        }
    }

    private fun getOrCalculateProgramPosition(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutProgramPosition {
        val userSettings = userSettingsRepository.findByUser_Id(user.id).orElse(null)
        val userProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val programDays = userSettings?.weeklyWorkoutDays
            ?: userProfile?.weeklyWorkoutDays
            ?: 3
        return workoutProgressTracker.getNextWorkoutInProgram(user, programDays)
    }

    private fun getWorkoutTypeForProgram(user: com.richjun.liftupai.domain.auth.entity.User, programPosition: WorkoutProgramPosition): WorkoutType {
        val userSettings = userSettingsRepository.findByUser_Id(user.id).orElse(null)
        val userProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val programType = userSettings?.workoutSplit
            ?: userProfile?.workoutSplit
            ?: "PPL"
        val workoutSequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
        return workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY
    }

    fun continueWorkout(userId: Long): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId)

        // 진행 중인 세션 찾기
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
            .orElseThrow { ResourceNotFoundException("No workout session is currently in progress") }

        // 만약 programDay가 없으면 설정 (이전 버전 호환성)
        if (existingSession.programDay == null) {
            val userSettings = userSettingsRepository.findByUser_Id(userId).orElse(null)
            val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
            val programDays = userSettings?.weeklyWorkoutDays
                ?: userProfile?.weeklyWorkoutDays
                ?: 3
            val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

            existingSession.programDay = programPosition.day
            existingSession.programCycle = programPosition.cycle

            // 운동 타입도 설정
            if (existingSession.workoutType == null) {
                val programType = userSettings?.workoutSplit
                    ?: userProfile?.workoutSplit
                    ?: "PPL"
                val workoutSequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
                existingSession.workoutType = workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY
            }

            workoutSessionRepository.save(existingSession)
        }

        // WorkoutExercise를 직접 조회하여 운동 정보 가져오기
        val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(existingSession.id)
        val translations = sharedSupport.translationMap(workoutExercises.map { it.exercise }, locale)

        val exercises = workoutExercises.map { workoutExercise ->
            toExerciseDto(workoutExercise.exercise, locale, translations)
        }

        // 각 운동의 세트 정보도 가져오기
        val exerciseSets = workoutExercises.map { workoutExercise ->
            val sets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
            ExerciseWithSets(
                exerciseId = workoutExercise.exercise.id,
                exerciseName = sharedSupport.localizedName(workoutExercise.exercise, locale, translations),
                orderIndex = workoutExercise.orderInSession,
                sets = sets.mapIndexed { index, set ->
                    SetInfo(
                        setId = set.id,
                        setNumber = set.setNumber,
                        weight = set.weight,
                        reps = set.reps,
                        completed = set.completed,
                        completedAt = AppTime.formatUtc(set.completedAt),
                        rpe = set.rpe  // RPE 추가
                    )
                }.ifEmpty {
                    // 세트가 없으면 기본 세트 생성 (일반적으로 3세트)
                    List(3) { index ->
                        SetInfo(
                            setNumber = index + 1,
                            completed = false
                        )
                    }
                }
            )
        }

        return StartWorkoutResponseV2(
            sessionId = existingSession.id,
            startTime = AppTime.formatUtcRequired(existingSession.startTime),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            ),
            exerciseSets = exerciseSets
        )
    }

    // 세션 행 잠금 + READ COMMITTED: 겹친 업데이트·완료 요청을 차례로, 앞 요청이 남긴 최신 세트를 보고 처리한다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun completeWorkout(userId: Long, sessionId: Long, request: CompleteWorkoutRequestV2): CompleteWorkoutResponseV2 {
        val session = sharedSupport.findUserSessionForUpdate(userId, sessionId)
        if (session.status != SessionStatus.IN_PROGRESS) {
            throw IllegalStateException("Session is already ${session.status}")
        }
        val locale = sharedSupport.resolveLocale(userId)
        val completedExerciseMap = request.exercises.associate { completedExercise ->
            completedExercise.exerciseId to exerciseRepository.findById(completedExercise.exerciseId)
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = sharedSupport.translationMap(completedExerciseMap.values, locale)

        logger.debug("completeWorkout - sessionId: {}", sessionId)
        logger.debug("request.exercises.size: {}", request.exercises.size)
        logger.debug("request.duration: {}", request.duration)

        val completedAt = request.endedAt?.let { AppTime.parseClientDateTime(it, sharedSupport.resolveTimeZone(userId)) } ?: AppTime.utcNow()
        val serverDuration = ChronoUnit.MINUTES.between(session.startTime, completedAt).toInt().coerceAtLeast(0)
        val normalizedDuration = normalizeDuration(request.duration, serverDuration)

        session.endTime = completedAt
        session.duration = normalizedDuration
        session.notes = request.notes
        session.status = SessionStatus.COMPLETED

        var totalVolume = 0.0
        var totalSets = 0
        val personalRecords = mutableListOf<PersonalRecordInfo>()

        // 완료된 운동 세트 저장 — 운동 중 /update 가 이미 저장한 세트를 지우고 완료 요청의 세트로 바꾼다.
        // 예전엔 기존 세트 뒤에 덧붙여, 운동을 완료할 때마다 기록·볼륨이 두 배가 됐다(2026-07~10, 13세션).
        val replacedExerciseIds = mutableSetOf<Long>()
        request.exercises.forEach { completedExercise ->
            val exercise = completedExerciseMap.getValue(completedExercise.exerciseId)

            // WorkoutExercise를 repository에서 찾기
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            val workoutExercise = workoutExercises.find { it.exercise.id == completedExercise.exerciseId }
                ?: WorkoutExercise(
                    session = session,
                    exercise = exercise,
                    orderInSession = workoutExercises.size
                ).also {
                    workoutExerciseRepository.save(it)
                }
            if (replacedExerciseIds.add(workoutExercise.id)) {
                val existing = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
                if (existing.isNotEmpty()) exerciseSetRepository.deleteAll(existing)
                workoutExercise.sets.clear()
                workoutExercise.totalVolume = 0.0
            }

            logger.debug("Exercise {} (ID: {}), sets: {}, completed: {}",
                exercise.name, completedExercise.exerciseId,
                completedExercise.sets.size, completedExercise.sets.count { it.completed })

            completedExercise.sets.filter { it.completed }.forEach { setDto ->
                val safeWeight = setDto.weight.coerceIn(0.0, 500.0)
                val safeReps = setDto.reps.coerceIn(0, 100)
                totalSets++
                val volume = safeWeight * safeReps
                totalVolume += volume
                workoutExercise.totalVolume = (workoutExercise.totalVolume ?: 0.0) + volume

                val exerciseSet = ExerciseSet(
                    workoutExercise = workoutExercise,
                    setNumber = workoutExercise.sets.size + 1,
                    weight = safeWeight,
                    reps = safeReps,
                    restTime = setDto.restTaken,
                    rpe = setDto.rpe,  // RPE 추가
                    completed = true,  // 이미 filter로 completed=true인 것만 처리
                    completedAt = setDto.completedAt?.let { AppTime.parseClientDateTime(it, sharedSupport.resolveTimeZone(userId)) } ?: completedAt
                )
                workoutExercise.sets.add(exerciseSet)
                exerciseSetRepository.save(exerciseSet)

                // 개인 기록 확인
                val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(
                    user = session.user,
                    exercise = exercise
                )

                if (previousRecord == null || safeWeight > previousRecord.weight) {
                    val newRecord = com.richjun.liftupai.domain.workout.entity.PersonalRecord(
                        user = session.user,
                        exercise = exercise,
                        weight = safeWeight,
                        reps = safeReps,
                        date = completedAt
                    )
                    personalRecordRepository.save(newRecord)

                    personalRecords.add(PersonalRecordInfo(
                        exerciseName = sharedSupport.localizedName(exercise, locale, translations),
                        weight = safeWeight,
                        reps = safeReps,
                        previousBest = previousRecord?.weight ?: 0.0
                    ))
                }
            }
        }

        logger.debug("Final totalVolume: {}", totalVolume)
        logger.debug("Final totalSets: {}", totalSets)

        session.totalVolume = totalVolume
        session.caloriesBurned = calculateCaloriesBurned(normalizedDuration, totalVolume)
        workoutSessionRepository.save(session)

        logger.debug("Saved session with totalVolume: {}", session.totalVolume)

        // UserProfile 업데이트 - lastWorkoutDate
        val profile = userProfileRepository.findByUser_Id(userId)
        if (profile.isPresent) {
            val userProfile = profile.get()
            userProfile.lastWorkoutDate = completedAt
            userProfileRepository.save(userProfile)
        }

        // MuscleRecovery 엔티티 업데이트 - 운동한 근육들 기록
        updateMuscleRecoveryAfterWorkout(session.user, request.exercises, completedAt)

        // 스트릭 업데이트
        val streak = updateWorkoutStreak(session.user)

        // 업적 확인
        val milestones = checkMilestones(session.user, session)

        // 트레이너가 채팅에서 오늘 결과에 반응한다(실패해도 완료 처리는 계속)
        try {
            trainerMessageService?.onWorkoutCompleted(session.user, totalSets, totalVolume, normalizedDuration)
        } catch (e: Exception) {
            logger.warn("Trainer feedback failed for user ${session.user.id}: ${e.message}")
        }

        // Update program enrollment if active
        try {
            val activeEnrollment = userProgramEnrollmentRepository
                .findFirstByUserAndStatusOrderByStartDateDesc(session.user, EnrollmentStatus.ACTIVE)
            if (activeEnrollment != null) {
                activeEnrollment.totalCompletedWorkouts += 1
                activeEnrollment.lastActiveDate = AppTime.utcNow()
                userProgramEnrollmentRepository.save(activeEnrollment)
            }
        } catch (e: Exception) {
            logger.error("Failed to update program enrollment for user ${session.user.id}: ${e.message}", e)
            // Non-blocking — workout completion should succeed even if enrollment update fails
        }

        // 통계 계산
        val stats = workoutStatsService.calculateWorkoutStats(session.user)

        // FCM 운동 완료 알림 발송
        try {
            val prText = if (personalRecords.isNotEmpty()) {
                NotificationLocalization.message("notification.workout_complete.pr", locale, personalRecords.size)
            } else ""
            val volumeStr = String.format("%.0f", totalVolume)
            notificationService?.sendPushNotification(
                PushNotificationRequest(
                    userId = userId,
                    title = NotificationLocalization.message("notification.title.workout_complete", locale),
                    body = NotificationLocalization.message("notification.workout_complete.body", locale, normalizedDuration, totalSets, volumeStr) + prText,
                    data = mapOf("type" to "workout_complete", "sessionId" to sessionId.toString())
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to send workout completion notification for user $userId", e)
        }

        return CompleteWorkoutResponseV2(
            success = true,
            summary = WorkoutSummaryV2(
                duration = normalizedDuration,
                totalVolume = totalVolume,
                totalSets = totalSets,
                exerciseCount = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).size,
                caloriesBurned = session.caloriesBurned ?: 0
            ),
            achievements = AchievementsInfo(
                newPersonalRecords = personalRecords,
                milestones = milestones
            ),
            stats = stats
        )
    }

    fun updateSet(userId: Long, sessionId: Long, request: UpdateSetRequest): UpdateSetResponse {
        val session = sharedSupport.findUserSession(userId, sessionId)

        if (session.status != SessionStatus.IN_PROGRESS) {
            throw IllegalStateException("Workout session is not in progress")
        }

        val exercise = exerciseRepository.findById(request.exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }

        // WorkoutExercise를 repository에서 찾기
        val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
        val workoutExercise = workoutExercises.find { it.exercise.id == request.exerciseId }
            ?: WorkoutExercise(
                session = session,
                exercise = exercise,
                orderInSession = workoutExercises.size
            ).also {
                workoutExerciseRepository.save(it)
            }

        val exerciseSet = ExerciseSet(
            workoutExercise = workoutExercise,
            setNumber = request.setNumber,
            weight = request.weight,
            reps = request.reps,
            rpe = request.rpe,  // RPE 추가
            completed = request.completed,
            completedAt = if (request.completed) {
                AppTime.parseClientDateTime(request.completedAt, sharedSupport.resolveTimeZone(userId))
            } else null
        )

        workoutExercise.sets.add(exerciseSet)
        val savedSet = exerciseSetRepository.save(exerciseSet)

        // 개인 기록 확인
        val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(
            session.user,
            exercise
        )

        val isPersonalRecord = request.completed && (previousRecord == null || request.weight > previousRecord.weight)

        if (isPersonalRecord) {
            val newRecord = com.richjun.liftupai.domain.workout.entity.PersonalRecord(
                user = session.user,
                exercise = exercise,
                weight = request.weight,
                reps = request.reps,
                date = AppTime.utcNow()
            )
            personalRecordRepository.save(newRecord)
        }

        return UpdateSetResponse(
            success = true,
            setId = savedSet.id,
            isPersonalRecord = isPersonalRecord,
            previousBest = previousRecord?.let {
                PreviousBest(
                    weight = it.weight,
                    reps = it.reps,
                    date = it.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
            }
        )
    }

    // 세트 완료를 빠르게 연달아 누르면 업데이트가 겹쳐 서로 지운 세트를 다시 지우다 500(낙관적 락 실패)이 났다.
    // 세션 행을 먼저 잠그고(READ COMMITTED) 차례로 처리한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun updateSession(userId: Long, sessionId: Long, request: UpdateSessionRequest, localeOverride: String? = null): UpdateSessionResponse {
        // 세션 조회(잠금) — 트랜잭션의 첫 읽기여야 한다
        val session = workoutSessionRepository.findByIdForUpdate(sessionId)
            .orElseThrow { ResourceNotFoundException("Workout session not found") }
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId, localeOverride)

        if (session.user.id != userId) {
            throw org.springframework.security.access.AccessDeniedException("Access denied")
        }

        // 진행 중인 세션만 업데이트 가능
        if (session.status != SessionStatus.IN_PROGRESS) {
            throw IllegalStateException("Only sessions in progress can be updated")
        }

        var totalSets = 0
        var completedSets = 0
        var updatedExercises = 0

        // 각 운동별로 업데이트
        val fallbackCompletedAt = request.lastUpdated?.let {
            AppTime.parseClientDateTime(it, sharedSupport.resolveTimeZone(userId))
        } ?: AppTime.utcNow()

        request.exercises.forEach { exerciseData ->
            // 운동 존재 확인
            val exercise = exerciseRepository.findById(exerciseData.exerciseId)
                .orElseThrow { ResourceNotFoundException("Exercise not found: ${exerciseData.exerciseId}") }

            // WorkoutExercise 조회 또는 생성
            val existingWorkoutExercise = workoutExerciseRepository.findBySessionIdAndExerciseId(sessionId, exerciseData.exerciseId)

            val workoutExercise = if (existingWorkoutExercise == null) {
                // 새로운 운동 추가
                val newWorkoutExercise = WorkoutExercise(
                    session = session,
                    exercise = exercise,
                    orderInSession = exerciseData.orderIndex
                )
                workoutExerciseRepository.save(newWorkoutExercise)
            } else {
                // 앱(1.1.28+)은 운동 중 순서를 바꿀 수 있다 — 보낸 순서를 그대로 따른다
                existingWorkoutExercise.also { it.orderInSession = exerciseData.orderIndex }
            }

            // 기존 세트 삭제
            val existingSets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
            exerciseSetRepository.deleteAll(existingSets)

            // 새로운 세트 추가
            var totalVolume = 0.0
            exerciseData.sets.forEach { setData ->
                val exerciseSet = ExerciseSet(
                    workoutExercise = workoutExercise,
                    setNumber = setData.setNumber,
                    weight = setData.weight,
                    reps = setData.reps,
                    rpe = setData.rpe,
                    restTime = setData.restTime,
                    completed = setData.completed,
                    completedAt = if (setData.completed) {
                        setData.completedAt?.let { AppTime.parseClientDateTime(it, sharedSupport.resolveTimeZone(userId)) }
                            ?: fallbackCompletedAt
                    } else null
                )
                exerciseSetRepository.save(exerciseSet)

                totalSets++
                if (setData.completed) {
                    completedSets++
                    totalVolume += setData.weight * setData.reps
                }
            }

            // WorkoutExercise 볼륨 업데이트
            workoutExercise.totalVolume = totalVolume
            workoutExercise.sets.clear()
            workoutExercise.sets.addAll(exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id))
            workoutExerciseRepository.save(workoutExercise)

            updatedExercises++
        }

        // 세션의 마지막 업데이트 시간 갱신
        session.totalVolume = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(sessionId)
            .sumOf { it.totalVolume ?: 0.0 }
        workoutSessionRepository.save(session)

        return UpdateSessionResponse(
            success = true,
            message = com.richjun.liftupai.domain.workout.util.WorkoutLocalization.message("session.update.success", locale),
            sessionId = sessionId,
            updatedExercises = updatedExercises,
            totalSets = totalSets,
            completedSets = completedSets
        )
    }

    private fun normalizeDuration(clientDuration: Int, serverDuration: Int): Int {
        if (serverDuration <= 0) {
            return clientDuration.coerceAtLeast(0)
        }

        if (clientDuration <= 0) {
            return serverDuration
        }

        return if (abs(clientDuration - serverDuration) <= 5) clientDuration else serverDuration
    }

    private fun toExerciseDto(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): ExerciseDto {
        return ExerciseDto(
            id = exercise.id,
            name = sharedSupport.localizedName(exercise, locale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { com.richjun.liftupai.domain.workout.util.WorkoutLocalization.muscleGroupName(it, locale) },
            equipment = exercise.equipment?.let { com.richjun.liftupai.domain.workout.util.WorkoutLocalization.equipmentName(it.name, locale) },
            instructions = sharedSupport.localizedInstructions(exercise, locale, translations)
        )
    }

    private fun calculateCaloriesBurned(duration: Int, totalVolume: Double): Int {
        val baseCalories = duration * 5
        val volumeBonus = (totalVolume / 1000) * 2
        return (baseCalories + volumeBonus).toInt()
    }

    private fun updateWorkoutStreak(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStreak {
        val zoneId = sharedSupport.resolveTimeZone(user.id)
        val today = AppTime.currentUserDate(zoneId)
        val existingStreak = workoutStreakRepository.findByUserAndDate(user, today)

        return if (existingStreak == null) {
            val yesterday = workoutStreakRepository.findByUserAndDate(user, today.minusDays(1))
            val currentStreak = (yesterday?.currentStreak ?: 0) + 1

            workoutStreakRepository.save(WorkoutStreak(
                user = user,
                date = today,
                currentStreak = currentStreak,
                longestStreak = maxOf(currentStreak, yesterday?.longestStreak ?: 0)
            ))
        } else {
            existingStreak
        }
    }

    private fun checkMilestones(user: com.richjun.liftupai.domain.auth.entity.User, session: WorkoutSession): List<String> {
        val milestones = mutableListOf<String>()
        val unlockedAchievements = mutableListOf<Achievement>()

        val totalWorkouts = workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED)

        when (totalWorkouts) {
            1L -> unlockAchievement(user, "first_workout", milestones, unlockedAchievements)
            10L -> unlockAchievement(user, "workout_10", milestones, unlockedAchievements)
            50L -> unlockAchievement(user, "workout_50", milestones, unlockedAchievements)
            100L -> unlockAchievement(user, "workout_100", milestones, unlockedAchievements)
            200L -> unlockAchievement(user, "workout_200", milestones, unlockedAchievements)
        }

        val currentStreak = workoutStatsService.calculateCurrentStreak(user, sharedSupport.resolveTimeZone(user.id))
        when (currentStreak) {
            7 -> unlockAchievement(user, "week_streak_7", milestones, unlockedAchievements)
            14 -> unlockAchievement(user, "week_streak_14", milestones, unlockedAchievements)
            30 -> unlockAchievement(user, "month_streak_30", milestones, unlockedAchievements)
            60 -> unlockAchievement(user, "month_streak_60", milestones, unlockedAchievements)
        }

        val totalVolume = session.totalVolume ?: 0.0
        if (totalVolume >= 10000) unlockAchievement(user, "volume_10000", milestones, unlockedAchievements)
        if (totalVolume >= 20000) unlockAchievement(user, "volume_20000", milestones, unlockedAchievements)

        val duration = session.duration ?: 0
        if (duration >= 60) unlockAchievement(user, "duration_60", milestones, unlockedAchievements)
        if (duration >= 90) unlockAchievement(user, "duration_90", milestones, unlockedAchievements)

        unlockedAchievements.forEach { achievement ->
            achievementRepository.save(achievement)
        }

        return milestones
    }

    private fun unlockAchievement(
        user: com.richjun.liftupai.domain.auth.entity.User,
        code: String,
        milestones: MutableList<String>,
        unlockedAchievements: MutableList<Achievement>
    ) {
        milestones.add(code)
        if (!achievementRepository.existsByUserAndCode(user, code)) {
            unlockedAchievements.add(createAchievement(user, code))
        }
    }

    private fun createAchievement(
        user: com.richjun.liftupai.domain.auth.entity.User,
        code: String
    ): Achievement {
        val definition = WorkoutAchievementCatalog.definition(code)
            ?: throw IllegalArgumentException("Unknown achievement code: $code")

        val locale = sharedSupport.resolveLocale(user.id)
        return Achievement(
            user = user,
            code = code,
            name = WorkoutAchievementCatalog.name(code, locale),
            description = WorkoutAchievementCatalog.description(code, locale),
            icon = definition.icon,
            type = definition.type,
            unlockedAt = AppTime.utcNow()
        )
    }

    /**
     * 운동 완료 후 근육 회복 데이터를 업데이트합니다.
     * MuscleRecovery 엔티티에 각 근육별로 마지막 운동 시간을 기록합니다.
     */
    private fun updateMuscleRecoveryAfterWorkout(
        user: com.richjun.liftupai.domain.auth.entity.User,
        completedExercises: List<CompletedExerciseV2>,
        completedAt: LocalDateTime
    ) {
        val now = completedAt
        val muscleGroupsWorked = mutableSetOf<String>()

        // 완료된 운동들에서 근육 그룹 추출
        completedExercises.forEach { completedExercise ->
            val exercise = exerciseRepository.findById(completedExercise.exerciseId).orElse(null)
            if (exercise != null) {
                logger.debug("Exercise {} (ID: {})", exercise.name, exercise.id)
                logger.debug("- Category: {}", exercise.category)
                logger.debug("- MuscleGroups: {}", exercise.muscleGroups.map { it.name })

                // Exercise의 muscleGroups에서 canonical key 가져오기
                exercise.muscleGroups.forEach { muscleGroup ->
                    val muscleKey = muscleGroup.name.lowercase()
                    muscleGroupsWorked.add(muscleKey)
                    logger.debug("Added muscle group: {} -> {}", muscleGroup.name, muscleKey)
                }

                // Exercise의 category를 기본 target key로도 추가
                val categoryMuscle = WorkoutTargetResolver.focusForCategory(exercise.category)
                    ?.let { WorkoutTargetResolver.key(it) }
                categoryMuscle?.let {
                    muscleGroupsWorked.add(it)
                    logger.debug("Added category muscle: {} -> {}", exercise.category, it)
                }
            }
        }

        logger.debug("Total muscle groups to save: {}", muscleGroupsWorked)

        // 각 근육 그룹에 대해 MuscleRecovery 업데이트
        muscleGroupsWorked.forEach { muscleGroup ->
            logger.debug("Processing muscle group: {}", muscleGroup)

            val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, muscleGroup)
                .orElseGet {
                    logger.debug("Creating new MuscleRecovery for {}", muscleGroup)
                    MuscleRecovery(
                        user = user,
                        muscleGroup = muscleGroup,
                        lastWorked = now,
                        recoveryPercentage = 0,
                        feelingScore = 5,
                        soreness = 3
                    )
                }

            muscleRecovery.lastWorked = now
            muscleRecovery.recoveryPercentage = 0  // 운동 직후는 0%
            muscleRecovery.soreness = 3  // 기본 근육통 레벨
            muscleRecovery.updatedAt = now

            val saved = muscleRecoveryRepository.save(muscleRecovery)
            logger.debug("Saved MuscleRecovery ID: {} for muscle: {}", saved.id, muscleGroup)
        }

        // UserProfile의 muscleRecovery JSON 업데이트 (하위 호환성)
        val profile = userProfileRepository.findByUser_Id(user.id)
        if (profile.isPresent) {
            val userProfile = profile.get()
            val muscleRecoveryJson = mutableMapOf<String, String>()
            muscleGroupsWorked.forEach { muscleGroup ->
                muscleRecoveryJson[muscleGroup] = now.toString()
            }
            userProfile.muscleRecovery = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(muscleRecoveryJson)
            userProfileRepository.save(userProfile)
        }
    }
}

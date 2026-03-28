package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.dto.*
import java.util.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.entity.Achievement
import com.richjun.liftupai.domain.workout.entity.AchievementType
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.recovery.service.RecoveryService
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.workout.util.WorkoutAchievementCatalog
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.time.AppTime
import com.richjun.liftupai.domain.notification.dto.PushNotificationRequest
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

@Service
@Transactional
class WorkoutServiceV2(
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
    private val recoveryService: RecoveryService,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val exerciseRecommendationService: ExerciseRecommendationService,
    private val exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService,
    private val autoProgramSelector: AutoProgramSelector,
    private val userProgramEnrollmentRepository: UserProgramEnrollmentRepository,
    private val programProgressiveOverloadService: ProgramProgressiveOverloadService,
    @Autowired(required = false)
    private val notificationService: NotificationService?,
    private val exerciseTrainingProfileResolver: ExerciseTrainingProfileResolver,
    @Autowired(required = false)
    private val vectorRecommendationService: VectorWorkoutRecommendationService?
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    @Value("\${app.exercise-media.base-url:https://liftup-cdn.com}")
    private var exerciseMediaBaseUrl: String = "https://liftup-cdn.com"

    private data class RecommendationProgramContext(
        val programDays: Int,
        val programType: String,
        val workoutSequence: List<WorkoutType>
    )

    // 기존 메서드 (호환성 유지) - 진행 중인 세션이 있으면 반환, 없으면 새로 생성
    fun startWorkout(userId: Long, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId)

        // 진행 중인 세션이 있으면 그대로 반환
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
        if (existingSession.isPresent) {
            val session = existingSession.get()

            // WorkoutExercise를 직접 조회하여 운동 정보 가져오기
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            val translations = translationMap(workoutExercises.map { it.exercise }, locale)

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
        val translations = translationMap(plannedExercises, locale)

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
        val locale = resolveLocale(user.id)

        cancelExistingSessions(user)

        val workoutDetail = getWorkoutFromRecommendationId(user, request.recommendationId, locale)
        val adjustedWorkout = request.adjustments?.let { applyWorkoutAdjustments(workoutDetail, it) } ?: workoutDetail

        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = workoutProgressTracker.determineWorkoutType(adjustedWorkout.targetMuscles)

        val session = WorkoutSession(
            user = user,
            startTime = AppTime.utcNow(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle,
            recommendationType = "QUICK"
        )
        val savedSession = workoutSessionRepository.save(session)
        val exerciseEntities = adjustedWorkout.exercises.map { quickExercise ->
            exerciseRepository.findById(quickExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = translationMap(exerciseEntities, locale)

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
        val locale = resolveLocale(user.id)

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
            programCycle = programPosition.cycle,
            recommendationType = "AI"
        )
        val savedSession = workoutSessionRepository.save(session)
        val exerciseEntities = request.aiWorkout.exercises.map { aiExercise ->
            exerciseRepository.findById(aiExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = translationMap(exerciseEntities, locale)

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
        val locale = resolveLocale(user.id)

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
        val translations = translationMap(plannedExercises, locale)

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
        val locale = resolveLocale(userId)

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
        val translations = translationMap(workoutExercises.map { it.exercise }, locale)

        val exercises = workoutExercises.map { workoutExercise ->
            toExerciseDto(workoutExercise.exercise, locale, translations)
        }

        // 각 운동의 세트 정보도 가져오기
        val exerciseSets = workoutExercises.map { workoutExercise ->
            val sets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
            ExerciseWithSets(
                exerciseId = workoutExercise.exercise.id,
                exerciseName = localizedName(workoutExercise.exercise, locale, translations),
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

    @Transactional
    fun completeWorkout(userId: Long, sessionId: Long, request: CompleteWorkoutRequestV2): CompleteWorkoutResponseV2 {
        val session = findUserSession(userId, sessionId)
        if (session.status != SessionStatus.IN_PROGRESS) {
            throw IllegalStateException("Session is already ${session.status}")
        }
        val locale = resolveLocale(userId)
        val completedExerciseMap = request.exercises.associate { completedExercise ->
            completedExercise.exerciseId to exerciseRepository.findById(completedExercise.exerciseId)
                .orElseThrow { ResourceNotFoundException("Exercise not found") }
        }
        val translations = translationMap(completedExerciseMap.values, locale)

        logger.debug("completeWorkout - sessionId: {}", sessionId)
        logger.debug("request.exercises.size: {}", request.exercises.size)
        logger.debug("request.duration: {}", request.duration)

        val completedAt = request.endedAt?.let { AppTime.parseClientDateTime(it, resolveTimeZone(userId)) } ?: AppTime.utcNow()
        val serverDuration = ChronoUnit.MINUTES.between(session.startTime, completedAt).toInt().coerceAtLeast(0)
        val normalizedDuration = normalizeDuration(request.duration, serverDuration)

        session.endTime = completedAt
        session.duration = normalizedDuration
        session.notes = request.notes
        session.status = SessionStatus.COMPLETED

        var totalVolume = 0.0
        var totalSets = 0
        val personalRecords = mutableListOf<PersonalRecordInfo>()

        // 완료된 운동 세트 저장
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

            logger.debug("Exercise {} (ID: {}), sets: {}, completed: {}",
                exercise.name, completedExercise.exerciseId,
                completedExercise.sets.size, completedExercise.sets.count { it.completed })

            completedExercise.sets.filter { it.completed }.forEach { setDto ->
                val safeWeight = setDto.weight.coerceIn(0.0, 500.0)
                val safeReps = setDto.reps.coerceIn(0, 100)
                totalSets++
                val volume = safeWeight * safeReps
                totalVolume += volume

                val exerciseSet = ExerciseSet(
                    workoutExercise = workoutExercise,
                    setNumber = workoutExercise.sets.size + 1,
                    weight = setDto.weight,
                    reps = setDto.reps,
                    restTime = setDto.restTaken,
                    rpe = setDto.rpe,  // RPE 추가
                    completed = true,  // 이미 filter로 completed=true인 것만 처리
                    completedAt = setDto.completedAt?.let { AppTime.parseClientDateTime(it, resolveTimeZone(userId)) } ?: completedAt
                )
                workoutExercise.sets.add(exerciseSet)
                exerciseSetRepository.save(exerciseSet)

                // 개인 기록 확인
                val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(
                    user = session.user,
                    exercise = exercise
                )

                if (previousRecord == null || setDto.weight > previousRecord.weight) {
                    val newRecord = com.richjun.liftupai.domain.workout.entity.PersonalRecord(
                        user = session.user,
                        exercise = exercise,
                        weight = setDto.weight,
                        reps = setDto.reps,
                        date = completedAt
                    )
                    personalRecordRepository.save(newRecord)

                    personalRecords.add(PersonalRecordInfo(
                        exerciseName = localizedName(exercise, locale, translations),
                        weight = setDto.weight,
                        reps = setDto.reps,
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
        val stats = calculateWorkoutStats(session.user)

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
        val session = findUserSession(userId, sessionId)

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
            rpe = request.rpe  // RPE 추가
        )

        workoutExercise.sets.add(exerciseSet)
        val savedSet = exerciseSetRepository.save(exerciseSet)

        // 개인 기록 확인
        val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(
            session.user,
            exercise
        )

        val isPersonalRecord = previousRecord == null || request.weight > previousRecord.weight

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

    @Transactional(readOnly = true)
    fun getExercisesV2Paged(
        category: String?,
        equipment: String?,
        hasGif: Boolean,
        localeOverride: String?,
        search: String?,
        pageable: org.springframework.data.domain.Pageable
    ): Map<String, Any> {
        val locale = resolveLocale(null, localeOverride)

        val page = when {
            search != null && search.isNotBlank() && category != null -> {
                val cat = ExerciseCategory.valueOf(category.uppercase())
                exerciseRepository.searchByCategory(cat, search, pageable)
            }
            search != null && search.isNotBlank() -> {
                exerciseRepository.searchPaged(search, pageable)
            }
            category != null && equipment != null -> {
                val cat = ExerciseCategory.valueOf(category.uppercase())
                val eq = Equipment.valueOf(equipment.uppercase())
                exerciseRepository.findByCategoryAndEquipment(cat, eq, pageable)
            }
            category != null -> {
                val cat = ExerciseCategory.valueOf(category.uppercase())
                exerciseRepository.findByCategory(cat, pageable)
            }
            else -> exerciseRepository.findAll(pageable)
        }

        val translations = translationMap(page.content, locale)

        val content = page.content.map { exercise ->
            ExerciseDetailV2(
                id = exercise.id,
                name = localizedName(exercise, locale, translations),
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
                equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
                imageUrl = if (hasGif) generateGifUrl(exercise) else exercise.imageUrl,
                thumbnailUrl = generateThumbnailUrl(exercise),
                difficulty = WorkoutLocalization.difficultyDisplayName("intermediate", locale),
                description = localizedInstructions(exercise, locale, translations)
            )
        }

        return mapOf(
            "content" to content,
            "totalElements" to page.totalElements,
            "totalPages" to page.totalPages,
            "number" to page.number,
            "last" to page.isLast,
            "size" to page.size
        )
    }

    @Transactional(readOnly = true)
    fun getExercisesV2(category: String?, equipment: String?, hasGif: Boolean, localeOverride: String?): List<ExerciseDetailV2> {
        val locale = resolveLocale(null, localeOverride)
        val exercises = when {
            category != null && equipment != null -> {
                val cat = ExerciseCategory.valueOf(category.uppercase())
                val eq = Equipment.valueOf(equipment.uppercase())
                exerciseRepository.findByCategoryAndEquipment(cat, eq)
            }
            category != null -> {
                val cat = ExerciseCategory.valueOf(category.uppercase())
                exerciseRepository.findByCategory(cat)
            }
            else -> exerciseRepository.findAll()
        }
        val translations = translationMap(exercises, locale)

        return exercises.map { exercise ->
            ExerciseDetailV2(
                id = exercise.id,
                name = localizedName(exercise, locale, translations),
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
                equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
                imageUrl = if (hasGif) generateGifUrl(exercise) else exercise.imageUrl,
                thumbnailUrl = generateThumbnailUrl(exercise),
                difficulty = WorkoutLocalization.difficultyDisplayName("intermediate", locale),
                description = localizedInstructions(exercise, locale, translations)
            )
        }
    }

    @Transactional(readOnly = true)
    fun getExerciseDetailsV2(userId: Long, exerciseId: Long, localeOverride: String?): ExerciseDetailResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }
        val translations = translationMap(listOf(exercise), locale)

        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)

        val lastSession = workoutSessionRepository.findTopByUserOrderByStartTimeDesc(user)
        val lastPerformed = if (lastSession != null) {
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(lastSession.id)
            exercises.find { it.exercise.id == exerciseId }?.let {
                lastSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
        } else null

        val totalSets = workoutExerciseRepository.countSetsByUserAndExercise(user.id, exerciseId) ?: 0
        val avgWeight = workoutExerciseRepository.calculateAverageWeight(user.id, exerciseId) ?: 0.0

        val exerciseDetail = ExerciseDetailV2(
            id = exercise.id,
            name = localizedName(exercise, locale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
            equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
            imageUrl = generateGifUrl(exercise),
            thumbnailUrl = generateThumbnailUrl(exercise),
            videoUrl = generateVideoUrl(exercise),
            difficulty = WorkoutLocalization.difficultyDisplayName("intermediate", locale),
            description = localizedInstructions(exercise, locale, translations),
            instructions = localizedLines(localizedInstructions(exercise, locale, translations)) ?: generateInstructions(exercise, locale),
            tips = localizedLines(localizedTips(exercise, locale, translations)) ?: generateTips(exercise, locale),
            commonMistakes = generateCommonMistakes(exercise, locale),
            breathing = generateBreathingGuide(locale)
        )

        return ExerciseDetailResponseV2(
            exercise = exerciseDetail,
            userStats = UserExerciseStats(
                personalRecord = personalRecord?.let {
                    com.richjun.liftupai.domain.workout.dto.PersonalRecord(
                        weight = it.weight,
                        reps = it.reps,
                        date = it.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    )
                },
                lastPerformed = lastPerformed,
                totalSets = totalSets,
                averageWeight = avgWeight,
                estimatedOneRepMax = personalRecord?.let { calculate1RM(it.weight, it.reps) } ?: 0.0
            )
        )
    }

    @Transactional(readOnly = true)
    fun getWorkoutCompletionStats(userId: Long, sessionId: Long?, localeOverride: String? = null): WorkoutCompletionStats {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        val session = sessionId?.let { findUserSession(userId, it) }

        // 세션 통계
        val sessionStats = session?.let {
            SessionStats(
                duration = it.duration ?: 0,
                totalVolume = it.totalVolume ?: 0.0,
                totalSets = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(it.id).sumOf { ex -> ex.sets.size },
                exerciseCount = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(it.id).size
            )
        } ?: SessionStats(0, 0.0, 0, 0)

        // 히스토리 통계
        val totalWorkouts = workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED)
        val memberSince = user.joinDate
        val zoneId = resolveTimeZone(userId)
        val completedSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        val weeksSinceJoin = ChronoUnit.WEEKS.between(memberSince, AppTime.utcNow())
        val avgWorkoutsPerWeek = if (weeksSinceJoin > 0) totalWorkouts.toDouble() / weeksSinceJoin else 0.0

        val historyStats = HistoryStats(
            totalWorkoutDays = completedSessions.map { AppTime.toUserLocalDate(it.startTime, zoneId) }.distinct().count(),
            totalWorkouts = totalWorkouts.toInt(),
            memberSince = memberSince.format(DateTimeFormatter.ISO_LOCAL_DATE),
            averageWorkoutsPerWeek = avgWorkoutsPerWeek
        )

        // 스트릭 통계
        val currentStreak = calculateCurrentStreak(user, zoneId)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0
        val weeklyCount = calculateWeeklyWorkoutCount(user, zoneId)
        val monthlyCount = calculateMonthlyWorkoutCount(user)

        val streakStats = StreakStats(
            current = currentStreak,
            longest = longestStreak,
            weeklyCount = weeklyCount,
            weeklyGoal = 5,
            monthlyCount = monthlyCount,
            monthlyGoal = 20
        )

        // 업적
        val achievements = achievementRepository.findByUser_Id(userId).map { ach ->
            com.richjun.liftupai.domain.workout.dto.Achievement(
                id = ach.id.toString(),
                code = ach.code,
                name = localizedAchievementName(ach, locale),
                description = localizedAchievementDescription(ach, locale),
                unlockedAt = ach.unlockedAt.format(DateTimeFormatter.ISO_LOCAL_DATE),
                icon = ach.icon
            )
        }

        // 비교 통계
        val lastWeekVolume = calculateLastWeekAverageVolume(user)
        val volumeChange = if (lastWeekVolume > 0 && session != null) {
            val change = ((session.totalVolume ?: 0.0) - lastWeekVolume) / lastWeekVolume * 100
            "${if (change >= 0) "+" else ""}${change.roundToInt()}%"
        } else "N/A"

        val comparisonStats = ComparisonStats(
            volumeChange = volumeChange,
            durationChange = WorkoutLocalization.message("duration.change.minutes", locale, 5),
            comparedTo = "lastWeekAverage"
        )

        return WorkoutCompletionStats(
            session = sessionStats,
            history = historyStats,
            streaks = streakStats,
            achievements = achievements,
            comparison = comparisonStats
        )
    }

    @Transactional(readOnly = true)
    fun getWorkoutCalendar(userId: Long, year: Int, month: Int, localeOverride: String? = null): WorkoutCalendarResponse {
        val logger = LoggerFactory.getLogger(this::class.java)

        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        val yearMonth = YearMonth.of(year, month)
        val zoneId = resolveTimeZone(userId)
        val (startDate, _) = AppTime.utcRangeForLocalDate(yearMonth.atDay(1), zoneId)
        val (_, endDate) = AppTime.utcRangeForLocalDate(yearMonth.atEndOfMonth(), zoneId)

        logger.info("Fetching workout sessions for user ${user.id} from $startDate to $endDate")

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)

        logger.info("Found ${sessions.size} sessions for user ${user.id} in ${yearMonth.year}-${yearMonth.monthValue}")
        sessions.forEach { session ->
            logger.debug("Session ID: ${session.id}, Date: ${session.startTime}, Status: ${session.status}, Volume: ${session.totalVolume}")
        }

        val calendarDays = (1..yearMonth.lengthOfMonth()).map { day ->
            val date = LocalDate.of(year, month, day)
            val daySessions = sessions.filter { AppTime.toUserLocalDate(it.startTime, zoneId) == date }

            if (daySessions.isNotEmpty()) {
                logger.debug("getWorkoutCalendar: Date {} has {} sessions", date, daySessions.size)
                daySessions.forEach { session ->
                    logger.debug("- Session ID: {}, Volume: {}, Status: {}", session.id, session.totalVolume, session.status)
                }
            }

            CalendarDay(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                hasWorkout = daySessions.isNotEmpty(),
                workoutCount = daySessions.size,
                totalVolume = daySessions.sumOf { it.totalVolume ?: 0.0 },
                primaryMuscles = daySessions.flatMap { session ->
                    val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    exercises.flatMap { it.exercise.muscleGroups.map { mg -> WorkoutLocalization.muscleGroupName(mg, locale) } }
                }.distinct()
            )
        }

        val totalDays = calendarDays.count { it.hasWorkout }
        val restDays = yearMonth.lengthOfMonth() - totalDays
        val averageVolume = if (totalDays > 0) {
            calendarDays.filter { it.hasWorkout }.sumOf { it.totalVolume ?: 0.0 } / totalDays
        } else 0.0

        val mostFrequentDay = sessions
            .groupBy { AppTime.toUserLocalDate(it.startTime, zoneId).dayOfWeek }
            .maxByOrNull { it.value.size }
            ?.key
            ?.let { WorkoutLocalization.message("day.${it.name.lowercase()}", locale) }
            ?: "n/a"

        return WorkoutCalendarResponse(
            calendar = calendarDays,
            summary = CalendarSummary(
                totalDays = totalDays,
                restDays = restDays,
                averageVolume = averageVolume,
                mostFrequentDay = mostFrequentDay
            )
        )
    }

    fun adjustNextSet(userId: Long, request: AdjustNextSetRequest, localeOverride: String? = null): AdjustNextSetResponse {
        val locale = resolveLocale(userId, localeOverride)
        val fatigueMultiplier = when (request.fatigue) {
            "high" -> 0.8
            "medium" -> 0.9
            else -> 1.0
        }

        val rpeAdjustment = when {
            request.previousSet.rpe >= 9 -> 0.9
            request.previousSet.rpe >= 8 -> 0.95
            else -> 1.0
        }

        val recommendedWeight = request.previousSet.weight * fatigueMultiplier * rpeAdjustment
        val recommendedReps = (request.previousSet.reps * fatigueMultiplier).roundToInt()

        val restSeconds = when (request.fatigue) {
            "high" -> 180
            "medium" -> 120
            else -> 90
        }

        val reason = when {
            request.fatigue == "high" -> WorkoutLocalization.message("adjust.reason.fatigue_high", locale)
            request.previousSet.rpe >= 9 -> WorkoutLocalization.message("adjust.reason.rpe_high", locale)
            else -> WorkoutLocalization.message("adjust.reason.standard", locale)
        }

        return AdjustNextSetResponse(
            recommendation = SetRecommendation(
                weight = recommendedWeight,
                reps = recommendedReps,
                restSeconds = restSeconds,
                reason = reason
            ),
            alternatives = listOf(
                AlternativeSet(
                    type = "drop_set",
                    weight = request.previousSet.weight * 0.7,
                    reps = request.previousSet.reps + 2,
                    description = WorkoutLocalization.message("adjust.alternative.drop_set", locale)
                ),
                AlternativeSet(
                    type = "rest_pause",
                    weight = request.previousSet.weight,
                    reps = request.previousSet.reps / 2,
                    description = WorkoutLocalization.message("adjust.alternative.rest_pause", locale)
                )
            )
        )
    }

    fun getRestTimer(userId: Long, exerciseType: String, intensity: String, setNumber: Int, localeOverride: String? = null): RestTimerResponse {
        val locale = resolveLocale(userId, localeOverride)
        val baseRest = when (exerciseType) {
            "compound" -> 180
            "isolation" -> 90
            else -> 120
        }

        val intensityMultiplier = when (intensity) {
            "high" -> 1.5
            "low" -> 0.7
            else -> 1.0
        }

        val setMultiplier = when {
            setNumber >= 4 -> 1.2
            setNumber >= 3 -> 1.1
            else -> 1.0
        }

        val recommendedRest = (baseRest * intensityMultiplier * setMultiplier).toInt()

        return RestTimerResponse(
            recommendedRest = recommendedRest,
            minRest = (recommendedRest * 0.7).toInt(),
            maxRest = (recommendedRest * 1.5).toInt(),
            factors = RestFactors(
                exerciseType = WorkoutLocalization.message("rest.exercise_type.${if (exerciseType == "compound" || exerciseType == "isolation") exerciseType else "general"}", locale),
                intensity = WorkoutLocalization.message("rest.intensity.${if (intensity == "high" || intensity == "low") intensity else "medium"}", locale),
                setNumber = WorkoutLocalization.message("rest.set_phase.${if (setNumber >= 4) "late" else "early"}", locale)
            )
        )
    }

    // Basic workout recommendation methods
    @Transactional(readOnly = true)
    fun getBasicWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        localeOverride: String? = null
    ): QuickWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        // Check if user has an active session
        val activeSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS).orElse(null)

        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val programContext = resolveRecommendationProgramContext(userId, user, userProfile)
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programContext.programDays)
        val normalizedTargetMuscle = normalizeRecommendationTarget(targetMuscle)

        // Determine target muscle based on current session or program position
        // 사용자가 명시적으로 근육을 지정하지 않으면 현재 세션 또는 프로그램 상태를 따름
        val adjustedTargetMuscle = if (normalizedTargetMuscle == null) {
            if (activeSession != null) {
                // 오래된 세션(6시간 이상) 무시 — 프로그램 순서 사용
                val sessionAge = java.time.Duration.between(activeSession.startTime, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))
                if (sessionAge.toHours() >= 6) {
                    logger.info("Ignoring stale IN_PROGRESS session (age: ${sessionAge.toHours()}h), using program position")
                    null // fall through to program position
                } else {
                    val currentWorkoutType = activeSession.workoutType ?: WorkoutType.FULL_BODY
                    val sessionMuscle = WorkoutTargetResolver.recommendationTargetForType(currentWorkoutType)
                    logger.debug("Selected target muscle from current session: {} (current type: {})", sessionMuscle, currentWorkoutType)
                    sessionMuscle
                }
            } else {
                null // fall through to program position
            } ?: run {
                // 진행 중인 세션이 없거나 오래된 경우 프로그램 위치에 따라 선택
                val workoutType = programContext.workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY
                val programMuscle = WorkoutTargetResolver.recommendationTargetForType(workoutType)
                logger.debug("Selected target muscle from program state: {} (next: {}, day {})", programMuscle, workoutType, programPosition.day)
                programMuscle
            }
        } else {
            logger.debug("Using user-selected target muscle: {}", normalizedTargetMuscle)
            normalizedTargetMuscle
        }

        // Generate main recommendation based on filters
        val recommendation = generateQuickRecommendation(user, duration, equipment, adjustedTargetMuscle, locale)

        // Generate alternatives
        val alternatives = generateAlternativeWorkouts(duration, equipment, adjustedTargetMuscle, locale)

        return QuickWorkoutRecommendationResponse(
            recommendation = recommendation,
            alternatives = alternatives
        )
    }

    @Deprecated("Use getBasicWorkoutRecommendation instead")
    @Transactional(readOnly = true)
    fun getQuickWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        localeOverride: String? = null
    ): QuickWorkoutRecommendationResponse {
        return getBasicWorkoutRecommendation(userId, duration, equipment, targetMuscle, localeOverride)
    }

    private fun generateQuickRecommendation(
        user: com.richjun.liftupai.domain.auth.entity.User,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): WorkoutRecommendationDetail {
        val workoutDuration = (duration ?: 30).coerceIn(10, 180)
        val workoutId = generateWorkoutId(workoutDuration, equipment, targetMuscle)

        // Get user profile to determine difficulty
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val difficultyKey = when (userProfile?.experienceLevel) {
            ExperienceLevel.BEGINNER -> "beginner"
            ExperienceLevel.INTERMEDIATE -> "intermediate"
            ExperienceLevel.ADVANCED -> "advanced"
            ExperienceLevel.EXPERT -> "advanced"
            else -> "intermediate" // default
        }
        val difficulty = WorkoutLocalization.difficultyDisplayName(difficultyKey, locale)

        // Calculate optimal exercise count based on duration
        val targetExerciseCount = getTargetExerciseCount(workoutDuration)

        // 벡터 추천 우선 시도, 실패 시 rule-based 폴백
        // 벡터 결과가 목표의 절반 미만이면 rule-based로 폴백 (인덱싱 미완료 등 대비)
        val minAcceptableCount = (targetExerciseCount + 1) / 2
        val exercises = try {
            vectorRecommendationService?.recommendExercises(
                user = user,
                profile = userProfile,
                duration = workoutDuration,
                targetMuscle = targetMuscle,
                equipment = equipment,
                difficulty = difficultyKey,
                limit = targetExerciseCount
            )?.takeIf { it.size >= minAcceptableCount }
        } catch (e: Exception) {
            logger.warn("Vector recommendation failed, falling back to rule-based: ${e.message}")
            null
        } ?: exerciseRecommendationService.getRecommendedExercises(
            user = user,
            targetMuscle = targetMuscle,
            equipment = equipment,
            duration = workoutDuration,
            limit = targetExerciseCount
        )
        val translations = translationMap(exercises, locale)

        logger.info("Workout recommendation completed: ${exercises.size} exercises (target: $targetExerciseCount)")

        // Create quick exercise details
        val quickExercises = exercises.take(targetExerciseCount).mapIndexed { index, exercise ->
            // PT 시스템으로 세트/렙/무게/휴식 전부 계산
            val ptRec = try {
                calculateFullPTRecommendation(user, exercise)
            } catch (e: Exception) {
                null
            }

            QuickExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = localizedName(exercise, locale, translations),
                sets = ptRec?.sets ?: 3,
                reps = ptRec?.reps ?: "10-12",
                rest = ptRec?.restSeconds ?: 60,
                order = index + 1,
                suggestedWeight = ptRec?.weight ?: try { calculateSuggestedWeight(user, exercise) } catch (e: Exception) { 0.0 }
            )
        }

        val targetMuscles = exercises
            .flatMap { exercise -> exercise.muscleGroups.map { muscle -> WorkoutTargetResolver.displayName(muscle, locale) } }
            .distinct()
        val equipmentList = exercises
            .mapNotNull { exercise -> exercise.equipment?.let { localizeEquipment(it, locale) } }
            .distinct()

        return WorkoutRecommendationDetail(
            workoutId = workoutId,
            name = generateWorkoutName(workoutDuration, targetMuscle, equipment, locale),
            duration = workoutDuration,
            difficulty = difficulty,
            exercises = quickExercises,
            estimatedCalories = calculateEstimatedCalories(workoutDuration, quickExercises.size),
            targetMuscles = targetMuscles,
            equipment = equipmentList
        )
    }

    private fun generateAlternativeWorkouts(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): List<AlternativeWorkout> {
        val alternatives = mutableListOf<AlternativeWorkout>()

        // Duration alternatives
        val baseDuration = duration ?: 30
        if (baseDuration >= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(20, equipment, "core"),
                name = generateWorkoutName(20, "core", equipment, locale),
                duration = 20
            ))
        }

        if (baseDuration <= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(45, equipment, "full_body"),
                name = generateWorkoutName(45, "full_body", equipment, locale),
                duration = 45
            ))
        }

        // Equipment alternatives
        if (equipment != "bodyweight") {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(baseDuration, "bodyweight", targetMuscle),
                name = generateWorkoutName(baseDuration, targetMuscle, "bodyweight", locale),
                duration = baseDuration
            ))
        }

        return alternatives.take(2)
    }

    /**
     * 운동 시간에 따른 목표 운동 개수 계산
     */
    private fun getTargetExerciseCount(duration: Int): Int {
        return when {
            duration <= 30 -> 4
            duration <= 45 -> 5
            duration <= 60 -> 6
            duration <= 75 -> 7
            else -> 8
        }
    }

    private fun getWorkoutFromRecommendationId(
        user: com.richjun.liftupai.domain.auth.entity.User,
        recommendationId: String,
        locale: String
    ): WorkoutRecommendationDetail {
        val query = parseWorkoutRecommendationId(recommendationId)
        return generateQuickRecommendation(
            user = user,
            duration = query.duration,
            equipment = query.equipment,
            targetMuscle = query.targetMuscle,
            locale = locale
        )
    }

    private fun applyWorkoutAdjustments(
        workout: WorkoutRecommendationDetail,
        adjustments: WorkoutAdjustments
    ): WorkoutRecommendationDetail {
        var adjustedExercises = workout.exercises

        // Apply skip exercises
        if (adjustments.skipExercises.isNotEmpty()) {
            adjustedExercises = adjustedExercises.filter {
                !adjustments.skipExercises.contains(it.exerciseId)
            }
        }

        // Apply exercise substitutions
        adjustments.substituteExercises.forEach { (original, substitute) ->
            adjustedExercises = adjustedExercises.map { exercise ->
                if (exercise.exerciseId == original) {
                    exercise.copy(exerciseId = substitute)
                } else {
                    exercise
                }
            }
        }

        return workout.copy(
            exercises = adjustedExercises,
            duration = adjustments.duration ?: workout.duration
        )
    }

    private data class WorkoutRecommendationQuery(
        val duration: Int,
        val equipment: String?,
        val targetMuscle: String?
    )

    private fun parseWorkoutRecommendationId(recommendationId: String): WorkoutRecommendationQuery {
        if (recommendationId.startsWith("quick|")) {
            val parts = recommendationId.split("|")
            return WorkoutRecommendationQuery(
                duration = parts.getOrNull(1)?.toIntOrNull() ?: 30,
                targetMuscle = normalizeRecommendationTarget(parts.getOrNull(2)),
                equipment = normalizeRecommendationEquipment(parts.getOrNull(3))
            )
        }

        val legacy = recommendationId.removePrefix("quick_")
        val durationToken = legacy.substringBefore("_", "30min")
        val remainder = legacy.substringAfter("_", "full_body_general")
        val equipmentToken = knownRecommendationEquipmentTokens
            .firstOrNull { remainder == it || remainder.endsWith("_$it") }

        val targetToken = when {
            equipmentToken == null -> remainder
            remainder == equipmentToken -> "full_body"
            else -> remainder.removeSuffix("_$equipmentToken")
        }

        return WorkoutRecommendationQuery(
            duration = durationToken.removeSuffix("min").filter { it.isDigit() }.toIntOrNull() ?: 30,
            targetMuscle = normalizeRecommendationTarget(targetToken),
            equipment = normalizeRecommendationEquipment(equipmentToken)
        )
    }

    fun calculateSuggestedWeight(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise
    ): Double {
        val logger = LoggerFactory.getLogger(this::class.java)
        val locale = resolveLocale(user.id)

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
            age = userProfile?.age
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
        val locale = resolveLocale(user.id)

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
            age = userProfile?.age
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
        age: Int? = null
    ): PTRecommendation {

        // 1. 기본 무게 계산 (체조성 보정 포함)
        val baseWeight = calculateBaseWeight(exercise, bodyWeight, gender, experienceLevel, bodyFatPercentage)

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
            age = age
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
        val avgRPE = recentHistory.takeLast(3).mapNotNull { it.rpe }.average().takeIf { !it.isNaN() }
            ?: estimateRPEFromIntensity(recentHistory)

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
            (volumes.takeLast(2).average() - volumes.take(2).average()) / volumes.average()
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
        age: Int? = null
    ): ProgressionPlan {

        val lastWeight = recentHistory.firstOrNull()?.weight ?: baseWeight
        // prWeight가 0이 되지 않도록 보장
        val prWeight = (personalRecord?.weight ?: lastWeight * 1.2).takeIf { it > 0 } ?: baseWeight.takeIf { it > 0 } ?: 10.0

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
        if (prWeight > 0) {
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
        }

        // 운동 특성별 분화된 세트/횟수/휴식 처방
        val config = exerciseTrainingProfileResolver.resolveConfig(exercise, periodizationPhase)

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

        // intensity 계산 시 NaN 방지
        val intensity = if (prWeight > 0 && targetWeight.isFinite()) {
            (targetWeight / prWeight * 100).roundToInt()
        } else {
            70 // 기본값: 70%
        }

        return ProgressionPlan(
            weight = roundToPlate(targetWeight),
            sets = sets,
            reps = reps,
            restSeconds = config.restSeconds,
            intensity = intensity,
            focus = determineFocus(performanceAnalysis, periodizationPhase, strategy, locale)
        )
    }

    /**
     * 렙 범위를 +2씩 올리는 헬퍼
     * 예: "6-8" → "8-10", "10" → "12"
     * 시간 기반("30s", "30-60s", "time-based")은 변경하지 않음
     */
    private fun increaseRepRange(reps: String): String {
        if (reps.contains("s") || reps == "time-based") return reps
        val parts = reps.split("-")
        return when (parts.size) {
            2 -> "${parts[0].trim().toIntOrNull()?.plus(2) ?: parts[0]}-${parts[1].trim().toIntOrNull()?.plus(2) ?: parts[1]}"
            1 -> parts[0].trim().toIntOrNull()?.plus(2)?.toString() ?: reps
            else -> reps
        }
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
            intensity = progressionPlan.intensity,
            reason = reason,
            coachingTip = coachingTip,
            focus = progressionPlan.focus
        )
    }

    // Helper 함수들
    private fun calculateBaseWeight(exercise: Exercise, bodyWeight: Double, gender: String, experienceLevel: com.richjun.liftupai.domain.user.entity.ExperienceLevel, bodyFatPercentage: Double? = null): Double {
        // 체조성 보정: 제지방량(LBM) 기반 계산
        val effectiveWeight = if (bodyFatPercentage != null && bodyFatPercentage in 5.0..50.0) {
            bodyWeight * (1.0 - bodyFatPercentage / 100.0)  // 제지방량
        } else {
            bodyWeight * 0.80  // 체지방률 미입력 시 기본 20% 가정
        }

        val genderMultiplier = when (gender) {
            "female" -> when (exercise.category) {
                ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS -> 0.7
                ExerciseCategory.LEGS -> 0.85
                else -> 0.75
            }
            else -> 1.0
        }

        val experienceMultiplier = when (experienceLevel) {
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER -> 0.5
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.NOVICE -> 0.6
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.INTERMEDIATE -> 0.75
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.ADVANCED -> 1.0
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.EXPERT -> 1.1
        }

        val baseWeight = getExerciseBaseWeight(exercise, effectiveWeight)
        return baseWeight * genderMultiplier * experienceMultiplier
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

        // 최근 3회 평균 반복수
        val avgReps = history.takeLast(3).map { it.reps }.average()

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

    // 데이터 클래스들
    data class PTRecommendation(
        val weight: Double,
        val sets: Int,
        val reps: String,
        val restSeconds: Int,
        val intensity: Int,  // % of 1RM
        val reason: String,
        val coachingTip: String,
        val focus: String
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
        val focus: String
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
     * 운동별 기본 무게 계산 (남성 중급자 기준)
     * PT의 경험적 데이터 기반
     */
    private fun getExerciseBaseWeight(exercise: Exercise, bodyWeight: Double): Double {
        val pattern = exercisePatternClassifier.classifyExercise(exercise)
        val baseRatio = when (pattern) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL -> 0.75
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL -> 0.3
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_MACHINE -> 0.7
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL -> 0.65
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL -> 0.25
            ExercisePatternClassifier.MovementPattern.DECLINE_PRESS -> 0.8
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL -> 0.5
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_DUMBBELL -> 0.22
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_MACHINE -> 0.45
            ExercisePatternClassifier.MovementPattern.DIPS,
            ExercisePatternClassifier.MovementPattern.PUSHUP -> 0.0
            ExercisePatternClassifier.MovementPattern.FLY -> 0.2
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP,
            ExercisePatternClassifier.MovementPattern.INVERTED_ROW -> 0.0
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN -> 0.6
            ExercisePatternClassifier.MovementPattern.BARBELL_ROW -> 0.6
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW -> 0.35
            ExercisePatternClassifier.MovementPattern.CABLE_ROW -> 0.65
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> 1.2
            ExercisePatternClassifier.MovementPattern.HIP_HINGE -> 0.8
            ExercisePatternClassifier.MovementPattern.SQUAT -> 0.9
            ExercisePatternClassifier.MovementPattern.LUNGE -> 0.4
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> 1.8
            ExercisePatternClassifier.MovementPattern.LEG_CURL -> 0.4
            ExercisePatternClassifier.MovementPattern.LEG_EXTENSION -> 0.5
            ExercisePatternClassifier.MovementPattern.GLUTE_FOCUSED -> 0.7
            ExercisePatternClassifier.MovementPattern.CALF -> 0.8
            ExercisePatternClassifier.MovementPattern.LATERAL_RAISE -> 0.1
            ExercisePatternClassifier.MovementPattern.FRONT_RAISE -> 0.12
            ExercisePatternClassifier.MovementPattern.REAR_DELT -> 0.08
            ExercisePatternClassifier.MovementPattern.FACE_PULL -> 0.2
            ExercisePatternClassifier.MovementPattern.UPRIGHT_ROW -> 0.4
            ExercisePatternClassifier.MovementPattern.SHRUG -> 0.6
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL -> 0.35
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_DUMBBELL -> 0.15
            ExercisePatternClassifier.MovementPattern.BICEP_CURL_CABLE -> 0.3
            ExercisePatternClassifier.MovementPattern.TRICEP_OVERHEAD -> 0.35
            ExercisePatternClassifier.MovementPattern.TRICEP_LYING -> 0.3
            ExercisePatternClassifier.MovementPattern.TRICEP_PUSHDOWN -> 0.4
            ExercisePatternClassifier.MovementPattern.CRUNCH,
            ExercisePatternClassifier.MovementPattern.LEG_RAISE,
            ExercisePatternClassifier.MovementPattern.PLANK,
            ExercisePatternClassifier.MovementPattern.ROLLOUT,
            ExercisePatternClassifier.MovementPattern.CARDIO,
            ExercisePatternClassifier.MovementPattern.PLYOMETRIC,
            ExercisePatternClassifier.MovementPattern.STRETCHING -> 0.0
            ExercisePatternClassifier.MovementPattern.ROTATION -> 0.15
            else -> when (exercise.category) {
                ExerciseCategory.CHEST -> 0.5
                ExerciseCategory.BACK -> 0.6
                ExerciseCategory.LEGS -> 0.7
                ExerciseCategory.SHOULDERS -> 0.35
                ExerciseCategory.ARMS -> 0.25
                ExerciseCategory.CORE -> 0.0
                else -> 0.4
            }
        }

        return bodyWeight * baseRatio
    }

    private fun resolveRecommendationProgramContext(
        userId: Long,
        user: com.richjun.liftupai.domain.auth.entity.User,
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile? = userProfileRepository.findByUser_Id(userId).orElse(null)
    ): RecommendationProgramContext {
        val userSettings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val configuredProgramType = userSettings?.workoutSplit
            ?: userProfile?.workoutSplit
            ?: RecommendationConstants.DEFAULT_PROGRAM_TYPE
        val programDays = userSettings?.weeklyWorkoutDays
            ?: userProfile?.weeklyWorkoutDays
            ?: 3
        val autoRecommendation = if (configuredProgramType.equals("AUTO", ignoreCase = true)) {
            autoProgramSelector.selectProgram(user)
        } else {
            null
        }

        return RecommendationProgramContext(
            programDays = programDays,
            programType = autoRecommendation?.programType ?: configuredProgramType,
            workoutSequence = autoRecommendation?.workoutSequence
                ?: workoutProgressTracker.getWorkoutTypeSequence(configuredProgramType)
        )
    }

    private fun generateWorkoutId(duration: Int, equipment: String?, targetMuscle: String?): String {
        val equipmentPart = sanitizeRecommendationToken(equipment ?: "general")
        val musclePart = sanitizeRecommendationToken(targetMuscle ?: "full_body")
        return "quick|$duration|$musclePart|$equipmentPart"
    }

    private fun generateWorkoutName(duration: Int, targetMuscle: String?, equipment: String?, locale: String): String {
        val focus = WorkoutTargetResolver.resolveFocus(targetMuscle) ?: WorkoutFocus.FULL_BODY
        val muscleText = WorkoutTargetResolver.displayName(focus, locale = locale)
        val equipmentText = localizeEquipment(equipment, locale)
        val durationText = WorkoutLocalization.durationLabel(duration, locale)

        return if (equipmentText.isBlank()) {
            WorkoutLocalization.message("basic.workout.name_no_equipment", locale, durationText, muscleText)
        } else {
            WorkoutLocalization.message("basic.workout.name", locale, durationText, muscleText, equipmentText)
        }
    }

    private fun sanitizeRecommendationToken(rawValue: String): String {
        return rawValue
            .trim()
            .lowercase()
            .replace("-", "_")
            .replace(" ", "_")
    }

    private fun normalizeRecommendationTarget(rawValue: String?): String? {
        return WorkoutTargetResolver.recommendationKey(rawValue)
    }

    private fun normalizeRecommendationEquipment(rawValue: String?): String? {
        val normalized = rawValue
            ?.let(::sanitizeRecommendationToken)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "general", "none" -> null
            else -> normalized
        }
    }

    private fun localizeEquipment(equipment: Equipment, locale: String): String {
        return localizeEquipment(equipment.name, locale)
    }

    private fun localizeEquipment(equipment: String?, locale: String): String {
        return WorkoutLocalization.equipmentName(equipment, locale)
    }

    companion object {
        private val knownRecommendationEquipmentTokens = listOf(
            "resistance_band",
            "bodyweight",
            "kettlebell",
            "dumbbells",
            "dumbbell",
            "barbell",
            "machine",
            "cable",
            "general"
        )
    }

    /**
     * 운동 전 추정 칼로리 (예측값)
     * 기본 5kcal/min + 운동 수 보정 (completeWorkout과 동일 기준)
     */
    private fun calculateEstimatedCalories(duration: Int, exerciseCount: Int): Int {
        val baseCalories = duration * 5
        val exerciseBonus = exerciseCount * 8
        return baseCalories + exerciseBonus
    }

    // Helper methods
    private fun findUserSession(userId: Long, sessionId: Long): WorkoutSession {
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("WORKOUT002: Workout session not found") }

        if (session.user.id != userId) {
            throw org.springframework.security.access.AccessDeniedException("Access denied")
        }

        return session
    }

    private fun calculateCaloriesBurned(duration: Int, totalVolume: Double): Int {
        val baseCalories = duration * 5
        val volumeBonus = (totalVolume / 1000) * 2
        return (baseCalories + volumeBonus).toInt()
    }

    private fun updateWorkoutStreak(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStreak {
        val zoneId = resolveTimeZone(user.id)
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

        val currentStreak = calculateCurrentStreak(user, resolveTimeZone(user.id))
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

        val locale = resolveLocale(user.id)
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

    private fun calculateWorkoutStats(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStats {
        val zoneId = resolveTimeZone(user.id)
        val completedSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        val totalWorkoutDays = completedSessions.map { AppTime.toUserLocalDate(it.startTime, zoneId) }.distinct().count()
        val currentWeekCount = calculateWeeklyWorkoutCount(user, zoneId)
        val currentStreak = calculateCurrentStreak(user, zoneId)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0

        return WorkoutStats(
            totalWorkoutDays = totalWorkoutDays,
            currentWeekCount = currentWeekCount,
            weeklyGoal = 5,
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

    private fun calculateCurrentStreak(user: com.richjun.liftupai.domain.auth.entity.User, zoneId: java.time.ZoneId): Int {
        val workoutDays = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
            .map { AppTime.toUserLocalDate(it.startTime, zoneId) }
            .toSet()
        val today = AppTime.currentUserDate(zoneId)
        var streak = 0
        var currentDate = today

        val maxStreakDays = 365
        var iterations = 0
        while (iterations < maxStreakDays) {
            val hasWorkout = workoutDays.contains(currentDate)
            if (hasWorkout) {
                streak++
                currentDate = currentDate.minusDays(1)
            } else if (currentDate == today) {
                currentDate = currentDate.minusDays(1)
            } else {
                break
            }
            iterations++
        }

        return streak
    }

    private fun calculateWeeklyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User, zoneId: java.time.ZoneId): Int {
        val today = AppTime.currentUserDate(zoneId)
        val startOfWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
        val (startUtc, _) = AppTime.utcRangeForLocalDate(startOfWeek, zoneId)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startUtc,
            AppTime.utcNow()
        )
    }

    private fun calculateMonthlyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val zoneId = resolveTimeZone(user.id)
        val today = AppTime.currentUserDate(zoneId)
        val startOfMonth = today.withDayOfMonth(1)
        val (startUtc, _) = AppTime.utcRangeForLocalDate(startOfMonth, zoneId)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startUtc,
            AppTime.utcNow()
        )
    }

    private fun calculateLastWeekAverageVolume(user: com.richjun.liftupai.domain.auth.entity.User): Double {
        val zoneId = resolveTimeZone(user.id)
        val today = AppTime.currentUserDate(zoneId)
        val (lastWeekStart, _) = AppTime.utcRangeForLocalDate(today.minusWeeks(1), zoneId)
        val (lastWeekEnd, _) = AppTime.utcRangeForLocalDate(today, zoneId)

        val lastWeekSessions = workoutSessionRepository.findByUserAndStartTimeBetween(
            user,
            lastWeekStart,
            lastWeekEnd
        )

        return if (lastWeekSessions.isNotEmpty()) {
            lastWeekSessions.sumOf { it.totalVolume ?: 0.0 } / lastWeekSessions.size
        } else 0.0
    }

    /**
     * Brzycki 공식을 사용한 1RM 계산
     * 1RM = weight / (1.0278 - 0.0278 × reps)
     *
     * 과학적 근거: Journal of Strength and Conditioning Research
     * 정확도: ±2% (1-10 reps 범위)
     *
     * @param weight 수행한 무게 (kg)
     * @param reps 반복 횟수 (1-10 권장, 최대 15)
     * @return 추정 1RM (kg)
     */
    private fun calculate1RM(weight: Double, reps: Int): Double {
        if (reps == 1) return weight
        if (reps > 15) {
            // 15회 이상은 근지구력 영역으로 1RM 추정 부정확
            // Epley 공식 사용 (Brzycki보다 보수적)
            return weight * (1 + reps / 30.0)
        }

        // Brzycki 공식 (가장 정확)
        val oneRM = weight / (1.0278 - 0.0278 * reps)

        // NaN 체크 및 범위 검증
        return if (oneRM.isNaN() || oneRM.isInfinite() || oneRM < weight) {
            weight // 계산 오류 시 최소한 수행 무게 반환
        } else {
            oneRM.coerceAtMost(weight * 2.0) // 안전 상한선: 수행 무게의 2배
        }
    }

    private fun generateGifUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "animation.gif")
    }

    private fun generateThumbnailUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "thumb.jpg")
    }

    private fun generateVideoUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "video.mp4")
    }

    private fun buildExerciseMediaUrl(exercise: Exercise, fileName: String): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/$fileName"
    }

    private fun generateInstructions(exercise: Exercise, locale: String): List<String> {
        val parsedInstructions = exercise.instructions
            ?.split("\n", ".")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        if (!parsedInstructions.isNullOrEmpty()) {
            return parsedInstructions.take(4)
        }

        return when (exercisePatternClassifier.classifyExercise(exercise)) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_MACHINE -> localizedMessages(
                locale,
                "exercise.instructions.press.1",
                "exercise.instructions.press.2",
                "exercise.instructions.press.3"
            )
            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LUNGE,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> localizedMessages(
                locale,
                "exercise.instructions.squat.1",
                "exercise.instructions.squat.2",
                "exercise.instructions.squat.3"
            )
            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> localizedMessages(
                locale,
                "exercise.instructions.hinge.1",
                "exercise.instructions.hinge.2",
                "exercise.instructions.hinge.3"
            )
            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN,
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP -> localizedMessages(
                locale,
                "exercise.instructions.row_pull.1",
                "exercise.instructions.row_pull.2",
                "exercise.instructions.row_pull.3"
            )
            else -> localizedMessages(locale, "exercise.instructions.generic.1")
        }
    }

    private fun generateTips(exercise: Exercise, locale: String): List<String> {
        return when (exercise.category) {
            ExerciseCategory.CHEST -> localizedMessages(
                locale,
                "exercise.tips.chest.1",
                "exercise.tips.chest.2",
                "exercise.tips.chest.3"
            )
            else -> localizedMessages(locale, "exercise.tips.generic.1")
        }
    }

    private fun generateCommonMistakes(exercise: Exercise, locale: String): List<String> {
        return when (exercisePatternClassifier.classifyExercise(exercise)) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL -> localizedMessages(
                locale,
                "exercise.mistakes.press.1",
                "exercise.mistakes.press.2",
                "exercise.mistakes.press.3"
            )
            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LUNGE,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> localizedMessages(
                locale,
                "exercise.mistakes.squat.1",
                "exercise.mistakes.squat.2",
                "exercise.mistakes.squat.3"
            )
            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> localizedMessages(
                locale,
                "exercise.mistakes.hinge.1",
                "exercise.mistakes.hinge.2",
                "exercise.mistakes.hinge.3"
            )
            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN,
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP -> localizedMessages(
                locale,
                "exercise.mistakes.row_pull.1",
                "exercise.mistakes.row_pull.2",
                "exercise.mistakes.row_pull.3"
            )
            else -> localizedMessages(
                locale,
                "exercise.mistakes.generic.1",
                "exercise.mistakes.generic.2"
            )
        }
    }

    private fun generateBreathingGuide(locale: String): String {
        return WorkoutLocalization.message("exercise.breathing.default", locale)
    }

    @Transactional
    fun updateSession(userId: Long, sessionId: Long, request: UpdateSessionRequest, localeOverride: String? = null): UpdateSessionResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        // 세션 조회 및 권한 확인
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("Workout session not found") }

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
                // 기존 운동 유지 (순서는 변경할 수 없으므로 그대로 사용)
                existingWorkoutExercise
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
                    notes = if (setData.completed) "completed" else "incomplete"
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
            message = WorkoutLocalization.message("session.update.success", locale),
            sessionId = sessionId,
            updatedExercises = updatedExercises,
            totalSets = totalSets,
            completedSets = completedSets
        )
    }

    private fun resolveLocale(userId: Long?, localeOverride: String? = null): String {
        if (!localeOverride.isNullOrBlank()) {
            return exerciseCatalogLocalizationService.normalizeLocale(localeOverride)
        }

        val userLocale = userId
            ?.let { userSettingsRepository.findByUser_Id(it).orElse(null)?.language }

        return exerciseCatalogLocalizationService.normalizeLocale(userLocale)
    }

    private fun resolveTimeZone(userId: Long): java.time.ZoneId {
        val timeZone = userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone
        return AppTime.resolveZoneId(timeZone)
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

    private fun translationMap(exercises: Collection<Exercise>, locale: String): Map<Long, ExerciseTranslation> {
        return exerciseCatalogLocalizationService.translationMap(exercises, locale)
    }

    private fun localizedName(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String {
        return exerciseCatalogLocalizationService.displayName(exercise, locale, translations)
    }

    private fun localizedInstructions(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String? {
        return exerciseCatalogLocalizationService.instructions(exercise, locale, translations)
    }

    private fun localizedTips(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String? {
        return exerciseCatalogLocalizationService.tips(exercise, locale, translations)
    }

    private fun localizedLines(text: String?): List<String>? {
        return text
            ?.split("\n", ".")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(4)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun localizedMessages(locale: String, vararg keys: String): List<String> {
        return keys.map { key -> WorkoutLocalization.message(key, locale) }
    }

    private fun localizedAchievementName(achievement: Achievement, locale: String): String {
        val code = achievement.code ?: return achievement.name
        return if (WorkoutAchievementCatalog.contains(code)) {
            WorkoutAchievementCatalog.name(code, locale)
        } else {
            achievement.name
        }
    }

    private fun localizedAchievementDescription(achievement: Achievement, locale: String): String {
        val code = achievement.code ?: return achievement.description
        return if (WorkoutAchievementCatalog.contains(code)) {
            WorkoutAchievementCatalog.description(code, locale)
        } else {
            achievement.description
        }
    }

    private fun toExerciseDto(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): ExerciseDto {
        return ExerciseDto(
            id = exercise.id,
            name = localizedName(exercise, locale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
            equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
            instructions = localizedInstructions(exercise, locale, translations)
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

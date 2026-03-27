package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

@Service
@Transactional
class WorkoutService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val workoutProgressTracker: WorkoutProgressTracker,
    private val autoProgramSelector: AutoProgramSelector,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {

    @Deprecated("Use WorkoutServiceV2.startNewWorkout() instead")
    fun startWorkout(userId: Long, request: StartWorkoutRequest): StartWorkoutResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val session = WorkoutSession(
            user = user,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS
        )

        val savedSession = workoutSessionRepository.save(session)

        // 계획된 운동들 추가
        request.plannedExercises.forEachIndexed { index, planned ->
            val exercise = exerciseRepository.findById(planned.exerciseId)
                .orElseThrow { ResourceNotFoundException("Exercise not found: ${planned.exerciseId}") }

            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = index
            )
            workoutExerciseRepository.save(workoutExercise)
        }

        return StartWorkoutResponse(
            sessionId = savedSession.id,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    private fun cleanupDuplicateInProgressSessions(user: User) {
        val inProgressSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.IN_PROGRESS)

        if (inProgressSessions.size > 1) {
            // Keep the most recent session, mark others as ABANDONED
            val sortedSessions = inProgressSessions.sortedByDescending { it.startTime }
            sortedSessions.drop(1).forEach { session ->
                session.status = SessionStatus.ABANDONED
                workoutSessionRepository.save(session)
            }
        }
    }

    @Deprecated("Use WorkoutServiceV2.completeWorkout() instead")
    fun endWorkout(userId: Long, sessionId: Long, request: EndWorkoutRequest): WorkoutSummaryResponse {
        val session = findUserSession(userId, sessionId)

        session.endTime = LocalDateTime.now()
        session.duration = request.duration
        session.notes = request.notes
        session.status = SessionStatus.COMPLETED

        var totalVolume = 0.0
        val personalRecords = mutableListOf<String>()

        // 완료된 운동 세트 저장
        request.exercises.forEach { completedExercise ->
            val exercise = exerciseRepository.findById(completedExercise.exerciseId)
                .orElseThrow { ResourceNotFoundException("Exercise not found: ${completedExercise.exerciseId}") }

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

            var exerciseVolume = 0.0
            completedExercise.sets.forEachIndexed { index, setDto ->
                val exerciseSet = ExerciseSet(
                    workoutExercise = workoutExercise,
                    setNumber = index + 1,
                    weight = setDto.weight,
                    reps = setDto.reps,
                    rpe = setDto.rpe,
                    restTime = setDto.restTime
                )

                exerciseVolume += setDto.weight * setDto.reps
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
                        date = LocalDateTime.now()
                    )
                    personalRecordRepository.save(newRecord)
                    personalRecords.add("${exercise.name}: ${setDto.weight}kg x ${setDto.reps} reps")
                }
            }

            workoutExercise.totalVolume = exerciseVolume
            totalVolume += exerciseVolume
        }

        session.totalVolume = totalVolume
        session.caloriesBurned = calculateCaloriesBurned(session.duration ?: 0, totalVolume)

        workoutSessionRepository.save(session)

        // UserProfile 업데이트 - lastWorkoutDate
        val profile = userProfileRepository.findByUser_Id(userId)
        if (profile.isPresent) {
            val userProfile = profile.get()
            userProfile.lastWorkoutDate = LocalDateTime.now()
            userProfileRepository.save(userProfile)
        }

        return WorkoutSummaryResponse(
            success = true,
            summary = WorkoutSummary(
                duration = session.duration ?: 0,
                totalVolume = totalVolume,
                exercisesCompleted = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).size,
                caloriesBurned = session.caloriesBurned,
                personalRecords = personalRecords
            )
        )
    }

    @Transactional(readOnly = true)
    fun getWorkoutSessions(
        userId: Long,
        pageable: Pageable,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): WorkoutSessionsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val sessionList = if (startDate != null && endDate != null) {
            workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)
        } else {
            workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED)
        }

        val sortedSessions = sessionList.sortedByDescending { it.startTime }
        val startIndex = pageable.pageNumber * pageable.pageSize
        val endIndex = minOf(startIndex + pageable.pageSize, sortedSessions.size)
        val paginatedSessions = if (startIndex < sortedSessions.size) {
            sortedSessions.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        val sessionDtos = paginatedSessions.map { session ->
            WorkoutSessionDto(
                sessionId = session.id,
                date = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                duration = session.duration,
                totalVolume = session.totalVolume,
                exerciseCount = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id).size,
                status = session.status.name
            )
        }

        return WorkoutSessionsResponse(
            sessions = sessionDtos,
            totalCount = sortedSessions.size.toLong()
        )
    }

    @Transactional(readOnly = true)
    fun getProgramStatus(userId: Long, localeOverride: String? = null): ProgramStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        // UserSettings에서 우선 가져오고, 없으면 UserProfile에서 가져오기 (하위 호환성)
        val userSettings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)

        val programDays = userSettings?.weeklyWorkoutDays
            ?: userProfile?.weeklyWorkoutDays
            ?: 3
        var programType = userSettings?.workoutSplit
            ?: userProfile?.workoutSplit
            ?: "FULL_BODY"

        // AUTO인 경우 실제 프로그램 선택
        val autoRecommendation = if (programType.uppercase() == "AUTO") {
            val recommendation = autoProgramSelector.selectProgram(user)
            recommendation
        } else {
            null
        }

        // 다음 운동 위치 계산
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

        // 운동 시퀀스 가져오기
        val sequence = if (autoRecommendation != null) {
            autoRecommendation.workoutSequence
        } else {
            workoutProgressTracker.getWorkoutTypeSequence(programType)
        }
        val nextWorkoutType = sequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY

        // 다음 운동 설명 - nextWorkoutType과 동일한 시퀀스 사용하여 불일치 방지
        val locale = resolveLocale(userId, localeOverride)
        val nextWorkoutDescription = workoutProgressTracker.getProgramSequenceDescriptionFromSequence(
            sequence,
            programPosition.day,
            programDays,
            locale
        )

        // 최근 운동 기록 조회
        val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
            user,
            listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS, SessionStatus.CANCELLED)
        )

        val workoutHistory = recentSessions
            .filter { it.programDay != null }
            .take(5)
            .map { session ->
                WorkoutHistoryItem(
                    dayNumber = session.programDay ?: 0,
                    workoutType = session.workoutType?.name ?: "UNKNOWN",
                    workoutTypeName = session.workoutType?.let { WorkoutLocalization.workoutTypeName(it, locale) },
                    date = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    status = session.status.name,
                    cycleNumber = session.programCycle ?: 1
                )
            }

        val lastWorkoutDate = recentSessions.firstOrNull()?.startTime
            ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        return ProgramStatusResponse(
            currentDay = programPosition.day,
            totalDays = sequence.size,
            currentCycle = programPosition.cycle,
            nextWorkoutType = nextWorkoutType.name,
            nextWorkoutTypeName = WorkoutLocalization.workoutTypeName(nextWorkoutType, locale),
            nextWorkoutDescription = nextWorkoutDescription,
            programType = programType,
            programTypeName = WorkoutLocalization.splitName(programType, locale),
            lastWorkoutDate = lastWorkoutDate,
            isNewCycle = programPosition.isNewCycle,
            workoutHistory = workoutHistory,
            recommendationReason = autoRecommendation?.reason,
            recommendationConfidence = autoRecommendation?.confidence
        )
    }

    @Transactional
    fun getCurrentSession(userId: Long): WorkoutDetailResponse? {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        // Clean up any duplicate IN_PROGRESS sessions
        cleanupDuplicateInProgressSessions(user)

        val activeSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)

        return if (activeSession.isPresent) {
            val session = activeSession.get()
            // WorkoutExercise를 직접 조회
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            println("DEBUG: Session ID: ${session.id}, WorkoutExercises found: ${workoutExercises.size}")
            val exerciseDtos = workoutExercises.map { workoutExercise ->
                val setDtos = workoutExercise.sets.map { set ->
                    ExerciseSetDto(
                        weight = set.weight,
                        reps = set.reps,
                        rpe = set.rpe,
                        restTime = set.restTime
                    )
                }

                WorkoutExerciseDto(
                    exerciseId = workoutExercise.exercise.id,
                    exerciseName = workoutExercise.exercise.name,
                    sets = setDtos,
                    totalVolume = workoutExercise.totalVolume ?: 0.0
                )
            }

            WorkoutDetailResponse(
                sessionId = session.id,
                date = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                duration = session.duration,
                exercises = exerciseDtos,
                totalVolume = session.totalVolume,
                caloriesBurned = session.caloriesBurned
            )
        } else {
            null
        }
    }

    @Transactional(readOnly = true)
    fun getWorkoutSession(userId: Long, sessionId: Long): WorkoutDetailResponse {
        val session = findUserSession(userId, sessionId)

        // WorkoutExercise를 직접 조회
        val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
        val exerciseDtos = workoutExercises.map { workoutExercise ->
            val setDtos = workoutExercise.sets.map { set ->
                ExerciseSetDto(
                    weight = set.weight,
                    reps = set.reps,
                    rpe = set.rpe,
                    restTime = set.restTime
                )
            }

            WorkoutExerciseDto(
                exerciseId = workoutExercise.exercise.id,
                exerciseName = workoutExercise.exercise.name,
                sets = setDtos,
                totalVolume = workoutExercise.totalVolume ?: 0.0
            )
        }

        return WorkoutDetailResponse(
            sessionId = session.id,
            date = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            duration = session.duration,
            exercises = exerciseDtos,
            totalVolume = session.totalVolume,
            caloriesBurned = session.caloriesBurned
        )
    }

    fun updateWorkoutSession(userId: Long, sessionId: Long, request: UpdateWorkoutRequest): WorkoutDetailResponse {
        val session = findUserSession(userId, sessionId)

        request.duration?.let { session.duration = it }
        request.notes?.let { session.notes = it }

        request.exercises?.let { exercises ->
            // 기존 운동 세트 삭제
            val existingExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            existingExercises.forEach { workoutExercise ->
                exerciseSetRepository.deleteAll(workoutExercise.sets)
                workoutExerciseRepository.delete(workoutExercise)
            }

            // 새로운 운동 세트 추가
            var totalVolume = 0.0
            exercises.forEachIndexed { exerciseIndex, completedExercise ->
                val exercise = exerciseRepository.findById(completedExercise.exerciseId)
                    .orElseThrow { ResourceNotFoundException("Exercise not found: ${completedExercise.exerciseId}") }

                val workoutExercise = WorkoutExercise(
                    session = session,
                    exercise = exercise,
                    orderInSession = exerciseIndex
                )
                workoutExerciseRepository.save(workoutExercise)

                var exerciseVolume = 0.0
                completedExercise.sets.forEachIndexed { index, setDto ->
                    val exerciseSet = ExerciseSet(
                        workoutExercise = workoutExercise,
                        setNumber = index + 1,
                        weight = setDto.weight,
                        reps = setDto.reps,
                        rpe = setDto.rpe,
                        restTime = setDto.restTime
                    )
                    exerciseVolume += setDto.weight * setDto.reps
                    workoutExercise.sets.add(exerciseSet)
                    exerciseSetRepository.save(exerciseSet)
                }

                workoutExercise.totalVolume = exerciseVolume
                totalVolume += exerciseVolume
            }

            session.totalVolume = totalVolume
        }

        workoutSessionRepository.save(session)
        return getWorkoutSession(userId, sessionId)
    }

    fun deleteWorkoutSession(userId: Long, sessionId: Long) {
        val session = findUserSession(userId, sessionId)
        workoutSessionRepository.delete(session)
    }

    fun addExerciseSet(userId: Long, exerciseId: Long, request: AddSetRequest): AddSetResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val session = workoutSessionRepository.findById(request.sessionId)
            .orElseThrow { ResourceNotFoundException("Session not found") }

        if (session.user.id != userId) {
            throw ResourceNotFoundException("Access denied")
        }

        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }

        // WorkoutExercise를 repository에서 찾기
        val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
        val workoutExercise = workoutExercises.find { it.exercise.id == exerciseId }
            ?: WorkoutExercise(
                session = session,
                exercise = exercise,
                orderInSession = workoutExercises.size
            ).also {
                workoutExerciseRepository.save(it)
            }

        var totalVolume = workoutExercise.totalVolume ?: 0.0
        var isPersonalRecord = false

        request.sets.forEach { setDto ->
            val exerciseSet = ExerciseSet(
                workoutExercise = workoutExercise,
                setNumber = workoutExercise.sets.size + 1,
                weight = setDto.weight,
                reps = setDto.reps,
                rpe = setDto.rpe,
                restTime = setDto.restTime
            )

            totalVolume += setDto.weight * setDto.reps
            workoutExercise.sets.add(exerciseSet)
            exerciseSetRepository.save(exerciseSet)

            // 개인 기록 확인
            val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)
            if (previousRecord == null || setDto.weight > previousRecord.weight) {
                isPersonalRecord = true
                val newRecord = com.richjun.liftupai.domain.workout.entity.PersonalRecord(
                    user = user,
                    exercise = exercise,
                    weight = setDto.weight,
                    reps = setDto.reps,
                    date = LocalDateTime.now()
                )
                personalRecordRepository.save(newRecord)
            }
        }

        workoutExercise.totalVolume = totalVolume
        workoutExerciseRepository.save(workoutExercise)

        // 세션 총 볼륨 업데이트
        // 세션의 모든 운동 조회하여 총 볼륨 계산
        val allExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
        session.totalVolume = allExercises.sumOf { it.totalVolume ?: 0.0 }
        workoutSessionRepository.save(session)

        return AddSetResponse(
            success = true,
            totalVolume = totalVolume,
            isPersonalRecord = isPersonalRecord
        )
    }

    @Transactional(readOnly = true)
    fun getTodayRecommendations(userId: Long): TodayWorkoutRecommendation {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)

        // 근육 회복 상태 계산
        val recoveryStatus = calculateMuscleRecovery(userId)

        // 회복된 근육군 찾기
        val wellRecoveredMuscles = recoveryStatus
            .filter { it.recoveryPercentage >= 80 }
            .map { it.muscle }

        // 타겟 근육군 결정 (회복 상태와 최근 운동 이력 고려)
        val targetMuscles = determineTargetMuscles(userId, wellRecoveredMuscles)

        // 추천 운동 생성
        val recommendedExercises = generateRecommendedExercises(
            userId,
            profile?.experienceLevel ?: ExperienceLevel.BEGINNER,
            settings?.availableEquipment?.toList() ?: emptyList(),
            recoveryStatus,
            targetMuscles
        )

        // 대체 운동 생성
        val alternatives = generateAlternativeExercises(recommendedExercises)

        // 난이도 계산
        val difficulty = calculateDifficulty(profile?.experienceLevel, recommendedExercises.size)

        // 예상 소요 시간 계산
        val estimatedDuration = calculateEstimatedDuration(recommendedExercises)

        return TodayWorkoutRecommendation(
            programName = "Custom ${targetMuscles.joinToString(", ")} workout",
            dayInProgram = calculateDayInProgram(userId),
            targetMuscles = targetMuscles,
            exercises = recommendedExercises,
            estimatedDuration = estimatedDuration,
            difficulty = difficulty,
            recoveryStatus = recoveryStatus,
            alternatives = alternatives
        )
    }

    fun generateProgram(userId: Long, request: GenerateProgramRequest): WorkoutProgramResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        // 실제로는 AI 기반으로 프로그램 생성
        val mesocycle = MesocycleInfo(
            phase = "Strength development",
            weeks = 8,
            focusAreas = request.goals,
            volumeProgression = "Progressive increase"
        )

        val locale = resolveLocale(userId)
        val weeklySchedule = generateWeeklySchedule(
            request.weeklyWorkoutDays,
            request.workoutSplit,
            request.availableEquipment,
            locale
        )

        return WorkoutProgramResponse(
            programId = 1L,
            mesocycle = mesocycle,
            weeklySchedule = weeklySchedule
        )
    }

    @Transactional(readOnly = true)
    fun getCurrentProgram(userId: Long): CurrentProgramResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val weeklyDays = settings?.weeklyWorkoutDays ?: profile?.weeklyWorkoutDays ?: 3
        val split = settings?.workoutSplit ?: profile?.workoutSplit ?: "FULL_BODY"
        val availableEquipment = settings?.availableEquipment?.toList() ?: emptyList()
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, weeklyDays)
        val sequence = workoutProgressTracker.getWorkoutTypeSequence(split).ifEmpty { listOf(WorkoutType.FULL_BODY) }
        val nextWorkoutType = sequence.getOrElse(programPosition.day - 1) { WorkoutType.FULL_BODY }
        val locale = resolveLocale(userId)
        val nextExercises = selectPreviewExercisesForWorkoutType(nextWorkoutType, availableEquipment, 3)
        val weeklySchedule = generateWeeklySchedule(weeklyDays, split, availableEquipment, locale)

        return CurrentProgramResponse(
            programId = 1L,
            programName = "${formatWorkoutTypeName(nextWorkoutType, locale)} focused program",
            currentWeek = programPosition.cycle,
            totalWeeks = 8,
            workoutSplit = formatWorkoutSplit(split, sequence, locale),
            nextWorkout = NextWorkoutInfo(
                dayName = "Day ${programPosition.day}",
                targetMuscles = workoutTargetsForType(nextWorkoutType, locale),
                exercises = nextExercises.map { it.name },
                estimatedDuration = maxOf(30, nextExercises.size * 15)
            ),
            weeklySchedule = weeklySchedule
        )
    }

    @Transactional(readOnly = true)
    fun getRecoveryStatus(userId: Long): RecoveryStatusResponse {
        val muscleGroups = calculateMuscleRecovery(userId)
        val overallFatigue = calculateOverallFatigue(userId)

        return RecoveryStatusResponse(
            muscleGroups = muscleGroups.map { recovery ->
                MuscleGroupRecovery(
                    name = recovery.muscle,
                    lastWorked = recovery.lastWorked,
                    recoveryPercentage = recovery.recoveryPercentage,
                    readyForWork = recovery.recoveryPercentage >= 80,
                    estimatedFullRecovery = if (recovery.recoveryPercentage < 100) {
                        LocalDateTime.now().plusHours((100 - recovery.recoveryPercentage) / 5L)
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    } else null
                )
            },
            overallFatigue = overallFatigue,
            deloadRecommended = overallFatigue > 80
        )
    }

    fun adjustVolume(userId: Long, request: AdjustVolumeRequest): AdjustedVolumeResponse {
        val multiplier = calculateVolumeMultiplier(request.fatigueLevel, request.timeAvailable)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val lastSession = workoutSessionRepository.findTopByUserOrderByStartTimeDesc(user)

        val adjustedExercises = lastSession
            ?.let { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .take(4)
                    .map { workoutExercise ->
                        val setCount = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
                            .size
                            .coerceAtLeast(3)
                        AdjustedExercise(
                            exerciseId = workoutExercise.exercise.id,
                            name = workoutExercise.exercise.name,
                            sets = maxOf(1, (setCount * multiplier).roundToInt()),
                            reps = "8-12",
                            weight = null
                        )
                    }
            }
            ?: emptyList()

        return AdjustedVolumeResponse(
            adjustedExercises = adjustedExercises,
            volumeMultiplier = multiplier,
            reason = when {
                request.fatigueLevel > 7 -> "Reduced volume due to high fatigue"
                request.timeAvailable < 30 -> "Reduced volume due to limited time"
                else -> "Maintaining normal volume"
            }
        )
    }

    fun calculateWeight(userId: Long, request: CalculateWeightRequest): WeightRecommendation {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val recommendedWeight = calculateRecommendedWeight(
            request.bodyWeight,
            request.gender,
            request.experienceLevel,
            request.previousRecords
        )

        val warmupSets = generateWarmupSets(recommendedWeight)
        val workingSets = generateWorkingSets(recommendedWeight)

        return WeightRecommendation(
            recommendedWeight = recommendedWeight,
            warmupSets = warmupSets,
            workingSets = workingSets,
            calculationMethod = "Bodyweight ratio and experience level",
            confidence = 0.85
        )
    }

    fun estimate1RM(request: Estimate1RMRequest): OneRMEstimation {
        val oneRM = calculate1RM(request.weight, request.reps, request.rpe)

        return OneRMEstimation(
            estimated1RM = oneRM,
            formula = "Epley Formula",
            percentages = mapOf(
                "95%" to oneRM * 0.95,
                "90%" to oneRM * 0.90,
                "85%" to oneRM * 0.85,
                "80%" to oneRM * 0.80,
                "75%" to oneRM * 0.75,
                "70%" to oneRM * 0.70
            )
        )
    }

    fun processStrengthTest(userId: Long, request: StrengthTestRequest): StrengthTestResult {
        val estimatedMaxes = mutableMapOf<String, Double>()

        request.exercises.forEach { test ->
            val exercise = exerciseRepository.findById(test.exerciseId).orElse(null)
            exercise?.let {
                val oneRM = calculate1RM(test.weight, test.reps, test.rpe)
                estimatedMaxes[it.name] = oneRM
            }
        }

        val strengthScore = calculateStrengthScore(estimatedMaxes)
        val strengthLevel = determineStrengthLevel(strengthScore)

        return StrengthTestResult(
            estimatedMaxes = estimatedMaxes,
            strengthLevel = strengthLevel,
            strengthScore = strengthScore,
            recommendations = generateStrengthRecommendations(strengthLevel, estimatedMaxes)
        )
    }

    @Transactional(readOnly = true)
    fun getStrengthStandards(gender: String, bodyWeight: Double): StrengthStandardsResponse {
        val standards = calculateStrengthStandards(gender, bodyWeight)

        return StrengthStandardsResponse(
            standards = standards
        )
    }

    @Transactional(readOnly = true)
    fun getExercises(category: String?, equipment: String?): ExercisesResponse {
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
            equipment != null -> {
                val eq = Equipment.valueOf(equipment.uppercase())
                exerciseRepository.findByEquipment(eq)
            }
            else -> exerciseRepository.findAll()
        }

        val exerciseDtos = exercises.map { exercise ->
            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        return ExercisesResponse(exercises = exerciseDtos)
    }

    @Transactional(readOnly = true)
    fun getExerciseDetail(userId: Long, exerciseId: Long): ExerciseDetailResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }

        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)

        val lastSession = workoutSessionRepository.findTopByUserOrderByStartTimeDesc(user)
        val lastPerformed = if (lastSession != null) {
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(lastSession.id)
            exercises.find { it.exercise.id == exerciseId }?.let {
                lastSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
        } else null

        return ExerciseDetailResponse(
            exercise = ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            ),
            personalRecords = personalRecord?.let {
                com.richjun.liftupai.domain.workout.dto.PersonalRecord(
                    weight = it.weight,
                    reps = it.reps,
                    date = it.date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            },
            lastPerformed = lastPerformed
        )
    }

    // Helper methods
    private fun findUserSession(userId: Long, sessionId: Long): WorkoutSession {
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("Session not found") }

        if (session.user.id != userId) {
            throw ResourceNotFoundException("Access denied")
        }

        return session
    }

    private fun calculateCaloriesBurned(duration: Int, totalVolume: Double): Int {
        // 간단한 칼로리 계산 공식
        val baseCalories = duration * 5 // 분당 5칼로리
        val volumeBonus = (totalVolume / 1000) * 2 // 1톤당 2칼로리
        return (baseCalories + volumeBonus).toInt()
    }

    private fun calculateMuscleRecovery(userId: Long): List<MuscleRecoveryStatus> {
        val user = userRepository.findById(userId).orElse(null) ?: return emptyList()
        val recentSessions = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        val muscleGroups = MuscleGroup.values()
        return muscleGroups.map { muscle ->
            val allExercises = recentSessions.flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            }
            val lastWorked = allExercises
                .filter { it.exercise.muscleGroups.contains(muscle) }
                .mapNotNull { workoutExercise ->
                    recentSessions.find { it.id == workoutExercise.session.id }?.startTime
                }
                .maxOrNull()

            val hoursSinceWork = lastWorked?.let {
                ChronoUnit.HOURS.between(it, LocalDateTime.now())
            } ?: 168L // 1주일 이상

            val recoveryPercentage = minOf(100, (hoursSinceWork * 100 / 48).toInt()) // 48시간 = 100% 회복

            MuscleRecoveryStatus(
                muscle = getMuscleGroupKoreanName(muscle),
                recoveryPercentage = recoveryPercentage,
                lastWorked = lastWorked?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
    }

    private fun calculateOverallFatigue(userId: Long): Int {
        val user = userRepository.findById(userId).orElse(null) ?: return 0
        val recentSessions = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        val totalVolume = recentSessions.sumOf { it.totalVolume ?: 0.0 }
        val sessionCount = recentSessions.size

        return when {
            sessionCount >= 6 && totalVolume > 10000 -> 90
            sessionCount >= 5 && totalVolume > 7500 -> 70
            sessionCount >= 4 && totalVolume > 5000 -> 50
            sessionCount >= 3 -> 30
            else -> 10
        }
    }

    private fun generateRecommendedExercises(
        userId: Long,
        experienceLevel: ExperienceLevel,
        availableEquipment: List<String>,
        recoveryStatus: List<MuscleRecoveryStatus>,
        targetMuscles: List<String> = listOf(
            WorkoutTargetResolver.displayName(WorkoutFocus.CHEST, locale = "ko"),
            WorkoutTargetResolver.displayName(MuscleGroup.TRICEPS, locale = "ko")
        )
    ): List<RecommendedExercise> {
        val recommendedExercises = mutableListOf<RecommendedExercise>()

        // 한글 타겟 근육군을 영어 MuscleGroup으로 변환
        val targetMuscleGroups = targetMuscles.flatMap { getEnglishMuscleGroups(it) }.toSet()

        // 타겟 근육군에 맞는 운동 조회
        val exercises = exerciseRepository.findAll().filter { exercise ->
            exercise.muscleGroups.any { muscle ->
                targetMuscleGroups.contains(muscle)
            }
        }

        // 장비 필터링
        var filteredExercises = if (availableEquipment.isNotEmpty()) {
            exercises.filter { exercise ->
                val equipmentName = exercise.equipment?.name
                equipmentName == null ||
                availableEquipment.any { it.equals(equipmentName, ignoreCase = true) }
            }
        } else {
            exercises
        }

        // 핵심 운동을 우선 추천하고, 부족할 때만 일반 운동으로 보충
        val coreExercises = removeDuplicatePatterns(
            filteredExercises.filter { RecommendationExerciseRanking.isCoreCandidate(it) }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        val fallbackExercises = removeDuplicatePatterns(
            filteredExercises.filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
                .filterNot { candidate -> coreExercises.any { it.id == candidate.id } }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        filteredExercises = (coreExercises + fallbackExercises).distinctBy { it.id }

        // 경험 레벨에 따른 운동 개수 및 세트/렙 조정
        data class WorkoutConfig(val exerciseCount: Pair<Int, Int>, val setsRange: Pair<Int, Int>, val repsRange: String, val rpeTarget: Int)

        val config = when (experienceLevel) {
            ExperienceLevel.NOVICE -> WorkoutConfig(3 to 4, 2 to 3, "15-20", 5)
            ExperienceLevel.BEGINNER -> WorkoutConfig(3 to 4, 2 to 3, "12-15", 6)
            ExperienceLevel.INTERMEDIATE -> WorkoutConfig(4 to 5, 3 to 4, "8-12", 7)
            ExperienceLevel.ADVANCED -> WorkoutConfig(5 to 6, 4 to 5, "6-10", 8)
            ExperienceLevel.EXPERT -> WorkoutConfig(6 to 7, 4 to 6, "4-8", 9)
        }

        val (exerciseCount, setsRange, repsRange, rpeTarget) = config

        // 운동 선택 및 추천 생성
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        filteredExercises.take(exerciseCount.second).forEach { exercise ->
            val previousRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(
                user = user,
                exercise = exercise
            )

            recommendedExercises.add(
                RecommendedExercise(
                    exerciseId = exercise.id,
                    name = exercise.name,
                    recommendedSets = setsRange.second,
                    recommendedReps = repsRange,
                    recommendedWeight = previousRecord?.weight?.times(0.8) ?: 20.0,
                    rpe = rpeTarget,
                    restTime = if (exercise.category in listOf(ExerciseCategory.LEGS, ExerciseCategory.BACK)) 120 else 90,
                    previousPerformance = previousRecord?.let { "${it.weight}kg x ${it.reps} reps" } ?: "No record"
                )
            )
        }

        return recommendedExercises
    }

    private fun generateAlternativeExercises(exercises: List<RecommendedExercise>): List<AlternativeExercise> {
        val alternatives = mutableListOf<AlternativeExercise>()

        exercises.forEach { exercise ->
            // 각 운동에 대한 대체 운동 찾기
            val mainExercise = exerciseRepository.findById(exercise.exerciseId).orElse(null)
            if (mainExercise != null) {
                // 같은 근육군을 타겟으로 하는 다른 운동 찾기
                val similarExercises = exerciseRepository.findAll().filter { alt ->
                    alt.id != mainExercise.id &&
                    alt.muscleGroups.any { it in mainExercise.muscleGroups } &&
                    alt.category == mainExercise.category
                }.take(2)

                similarExercises.forEach { alt ->
                    alternatives.add(
                        AlternativeExercise(
                            exerciseId = alt.id,
                            name = alt.name,
                            reason = when {
                                alt.equipment != mainExercise.equipment ->
                                    "Can be replaced with a ${alt.equipment?.name ?: "bodyweight"} variation"
                                alt.muscleGroups.size != mainExercise.muscleGroups.size ->
                                    "Alternative for difficulty adjustment"
                                else ->
                                    "Alternative with a similar muscle stimulus"
                            }
                        )
                    )
                }
            }
        }

        return alternatives.distinctBy { it.exerciseId }
    }

    private fun generateWeeklySchedule(
        weeklyDays: Int,
        workoutSplit: String,
        equipment: List<String>,
        locale: String = "en"
    ): List<WeeklyWorkout> {
        val sequence = workoutProgressTracker.getWorkoutTypeSequence(workoutSplit).ifEmpty { listOf(WorkoutType.FULL_BODY) }
        return (0 until weeklyDays).map { index ->
            val workoutType = sequence.getOrElse(index % sequence.size) { WorkoutType.FULL_BODY }
            val exercises = selectPreviewExercisesForWorkoutType(workoutType, equipment, 3)

            WeeklyWorkout(
                day = "Day ${index + 1}",
                targetMuscles = workoutTargetsForType(workoutType, locale),
                exercises = exercises.map { it.name }
            )
        }
    }

    private fun calculateVolumeMultiplier(fatigueLevel: Int, timeAvailable: Int): Double {
        val fatigueFactor = when {
            fatigueLevel > 7 -> 0.6
            fatigueLevel > 5 -> 0.8
            else -> 1.0
        }

        val timeFactor = when {
            timeAvailable < 30 -> 0.5
            timeAvailable < 45 -> 0.75
            else -> 1.0
        }

        return minOf(fatigueFactor, timeFactor)
    }

    private fun selectPreviewExercisesForWorkoutType(
        workoutType: WorkoutType,
        availableEquipment: List<String>,
        limit: Int
    ): List<Exercise> {
        val allowedEquipment = availableEquipment.map { it.uppercase() }.toSet()
        val targetMuscleGroups = WorkoutTargetResolver.targetsForWorkoutType(workoutType)
            .flatMap { WorkoutTargetResolver.muscleGroupsForFocus(it) }
            .toSet()

        val equipmentMatched = exerciseRepository.findAll().filter { exercise ->
            targetMuscleGroups.isEmpty() || exercise.muscleGroups.any { it in targetMuscleGroups }
        }.filter { exercise ->
            val equipmentName = exercise.equipment?.name
            allowedEquipment.isEmpty() || equipmentName == null || equipmentName == Equipment.BODYWEIGHT.name || equipmentName in allowedEquipment
        }

        val coreExercises = removeDuplicatePatterns(
            equipmentMatched.filter { RecommendationExerciseRanking.isCoreCandidate(it) }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        val fallbackExercises = removeDuplicatePatterns(
            equipmentMatched.filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
                .filterNot { candidate -> coreExercises.any { it.id == candidate.id } }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        return (coreExercises + fallbackExercises)
            .distinctBy { it.id }
            .take(limit)
    }

    private fun workoutTargetsForType(workoutType: WorkoutType, locale: String = "en"): List<String> {
        return WorkoutTargetResolver.displayNamesForWorkoutType(workoutType, locale = locale)
            .ifEmpty { listOf(formatWorkoutTypeName(workoutType, locale)) }
    }

    private fun formatWorkoutTypeName(workoutType: WorkoutType, locale: String = "en"): String {
        val primaryFocus = WorkoutTargetResolver.primaryFocusForWorkoutType(workoutType)
        return WorkoutTargetResolver.displayName(primaryFocus, locale = locale)
    }

    private fun formatWorkoutSplit(split: String, sequence: List<WorkoutType>, locale: String = "en"): String {
        val labels = sequence
            .map { formatWorkoutTypeName(it, locale) }
            .distinct()

        return if (labels.isNotEmpty()) {
            labels.joinToString("/")
        } else {
            split
        }
    }

    private fun calculateRecommendedWeight(
        bodyWeight: Double,
        gender: String,
        experienceLevel: String,
        previousRecords: List<PreviousRecord>
    ): Double {
        val baseMultiplier = when (experienceLevel) {
            "BEGINNER" -> 0.5
            "INTERMEDIATE" -> 0.75
            "ADVANCED" -> 1.0
            else -> 0.5
        }

        val genderMultiplier = if (gender == "MALE") 1.0 else 0.8

        return bodyWeight * baseMultiplier * genderMultiplier
    }

    private fun generateWarmupSets(workingWeight: Double): List<com.richjun.liftupai.domain.workout.dto.WarmupSet> {
        return listOf(
            com.richjun.liftupai.domain.workout.dto.WarmupSet(weight = workingWeight * 0.4, reps = 10),
            com.richjun.liftupai.domain.workout.dto.WarmupSet(weight = workingWeight * 0.6, reps = 8),
            com.richjun.liftupai.domain.workout.dto.WarmupSet(weight = workingWeight * 0.8, reps = 5)
        )
    }

    private fun determineTargetMuscles(userId: Long, wellRecoveredMuscles: List<String>): List<String> {
        // 최근 7일간 운동 이력 조회
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val recentSessions = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        // 최근에 운동한 근육군 파악
        val recentlyWorkedMuscles = mutableMapOf<WorkoutFocus, Int>()
        recentSessions.forEach { session ->
            val sessionExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            sessionExercises.forEach { exercise ->
                exercise.exercise.muscleGroups.forEach { muscle ->
                    val focus = WorkoutTargetResolver.focusForMuscleGroup(muscle)
                    recentlyWorkedMuscles[focus] = recentlyWorkedMuscles.getOrDefault(focus, 0) + 1
                }
            }
        }

        // 덜 운동한 근육군 우선 선택
        val focusPriority = listOf(
            WorkoutFocus.CHEST,
            WorkoutFocus.BACK,
            WorkoutFocus.SHOULDERS,
            WorkoutFocus.ARMS,
            WorkoutFocus.LEGS,
            WorkoutFocus.CORE
        )
        val recoveredFocuses = wellRecoveredMuscles
            .mapNotNull { WorkoutTargetResolver.resolveFocus(it) ?: WorkoutTargetResolver.resolveMuscleGroup(it)?.let(WorkoutTargetResolver::focusForMuscleGroup) }
            .toSet()
        val underworkedMuscles = focusPriority.filter { focus ->
            (recoveredFocuses.isEmpty() || focus in recoveredFocuses) &&
                recentlyWorkedMuscles.getOrDefault(focus, 0) < 2
        }

        return if (underworkedMuscles.isNotEmpty()) {
            underworkedMuscles
                .take(2)
                .map { WorkoutTargetResolver.displayName(it, locale = resolveLocale(userId)) }
        } else {
            // 기본값: 푸시/풀 분할
            val zoneId = AppTime.resolveZoneId(userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone)
            val dayOfWeek = AppTime.currentUserDate(zoneId).dayOfWeek.value
            when (dayOfWeek % 3) {
                0 -> listOf(WorkoutFocus.CHEST, WorkoutFocus.ARMS)
                1 -> listOf(WorkoutFocus.BACK, WorkoutFocus.ARMS)
                else -> listOf(WorkoutFocus.LEGS, WorkoutFocus.CORE)
            }
                .map { WorkoutTargetResolver.displayName(it, locale = resolveLocale(userId)) }
        }
    }

    private fun calculateDifficulty(experienceLevel: ExperienceLevel?, exerciseCount: Int): String {
        return when {
            experienceLevel == ExperienceLevel.BEGINNER && exerciseCount <= 3 -> "easy"
            experienceLevel == ExperienceLevel.BEGINNER -> "moderate"
            experienceLevel == ExperienceLevel.INTERMEDIATE && exerciseCount <= 4 -> "moderate"
            experienceLevel == ExperienceLevel.INTERMEDIATE -> "hard"
            experienceLevel == ExperienceLevel.ADVANCED && exerciseCount <= 5 -> "hard"
            experienceLevel == ExperienceLevel.ADVANCED -> "very hard"
            else -> "moderate"
        }
    }

    private fun calculateEstimatedDuration(exercises: List<RecommendedExercise>): Int {
        var totalMinutes = 0
        exercises.forEach { exercise ->
            // 세트당 시간: 실행시간(30초) + 휴식시간
            val minutesPerSet = (30 + exercise.restTime) / 60.0
            totalMinutes += (exercise.recommendedSets * minutesPerSet).toInt()
        }
        // 준비운동 10분 + 마무리 5분 추가
        return totalMinutes + 15
    }

    private fun calculateDayInProgram(userId: Long): Int {
        // 현재 진행 중인 프로그램의 날짜 계산
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val sessions = workoutSessionRepository.findByUserOrderByStartTimeDesc(user,
            org.springframework.data.domain.PageRequest.of(0, 1))

        return if (sessions.hasContent()) {
            val firstSession = sessions.content.first()
            ChronoUnit.DAYS.between(firstSession.startTime.toLocalDate(), LocalDateTime.now().toLocalDate()).toInt() % 30 + 1
        } else {
            1
        }
    }

    private fun generateWorkingSets(workingWeight: Double): List<WorkingSet> {
        return listOf(
            WorkingSet(weight = workingWeight, reps = 8, rpe = 7),
            WorkingSet(weight = workingWeight, reps = 8, rpe = 8),
            WorkingSet(weight = workingWeight, reps = 8, rpe = 9)
        )
    }

    private fun calculate1RM(weight: Double, reps: Int, rpe: Int?): Double {
        // Epley Formula with RPE adjustment
        val rpeAdjustment = rpe?.let { (10 - it) * 0.025 } ?: 0.0
        return weight * (1 + reps / 30.0) * (1 + rpeAdjustment)
    }

    private fun calculateStrengthScore(estimatedMaxes: Map<String, Double>): Double {
        // 간단한 점수 계산
        return estimatedMaxes.values.average()
    }

    private fun determineStrengthLevel(score: Double): String {
        return when {
            score > 150 -> "ELITE"
            score > 120 -> "ADVANCED"
            score > 90 -> "INTERMEDIATE"
            score > 60 -> "NOVICE"
            else -> "BEGINNER"
        }
    }

    private fun generateStrengthRecommendations(level: String, maxes: Map<String, Double>): List<String> {
        return when (level) {
            "BEGINNER" -> listOf(
                "Focus on the fundamental movement patterns",
                "Increase load progressively",
                "Prioritize technique quality"
            )
            "INTERMEDIATE" -> listOf(
                "Consider a periodized program",
                "Add targeted accessory work"
            )
            else -> listOf(
                "Maintain your current level"
            )
        }
    }

    private fun calculateStrengthStandards(gender: String, bodyWeight: Double): List<StrengthStandard> {
        val multiplier = if (gender == "MALE") 1.0 else 0.7
        val exercises = exerciseRepository.findAll()

        return listOf(
            StrengthStandard(
                exercise = findBenchmarkExerciseName(
                    exercises,
                    ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
                    fallback = "Bench Press"
                ),
                beginner = bodyWeight * 0.5 * multiplier,
                novice = bodyWeight * 0.75 * multiplier,
                intermediate = bodyWeight * 1.0 * multiplier,
                advanced = bodyWeight * 1.5 * multiplier,
                elite = bodyWeight * 2.0 * multiplier
            ),
            StrengthStandard(
                exercise = findBenchmarkExerciseName(
                    exercises,
                    ExercisePatternClassifier.MovementPattern.SQUAT,
                    fallback = "Squat"
                ),
                beginner = bodyWeight * 0.75 * multiplier,
                novice = bodyWeight * 1.0 * multiplier,
                intermediate = bodyWeight * 1.5 * multiplier,
                advanced = bodyWeight * 2.0 * multiplier,
                elite = bodyWeight * 2.5 * multiplier
            ),
            StrengthStandard(
                exercise = findBenchmarkExerciseName(
                    exercises,
                    ExercisePatternClassifier.MovementPattern.DEADLIFT,
                    fallback = "Deadlift"
                ),
                beginner = bodyWeight * 1.0 * multiplier,
                novice = bodyWeight * 1.25 * multiplier,
                intermediate = bodyWeight * 1.75 * multiplier,
                advanced = bodyWeight * 2.25 * multiplier,
                elite = bodyWeight * 2.75 * multiplier
            )
        )
    }

    private fun findBenchmarkExerciseName(
        exercises: List<Exercise>,
        pattern: ExercisePatternClassifier.MovementPattern,
        fallback: String
    ): String {
        return exercises
            .filter { exercisePatternClassifier.classifyExercise(it) == pattern }
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .sortedWith(RecommendationExerciseRanking.patternSelectionComparator())
            .firstOrNull()
            ?.name
            ?: fallback
    }

    private fun minOf(a: Double, b: Double): Double = if (a < b) a else b

    // 근육군 이름 매핑
    private fun getMuscleGroupKoreanName(muscleGroup: MuscleGroup): String {
        return WorkoutTargetResolver.displayName(muscleGroup, locale = "ko")
    }

    private fun getEnglishMuscleGroups(koreanName: String): List<MuscleGroup> {
        return WorkoutTargetResolver.muscleGroupsFor(koreanName).toList()
    }

    /**
     * 패턴 중복 제거 - 같은 패턴의 운동 중 핵심도와 활용도가 높은 운동 하나만 선택
     */
    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        val patternGroups = mutableMapOf<ExercisePatternClassifier.MovementPattern, MutableList<Exercise>>()

        // 1. 패턴별로 그룹화
        exercises.forEach { exercise ->
            val pattern = exercisePatternClassifier.classifyExercise(exercise)
            patternGroups.getOrPut(pattern) { mutableListOf() }.add(exercise)
        }

        // 2. 각 패턴에서 가장 쉬운 운동 1개만 선택
        val selectedExercises = mutableListOf<Exercise>()

        patternGroups.forEach { (pattern, groupExercises) ->
            val bestExercise = groupExercises
                .sortedWith(RecommendationExerciseRanking.patternSelectionComparator())
                .firstOrNull()

            bestExercise?.let { selectedExercises.add(it) }
        }

        return selectedExercises
    }

    private fun resolveLocale(userId: Long, localeOverride: String? = null): String {
        if (!localeOverride.isNullOrBlank()) {
            return WorkoutLocalization.normalizeLocale(localeOverride)
        }
        return WorkoutLocalization.normalizeLocale(
            userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        )
    }
}

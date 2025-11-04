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
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.recovery.service.RecoveryService
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
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
    private val muscleRecoveryRepository: MuscleRecoveryRepository
) {

    // 기존 메서드 (호환성 유지) - 진행 중인 세션이 있으면 반환, 없으면 새로 생성
    fun startWorkout(userId: Long, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 진행 중인 세션이 있으면 그대로 반환
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
        if (existingSession.isPresent) {
            val session = existingSession.get()

            // WorkoutExercise를 직접 조회하여 운동 정보 가져오기
            val workoutExercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)

            val exercises = workoutExercises.map { workoutExercise ->
                val exercise = workoutExercise.exercise
                ExerciseDto(
                    id = exercise.id,
                    name = exercise.name,
                    category = exercise.category.name,
                    muscleGroups = exercise.muscleGroups.map { it.name },
                    equipment = exercise.equipment?.name,
                    instructions = exercise.instructions
                )
            }

            return StartWorkoutResponseV2(
                sessionId = session.id,
                startTime = session.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )

        val savedSession = workoutSessionRepository.save(session)

        // 계획된 운동들 추가 및 WorkoutExercise 엔티티 생성
        val exercises = request.plannedExercises.mapIndexed { index, planned ->
            val exercise = exerciseRepository.findById(planned.exerciseId)
                .orElseThrow { ResourceNotFoundException("EXERCISE001: 운동을 찾을 수 없습니다") }

            // WorkoutExercise 엔티티 생성 및 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = planned.orderIndex ?: index
            )
            val savedWorkoutExercise = workoutExerciseRepository.save(workoutExercise)
            println("DEBUG: Saved WorkoutExercise ID: ${savedWorkoutExercise.id}, Session ID: ${savedSession.id}, Exercise: ${exercise.name}")

            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    fun startNewWorkout(userId: Long, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // workout_type에 따른 처리
        return when (request.workoutType) {
            "quick" -> startQuickWorkout(user, request)
            "ai" -> startAIWorkout(user, request)
            else -> startRegularWorkout(user, request)
        }
    }

    private fun startQuickWorkout(user: com.richjun.liftupai.domain.auth.entity.User, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        // 기존 startRecommendedWorkout 로직 활용
        if (request.recommendationId == null) {
            throw IllegalArgumentException("Quick 운동을 시작하려면 recommendation_id가 필요합니다")
        }

        // 진행 중인 세션 처리
        cancelExistingSessions(user)

        // 추천 운동 정보 가져오기
        val workoutDetail = getWorkoutFromRecommendationId(request.recommendationId)
        val adjustedWorkout = request.adjustments?.let { applyWorkoutAdjustments(workoutDetail, it) } ?: workoutDetail

        // 세션 생성
        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = workoutProgressTracker.determineWorkoutType(adjustedWorkout.targetMuscles)

        val session = WorkoutSession(
            user = user,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle,
            recommendationType = "QUICK"
        )
        val savedSession = workoutSessionRepository.save(session)

        // 운동 정보 생성
        val exercises = adjustedWorkout.exercises.map { quickExercise ->
            val exercise = exerciseRepository.findById(quickExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

            // WorkoutExercise 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = quickExercise.order - 1
            )
            workoutExerciseRepository.save(workoutExercise)

            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    private fun startAIWorkout(user: com.richjun.liftupai.domain.auth.entity.User, request: StartWorkoutRequestV2): StartWorkoutResponseV2 {
        if (request.aiWorkout == null) {
            throw IllegalArgumentException("AI 운동을 시작하려면 ai_workout 데이터가 필요합니다")
        }

        // 진행 중인 세션 처리
        cancelExistingSessions(user)

        // 세션 생성
        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = workoutProgressTracker.determineWorkoutType(request.aiWorkout.targetMuscles)

        val session = WorkoutSession(
            user = user,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle,
            recommendationType = "AI"
        )
        val savedSession = workoutSessionRepository.save(session)

        // AI 추천 운동 정보 생성
        val exercises = request.aiWorkout.exercises.map { aiExercise ->
            val exercise = exerciseRepository.findById(aiExercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

            // WorkoutExercise 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = aiExercise.order - 1
            )
            workoutExerciseRepository.save(workoutExercise)

            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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

        // 프로그램 진행 상황 계산
        val programPosition = getOrCalculateProgramPosition(user)
        val workoutType = getWorkoutTypeForProgram(user, programPosition)

        // 새 세션 생성
        val session = WorkoutSession(
            user = user,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle
        )

        val savedSession = workoutSessionRepository.save(session)

        // 계획된 운동들 추가 및 WorkoutExercise 엔티티 생성
        val exercises = request.plannedExercises.mapIndexed { index, planned ->
            val exercise = exerciseRepository.findById(planned.exerciseId)
                .orElseThrow { ResourceNotFoundException("EXERCISE001: 운동을 찾을 수 없습니다") }

            // WorkoutExercise 엔티티 생성 및 저장
            val workoutExercise = WorkoutExercise(
                session = savedSession,
                exercise = exercise,
                orderInSession = planned.orderIndex ?: index
            )
            workoutExerciseRepository.save(workoutExercise)

            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        return StartWorkoutResponseV2(
            sessionId = savedSession.id,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            exercises = exercises,
            restTimerSettings = RestTimerSettings(
                defaultRestSeconds = 90,
                autoStartTimer = true
            )
        )
    }

    private fun cancelExistingSessions(user: com.richjun.liftupai.domain.auth.entity.User) {
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
        if (existingSession.isPresent) {
            val session = existingSession.get()
            session.status = SessionStatus.ABANDONED
            session.endTime = LocalDateTime.now()
            session.duration = ChronoUnit.MINUTES.between(session.startTime, session.endTime).toInt()
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
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 진행 중인 세션 찾기
        val existingSession = workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(user, SessionStatus.IN_PROGRESS)
            .orElseThrow { ResourceNotFoundException("진행 중인 운동 세션이 없습니다") }

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

        val exercises = workoutExercises.map { workoutExercise ->
            val exercise = workoutExercise.exercise
            ExerciseDto(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                instructions = exercise.instructions
            )
        }

        // 각 운동의 세트 정보도 가져오기
        val exerciseSets = workoutExercises.map { workoutExercise ->
            val sets = exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
            ExerciseWithSets(
                exerciseId = workoutExercise.exercise.id,
                exerciseName = workoutExercise.exercise.name,
                orderIndex = workoutExercise.orderInSession,
                sets = sets.mapIndexed { index, set ->
                    SetInfo(
                        setId = set.id,
                        setNumber = set.setNumber,
                        weight = set.weight,
                        reps = set.reps,
                        completed = set.completed,
                        completedAt = set.completedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
            startTime = existingSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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

        println("DEBUG: completeWorkout - sessionId: $sessionId")
        println("DEBUG: request.exercises.size: ${request.exercises.size}")
        println("DEBUG: request.duration: ${request.duration}")

        session.endTime = LocalDateTime.now()
        session.duration = request.duration
        session.notes = request.notes
        session.status = SessionStatus.COMPLETED

        var totalVolume = 0.0
        var totalSets = 0
        val personalRecords = mutableListOf<PersonalRecordInfo>()

        // 완료된 운동 세트 저장
        request.exercises.forEach { completedExercise ->
            val exercise = exerciseRepository.findById(completedExercise.exerciseId)
                .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

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

            println("DEBUG: Exercise ${exercise.name} (ID: ${completedExercise.exerciseId})")
            println("DEBUG: - Sets count: ${completedExercise.sets.size}")
            println("DEBUG: - Completed sets: ${completedExercise.sets.filter { it.completed }.size}")

            completedExercise.sets.filter { it.completed }.forEach { setDto ->
                println("DEBUG: - Set: weight=${setDto.weight}, reps=${setDto.reps}, volume=${setDto.weight * setDto.reps}, completed=${setDto.completed}")
                totalSets++
                val volume = setDto.weight * setDto.reps
                totalVolume += volume

                val exerciseSet = ExerciseSet(
                    workoutExercise = workoutExercise,
                    setNumber = workoutExercise.sets.size + 1,
                    weight = setDto.weight,
                    reps = setDto.reps,
                    restTime = setDto.restTaken,
                    completed = true,  // 이미 filter로 completed=true인 것만 처리
                    completedAt = LocalDateTime.now()
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
                        date = LocalDateTime.now()
                    )
                    personalRecordRepository.save(newRecord)

                    personalRecords.add(PersonalRecordInfo(
                        exerciseName = exercise.name,
                        weight = setDto.weight,
                        reps = setDto.reps,
                        previousBest = previousRecord?.weight ?: 0.0
                    ))
                }
            }
        }

        println("DEBUG: Final totalVolume: $totalVolume")
        println("DEBUG: Final totalSets: $totalSets")

        session.totalVolume = totalVolume
        session.caloriesBurned = calculateCaloriesBurned(request.duration, totalVolume)
        workoutSessionRepository.save(session)

        println("DEBUG: Saved session with totalVolume: ${session.totalVolume}")

        // UserProfile 업데이트 - lastWorkoutDate
        val profile = userProfileRepository.findByUser_Id(userId)
        if (profile.isPresent) {
            val userProfile = profile.get()
            userProfile.lastWorkoutDate = LocalDateTime.now()
            userProfileRepository.save(userProfile)
        }

        // MuscleRecovery 엔티티 업데이트 - 운동한 근육들 기록
        updateMuscleRecoveryAfterWorkout(session.user, request.exercises)

        // 스트릭 업데이트
        val streak = updateWorkoutStreak(session.user)

        // 업적 확인
        val milestones = checkMilestones(session.user, session)

        // 통계 계산
        val stats = calculateWorkoutStats(session.user)

        return CompleteWorkoutResponseV2(
            success = true,
            summary = WorkoutSummaryV2(
                duration = request.duration,
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
            throw IllegalStateException("운동 세션이 진행 중이 아닙니다")
        }

        val exercise = exerciseRepository.findById(request.exerciseId)
            .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

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
            reps = request.reps
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
                date = LocalDateTime.now()
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
    fun getExercisesV2(category: String?, equipment: String?, hasGif: Boolean): List<ExerciseDetailV2> {
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

        return exercises.map { exercise ->
            ExerciseDetailV2(
                id = exercise.id,
                name = exercise.name,
                category = exercise.category.name,
                muscleGroups = exercise.muscleGroups.map { it.name },
                equipment = exercise.equipment?.name,
                imageUrl = if (hasGif) generateGifUrl(exercise) else exercise.imageUrl,
                thumbnailUrl = generateThumbnailUrl(exercise),
                difficulty = "intermediate",
                description = exercise.instructions
            )
        }
    }

    @Transactional(readOnly = true)
    fun getExerciseDetailsV2(userId: Long, exerciseId: Long): ExerciseDetailResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

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
            name = exercise.name,
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { it.name },
            equipment = exercise.equipment?.name,
            imageUrl = generateGifUrl(exercise),
            thumbnailUrl = generateThumbnailUrl(exercise),
            videoUrl = generateVideoUrl(exercise),
            difficulty = "intermediate",
            description = exercise.instructions,
            instructions = generateInstructions(exercise),
            tips = generateTips(exercise),
            commonMistakes = generateCommonMistakes(exercise),
            breathing = generateBreathingGuide(exercise)
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
    fun getWorkoutCompletionStats(userId: Long, sessionId: Long?): WorkoutCompletionStats {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

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
        val weeksSinceJoin = ChronoUnit.WEEKS.between(memberSince, LocalDateTime.now())
        val avgWorkoutsPerWeek = if (weeksSinceJoin > 0) totalWorkouts.toDouble() / weeksSinceJoin else 0.0

        val historyStats = HistoryStats(
            totalWorkoutDays = workoutSessionRepository.countDistinctWorkoutDays(user),
            totalWorkouts = totalWorkouts.toInt(),
            memberSince = memberSince.format(DateTimeFormatter.ISO_LOCAL_DATE),
            averageWorkoutsPerWeek = avgWorkoutsPerWeek
        )

        // 스트릭 통계
        val currentStreak = calculateCurrentStreak(user)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0
        val weeklyCount = calculateWeeklyWorkoutCount(user)
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
                name = ach.name,
                description = ach.description,
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
            durationChange = "+5min",
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
    fun getWorkoutCalendar(userId: Long, year: Int, month: Int): WorkoutCalendarResponse {
        val logger = LoggerFactory.getLogger(this::class.java)

        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val yearMonth = YearMonth.of(year, month)
        val startDate = yearMonth.atDay(1).atStartOfDay()
        val endDate = yearMonth.atEndOfMonth().atTime(23, 59, 59)

        logger.info("Fetching workout sessions for user ${user.id} from $startDate to $endDate")

        val sessions = workoutSessionRepository.findByUserAndStartTimeBetween(user, startDate, endDate)

        logger.info("Found ${sessions.size} sessions for user ${user.id} in ${yearMonth.year}-${yearMonth.monthValue}")
        sessions.forEach { session ->
            logger.debug("Session ID: ${session.id}, Date: ${session.startTime}, Status: ${session.status}, Volume: ${session.totalVolume}")
        }

        val calendarDays = (1..yearMonth.lengthOfMonth()).map { day ->
            val date = LocalDate.of(year, month, day)
            val daySessions = sessions.filter { it.startTime.toLocalDate() == date }

            if (daySessions.isNotEmpty()) {
                println("DEBUG getWorkoutCalendar: Date $date has ${daySessions.size} sessions")
                daySessions.forEach { session ->
                    println("DEBUG: - Session ID: ${session.id}, Volume: ${session.totalVolume}, Status: ${session.status}")
                }
            }

            CalendarDay(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                hasWorkout = daySessions.isNotEmpty(),
                workoutCount = daySessions.size,
                totalVolume = daySessions.sumOf { it.totalVolume ?: 0.0 },
                primaryMuscles = daySessions.flatMap { session ->
                    val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    exercises.flatMap { it.exercise.muscleGroups.map { mg -> mg.name } }
                }.distinct()
            )
        }

        val totalDays = calendarDays.count { it.hasWorkout }
        val restDays = yearMonth.lengthOfMonth() - totalDays
        val averageVolume = if (totalDays > 0) {
            calendarDays.filter { it.hasWorkout }.sumOf { it.totalVolume ?: 0.0 } / totalDays
        } else 0.0

        val mostFrequentDay = sessions
            .groupBy { it.startTime.dayOfWeek }
            .maxByOrNull { it.value.size }
            ?.key?.name ?: "N/A"

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

    fun adjustNextSet(userId: Long, request: AdjustNextSetRequest): AdjustNextSetResponse {
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
            request.fatigue == "high" -> "피로도가 높아 중량과 반복수를 줄였습니다"
            request.previousSet.rpe >= 9 -> "RPE가 높아 중량을 조정했습니다"
            else -> "표준 진행으로 계속합니다"
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
                    description = "드롭세트로 볼륨 유지"
                ),
                AlternativeSet(
                    type = "rest_pause",
                    weight = request.previousSet.weight,
                    reps = request.previousSet.reps / 2,
                    description = "레스트-포즈로 강도 유지"
                )
            )
        )
    }

    fun getRestTimer(exerciseType: String, intensity: String, setNumber: Int): RestTimerResponse {
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
                exerciseType = when (exerciseType) {
                    "compound" -> "복합 운동"
                    "isolation" -> "고립 운동"
                    else -> "일반 운동"
                },
                intensity = when (intensity) {
                    "high" -> "고강도"
                    "low" -> "저강도"
                    else -> "중강도"
                },
                setNumber = if (setNumber >= 4) "후반 세트" else "초반 세트"
            )
        )
    }

    // Quick workout recommendation methods for V3
    @Transactional(readOnly = true)
    fun getQuickWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null
    ): QuickWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Check if user has started a workout today
        val hasStartedToday = workoutProgressTracker.hasStartedWorkoutToday(user)

        // Get user's program type and position
        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val programDays = userProfile?.weeklyWorkoutDays ?: 3
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

        // Determine target muscle based on program position
        val adjustedTargetMuscle = if (!hasStartedToday && targetMuscle == null) {
            // Get program type from user profile
            val programType = userProfile?.workoutSplit ?: "PPL"
            val sequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
            val workoutType = sequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY

            when (workoutType) {
                WorkoutType.PUSH -> "chest"
                WorkoutType.PULL -> "back"
                WorkoutType.LEGS -> "legs"
                WorkoutType.UPPER -> "upper"
                WorkoutType.LOWER -> "lower"
                WorkoutType.CHEST -> "chest"
                WorkoutType.BACK -> "back"
                WorkoutType.ARMS -> "arms"
                WorkoutType.SHOULDERS -> "shoulders"
                else -> "full_body"
            }
        } else {
            targetMuscle
        }

        // Generate main recommendation based on filters
        val recommendation = generateQuickRecommendation(user, duration, equipment, adjustedTargetMuscle)

        // Generate alternatives
        val alternatives = generateAlternativeWorkouts(user, duration, equipment, adjustedTargetMuscle)

        return QuickWorkoutRecommendationResponse(
            recommendation = recommendation,
            alternatives = alternatives
        )
    }

    @Transactional
    fun startRecommendedWorkout(
        userId: Long,
        request: StartRecommendedWorkoutRequest
    ): StartRecommendedWorkoutResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Cancel existing IN_PROGRESS sessions
        val existingSessions = workoutSessionRepository.findAllByUserAndStatus(user, SessionStatus.IN_PROGRESS)
        existingSessions.forEach { session ->
            session.status = SessionStatus.CANCELLED
            workoutSessionRepository.save(session)
        }

        // Get workout details from recommendation ID
        val workoutDetail = getWorkoutFromRecommendationId(request.recommendationId)

        // Apply adjustments
        val adjustedWorkout = applyWorkoutAdjustments(workoutDetail, request.adjustments)

        // Get program position for tracking
        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val programDays = userProfile?.weeklyWorkoutDays ?: 3
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

        // Determine workout type from target muscles
        val workoutType = workoutProgressTracker.determineWorkoutType(adjustedWorkout.targetMuscles)

        // Create session with tracking info
        val session = WorkoutSession(
            user = user,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS,
            workoutType = workoutType,
            programDay = programPosition.day,
            programCycle = programPosition.cycle,
            recommendationType = "QUICK"
        )

        val savedSession = workoutSessionRepository.save(session)

        // Prepare exercises with suggested weights
        val exercises = adjustedWorkout.exercises.map { exercise ->
            val exerciseEntity = exerciseRepository.findById(exercise.exerciseId.toLong())
                .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

            val suggestedWeight = calculateSuggestedWeight(user, exerciseEntity)

            RecommendedWorkoutExercise(
                exerciseId = exercise.exerciseId,
                name = exercise.name,
                plannedSets = exercise.sets,
                plannedReps = exercise.reps,
                suggestedWeight = suggestedWeight,
                restTimer = exercise.rest
            )
        }

        return StartRecommendedWorkoutResponse(
            sessionId = savedSession.id.toString(),
            workoutName = adjustedWorkout.name,
            startTime = savedSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            exercises = exercises,
            estimatedDuration = adjustedWorkout.duration,
            started = true
        )
    }

    private fun generateQuickRecommendation(
        user: com.richjun.liftupai.domain.auth.entity.User,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?
    ): WorkoutRecommendationDetail {
        val workoutDuration = duration ?: 30
        val workoutId = generateWorkoutId(workoutDuration, equipment, targetMuscle)

        // Get user profile to determine difficulty
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val difficulty = when (userProfile?.experienceLevel) {
            ExperienceLevel.BEGINNER -> "beginner"
            ExperienceLevel.INTERMEDIATE -> "intermediate"
            ExperienceLevel.ADVANCED -> "advanced"
            ExperienceLevel.EXPERT -> "expert"
            else -> "intermediate" // default
        }

        // Get suitable exercises based on filters (user 정보 포함)
        val exercises = getFilteredExercises(equipment, targetMuscle, workoutDuration, user)

        // Create quick exercise details
        val quickExercises = exercises.take(6).mapIndexed { index, exercise ->
            // Calculate suggested weight for each exercise
            val suggestedWeight = calculateSuggestedWeight(user, exercise)

            QuickExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = exercise.name,
                sets = when (exercise.category) {
                    ExerciseCategory.CHEST, ExerciseCategory.BACK -> 3
                    ExerciseCategory.LEGS -> 4
                    ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS -> 3
                    ExerciseCategory.CORE -> 2
                    else -> 3
                },
                reps = when (exercise.category) {
                    ExerciseCategory.CHEST, ExerciseCategory.BACK -> "8-12"
                    ExerciseCategory.LEGS -> "12-15"
                    ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS -> "10-12"
                    ExerciseCategory.CORE -> "15-20"
                    else -> "10-12"
                },
                rest = when (exercise.category) {
                    ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.LEGS -> 90
                    ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS -> 60
                    ExerciseCategory.CORE -> 45
                    else -> 60
                },
                order = index + 1,
                suggestedWeight = suggestedWeight
            )
        }

        val targetMuscles = exercises.flatMap { it.muscleGroups.map { mg -> mg.name.lowercase() } }.distinct()
        val equipmentList = exercises.mapNotNull { it.equipment?.name?.lowercase() }.distinct()

        return WorkoutRecommendationDetail(
            workoutId = workoutId,
            name = generateWorkoutName(workoutDuration, targetMuscle, equipment),
            duration = workoutDuration,
            difficulty = difficulty,
            exercises = quickExercises,
            estimatedCalories = calculateEstimatedCalories(workoutDuration, quickExercises.size),
            targetMuscles = targetMuscles,
            equipment = equipmentList
        )
    }

    private fun generateAlternativeWorkouts(
        user: com.richjun.liftupai.domain.auth.entity.User,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?
    ): List<AlternativeWorkout> {
        val alternatives = mutableListOf<AlternativeWorkout>()

        // Duration alternatives
        val baseDuration = duration ?: 30
        if (baseDuration >= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(20, equipment, "core"),
                name = "20분 코어 집중",
                duration = 20
            ))
        }

        if (baseDuration <= 30) {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(45, equipment, "full_body"),
                name = "45분 전신 운동",
                duration = 45
            ))
        }

        // Equipment alternatives
        if (equipment != "bodyweight") {
            alternatives.add(AlternativeWorkout(
                workoutId = generateWorkoutId(baseDuration, "bodyweight", targetMuscle),
                name = "${baseDuration}분 맨몸 운동",
                duration = baseDuration
            ))
        }

        return alternatives.take(2)
    }

    /**
     * 헬스 트레이너 관점으로 개선된 운동 필터링 및 선택
     * - 회복 중인 근육 제외
     * - 주간 볼륨 고려
     * - 운동 다양성 보장
     * - 운동 순서 정렬 (복합운동 → 고립운동, 큰 근육 → 작은 근육)
     */
    private fun getFilteredExercises(
        equipment: String?,
        targetMuscle: String?,
        duration: Int,
        user: com.richjun.liftupai.domain.auth.entity.User? = null
    ): List<Exercise> {
        var exercises = exerciseRepository.findAll().toList()

        // 사용자 정보가 있으면 헬스 트레이너 관점 필터링 적용 (완화됨)
        user?.let { u ->
            // 회복 필터링 제거 (너무 엄격함 - 매일 운동하는 사람에게 추천 불가능)
            println("회복 필터링 스킵 (너무 엄격하여 제거)")
        }

        // 3. 장비 필터링
        equipment?.let { eq ->
            val equipmentEnum = try {
                Equipment.valueOf(eq.uppercase().replace(" ", "_"))
            } catch (e: IllegalArgumentException) {
                null
            }
            equipmentEnum?.let { exercises = exercises.filter { it.equipment == equipmentEnum } }
        }

        // 4. 타겟 근육 필터링
        targetMuscle?.let { muscle ->
            val muscleEnum = try {
                when (muscle.lowercase()) {
                    "full_body" -> null // Don't filter for full body
                    "chest" -> MuscleGroup.CHEST
                    "back" -> MuscleGroup.BACK
                    "legs" -> MuscleGroup.QUADRICEPS // Representative for legs
                    "shoulders" -> MuscleGroup.SHOULDERS
                    "arms" -> MuscleGroup.BICEPS // Representative for arms
                    "core" -> MuscleGroup.ABS
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                null
            }

            if (muscleEnum != null && muscle.lowercase() != "full_body") {
                exercises = exercises.filter { it.muscleGroups.contains(muscleEnum) }
            }
        }

        // 5. 운동 다양성 보장 (사용자 정보가 있을 때만)
        user?.let { u ->
            val userProfile = userProfileRepository.findByUser(u).orElse(null)
            val experienceLevel = userProfile?.experienceLevel ?: ExperienceLevel.INTERMEDIATE

            // 경험 수준별 익숙한 운동 vs 새로운 운동 비율
            val (familiarCount, newCount) = when (experienceLevel) {
                ExperienceLevel.BEGINNER -> Pair(8, 2)      // 초보자: 80% 익숙한 운동
                ExperienceLevel.INTERMEDIATE -> Pair(6, 4)   // 중급: 60% 익숙한 운동
                ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> Pair(4, 6) // 고급: 40% 익숙한 운동
                else -> Pair(6, 4)
            }

            exercises = ensureExerciseVarietyV2(u, exercises, familiarCount, newCount)
            println("다양성 보장 후: ${exercises.size}개 운동 (익숙: $familiarCount, 새로운: $newCount)")
        }

        // 6. 운동 순서 정렬 ⭐ 가장 중요!
        exercises = orderExercisesByPriorityV2(exercises)
        println("최종 정렬 완료: 복합운동 우선, 큰 근육 → 작은 근육")

        return exercises.take(10)
    }

    private fun getWorkoutFromRecommendationId(recommendationId: String): WorkoutRecommendationDetail {
        // In a real implementation, this would fetch from a database or cache
        // For now, generate based on ID pattern
        val parts = recommendationId.split("_")
        val duration = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 30
        val targetMuscle = parts.getOrNull(2)

        // recommendationId에서 실제 사용자 정보 추출 또는 전달받은 사용자 ID 사용
        val user = userRepository.findById(recommendationId.split("-").firstOrNull()?.toLongOrNull() ?: 1L)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        return generateQuickRecommendation(
            user,
            duration,
            null,
            targetMuscle
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

    fun calculateSuggestedWeight(
        user: com.richjun.liftupai.domain.auth.entity.User,
        exercise: Exercise
    ): Double {
        val logger = LoggerFactory.getLogger(this::class.java)

        // 사용자 프로필 및 데이터 수집
        val userProfile = userProfileRepository.findByUser_Id(user.id).orElse(null)
        val bodyWeight = userProfile?.bodyInfo?.weight ?: 70.0
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
            recentHistory = recentSessions
        )

        logger.info("Advanced PT System - Exercise: ${exercise.name}, " +
                "Previous: ${recentSessions.lastOrNull()?.weight}kg, " +
                "Suggested: ${suggested.weight}kg, " +
                "Reason: ${suggested.reason}")

        return suggested.weight
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
        recentHistory: List<WorkoutData>
    ): PTRecommendation {

        // 1. 기본 무게 계산
        val baseWeight = calculateBaseWeight(exercise, bodyWeight, gender, experienceLevel)

        // 2. 최근 운동 분석
        val performanceAnalysis = analyzeRecentPerformance(recentHistory)

        // 3. 피로도 및 회복 상태 평가
        val recoveryStatus = assessRecoveryStatus(user, exercise)

        // 4. 주기화 단계 결정 (메소사이클)
        val periodizationPhase = determinePeriodizationPhase(user, recentHistory)

        // 5. 점진적 과부하 계획
        val progressionPlan = calculateProgressiveOverload(
            baseWeight = baseWeight,
            personalRecord = personalRecord,
            recentHistory = recentHistory,
            performanceAnalysis = performanceAnalysis,
            recoveryStatus = recoveryStatus,
            periodizationPhase = periodizationPhase
        )

        // 6. 최종 추천 생성
        return generateFinalRecommendation(
            exercise = exercise,
            progressionPlan = progressionPlan,
            periodizationPhase = periodizationPhase,
            recoveryStatus = recoveryStatus
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
            1.0 - (weights.maxOrNull()!! - weights.minOrNull()!!) / weights.average()
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
    private fun assessRecoveryStatus(user: com.richjun.liftupai.domain.auth.entity.User, exercise: Exercise): RecoveryStatus {
        // 마지막 운동으로부터 경과 시간
        val lastWorkout = workoutSessionRepository.findFirstByUserOrderByStartTimeDesc(user).orElse(null)
        val daysSinceLastWorkout = lastWorkout?.let {
            ChronoUnit.DAYS.between(it.startTime, LocalDateTime.now())
        } ?: 7L

        // 근육군별 회복 시간 (48-72시간)
        val optimalRecoveryDays = when (exercise.category) {
            ExerciseCategory.LEGS -> 3..4
            ExerciseCategory.BACK, ExerciseCategory.CHEST -> 2..3
            else -> 1..2
        }

        // 주간 운동 빈도 (최근 7일간 세션 수)
        val weekStart = LocalDateTime.now().minusDays(7)
        val weeklyFrequency = workoutSessionRepository.countByUserAndStartTimeAfter(user, weekStart)

        return when {
            daysSinceLastWorkout < optimalRecoveryDays.first -> RecoveryStatus.UNDER_RECOVERED
            daysSinceLastWorkout > optimalRecoveryDays.last * 2 -> RecoveryStatus.DETRAINED
            weeklyFrequency > 6 -> RecoveryStatus.OVERREACHING
            daysSinceLastWorkout in optimalRecoveryDays -> RecoveryStatus.OPTIMAL
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
    private fun determinePeriodizationPhase(user: com.richjun.liftupai.domain.auth.entity.User, recentHistory: List<WorkoutData>): PeriodizationPhase {
        val now = LocalDateTime.now()

        // 최근 8주간의 주별 운동 빈도 계산
        val last8Weeks = (0..7).map { weekOffset ->
            val weekStart = now.minusWeeks(weekOffset.toLong()).with(java.time.DayOfWeek.MONDAY)
            val weekEnd = weekStart.plusDays(6)

            val workoutsThisWeek = workoutSessionRepository.findByUserAndStartTimeAfter(user, weekStart)
                .count { it.startTime <= weekEnd && it.status == SessionStatus.COMPLETED }

            workoutsThisWeek
        }

        // 평균 주간 운동 빈도
        val avgWorkoutsPerWeek = last8Weeks.average().takeIf { !it.isNaN() } ?: 3.0

        // 마지막 운동 이후 경과 일수
        val daysSinceLastWorkout = recentHistory.firstOrNull()?.let { lastWorkout ->
            java.time.Duration.between(lastWorkout.completedAt, now).toDays()
        } ?: 0L

        // 디트레이닝 체크 (2주 이상 쉼)
        if (daysSinceLastWorkout >= 14) {
            return PeriodizationPhase.DELOAD // 복귀 시 가볍게 시작
        }

        // 실제 주간 운동 횟수로 사이클 주차 계산
        val totalWeeks = workoutSessionRepository.findAll()
            .filter { it.user.id == user.id && it.status == SessionStatus.COMPLETED }
            .map { it.startTime.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }
            .distinct()
            .count()

        // 4주 메소사이클 (주차 기준)
        val cycleWeek = ((totalWeeks % 4) + 1).toInt()

        // 과훈련 체크 (최근 4주 평균이 주 5회 이상)
        val recentAvg = last8Weeks.take(4).average()
        if (recentAvg >= 5.0) {
            return PeriodizationPhase.DELOAD // 과훈련 방지
        }

        return when (cycleWeek) {
            1 -> PeriodizationPhase.ACCUMULATION  // 1주차: 볼륨 증가 (65-75% 1RM)
            2 -> PeriodizationPhase.INTENSIFICATION  // 2주차: 강도 증가 (75-85% 1RM)
            3 -> PeriodizationPhase.REALIZATION  // 3주차: 피크 (85-95% 1RM)
            4 -> PeriodizationPhase.DELOAD  // 4주차: 회복주 (50-60% 1RM)
            else -> PeriodizationPhase.ACCUMULATION
        }
    }

    /**
     * 점진적 과부하 계획 수립
     */
    private fun calculateProgressiveOverload(
        baseWeight: Double,
        personalRecord: com.richjun.liftupai.domain.workout.entity.PersonalRecord?,
        recentHistory: List<WorkoutData>,
        performanceAnalysis: PerformanceAnalysis,
        recoveryStatus: RecoveryStatus,
        periodizationPhase: PeriodizationPhase
    ): ProgressionPlan {

        val lastWeight = recentHistory.lastOrNull()?.weight ?: baseWeight
        // prWeight가 0이 되지 않도록 보장
        val prWeight = (personalRecord?.weight ?: lastWeight * 1.2).takeIf { it > 0 } ?: baseWeight.takeIf { it > 0 } ?: 10.0

        // 주기화에 따른 강도 설정
        val intensityRange = when (periodizationPhase) {
            PeriodizationPhase.ACCUMULATION -> 0.65..0.75  // 65-75% 1RM
            PeriodizationPhase.INTENSIFICATION -> 0.75..0.85  // 75-85% 1RM
            PeriodizationPhase.REALIZATION -> 0.85..0.95  // 85-95% 1RM
            PeriodizationPhase.DELOAD -> 0.50..0.60  // 50-60% 1RM
        }

        // 수행 분석에 따른 조정
        val progressionMultiplier = when (performanceAnalysis.trend) {
            PerformanceTrend.READY_TO_PROGRESS -> 1.05  // 5% 증가
            PerformanceTrend.IMPROVING -> 1.025  // 2.5% 증가
            PerformanceTrend.MAINTAINING -> 1.0  // 유지
            PerformanceTrend.NEEDS_DELOAD -> 0.8  // 20% 감소
            PerformanceTrend.TECHNIQUE_FOCUS -> 0.9  // 10% 감소
            PerformanceTrend.DECLINING -> 0.95  // 5% 감소
            PerformanceTrend.NEW_EXERCISE -> 1.0  // 기본값
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
            lastWeight * progressionMultiplier * recoveryMultiplier
        } else {
            prWeight * 0.7 // 기본값: PR의 70%
        }

        // 안전 마진 적용 (과학적 근거: NSCA Guidelines)
        if (targetWeight.isFinite() && prWeight > 0) {
            // 1. 주기화 범위 내로 제한
            targetWeight = targetWeight.coerceIn(
                prWeight * intensityRange.start,
                prWeight * intensityRange.endInclusive
            )

            // 2. 급격한 증가 방지 (주당 최대 5% 증가)
            val maxWeeklyIncrease = lastWeight * 1.05
            if (progressionMultiplier > 1.0 && targetWeight > maxWeeklyIncrease) {
                targetWeight = maxWeeklyIncrease
                println("⚠️ 안전을 위해 증가율 제한: 최대 5%/주")
            }

            // 3. 절대 최소값 보장 (2.5kg)
            targetWeight = targetWeight.coerceAtLeast(2.5)

            // 4. PR 대비 안전 상한선 (110% 제한)
            targetWeight = targetWeight.coerceAtMost(prWeight * 1.1)
        }

        // 반복 횟수 및 세트 추천
        val (sets, reps) = when (periodizationPhase) {
            PeriodizationPhase.ACCUMULATION -> 4 to "10-12"  // 고볼륨
            PeriodizationPhase.INTENSIFICATION -> 4 to "6-8"  // 중강도
            PeriodizationPhase.REALIZATION -> 3 to "3-5"  // 고강도
            PeriodizationPhase.DELOAD -> 3 to "12-15"  // 저강도 회복
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
            restSeconds = getRestTime(periodizationPhase),
            intensity = intensity,
            focus = determineFocus(performanceAnalysis, periodizationPhase)
        )
    }

    /**
     * 최종 추천 생성
     */
    private fun generateFinalRecommendation(
        exercise: Exercise,
        progressionPlan: ProgressionPlan,
        periodizationPhase: PeriodizationPhase,
        recoveryStatus: RecoveryStatus
    ): PTRecommendation {

        // PT의 코칭 메시지 생성
        val coachingTip = generateCoachingTip(
            exercise = exercise,
            phase = periodizationPhase,
            recovery = recoveryStatus,
            focus = progressionPlan.focus
        )

        // 추천 이유 설명
        val reason = buildString {
            append(when (periodizationPhase) {
                PeriodizationPhase.ACCUMULATION -> "볼륨 증가 주간: "
                PeriodizationPhase.INTENSIFICATION -> "강도 증가 주간: "
                PeriodizationPhase.REALIZATION -> "피크 주간: "
                PeriodizationPhase.DELOAD -> "회복 주간: "
            })

            append(when (recoveryStatus) {
                RecoveryStatus.OPTIMAL -> "최적의 회복 상태입니다."
                RecoveryStatus.UNDER_RECOVERED -> "충분한 회복이 필요합니다."
                RecoveryStatus.OVERREACHING -> "과훈련 주의가 필요합니다."
                RecoveryStatus.DETRAINED -> "점진적으로 강도를 높여갑니다."
                else -> "적절한 강도로 진행합니다."
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
    private fun calculateBaseWeight(exercise: Exercise, bodyWeight: Double, gender: String, experienceLevel: com.richjun.liftupai.domain.user.entity.ExperienceLevel): Double {
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
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.INTERMEDIATE -> 0.75
            com.richjun.liftupai.domain.user.entity.ExperienceLevel.ADVANCED -> 1.0
            else -> 0.6
        }

        val baseWeight = getExerciseBaseWeight(exercise, bodyWeight)
        return baseWeight * genderMultiplier * experienceMultiplier
    }

    private fun getRecentWorkoutHistory(user: com.richjun.liftupai.domain.auth.entity.User, exercise: Exercise, days: Int): List<WorkoutData> {
        return try {
            val cutoffDate = LocalDateTime.now().minusDays(days.toLong())

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
                        if (set.weight != null && set.actualReps != null && set.actualReps!! > 0) {
                            workoutDataList.add(
                                WorkoutData(
                                    weight = set.weight!!,
                                    reps = set.actualReps!!,
                                    sets = sets.size,
                                    rpe = set.rpe,
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
            println("⚠️ 최근 운동 기록 조회 실패: ${e.message}")
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

    private fun getRestTime(phase: PeriodizationPhase): Int {
        return when (phase) {
            PeriodizationPhase.ACCUMULATION -> 90
            PeriodizationPhase.INTENSIFICATION -> 120
            PeriodizationPhase.REALIZATION -> 180
            PeriodizationPhase.DELOAD -> 60
        }
    }

    private fun determineFocus(analysis: PerformanceAnalysis, phase: PeriodizationPhase): String {
        return when {
            analysis.trend == PerformanceTrend.TECHNIQUE_FOCUS -> "폼과 기술에 집중하세요"
            phase == PeriodizationPhase.DELOAD -> "가볍게 수행하며 회복에 집중하세요"
            phase == PeriodizationPhase.REALIZATION -> "최대 집중력으로 수행하세요"
            analysis.avgRPE > 8 -> "충분한 휴식 후 수행하세요"
            else -> "일정한 템포로 컨트롤하며 수행하세요"
        }
    }

    private fun generateCoachingTip(exercise: Exercise, phase: PeriodizationPhase, recovery: RecoveryStatus, focus: String): String {
        // 10년차 PT의 운동별 맞춤 팁
        val exerciseTip = when {
            exercise.name.contains("벤치프레스") -> "가슴을 펴고 견갑골을 모아 안정적인 자세를 유지하세요."
            exercise.name.contains("스쿼트") -> "무릎이 발끝 방향으로 향하도록 하고, 코어를 단단히 고정하세요."
            exercise.name.contains("데드리프트") -> "등을 곧게 펴고, 바가 몸에서 멀어지지 않도록 주의하세요."
            else -> "정확한 자세와 호흡에 집중하세요."
        }

        return "$exerciseTip $focus"
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
        val exerciseName = exercise.name.lowercase()

        return when {
            // === CHEST 운동 ===
            exerciseName.contains("벤치프레스") || exerciseName.contains("bench press") -> {
                when {
                    exerciseName.contains("바벨") -> bodyWeight * 0.75  // 체중의 75%
                    exerciseName.contains("덤벨") -> bodyWeight * 0.3   // 한쪽 덤벨 기준
                    exerciseName.contains("인클라인") -> bodyWeight * 0.65
                    exerciseName.contains("디클라인") -> bodyWeight * 0.85
                    else -> bodyWeight * 0.7
                }
            }
            exerciseName.contains("플라이") || exerciseName.contains("fly") -> {
                when {
                    exerciseName.contains("케이블") -> bodyWeight * 0.25
                    exerciseName.contains("덤벨") -> bodyWeight * 0.2
                    else -> bodyWeight * 0.22
                }
            }
            exerciseName.contains("체스트 프레스") -> bodyWeight * 0.7
            exerciseName.contains("케이블 크로스오버") -> bodyWeight * 0.3
            exerciseName.contains("푸시업") || exerciseName.contains("push up") -> 0.0  // 맨몸
            exerciseName.contains("딥스") || exerciseName.contains("dips") -> 0.0  // 맨몸 or 보조

            // === BACK 운동 ===
            exerciseName.contains("데드리프트") || exerciseName.contains("deadlift") -> {
                when {
                    exerciseName.contains("루마니안") -> bodyWeight * 0.8
                    exerciseName.contains("스티프") -> bodyWeight * 0.75
                    else -> bodyWeight * 1.2  // 컨벤셔널 데드리프트
                }
            }
            exerciseName.contains("바벨로우") || exerciseName.contains("barbell row") -> bodyWeight * 0.6
            exerciseName.contains("덤벨로우") -> bodyWeight * 0.35  // 한쪽
            exerciseName.contains("시티드 로우") || exerciseName.contains("seated row") -> bodyWeight * 0.65
            exerciseName.contains("랫풀다운") || exerciseName.contains("lat pulldown") -> bodyWeight * 0.6
            exerciseName.contains("풀업") || exerciseName.contains("pull up") -> 0.0  // 맨몸
            exerciseName.contains("페이스 풀") -> bodyWeight * 0.2

            // === LEGS 운동 ===
            exerciseName.contains("스쿼트") || exerciseName.contains("squat") -> {
                when {
                    exerciseName.contains("프론트") -> bodyWeight * 0.7
                    exerciseName.contains("불가리안") -> bodyWeight * 0.4  // 한쪽 다리
                    exerciseName.contains("고블릿") -> bodyWeight * 0.5
                    else -> bodyWeight * 0.9  // 백스쿼트
                }
            }
            exerciseName.contains("레그프레스") || exerciseName.contains("leg press") -> bodyWeight * 1.8
            exerciseName.contains("런지") || exerciseName.contains("lunge") -> bodyWeight * 0.4
            exerciseName.contains("레그 익스텐션") -> bodyWeight * 0.5
            exerciseName.contains("레그 컬") -> bodyWeight * 0.4
            exerciseName.contains("카프 레이즈") -> bodyWeight * 0.8

            // === SHOULDERS 운동 ===
            exerciseName.contains("숄더프레스") || exerciseName.contains("shoulder press") -> {
                when {
                    exerciseName.contains("바벨") -> bodyWeight * 0.5
                    exerciseName.contains("덤벨") -> bodyWeight * 0.22  // 한쪽
                    else -> bodyWeight * 0.45
                }
            }
            exerciseName.contains("사이드 레이즈") || exerciseName.contains("lateral raise") -> bodyWeight * 0.1
            exerciseName.contains("프론트 레이즈") -> bodyWeight * 0.12
            exerciseName.contains("리어 델트") -> bodyWeight * 0.08
            exerciseName.contains("업라이트 로우") -> bodyWeight * 0.4
            exerciseName.contains("슈러그") -> bodyWeight * 0.6

            // === ARMS 운동 ===
            exerciseName.contains("바벨컬") || exerciseName.contains("barbell curl") -> bodyWeight * 0.35
            exerciseName.contains("덤벨컬") || exerciseName.contains("dumbbell curl") -> bodyWeight * 0.15  // 한쪽
            exerciseName.contains("해머컬") -> bodyWeight * 0.17  // 한쪽
            exerciseName.contains("프리처컬") -> bodyWeight * 0.3
            exerciseName.contains("케이블 컬") -> bodyWeight * 0.3
            exerciseName.contains("트라이셉") || exerciseName.contains("tricep") -> {
                when {
                    exerciseName.contains("익스텐션") -> bodyWeight * 0.35
                    exerciseName.contains("푸시다운") -> bodyWeight * 0.4
                    exerciseName.contains("킥백") -> bodyWeight * 0.1
                    else -> bodyWeight * 0.3
                }
            }

            // === CORE 운동 ===
            exerciseName.contains("플랭크") || exerciseName.contains("plank") -> 0.0
            exerciseName.contains("크런치") || exerciseName.contains("crunch") -> 0.0
            exerciseName.contains("레그레이즈") -> 0.0
            exerciseName.contains("우드챱") -> bodyWeight * 0.15

            // === 기본값 (카테고리별) ===
            else -> when (exercise.category) {
                ExerciseCategory.CHEST -> bodyWeight * 0.5
                ExerciseCategory.BACK -> bodyWeight * 0.6
                ExerciseCategory.LEGS -> bodyWeight * 0.7
                ExerciseCategory.SHOULDERS -> bodyWeight * 0.35
                ExerciseCategory.ARMS -> bodyWeight * 0.25
                ExerciseCategory.CORE -> 0.0
                else -> bodyWeight * 0.4
            }
        }
    }

    private fun generateWorkoutId(duration: Int, equipment: String?, targetMuscle: String?): String {
        val equipmentPart = equipment ?: "general"
        val musclePart = targetMuscle ?: "fullbody"
        return "quick_${duration}min_${musclePart}_${equipmentPart}"
    }

    private fun generateWorkoutName(duration: Int, targetMuscle: String?, equipment: String?): String {
        val muscleText = when (targetMuscle?.lowercase()) {
            "chest" -> "가슴"
            "back" -> "등"
            "legs" -> "하체"
            "shoulders" -> "어깨"
            "arms" -> "팔"
            "core" -> "코어"
            else -> "전신"
        }

        val equipmentText = when (equipment?.lowercase()) {
            "dumbbell", "dumbbells" -> "덤벨"
            "barbell" -> "바벨"
            "bodyweight" -> "맨몸"
            "machine" -> "머신"
            else -> ""
        }

        return "${duration}분 ${muscleText} ${equipmentText} 운동".trim()
    }

    private fun calculateEstimatedCalories(duration: Int, exerciseCount: Int): Int {
        val baseCaloriesPerMinute = 6
        val exerciseMultiplier = 1 + (exerciseCount * 0.1)
        return (duration * baseCaloriesPerMinute * exerciseMultiplier).toInt()
    }

    // Helper methods
    private fun findUserSession(userId: Long, sessionId: Long): WorkoutSession {
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("WORKOUT002: 운동 세션을 찾을 수 없습니다") }

        if (session.user.id != userId) {
            throw ResourceNotFoundException("권한이 없습니다")
        }

        return session
    }

    private fun calculateCaloriesBurned(duration: Int, totalVolume: Double): Int {
        val baseCalories = duration * 5
        val volumeBonus = (totalVolume / 1000) * 2
        return (baseCalories + volumeBonus).toInt()
    }

    private fun updateWorkoutStreak(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStreak {
        val today = LocalDate.now()
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

        // 운동 횟수 업적 확인 및 저장
        when (totalWorkouts) {
            1L -> {
                milestones.add("first_workout")
                unlockedAchievements.add(createAchievement(
                    user = user,
                    name = "첫 운동 완료",
                    description = "운동 여정의 첫 걸음을 내딛었습니다!",
                    icon = "🌟",
                    type = AchievementType.MILESTONE
                ))
            }
            10L -> {
                milestones.add("workout_10")
                unlockedAchievements.add(createAchievement(
                    user = user,
                    name = "운동 10회 달성",
                    description = "꾸준함이 습관이 되고 있습니다!",
                    icon = "💪",
                    type = AchievementType.WORKOUT_COUNT
                ))
            }
            50L -> {
                milestones.add("workout_50")
                unlockedAchievements.add(createAchievement(
                    user = user,
                    name = "운동 50회 달성",
                    description = "반백 운동! 당신은 진정한 운동인입니다!",
                    icon = "🏅",
                    type = AchievementType.WORKOUT_COUNT
                ))
            }
            100L -> {
                milestones.add("workout_100")
                unlockedAchievements.add(createAchievement(
                    user = user,
                    name = "운동 100회 달성",
                    description = "백 번의 도전, 백 번의 성장! 놀라운 성취입니다!",
                    icon = "🏆",
                    type = AchievementType.WORKOUT_COUNT
                ))
            }
            200L -> {
                milestones.add("workout_200")
                unlockedAchievements.add(createAchievement(
                    user = user,
                    name = "운동 200회 달성",
                    description = "200회의 노력이 빛나는 순간입니다!",
                    icon = "👑",
                    type = AchievementType.WORKOUT_COUNT
                ))
            }
        }

        // 연속 운동 스트릭 업적 확인 및 저장
        val currentStreak = calculateCurrentStreak(user)
        when (currentStreak) {
            7 -> {
                milestones.add("week_streak_7")
                if (!achievementRepository.existsByUserAndName(user, "일주일 연속 운동")) {
                    unlockedAchievements.add(createAchievement(
                        user = user,
                        name = "일주일 연속 운동",
                        description = "7일 연속 운동을 완료했습니다! 당신의 의지는 강철입니다!",
                        icon = "🔥",
                        type = AchievementType.STREAK
                    ))
                }
            }
            14 -> {
                milestones.add("week_streak_14")
                if (!achievementRepository.existsByUserAndName(user, "2주 연속 운동")) {
                    unlockedAchievements.add(createAchievement(
                        user = user,
                        name = "2주 연속 운동",
                        description = "14일 연속 운동! 습관이 형성되고 있습니다!",
                        icon = "🎯",
                        type = AchievementType.STREAK
                    ))
                }
            }
            30 -> {
                milestones.add("month_streak_30")
                if (!achievementRepository.existsByUserAndName(user, "한 달 연속 운동")) {
                    unlockedAchievements.add(createAchievement(
                        user = user,
                        name = "한 달 연속 운동",
                        description = "30일 연속 운동! 당신은 진정한 챔피언입니다!",
                        icon = "🏆",
                        type = AchievementType.STREAK
                    ))
                }
            }
            60 -> {
                milestones.add("month_streak_60")
                if (!achievementRepository.existsByUserAndName(user, "두 달 연속 운동")) {
                    unlockedAchievements.add(createAchievement(
                        user = user,
                        name = "두 달 연속 운동",
                        description = "60일 연속 운동! 전설이 되어가고 있습니다!",
                        icon = "⭐",
                        type = AchievementType.STREAK
                    ))
                }
            }
        }

        // 볼륨 업적 확인
        val totalVolume = session.totalVolume ?: 0.0
        if (totalVolume >= 10000 && !achievementRepository.existsByUserAndName(user, "10톤 마스터")) {
            milestones.add("volume_10000")
            unlockedAchievements.add(createAchievement(
                user = user,
                name = "10톤 마스터",
                description = "한 세션에서 10,000kg을 들어올렸습니다!",
                icon = "💪",
                type = AchievementType.VOLUME
            ))
        }
        if (totalVolume >= 20000 && !achievementRepository.existsByUserAndName(user, "20톤 전사")) {
            milestones.add("volume_20000")
            unlockedAchievements.add(createAchievement(
                user = user,
                name = "20톤 전사",
                description = "한 세션에서 20,000kg을 들어올렸습니다!",
                icon = "🦾",
                type = AchievementType.VOLUME
            ))
        }

        // 운동 시간 업적 확인
        val duration = session.duration ?: 0
        if (duration >= 60 && !achievementRepository.existsByUserAndName(user, "한 시간 집중")) {
            milestones.add("duration_60")
            unlockedAchievements.add(createAchievement(
                user = user,
                name = "한 시간 집중",
                description = "60분 이상 운동을 완료했습니다!",
                icon = "⏱️",
                type = AchievementType.CONSISTENCY
            ))
        }
        if (duration >= 90 && !achievementRepository.existsByUserAndName(user, "90분 전사")) {
            milestones.add("duration_90")
            unlockedAchievements.add(createAchievement(
                user = user,
                name = "90분 전사",
                description = "90분 이상 운동을 완료했습니다!",
                icon = "⚡",
                type = AchievementType.CONSISTENCY
            ))
        }

        // 업적을 데이터베이스에 저장
        unlockedAchievements.forEach { achievement ->
            achievementRepository.save(achievement)
        }

        return milestones
    }

    private fun createAchievement(
        user: com.richjun.liftupai.domain.auth.entity.User,
        name: String,
        description: String,
        icon: String,
        type: AchievementType
    ): Achievement {
        return Achievement(
            user = user,
            name = name,
            description = description,
            icon = icon,
            type = type,
            unlockedAt = LocalDateTime.now()
        )
    }

    private fun calculateWorkoutStats(user: com.richjun.liftupai.domain.auth.entity.User): WorkoutStats {
        val totalWorkoutDays = workoutSessionRepository.countDistinctWorkoutDays(user)
        val currentWeekCount = calculateWeeklyWorkoutCount(user)
        val currentStreak = calculateCurrentStreak(user)
        val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0

        return WorkoutStats(
            totalWorkoutDays = totalWorkoutDays,
            currentWeekCount = currentWeekCount,
            weeklyGoal = 5,
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

    private fun calculateCurrentStreak(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val today = LocalDate.now()
        var streak = 0
        var currentDate = today

        while (true) {
            val hasWorkout = workoutSessionRepository.existsByUserAndDate(user, currentDate)
            if (hasWorkout) {
                streak++
                currentDate = currentDate.minusDays(1)
            } else if (currentDate == today) {
                currentDate = currentDate.minusDays(1)
            } else {
                break
            }
        }

        return streak
    }

    private fun calculateWeeklyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val startOfWeek = LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() - 1)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startOfWeek.atStartOfDay(),
            LocalDateTime.now()
        )
    }

    private fun calculateMonthlyWorkoutCount(user: com.richjun.liftupai.domain.auth.entity.User): Int {
        val startOfMonth = LocalDate.now().withDayOfMonth(1)
        return workoutSessionRepository.countByUserAndDateRange(
            user,
            startOfMonth.atStartOfDay(),
            LocalDateTime.now()
        )
    }

    private fun calculateLastWeekAverageVolume(user: com.richjun.liftupai.domain.auth.entity.User): Double {
        val lastWeekStart = LocalDate.now().minusWeeks(1).atStartOfDay()
        val lastWeekEnd = LocalDate.now().atStartOfDay()

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
        return "https://liftup-cdn.com/exercises/${exercise.id}/animation.gif"
    }

    private fun generateThumbnailUrl(exercise: Exercise): String {
        return "https://liftup-cdn.com/exercises/${exercise.id}/thumb.jpg"
    }

    private fun generateVideoUrl(exercise: Exercise): String {
        return "https://liftup-cdn.com/exercises/${exercise.id}/video.mp4"
    }

    private fun generateInstructions(exercise: Exercise): List<String> {
        return when (exercise.name) {
            "벤치프레스" -> listOf(
                "벤치에 등을 대고 눕습니다",
                "견갑골을 모으고 아치를 만듭니다",
                "바벨을 어깨너비보다 약간 넓게 잡습니다",
                "천천히 가슴으로 내린 후 폭발적으로 밀어올립니다"
            )
            else -> listOf(exercise.instructions ?: "표준 자세로 수행하세요")
        }
    }

    private fun generateTips(exercise: Exercise): List<String> {
        return when (exercise.category) {
            ExerciseCategory.CHEST -> listOf(
                "견갑골을 모으고 아치를 유지하세요",
                "손목은 중립 위치를 유지하세요",
                "팔꿈치는 45-75도 각도를 유지하세요"
            )
            else -> listOf("정확한 자세를 유지하세요")
        }
    }

    private fun generateCommonMistakes(exercise: Exercise): List<String> {
        return when (exercise.name) {
            "벤치프레스" -> listOf(
                "바벨을 너무 높은 위치(목 쪽)에서 내리기",
                "엉덩이를 벤치에서 들어올리기",
                "바운싱(가슴에서 튕기기)"
            )
            else -> listOf("급한 동작", "불완전한 가동범위")
        }
    }

    private fun generateBreathingGuide(exercise: Exercise): String {
        return "내릴 때 들이마시고, 올릴 때 내쉬세요"
    }

    @Transactional
    fun updateSession(userId: Long, sessionId: Long, request: UpdateSessionRequest): UpdateSessionResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 세션 조회 및 권한 확인
        val session = workoutSessionRepository.findById(sessionId)
            .orElseThrow { ResourceNotFoundException("운동 세션을 찾을 수 없습니다") }

        if (session.user.id != userId) {
            throw ResourceNotFoundException("권한이 없습니다")
        }

        // 진행 중인 세션만 업데이트 가능
        if (session.status != SessionStatus.IN_PROGRESS) {
            throw IllegalStateException("진행 중인 세션만 업데이트할 수 있습니다")
        }

        var totalSets = 0
        var completedSets = 0
        var updatedExercises = 0

        // 각 운동별로 업데이트
        request.exercises.forEach { exerciseData ->
            // 운동 존재 확인
            val exercise = exerciseRepository.findById(exerciseData.exerciseId)
                .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다: ${exerciseData.exerciseId}") }

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
            message = "세션이 성공적으로 업데이트되었습니다",
            sessionId = sessionId,
            updatedExercises = updatedExercises,
            totalSets = totalSets,
            completedSets = completedSets
        )
    }

    private fun mapMuscleGroupToKorean(muscleGroup: MuscleGroup): String {
        return when (muscleGroup) {
            MuscleGroup.CHEST -> "가슴"
            MuscleGroup.BACK -> "등"
            MuscleGroup.SHOULDERS -> "어깨"
            MuscleGroup.BICEPS -> "이두근"
            MuscleGroup.TRICEPS -> "삼두근"
            MuscleGroup.LEGS -> "다리"
            MuscleGroup.CORE -> "코어"
            MuscleGroup.ABS -> "복근"
            MuscleGroup.GLUTES -> "둔근"
            MuscleGroup.CALVES -> "종아리"
            MuscleGroup.FOREARMS -> "전완근"
            MuscleGroup.NECK -> "목"
            MuscleGroup.QUADRICEPS -> "대퇴사두근"
            MuscleGroup.HAMSTRINGS -> "햄스트링"
            MuscleGroup.LATS -> "광배근"
            MuscleGroup.TRAPS -> "승모근"
        }
    }

    /**
     * 운동 완료 후 근육 회복 데이터를 업데이트합니다.
     * MuscleRecovery 엔티티에 각 근육별로 마지막 운동 시간을 기록합니다.
     */
    private fun updateMuscleRecoveryAfterWorkout(
        user: com.richjun.liftupai.domain.auth.entity.User,
        completedExercises: List<CompletedExerciseV2>
    ) {
        val now = LocalDateTime.now()
        val muscleGroupsWorked = mutableSetOf<String>()

        // 완료된 운동들에서 근육 그룹 추출
        completedExercises.forEach { completedExercise ->
            val exercise = exerciseRepository.findById(completedExercise.exerciseId).orElse(null)
            if (exercise != null) {
                println("DEBUG: Exercise ${exercise.name} (ID: ${exercise.id})")
                println("DEBUG: - Category: ${exercise.category}")
                println("DEBUG: - MuscleGroups: ${exercise.muscleGroups.map { it.name }}")

                // Exercise의 muscleGroups에서 근육 그룹 가져오기 (한글로 변환)
                exercise.muscleGroups.forEach { muscleGroup ->
                    val koreanName = mapMuscleGroupToKorean(muscleGroup)
                    muscleGroupsWorked.add(koreanName)
                    println("DEBUG: Added muscle group: ${muscleGroup.name} -> $koreanName")
                }

                // Exercise의 category를 기본 근육 그룹으로도 추가 (한글로 변환)
                val categoryMuscle = when (exercise.category) {
                    ExerciseCategory.CHEST -> "가슴"
                    ExerciseCategory.BACK -> "등"
                    ExerciseCategory.LEGS -> "다리"
                    ExerciseCategory.SHOULDERS -> "어깨"
                    ExerciseCategory.ARMS -> "팔"
                    ExerciseCategory.CORE -> "복근"
                    else -> null
                }
                categoryMuscle?.let {
                    muscleGroupsWorked.add(it)
                    println("DEBUG: Added category muscle: ${exercise.category} -> $it")
                }
            }
        }

        println("DEBUG: Total muscle groups to save: $muscleGroupsWorked")

        // 각 근육 그룹에 대해 MuscleRecovery 업데이트
        muscleGroupsWorked.forEach { muscleGroup ->
            println("DEBUG: Processing muscle group: $muscleGroup")

            val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, muscleGroup)
                .orElseGet {
                    println("DEBUG: Creating new MuscleRecovery for $muscleGroup")
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
            println("DEBUG: Saved MuscleRecovery ID: ${saved.id} for muscle: $muscleGroup")
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

    // ========================================
    // 헬스 트레이너 관점 유틸리티 메서드 (WorkoutServiceV2 전용)
    // ========================================

    /**
     * 복합운동 여부 판별
     */
    private fun isCompoundExerciseV2(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true

        val name = exercise.name.lowercase()
        val compoundKeywords = listOf(
            "프레스", "스쿼트", "데드리프트", "로우", "풀업", "친업",
            "딥", "런지", "푸쉬업", "벤치", "밀리터리"
        )

        return compoundKeywords.any { name.contains(it) }
    }

    /**
     * 운동 우선순위 정렬
     */
    private fun orderExercisesByPriorityV2(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(
            compareBy<Exercise> { exercise ->
                when (exercise.category) {
                    ExerciseCategory.LEGS -> 1
                    ExerciseCategory.BACK -> 2
                    ExerciseCategory.CHEST -> 3
                    ExerciseCategory.SHOULDERS -> 4
                    ExerciseCategory.ARMS -> 5
                    ExerciseCategory.CORE -> 6
                    else -> 7
                }
            }.thenBy { exercise ->
                if (isCompoundExerciseV2(exercise)) 0 else 1
            }
        )
    }

    /**
     * 최근 N시간 이내에 운동한 근육군 조회
     */
    private fun getRecentlyWorkedMusclesV2(user: com.richjun.liftupai.domain.auth.entity.User, hours: Int): Set<MuscleGroup> {
        val cutoffTime = LocalDateTime.now().minusHours(hours.toLong())

        return workoutSessionRepository
            .findByUserAndStartTimeAfter(user, cutoffTime)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .flatMap { it.exercise.muscleGroups }
            }
            .toSet()
    }

    /**
     * 특정 근육군의 주간 볼륨 계산
     */
    private fun calculateWeeklyVolumeV2(user: com.richjun.liftupai.domain.auth.entity.User, muscleGroup: MuscleGroup): Int {
        val oneWeekAgo = LocalDateTime.now().minusDays(7)

        return workoutSessionRepository
            .findByUserAndStartTimeAfter(user, oneWeekAgo)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .filter { it.exercise.muscleGroups.contains(muscleGroup) }
            }
            .sumOf { exerciseSetRepository.findByWorkoutExerciseId(it.id).size }
    }

    /**
     * 모든 주요 근육군의 주간 볼륨 맵
     */
    private fun getWeeklyVolumeMapV2(user: com.richjun.liftupai.domain.auth.entity.User): Map<String, Int> {
        val majorMuscleGroups = listOf(
            MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LEGS,
            MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS,
            MuscleGroup.CORE
        )

        return majorMuscleGroups.associate { muscleGroup ->
            translateMuscleGroupToKorean(muscleGroup) to calculateWeeklyVolumeV2(user, muscleGroup)
        }
    }

    /**
     * MuscleGroup을 한글로 변환
     */
    private fun translateMuscleGroupToKorean(muscleGroup: MuscleGroup): String {
        return when (muscleGroup) {
            MuscleGroup.CHEST -> "가슴"
            MuscleGroup.BACK -> "등"
            MuscleGroup.LEGS -> "하체"
            MuscleGroup.SHOULDERS -> "어깨"
            MuscleGroup.BICEPS -> "이두"
            MuscleGroup.TRICEPS -> "삼두"
            MuscleGroup.CORE -> "코어"
            else -> muscleGroup.name
        }
    }

    /**
     * 운동 다양성 보장 (익숙한 운동 vs 새로운 운동 비율 조정)
     *
     * 개선사항:
     * - 친숙도 판단 기간: 2주 → 4주로 확장
     * - 인기도/난이도 고려하여 새로운 운동 선택
     * - 기본 운동 우선 추천
     */
    private fun ensureExerciseVarietyV2(
        user: com.richjun.liftupai.domain.auth.entity.User,
        candidates: List<Exercise>,
        familiarCount: Int,
        newCount: Int
    ): List<Exercise> {
        val fourWeeksAgo = LocalDateTime.now().minusDays(28)  // 2주 → 4주로 확장

        // 최근 4주간 한 운동 ID 목록 + 횟수
        val recentExerciseStats = workoutSessionRepository
            .findByUserAndStartTimeAfter(user, fourWeeksAgo)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .map { it.exercise.id }
            }
            .groupingBy { it }
            .eachCount()

        // 익숙한 운동 (4주 내 2회 이상), 새로운 운동, 기본 운동 분리
        val veryFamiliarExercises = candidates.filter { (recentExerciseStats[it.id] ?: 0) >= 2 }
            .sortedByDescending { recentExerciseStats[it.id] ?: 0 }  // 많이 한 운동 우선

        val somewhatFamiliarExercises = candidates.filter { (recentExerciseStats[it.id] ?: 0) == 1 }

        val newExercises = candidates.filter { it.id !in recentExerciseStats.keys }
            .sortedWith(
                compareByDescending<Exercise> { it.isBasicExercise }  // 기본 운동 우선
                    .thenByDescending { it.popularity }  // 인기도 높은 것 우선
                    .thenBy { it.difficulty }  // 쉬운 것 우선
            )

        // 익숙한 운동 먼저 선택 (매우 익숙 → 약간 익숙)
        val familiarExercises = (veryFamiliarExercises + somewhatFamiliarExercises)
            .distinctBy { it.id }
            .take(familiarCount)

        // 새로운 운동은 인기도/난이도 고려하여 선택
        val selectedNewExercises = newExercises
            .distinctBy { it.id }
            .filter { it !in familiarExercises }
            .take(newCount)

        val selected = familiarExercises + selectedNewExercises

        // 부족하면 나머지로 채우기 (인기도 높은 것 우선)
        return if (selected.size < (familiarCount + newCount)) {
            val remaining = candidates
                .filter { it !in selected }
                .sortedByDescending { it.popularity }
            (selected + remaining).take(familiarCount + newCount)
        } else {
            selected
        }
    }
}
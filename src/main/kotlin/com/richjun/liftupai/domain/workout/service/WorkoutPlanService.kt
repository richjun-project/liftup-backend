package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.domain.workout.entity.WorkoutPlan
import com.richjun.liftupai.domain.workout.repository.WorkoutPlanRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTemplateRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTemplate
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.WorkoutGoal
import com.richjun.liftupai.domain.workout.util.WorkoutAliasCatalog
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional
class WorkoutPlanService(
    val userRepository: UserRepository,
    val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutPlanRepository: WorkoutPlanRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val userService: UserService,
    private val aiAnalysisService: AIAnalysisService,
    private val objectMapper: ObjectMapper,
    val exerciseRepository: ExerciseRepository,
    val exerciseTemplateRepository: ExerciseTemplateRepository,
    val personalRecordRepository: PersonalRecordRepository,
    val exerciseSetRepository: ExerciseSetRepository,
    private val workoutProgressTracker: WorkoutProgressTracker,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {

    fun updateWorkoutPlan(userId: Long, request: WorkoutPlanRequest): WorkoutPlanResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("Profile not found") }

        // Update profile with plan details
        profile.weeklyWorkoutDays = request.weeklyWorkoutDays
        profile.workoutSplit = request.workoutSplit
        profile.preferredWorkoutTime = request.preferredWorkoutTime
        profile.workoutDuration = request.workoutDuration
        profile.availableEquipment.clear()
        profile.availableEquipment.addAll(request.availableEquipment)
        profile.updatedAt = LocalDateTime.now()

        userProfileRepository.save(profile)

        // Determine recommended program based on parameters
        val recommendedProgram = determineRecommendedProgram(
            request.weeklyWorkoutDays,
            request.workoutSplit,
            profile.experienceLevel.name
        )

        profile.currentProgram = recommendedProgram
        userProfileRepository.save(profile)

        return WorkoutPlanResponse(
            weeklyWorkoutDays = request.weeklyWorkoutDays,
            workoutSplit = request.workoutSplit,
            preferredWorkoutTime = request.preferredWorkoutTime,
            workoutDuration = request.workoutDuration,
            availableEquipment = request.availableEquipment,
            recommendedProgram = recommendedProgram
        )
    }

    fun generateProgram(userId: Long, request: GenerateProgramRequest): GeneratedProgramResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        // Use AI to generate personalized program
        val prompt = buildProgramPrompt(request)
        val aiResponse = "AI generated program"

        // Parse AI response to create program schedule
        val locale = resolveLocale(userId)
        val schedule = parseAIResponseToSchedule(aiResponse, request, locale)

        // Save as workout plan
        val programName = "${request.weeklyWorkoutDays}-day ${translateSplit(request.workoutSplit)} ${translateGoals(request.goals)} program"

        val workoutPlan = WorkoutPlan(
            user = user,
            name = programName,
            weeklyDays = request.weeklyWorkoutDays,
            splitType = request.workoutSplit,
            programDurationWeeks = 8,
            schedule = objectMapper.writeValueAsString(schedule)
        )

        workoutPlanRepository.save(workoutPlan)

        // Update profile
        val profile = userProfileRepository.findByUser_Id(userId).orElseThrow()
        profile.currentProgram = programName
        profile.currentWeek = 1
        userProfileRepository.save(profile)

        return GeneratedProgramResponse(
            programName = programName,
            weeks = 8,
            schedule = schedule
        )
    }

    fun getTodayWorkoutRecommendation(userId: Long, request: TodayWorkoutRequest): TodayWorkoutResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("Profile not found") }

        // 프로그램 진행 상황 가져오기 (고정 순서 사용)
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(
            user,
            profile.weeklyWorkoutDays ?: 3
        )

        // 프로그램 타입에 따른 운동 타입 시퀀스 가져오기
        val workoutSequence = workoutProgressTracker.getWorkoutTypeSequence(
            request.workoutSplit
        )

        // 오늘의 운동 타입 결정 (고정 순서)
        val workoutType = workoutSequence.getOrNull(programPosition.day - 1)
            ?: com.richjun.liftupai.domain.workout.entity.WorkoutType.FULL_BODY

        // 운동 타입에 따른 타겟 근육 결정
        val locale = resolveLocale(userId)
        val (workoutName, targetMuscles) = getWorkoutDetailsFromType(workoutType, locale)

        // DB에서 실제 운동 데이터 필터링해서 가져오기
        val targetMuscleForFilter = mapWorkoutTypeToTargetMuscle(workoutType)
        val exercises = getExercisesFromDatabase(
            user,
            profile,
            targetMuscleForFilter,
            profile.workoutDuration ?: 60
        )

        val reason = "Program progression: cycle ${programPosition.cycle}, day ${programPosition.day} - $workoutName"

        return TodayWorkoutResponse(
            workoutName = workoutName,
            targetMuscles = targetMuscles,
            estimatedDuration = profile.workoutDuration ?: 60,
            exercises = exercises,
            reason = reason
        )
    }

    @Transactional(readOnly = true)
    fun getWeeklyStats(userId: Long): WeeklyStatsResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("Profile not found") }

        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay()
        val weekEnd = weekStart.plusDays(6).plusHours(23).plusMinutes(59)

        val sessions = workoutSessionRepository.findByUser_IdAndStartTimeBetween(
            userId, weekStart, weekEnd
        )

        val totalVolume = sessions.sumOf { session -> session.totalVolume ?: 0.0 }
        // Simplified calculation for now
        val totalSets = sessions.size * 20 // Estimated
        val totalReps = sessions.size * 200 // Estimated

        val workoutDates = sessions.map { session -> session.startTime.toLocalDate().toString() }
        val completedDays = workoutDates.size

        // Calculate next workout day
        val nextWorkoutDay = calculateNextWorkoutDay(
            profile.weeklyWorkoutDays ?: 3,
            workoutDates,
            profile.preferredWorkoutTime ?: "evening"
        )

        val weeklyProgress = (completedDays * 100) / (profile.weeklyWorkoutDays ?: 3)

        return WeeklyStatsResponse(
            targetDays = profile.weeklyWorkoutDays ?: 3,
            completedDays = completedDays,
            totalVolume = totalVolume,
            totalSets = totalSets,
            totalReps = totalReps,
            workoutDates = workoutDates,
            nextWorkoutDay = nextWorkoutDay,
            weeklyProgress = weeklyProgress
        )
    }

    private fun determineRecommendedProgram(days: Int, split: String, level: String): String {
        return when {
            days <= 2 -> "Full-body program"
            days == 3 && split == "full_body" -> "3-day full-body program"
            days == 3 && split == "ppl" -> "Beginner push/pull/legs program"
            days == 4 && split == "upper_lower" -> "Intermediate upper/lower split program"
            days == 5 && split == "ppl" -> "Advanced push/pull/legs program"
            days >= 6 && split == "bro_split" -> "Bodybuilding split program"
            else -> "$days-day ${translateSplit(split)} program"
        }
    }

    private fun translateSplit(split: String): String {
        return when (split.lowercase()) {
            "full_body" -> "full body"
            "upper_lower" -> "upper/lower split"
            "ppl", "push_pull_legs" -> "push/pull/legs"
            "push_pull" -> "push/pull"
            "bro_split" -> "body-part split"
            else -> split
        }
    }

    private fun translateGoals(goals: List<String>): String {
        val translated = goals.map {
            when (it.uppercase()) {
                "MUSCLE_GAIN" -> "muscle gain"
                "FAT_LOSS" -> "fat loss"
                "STRENGTH" -> "strength"
                "ENDURANCE" -> "endurance"
                else -> it
            }
        }
        return translated.joinToString(", ")
    }

    private fun determineReadyMuscles(muscleRecovery: Map<String, String>?): List<String> {
        if (muscleRecovery.isNullOrEmpty()) {
            return listOf("chest", "back", "legs", "shoulders", "arms", "core")
        }

        val now = LocalDateTime.now()
        val readyMuscles = mutableListOf<String>()

        val allMuscles = listOf("chest", "back", "legs", "shoulders", "arms", "core")

        allMuscles.forEach { muscle ->
            val lastWorkout = muscleRecovery[muscle]?.let {
                try {
                    LocalDateTime.parse(it)
                } catch (e: Exception) {
                    null
                }
            }

            if (lastWorkout == null || lastWorkout.plusHours(48).isBefore(now)) {
                readyMuscles.add(muscle)
            }
        }

        return readyMuscles.ifEmpty { allMuscles }
    }

    private fun determineWorkout(
        split: String,
        readyMuscles: List<String>,
        lastWorkoutDate: LocalDateTime?
    ): Pair<String, List<String>> {
        return when (split) {
            "upper_lower" -> {
                if (readyMuscles.containsAll(listOf("chest", "back", "shoulders"))) {
                    "Upper body workout" to listOf("chest", "back", "shoulders", "arms")
                } else {
                    "Lower body workout" to listOf("legs", "glutes", "core")
                }
            }
            "ppl", "push_pull_legs" -> {
                when {
                    readyMuscles.containsAll(listOf("chest", "shoulders")) -> "Push Day" to listOf("chest", "shoulders", "triceps")
                    readyMuscles.contains("back") -> "Pull Day" to listOf("back", "biceps")
                    readyMuscles.contains("legs") -> "Leg Day" to listOf("legs", "glutes")
                    else -> "Full body workout" to readyMuscles
                }
            }
            "full_body" -> "Full body workout" to listOf("chest", "back", "legs", "shoulders")
            else -> "Full body workout" to readyMuscles.take(3)
        }
    }

    private fun buildProgramPrompt(request: GenerateProgramRequest): String {
        return """
            Generate a workout program:
            - Weekly training days: ${request.weeklyWorkoutDays}
            - Split: ${translateSplit(request.workoutSplit)}
            - Experience level: ${request.experienceLevel}
            - Goals: ${request.goals.joinToString(", ")}
            - Available equipment: ${request.availableEquipment.joinToString(", ")}
            - Duration: ${request.duration} minutes

            Include exercise selection, sets, reps, and rest for each training day.
        """.trimIndent()
    }

    private fun buildWorkoutPrompt(
        workoutName: String,
        targetMuscles: List<String>,
        level: String,
        goals: List<String>,
        equipment: List<String>
    ): String {
        return """
            Today's workout: $workoutName
            Target muscles: ${targetMuscles.joinToString(", ")}
            Experience level: $level
            Goals: ${goals.joinToString(", ")}
            Available equipment: ${equipment.joinToString(", ")}

            Recommend 5-7 exercises with:
            - Exercise name
            - Sets and reps
            - Rest time
            - Execution tips
        """.trimIndent()
    }

    private fun parseAIResponseToSchedule(aiResponse: String, request: GenerateProgramRequest, locale: String = "en"): ProgramSchedule {
        val experienceLevel = resolveExperienceLevel(request.experienceLevel)
        val workoutGoal = resolveWorkoutGoal(request.goals)
        val availableEquipment = request.availableEquipment.map { it.uppercase() }.toSet()
        val workoutTypes = workoutProgressTracker.getWorkoutTypeSequence(request.workoutSplit)
        val exerciseLimit = when {
            request.duration <= 30 -> 4
            request.duration <= 60 -> 5
            else -> 6
        }

        return ProgramSchedule(
            monday = buildWorkoutDay(1, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale),
            tuesday = buildWorkoutDay(2, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale),
            wednesday = null,
            thursday = buildWorkoutDay(3, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale),
            friday = buildWorkoutDay(4, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale),
            saturday = buildWorkoutDay(5, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale),
            sunday = null
        )
    }

    private fun buildWorkoutDay(
        dayNumber: Int,
        weeklyWorkoutDays: Int,
        workoutTypes: List<WorkoutType>,
        availableEquipment: Set<String>,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal,
        exerciseLimit: Int,
        locale: String = "en"
    ): WorkoutDay? {
        if (dayNumber > weeklyWorkoutDays) return null

        val workoutType = workoutTypes.getOrElse((dayNumber - 1) % workoutTypes.size) {
            WorkoutType.FULL_BODY
        }
        val (workoutName, targetMuscles) = getWorkoutDetailsFromType(workoutType, locale)

        return WorkoutDay(
            name = "Day $dayNumber",
            exercises = buildExercisePlans(
                targetMuscles = targetMuscles,
                availableEquipment = availableEquipment,
                experienceLevel = experienceLevel,
                workoutGoal = workoutGoal,
                limit = exerciseLimit
            ).ifEmpty {
                listOf(
                    ExercisePlan(
                        name = workoutName,
                        sets = defaultSetCount(experienceLevel),
                        reps = "8-12",
                        rest = 90
                    )
                )
            }
        )
    }

    private fun parseAIResponseToExercises(aiResponse: String, targetMuscles: List<String>): List<ExerciseDetailV4> {
        // 현재 메서드는 getTodayWorkoutRecommendation에서 호출됨
        // request를 통해 사용자 정보 접근 필요
        return parseAIResponseToExercisesWithUser(aiResponse, targetMuscles, null, null, null)
    }

    private fun parseAIResponseToExercisesWithUser(
        aiResponse: String,
        targetMuscles: List<String>,
        userId: Long?,
        experienceLevel: String?,
        goals: List<String>?
    ): List<ExerciseDetailV4> {
        val user = userId?.let { id ->
            userRepository.findById(id).orElse(null)
        }
        val profile = userId?.let { id ->
            userProfileRepository.findByUser_Id(id).orElse(null)
        }
        val resolvedExperience = resolveExperienceLevel(experienceLevel ?: profile?.experienceLevel?.name)
        val resolvedGoal = resolveWorkoutGoal(goals ?: profile?.goals?.map { it.name })
        val availableEquipment = profile?.availableEquipment?.map { it.uppercase() }?.toSet() ?: emptySet()
        val bodyWeight = profile?.bodyInfo?.weight ?: 70.0
        val exerciseLimit = when {
            targetMuscles.size <= 1 -> 4
            targetMuscles.size == 2 -> 5
            else -> 6
        }

        return buildExerciseDetails(
            targetMuscles = targetMuscles,
            user = user,
            bodyWeight = bodyWeight,
            availableEquipment = availableEquipment,
            experienceLevel = resolvedExperience,
            workoutGoal = resolvedGoal,
            limit = exerciseLimit
        ).ifEmpty {
            buildExerciseDetails(
                targetMuscles = listOf("full_body"),
                user = user,
                bodyWeight = bodyWeight,
                availableEquipment = availableEquipment,
                experienceLevel = resolvedExperience,
                workoutGoal = resolvedGoal,
                limit = 3
            )
        }
    }

    private fun buildExercisePlans(
        targetMuscles: List<String>,
        availableEquipment: Set<String>,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal,
        limit: Int
    ): List<ExercisePlan> {
        return selectExercisesForTargets(targetMuscles, availableEquipment, limit).map { exercise ->
            val template = resolveExerciseTemplate(exercise, experienceLevel, workoutGoal)

            ExercisePlan(
                name = exercise.name,
                sets = template?.sets ?: defaultSetCount(experienceLevel),
                reps = template?.let { "${it.minReps}-${it.maxReps}" } ?: defaultRepRange(exercise, experienceLevel),
                rest = template?.restSeconds ?: calculateRestTime(exercise)
            )
        }
    }

    private fun buildExerciseDetails(
        targetMuscles: List<String>,
        user: User?,
        bodyWeight: Double,
        availableEquipment: Set<String>,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal,
        limit: Int
    ): List<ExerciseDetailV4> {
        return selectExercisesForTargets(targetMuscles, availableEquipment, limit).map { exercise ->
            val template = resolveExerciseTemplate(exercise, experienceLevel, workoutGoal)
            val sets = template?.let {
                val workingWeight = calculateWeightForUser(user, exercise, it, bodyWeight)
                createSetsFromTemplate(it, workingWeight)
            } ?: generateDefaultSets(exercise, experienceLevel)

            ExerciseDetailV4(
                id = exercise.id.toString(),
                name = exercise.name,
                targetMuscle = resolveTargetLabel(exercise, targetMuscles),
                sets = sets,
                restTime = template?.restSeconds ?: calculateRestTime(exercise),
                tips = extractPrimaryTip(exercise)
            )
        }
    }

    private fun selectExercisesForTargets(
        targetMuscles: List<String>,
        availableEquipment: Set<String>,
        limit: Int
    ): List<Exercise> {
        val normalizedTargets = if (targetMuscles.isEmpty()) listOf("full_body") else targetMuscles
        val equipmentFiltered = exerciseRepository.findAll()
            .filter { exercise -> matchesAvailableEquipment(exercise, availableEquipment) }
        val perTargetLimit = maxOf(1, limit / normalizedTargets.size)

        val selected = normalizedTargets.flatMap { target ->
            val targetMuscleGroups = resolveMuscleGroups(target)
            val targetCandidates = equipmentFiltered.filter { exercise ->
                targetMuscleGroups.isEmpty() || exercise.muscleGroups.any { it in targetMuscleGroups }
            }
            prioritizeExercises(targetCandidates).take(perTargetLimit)
        }

        val distinctSelected = selected.distinctBy { it.id }
        if (distinctSelected.size >= limit) {
            return distinctSelected.take(limit)
        }

        val fallback = prioritizeExercises(
            equipmentFiltered.filterNot { candidate ->
                distinctSelected.any { it.id == candidate.id }
            }
        )

        return (distinctSelected + fallback)
            .distinctBy { it.id }
            .take(limit)
    }

    private fun prioritizeExercises(exercises: List<Exercise>): List<Exercise> {
        val coreExercises = removeDuplicatePatterns(
            exercises.filter { RecommendationExerciseRanking.isCoreCandidate(it) }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        val fallbackExercises = removeDuplicatePatterns(
            exercises.filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
                .filterNot { candidate -> coreExercises.any { it.id == candidate.id } }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        return (coreExercises + fallbackExercises).distinctBy { it.id }
    }

    private fun resolveExerciseTemplate(
        exercise: Exercise,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal
    ): ExerciseTemplate? {
        return exerciseTemplateRepository.findTemplate(exercise.id, experienceLevel, workoutGoal)
            ?: exerciseTemplateRepository.findByExerciseAndExperienceLevel(exercise, experienceLevel).firstOrNull()
    }

    private fun matchesAvailableEquipment(exercise: Exercise, availableEquipment: Set<String>): Boolean {
        if (availableEquipment.isEmpty()) return true
        val equipmentName = exercise.equipment?.name ?: return true
        return equipmentName == "BODYWEIGHT" || availableEquipment.contains(equipmentName)
    }

    private fun resolveMuscleGroups(targetMuscle: String): Set<MuscleGroup> {
        return WorkoutTargetResolver.muscleGroupsFor(targetMuscle)
    }

    private fun resolveTargetLabel(exercise: Exercise, targetMuscles: List<String>): String {
        val matchedTarget = targetMuscles.firstOrNull { target ->
            val targetMuscleGroups = resolveMuscleGroups(target)
            targetMuscleGroups.isEmpty() || exercise.muscleGroups.any { it in targetMuscleGroups }
        }

        val focus = WorkoutTargetResolver.resolveFocus(matchedTarget ?: targetMuscles.firstOrNull())
        return focus?.let { WorkoutTargetResolver.displayName(it, locale = "ko") }
            ?: matchedTarget
            ?: WorkoutTargetResolver.displayName(WorkoutFocus.FULL_BODY, locale = "ko")
    }

    private fun resolveExperienceLevel(value: String?): ExperienceLevel {
        return runCatching {
            ExperienceLevel.valueOf(value?.trim()?.uppercase() ?: ExperienceLevel.INTERMEDIATE.name)
        }.getOrDefault(ExperienceLevel.INTERMEDIATE)
    }

    private fun resolveWorkoutGoal(goals: List<String>?): WorkoutGoal {
        val normalizedGoals = goals.orEmpty().joinToString(" ").lowercase()

        return when {
            WorkoutAliasCatalog.list("goal.alias.strength").any { normalizedGoals.contains(it.lowercase()) } -> WorkoutGoal.STRENGTH
            WorkoutAliasCatalog.list("goal.alias.fat_loss").any { normalizedGoals.contains(it.lowercase()) } -> WorkoutGoal.FAT_LOSS
            WorkoutAliasCatalog.list("goal.alias.endurance").any { normalizedGoals.contains(it.lowercase()) } -> WorkoutGoal.ENDURANCE
            WorkoutAliasCatalog.list("goal.alias.muscle_gain").any { normalizedGoals.contains(it.lowercase()) } -> WorkoutGoal.MUSCLE_GAIN
            else -> WorkoutGoal.GENERAL_FITNESS
        }
    }

    private fun extractPrimaryTip(exercise: Exercise): String {
        return exercise.instructions
            ?.split("\n", ".")
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: when (exercise.category) {
                ExerciseCategory.LEGS -> "Brace the trunk and keep the range of motion controlled"
                ExerciseCategory.BACK -> "Prioritize full contraction and controlled eccentric work"
                ExerciseCategory.CHEST -> "Keep the scapula stable and maintain chest tension"
                ExerciseCategory.SHOULDERS -> "Reduce trap dominance and lead with the shoulder"
                ExerciseCategory.ARMS -> "Fix the joints and focus on peak contraction"
                ExerciseCategory.CORE -> "Maintain breathing and trunk pressure throughout the set"
                else -> "Maintain clean technique and a consistent tempo"
            }
    }

    private fun defaultSetCount(experienceLevel: ExperienceLevel): Int {
        return when (experienceLevel) {
            ExperienceLevel.NOVICE, ExperienceLevel.BEGINNER -> 3
            ExperienceLevel.INTERMEDIATE -> 4
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> 5
        }
    }

    private fun defaultRepRange(exercise: Exercise, experienceLevel: ExperienceLevel): String {
        return when (experienceLevel) {
            ExperienceLevel.NOVICE, ExperienceLevel.BEGINNER -> when (exercise.category) {
                ExerciseCategory.CORE -> "12-20"
                ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS -> "10-15"
                else -> "10-12"
            }
            ExperienceLevel.INTERMEDIATE -> when (exercise.category) {
                ExerciseCategory.CORE -> "15-20"
                ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS -> "10-12"
                else -> "8-10"
            }
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> when (exercise.category) {
                ExerciseCategory.CORE -> "15-20"
                ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS -> "8-10"
                else -> "5-8"
            }
        }
    }

    private fun buildReasonMessage(
        lastWorkoutDate: LocalDateTime?,
        readyMuscles: List<String>,
        weeklyDays: Int,
        split: String
    ): String {
        val daysSinceLastWorkout = lastWorkoutDate?.let {
            java.time.Duration.between(it, LocalDateTime.now()).toDays()
        } ?: 0

        return when {
            daysSinceLastWorkout == 0L -> "You already trained today. Prioritize recovery."
            daysSinceLastWorkout >= 3 -> "$daysSinceLastWorkout days have passed since your last workout. Today is a good day to train."
            else -> "Based on your $weeklyDays-day ${translateSplit(split)} plan, today is a ${readyMuscles.take(2).joinToString(", ")} day."
        }
    }

    private fun calculateNextWorkoutDay(
        targetDays: Int,
        completedDates: List<String>,
        preferredTime: String
    ): String? {
        val today = LocalDate.now()
        val daysCompleted = completedDates.size

        if (daysCompleted >= targetDays) {
            return null // Already met weekly goal
        }

        // Simple logic: suggest tomorrow if not worked out today
        val todayString = today.toString()
        return if (todayString in completedDates) {
            today.plusDays(1).toString()
        } else {
            todayString
        }
    }

    /**
     * 운동 타입에 따른 운동 이름과 타겟 근육 반환
     * WorkoutProgressTracker의 determineWorkoutType()와 역방향 매핑
     */
    private fun getWorkoutDetailsFromType(workoutType: com.richjun.liftupai.domain.workout.entity.WorkoutType, locale: String = "en"): Pair<String, List<String>> {
        val primaryFocus = WorkoutTargetResolver.primaryFocusForWorkoutType(workoutType)
        val focusTargets = WorkoutTargetResolver.targetsForWorkoutType(workoutType)
        val workoutName = "${WorkoutTargetResolver.displayName(primaryFocus, locale = locale)} workout"
        val targetLabels = focusTargets.map { focus ->
            WorkoutTargetResolver.displayName(focus, locale = locale)
        }

        return workoutName to targetLabels
    }

    /**
     * 운동 타입을 필터링용 타겟 근육 문자열로 매핑
     * WorkoutServiceV2의 필터링 로직과 호환되도록 영어로 반환
     */
    private fun mapWorkoutTypeToTargetMuscle(workoutType: com.richjun.liftupai.domain.workout.entity.WorkoutType): String {
        val focus = WorkoutTargetResolver.primaryFocusForWorkoutType(workoutType)
        return when (focus) {
            WorkoutFocus.CARDIO -> WorkoutTargetResolver.key(WorkoutFocus.FULL_BODY)
            else -> WorkoutTargetResolver.key(focus)
        }
    }

    /**
     * DB에서 운동 데이터를 필터링해서 가져오기
     * WorkoutServiceV2의 로직을 재사용하여 일관성 유지
     */
    private fun getExercisesFromDatabase(
        user: User,
        profile: UserProfile,
        targetMuscle: String,
        duration: Int
    ): List<ExerciseDetailV4> {
        // WorkoutServiceV2의 필터링 로직을 사용
        var exercises = exerciseRepository.findAll().toList()

        // 1. 장비 필터링 (프로필의 availableEquipment 사용)
        val availableEquipment = profile.availableEquipment
        if (availableEquipment.isNotEmpty()) {
            exercises = exercises.filter { exercise ->
                exercise.equipment?.let { availableEquipment.contains(it.name) } ?: false
            }
        }

        // 2. 타겟 근육 필터링
        exercises = filterByTargetMuscle(exercises, targetMuscle)

        // 3. 핵심 운동 우선 추천, 부족할 때만 일반 운동으로 보충
        val coreExercises = removeDuplicatePatterns(
            exercises.filter { RecommendationExerciseRanking.isCoreCandidate(it) }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        val fallbackExercises = removeDuplicatePatterns(
            exercises.filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
                .filterNot { candidate -> coreExercises.any { it.id == candidate.id } }
        ).sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        exercises = (coreExercises + fallbackExercises).distinctBy { it.id }

        // 4. 운동 개수 결정 (duration 기반)
        val targetExerciseCount = when {
            duration <= 30 -> 4
            duration <= 45 -> 5
            duration <= 60 -> 6
            duration <= 75 -> 7
            else -> 8
        }

        // 5. Exercise를 ExerciseDetailV4로 변환
        return exercises.take(targetExerciseCount).map { exercise ->
            ExerciseDetailV4(
                id = exercise.id.toString(),
                name = exercise.name,
                targetMuscle = targetMuscle,
                sets = generateDefaultSets(exercise, profile.experienceLevel),
                restTime = calculateRestTime(exercise),
                tips = "Use controlled technique throughout the set"
            )
        }
    }

    /**
     * 타겟 근육으로 운동 필터링
     * WorkoutServiceV2의 로직과 동일
     */
    private fun filterByTargetMuscle(exercises: List<Exercise>, targetMuscle: String): List<Exercise> {
        return when (targetMuscle.lowercase()) {
            "full_body" -> exercises
            "legs", "lower" -> {
                val legMuscles = setOf(
                    MuscleGroup.QUADRICEPS,
                    MuscleGroup.HAMSTRINGS,
                    MuscleGroup.GLUTES,
                    MuscleGroup.CALVES
                )
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in legMuscles }
                }
            }
            "push" -> {
                // 밀기 운동: 가슴, 어깨, 삼두
                val pushMuscles = setOf(
                    MuscleGroup.CHEST,
                    MuscleGroup.SHOULDERS,
                    MuscleGroup.TRICEPS
                )
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in pushMuscles }
                }
            }
            "pull" -> {
                // 당기기 운동: 등, 이두
                val pullMuscles = setOf(
                    MuscleGroup.BACK,
                    MuscleGroup.LATS,
                    MuscleGroup.BICEPS,
                    MuscleGroup.FOREARMS
                )
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in pullMuscles }
                }
            }
            "upper" -> {
                val upperMuscles = setOf(
                    MuscleGroup.CHEST,
                    MuscleGroup.BACK,
                    MuscleGroup.LATS,
                    MuscleGroup.SHOULDERS,
                    MuscleGroup.BICEPS,
                    MuscleGroup.TRICEPS,
                    MuscleGroup.FOREARMS
                )
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in upperMuscles }
                }
            }
            "chest" -> exercises.filter { it.muscleGroups.contains(MuscleGroup.CHEST) }
            "back" -> {
                val backMuscles = setOf(MuscleGroup.BACK, MuscleGroup.LATS)
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in backMuscles }
                }
            }
            "shoulders" -> exercises.filter { it.muscleGroups.contains(MuscleGroup.SHOULDERS) }
            "arms" -> {
                val armMuscles = setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS)
                exercises.filter { exercise ->
                    exercise.muscleGroups.any { it in armMuscles }
                }
            }
            "core" -> exercises.filter { it.muscleGroups.contains(MuscleGroup.ABS) }
            else -> exercises
        }
    }

    /**
     * 경험 수준에 따른 기본 세트 생성
     */
    private fun generateDefaultSets(exercise: Exercise, experienceLevel: ExperienceLevel?): List<SetDetail> {
        val level = experienceLevel ?: ExperienceLevel.INTERMEDIATE

        return when (level) {
            ExperienceLevel.NOVICE, ExperienceLevel.BEGINNER -> listOf(
                SetDetail(1, 12, 0.0, "warm_up"),
                SetDetail(2, 10, 40.0, "working"),
                SetDetail(3, 10, 40.0, "working")
            )
            ExperienceLevel.INTERMEDIATE -> listOf(
                SetDetail(1, 12, 0.0, "warm_up"),
                SetDetail(2, 10, 60.0, "working"),
                SetDetail(3, 8, 70.0, "working"),
                SetDetail(4, 8, 70.0, "working")
            )
            ExperienceLevel.ADVANCED, ExperienceLevel.EXPERT -> listOf(
                SetDetail(1, 10, 40.0, "warm_up"),
                SetDetail(2, 8, 70.0, "working"),
                SetDetail(3, 6, 80.0, "working"),
                SetDetail(4, 6, 85.0, "working"),
                SetDetail(5, 4, 90.0, "working")
            )
        }
    }

    /**
     * 운동 유형에 따른 휴식 시간 계산
     */
    private fun calculateRestTime(exercise: Exercise): Int {
        return when (exercise.category) {
            ExerciseCategory.LEGS -> 180  // 하체는 3분
            ExerciseCategory.BACK, ExerciseCategory.CHEST -> 150  // 상체 큰 근육 2분 30초
            ExerciseCategory.SHOULDERS -> 120  // 어깨 2분
            ExerciseCategory.ARMS -> 90  // 팔 1분 30초
            ExerciseCategory.CORE -> 60  // 코어 1분
            else -> 120  // 기본 2분
        }
    }

    /**
     * 패턴 중복 제거 - 같은 패턴의 운동 중 핵심도와 활용도가 높은 운동 하나만 선택
     */
    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { exercisePatternClassifier.classifyExercise(it) }
            .mapNotNull { (_, groupExercises) ->
                groupExercises.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())
            }
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

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
import com.richjun.liftupai.global.time.AppTime
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
    private val exercisePatternClassifier: ExercisePatternClassifier,
    /** 장비·부상(프로필 ∪ 설정)·수준·나이의 단일 출처 — 프로그램 생성도 다른 추천 경로와 같은 안전 규칙을 쓴다 */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val injuryFilterService: InjuryFilterService? = null,
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
        profile.updatedAt = AppTime.utcNow()

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
        // 부상(프로필 ∪ 설정)·보유 장비·수준 규칙을 모든 날의 운동 선택에 건다
        val ctx = trainingContext.resolve(userId, UserTrainingContextResolver.Overrides(
            equipment = request.availableEquipment, experience = request.experienceLevel, sessionMinutes = request.duration,
        ))
        val restricted: (Exercise) -> Boolean = injuryFilterService?.restrictionChecker(ctx.injuries)
            ?: { ex -> ctx.injuries.isNotEmpty() && InjuryFilterService.ruleRestricts(ex, ctx.injuries) }
        val suitable = ctx.suitability()
        val allowed: (Exercise) -> Boolean = { ex -> !restricted(ex) && suitable(ex) }
        val schedule = parseAIResponseToSchedule(aiResponse, request, locale, allowed)

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

    private fun parseAIResponseToSchedule(
        aiResponse: String,
        request: GenerateProgramRequest,
        locale: String = "en",
        allowed: (Exercise) -> Boolean = { true },
    ): ProgramSchedule {
        val experienceLevel = resolveExperienceLevel(request.experienceLevel)
        val workoutGoal = resolveWorkoutGoal(request.goals)
        val availableEquipment = RecoRules.normalizeEquipment(request.availableEquipment)
        val workoutTypes = workoutProgressTracker.getWorkoutTypeSequence(request.workoutSplit)
        val exerciseLimit = when {
            request.duration <= 30 -> 4
            request.duration <= 60 -> 5
            else -> 6
        }

        return ProgramSchedule(
            monday = buildWorkoutDay(1, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale, allowed),
            tuesday = buildWorkoutDay(2, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale, allowed),
            wednesday = null,
            thursday = buildWorkoutDay(3, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale, allowed),
            friday = buildWorkoutDay(4, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale, allowed),
            saturday = buildWorkoutDay(5, request.weeklyWorkoutDays, workoutTypes, availableEquipment, experienceLevel, workoutGoal, exerciseLimit, locale, allowed),
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
        locale: String = "en",
        allowed: (Exercise) -> Boolean = { true },
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
                limit = exerciseLimit,
                allowed = allowed,
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

    private fun buildExercisePlans(
        targetMuscles: List<String>,
        availableEquipment: Set<String>,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal,
        limit: Int,
        allowed: (Exercise) -> Boolean = { true },
    ): List<ExercisePlan> {
        return selectExercisesForTargets(targetMuscles, availableEquipment, limit, allowed).map { exercise ->
            val template = resolveExerciseTemplate(exercise, experienceLevel, workoutGoal)

            ExercisePlan(
                name = exercise.name,
                sets = template?.sets ?: defaultSetCount(experienceLevel),
                reps = template?.let { "${it.minReps}-${it.maxReps}" } ?: defaultRepRange(exercise, experienceLevel),
                rest = template?.restSeconds ?: calculateRestTime(exercise)
            )
        }
    }

    private fun selectExercisesForTargets(
        targetMuscles: List<String>,
        availableEquipment: Set<String>,
        limit: Int,
        allowed: (Exercise) -> Boolean = { true },
    ): List<Exercise> {
        val normalizedTargets = if (targetMuscles.isEmpty()) listOf("full_body") else targetMuscles
        val equipmentFiltered = exerciseRepository.findAll()
            .filter { exercise -> matchesAvailableEquipment(exercise, availableEquipment) && allowed(exercise) }
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
        return TemplatePlanAdapter.equipmentAllowed(exercise, availableEquipment)
    }

    private fun resolveMuscleGroups(targetMuscle: String): Set<MuscleGroup> {
        return WorkoutTargetResolver.muscleGroupsFor(targetMuscle)
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

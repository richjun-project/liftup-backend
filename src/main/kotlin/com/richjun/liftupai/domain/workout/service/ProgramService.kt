package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTranslations
import com.richjun.liftupai.global.exception.BadRequestException
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.i18n.ErrorLocalization
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.richjun.liftupai.global.time.AppTime

@Service
@Transactional(readOnly = true)
class ProgramService(
    private val userRepository: UserRepository,
    private val canonicalProgramRepository: CanonicalProgramRepository,
    private val programDayRepository: ProgramDayRepository,
    private val programDayExerciseRepository: ProgramDayExerciseRepository,
    private val userProgramEnrollmentRepository: UserProgramEnrollmentRepository,
    private val exerciseSubstitutionRepository: ExerciseSubstitutionRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val exerciseRepository: ExerciseRepository,
    private val canonicalProgramService: CanonicalProgramService,
    private val enrollmentService: ProgramEnrollmentService,
    private val workoutGeneratorService: ProgramWorkoutGeneratorService,
    private val absenceDetectionService: AbsenceDetectionService,
    private val userSettingsRepository: UserSettingsRepository,
    private val localizationService: ExerciseCatalogLocalizationService,
    private val exerciseSubstitutionService: ExerciseSubstitutionService
) {

    @org.springframework.beans.factory.annotation.Value("\${app.exercise-media.base-url:https://liftup-cdn.com}")
    private var exerciseMediaBaseUrl: String = "https://liftup-cdn.com"

    private fun thumbnailUrl(exercise: Exercise): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/thumb.jpg"
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    fun getAllPrograms(localeOverride: String? = null): ProgramListResponse {
        val locale = localeOverride?.let { WorkoutLocalization.normalizeLocale(it) } ?: "en"
        val programs = canonicalProgramRepository.findByIsActiveTrue()
        return ProgramListResponse(programs.map { it.toSummary(locale) })
    }

    fun getProgramDetail(code: String, localeOverride: String? = null): ProgramDetailResponse {
        val locale = localeOverride?.let { WorkoutLocalization.normalizeLocale(it) } ?: "en"
        val program = canonicalProgramRepository.findByCode(code)
            ?: throw ResourceNotFoundException("Program not found: $code")
        val days = programDayRepository.findByProgramIdOrderByDayNumber(program.id)
        val dayDetails = days.map { day ->
            val exercises = programDayExerciseRepository.findByDayIdWithExercises(day.id)
            ProgramDayDetail(
                dayNumber = day.dayNumber,
                name = day.name,
                workoutType = day.workoutType.name,
                workoutTypeName = WorkoutLocalization.workoutTypeName(day.workoutType, locale),
                estimatedDuration = day.estimatedDurationMinutes,
                exercises = exercises.map { it.toDetail() }
            )
        }
        return program.toDetailResponse(dayDetails, locale)
    }

    fun getRecommendedProgram(userId: Long, localeOverride: String? = null): ProgramDetailResponse {
        val locale = resolveLocale(userId, localeOverride)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val program = canonicalProgramService.getRecommendedProgram(user)
        val days = programDayRepository.findByProgramIdOrderByDayNumber(program.id)
        val dayDetails = days.map { day ->
            val exercises = programDayExerciseRepository.findByDayIdWithExercises(day.id)
            ProgramDayDetail(
                dayNumber = day.dayNumber,
                name = day.name,
                workoutType = day.workoutType.name,
                workoutTypeName = WorkoutLocalization.workoutTypeName(day.workoutType, locale),
                estimatedDuration = day.estimatedDurationMinutes,
                exercises = exercises.map { it.toDetail() }
            )
        }
        return program.toDetailResponse(dayDetails, locale)
    }

    // ── Enrollment ───────────────────────────────────────────────────────────

    @Transactional
    fun enrollInProgram(userId: Long, request: EnrollRequest): EnrollmentStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val enrollment = enrollmentService.enrollUser(user, request.programCode)
        return enrollment.toStatusResponse()
    }

    fun getCurrentEnrollment(userId: Long): EnrollmentStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val enrollment = userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.ACTIVE
        ) ?: userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.PAUSED
        ) ?: throw ResourceNotFoundException("No active enrollment found")
        return enrollment.toStatusResponse()
    }

    @Transactional
    fun updateEnrollmentStatus(userId: Long, status: String): EnrollmentStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        when (status.lowercase()) {
            "pause" -> enrollmentService.pauseEnrollment(user)
            "resume" -> enrollmentService.resumeEnrollment(user)
            else -> throw BadRequestException("Invalid status. Use 'pause' or 'resume'")
        }
        val enrollment = userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.ACTIVE
        ) ?: userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.PAUSED
        ) ?: throw ResourceNotFoundException("No enrollment found")
        return enrollment.toStatusResponse()
    }

    @Transactional
    fun abandonEnrollment(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        enrollmentService.abandonEnrollment(user)
    }

    // ── Today's Workout ──────────────────────────────────────────────────────

    fun getTodayWorkout(userId: Long, subjectiveReadiness: Int? = null, localeOverride: String? = null): TodayWorkoutResponse {
        val locale = resolveLocale(userId, localeOverride)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val enrollment = enrollmentService.getCurrentEnrollment(user)
            ?: throw ResourceNotFoundException("No active program enrollment")

        val absenceStatus = absenceDetectionService.checkAbsence(enrollment, locale)
        if (absenceStatus.shouldPause) {
            enrollmentService.pauseEnrollment(user)
            throw ResourceNotFoundException(ErrorLocalization.message("absence.paused", locale))
        }

        var workout = workoutGeneratorService.generateTodayWorkout(user, subjectiveReadiness)
        if (absenceStatus.needsWeightReduction) {
            workout = workout.copy(
                exercises = workout.exercises.map { ex ->
                    ex.copy(suggestedWeight = ex.suggestedWeight?.let { it * (1 - absenceStatus.weightReductionPercent) })
                }
            )
        }

        val todayExercises = workout.exercises.map { ex ->
            TodayExerciseResponse(
                exerciseId = ex.exerciseId,
                name = ex.name,
                category = ex.category,
                equipment = ex.equipment,
                sets = ex.sets,
                minReps = ex.minReps,
                maxReps = ex.maxReps,
                restSeconds = ex.restSeconds,
                suggestedWeight = ex.suggestedWeight,
                targetRPE = ex.targetRPE,
                isCompound = ex.isCompound,
                warmupSets = ex.warmupSets.map { WarmupSetResponse(it.weight, it.reps) },
                substitutes = ex.substitutes.map { SubstituteResponse(
                    exerciseId = it.exerciseId,
                    name = it.name,
                    reason = it.reason,
                    category = it.category,
                    equipment = it.equipment
                ) }
            )
        }

        val programSplitName = WorkoutLocalization.splitName(
            enrollment.program.splitType.name, locale
        )

        return TodayWorkoutResponse(
            programName = programSplitName,
            weekNumber = workout.weekNumber,
            dayNumber = workout.dayNumber,
            dayName = workout.dayName,
            isDeloadWeek = workout.isDeloadWeek,
            periodizationPhase = workout.periodizationPhase,
            workoutType = workout.workoutType.name,
            estimatedDuration = workout.estimatedDuration,
            exercises = todayExercises,
            graduationStatus = workout.graduationStatus,
            weeklyVolume = workout.weeklyVolume.map {
                WeeklyVolumeStatusDto(
                    muscleGroup = it.muscleGroup,
                    currentSets = it.currentSets,
                    mevSets = it.mevSets,
                    mavSets = it.mavSets,
                    status = it.status
                )
            },
            readinessScore = workout.readinessScore?.let {
                ReadinessScoreDto(
                    score = it.score,
                    factors = it.factors,
                    intensityMultiplier = it.intensityMultiplier
                )
            }
        )
    }

    // ── Weekly Schedule ──────────────────────────────────────────────────────

    fun getWeeklySchedule(userId: Long, localeOverride: String? = null): WeeklyScheduleResponse {
        val locale = resolveLocale(userId, localeOverride)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val enrollment = userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.ACTIVE
        ) ?: throw ResourceNotFoundException("No active program enrollment found")

        val program = enrollment.program
        val days = programDayRepository.findByProgramIdOrderByDayNumber(program.id)
        val daysPerWeek = program.daysPerWeek.coerceAtLeast(1)
        val weekNumber = enrollment.totalCompletedWorkouts / daysPerWeek + 1
        val currentDayIndex = enrollment.totalCompletedWorkouts % daysPerWeek

        val isDeloadWeek = program.deloadEveryNWeeks > 0 &&
            enrollment.totalCompletedWorkouts > 0 &&
            weekNumber % program.deloadEveryNWeeks == 0

        val scheduleDays = days.mapIndexed { index, day ->
            ScheduleDayResponse(
                dayNumber = day.dayNumber,
                name = day.name,
                workoutType = day.workoutType.name,
                isCompleted = index < currentDayIndex,
                isToday = index == currentDayIndex
            )
        }

        return WeeklyScheduleResponse(
            programName = WorkoutLocalization.splitName(program.splitType.name, locale),
            currentWeek = weekNumber,
            isDeloadWeek = isDeloadWeek,
            days = scheduleDays
        )
    }

    // ── Substitutes ──────────────────────────────────────────────────────────

    fun getExerciseSubstitutes(exerciseId: Long, locale: String? = null): SubstituteListResponse {
        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found: $exerciseId") }

        val normalizedLocale = localizationService.normalizeLocale(locale)

        // First try the static substitution table
        val staticRaw = exerciseSubstitutionRepository
            .findByOriginalExerciseIdOrderByPriority(exerciseId)

        // Collect all exercises (original + substitutes) for batch translation
        val allExercises = staticRaw.map { it.substituteExercise } + listOf(exercise)

        val originalMuscles = exercise.muscleGroups.toSet()
        val originalEquipment = exercise.equipment

        val dynamicRaw = if (staticRaw.isEmpty() && originalMuscles.isNotEmpty()) {
            exerciseRepository.findAlternativeExercises(
                exerciseId, exercise.category, originalMuscles.toList()
            ).take(30) // 넉넉히 가져와서 스코어링 후 상위 10개 선택
        } else emptyList()

        // 스코어링: 근육 겹침도(0~1) * 60 + 장비 일치(0|1) * 30 + 인기도(0~1) * 10
        val scoredDynamic = dynamicRaw.map { alt ->
            val altMuscles = alt.muscleGroups.toSet()
            val overlap = (originalMuscles intersect altMuscles).size
            val union = (originalMuscles union altMuscles).size
            val muscleScore = if (union > 0) overlap.toDouble() / union else 0.0
            val equipMatch = if (alt.equipment == originalEquipment) 1.0 else 0.0
            val popScore = alt.popularity.coerceIn(0, 100) / 100.0
            val score = muscleScore * 60 + equipMatch * 30 + popScore * 10
            alt to score
        }.sortedByDescending { it.second }.take(10)

        val allForTranslation = staticRaw.map { it.substituteExercise } + listOf(exercise) + scoredDynamic.map { it.first }
        val translations = localizationService.translationMap(allForTranslation, normalizedLocale)

        // static substitutes: EQUIVALENT 우선 정렬
        val reasonOrder = mapOf("EQUIVALENT" to 0, "INJURY" to 1, "PREFERENCE" to 2, "EQUIPMENT" to 3)
        val staticSubstitutes = staticRaw.map {
            SubstituteResponse(
                exerciseId = it.substituteExercise.id,
                name = localizationService.displayName(it.substituteExercise, normalizedLocale, translations),
                reason = it.reason.name,
                category = it.substituteExercise.category.name,
                equipment = it.substituteExercise.equipment?.name,
                muscleGroups = it.substituteExercise.muscleGroups.map { mg -> mg.name },
                imageUrl = thumbnailUrl(it.substituteExercise)
            )
        }.sortedBy { reasonOrder[it.reason] ?: 99 }

        val substitutes = if (staticSubstitutes.isNotEmpty()) {
            staticSubstitutes
        } else {
            scoredDynamic.map { (alt, _) ->
                val altMuscles = alt.muscleGroups.toSet()
                val overlap = (originalMuscles intersect altMuscles).size
                val union = (originalMuscles union altMuscles).size
                val similarity = if (union > 0) overlap.toDouble() / union else 0.0
                val reason = when {
                    similarity >= 0.5 && alt.equipment == originalEquipment -> "EQUIVALENT"
                    similarity >= 0.5 -> "EQUIPMENT"
                    alt.equipment == originalEquipment -> "PREFERENCE"
                    else -> "EQUIPMENT"
                }
                SubstituteResponse(
                    exerciseId = alt.id,
                    name = localizationService.displayName(alt, normalizedLocale, translations),
                    reason = reason,
                    category = alt.category.name,
                    equipment = alt.equipment?.name,
                    muscleGroups = alt.muscleGroups.map { mg -> mg.name },
                    imageUrl = thumbnailUrl(alt)
                )
            }
        }

        return SubstituteListResponse(
            exerciseId = exerciseId,
            exerciseName = localizationService.displayName(exercise, normalizedLocale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { it.name },
            substitutes = substitutes
        )
    }

    // ── Exercise Search & List ───────────────────────────────────────────────

    fun searchExercises(query: String, category: String?, page: Int, size: Int, locale: String? = null): ExerciseSearchResponse {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return listExercises(category, page, size, locale)
        }
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 50)
        val pageable = PageRequest.of(safePage, safeSize)
        val cat = category?.let { parseCategory(it) }
        val result = if (cat != null) {
            exerciseRepository.searchByCategory(cat, trimmed, pageable)
        } else {
            exerciseRepository.searchPaged(trimmed, pageable)
        }
        val normalizedLocale = localizationService.normalizeLocale(locale)
        val translations = localizationService.translationMap(result.content, normalizedLocale)
        return ExerciseSearchResponse(
            exercises = result.content.map { it.toSearchItem(normalizedLocale, translations) },
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            currentPage = safePage
        )
    }

    fun listExercises(
        category: String?,
        page: Int,
        size: Int,
        locale: String? = null,
        referenceExerciseId: Long? = null
    ): ExerciseSearchResponse {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 50)
        val cat = category?.let { parseCategory(it) }
        val reference = referenceExerciseId?.let { exerciseRepository.findById(it).orElse(null) }

        // 기준 운동이 있으면: 유사도(transfer score) 내림차순으로 정렬하고 메모리에서 페이지네이션한다.
        // 카테고리 필터는 명시적으로 전달된 경우에만 적용한다 (자동 narrowing 없음).
        // ESSENTIAL/STANDARD만 노출 (ADVANCED/SPECIALIZED는 검색 시에만).
        if (reference != null) {
            val all = (if (cat != null)
                exerciseRepository.findAllListableByCategory(cat)
            else
                exerciseRepository.findAllListable())
                .filter { it.id != reference.id }
            val sorted = all.sortedByDescending {
                exerciseSubstitutionService.calculateTransferScore(reference, it)
            }
            val totalElements = sorted.size.toLong()
            val totalPages = if (sorted.isEmpty()) 0 else ((sorted.size + safeSize - 1) / safeSize)
            val from = (safePage * safeSize).coerceAtMost(sorted.size)
            val to = (from + safeSize).coerceAtMost(sorted.size)
            val pageContent = sorted.subList(from, to)
            val normalizedLocale = localizationService.normalizeLocale(locale)
            val translations = localizationService.translationMap(pageContent, normalizedLocale)
            return ExerciseSearchResponse(
                exercises = pageContent.map { it.toSearchItem(normalizedLocale, translations) },
                totalElements = totalElements,
                totalPages = totalPages,
                currentPage = safePage
            )
        }

        val pageable = PageRequest.of(safePage, safeSize)
        val result = if (cat != null) {
            exerciseRepository.findListableByCategory(cat, pageable)
        } else {
            exerciseRepository.findListable(pageable)
        }
        val normalizedLocale = localizationService.normalizeLocale(locale)
        val translations = localizationService.translationMap(result.content, normalizedLocale)
        return ExerciseSearchResponse(
            exercises = result.content.map { it.toSearchItem(normalizedLocale, translations) },
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            currentPage = safePage
        )
    }

    private fun parseCategory(raw: String): ExerciseCategory {
        return try {
            ExerciseCategory.valueOf(raw.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid category: $raw")
        }
    }

    @Transactional
    fun overrideExercise(userId: Long, request: ExerciseOverrideRequest) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val enrollment = userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(
            user, EnrollmentStatus.ACTIVE
        ) ?: throw ResourceNotFoundException("No active program enrollment found")

        if (request.originalExerciseId == request.substituteExerciseId) {
            throw BadRequestException("Cannot substitute an exercise with itself")
        }

        val originalExercise = exerciseRepository.findById(request.originalExerciseId)
            .orElseThrow { BadRequestException("Original exercise not found: ${request.originalExerciseId}") }
        val substituteExercise = exerciseRepository.findById(request.substituteExerciseId)
            .orElseThrow { BadRequestException("Substitute exercise not found: ${request.substituteExerciseId}") }

        val reason = try {
            SubstitutionReason.valueOf(request.reason.uppercase())
        } catch (e: IllegalArgumentException) {
            SubstitutionReason.PREFERENCE
        }

        val existing = userExerciseOverrideRepository
            .findByEnrollmentIdAndOriginalExerciseId(enrollment.id, request.originalExerciseId)

        if (existing != null) {
            // Replace: delete and re-insert (entity is immutable)
            userExerciseOverrideRepository.delete(existing)
        }

        userExerciseOverrideRepository.save(
            UserExerciseOverride(
                enrollment = enrollment,
                originalExercise = originalExercise,
                substituteExercise = substituteExercise,
                reason = reason
            )
        )
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun CanonicalProgram.toSummary(locale: String = "en") = ProgramSummary(
        code = code,
        name = WorkoutTranslations.createProgramName(daysPerWeek, splitType.name, targetExperienceLevel.name, locale),
        splitType = splitType.name,
        splitTypeName = WorkoutLocalization.splitName(splitType.name, locale),
        experienceLevel = targetExperienceLevel.name,
        experienceLevelName = WorkoutLocalization.difficultyDisplayName(targetExperienceLevel.name, locale),
        goal = targetGoal.name,
        daysPerWeek = daysPerWeek,
        durationWeeks = programDurationWeeks,
        progressionModel = progressionModel.name,
        description = description
    )

    private fun CanonicalProgram.toDetailResponse(days: List<ProgramDayDetail>, locale: String = "en") = ProgramDetailResponse(
        code = code,
        name = WorkoutTranslations.createProgramName(daysPerWeek, splitType.name, targetExperienceLevel.name, locale),
        splitType = splitType.name,
        splitTypeName = WorkoutLocalization.splitName(splitType.name, locale),
        experienceLevel = targetExperienceLevel.name,
        experienceLevelName = WorkoutLocalization.difficultyDisplayName(targetExperienceLevel.name, locale),
        goal = targetGoal.name,
        daysPerWeek = daysPerWeek,
        durationWeeks = programDurationWeeks,
        progressionModel = progressionModel.name,
        deloadEveryNWeeks = deloadEveryNWeeks,
        description = description,
        days = days
    )

    private fun resolveLocale(userId: Long, localeOverride: String? = null): String {
        if (!localeOverride.isNullOrBlank()) {
            return WorkoutLocalization.normalizeLocale(localeOverride)
        }
        return WorkoutLocalization.normalizeLocale(
            userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        )
    }

    private fun ProgramDayExercise.toDetail() = ProgramExerciseDetail(
        exerciseId = exercise.id,
        name = exercise.name,
        category = exercise.category.name,
        equipment = exercise.equipment?.name,
        order = orderInDay,
        isCompound = isCompound,
        sets = sets,
        minReps = minReps,
        maxReps = maxReps,
        restSeconds = restSeconds,
        targetRPE = targetRPE,
        isOptional = isOptional,
        notes = notes
    )

    private fun Exercise.toSearchItem(
        locale: String = "en",
        translations: Map<Long, com.richjun.liftupai.domain.workout.entity.ExerciseTranslation> = emptyMap()
    ) = ExerciseSearchItem(
        exerciseId = id,
        name = localizationService.displayName(this, locale, translations),
        category = category.name,
        equipment = equipment?.name,
        muscleGroups = muscleGroups.map { it.name },
        imageUrl = thumbnailUrl(this),
        difficulty = difficulty,
        popularity = popularity
    )

    private fun UserProgramEnrollment.toStatusResponse(locale: String = "en"): EnrollmentStatusResponse {
        val daysPerWeek = program.daysPerWeek.coerceAtLeast(1)
        val currentDayInCycle = totalCompletedWorkouts % daysPerWeek + 1
        val currentWeek = totalCompletedWorkouts / daysPerWeek + 1
        val isDeloadWeek = program.deloadEveryNWeeks > 0 &&
            totalCompletedWorkouts > 0 &&
            currentWeek % program.deloadEveryNWeeks == 0
        return EnrollmentStatusResponse(
            programCode = program.code,
            programName = WorkoutLocalization.splitName(program.splitType.name, locale),
            currentWeek = currentWeek,
            currentDay = currentDayInCycle,
            totalCompletedWorkouts = totalCompletedWorkouts,
            isDeloadWeek = isDeloadWeek,
            status = status.name,
            startDate = AppTime.formatUtcRequired(startDate),
            lastActiveDate = AppTime.formatUtc(lastActiveDate)
        )
    }
}

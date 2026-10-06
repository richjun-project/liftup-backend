package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.CreateCustomPlanRequest
import com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest
import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.exception.BadRequestException
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class UserPlanService(
    private val userWorkoutPlanRepository: UserWorkoutPlanRepository,
    private val userPlanDayRepository: UserPlanDayRepository,
    private val userPlanDayExerciseRepository: UserPlanDayExerciseRepository,
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository,
    private val injuryFilterService: InjuryFilterService,
    private val userSettingsRepository: com.richjun.liftupai.domain.user.repository.UserSettingsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val weightRecommendationService: WeightRecommendationService,
    private val progressiveOverloadService: ProgramProgressiveOverloadService,
    private val planWorkoutEnricher: PlanWorkoutEnricher,
    private val userProfileRepository: com.richjun.liftupai.domain.user.repository.UserProfileRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository
) {
    private val log = LoggerFactory.getLogger(UserPlanService::class.java)

    companion object {
        /** 템플릿 하루 운동 수 목표. 부상으로 빠진 만큼 여기까지 채운다. */
        const val TARGET_EXERCISES_PER_DAY = TemplatePlanAdapter.TARGET_EXERCISES_PER_DAY
    }

    @org.springframework.beans.factory.annotation.Value("\${app.exercise-media.base-url:https://liftup-cdn.com}")
    private var exerciseMediaBaseUrl: String = "https://liftup-cdn.com"

    private fun thumbnailUrl(exercise: Exercise): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/thumb.jpg"
    }

    private fun videoUrl(exercise: Exercise): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/video.mp4"
    }

    fun getActivePlan(userId: Long): UserWorkoutPlan? {
        return userWorkoutPlanRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, PlanStatus.ACTIVE)
    }

    // Preset templates have no per-locale name column (see TemplateService for the same lookup against
    // workout_messages*.properties). A plan created from a preset template records the template code as
    // sourceId, so we can re-resolve the locale-correct name/dayName at read time regardless of which
    // locale was active when the plan was first created/persisted. AI-generated and custom plans have no
    // bundle entry for their sourceId and simply fall back to the stored (creation-time) value.
    private fun translatedPlanName(plan: UserWorkoutPlan, locale: String): String {
        if (plan.sourceType != PlanSourceType.TEMPLATE) return plan.planName
        return WorkoutLocalization.maybeMessage("template.${plan.sourceId}.name", locale) ?: plan.planName
    }

    private fun translatedPlanDescription(plan: UserWorkoutPlan, locale: String): String? {
        if (plan.sourceType != PlanSourceType.TEMPLATE) return plan.planDescription
        return WorkoutLocalization.maybeMessage("template.${plan.sourceId}.description", locale) ?: plan.planDescription
    }

    private fun translatedPlanDayName(plan: UserWorkoutPlan, dayNumber: Int, fallback: String, locale: String): String {
        if (plan.sourceType != PlanSourceType.TEMPLATE) return fallback
        return WorkoutLocalization.maybeMessage("template.${plan.sourceId}.day.$dayNumber.name", locale) ?: fallback
    }

    /**
     * 부상 부위를 피하는 대체 운동. 실제 규칙은 TemplatePlanAdapter 에 있고(추천기와 공유), 여기는 기존 호출부용 얇은 위임이다.
     */
    internal fun pickRefillExercises(
        workoutType: String,
        injuries: Set<String>,
        exclude: Set<Long>,
        needed: Int,
        allowedEquipment: Set<String> = emptySet(),
        maxDifficulty: Int? = null,
    ): List<Exercise> = TemplatePlanAdapter(
        poolByCategory = { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(it) },
        isRestricted = { ex -> injuries.isNotEmpty() && injuryFilterService.isExerciseRestricted(ex.id, injuries) },
    ).pickRefill(workoutType, injuries, exclude, needed, allowedEquipment, maxDifficulty)

    /** 보유 장비로 할 수 있는 종목인가. 장비 미지정·맨몸·기타는 항상 허용. */
    internal fun equipmentAllowed(ex: Exercise, allowedEquipment: Set<String>): Boolean =
        TemplatePlanAdapter.equipmentAllowed(ex, allowedEquipment)

    /** 요청 장비 → 설정 장비 순. 비어 있으면 제한 없음. 표기는 Equipment enum 이름(BARBELL 등)으로 정규화. */
    internal fun resolveAllowedEquipment(user: User, request: ApplyTemplateRequest?): Set<String> {
        val fromRequest = request?.equipment?.filter { it.isNotBlank() }.orEmpty()
        // 기기 가입(AuthService)은 프로필에만 장비를 쓰고, 온보딩 완료(UserService)는 설정에도 쓴다 — 둘 다 본다.
        val raw = if (fromRequest.isNotEmpty()) fromRequest else
            userSettingsRepository.findByUser_Id(user.id).orElse(null)?.availableEquipment?.takeIf { it.isNotEmpty() }?.toList()
                ?: userProfileRepository.findByUser_Id(user.id).orElse(null)?.availableEquipment?.toList().orEmpty()
        return raw.map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }.filter { it.isNotBlank() }.toSet()
    }

    fun getPlanDashboard(userId: Long, locale: String = "en"): PlanDashboardResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val days = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val dayExerciseCounts = days.associate { day ->
            day.id to userPlanDayExerciseRepository.findByPlanDayIdOrderByOrderInDay(day.id).size
        }

        return PlanDashboardResponse(
            planId = plan.id,
            planName = translatedPlanName(plan, locale),
            planDescription = translatedPlanDescription(plan, locale),
            splitType = plan.splitType.name,
            sourceType = plan.sourceType.name,
            totalDays = plan.totalDays,
            currentDay = plan.currentDay,
            progressionModel = plan.progressionModel.name,
            deloadEveryNWeeks = plan.deloadEveryNWeeks,
            days = days.map { day ->
                PlanDayOverview(
                    dayNumber = day.dayNumber,
                    dayName = translatedPlanDayName(plan, day.dayNumber, day.dayName, locale),
                    workoutType = day.workoutType.name,
                    estimatedDuration = day.estimatedDurationMinutes,
                    exerciseCount = dayExerciseCounts[day.id] ?: 0,
                    totalCompletions = day.totalCompletions,
                    lastCompletedAt = day.lastCompletedAt?.toString(),
                    isCurrent = day.dayNumber == plan.currentDay
                )
            },
            totalWorkoutsCompleted = days.sumOf { it.totalCompletions },
            aiCoachingNotes = plan.aiCoachingNotes,
            createdAt = plan.createdAt.toString()
        )
    }

    fun getDayWorkout(userId: Long, dayNumber: Int, subjectiveReadiness: Int? = null, locale: String = "ko"): DayWorkoutResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val day = userPlanDayRepository.findByPlanIdAndDayNumber(plan.id, dayNumber)
            ?: throw ResourceNotFoundException("Day $dayNumber not found in plan")

        val exercises = userPlanDayExerciseRepository.findByPlanDayIdWithExerciseOrderByOrderInDay(day.id)

        // 한글 이름 일괄 조회
        val exerciseIds = exercises.map { it.exercise.id }
        val translatedNames = if (locale != "en") {
            exerciseTranslationRepository.findByExerciseIdInAndLocale(exerciseIds, locale)
                .associateBy { it.exercise.id }
        } else emptyMap()

        // Calculate week number and deload status for progressive overload
        val allDays = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val totalCompletions = allDays.sumOf { it.totalCompletions }
        val weekNumber = (totalCompletions / plan.totalDays.coerceAtLeast(1)) + 1
        val isDeloadWeek = plan.deloadEveryNWeeks > 0 &&
            weekNumber > 1 &&
            (weekNumber % plan.deloadEveryNWeeks == 0)

        val baseResponse = DayWorkoutResponse(
            dayNumber = day.dayNumber,
            dayName = translatedPlanDayName(plan, day.dayNumber, day.dayName, locale),
            workoutType = day.workoutType.name,
            estimatedDuration = day.estimatedDurationMinutes,
            completionCount = day.totalCompletions,
            exercises = exercises.map { ex ->
                val targetReps = (ex.minReps + ex.maxReps) / 2
                // Progressive overload: use history-based calculation, fall back to static recommendation
                val suggestedWeight = progressiveOverloadService.calculateWeightForPlan(
                    user = plan.user,
                    exercise = ex.exercise,
                    progressionModel = plan.progressionModel,
                    week = weekNumber,
                    dayInCycle = dayNumber,
                    isDeloadWeek = isDeloadWeek,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps
                ) ?: weightRecommendationService.calculateSuggestedWeight(
                    userId, ex.exercise.id, targetReps
                )
                val warmupSets = if (ex.isCompound && suggestedWeight > 20.0) {
                    generateWarmupSets(suggestedWeight)
                } else {
                    emptyList()
                }
                DayExerciseDetail(
                    exerciseId = ex.exercise.id,
                    exerciseName = translatedNames[ex.exercise.id]?.displayName ?: ex.exercise.name,
                    category = ex.exercise.category.name,
                    equipment = ex.exercise.equipment?.name,
                    imageUrl = thumbnailUrl(ex.exercise),
                    videoUrl = videoUrl(ex.exercise),
                    sets = ex.sets,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps,
                    restSeconds = ex.restSeconds,
                    isCompound = ex.isCompound,
                    targetRPE = ex.targetRPE,
                    suggestedWeight = suggestedWeight,
                    warmupSets = warmupSets,
                    notes = ex.notes,
                    targetMuscles = ex.exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) }
                )
            }
        )

        // Enrich with advanced features
        val userAge = userProfileRepository.findByUser_Id(userId).orElse(null)?.age
        return planWorkoutEnricher.enrich(baseResponse, plan, userAge, subjectiveReadiness)
    }

    @Transactional
    fun applyTemplate(user: User, templateCode: String, request: ApplyTemplateRequest?, locale: String = "en"): PlanDashboardResponse {
        val template = templateRepository.findByCode(templateCode)
            ?: throw ResourceNotFoundException(WorkoutLocalization.message("error.template_not_found", locale, templateCode))

        // 부상 정보는 요청값을 우선하되, 없으면 온보딩에서 저장된 설정값을 쓴다.
        // (앱이 요청에 injuries 를 안 실어 보내는 경로가 있어 설정값 폴백이 필요하다)
        val injuries = resolveInjuries(user, request)
        val allowedEquipment = resolveAllowedEquipment(user, request)
        val focus = resolveFocusAreas(user, request)
        val maxDifficulty = TemplatePlanAdapter.maxDifficultyFor(resolveExperience(user, request))

        // Abandon existing active plan
        abandonActivePlan(user.id)

        // Create new plan from template
        val plan = UserWorkoutPlan(
            user = user,
            sourceType = if (template.sourceType == PlanSourceType.AI_GENERATED) PlanSourceType.AI_GENERATED else PlanSourceType.TEMPLATE,
            sourceId = template.code,
            planName = WorkoutLocalization.maybeMessage("template.${template.code}.name", locale) ?: template.name,
            planDescription = WorkoutLocalization.maybeMessage("template.${template.code}.description", locale) ?: template.description,
            splitType = template.splitType,
            totalDays = template.totalDays,
            currentDay = 1,
            status = PlanStatus.ACTIVE,
            aiCoachingNotes = template.aiCoachingNotes
        )
        val savedPlan = userWorkoutPlanRepository.save(plan)

        // 템플릿을 이 사람에게 맞게 고친다(부상·장비 필터 → 빈 날 채우기 → 집중 부위 볼륨). 추천기와 같은 순수 규칙.
        val templateDays = templateDayRepository.findByTemplateIdOrderByDayNumber(template.id)
        val sourceDays = templateDays.map { td ->
            TemplatePlanAdapter.SourceDay(
                dayNumber = td.dayNumber,
                workoutType = td.workoutType.name,
                exercises = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(td.id).map { te ->
                    TemplatePlanAdapter.SourceExercise(
                        exercise = te.exercise, sets = te.sets, minReps = te.minReps, maxReps = te.maxReps,
                        restSeconds = te.restSeconds, isCompound = te.isCompound, targetRPE = te.targetRPE, notes = te.notes
                    )
                }
            )
        }
        val adapter = TemplatePlanAdapter(
            poolByCategory = planPool(),
            isRestricted = injuryFilterService.restrictionChecker(injuries),
        )
        val adaptedDays = adapter.adapt(
            sourceDays,
            TemplatePlanAdapter.Context(allowedEquipment, injuries, focus, maxDifficulty)
        ).associateBy { it.dayNumber }

        var skippedForInjury = 0
        var skippedForEquipment = 0
        for (templateDay in templateDays) {
            val adapted = adaptedDays.getValue(templateDay.dayNumber)
            skippedForInjury += adapted.skippedForInjury
            skippedForEquipment += adapted.skippedForEquipment
            val savedDay = userPlanDayRepository.save(
                UserPlanDay(
                    plan = savedPlan,
                    dayNumber = templateDay.dayNumber,
                    dayName = WorkoutLocalization.maybeMessage("template.${template.code}.day.${templateDay.dayNumber}.name", locale) ?: templateDay.dayName,
                    workoutType = templateDay.workoutType,
                    estimatedDurationMinutes = templateDay.estimatedDurationMinutes
                )
            )
            adapted.exercises.forEachIndexed { i, planned ->
                val src = planned.source
                userPlanDayExerciseRepository.save(
                    UserPlanDayExercise(
                        planDay = savedDay,
                        exercise = src.exercise,
                        orderInDay = i + 1,
                        sets = src.sets,
                        minReps = src.minReps,
                        maxReps = src.maxReps,
                        restSeconds = src.restSeconds,
                        isCompound = src.isCompound,
                        targetRPE = src.targetRPE,
                        notes = src.notes
                    )
                )
            }
            val added = adapted.exercises.count { it.origin != TemplatePlanAdapter.Origin.TEMPLATE }
            if (added > 0) {
                log.info("Adapted day {} of plan {}: {} exercises added/replaced (refill+focus)", templateDay.dayNumber, savedPlan.id, added)
            }
        }

        log.info(
            "User {} applied template {} as new plan {} (injuries={}, focus={}, exercisesSkipped={}, skippedForEquipment={})",
            user.id, templateCode, savedPlan.id, injuries, focus, skippedForInjury, skippedForEquipment
        )
        return getPlanDashboard(user.id)
    }

    /**
     * 부상 목록 해석. 요청에 담겨 오면 그것을, 없으면 온보딩에서 저장된 UserSettings 값을 쓴다.
     * 저장 형식이 "knee:severe" 처럼 심각도가 붙어 오는 경우가 있어 부위만 남긴다.
     */
    internal fun resolveInjuries(user: User, request: ApplyTemplateRequest?): Set<String> {
        val fromRequest = request?.injuries?.filter { it.isNotBlank() }.orEmpty()
        val raw = if (fromRequest.isNotEmpty()) {
            fromRequest
        } else {
            // 부상이 두 테이블에 나뉘어 저장된다 — 하나만 보면 필터가 조용히 무력화된다.
            //   기기 가입(AuthService)      → user_profile_injuries
            //   온보딩 설정(UserService)    → user_settings_injuries
            // 실제로 한쪽만 읽었다가 무릎 부상 사용자의 플랜에 하체 운동이 그대로 들어갔다.
            val fromProfile = userProfileRepository.findByUser_Id(user.id)
                .orElse(null)?.injuries?.toList().orEmpty()
            val fromSettings = userSettingsRepository.findByUser_Id(user.id)
                .orElse(null)?.injuries?.toList().orEmpty()
            fromProfile + fromSettings
        }
        return raw.map { it.substringBefore(':').trim().lowercase() }
            .filter { it.isNotEmpty() && it != "none" }
            .toSet()
    }

    /** 플랜 후보 풀(카테고리별 인기순). 한 번의 적용 안에서 같은 카테고리를 여러 번 읽지 않게 캐시한다. */
    private fun planPool(): (ExerciseCategory) -> List<Exercise> {
        val cache = mutableMapOf<ExerciseCategory, List<Exercise>>()
        return { category -> cache.getOrPut(category) { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(category) } }
    }

    /** 경험 수준: 요청값 → 프로필. 채워 넣는 운동의 난이도 상한에 쓴다. */
    internal fun resolveExperience(user: User, request: ApplyTemplateRequest?): String? =
        request?.experienceLevel?.takeIf { it.isNotBlank() }
            ?: userProfileRepository.findByUser_Id(user.id).orElse(null)?.experienceLevel?.name

    /**
     * 집중 부위: 요청(goals·focus_areas) → 온보딩에서 저장한 세부 목표(UserSettings.focusGoals).
     * FitnessGoal 은 6종뿐이라 thick_legs·glutes 같은 세부 목표는 별도로 저장해 둔 값을 읽는다.
     */
    internal fun resolveFocusAreas(user: User, request: ApplyTemplateRequest?): List<FocusArea> {
        val fromRequest = FocusArea.resolve(request?.goals, request?.focusAreas)
        if (fromRequest.isNotEmpty()) return fromRequest
        val stored = userSettingsRepository.findByUser_Id(user.id).orElse(null)?.focusGoals?.toList().orEmpty()
        return FocusArea.resolve(stored)
    }

    @Transactional
    fun createCustomPlan(user: User, request: CreateCustomPlanRequest): PlanDashboardResponse {
        if (request.days.isEmpty()) throw BadRequestException("At least one day is required")
        if (request.planName.isBlank()) throw BadRequestException("Plan name is required")

        // Abandon existing active plan
        abandonActivePlan(user.id)

        // Determine split type from workout types
        val workoutTypes = request.days.map { it.workoutType.uppercase() }.toSet()
        val splitType = when {
            workoutTypes.all { it == "FULL_BODY" } -> SplitType.FULL_BODY
            workoutTypes.containsAll(setOf("PUSH", "PULL", "LEGS")) -> SplitType.PPL
            workoutTypes.containsAll(setOf("UPPER", "LOWER")) -> SplitType.UPPER_LOWER
            else -> SplitType.FULL_BODY
        }

        val plan = UserWorkoutPlan(
            user = user,
            sourceType = PlanSourceType.CUSTOM,
            sourceId = null,
            planName = request.planName,
            planDescription = null,
            splitType = splitType,
            totalDays = request.days.size,
            currentDay = 1,
            status = PlanStatus.ACTIVE
        )
        val savedPlan = userWorkoutPlanRepository.save(plan)

        for ((dayIndex, dayReq) in request.days.withIndex()) {
            val workoutType = try { WorkoutType.valueOf(dayReq.workoutType.uppercase()) } catch (_: Exception) { WorkoutType.FULL_BODY }
            val planDay = UserPlanDay(
                plan = savedPlan,
                dayNumber = dayIndex + 1,
                dayName = dayReq.dayName,
                workoutType = workoutType,
                estimatedDurationMinutes = dayReq.exercises.size * 8 // rough estimate
            )
            val savedDay = userPlanDayRepository.save(planDay)

            for ((exIndex, exReq) in dayReq.exercises.withIndex()) {
                val exercise = exerciseRepository.findById(exReq.exerciseId).orElse(null) ?: continue
                val planExercise = UserPlanDayExercise(
                    planDay = savedDay,
                    exercise = exercise,
                    orderInDay = exIndex + 1,
                    sets = exReq.sets,
                    minReps = exReq.minReps,
                    maxReps = exReq.maxReps,
                    restSeconds = exReq.restSeconds,
                    isCompound = exercise.movementPattern?.lowercase()?.contains("compound") == true
                )
                userPlanDayExerciseRepository.save(planExercise)
            }
        }

        log.info("User {} created custom plan {} with {} days", user.id, savedPlan.id, request.days.size)
        return getPlanDashboard(user.id)
    }

    /**
     * 활성 플랜에서 특정 운동을 대체 운동으로 교체한다.
     *
     * 대체 운동 기능은 원래 `UserProgramEnrollment` 기반 오버라이드만 지원했는데,
     * 템플릿/AI 플랜 사용자는 enrollment 가 생성되지 않으므로 프로덕션에서 항상 404 였다
     * (실사 결과 enrollment 0행, override 0행). 플랜 사용자는 플랜 행을 직접 교체한다.
     *
     * @return 교체된 행 수 (플랜의 여러 날에 같은 운동이 있으면 모두 교체)
     */
    @Transactional
    fun substituteExerciseInActivePlan(
        userId: Long,
        originalExerciseId: Long,
        substituteExerciseId: Long
    ): Int {
        if (originalExerciseId == substituteExerciseId) {
            throw BadRequestException("Cannot substitute an exercise with itself")
        }
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")
        val substitute = exerciseRepository.findById(substituteExerciseId)
            .orElseThrow { BadRequestException("Substitute exercise not found: $substituteExerciseId") }

        val days = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        var replaced = 0
        for (day in days) {
            val exercises = userPlanDayExerciseRepository
                .findByPlanDayIdWithExerciseOrderByOrderInDay(day.id)
            // 같은 날에 이미 대체 운동이 있으면 중복 배치가 되므로 건너뛴다
            if (exercises.any { it.exercise.id == substituteExerciseId }) {
                log.info(
                    "Skipping substitution on day {} — plan already contains exercise {}",
                    day.dayNumber, substituteExerciseId
                )
                continue
            }
            for (planExercise in exercises.filter { it.exercise.id == originalExerciseId }) {
                planExercise.exercise = substitute
                planExercise.isCompound = substitute.muscleGroups.size >= 2
                userPlanDayExerciseRepository.save(planExercise)
                replaced++
            }
        }

        if (replaced == 0) {
            throw BadRequestException("Exercise $originalExerciseId is not part of the active plan")
        }
        plan.updatedAt = LocalDateTime.now()
        userWorkoutPlanRepository.save(plan)
        log.info(
            "User {} substituted exercise {} -> {} in plan {} ({} rows)",
            userId, originalExerciseId, substituteExerciseId, plan.id, replaced
        )
        return replaced
    }

    @Transactional
    fun completeDay(userId: Long, dayNumber: Int) {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val day = userPlanDayRepository.findByPlanIdAndDayNumber(plan.id, dayNumber)
            ?: throw ResourceNotFoundException("Day $dayNumber not found")

        // Update day completion tracking
        day.totalCompletions++
        day.lastCompletedAt = LocalDateTime.now()
        userPlanDayRepository.save(day)

        // Auto-advance to next day
        val nextDay = if (dayNumber >= plan.totalDays) 1 else dayNumber + 1
        plan.currentDay = nextDay
        plan.updatedAt = LocalDateTime.now()
        userWorkoutPlanRepository.save(plan)

        log.info("User {} completed day {} of plan {}, advancing to day {}", userId, dayNumber, plan.id, nextDay)
    }

    @Transactional
    fun setCurrentDay(userId: Long, request: SetCurrentDayRequest) {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val day = userPlanDayRepository.findByPlanIdAndDayNumber(plan.id, request.dayNumber)
            ?: throw BadRequestException("Day ${request.dayNumber} not found in current plan")

        plan.currentDay = request.dayNumber
        plan.updatedAt = LocalDateTime.now()
        userWorkoutPlanRepository.save(plan)
    }

    @Transactional
    fun pausePlan(userId: Long) {
        val plan = getActivePlan(userId) ?: throw ResourceNotFoundException("No active plan found")
        plan.status = PlanStatus.PAUSED
        plan.updatedAt = LocalDateTime.now()
        userWorkoutPlanRepository.save(plan)
    }

    @Transactional
    fun resumePlan(userId: Long) {
        val plan = userWorkoutPlanRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, PlanStatus.PAUSED)
            ?: throw ResourceNotFoundException("No paused plan found")
        plan.status = PlanStatus.ACTIVE
        plan.updatedAt = LocalDateTime.now()
        userWorkoutPlanRepository.save(plan)
    }

    @Transactional
    fun abandonActivePlan(userId: Long) {
        val plans = userWorkoutPlanRepository.findByUserIdAndStatus(userId, PlanStatus.ACTIVE)
        for (plan in plans) {
            plan.status = PlanStatus.ABANDONED
            plan.updatedAt = LocalDateTime.now()
            userWorkoutPlanRepository.save(plan)
            log.info("User {} abandoned plan {}", userId, plan.id)
        }
    }

    fun getPlanOptions(userId: Long): PlanOptionsResponse {
        val systemTemplates = templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
            .map { it.toSummaryResponse() }
        val myAIPlans = templateRepository.findByOwnerUserIdAndSourceType(userId, PlanSourceType.AI_GENERATED)
            .map { it.toSummaryResponse() }
        val currentPlan = try { getPlanDashboard(userId) } catch (_: ResourceNotFoundException) { null }

        return PlanOptionsResponse(
            systemTemplates = systemTemplates,
            myAIPlans = myAIPlans,
            canUseAIPlan = true, // Will be checked by subscription on frontend
            currentPlan = currentPlan
        )
    }

    private fun generateWarmupSets(workingWeight: Double): List<WarmupSetDto> {
        val sets = mutableListOf<WarmupSetDto>()
        // Empty bar
        sets.add(WarmupSetDto(weight = 20.0, reps = 10))
        if (workingWeight > 40.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.50).roundToNearest(2.5), reps = 8))
        }
        if (workingWeight > 60.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.70).roundToNearest(2.5), reps = 5))
        }
        if (workingWeight > 80.0) {
            sets.add(WarmupSetDto(weight = (workingWeight * 0.85).roundToNearest(2.5), reps = 3))
        }
        return sets
    }

    private fun Double.roundToNearest(step: Double): Double {
        return (this / step).roundToInt() * step
    }

    private fun WorkoutPlanTemplate.toSummaryResponse() = TemplateSummaryResponse(
        code = code, name = name, description = description,
        targetGoal = targetGoal, targetExperience = targetExperience,
        splitType = splitType.name, totalDays = totalDays,
        estimatedWeeks = estimatedWeeks, iconName = iconName,
        isPremium = isPremium, sourceType = sourceType.name
    )
}

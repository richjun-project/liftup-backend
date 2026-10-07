package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules
import com.richjun.liftupai.domain.workout.service.new.SessionFit

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
    private val exerciseTranslationRepository: ExerciseTranslationRepository,
    /** 장비·부상·경험·나이·세션 시간·집중 부위의 단일 출처 (요청 값은 이 위에 덮어쓴다) */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
    private val trainingProfileResolver: ExerciseTrainingProfileResolver = ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
) {
    private val log = LoggerFactory.getLogger(UserPlanService::class.java)

    companion object {
        /** 템플릿 하루 운동 수 목표. 부상으로 빠진 만큼 여기까지 채운다. */
        const val TARGET_EXERCISES_PER_DAY = TemplatePlanAdapter.TARGET_EXERCISES_PER_DAY
        /** 초보·노비스 보호 처방을 적용하는 처음 몇 주 — PlanWorkoutEnricher 와 같은 값이어야 대시보드·상세의 시간이 일치한다 */
        const val BEGINNER_PROTECT_WEEKS = 4
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

    /** 요청 장비 → 설정 장비 → 프로필 장비 순. 비어 있으면 제한 없음. 표기는 Equipment enum 이름(BARBELL 등)으로 정규화. */
    internal fun resolveAllowedEquipment(user: User, request: ApplyTemplateRequest?): Set<String> =
        trainingContext.resolve(user.id, overridesOf(request)).equipment

    internal fun overridesOf(request: ApplyTemplateRequest?) = UserTrainingContextResolver.Overrides(
        equipment = request?.equipment, injuries = request?.injuries, experience = request?.experienceLevel,
        focusGoals = null, sessionMinutes = request?.sessionDuration, age = request?.age,
    )

    fun getPlanDashboard(userId: Long, locale: String = "en"): PlanDashboardResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val days = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val ctx = trainingContext.resolve(userId)
        // 날마다 운동 목록과 개인화된 추정 시간(getDayWorkout 과 같은 세트·휴식 맞춤)을 함께 읽는다
        val dayRows = days.associate { day -> day.id to userPlanDayExerciseRepository.findByPlanDayIdWithExerciseOrderByOrderInDay(day.id) }
        val dayExerciseCounts = dayRows.mapValues { it.value.size }
        // 하루 상세(enrich)와 같은 주차·디로드 판정 — 두 화면이 같은 분을 보여 주게 한다
        val weekNumber = (days.sumOf { it.totalCompletions } / plan.totalDays.coerceAtLeast(1)) + 1
        val deloadWeek = plan.deloadEveryNWeeks > 0 && weekNumber > 1 && (weekNumber % plan.deloadEveryNWeeks == 0)
        val beginnerProtected = ctx.level != null && RecoRules.isBeginnerBand(ctx.level) && weekNumber <= BEGINNER_PROTECT_WEEKS

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
                    estimatedDuration = personalizedMinutes(dayRows[day.id].orEmpty(), ctx, deloadWeek, beginnerProtected) ?: day.estimatedDurationMinutes,
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

    /**
     * 하루 운동 목록의 추정 시간(분). getDayWorkout(PlanWorkoutEnricher)가 화면에 보이는 값과 같아야 한다 —
     * 세션 시간이 저장돼 있으면 같은 세트·휴식 맞춤(디로드 주간은 줄이기만, 초보 첫 4주는 주력 복합 보호 반영)을 거친 값,
     * 없으면 enrich 가 그대로 두는 저장값을 쓰도록 null 을 돌려준다. 목록이 비어도 null.
     */
    internal fun personalizedMinutes(
        rows: List<UserPlanDayExercise>,
        ctx: UserTrainingContextResolver.Context,
        deloadWeek: Boolean = false,
        beginnerProtected: Boolean = false,
    ): Int? {
        if (rows.isEmpty()) return null
        val target = ctx.sessionMinutes?.takeIf { it > 0 } ?: return null
        val level = ctx.level
        val slots = rows.map { r ->
            var sets = r.sets; var lo = r.minReps; var hi = r.maxReps; var rest = r.restSeconds
            if (beginnerProtected) {
                val rx = RecoRules.beginnerRx(r.exercise, RecoRules.Rx(sets, lo, hi, rest), r.isCompound)
                sets = rx.sets; lo = rx.minReps; hi = rx.maxReps; rest = rx.restSeconds
            }
            val src = TemplatePlanAdapter.SourceExercise(r.exercise, sets, lo, hi, rest, r.isCompound, r.targetRPE, r.notes)
            TemplatePlanAdapter.slotOf(src, RecoRules.maxSetsFor(r.exercise, level, ctx.age, r.isCompound, ctx.sessionMinutes).coerceAtLeast(sets), null, true)
        }
        return SessionFit.minutes(SessionFit.fit(slots, target, allowGrow = !deloadWeek).slots)
    }

    fun getDayWorkout(userId: Long, dayNumber: Int, subjectiveReadiness: Int? = null, locale: String = "ko"): DayWorkoutResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val day = userPlanDayRepository.findByPlanIdAndDayNumber(plan.id, dayNumber)
            ?: throw ResourceNotFoundException("Day $dayNumber not found in plan")

        val exercises = userPlanDayExerciseRepository.findByPlanDayIdWithExerciseOrderByOrderInDay(day.id)
        val ctx = trainingContext.resolve(userId)

        // 이름 일괄 조회(사용자 언어)
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
                // 모든 경로가 같은 마지막 관문(2.5kg 단위·바벨 20kg 하한)을 거친다
                val finalWeight = RecoRules.finalizeWeight(ex.exercise, suggestedWeight)
                val warmupSets = if (ex.isCompound) {
                    RecoRules.warmups(ex.exercise, finalWeight, ctx.age).map { (w, reps) -> WarmupSetDto(weight = w, reps = reps) }
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
                    suggestedWeight = finalWeight,
                    warmupSets = warmupSets,
                    notes = ex.notes,
                    targetMuscles = ex.exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) }
                )
            }
        )

        // Enrich with advanced features
        return planWorkoutEnricher.enrich(baseResponse, plan, ctx.age, subjectiveReadiness, locale)
    }

    @Transactional
    fun applyTemplate(user: User, templateCode: String, request: ApplyTemplateRequest?, locale: String = "en"): PlanDashboardResponse {
        val template = templateRepository.findByCode(templateCode)
            ?: throw ResourceNotFoundException(WorkoutLocalization.message("error.template_not_found", locale, templateCode))

        // 부상 정보는 요청값을 우선하되, 없으면 온보딩에서 저장된 설정값을 쓴다.
        // (앱이 요청에 injuries 를 안 실어 보내는 경로가 있어 설정값 폴백이 필요하다)
        // 장비·부상·경험·나이·세션 시간은 추천(recommend)과 같은 단일 해석기를 거친다 — 두 경로가 갈라지지 않게.
        val ctx = trainingContext.resolve(user.id, overridesOf(request))
        val injuries = ctx.injuries
        val allowedEquipment = ctx.equipment
        val focus = FocusArea.resolve(request?.goals, request?.focusAreas).ifEmpty { ctx.focusAreas }
        val maxDifficulty = TemplatePlanAdapter.maxDifficultyFor(ctx.experience)
        val level = ctx.level ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER

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
            TemplatePlanAdapter.Context(
                allowedEquipment = allowedEquipment, injuries = injuries, focus = focus, maxDifficulty = maxDifficulty,
                sessionMinutes = ctx.sessionMinutesOrDefault(),
                suitable = RecoRules.suitability(allowedEquipment, level, ctx.age),
                level = level, age = ctx.age,
                repeatCap = TemplatePlanAdapter.repeatCapFor(allowedEquipment, injuries),
            )
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
                    // 템플릿이 적어 둔 분이 아니라 이 사람에게 맞춘 하루의 실제 추정 시간
                    estimatedDurationMinutes = adapted.estimatedMinutes().takeIf { adapted.exercises.isNotEmpty() } ?: templateDay.estimatedDurationMinutes
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
     * 부상 목록 해석 — 프로필 ∪ 설정 ∪ 요청의 합집합(UserTrainingContextResolver). 요청이 저장된 부상을 가리지 않는다.
     * 저장 형식이 "knee:severe" 처럼 심각도가 붙어 오는 경우가 있어 부위만 남긴다.
     */
    internal fun resolveInjuries(user: User, request: ApplyTemplateRequest?): Set<String> =
        trainingContext.resolve(user.id, overridesOf(request)).injuries

    /** 플랜 후보 풀(카테고리별 인기순). 한 번의 적용 안에서 같은 카테고리를 여러 번 읽지 않게 캐시한다. */
    private fun planPool(): (ExerciseCategory) -> List<Exercise> {
        val cache = mutableMapOf<ExerciseCategory, List<Exercise>>()
        return { category -> cache.getOrPut(category) { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(category) } }
    }

    /** 경험 수준: 요청값 → 프로필. 채워 넣는 운동의 난이도 상한에 쓴다. */
    internal fun resolveExperience(user: User, request: ApplyTemplateRequest?): String? =
        trainingContext.resolve(user.id, overridesOf(request)).experience?.uppercase()

    /**
     * 집중 부위: 요청(goals·focus_areas) → 온보딩에서 저장한 세부 목표(UserSettings.focusGoals).
     * FitnessGoal 은 6종뿐이라 thick_legs·glutes 같은 세부 목표는 별도로 저장해 둔 값을 읽는다.
     */
    internal fun resolveFocusAreas(user: User, request: ApplyTemplateRequest?): List<FocusArea> =
        FocusArea.resolve(request?.goals, request?.focusAreas).ifEmpty { trainingContext.resolve(user.id).focusAreas }

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
            val dayExercises = dayReq.exercises.mapNotNull { exReq ->
                exerciseRepository.findById(exReq.exerciseId).orElse(null)?.let { exReq to it }
            }
            val planDay = UserPlanDay(
                plan = savedPlan,
                dayNumber = dayIndex + 1,
                dayName = dayReq.dayName,
                workoutType = workoutType,
                estimatedDurationMinutes = customDayMinutes(dayExercises)
            )
            val savedDay = userPlanDayRepository.save(planDay)

            for ((exIndex, pair) in dayExercises.withIndex()) {
                val (exReq, exercise) = pair
                val planExercise = UserPlanDayExercise(
                    planDay = savedDay,
                    exercise = exercise,
                    orderInDay = exIndex + 1,
                    sets = exReq.sets,
                    minReps = exReq.minReps,
                    maxReps = exReq.maxReps,
                    restSeconds = exReq.restSeconds,
                    // 복합운동 판정은 템플릿·추천과 같은 단일 규칙 — 예전엔 패턴 이름에 "compound" 가 든 것만 true 라 웜업 세트가 안 나왔다
                    isCompound = RecoRules.isCompound(exercise)
                )
                userPlanDayExerciseRepository.save(planExercise)
            }
        }

        log.info("User {} created custom plan {} with {} days", user.id, savedPlan.id, request.days.size)
        return getPlanDashboard(user.id)
    }

    /** 맞춤 플랜 하루의 추정 시간 — 종목 수 × 8분 같은 어림이 아니라 세트·반복·휴식으로 계산한다(추천·AI 와 같은 식). */
    internal fun customDayMinutes(exercises: List<Pair<com.richjun.liftupai.domain.workout.dto.request.CustomPlanExerciseRequest, Exercise>>): Int {
        if (exercises.isEmpty()) return 0
        return SessionFit.minutes(exercises.map { (req, ex) ->
            SessionFit.Slot(req.sets, "${req.minReps}-${req.maxReps}", req.restSeconds)
        })
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

        // 대체 후보 목록(C3)과 같은 규칙으로 직접 지정한 대체도 막는다 — 부상 부위·없는 장비·수준에 안 맞는 동작은 받지 않는다.
        val ctx = trainingContext.resolve(userId)
        if (ctx.injuries.isNotEmpty() && injuryFilterService.restrictionChecker(ctx.injuries)(substitute)) {
            throw BadRequestException("Substitute exercise $substituteExerciseId is restricted by your injuries")
        }
        if (!TemplatePlanAdapter.equipmentAllowed(substitute, ctx.equipment)) {
            throw BadRequestException("Substitute exercise $substituteExerciseId needs equipment you do not have")
        }
        if (!RecoRules.suitable(substitute, ctx.equipment, ctx.level, ctx.age)) {
            throw BadRequestException("Substitute exercise $substituteExerciseId is not suitable for your level")
        }
        val goals = userProfileRepository.findByUser_Id(userId).orElse(null)?.goals.orEmpty()

        val days = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        var replaced = 0
        var skippedAsDuplicate = 0
        for (day in days) {
            val exercises = userPlanDayExerciseRepository
                .findByPlanDayIdWithExerciseOrderByOrderInDay(day.id)
            val targets = exercises.filter { it.exercise.id == originalExerciseId }
            // 같은 날에 이미 대체 운동이 있으면 중복 배치가 되므로 건너뛴다
            if (exercises.any { it.exercise.id == substituteExerciseId }) {
                skippedAsDuplicate += targets.size
                log.info(
                    "Skipping substitution on day {} — plan already contains exercise {}",
                    day.dayNumber, substituteExerciseId
                )
                continue
            }
            for (planExercise in targets) {
                planExercise.exercise = substitute
                // 처방(세트·반복·휴식·복합 여부)을 새 운동 기준으로 다시 정한다 — 스쿼트 4x6-8/180초가 레그 익스텐션에 그대로 남지 않게
                applyPrescription(planExercise, substitute, ctx, goals)
                userPlanDayExerciseRepository.save(planExercise)
                replaced++
            }
        }

        if (replaced == 0) {
            if (skippedAsDuplicate > 0) {
                throw BadRequestException("Substitute exercise $substituteExerciseId is already in every day that contains exercise $originalExerciseId")
            }
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

    /** 새 운동의 훈련 특성(주력 복합·보조 복합·고립·코어)과 이 사람의 수준·목표로 처방을 다시 정한다. */
    internal fun applyPrescription(
        row: UserPlanDayExercise,
        exercise: Exercise,
        ctx: UserTrainingContextResolver.Context,
        goals: Set<com.richjun.liftupai.domain.user.entity.FitnessGoal>,
    ) {
        val compound = RecoRules.isCompound(exercise)
        row.isCompound = compound
        row.notes = null
        val profile = trainingProfileResolver.resolveProfile(exercise)
        val config = trainingProfileResolver.applyGoal(
            trainingProfileResolver.getConfig(PTRecommendationEngine.PeriodizationPhase.ACCUMULATION, profile), profile, goals
        )
        val parts = Regex("""(\d+)\s*(?:-\s*(\d+))?""").find(config.reps)
        // 시간 기반(유산소 등)은 반복수로 표현할 수 없어 원래 자리의 값을 둔다
        if (parts == null || config.reps.contains("time")) return
        val lo = parts.groupValues[1].toInt()
        val hi = parts.groupValues[2].ifEmpty { parts.groupValues[1] }.toInt()
        var rx = RecoRules.Rx(config.sets, lo, hi, config.restSeconds)
        if (RecoRules.isBeginnerBand(ctx.level) && ctx.level != null) rx = RecoRules.beginnerRx(exercise, rx, compound)
        val cap = RecoRules.maxSetsFor(exercise, ctx.level, ctx.age, compound)
        row.sets = rx.sets.coerceAtMost(cap).coerceAtLeast(1)
        row.minReps = rx.minReps
        row.maxReps = rx.maxReps
        row.restSeconds = rx.restSeconds
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

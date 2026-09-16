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
        const val TARGET_EXERCISES_PER_DAY = 5
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
     * 부상 부위를 피하는 대체 운동. 그날의 부위 → 코어 → 상체 순으로, 인기순.
     * 카탈로그에 후보가 없으면 빈 목록(그날은 있는 만큼만 간다).
     */
    internal fun pickRefillExercises(
        workoutType: String,
        injuries: Set<String>,
        exclude: Set<Long>,
        needed: Int,
        allowedEquipment: Set<String> = emptySet(),
    ): List<Exercise> {
        if (needed <= 0) return emptyList()
        fun equipmentOk(ex: Exercise) = equipmentAllowed(ex, allowedEquipment)
        val categories = refillCategoriesFor(workoutType) + listOf(
            ExerciseCategory.CORE, ExerciseCategory.CHEST, ExerciseCategory.BACK,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS,
        )
        val picked = mutableListOf<Exercise>()
        val seen = exclude.toMutableSet()
        // 카테고리별 후보 큐를 만들고 라운드로빈으로 뽑는다 — 한 부위(가슴)만 몰리지 않게
        val queues = categories.distinct().map { category ->
            exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(category).filter { ex ->
                ex.id !in seen && equipmentOk(ex) && (injuries.isEmpty() || !injuryFilterService.isExerciseRestricted(ex.id, injuries))
            }.iterator()
        }
        var progressed = true
        while (picked.size < needed && progressed) {
            progressed = false
            for (q in queues) {
                if (picked.size >= needed) break
                while (q.hasNext()) {
                    val ex = q.next()
                    if (ex.id in seen) continue
                    seen += ex.id; picked += ex; progressed = true; break
                }
            }
        }
        return picked
    }

    /** 보유 장비로 할 수 있는 종목인가. 장비 미지정·맨몸·기타는 항상 허용. */
    internal fun equipmentAllowed(ex: Exercise, allowedEquipment: Set<String>): Boolean {
        val eq = ex.equipment?.name ?: return true
        return allowedEquipment.isEmpty() || eq == "BODYWEIGHT" || eq == "OTHER" || eq in allowedEquipment
    }

    /** 요청 장비 → 설정 장비 순. 비어 있으면 제한 없음. 표기는 Equipment enum 이름(BARBELL 등)으로 정규화. */
    internal fun resolveAllowedEquipment(user: User, request: ApplyTemplateRequest?): Set<String> {
        val fromRequest = request?.equipment?.filter { it.isNotBlank() }.orEmpty()
        // 기기 가입(AuthService)은 프로필에만 장비를 쓰고, 온보딩 완료(UserService)는 설정에도 쓴다 — 둘 다 본다.
        val raw = if (fromRequest.isNotEmpty()) fromRequest else
            userSettingsRepository.findByUser_Id(user.id).orElse(null)?.availableEquipment?.takeIf { it.isNotEmpty() }?.toList()
                ?: userProfileRepository.findByUser_Id(user.id).orElse(null)?.availableEquipment?.toList().orEmpty()
        return raw.map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }.filter { it.isNotBlank() }.toSet()
    }

    private fun refillCategoriesFor(workoutType: String): List<ExerciseCategory> = when (workoutType.uppercase()) {
        "PUSH", "CHEST" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
        "PULL", "BACK" -> listOf(ExerciseCategory.BACK, ExerciseCategory.ARMS)
        "LEGS", "LOWER" -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CORE)
        "UPPER" -> listOf(ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
        "SHOULDERS" -> listOf(ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS)
        "ARMS" -> listOf(ExerciseCategory.ARMS, ExerciseCategory.SHOULDERS)
        "ABS", "CORE" -> listOf(ExerciseCategory.CORE)
        "CARDIO" -> listOf(ExerciseCategory.CARDIO, ExerciseCategory.LEGS)
        else -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.CORE)
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
        var skippedForInjury = 0
        var skippedForEquipment = 0

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

        // Copy template days and exercises
        val templateDays = templateDayRepository.findByTemplateIdOrderByDayNumber(template.id)
        // 재구성으로 넣은 운동은 플랜 전체에서 한 번만(운영 계정 123: 3일 모두 같은 3개가 들어갔다)
        val refillUsedAcrossPlan = mutableSetOf<Long>()
        for (templateDay in templateDays) {
            val planDay = UserPlanDay(
                plan = savedPlan,
                dayNumber = templateDay.dayNumber,
                dayName = WorkoutLocalization.maybeMessage("template.${template.code}.day.${templateDay.dayNumber}.name", locale) ?: templateDay.dayName,
                workoutType = templateDay.workoutType,
                estimatedDurationMinutes = templateDay.estimatedDurationMinutes
            )
            val savedDay = userPlanDayRepository.save(planDay)

            val templateExercises = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(templateDay.id)
            val allowedEquipment = resolveAllowedEquipment(user, request)
            val keptExerciseIds = mutableSetOf<Long>()
            var keptInDay = 0
            var lastOrder = 0
            for (templateEx in templateExercises) {
                // 부상 부위를 건드리는 종목은 플랜에 넣지 않는다.
                //
                // 2026-08-26 실사용 사고: 무릎 수술을 신고한 사용자(user_settings_injuries='knee')의
                // 템플릿 플랜에 하체 운동 6개가 그대로 들어갔다. 부상 필터는 AI 추천·프로그램 등록·
                // 대체운동 경로에만 연결돼 있었고, 신규 사용자 대부분이 타는 템플릿 경로에는 없었다.
                if (injuries.isNotEmpty() &&
                    injuryFilterService.isExerciseRestricted(templateEx.exercise.id, injuries)
                ) {
                    skippedForInjury++
                    continue
                }
                // 보유 장비로 못 하는 종목도 넣지 않는다(덤벨만 있는 사용자에게 바벨 벤치 — 2026-09-16 운영 검증).
                if (!equipmentAllowed(templateEx.exercise, allowedEquipment)) {
                    skippedForEquipment++
                    continue
                }
                keptExerciseIds += templateEx.exercise.id
                keptInDay++
                lastOrder = templateEx.orderInDay
                val planExercise = UserPlanDayExercise(
                    planDay = savedDay,
                    exercise = templateEx.exercise,
                    orderInDay = templateEx.orderInDay,
                    sets = templateEx.sets,
                    minReps = templateEx.minReps,
                    maxReps = templateEx.maxReps,
                    restSeconds = templateEx.restSeconds,
                    isCompound = templateEx.isCompound,
                    targetRPE = templateEx.targetRPE,
                    notes = templateEx.notes
                )
                userPlanDayExerciseRepository.save(planExercise)
            }

            // 부상 때문에 비어버린 날을 안전한 운동으로 다시 채운다.
            //
            // 2026-09-15 실사용: 무릎·고관절·허리 부상 사용자(118)의 diet_circuit 플랜이
            // 하루 2·3·4개로 줄었고, 손목 부상(115)은 2개짜리 날이 생겼다.
            // 걸러내기만 하고 채우지 않으면 "운동이 거의 없는 플랜"을 받는다.
            val removedInDay = templateExercises.size - keptInDay
            if (removedInDay > 0 && keptInDay < TARGET_EXERCISES_PER_DAY) {
                val refill = pickRefillExercises(
                    workoutType = templateDay.workoutType.name,
                    injuries = injuries,
                    exclude = keptExerciseIds + refillUsedAcrossPlan,
                    needed = TARGET_EXERCISES_PER_DAY - keptInDay,
                    allowedEquipment = allowedEquipment,
                )
                refillUsedAcrossPlan += refill.map { it.id }
                refill.forEachIndexed { i, ex ->
                    userPlanDayExerciseRepository.save(
                        UserPlanDayExercise(
                            planDay = savedDay,
                            exercise = ex,
                            orderInDay = lastOrder + i + 1,
                            sets = 3,
                            minReps = 10,
                            maxReps = 15,
                            restSeconds = 60,
                            isCompound = false,
                            targetRPE = 7.0,
                            notes = null,
                        )
                    )
                }
                if (refill.isNotEmpty()) {
                    log.info("Refilled day {} of plan {} with {} injury-safe exercises", templateDay.dayNumber, savedPlan.id, refill.size)
                }
            }
        }

        log.info(
            "User {} applied template {} as new plan {} (injuries={}, exercisesSkipped={}, skippedForEquipment={})",
            user.id, templateCode, savedPlan.id, injuries, skippedForInjury, skippedForEquipment
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

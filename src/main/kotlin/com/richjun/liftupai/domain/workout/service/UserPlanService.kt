package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest
import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.exception.BadRequestException
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
    private val exerciseRepository: ExerciseRepository,
    private val weightRecommendationService: WeightRecommendationService,
    private val planWorkoutEnricher: PlanWorkoutEnricher,
    private val userProfileRepository: com.richjun.liftupai.domain.user.repository.UserProfileRepository
) {
    private val log = LoggerFactory.getLogger(UserPlanService::class.java)

    fun getActivePlan(userId: Long): UserWorkoutPlan? {
        return userWorkoutPlanRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, PlanStatus.ACTIVE)
    }

    fun getPlanDashboard(userId: Long): PlanDashboardResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val days = userPlanDayRepository.findByPlanIdOrderByDayNumber(plan.id)
        val dayExerciseCounts = days.associate { day ->
            day.id to userPlanDayExerciseRepository.findByPlanDayIdOrderByOrderInDay(day.id).size
        }

        return PlanDashboardResponse(
            planId = plan.id,
            planName = plan.planName,
            planDescription = plan.planDescription,
            splitType = plan.splitType.name,
            sourceType = plan.sourceType.name,
            totalDays = plan.totalDays,
            currentDay = plan.currentDay,
            progressionModel = plan.progressionModel.name,
            deloadEveryNWeeks = plan.deloadEveryNWeeks,
            days = days.map { day ->
                PlanDayOverview(
                    dayNumber = day.dayNumber,
                    dayName = day.dayName,
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

    fun getDayWorkout(userId: Long, dayNumber: Int, subjectiveReadiness: Int? = null): DayWorkoutResponse {
        val plan = getActivePlan(userId)
            ?: throw ResourceNotFoundException("No active plan found")

        val day = userPlanDayRepository.findByPlanIdAndDayNumber(plan.id, dayNumber)
            ?: throw ResourceNotFoundException("Day $dayNumber not found in plan")

        val exercises = userPlanDayExerciseRepository.findByPlanDayIdWithExerciseOrderByOrderInDay(day.id)

        val baseResponse = DayWorkoutResponse(
            dayNumber = day.dayNumber,
            dayName = day.dayName,
            workoutType = day.workoutType.name,
            estimatedDuration = day.estimatedDurationMinutes,
            completionCount = day.totalCompletions,
            exercises = exercises.map { ex ->
                val targetReps = (ex.minReps + ex.maxReps) / 2
                val suggestedWeight = weightRecommendationService.calculateSuggestedWeight(
                    userId, ex.exercise.id, targetReps
                )
                val warmupSets = if (ex.isCompound && suggestedWeight != null && suggestedWeight > 20.0) {
                    generateWarmupSets(suggestedWeight)
                } else {
                    emptyList()
                }
                DayExerciseDetail(
                    exerciseId = ex.exercise.id,
                    exerciseName = ex.exercise.name,
                    imageUrl = ex.exercise.imageUrl,
                    sets = ex.sets,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps,
                    restSeconds = ex.restSeconds,
                    isCompound = ex.isCompound,
                    targetRPE = ex.targetRPE,
                    suggestedWeight = suggestedWeight,
                    warmupSets = warmupSets,
                    notes = ex.notes
                )
            }
        )

        // Enrich with advanced features
        val userAge = userProfileRepository.findByUser_Id(userId).orElse(null)?.age
        return planWorkoutEnricher.enrich(baseResponse, plan, userAge, subjectiveReadiness)
    }

    @Transactional
    fun applyTemplate(user: User, templateCode: String, request: ApplyTemplateRequest?): PlanDashboardResponse {
        val template = templateRepository.findByCode(templateCode)
            ?: throw ResourceNotFoundException("Template not found: $templateCode")

        // Abandon existing active plan
        abandonActivePlan(user.id)

        // Create new plan from template
        val plan = UserWorkoutPlan(
            user = user,
            sourceType = if (template.sourceType == PlanSourceType.AI_GENERATED) PlanSourceType.AI_GENERATED else PlanSourceType.TEMPLATE,
            sourceId = template.code,
            planName = template.name,
            planDescription = template.description,
            splitType = template.splitType,
            totalDays = template.totalDays,
            currentDay = 1,
            status = PlanStatus.ACTIVE,
            aiCoachingNotes = template.aiCoachingNotes
        )
        val savedPlan = userWorkoutPlanRepository.save(plan)

        // Copy template days and exercises
        val templateDays = templateDayRepository.findByTemplateIdOrderByDayNumber(template.id)
        for (templateDay in templateDays) {
            val planDay = UserPlanDay(
                plan = savedPlan,
                dayNumber = templateDay.dayNumber,
                dayName = templateDay.dayName,
                workoutType = templateDay.workoutType,
                estimatedDurationMinutes = templateDay.estimatedDurationMinutes
            )
            val savedDay = userPlanDayRepository.save(planDay)

            val templateExercises = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(templateDay.id)
            for (templateEx in templateExercises) {
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
        }

        log.info("User {} applied template {} as new plan {}", user.id, templateCode, savedPlan.id)
        return getPlanDashboard(user.id)
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

        if (request.dayNumber < 1 || request.dayNumber > plan.totalDays) {
            throw BadRequestException("Day number must be between 1 and ${plan.totalDays}")
        }

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

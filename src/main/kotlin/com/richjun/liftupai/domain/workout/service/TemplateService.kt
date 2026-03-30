package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TemplateService(
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository
) {
    fun getAllTemplates(userId: Long?): List<TemplateSummaryResponse> {
        val systemTemplates = templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
        val userAIPlans = if (userId != null) {
            templateRepository.findByOwnerUserIdAndSourceType(userId, PlanSourceType.AI_GENERATED)
        } else emptyList()

        return (systemTemplates + userAIPlans).map { it.toSummaryResponse() }
    }

    fun getSystemTemplates(): List<TemplateSummaryResponse> {
        return templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
            .map { it.toSummaryResponse() }
    }

    fun getUserAIPlans(userId: Long): List<TemplateSummaryResponse> {
        return templateRepository.findByOwnerUserIdAndSourceType(userId, PlanSourceType.AI_GENERATED)
            .map { it.toSummaryResponse() }
    }

    fun getTemplateDetail(code: String): TemplateDetailResponse {
        val template = templateRepository.findByCode(code)
            ?: throw ResourceNotFoundException("Template not found: $code")

        val days = templateDayRepository.findByTemplateIdOrderByDayNumber(template.id)
        val dayResponses = days.map { day ->
            val exercises = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(day.id)
            TemplateDayResponse(
                dayNumber = day.dayNumber,
                dayName = day.dayName,
                workoutType = day.workoutType.name,
                estimatedDurationMinutes = day.estimatedDurationMinutes,
                exercises = exercises.map { ex ->
                    TemplateDayExerciseResponse(
                        exerciseId = ex.exercise.id,
                        exerciseName = ex.exercise.name,
                        imageUrl = ex.exercise.imageUrl,
                        orderInDay = ex.orderInDay,
                        sets = ex.sets,
                        minReps = ex.minReps,
                        maxReps = ex.maxReps,
                        restSeconds = ex.restSeconds,
                        isCompound = ex.isCompound,
                        notes = ex.notes
                    )
                }
            )
        }

        return TemplateDetailResponse(
            code = template.code,
            name = template.name,
            description = template.description,
            targetGoal = template.targetGoal,
            targetExperience = template.targetExperience,
            splitType = template.splitType.name,
            totalDays = template.totalDays,
            estimatedWeeks = template.estimatedWeeks,
            days = dayResponses
        )
    }

    private fun WorkoutPlanTemplate.toSummaryResponse() = TemplateSummaryResponse(
        code = code,
        name = name,
        description = description,
        targetGoal = targetGoal,
        targetExperience = targetExperience,
        splitType = splitType.name,
        totalDays = totalDays,
        estimatedWeeks = estimatedWeeks,
        iconName = iconName,
        isPremium = isPremium,
        sourceType = sourceType.name
    )
}

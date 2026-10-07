package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.response.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TemplateService(
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository
) {
    @org.springframework.beans.factory.annotation.Value("\${app.exercise-media.base-url:https://liftup-cdn.com}")
    private var exerciseMediaBaseUrl: String = "https://liftup-cdn.com"

    private fun thumbnailUrl(exercise: Exercise): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/thumb.jpg"
    }

    private fun videoUrl(exercise: Exercise): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/video.mp4"
    }

    // Preset (PLAN_SOURCE=PRESET) template name/description/day-name are seeded once in Korean by
    // TemplateBootstrapService and have no per-locale column, so we look up a translation from
    // workout_messages*.properties by template code instead. AI_GENERATED plans have no such bundle entry
    // (they're generated per-user, not preset), so they fall back to the stored value unchanged.
    private fun translatedTemplateName(code: String, fallback: String, locale: String): String {
        return WorkoutLocalization.maybeMessage("template.$code.name", locale) ?: fallback
    }

    private fun translatedTemplateDescription(code: String, fallback: String?, locale: String): String? {
        return WorkoutLocalization.maybeMessage("template.$code.description", locale) ?: fallback
    }

    private fun translatedDayName(code: String, dayNumber: Int, fallback: String, locale: String): String {
        return WorkoutLocalization.maybeMessage("template.$code.day.$dayNumber.name", locale) ?: fallback
    }

    fun getAllTemplates(userId: Long?, locale: String = "en"): List<TemplateSummaryResponse> {
        val systemTemplates = templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
        val userAIPlans = if (userId != null) {
            templateRepository.findByOwnerUserIdAndSourceType(userId, PlanSourceType.AI_GENERATED)
        } else emptyList()

        return (systemTemplates + userAIPlans).map { it.toSummaryResponse(locale) }
    }

    fun getSystemTemplates(locale: String = "en"): List<TemplateSummaryResponse> {
        return templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
            .map { it.toSummaryResponse(locale) }
    }

    fun getUserAIPlans(userId: Long, locale: String = "en"): List<TemplateSummaryResponse> {
        return templateRepository.findByOwnerUserIdAndSourceType(userId, PlanSourceType.AI_GENERATED)
            .map { it.toSummaryResponse(locale) }
    }

    fun getTemplateDetail(code: String, locale: String = "ko"): TemplateDetailResponse {
        val template = templateRepository.findByCode(code)
            ?: throw ResourceNotFoundException("Template not found: $code")

        val days = templateDayRepository.findByTemplateIdOrderByDayNumber(template.id)

        // 모든 Day의 운동 ID 수집 → 번역 일괄 조회
        val allExercises = days.flatMap { day ->
            templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(day.id)
        }
        val exerciseIds = allExercises.map { it.exercise.id }.distinct()
        val translatedNames = if (locale != "en") {
            exerciseTranslationRepository.findByExerciseIdInAndLocale(exerciseIds, locale)
                .associateBy { it.exercise.id }
        } else emptyMap()

        val dayResponses = days.map { day ->
            val exercises = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(day.id)
            TemplateDayResponse(
                dayNumber = day.dayNumber,
                dayName = translatedDayName(template.code, day.dayNumber, day.dayName, locale),
                workoutType = day.workoutType.name,
                // 저장된 분이 아니라 운동 구성이 실제로 걸리는 시간(추천·플랜과 같은 식)
                estimatedDurationMinutes = if (exercises.isEmpty()) day.estimatedDurationMinutes else SessionFit.minutes(
                    exercises.map { SessionFit.Slot(it.sets, "${it.minReps}-${it.maxReps}", it.restSeconds) }
                ),
                exercises = exercises.map { ex ->
                    TemplateDayExerciseResponse(
                        exerciseId = ex.exercise.id,
                        exerciseName = translatedNames[ex.exercise.id]?.displayName ?: ex.exercise.name,
                        category = ex.exercise.category.name,
                        equipment = ex.exercise.equipment?.name,
                        imageUrl = thumbnailUrl(ex.exercise),
                        videoUrl = videoUrl(ex.exercise),
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
            name = translatedTemplateName(template.code, template.name, locale),
            description = translatedTemplateDescription(template.code, template.description, locale),
            targetGoal = template.targetGoal,
            targetExperience = template.targetExperience,
            splitType = template.splitType.name,
            totalDays = template.totalDays,
            estimatedWeeks = template.estimatedWeeks,
            days = dayResponses
        )
    }

    private fun WorkoutPlanTemplate.toSummaryResponse(locale: String = "en") = TemplateSummaryResponse(
        code = code,
        name = translatedTemplateName(code, name, locale),
        description = translatedTemplateDescription(code, description, locale),
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

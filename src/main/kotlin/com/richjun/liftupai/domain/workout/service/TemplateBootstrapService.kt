package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
@Order(200) // ExerciseCatalogBootstrapService(@Order(100)) 이후 실행
class TemplateBootstrapService(
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository,
    private val exerciseRepository: ExerciseRepository,
    private val txTemplate: TransactionTemplate
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run(args: ApplicationArguments) {
        if (exerciseRepository.count() == 0L) {
            logger.warn("Template bootstrap skipped: no exercises in database")
            return
        }

        var seeded = 0
        for (def in PresetTemplates.ALL) {
            try {
                // 이미 있는 코드는 건너뛴다 — 운영 DB 의 기존 템플릿·사용자 플랜은 그대로 두고 새 코드만 추가된다.
                txTemplate.execute { seedTemplate(def) }
                seeded++
            } catch (e: TemplateAlreadyExistsException) {
                logger.debug("Template already exists: {}", e.code)
            } catch (e: Exception) {
                logger.error("Failed to seed template {}: {}", def.code, e.message, e)
            }
        }

        if (seeded > 0) {
            logger.info("Preset workout plan templates seeded: {} new", seeded)
        } else {
            logger.info("Template bootstrap: all preset templates already exist")
        }
    }

    private fun seedTemplate(def: PresetTemplate) {
        val template = saveTemplate(def)
        def.days.forEach { seedDay(template, it) }
        logger.info("  -> {} seeded", def.code)
    }

    // ── helpers ───────────────────────────────────────────────

    private class TemplateAlreadyExistsException(val code: String) : RuntimeException()

    private fun saveTemplate(def: PresetTemplate): WorkoutPlanTemplate {
        if (templateRepository.findByCode(def.code) != null) {
            throw TemplateAlreadyExistsException(def.code)
        }
        return templateRepository.save(
            WorkoutPlanTemplate(
                code = def.code,
                name = def.name,
                description = def.description,
                targetGoal = def.targetGoal,
                targetExperience = def.targetExperience,
                splitType = def.splitType,
                totalDays = def.totalDays,
                estimatedWeeks = 8,
                iconName = def.iconName,
                sortOrder = def.sortOrder,
                sourceType = PlanSourceType.PRESET
            )
        )
    }

    private fun seedDay(template: WorkoutPlanTemplate, def: PresetDay) {
        val day = templateDayRepository.save(
            TemplateDay(
                template = template,
                dayNumber = def.dayNumber,
                dayName = def.dayName,
                workoutType = def.workoutType,
                estimatedDurationMinutes = def.durationMinutes
            )
        )

        def.exercises.forEachIndexed { index, ex ->
            val exercise = exerciseRepository.findBySlug(ex.slug)
            if (exercise == null) {
                logger.warn("Exercise not found: {} (skipped for template {})", ex.slug, template.code)
                return@forEachIndexed
            }
            templateDayExerciseRepository.save(
                TemplateDayExercise(
                    templateDay = day,
                    exercise = exercise,
                    orderInDay = index + 1,
                    sets = ex.sets,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps,
                    restSeconds = ex.restSeconds,
                    isCompound = ex.isCompound,
                    targetRPE = ex.targetRPE,
                    notes = ex.notes
                )
            )
        }
    }
}

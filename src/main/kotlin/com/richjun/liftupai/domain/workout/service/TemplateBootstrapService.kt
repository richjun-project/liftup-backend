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

        val definitions = listOf(
            ::seedBeginnerFullBody,
            ::seedHypertrophyBasic,
            ::seedDietCircuit,
            ::seedPPLClassic,
            ::seedUpperLowerMuscle
        )

        var seeded = 0
        for (def in definitions) {
            try {
                txTemplate.execute { def() }
                seeded++
            } catch (e: TemplateAlreadyExistsException) {
                logger.debug("Template already exists: {}", e.code)
            } catch (e: Exception) {
                logger.error("Failed to seed template: {}", e.message, e)
            }
        }

        if (seeded > 0) {
            logger.info("Preset workout plan templates seeded: {} new", seeded)
        } else {
            logger.info("Template bootstrap: all preset templates already exist")
        }
    }

    // ── 1. 초급자 전신 루틴 (3일/주) ──────────────────────────
    private fun seedBeginnerFullBody() {
        val template = saveTemplate(
            code = "beginner_fullbody",
            name = "초급자 전신 루틴",
            description = "운동을 처음 시작하는 분을 위한 전신 프로그램입니다. 주 3일, 기본 복합 운동 중심으로 전신 근력을 키웁니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "fitness_center",
            sortOrder = 1
        )

        seedDay(template, 1, "전신 A", WorkoutType.FULL_BODY, 50, listOf(
            Ex("barbell-full-squat", 4, 8, 12, 90, true, 7.0),
            Ex("barbell-bench-press", 4, 8, 12, 90, true, 7.0),
            Ex("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
            Ex("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
        ))

        seedDay(template, 2, "전신 B", WorkoutType.FULL_BODY, 50, listOf(
            Ex("barbell-deadlift", 4, 6, 10, 120, true, 7.0),
            Ex("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
            Ex("cable-pulldown", 3, 10, 12, 60, true, 7.0),
            Ex("barbell-lunge", 3, 10, 12, 60, true, 7.0),
            Ex("lying-leg-raise", 3, 12, 15, 45, false, 6.0)
        ))

        seedDay(template, 3, "전신 C", WorkoutType.FULL_BODY, 50, listOf(
            Ex("leg-press-machine-normal-stance", 4, 10, 12, 90, true, 7.0),
            Ex("dumbbell-bench-press", 3, 10, 12, 60, true, 7.0),
            Ex("barbell-bent-over-row-pronated-grip", 3, 8, 12, 90, true, 7.0),
            Ex("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0),
            Ex("russian-twist", 3, 15, 20, 45, false, 6.0)
        ))

        logger.info("  -> beginner_fullbody seeded")
    }

    // ── 2. 근비대 기초 루틴 (4일/주, 상하체) ──────────────────
    private fun seedHypertrophyBasic() {
        val template = saveTemplate(
            code = "hypertrophy_basic",
            name = "근비대 기초 루틴",
            description = "근육량 증가를 목표로 하는 상하체 분할 프로그램입니다. 주 4일, 복합+고립 운동을 조합합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 4,
            iconName = "trending_up",
            sortOrder = 2
        )

        seedDay(template, 1, "상체 A", WorkoutType.UPPER, 55, listOf(
            Ex("barbell-bench-press", 4, 8, 12, 90, true, 7.0),
            Ex("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
            Ex("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
            Ex("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0)
        ))

        seedDay(template, 2, "하체 A", WorkoutType.LOWER, 50, listOf(
            Ex("barbell-full-squat", 4, 8, 12, 120, true, 7.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
            Ex("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
            Ex("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
        ))

        seedDay(template, 3, "상체 B", WorkoutType.UPPER, 55, listOf(
            Ex("barbell-incline-bench-press", 4, 8, 12, 90, true, 7.0),
            Ex("cable-bar-lateral-pulldown", 4, 10, 12, 60, true, 7.0),
            Ex("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0),
            Ex("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
            Ex("cable-pushdown", 3, 10, 12, 60, false, 7.0)
        ))

        seedDay(template, 4, "하체 B", WorkoutType.LOWER, 50, listOf(
            Ex("barbell-deadlift", 4, 6, 10, 120, true, 7.0),
            Ex("barbell-lunge", 3, 10, 12, 60, true, 7.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
            Ex("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
        ))

        logger.info("  -> hypertrophy_basic seeded")
    }

    // ── 3. 다이어트 서킷 (3일/주) ────────────────────────────
    private fun seedDietCircuit() {
        val template = saveTemplate(
            code = "diet_circuit",
            name = "다이어트 서킷",
            description = "체지방 감량을 목표로 하는 서킷 트레이닝입니다. 복합 운동 위주로 높은 칼로리 소모를 유도합니다.",
            targetGoal = "weight_loss",
            targetExperience = "intermediate",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "local_fire_department",
            sortOrder = 3
        )

        seedDay(template, 1, "서킷 A", WorkoutType.FULL_BODY, 45, listOf(
            Ex("barbell-full-squat", 4, 10, 15, 60, true, 7.0),
            Ex("barbell-bench-press", 4, 10, 15, 60, true, 7.0),
            Ex("barbell-bent-over-row-pronated-grip", 3, 10, 15, 60, true, 7.0),
            Ex("barbell-lunge", 3, 12, 15, 45, true, 7.0),
            Ex("high-plank", 3, 30, 60, 45, false, 7.0, "초 단위")
        ))

        seedDay(template, 2, "서킷 B", WorkoutType.FULL_BODY, 45, listOf(
            Ex("barbell-deadlift", 4, 8, 12, 60, true, 7.0),
            Ex("deep-push-up", 3, 12, 20, 45, true, 7.0),
            Ex("cable-bar-lateral-pulldown", 3, 12, 15, 45, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 10, 15, 45, true, 7.0),
            Ex("russian-twist", 3, 15, 20, 45, false, 7.0)
        ))

        seedDay(template, 3, "서킷 C", WorkoutType.FULL_BODY, 45, listOf(
            Ex("leg-press-machine-normal-stance", 4, 12, 15, 60, true, 7.0),
            Ex("barbell-incline-bench-press", 3, 10, 15, 60, true, 7.0),
            Ex("cable-pulldown", 3, 12, 15, 45, true, 7.0),
            Ex("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0),
            Ex("abdominal-crunches", 3, 15, 20, 45, false, 7.0)
        ))

        logger.info("  -> diet_circuit seeded")
    }

    // ── 4. PPL 클래식 (6일/주) ────────────────────────────────
    private fun seedPPLClassic() {
        val template = saveTemplate(
            code = "ppl_classic",
            name = "PPL 클래식",
            description = "Push/Pull/Legs 6일 분할 프로그램입니다. 각 근육군을 주 2회 자극하여 근비대를 극대화합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "intermediate",
            splitType = SplitType.PPL,
            totalDays = 6,
            iconName = "bolt",
            sortOrder = 4
        )

        seedDay(template, 1, "Push A", WorkoutType.PUSH, 60, listOf(
            Ex("barbell-bench-press", 4, 6, 10, 120, true, 8.0),
            Ex("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 8, 12, 90, true, 7.0),
            Ex("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0),
            Ex("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0),
            Ex("cable-pushdown", 3, 10, 12, 60, false, 7.0)
        ))

        seedDay(template, 2, "Pull A", WorkoutType.PULL, 60, listOf(
            Ex("barbell-deadlift", 4, 5, 8, 150, true, 8.0),
            Ex("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
            Ex("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 7.0),
            Ex("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
            Ex("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
            Ex("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
        ))

        seedDay(template, 3, "Legs A", WorkoutType.LEGS, 60, listOf(
            Ex("barbell-full-squat", 4, 6, 10, 120, true, 8.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("barbell-lunge", 3, 10, 12, 60, true, 7.0),
            Ex("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
            Ex("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 6.0),
            Ex("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
        ))

        seedDay(template, 4, "Push B", WorkoutType.PUSH, 60, listOf(
            Ex("dumbbell-bench-press", 4, 8, 12, 90, true, 7.0),
            Ex("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
            Ex("dumbbell-front-raise", 3, 12, 15, 45, false, 6.0),
            Ex("cable-pushdown", 3, 10, 12, 60, false, 7.0),
            Ex("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
        ))

        seedDay(template, 5, "Pull B", WorkoutType.PULL, 60, listOf(
            Ex("cable-bar-lateral-pulldown", 4, 8, 12, 90, true, 7.0),
            Ex("barbell-bent-over-row-pronated-grip", 3, 8, 12, 90, true, 7.0),
            Ex("cable-rear-pulldown", 3, 10, 12, 60, true, 7.0),
            Ex("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
            Ex("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
            Ex("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
        ))

        seedDay(template, 6, "Legs B", WorkoutType.LEGS, 60, listOf(
            Ex("barbell-full-squat", 4, 8, 12, 120, true, 7.0),
            Ex("barbell-romanian-deadlift", 3, 8, 12, 90, true, 7.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
            Ex("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 6.0),
            Ex("lying-leg-raise", 3, 12, 15, 45, false, 6.0)
        ))

        logger.info("  -> ppl_classic seeded")
    }

    // ── 5. 상하체 분할 (4일/주) ───────────────────────────────
    private fun seedUpperLowerMuscle() {
        val template = saveTemplate(
            code = "upper_lower_muscle",
            name = "상하체 분할",
            description = "상체·하체를 각각 주 2회씩 훈련하는 4일 분할 프로그램입니다. 근력과 근비대를 균형 있게 발전시킵니다.",
            targetGoal = "muscle_gain",
            targetExperience = "intermediate",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 4,
            iconName = "swap_vert",
            sortOrder = 5
        )

        seedDay(template, 1, "상체 A", WorkoutType.UPPER, 60, listOf(
            Ex("barbell-bench-press", 4, 6, 10, 120, true, 8.0),
            Ex("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
            Ex("dumbbell-seated-shoulder-press", 3, 8, 12, 60, true, 7.0),
            Ex("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
            Ex("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0),
            Ex("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0)
        ))

        seedDay(template, 2, "하체 A", WorkoutType.LOWER, 55, listOf(
            Ex("barbell-full-squat", 4, 6, 10, 120, true, 8.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
            Ex("barbell-lunge", 3, 10, 12, 60, true, 7.0),
            Ex("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
            Ex("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
        ))

        seedDay(template, 3, "상체 B", WorkoutType.UPPER, 60, listOf(
            Ex("barbell-incline-bench-press", 4, 8, 12, 90, true, 7.0),
            Ex("cable-bar-lateral-pulldown", 4, 10, 12, 60, true, 7.0),
            Ex("cable-pulldown", 3, 10, 12, 60, true, 7.0),
            Ex("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
            Ex("cable-pushdown", 3, 10, 12, 60, false, 7.0),
            Ex("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
        ))

        seedDay(template, 4, "하체 B", WorkoutType.LOWER, 55, listOf(
            Ex("barbell-deadlift", 4, 5, 8, 150, true, 8.0),
            Ex("barbell-lunge", 3, 10, 12, 60, true, 7.0),
            Ex("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
            Ex("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
            Ex("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
            Ex("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
        ))

        logger.info("  -> upper_lower_muscle seeded")
    }

    // ── helpers ───────────────────────────────────────────────

    private class TemplateAlreadyExistsException(val code: String) : RuntimeException()

    private data class Ex(
        val slug: String,
        val sets: Int,
        val minReps: Int,
        val maxReps: Int,
        val restSeconds: Int,
        val isCompound: Boolean,
        val targetRPE: Double,
        val notes: String? = null
    )

    private fun saveTemplate(
        code: String,
        name: String,
        description: String,
        targetGoal: String,
        targetExperience: String,
        splitType: SplitType,
        totalDays: Int,
        iconName: String,
        sortOrder: Int
    ): WorkoutPlanTemplate {
        if (templateRepository.findByCode(code) != null) {
            throw TemplateAlreadyExistsException(code)
        }
        return templateRepository.save(
            WorkoutPlanTemplate(
                code = code,
                name = name,
                description = description,
                targetGoal = targetGoal,
                targetExperience = targetExperience,
                splitType = splitType,
                totalDays = totalDays,
                estimatedWeeks = 8,
                iconName = iconName,
                sortOrder = sortOrder,
                sourceType = PlanSourceType.PRESET
            )
        )
    }

    private fun seedDay(
        template: WorkoutPlanTemplate,
        dayNumber: Int,
        dayName: String,
        workoutType: WorkoutType,
        durationMinutes: Int,
        exercises: List<Ex>
    ) {
        val day = templateDayRepository.save(
            TemplateDay(
                template = template,
                dayNumber = dayNumber,
                dayName = dayName,
                workoutType = workoutType,
                estimatedDurationMinutes = durationMinutes
            )
        )

        exercises.forEachIndexed { index, ex ->
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

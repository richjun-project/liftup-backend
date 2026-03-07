package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.context.ApplicationContext
import org.springframework.transaction.annotation.Transactional
import kotlin.system.exitProcess

@Service
class ExerciseCatalogBootstrapService(
    private val objectMapper: ObjectMapper,
    private val exerciseRepository: ExerciseRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val applicationContext: ApplicationContext,
    @Value("\${app.exercise-catalog.mode:if_empty}") private val bootstrapMode: String,
    @Value("\${app.exercise-catalog.resource:catalog/exercise-catalog.json}") private val resourcePath: String,
    @Value("\${app.exercise-catalog.exit-after-bootstrap:false}") private val exitAfterBootstrap: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run(args: ApplicationArguments) {
        val mode = bootstrapMode.trim().lowercase()
        if (mode == "never") {
            logger.info("Exercise catalog bootstrap skipped (mode=never)")
            maybeExit()
            return
        }

        val resource = ClassPathResource(resourcePath)
        if (!resource.exists()) {
            logger.warn("Exercise catalog resource not found: {}", resourcePath)
            maybeExit()
            return
        }

        val exerciseCount = exerciseRepository.count()
        if (mode == "if_empty" && exerciseCount > 0) {
            logger.info("Exercise catalog bootstrap skipped ({} exercises already exist)", exerciseCount)
            maybeExit()
            return
        }

        if (mode == "reset") {
            resetExerciseDomain()
        } else if (exerciseCount > 0) {
            logger.info("Exercise catalog bootstrap skipped (mode={}, exercises={})", mode, exerciseCount)
            maybeExit()
            return
        }

        importCatalog(resource)
        maybeExit()
    }

    @Transactional
    fun importCatalog(resource: ClassPathResource) {
        val catalogType = objectMapper.typeFactory.constructCollectionType(List::class.java, ExerciseCatalogRecord::class.java)
        val catalog: List<ExerciseCatalogRecord> = resource.inputStream.use { inputStream ->
            objectMapper.readValue(inputStream, catalogType)
        }

        if (catalog.isEmpty()) {
            logger.warn("Exercise catalog resource is empty: {}", resourcePath)
            return
        }

        val exercises: List<Exercise> = catalog.map { record: ExerciseCatalogRecord ->
            Exercise(
                slug = record.slug,
                name = record.primaryTranslation().name,
                defaultLocale = record.defaultLocale,
                category = record.category,
                movementPattern = record.movementPattern,
                muscleGroups = record.muscleGroups.toMutableSet(),
                equipment = record.equipment,
                equipmentDetail = record.equipmentDetail,
                sourceCategory = record.sourceCategory,
                instructions = record.primaryTranslation().instructions,
                popularity = record.popularity,
                difficulty = record.difficulty,
                isBasicExercise = record.basicExercise,
                recommendationTier = record.recommendationTier
            )
        }

        val savedExercises: List<Exercise> = exerciseRepository.saveAllAndFlush(exercises).toList()
        val exerciseBySlug = savedExercises.associateBy { it.slug }

        val translations: List<ExerciseTranslation> = catalog.flatMap { record: ExerciseCatalogRecord ->
            val savedExercise = exerciseBySlug.getValue(record.slug)
            record.translations.map { translation: ExerciseCatalogTranslation ->
                ExerciseTranslation(
                    exercise = savedExercise,
                    locale = translation.locale,
                    displayName = translation.name,
                    instructions = translation.instructions,
                    tips = translation.tips
                )
            }
        }

        exerciseTranslationRepository.saveAllAndFlush(translations)
        logger.info("Exercise catalog imported: {} exercises, {} translations", savedExercises.size, translations.size)
    }

    @Transactional
    fun resetExerciseDomain() {
        logger.warn("Resetting workout/exercise domain before importing catalog")

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0")
        try {
            listOf(
                "exercise_translations",
                "exercise_templates",
                "exercise_muscle_groups",
                "exercise_sets",
                "workout_exercises",
                "personal_records",
                "workout_logs",
                "workout_plans",
                "workout_sessions",
                "workout_streaks",
                "muscle_recovery",
                "exercises"
            ).forEach { table ->
                jdbcTemplate.execute("TRUNCATE TABLE $table")
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1")
        }
    }

    private fun maybeExit() {
        if (!exitAfterBootstrap) {
            return
        }

        val exitCode = SpringApplication.exit(applicationContext, { 0 })
        exitProcess(exitCode)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExerciseCatalogRecord(
    val slug: String,
    @param:JsonProperty("defaultLocale")
    val defaultLocale: String = "en",
    val category: ExerciseCategory,
    val equipment: Equipment? = null,
    @param:JsonProperty("equipmentDetail")
    val equipmentDetail: String? = null,
    @param:JsonProperty("sourceCategory")
    val sourceCategory: String? = null,
    @param:JsonProperty("movementPattern")
    val movementPattern: String? = null,
    @param:JsonProperty("muscleGroups")
    val muscleGroups: Set<MuscleGroup> = emptySet(),
    @param:JsonProperty("recommendationTier")
    val recommendationTier: RecommendationTier = RecommendationTier.STANDARD,
    val popularity: Int = 50,
    val difficulty: Int = 50,
    @param:JsonProperty("basicExercise")
    val basicExercise: Boolean = false,
    val translations: List<ExerciseCatalogTranslation> = emptyList()
) {
    fun primaryTranslation(): ExerciseCatalogTranslation {
        return translations.firstOrNull { it.locale == defaultLocale }
            ?: translations.firstOrNull()
            ?: throw IllegalArgumentException("Exercise catalog record $slug does not contain translations")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExerciseCatalogTranslation(
    val locale: String,
    val name: String,
    val instructions: String? = null,
    val tips: String? = null
)

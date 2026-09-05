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
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.context.ApplicationContext
import org.springframework.transaction.annotation.Transactional
import kotlin.system.exitProcess

@Service
@Order(100)
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
            // 운동 데이터는 있지만 큐레이션 필드/번역이 카탈로그와 다를 수 있으므로 항상 싱크.
            // @Transactional 안에서 제약 위반이 나면 내부 catch로도 롤백 표시가 지워지지 않아
            // 커밋 시점에 다시 터진다 → 여기서 막지 않으면 기동 실패로 서비스 전체가 내려간다.
            // 카탈로그 싱크는 부가 작업이므로 실패해도 앱은 떠야 한다.
            try {
                syncCatalog(resource)
            } catch (e: Exception) {
                logger.error("Exercise catalog sync failed — 앱은 계속 기동한다: {}", e.message, e)
            }
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
                instructions = record.primaryTranslation().instructions,
                popularity = record.popularity,
                difficulty = record.difficulty,
                isBasicExercise = record.basicExercise,
                recommendationTier = record.recommendationTier,
                isPlanEligible = record.isPlanEligible,
                planCategory = record.planCategory
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

    /**
     * 카탈로그의 큐레이션 필드와 번역을 기존 운동 행에 반영한다.
     *
     * 카탈로그 JSON이 추천 품질의 단일 진실 소스이므로, 부트스트랩 이후에도
     * 큐레이션 결과(부위/동작패턴/난이도/인기도/추천등급/플랜풀)를 매 기동 시 동기화한다.
     * 이 동기화가 없으면 카탈로그를 고쳐도 이미 시딩된 환경에는 영원히 반영되지 않는다.
     *
     * 사용자 데이터(vectorId, 세션/기록 등)는 건드리지 않는다.
     */
    @Transactional
    fun syncCatalog(resource: ClassPathResource) {
        val catalogType = objectMapper.typeFactory.constructCollectionType(List::class.java, ExerciseCatalogRecord::class.java)
        val catalog: List<ExerciseCatalogRecord> = resource.inputStream.use { objectMapper.readValue(it, catalogType) }

        val allExercises = exerciseRepository.findAll().associateBy { it.slug }
        var updated = 0
        var created = 0

        var curationUpdated = 0
        var failed = 0
        for (record in catalog) {
            val exercise = allExercises[record.slug] ?: continue

            // 큐레이션 필드 동기화 — 추천/플랜 파이프라인이 이 값들로 후보를 고른다
            val needsCurationSync = exercise.category != record.category ||
                exercise.movementPattern != record.movementPattern ||
                exercise.equipment != record.equipment ||
                exercise.difficulty != record.difficulty ||
                exercise.popularity != record.popularity ||
                exercise.recommendationTier != record.recommendationTier ||
                exercise.isBasicExercise != record.basicExercise ||
                exercise.isPlanEligible != record.isPlanEligible ||
                exercise.planCategory != record.planCategory ||
                exercise.muscleGroups != record.muscleGroups

            if (needsCurationSync) {
                // copy() 는 vectorId(var) 까지 그대로 옮기므로 Qdrant 인덱스 연결이 유지된다
                exerciseRepository.save(exercise.copy(
                    category = record.category,
                    movementPattern = record.movementPattern,
                    equipment = record.equipment,
                    difficulty = record.difficulty,
                    popularity = record.popularity,
                    recommendationTier = record.recommendationTier,
                    isBasicExercise = record.basicExercise,
                    isPlanEligible = record.isPlanEligible,
                    planCategory = record.planCategory,
                    muscleGroups = record.muscleGroups.toMutableSet()
                ))
                curationUpdated++
            }

            for (translation in record.translations) {
                // 카탈로그 한 건의 데이터 문제로 서버 기동 전체가 실패하면 안 된다
                // (locale+display_name 유니크 제약 위반으로 프로덕션이 부팅 크래시 루프에 빠진 적이 있다)
                try {
                    val existing = exerciseTranslationRepository.findByExerciseIdAndLocale(exercise.id, translation.locale)
                    if (existing != null) {
                        // 이름뿐 아니라 설명/팁이 새로 생긴 경우도 반영해야 한다
                        // (이름만 비교하면 ko처럼 이름이 이미 있는 로케일에 instructions/tips가 영원히 안 채워진다)
                        val nextInstructions = translation.instructions ?: existing.instructions
                        val nextTips = translation.tips ?: existing.tips
                        if (existing.displayName != translation.name ||
                            existing.instructions != nextInstructions ||
                            existing.tips != nextTips
                        ) {
                            exerciseTranslationRepository.save(existing.copy(
                                displayName = translation.name,
                                instructions = nextInstructions,
                                tips = nextTips
                            ))
                            updated++
                        }
                    } else {
                        exerciseTranslationRepository.save(ExerciseTranslation(
                            exercise = exercise,
                            locale = translation.locale,
                            displayName = translation.name,
                            instructions = translation.instructions,
                            tips = translation.tips
                        ))
                        created++
                    }
                } catch (e: Exception) {
                    failed++
                    logger.error(
                        "Catalog translation sync failed: slug={} locale={} name={} — {}",
                        record.slug, translation.locale, translation.name, e.message
                    )
                }
            }
        }
        if (updated > 0 || created > 0 || curationUpdated > 0 || failed > 0) {
            logger.info(
                "Catalog sync: translations {}/{} updated/created, {} failed, curation fields {} rows updated",
                updated, created, failed, curationUpdated
            )
        }
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
    @param:JsonProperty("isPlanEligible")
    val isPlanEligible: Boolean = false,
    @param:JsonProperty("planCategory")
    val planCategory: String? = null,
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

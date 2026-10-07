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
import com.richjun.liftupai.domain.workout.search.ExerciseCatalogChangedEvent
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

    companion object {
        /** 사라진 slug → 남는 slug (중복 정리 기록). 마이그레이션이 이 목록으로 사용자 기록을 옮긴다 */
        const val DUPLICATE_MAP_RESOURCE = "catalog/duplicate-map.json"
    }

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
        importCatalog(catalog)
    }

    @Transactional
    fun importCatalog(catalog: List<ExerciseCatalogRecord>) {
        if (catalog.isEmpty()) {
            logger.warn("Exercise catalog resource is empty: {}", resourcePath)
            return
        }

        val exercises: List<Exercise> = catalog.map { record: ExerciseCatalogRecord -> newExercise(record) }

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
        applicationContext.publishEvent(ExerciseCatalogChangedEvent())
    }

    /**
     * 카탈로그 JSON 을 운영 DB 에 맞춘다 (멱등).
     *
     * - 카탈로그에만 있는 새 slug 는 운동·근육 태그·번역을 삽입한다 (벡터 인덱스는 vectorId 가 비어 있어 기동 시 벡터 마이그레이션이 채운다).
     * - 이미 있는 slug 는 큐레이션 필드(부위/장비/동작패턴/난이도/인기도/티어/플랜 풀)와 이름·설명, 로케일별 번역을 카탈로그 값으로 갱신한다.
     * - 카탈로그에서 사라진 운동(중복 정리)은 여기서 지우지 않는다. 사용자 기록을 옮기는 일은 db/migration/catalog 의 마이그레이션이 하고,
     *   이 동기화는 남아 있으면 경고만 남긴다. 사용자 데이터(vectorId, 세션/기록 등)는 절대 건드리지 않는다.
     *
     * 자기 호출(run → syncCatalog)이라 트랜잭션으로 묶이지 않고 행마다 확정된다. 그래서 한 건의 데이터 문제가 나머지를 막지 않는다.
     * (locale+display_name 유니크 제약 위반으로 프로덕션이 부팅 크래시 루프에 빠진 적이 있어 번역은 충돌을 미리 계산해 안전한 순서로만 쓴다)
     */
    fun syncCatalog(resource: ClassPathResource): CatalogSyncResult {
        val catalogType = objectMapper.typeFactory.constructCollectionType(List::class.java, ExerciseCatalogRecord::class.java)
        val catalog: List<ExerciseCatalogRecord> = resource.inputStream.use { objectMapper.readValue(it, catalogType) }
        return syncCatalog(catalog)
    }

    fun syncCatalog(catalog: List<ExerciseCatalogRecord>): CatalogSyncResult {
        val existingBySlug = exerciseRepository.findAll().associateBy { it.slug }.toMutableMap()

        // 1) 새 slug 삽입
        val missing = catalog.filter { it.slug !in existingBySlug }
        val missingSlugs = missing.mapTo(HashSet()) { it.slug }
        var created = 0
        if (missing.isNotEmpty()) {
            val saved = exerciseRepository.saveAllAndFlush(missing.map { newExercise(it) })
            saved.forEach { existingBySlug[it.slug] = it }
            created = saved.size
        }

        // 2) 기존 slug 의 큐레이션 필드·이름·설명 갱신
        var curationUpdated = 0
        for (record in catalog) {
            val exercise = existingBySlug[record.slug] ?: continue
            if (record.slug in missingSlugs) continue // 방금 카탈로그 값 그대로 삽입했다
            val primary = record.primaryTranslation()
            val nextInstructions = primary.instructions ?: exercise.instructions
            val needsSync = exercise.category != record.category ||
                exercise.movementPattern != record.movementPattern ||
                exercise.equipment != record.equipment ||
                exercise.difficulty != record.difficulty ||
                exercise.popularity != record.popularity ||
                exercise.recommendationTier != record.recommendationTier ||
                exercise.isBasicExercise != record.basicExercise ||
                exercise.isPlanEligible != record.isPlanEligible ||
                exercise.planCategory != record.planCategory ||
                exercise.muscleGroups != record.muscleGroups ||
                exercise.name != primary.name ||
                exercise.defaultLocale != record.defaultLocale ||
                exercise.instructions != nextInstructions
            if (!needsSync) continue
            try {
                // copy() 는 vectorId(var) 까지 그대로 옮기므로 Qdrant 인덱스 연결이 유지된다
                exerciseRepository.save(exercise.copy(
                    name = primary.name,
                    defaultLocale = record.defaultLocale,
                    category = record.category,
                    movementPattern = record.movementPattern,
                    equipment = record.equipment,
                    instructions = nextInstructions,
                    difficulty = record.difficulty,
                    popularity = record.popularity,
                    recommendationTier = record.recommendationTier,
                    isBasicExercise = record.basicExercise,
                    isPlanEligible = record.isPlanEligible,
                    planCategory = record.planCategory,
                    muscleGroups = record.muscleGroups.toMutableSet()
                ))
                curationUpdated++
            } catch (e: Exception) {
                logger.error("Catalog exercise sync failed: slug={} — {}", record.slug, e.message)
            }
        }

        // 3) 번역
        val tx = syncTranslations(catalog, existingBySlug)

        // 4) 중복 정리 마이그레이션이 아직 안 돈 환경 경고
        val stale = staleDuplicateSlugs(existingBySlug.keys)
        if (stale.isNotEmpty()) {
            logger.warn("Catalog sync: 중복 정리 마이그레이션(V20261008100)이 적용되지 않은 운동 {}개가 남아 있다: {}", stale.size, stale.take(10))
        }

        val result = CatalogSyncResult(
            exercisesCreated = created,
            exercisesUpdated = curationUpdated,
            translationsCreated = tx.created,
            translationsUpdated = tx.updated,
            translationsFailed = tx.failed,
            staleDuplicates = stale.size
        )
        if (result.changed) {
            // 이름/큐레이션 필드가 바뀌었으니 검색 인덱스를 다시 만들게 한다
            applicationContext.publishEvent(ExerciseCatalogChangedEvent())
        }
        if (result.changed || result.translationsFailed > 0) {
            logger.info(
                "Catalog sync: exercises {} created/{} updated, translations {} created/{} updated/{} failed",
                result.exercisesCreated, result.exercisesUpdated, result.translationsCreated, result.translationsUpdated, result.translationsFailed
            )
        }
        return result
    }

    private fun newExercise(record: ExerciseCatalogRecord): Exercise {
        val primary = record.primaryTranslation()
        return Exercise(
            slug = record.slug,
            name = primary.name,
            defaultLocale = record.defaultLocale,
            category = record.category,
            movementPattern = record.movementPattern,
            muscleGroups = record.muscleGroups.toMutableSet(),
            equipment = record.equipment,
            instructions = primary.instructions,
            popularity = record.popularity,
            difficulty = record.difficulty,
            isBasicExercise = record.basicExercise,
            recommendationTier = record.recommendationTier,
            isPlanEligible = record.isPlanEligible,
            planCategory = record.planCategory
        )
    }

    private data class TranslationRow(
        val id: Long,
        val exerciseId: Long,
        val locale: String,
        val name: String,
        val instructions: String?,
        val tips: String?
    )

    private data class TranslationChange(
        val exerciseId: Long,
        val locale: String,
        val row: TranslationRow?,
        val name: String,
        val instructions: String?,
        val tips: String?,
        val slug: String
    )

    private data class TranslationSyncCounts(var created: Int = 0, var updated: Int = 0, var failed: Int = 0)

    /**
     * 번역 행을 카탈로그와 맞춘다. DB 에는 (locale, display_name) 유니크 제약이 있어서, 이름을 바꾸거나 새로 넣을 때
     * 그 이름을 지금 다른 운동이 쓰고 있으면 충돌한다. 그 다른 운동도 곧 이름이 바뀔 예정이면 먼저 바뀌게 순서를 잡고(고정점 반복),
     * 끝까지 풀리지 않는 충돌(진짜 데이터 충돌)은 건너뛰고 로그만 남긴다.
     */
    private fun syncTranslations(catalog: List<ExerciseCatalogRecord>, exerciseBySlug: Map<String, Exercise>): TranslationSyncCounts {
        val counts = TranslationSyncCounts()
        val rows = jdbcTemplate.query(
            "SELECT id, exercise_id, locale, display_name, instructions, tips FROM exercise_translations"
        ) { rs, _ ->
            TranslationRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }
        val rowByKey = HashMap<Pair<Long, String>, TranslationRow>(rows.size * 2)
        val ownerByName = HashMap<Pair<String, String>, Long>(rows.size * 2)
        for (row in rows) {
            rowByKey[row.exerciseId to row.locale] = row
            ownerByName[row.locale to row.name] = row.exerciseId
        }

        val pending = ArrayList<TranslationChange>()
        for (record in catalog) {
            val exercise = exerciseBySlug[record.slug] ?: continue
            for (t in record.translations) {
                val row = rowByKey[exercise.id to t.locale]
                if (row == null) {
                    pending += TranslationChange(exercise.id, t.locale, null, t.name, t.instructions, t.tips, record.slug)
                    continue
                }
                // 이름뿐 아니라 설명/팁이 새로 생긴 경우도 반영해야 한다
                // (이름만 비교하면 ko처럼 이름이 이미 있는 로케일에 instructions/tips가 영원히 안 채워진다)
                val nextInstructions = t.instructions ?: row.instructions
                val nextTips = t.tips ?: row.tips
                if (row.name != t.name || row.instructions != nextInstructions || row.tips != nextTips) {
                    pending += TranslationChange(exercise.id, t.locale, row, t.name, nextInstructions, nextTips, record.slug)
                }
            }
        }

        fun apply(change: TranslationChange): Boolean {
            try {
                val row = change.row
                if (row == null) {
                    jdbcTemplate.update(
                        "INSERT INTO exercise_translations (exercise_id, locale, display_name, instructions, tips) VALUES (?, ?, ?, ?, ?)",
                        change.exerciseId, change.locale, change.name, change.instructions, change.tips
                    )
                    counts.created++
                } else {
                    jdbcTemplate.update(
                        "UPDATE exercise_translations SET display_name = ?, instructions = ?, tips = ? WHERE id = ?",
                        change.name, change.instructions, change.tips, row.id
                    )
                    ownerByName.remove(row.locale to row.name)
                    counts.updated++
                }
                ownerByName[change.locale to change.name] = change.exerciseId
                return true
            } catch (e: Exception) {
                counts.failed++
                logger.error(
                    "Catalog translation sync failed: slug={} locale={} name={} — {}",
                    change.slug, change.locale, change.name, e.message
                )
                return false
            }
        }

        var queue: List<TranslationChange> = pending
        while (queue.isNotEmpty()) {
            val deferred = ArrayList<TranslationChange>()
            var progressed = false
            for (change in queue) {
                val owner = ownerByName[change.locale to change.name]
                val nameFree = owner == null || owner == change.exerciseId
                val renames = change.row == null || change.row.name != change.name
                if (renames && !nameFree) {
                    deferred += change
                    continue
                }
                progressed = true
                apply(change)
            }
            if (!progressed) {
                deferred.forEach {
                    counts.failed++
                    logger.error(
                        "Catalog translation sync skipped (name already used by exercise {}): slug={} locale={} name={}",
                        ownerByName[it.locale to it.name], it.slug, it.locale, it.name
                    )
                }
                break
            }
            queue = deferred
        }
        return counts
    }

    /** duplicate-map.json 의 사라진 slug 가 DB 에 아직 남아 있으면(마이그레이션 미적용) 그 slug 목록 */
    private fun staleDuplicateSlugs(existingSlugs: Set<String>): List<String> {
        val resource = ClassPathResource(DUPLICATE_MAP_RESOURCE)
        if (!resource.exists()) return emptyList()
        return try {
            val map: Map<String, String> = resource.inputStream.use {
                objectMapper.readValue(it, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java))
            }
            map.keys.filter { it in existingSlugs }.sorted()
        } catch (e: Exception) {
            logger.warn("duplicate-map.json 을 읽지 못했다: {}", e.message)
            emptyList()
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

/** 카탈로그 동기화 결과 — 같은 카탈로그를 다시 동기화하면 변경 수가 모두 0 이어야 한다(멱등) */
data class CatalogSyncResult(
    val exercisesCreated: Int,
    val exercisesUpdated: Int,
    val translationsCreated: Int,
    val translationsUpdated: Int,
    val translationsFailed: Int,
    val staleDuplicates: Int
) {
    val changed: Boolean
        get() = exercisesCreated > 0 || exercisesUpdated > 0 || translationsCreated > 0 || translationsUpdated > 0
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

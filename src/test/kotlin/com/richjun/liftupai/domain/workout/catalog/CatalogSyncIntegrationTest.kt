package com.richjun.liftupai.domain.workout.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.config.TestConfig
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseSubstitution
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.PersonalRecord
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.entity.SubstitutionReason
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSubstitutionRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.CatalogSyncResult
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogBootstrapService
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogRecord
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogTranslation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.util.zip.GZIPInputStream
import javax.sql.DataSource

/**
 * 운영 DB(이전 카탈로그가 시딩된 상태)에 새 카탈로그를 동기화했을 때의 결과를 증명한다.
 *  - 새 slug 가 삽입되고, 기존 slug 의 메타데이터·번역이 갱신되고, 같은 카탈로그를 다시 돌려도 변하는 게 없다(멱등).
 *  - 중복 정리 마이그레이션(V20261008100)이 사용자 기록을 표준형으로 옮긴 뒤 중복을 지운다.
 *    이식 가능한 SQL 이라 H2(MODE=MySQL)에서 같은 파일을 그대로 실행한다.
 *
 * 이전 카탈로그는 기준 커밋 bcde543 의 exercise-catalog.json 을 압축해 둔 scripts/catalog/previous-catalog-bcde543.json.gz 에서 읽는다
 * (git 이력이 없는 CI 에서도 같은 시딩이 되도록 저장소에 스냅샷을 둔다).
 * 다른 통합 테스트와 DB·컨텍스트를 공유하지 않도록 별도 인메모리 DB 를 쓰고 끝나면 컨텍스트를 버린다.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:catalogsync;DB_CLOSE_DELAY=-1;MODE=MYSQL",
        "app.exercise-catalog.mode=never"
    ]
)
@ActiveProfiles("test")
@Import(TestConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogSyncIntegrationTest {

    @Autowired private lateinit var bootstrap: ExerciseCatalogBootstrapService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var personalRecordRepository: PersonalRecordRepository
    @Autowired private lateinit var substitutionRepository: ExerciseSubstitutionRepository

    private val catalogResource = ClassPathResource("catalog/exercise-catalog.json")
    private val migrationResource = ClassPathResource("db/migration/catalog/V20261008100__catalog_dedup_round4.sql")

    private fun readCatalog(stream: java.io.InputStream): List<ExerciseCatalogRecord> =
        stream.use {
            objectMapper.readValue(
                it, objectMapper.typeFactory.constructCollectionType(List::class.java, ExerciseCatalogRecord::class.java)
            )
        }

    private val previousCatalog: List<ExerciseCatalogRecord> by lazy {
        val gz = File("scripts/catalog/previous-catalog-bcde543.json.gz")
        assertTrue(gz.exists(), "이전 카탈로그 스냅샷이 없다: ${gz.absolutePath}")
        readCatalog(GZIPInputStream(gz.inputStream()))
    }
    private val newCatalog: List<ExerciseCatalogRecord> by lazy { readCatalog(catalogResource.inputStream) }
    private val duplicateMap: Map<String, String> by lazy {
        objectMapper.readValue(
            ClassPathResource("catalog/duplicate-map.json").inputStream,
            objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)
        )
    }

    /** 운영과 같은 모양으로 맞춘다: 이전 카탈로그 시딩 + (locale, display_name) 유니크 제약(V20260711002) */
    @BeforeEach
    fun seedPreviousCatalog() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE")
        jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
            String::class.java
        ).forEach { jdbc.execute("TRUNCATE TABLE \"$it\"") }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE")
        val hasUnique = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_NAME = 'UK_EXERCISE_TRANSLATIONS_LOCALE_NAME'",
            Int::class.java
        ) ?: 0
        if (hasUnique == 0) {
            jdbc.execute("ALTER TABLE exercise_translations ADD CONSTRAINT uk_exercise_translations_locale_name UNIQUE (locale, display_name)")
        }
        bootstrap.importCatalog(previousCatalog)
    }

    private fun applyMigration() {
        ResourceDatabasePopulator(migrationResource).execute(dataSource)
    }

    private fun exerciseCount() = jdbc.queryForObject("SELECT COUNT(*) FROM exercises", Int::class.java)!!

    private fun translationNames(slug: String): Map<String, String> =
        jdbc.query(
            "SELECT t.locale, t.display_name FROM exercise_translations t JOIN exercises e ON e.id = t.exercise_id WHERE e.slug = ?",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, slug
        ).toMap()

    @Test
    fun `이전 카탈로그가 든 DB 에 동기화하면 새 운동이 삽입되고 중복은 정리돼 행 수가 새 카탈로그와 같다`() {
        val oldSlugs = previousCatalog.map { it.slug }.toSet()
        val newSlugs = newCatalog.map { it.slug }.toSet()
        val added = newSlugs - oldSlugs
        assertEquals(previousCatalog.size, exerciseCount())
        assertTrue(added.size >= 50, "새 운동이 충분히 추가돼야 한다: ${added.size}")

        applyMigration()
        val result = bootstrap.syncCatalog(catalogResource)

        assertEquals(newCatalog.size, exerciseCount(), "마이그레이션(삭제) + 동기화(삽입) 뒤 행 수가 새 카탈로그와 같아야 한다")
        assertEquals(added.size, result.exercisesCreated)
        assertEquals(0, result.translationsFailed)
        assertEquals(0, result.staleDuplicates)
        assertEquals(newCatalog.sumOf { it.translations.size }, jdbc.queryForObject("SELECT COUNT(*) FROM exercise_translations", Int::class.java))

        // 대표 운동: 레그 익스텐션 — ko/ja/en 이름과 설명·팁, 주동근, 티어
        val legExt = exerciseRepository.findBySlug("leg-extension-machine")!!
        assertEquals(ExerciseCategory.LEGS, legExt.category)
        assertEquals(RecommendationTier.ESSENTIAL, legExt.recommendationTier)
        assertEquals(setOf(MuscleGroup.QUADRICEPS), legExt.muscleGroups.toSet())
        assertEquals(
            mapOf("en" to "Leg extension machine", "ko" to "레그 익스텐션 머신", "ja" to "レッグエクステンション（マシン）"),
            translationNames("leg-extension-machine")
        )
        val tipsRows = jdbc.queryForList(
            "SELECT t.instructions, t.tips FROM exercise_translations t JOIN exercises e ON e.id = t.exercise_id WHERE e.slug = 'leg-extension-machine'"
        )
        assertEquals(3, tipsRows.size)
        tipsRows.forEach {
            assertTrue((it["INSTRUCTIONS"] as String).startsWith("1. "), it.toString())
            assertTrue((it["TIPS"] as String).isNotBlank())
        }
    }

    @Test
    fun `기존 slug 의 메타데이터와 번역이 새 값으로 갱신되고 벡터 연결은 유지된다`() {
        val kickbackBefore = exerciseRepository.findBySlug("dumbbell-kickback")!!
        assertEquals(ExerciseCategory.LEGS, kickbackBefore.category)
        val squat = exerciseRepository.findBySlug("barbell-full-squat")!!
        squat.vectorId = "vector-keep-me"
        exerciseRepository.save(squat)
        val squatBeforeName = translationNames("barbell-full-squat")
        assertEquals("바벨 풀 스쿼트", squatBeforeName["ko"])

        applyMigration()
        val result = bootstrap.syncCatalog(catalogResource)

        assertTrue(result.exercisesUpdated > 100, "메타데이터가 바뀐 운동이 많다: ${result.exercisesUpdated}")
        assertTrue(result.translationsUpdated > 0)
        val kickback = exerciseRepository.findBySlug("dumbbell-kickback")!!
        assertEquals(ExerciseCategory.ARMS, kickback.category, "삼두 킥백은 팔 운동이다")
        assertEquals(setOf(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS), kickback.muscleGroups.toSet())

        val bike = exerciseRepository.findBySlug("stationary-exercise-bike")!!
        assertEquals(ExerciseCategory.CARDIO, bike.category)
        assertEquals("Stationary bike", bike.name)

        val updated = exerciseRepository.findBySlug("barbell-full-squat")!!
        assertEquals("Barbell full squat", updated.name)
        assertEquals("vector-keep-me", updated.vectorId, "동기화는 벡터 인덱스 연결을 건드리지 않는다")
        val names = translationNames("barbell-full-squat")
        assertEquals("바벨 백 스쿼트", names["ko"])
        assertEquals("バーベル・バックスクワット", names["ja"])

        // 카탈로그의 모든 기존 slug 가 카탈로그 값과 같아졌는지 전수 확인
        val bySlug = exerciseRepository.findAll().associateBy { it.slug }
        newCatalog.forEach { record ->
            val e = bySlug.getValue(record.slug)
            assertEquals(record.category, e.category, record.slug)
            assertEquals(record.popularity, e.popularity, record.slug)
            assertEquals(record.recommendationTier, e.recommendationTier, record.slug)
            assertEquals(record.isPlanEligible, e.isPlanEligible, record.slug)
            assertEquals(record.muscleGroups, e.muscleGroups.toSet(), record.slug)
            assertEquals(record.translations.associate { it.locale to it.name }, translationNames(record.slug), record.slug)
        }
    }

    @Test
    fun `같은 카탈로그를 다시 동기화해도 아무것도 바뀌지 않는다(멱등)`() {
        applyMigration()
        val first = bootstrap.syncCatalog(catalogResource)
        assertTrue(first.changed)
        val countAfterFirst = exerciseCount()

        val second = bootstrap.syncCatalog(catalogResource)

        assertEquals(CatalogSyncResult(0, 0, 0, 0, 0, 0), second)
        assertFalse(second.changed)
        assertEquals(countAfterFirst, exerciseCount())
    }

    @Test
    fun `중복 운동에 쌓인 사용자 기록은 표준형 운동으로 옮겨지고 중복 행은 지워진다`() {
        val user = userRepository.save(User(email = "dedup@example.com", nickname = "dedup"))
        val session = sessionRepository.save(WorkoutSession(user = user))
        val dupLat = exerciseRepository.findBySlug("lat-pull-down-normal-grip")!!
        val keptLat = exerciseRepository.findBySlug("cable-pulldown")!!
        val dupFly = exerciseRepository.findBySlug("dumbbell-fly-incline-bench")!!
        val keptFly = exerciseRepository.findBySlug("dumbbell-incline-fly")!!
        assertEquals("cable-pulldown", duplicateMap.getValue("lat-pull-down-normal-grip"))

        val historyOnDuplicate = workoutExerciseRepository.save(WorkoutExercise(session = session, exercise = dupLat, orderInSession = 1))
        val historyOnKept = workoutExerciseRepository.save(WorkoutExercise(session = session, exercise = keptLat, orderInSession = 2))
        val prOnDuplicate = personalRecordRepository.save(PersonalRecord(user = user, exercise = dupFly, weight = 30.0, reps = 8))
        // 중복 → 표준형 대체 기록은 합쳐지면 자기 자신을 가리키므로 사라져야 하고, 다른 기록은 표준형으로 옮겨져야 한다
        val selfAfterMerge = substitutionRepository.save(ExerciseSubstitution(originalExercise = dupLat, substituteExercise = keptLat, reason = SubstitutionReason.EQUIVALENT))
        val other = exerciseRepository.findBySlug("barbell-bench-press")!!
        val substOnDuplicate = substitutionRepository.save(ExerciseSubstitution(originalExercise = other, substituteExercise = dupFly, reason = SubstitutionReason.PREFERENCE))
        val userRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM workout_exercises", Int::class.java)
        val dupIds = listOf(dupLat.id, dupFly.id)

        applyMigration()
        bootstrap.syncCatalog(catalogResource)

        fun slugOf(sql: String, id: Long) = jdbc.queryForObject(sql, String::class.java, id)
        assertEquals(
            "cable-pulldown",
            slugOf("SELECT e.slug FROM workout_exercises we JOIN exercises e ON e.id = we.exercise_id WHERE we.id = ?", historyOnDuplicate.id),
            "중복에 쌓인 운동 기록은 표준형으로 옮겨져야 한다"
        )
        assertEquals(
            "cable-pulldown",
            slugOf("SELECT e.slug FROM workout_exercises we JOIN exercises e ON e.id = we.exercise_id WHERE we.id = ?", historyOnKept.id)
        )
        assertEquals(
            "dumbbell-incline-fly",
            slugOf("SELECT e.slug FROM personal_records p JOIN exercises e ON e.id = p.exercise_id WHERE p.id = ?", prOnDuplicate.id)
        )
        assertEquals(
            "dumbbell-incline-fly",
            slugOf("SELECT e.slug FROM exercise_substitutions s JOIN exercises e ON e.id = s.substitute_exercise_id WHERE s.id = ?", substOnDuplicate.id)
        )
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM exercise_substitutions WHERE id = ?", Int::class.java, selfAfterMerge.id), "자기 자신을 가리키게 된 대체 기록은 지워진다")
        assertEquals(userRowsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM workout_exercises", Int::class.java), "사용자 기록 행은 하나도 사라지지 않는다")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM personal_records", Int::class.java))

        // 중복 운동 행과 부속 행은 사라졌고 표준형은 남았다
        dupIds.forEach {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM exercises WHERE id = ?", Int::class.java, it))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM exercise_translations WHERE exercise_id = ?", Int::class.java, it))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM exercise_muscle_groups WHERE exercise_id = ?", Int::class.java, it))
        }
        assertNotNull(exerciseRepository.findBySlug("cable-pulldown"))
        assertNull(exerciseRepository.findBySlug("lat-pull-down-normal-grip"))
        assertEquals(keptLat.id, exerciseRepository.findBySlug("cable-pulldown")!!.id, "표준형 id 는 그대로다")
        assertEquals(keptFly.id, exerciseRepository.findBySlug("dumbbell-incline-fly")!!.id)
    }

    @Test
    fun `마이그레이션은 DB 스키마에서 exercises 를 가리키는 모든 FK 를 처리한다`() {
        val sql = migrationResource.inputStream.bufferedReader().readText()
        val handledByDelete = setOf("exercise_translations", "exercise_muscle_groups")
        val referencing = mutableSetOf<Pair<String, String>>()
        dataSource.connection.use { conn ->
            conn.metaData.getExportedKeys(null, null, "EXERCISES").use { rs ->
                while (rs.next()) referencing += rs.getString("FKTABLE_NAME").lowercase() to rs.getString("FKCOLUMN_NAME").lowercase()
            }
        }
        assertTrue(referencing.size >= 12, "스키마에서 exercises 를 가리키는 컬럼을 읽어야 한다: $referencing")
        referencing.filter { it.first !in handledByDelete }.forEach { (table, column) ->
            val repoint = Regex("UPDATE\\s+$table\\s+SET\\s+$column\\s*=", RegexOption.IGNORE_CASE)
            assertTrue(repoint.containsMatchIn(sql), "마이그레이션이 $table.$column 을 표준형으로 옮기지 않는다")
        }
        handledByDelete.forEach {
            assertTrue(Regex("DELETE\\s+FROM\\s+$it", RegexOption.IGNORE_CASE).containsMatchIn(sql), "$it 행을 지우지 않는다")
        }
    }

    @Test
    fun `마이그레이션의 slug 쌍은 duplicate-map json 과 정확히 같다`() {
        val sql = migrationResource.inputStream.bufferedReader().readText()
        val values = sql.substringAfter("INSERT INTO catalog_dedup_slugs").substringBefore(";")
        val pairs = Regex("'([^']+)',\\s*'([^']+)'").findAll(values).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(duplicateMap, pairs)
        val newSlugs = newCatalog.map { it.slug }.toSet()
        val oldSlugs = previousCatalog.map { it.slug }.toSet()
        duplicateMap.forEach { (removed, kept) ->
            assertTrue(removed in oldSlugs && removed !in newSlugs, "사라지는 slug 는 이전 카탈로그에만 있다: $removed")
            assertTrue(kept in oldSlugs && kept in newSlugs, "남는 slug 는 운영에 이미 있고 새 카탈로그에도 있다: $kept")
        }
    }

    @Test
    fun `이름 유니크 제약이 있어도 이름이 서로 이어서 바뀌는 번역을 충돌 없이 반영한다`() {
        fun record(slug: String, en: String) = ExerciseCatalogRecord(
            slug = slug, category = ExerciseCategory.CORE, muscleGroups = setOf(MuscleGroup.ABS),
            translations = listOf(ExerciseCatalogTranslation("en", en, "1. do it", "1. tip"))
        )
        // A:"Name X" B:"Name Y" 가 있는 DB 에서 A->"Name Y", B->"Name Z" (B 가 먼저 비켜야 A 가 들어간다), 신규 C->"Name X"
        bootstrap.syncCatalog(listOf(record("chain-a", "Name X"), record("chain-b", "Name Y")))
        assertEquals("Name X", translationNames("chain-a")["en"])

        val result = bootstrap.syncCatalog(listOf(record("chain-a", "Name Y"), record("chain-b", "Name Z"), record("chain-c", "Name X")))

        assertEquals(0, result.translationsFailed)
        assertEquals("Name Y", translationNames("chain-a")["en"])
        assertEquals("Name Z", translationNames("chain-b")["en"])
        assertEquals("Name X", translationNames("chain-c")["en"])
        assertEquals(1, result.exercisesCreated)
    }
}

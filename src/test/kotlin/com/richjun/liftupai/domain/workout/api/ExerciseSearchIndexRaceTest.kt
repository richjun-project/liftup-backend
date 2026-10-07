package com.richjun.liftupai.domain.workout.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.ExerciseCatalogChangedEvent
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 검색 인덱스 캐시의 경합·장애 동작: 커밋 전 데이터를 남기지 않는다, 재빌드 실패 시 이전 인덱스를 유지한다,
 * 기동 직후 미리 만든다, TTL 갱신은 요청을 막지 않는다.
 */
class ExerciseSearchIndexRaceTest : ApiTestSupport() {

    @Autowired private lateinit var realIndex: ExerciseSearchIndexService
    @Autowired private lateinit var realCatalogCache: ExerciseCatalogCache
    @Autowired private lateinit var publisher: ApplicationEventPublisher
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private fun exercise(id: Long, name: String, category: ExerciseCategory = ExerciseCategory.CHEST) = Exercise(
        id = id, slug = name.lowercase().replace(' ', '-'), name = name, category = category, equipment = Equipment.BARBELL,
        muscleGroups = mutableSetOf(MuscleGroup.CHEST), popularity = 80, recommendationTier = RecommendationTier.STANDARD
    )

    private val squat = exercise(1, "Barbell squat", ExerciseCategory.LEGS)
    private val bench = exercise(2, "Barbell bench press")
    private val legPress = exercise(3, "Leg press", ExerciseCategory.LEGS)

    private fun service(repo: ExerciseRepository): ExerciseSearchIndexService {
        val translations = mockk<ExerciseTranslationRepository>()
        every { translations.findAllNameRows() } returns emptyList()
        return ExerciseSearchIndexService(repo, translations)
    }

    private fun capture(loggerName: String, block: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try { block(appender) } finally { logger.detachAppender(appender) }
    }

    // ── 커밋 전 데이터로 만든 빌드는 캐시하지 않는다 ───────────────────────────────────────────────

    @Test
    fun `빌드가 도는 사이 카탈로그 변경이 커밋되면 그 빌드 결과는 캐시하지 않고 새 데이터로 다시 만든다`() {
        val repo = mockk<ExerciseRepository>()
        val committed = AtomicReference(listOf(squat))
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { repo.findAllWithMuscleGroups() } answers {
            val readView = committed.get() // 읽는 시점의 DB 상태(아직 커밋 전 데이터)
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            readView
        }
        val service = service(repo)

        val pool = Executors.newSingleThreadExecutor()
        try {
            val firstRequest = pool.submit<List<Long>> { service.searchIds("bench press", null, null, "en") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            committed.set(listOf(squat, bench)) // 카탈로그 변경이 커밋되고
            service.invalidate()             // 커밋 후 이벤트가 온다
            release.countDown()

            assertEquals(listOf(2L), firstRequest.get(5, TimeUnit.SECONDS), "낡은 빌드가 아니라 새 데이터로 다시 만든 결과여야 한다")
            assertEquals(2, calls.get())
            // 이후 요청은 캐시된 새 인덱스를 쓴다 — 더 빌드하지 않는다
            assertEquals(listOf(2L), service.searchIds("bench press", null, null, "en"))
            assertEquals(2, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `이벤트 직후 동시 요청이 몰려도 빌드는 한 번이다`() {
        val repo = mockk<ExerciseRepository>()
        val calls = AtomicInteger()
        every { repo.findAllWithMuscleGroups() } answers { calls.incrementAndGet(); Thread.sleep(30); listOf(squat, bench) }
        val service = service(repo)
        service.engine()
        calls.set(0)

        service.invalidate()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..16).map { pool.submit<Int> { service.searchIds("squat", null, null, "en").size } }
            futures.forEach { assertEquals(1, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `이벤트는 커밋 후에만 반영된다 - 트랜잭션 안에서는 캐시를 지우지 않고 롤백이면 영영 지우지 않는다`() {
        realIndex.engine()
        realCatalogCache.get()
        val builds = realIndex.buildCount.get()
        val catalogBefore = realCatalogCache.get()
        val tx = TransactionTemplate(transactionManager)

        // 커밋 전: 이벤트를 발행해도 아직 무효화되지 않는다(이 시점에 재빌드하면 커밋 전 데이터를 캐시하게 된다)
        tx.executeWithoutResult {
            publisher.publishEvent(ExerciseCatalogChangedEvent())
            realIndex.engine()
            assertSame(catalogBefore, realCatalogCache.get())
            assertEquals(builds, realIndex.buildCount.get(), "커밋 전에는 다시 만들지 않는다")
        }

        // 커밋 후: 다음 요청이 새로 만든다
        realIndex.engine()
        assertEquals(builds + 1, realIndex.buildCount.get())
        assertNotSame(catalogBefore, realCatalogCache.get())

        // 롤백이면 이벤트가 전달되지 않는다
        val builds2 = realIndex.buildCount.get()
        val catalogBefore2 = realCatalogCache.get()
        tx.executeWithoutResult { status ->
            publisher.publishEvent(ExerciseCatalogChangedEvent())
            status.setRollbackOnly()
        }
        realIndex.engine()
        assertEquals(builds2, realIndex.buildCount.get())
        assertSame(catalogBefore2, realCatalogCache.get())
    }

    @Test
    fun `트랜잭션 밖에서 발행한 이벤트도 바로 반영된다`() {
        realIndex.engine()
        val builds = realIndex.buildCount.get()
        publisher.publishEvent(ExerciseCatalogChangedEvent())
        realIndex.engine()
        assertEquals(builds + 1, realIndex.buildCount.get())
    }

    // ── 재빌드 실패 시 이전 인덱스 유지 ─────────────────────────────────────────────────────────────

    @Test
    fun `재빌드가 실패하면 이전 인덱스를 계속 쓰고 WARN 을 남기며 장애 중에는 DB 를 두드리지 않는다`() {
        val repo = mockk<ExerciseRepository>()
        val fail = AtomicBoolean(false)
        val calls = AtomicInteger()
        every { repo.findAllWithMuscleGroups() } answers {
            calls.incrementAndGet()
            if (fail.get()) throw IllegalStateException("db down")
            listOf(squat, bench)
        }
        var now = 5_000_000L
        val service = service(repo).also { it.clock = { now } }
        assertEquals(listOf(2L), service.searchIds("bench", null, null, "en"))

        fail.set(true)
        service.invalidate()
        capture(ExerciseSearchIndexService::class.java.name) { appender ->
            repeat(5) { assertEquals(listOf(2L), service.searchIds("bench", null, null, "en"), "이전 인덱스로 계속 응답") }
            val warns = appender.list.filter { it.level == Level.WARN }
            assertTrue(warns.isNotEmpty(), "재빌드 실패는 WARN 으로 남긴다")
            assertTrue(warns.first().formattedMessage.contains("db down"))
            assertTrue(appender.list.none { it.level.isGreaterOrEqual(Level.ERROR) })
        }
        assertEquals(2, calls.get(), "첫 빌드 + 실패한 재빌드 1회 — 백오프 동안은 다시 시도하지 않는다")

        // DB 가 돌아오고 백오프가 지나면 새 데이터로 복구된다
        fail.set(false)
        now += 31_000 // 백오프(30초) 경과
        service.invalidate()
        assertEquals(listOf(2L), service.searchIds("bench", null, null, "en"))
        assertEquals(3, calls.get())
    }

    @Test
    fun `이전 인덱스가 없을 때의 첫 빌드 실패는 예외로 알린다`() {
        val repo = mockk<ExerciseRepository>()
        every { repo.findAllWithMuscleGroups() } throws IllegalStateException("db down")
        assertThrows(IllegalStateException::class.java) { service(repo).engine() }
    }

    // ── 기동 예열 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ApplicationReadyEvent 를 받으면 검색 전에 인덱스를 미리 만든다`() {
        val repo = mockk<ExerciseRepository>()
        every { repo.findAllWithMuscleGroups() } returns listOf(squat, bench, legPress)
        val service = service(repo)

        AnnotationConfigApplicationContext().use { ctx ->
            ctx.registerBean(ExerciseSearchIndexService::class.java, java.util.function.Supplier { service })
            ctx.refresh()
            verify(exactly = 0) { repo.findAllWithMuscleGroups() }
            ctx.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), ctx, Duration.ZERO))
        }
        verify(exactly = 1) { repo.findAllWithMuscleGroups() }
        assertEquals(1, service.buildCount.get())
        service.searchIds("squat", null, null, "en")
        verify(exactly = 1) { repo.findAllWithMuscleGroups() } // 첫 요청은 이미 데워진 인덱스를 쓴다
    }

    @Test
    fun `예열이 실패해도 앱 기동을 막지 않는다`() {
        val repo = mockk<ExerciseRepository>()
        every { repo.findAllWithMuscleGroups() } throws IllegalStateException("db not ready")
        capture(ExerciseSearchIndexService::class.java.name) { appender ->
            service(repo).warmUp()
            assertTrue(appender.list.any { it.level == Level.WARN })
        }
    }

    @Test
    fun `실제 컨텍스트의 인덱스와 카탈로그 캐시는 기동 직후 이미 만들어져 있다`() {
        assertTrue(realIndex.buildCount.get() >= 1)
    }

    // ── TTL 갱신은 요청을 막지 않는다 ───────────────────────────────────────────────────────────────

    @Test
    fun `TTL 이 지나면 이전 인덱스로 즉시 응답하고 백그라운드에서 갱신한다`() {
        val repo = mockk<ExerciseRepository>()
        val data = AtomicReference(listOf(squat))
        val calls = AtomicInteger()
        val gate = CountDownLatch(1)
        every { repo.findAllWithMuscleGroups() } answers {
            if (calls.incrementAndGet() > 1) gate.await(5, TimeUnit.SECONDS) // 갱신 빌드는 느리다
            data.get()
        }
        var now = 1_000_000L
        val service = service(repo).also { it.clock = { now } }
        assertEquals(listOf(1L), service.searchIds("squat", null, null, "en"))

        now += 11 * 60 * 1000 // TTL(10분) 경과
        data.set(listOf(squat, bench))
        val started = System.nanoTime()
        assertEquals(listOf(1L), service.searchIds("squat", null, null, "en"), "갱신을 기다리지 않고 이전 인덱스로 답한다")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)

        gate.countDown()
        val deadline = System.currentTimeMillis() + 5000
        while (service.searchIds("bench", null, null, "en").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf(2L), service.searchIds("bench", null, null, "en"), "백그라운드 갱신 후 새 데이터가 보인다")
        assertEquals(2, calls.get())
    }

    // ── 질의 상한과 결과 캐시 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `질의는 길이 80자 단어 6개로 줄이고 중복 단어를 뺀 뒤 엔진에 넘긴다`() {
        assertEquals(null, ExerciseSearchIndexService.normalizeQuery("  \u0000\t "))
        assertEquals("bench press", ExerciseSearchIndexService.normalizeQuery("  bench\u0000 \t press  "))
        assertEquals("dumbbell curl row", ExerciseSearchIndexService.normalizeQuery("dumbbell curl row ".repeat(450)))
        assertEquals("Squat bench", ExerciseSearchIndexService.normalizeQuery("Squat squat bench SQUAT"))
        val many = ExerciseSearchIndexService.normalizeQuery((1..400).joinToString(" ") { "w$it" })!!
        assertTrue(many.length <= ExerciseSearchIndexService.MAX_QUERY_CHARS)
        assertEquals(ExerciseSearchIndexService.MAX_QUERY_WORDS, many.split(' ').size)
        val longWord = ExerciseSearchIndexService.normalizeQuery("a".repeat(100_000))!!
        assertEquals(ExerciseSearchIndexService.MAX_QUERY_CHARS, longWord.length)
        // 서로게이트 쌍을 반으로 자르지 않는다
        val emoji = ExerciseSearchIndexService.normalizeQuery("💪".repeat(100))!!
        assertTrue(emoji.none { Character.isHighSurrogate(it) && emoji.indexOf(it) == emoji.length - 1 })
    }

    @Test
    fun `같은 질의의 다음 페이지는 결과 캐시를 쓰고 인덱스가 바뀌면 캐시도 바뀐다`() {
        val repo = mockk<ExerciseRepository>()
        val data = AtomicReference(listOf(squat, legPress))
        every { repo.findAllWithMuscleGroups() } answers { data.get() }
        val service = service(repo)
        val first = service.searchIds("squat", ExerciseCategory.LEGS, setOf(Equipment.BARBELL), "en")
        assertSame(first, service.searchIds("  squat ", ExerciseCategory.LEGS, setOf(Equipment.BARBELL), "en"), "정규화한 질의가 같으면 재사용")
        assertNotSame(first, service.searchIds("squat", ExerciseCategory.LEGS, null, "en"), "필터가 다르면 다른 결과")

        data.set(listOf(legPress))
        service.invalidate()
        assertEquals(emptyList<Long>(), service.searchIds("squat", ExerciseCategory.LEGS, setOf(Equipment.BARBELL), "en"))
    }

    @Test
    fun `대안 목록은 필터와 카테고리를 풀어가며 찾고 모르는 질의는 비운다`() {
        val repo = mockk<ExerciseRepository>()
        every { repo.findAllWithMuscleGroups() } returns listOf(
            squat, bench, legPress,
            exercise(4, "Dumbbell squat", ExerciseCategory.LEGS).copy(equipment = Equipment.DUMBBELL)
        )
        val service = service(repo)
        // 장비 필터로 0건: 필터를 풀면 나오는 스쿼트들
        assertEquals(setOf(1L, 4L), service.fallbackIds("squat", null, setOf(Equipment.KETTLEBELL), "en").toSet())
        // 카테고리 필터로 0건
        assertTrue(service.fallbackIds("squat", ExerciseCategory.CHEST, null, "en").containsAll(listOf(1L, 4L)))
        // 알아듣는 단어 + 모르는 단어: 알아듣는 단어의 대표 운동
        assertTrue(2L in service.fallbackIds("bench blorpzx", null, null, "en"))
        // 전혀 모르는 질의, 빈 질의
        assertEquals(emptyList<Long>(), service.fallbackIds("zzqqxx", null, null, "en"))
        assertEquals(emptyList<Long>(), service.fallbackIds(null, null, null, "en"))
    }
}

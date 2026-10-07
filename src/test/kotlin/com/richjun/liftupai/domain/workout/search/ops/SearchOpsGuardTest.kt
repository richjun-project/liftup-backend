package com.richjun.liftupai.domain.workout.search.ops

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewriter
import com.richjun.liftupai.domain.workout.search.rewrite.RewriteTransport
import com.richjun.liftupai.domain.workout.search.rewrite.GeminiRewriteTransport
import com.richjun.liftupai.domain.workout.search.semantic.EmbeddingTransport
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.GeminiEmbeddingTransport
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 검색의 Gemini 호출 운영 강화 — 전역 예산·동시 대기 상한·장애 분류(차단기 vs 음성 캐시)·429 Retry-After·키 없음 경고·주기 지표 로그.
 * 네트워크는 쓰지 않는다(가짜 전송 + 로컬 HTTP 서버).
 */
class SearchOpsGuardTest {
    private var now = 10 * 86_400_000L
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() { server?.stop(0) }

    private fun guard(perMinute: Int = 1000, daily: Int = 100_000, concurrent: Int = 20) =
        SearchLlmGuard(perMinute, daily, concurrent).also { it.clock = { now } }

    private class CountingEmbed(val answer: (String) -> FloatArray = { vec() }) : EmbeddingTransport {
        val calls = AtomicInteger()
        @Volatile var failure: (() -> Throwable)? = null
        override fun embed(text: String, timeoutMillis: Long): FloatArray {
            calls.incrementAndGet()
            failure?.let { throw it() }
            return answer(text)
        }
    }

    private class CountingRewrite(var answer: (String) -> String = { GOOD }) : RewriteTransport {
        val calls = AtomicInteger()
        @Volatile var failure: (() -> Throwable)? = null
        override fun generate(prompt: String, timeoutMillis: Long): String {
            calls.incrementAndGet()
            failure?.let { throw it() }
            return answer(prompt)
        }
    }

    private fun embedder(t: EmbeddingTransport?, g: SearchLlmGuard? = null, failures: Int = 3, openMs: Long = 10_000, enabled: Boolean = true, key: String = "secret-test-key") =
        QueryEmbedder(enabled, key, 1200, 100, failures, openMs, t, g).also { it.clock = { now } }

    private fun rewriter(t: RewriteTransport?, g: SearchLlmGuard? = null, failures: Int = 3, openMs: Long = 10_000, enabled: Boolean = true, key: String = "secret-test-key") =
        QueryRewriter(enabled, key, QueryRewriter.DEFAULT_MODEL, 2500, 100, failures, openMs, t, g).also { it.clock = { now } }

    companion object {
        const val GOOD = """{"hints":["Squat"],"forbid_equipment":[]}"""
        fun vec() = FloatArray(QueryEmbedder.DIM) { 0.5f + it * 0.001f }
    }

    // ── 전역 예산 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `분당 예산을 넘으면 임베딩을 부르지 않고 건너뛴 횟수를 센다`() {
        val g = guard(perMinute = 3)
        val t = CountingEmbed()
        val e = embedder(t, g)
        val got = (1..5).map { e.embed("query number $it") }
        assertEquals(3, t.calls.get())
        assertEquals(3, got.count { it != null })
        assertNull(got[3]); assertNull(got[4])
        assertEquals(2, g.stat(LlmKind.EMBED).budgetSkips.get())
        assertFalse(e.breakerOpen(), "예산 초과는 서버 장애가 아니라 차단기에 세지 않는다")
    }

    @Test
    fun `예산은 분이 바뀌면 다시 열린다`() {
        val g = guard(perMinute = 1)
        val t = CountingEmbed()
        val e = embedder(t, g)
        assertNotNull(e.embed("a")); assertNull(e.embed("b"))
        now += 61_000
        assertNotNull(e.embed("b"))
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `일일 상한을 넘으면 분이 바뀌어도 부르지 않고 UTC 날짜가 바뀌면 다시 열린다`() {
        val g = guard(perMinute = 100, daily = 2)
        val t = CountingEmbed()
        val e = embedder(t, g)
        assertNotNull(e.embed("a")); assertNotNull(e.embed("b"))
        now += 120_000
        assertNull(e.embed("c"))
        assertEquals(2, t.calls.get())
        now += 86_400_000
        assertNotNull(e.embed("c"))
    }

    @Test
    fun `일일 예산이 바닥나면 WARN 을 그날 한 번만 남긴다`() {
        val g = guard(perMinute = 100, daily = 1)
        val e = embedder(CountingEmbed(), g)
        val logs = captureLogs(SearchLlmGuard::class.java) {
            e.embed("a")
            repeat(5) { e.embed("b$it") }
        }
        assertEquals(1, logs.count { it.level == Level.WARN && it.formattedMessage.contains("daily budget") })
    }

    @Test
    fun `임베딩과 질의 해석은 같은 예산을 나눠 쓴다`() {
        val g = guard(perMinute = 3)
        val et = CountingEmbed(); val rt = CountingRewrite()
        val e = embedder(et, g); val r = rewriter(rt, g)
        assertNotNull(e.embed("one")); assertNotNull(r.rewrite("one")); assertNotNull(e.embed("two"))
        assertNull(r.rewrite("two"), "네 번째 호출은 예산 밖")
        assertEquals(1, g.stat(LlmKind.REWRITE).budgetSkips.get())
        assertEquals(3, et.calls.get() + rt.calls.get())
    }

    @Test
    fun `예산이 없어 건너뛰어도 캐시 적중은 예산을 쓰지 않는다`() {
        val g = guard(perMinute = 1)
        val t = CountingEmbed()
        val e = embedder(t, g)
        assertNotNull(e.embed("same")); assertNotNull(e.embed("same")); assertNotNull(e.embed("same"))
        assertEquals(1, t.calls.get())
        assertEquals(0, g.stat(LlmKind.EMBED).budgetSkips.get())
        assertEquals(2, g.stat(LlmKind.EMBED).cacheHits.get())
    }

    // ── 동시 대기 상한 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `Gemini 를 기다리는 자연어 검색이 상한을 넘으면 기다리지 않고 바로 어휘 결과로 간다`() {
        val g = guard(concurrent = 2)
        val engine = ExerciseSearchEngine(docs(), emptyList(), 10_000_000_000L)
        val hybrid = HybridSearch(engine, vectors(), executor = null, guard = g)
        val pool = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val vectorCalls = AtomicInteger()
        val sentence = "something to protect my throwing arm all season long"
        try {
            repeat(2) {
                pool.submit {
                    hybrid.search(sentence, null, null, "en") { vectorCalls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); null }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, g.waitingSearches())
            val started = System.nanoTime()
            val third = hybrid.search(sentence, null, null, "en") { vectorCalls.incrementAndGet(); null }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1000, "상한을 넘은 검색은 기다리지 않는다")
            assertTrue(third.llmSkipped)
            assertTrue(third.degraded, "건너뛴 결과는 degraded 라 오래 캐시되지 않는다")
            assertFalse(third.semantic)
            assertEquals(2, vectorCalls.get(), "세 번째 검색은 임베딩 함수를 부르지 않았다")
            assertEquals(1, g.summary(reset = false).let { Regex("concurrency=(\\d+)").find(it)!!.groupValues[1].toInt() })
        } finally {
            release.countDown(); pool.shutdown()
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, g.waitingSearches(), "자리는 끝나면 돌려준다")
    }

    @Test
    fun `예외가 나도 동시 대기 자리를 돌려준다`() {
        val g = guard(concurrent = 1)
        val engine = ExerciseSearchEngine(docs(), emptyList(), 10_000_000_000L)
        val hybrid = HybridSearch(engine, vectors(), guard = g)
        repeat(3) { runCatching { hybrid.search("something to protect my throwing arm all season long", null, null, "en") { throw IllegalStateException("x") } } }
        assertEquals(0, g.waitingSearches())
    }

    // ── 차단기 분류 ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `내용 실패는 차단기를 열지 않고 질의별 음성 캐시로 같은 질의를 다시 부르지 않는다`() {
        val t = CountingRewrite { "I cannot help with that" }
        val r = rewriter(t, failures = 3)
        repeat(10) { assertNull(r.rewrite("adversarial $it")) }
        assertFalse(r.breakerOpen(), "나쁜 질의 열 개가 모두의 차단기를 열면 안 된다")
        assertEquals(10, t.calls.get())
        repeat(5) { assertNull(r.rewrite("adversarial 3")) }
        assertEquals(10, t.calls.get(), "같은 질의 반복은 음성 캐시")
        assertEquals(10, r.negativeCount())
        // 정상 질의는 그대로 동작한다
        t.answer = { GOOD }
        assertNotNull(r.rewrite("normal query"))
    }

    @Test
    fun `임베딩의 내용 실패(차원 불일치·응답 없음)도 차단기에 세지 않는다`() {
        val t = CountingEmbed { FloatArray(5) { 1f } }
        val e = embedder(t, failures = 2)
        repeat(6) { assertNull(e.embed("q$it")) }
        assertFalse(e.breakerOpen())
        assertEquals(6, e.negativeCount())
        val t2 = CountingEmbed(); t2.failure = { LlmContentException("no embedding in response") }
        val e2 = embedder(t2, failures = 2)
        repeat(5) { assertNull(e2.embed("q$it")) }
        assertFalse(e2.breakerOpen())
    }

    @Test
    fun `전송 오류·시간 초과·5xx 는 연속 실패로 세어 차단기를 연다`() {
        for (failure in listOf<() -> Throwable>({ IOException("reset") }, { java.io.InterruptedIOException("timeout") }, { LlmHttpException(503) })) {
            val t = CountingRewrite(); t.failure = failure
            val r = rewriter(t, failures = 3)
            repeat(3) { assertNull(r.rewrite("q$it")) }
            assertTrue(r.breakerOpen(), failure().javaClass.simpleName)
            assertNull(r.rewrite("q-after"))
            assertEquals(3, t.calls.get(), "열려 있으면 부르지 않는다")
        }
    }

    @Test
    fun `4xx 중 429 와 인증 오류 외(400)는 질의의 문제라 차단기에 세지 않는다`() {
        val t = CountingRewrite(); t.failure = { LlmHttpException(400) }
        val r = rewriter(t, failures = 2)
        repeat(6) { assertNull(r.rewrite("q$it")) }
        assertFalse(r.breakerOpen())
    }

    @Test
    fun `429 는 한 번으로 열리고 Retry-After 만큼 유지된다`() {
        val t = CountingRewrite(); t.failure = { LlmHttpException(429, retryAfterMillis = 120_000) }
        val r = rewriter(t, failures = 5, openMs = 10_000)
        assertNull(r.rewrite("a"))
        assertTrue(r.breakerOpen(), "429 한 번이면 열린다")
        now += 119_000
        assertTrue(r.breakerOpen(), "Retry-After(120초) 동안은 열려 있다")
        assertNull(r.rewrite("b"))
        assertEquals(1, t.calls.get())
        now += 2_000
        assertFalse(r.breakerOpen())
        t.failure = null
        assertNotNull(r.rewrite("c"), "시간이 지나면 한 번 시험하고 성공하면 닫힌다")
        assertFalse(r.breakerOpen())
    }

    @Test
    fun `429 에 Retry-After 가 없으면 설정한 열림 시간을 쓴다`() {
        val t = CountingEmbed(); t.failure = { LlmHttpException(429) }
        val e = embedder(t, failures = 5, openMs = 10_000)
        assertNull(e.embed("a"))
        assertTrue(e.breakerOpen())
        now += 10_001
        assertFalse(e.breakerOpen())
    }

    @Test
    fun `인증 오류는 한 번으로 길게 연다`() {
        val t = CountingEmbed(); t.failure = { LlmHttpException(403) }
        val e = embedder(t, failures = 5, openMs = 10_000)
        assertNull(e.embed("a"))
        assertTrue(e.breakerOpen())
        now += 60_000
        assertTrue(e.breakerOpen(), "잘못된 키로 호출을 두드리지 않는다")
        now += 300_000
        assertFalse(e.breakerOpen())
    }

    @Test
    fun `서버가 답하면(내용 실패여도) 연속 실패 수가 비워진다`() {
        val t = CountingRewrite(); t.failure = { IOException("x") }
        val r = rewriter(t, failures = 3)
        r.rewrite("a"); r.rewrite("b")
        t.failure = null; t.answer = { "not json" }
        r.rewrite("c")
        t.answer = { GOOD }; t.failure = { IOException("x") }
        r.rewrite("d"); r.rewrite("e")
        assertFalse(r.breakerOpen(), "내용 실패가 낀 뒤에는 두 번 더 실패해도 임계(3)에 못 미친다")
    }

    @Test
    fun `전송이 Error 를 던져도 시험 호출 자리가 영영 잠기지 않는다`() {
        val t = CountingRewrite(); t.failure = { IOException("x") }
        val r = rewriter(t, failures = 1, openMs = 1_000)
        assertNull(r.rewrite("a"))
        assertTrue(r.breakerOpen())
        now += 1_001
        t.failure = { StackOverflowError() }
        runCatching { r.rewrite("probe") }
        t.failure = null
        assertNotNull(r.rewrite("next"), "다음 요청이 시험 호출을 할 수 있어야 한다")
        assertFalse(r.breakerOpen())
    }

    @Test
    fun `같은 임베딩 질의가 동시에 오면 호출 하나를 같이 쓴다`() {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val e = embedder(EmbeddingTransport { _, _ -> calls.incrementAndGet(); release.await(5, TimeUnit.SECONDS); vec() })
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit<FloatArray?> { e.embed("same query") } }
            Thread.sleep(200)
            release.countDown()
            futures.forEach { assertNotNull(it.get(5, TimeUnit.SECONDS)) }
        } finally { pool.shutdown() }
        assertEquals(1, calls.get())
    }

    // ── HTTP 전송: 상태 코드·Retry-After ─────────────────────────────────────────────────────

    private fun startServer(code: Int, retryAfter: String?, body: String = "{}"): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val bytes = body.toByteArray()
            retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/v1beta/models"
    }

    @Test
    fun `임베딩 전송은 429 의 Retry-After 초를 읽어 예외에 담는다`() {
        val base = startServer(429, "7")
        val ex = assertThrows(LlmHttpException::class.java) { GeminiEmbeddingTransport("k", endpoint = "$base/m:embedContent").embed("q", 2000) }
        assertEquals(429, ex.code)
        assertEquals(7000L, ex.retryAfterMillis)
        assertEquals(LlmFailureClass.RATE_LIMIT, LlmFailures.classify(ex))
    }

    @Test
    fun `해석 전송은 상태 코드를 분류할 수 있는 예외로 낸다 - Retry-After 가 날짜 형식이면 무시한다`() {
        val base = startServer(429, "Wed, 21 Oct 2026 07:28:00 GMT")
        val ex = assertThrows(LlmHttpException::class.java) { GeminiRewriteTransport("k", "gemini-flash-lite-latest", baseUrl = base).generate("p", 2000) }
        assertEquals(429, ex.code)
        assertNull(ex.retryAfterMillis)
        val base5 = startServer(503, null)
        assertEquals(LlmFailureClass.SERVER_5XX, LlmFailures.classify(assertThrows(LlmHttpException::class.java) { GeminiRewriteTransport("k", "gemini-flash-lite-latest", baseUrl = base5).generate("p", 2000) }))
    }

    @Test
    fun `후보가 없거나 본문을 읽을 수 없는 200 응답은 내용 실패다`() {
        val noCand = startServer(200, null, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals(LlmFailureClass.CONTENT, LlmFailures.classify(assertThrows(Exception::class.java) { GeminiRewriteTransport("k", "gemini-flash-lite-latest", baseUrl = noCand).generate("p", 2000) }))
        server?.stop(0)
        val garbage = startServer(200, null, "<html>not json")
        assertEquals(LlmFailureClass.CONTENT, LlmFailures.classify(assertThrows(Exception::class.java) { GeminiEmbeddingTransport("k", endpoint = "$garbage/m:embedContent").embed("q", 2000) }))
    }

    @Test
    fun `Retry-After 는 상한 10분으로 자르고 음수나 이상한 값은 무시한다`() {
        assertEquals(600_000L, LlmFailures.parseRetryAfter("99999"))
        assertNull(LlmFailures.parseRetryAfter("-5"))
        assertNull(LlmFailures.parseRetryAfter("soon"))
        assertNull(LlmFailures.parseRetryAfter(null))
        assertEquals(0L, LlmFailures.parseRetryAfter("0"))
    }

    // ── 키 없음 ──────────────────────────────────────────────────────────────────────────────

    private fun captureLogs(clazz: Class<*>, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(clazz) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val old = logger.level
        logger.level = Level.DEBUG
        try { block() } finally { logger.detachAppender(appender); logger.level = old }
        return appender.list.toList()
    }

    @Test
    fun `설정은 켜져 있는데 키가 비어 있으면 기동할 때 WARN 한 줄과 꺼짐 상태를 알린다`() {
        val e = embedder(null, enabled = true, key = "")
        val r = rewriter(null, enabled = true, key = "  ")
        val status = SearchLlmStatus(e, r, guard())
        assertTrue(status.misconfigured())
        assertTrue(status.describe().contains("DISABLED(gemini api key is blank)"), status.describe())
        val logs = captureLogs(SearchLlmStatus::class.java) { status.report() }
        val warns = logs.filter { it.level == Level.WARN }
        assertEquals(1, warns.size, "WARN 은 한 줄만")
        assertTrue(warns.single().formattedMessage.contains("blank"))
        assertTrue(warns.single().formattedMessage.contains("lexical"))
        assertFalse(e.enabled); assertFalse(r.enabled)
    }

    @Test
    fun `키가 있거나 설정이 꺼져 있으면 WARN 이 없고 상태는 정상 또는 꺼짐`() {
        val on = SearchLlmStatus(embedder(CountingEmbed()), rewriter(CountingRewrite()), guard())
        assertFalse(on.misconfigured())
        assertTrue(on.describe().contains("semantic=enabled rewrite=enabled"))
        val off = SearchLlmStatus(embedder(null, enabled = false, key = ""), rewriter(null, enabled = false, key = ""), guard())
        assertFalse(off.misconfigured())
        assertTrue(off.describe().contains("disabled(config off)"))
        val logs = captureLogs(SearchLlmStatus::class.java) { on.report(); off.report() }
        assertTrue(logs.none { it.level == Level.WARN })
        assertTrue(logs.any { it.formattedMessage.contains("budget/min=") }, "한도도 기동 로그에 남긴다")
    }

    // ── 주기 지표 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `요약은 호출 수 실패 분류 건너뜀 캐시 적중 지연 백분위를 담고 질의 문자열은 담지 않는다`() {
        val g = guard(perMinute = 5)
        val et = CountingEmbed()
        val e = embedder(et, g)
        val rt = CountingRewrite()
        val r = rewriter(rt, g)
        e.embed("secret-query-text-alpha"); e.embed("secret-query-text-alpha"); e.embed("beta")
        rt.failure = { IOException("net") }
        r.rewrite("secret-query-text-gamma")
        rt.failure = null; rt.answer = { "garbage" }
        r.rewrite("delta")
        e.embed("epsilon")  // 예산(5) 안 마지막
        e.embed("zeta")      // 예산 초과 → 건너뜀
        g.recordSearch(semantic = true, rewritten = false, degraded = true)
        g.recordResultCache(true); g.recordResultCache(false)
        val line = g.summary(reset = false)
        assertTrue(line.contains("embed[calls=3 ok=3"), line)
        assertTrue(line.contains("rewrite[calls=2 ok=0 fail(transport=1,content=1)"), line)
        assertTrue(line.contains("skipBudget=1"), line)
        assertTrue(Regex("cache=20%").containsMatchIn(line), line)
        assertTrue(Regex("p50=\\d+ms p95=\\d+ms").containsMatchIn(line), line)
        assertTrue(line.contains("searches[llm=1 semantic=1 rewritten=0 degraded=1]"), line)
        assertTrue(line.contains("skip[minuteBudget=1"), line)
        assertFalse(line.contains("secret-query-text") || line.contains("alpha") || line.contains("beta"), "질의 문자열은 로그에 없다")
        // reset 하면 다음 구간은 0 에서 시작
        g.summary(reset = true)
        assertTrue(g.summary(reset = false).contains("embed[calls=0 ok=0"))
    }

    @Test
    fun `주기 요약은 활동이 있을 때만 INFO 한 줄을 남기고 비운다`() {
        val g = guard()
        val logs = captureLogs(SearchLlmGuard::class.java) {
            g.logSummary()  // 활동 없음 → 로그 없음
            embedder(CountingEmbed(), g).embed("q")
            g.logSummary()
            g.logSummary()  // 비워졌으니 다시 조용
        }
        val infos = logs.filter { it.level == Level.INFO }
        assertEquals(1, infos.size)
        assertTrue(infos.single().formattedMessage.startsWith("Search LLM summary:"))
    }

    @Test
    fun `지연 백분위는 중앙값과 95 백분위를 낸다`() {
        val s = SearchLlmGuard.KindStat()
        (1..100).forEach { s.addLatency(it.toLong()) }
        val (p50, p95) = s.percentiles()!!
        assertEquals(50L, p50)
        assertEquals(95L, p95)
        assertNull(SearchLlmGuard.KindStat().percentiles())
    }

    // ── 운영 기록의 의미·해석 사용 꼬리표 ──────────────────────────────────────────────────

    @Test
    fun `검색 사용 꼬리표는 의미 s 해석 r 미확보 d 로 붙고 한 번 읽으면 비워진다`() {
        SearchUsageContext.clear()
        assertEquals("", SearchUsageContext.consumeSuffix())
        SearchUsageContext.note("q", SearchUsageContext.SEMANTIC or SearchUsageContext.REWRITTEN)
        assertEquals("+sr", SearchUsageContext.consumeSuffix())
        assertEquals("", SearchUsageContext.consumeSuffix())
        SearchUsageContext.note("q", SearchUsageContext.SEMANTIC)
        SearchUsageContext.note("q", SearchUsageContext.DEGRADED)  // 같은 질의의 연이은 조회는 합친다
        assertEquals("+sd", SearchUsageContext.consumeSuffix())
        SearchUsageContext.note("q", 0)
        assertEquals("", SearchUsageContext.consumeSuffix())
        SearchUsageContext.note("q", SearchUsageContext.SEMANTIC, nowNanos = 0)
        assertEquals("", SearchUsageContext.consumeSuffix(nowNanos = 60_000_000_000L), "오래된 꼬리표는 다음 요청에 새지 않는다")
        // 꼬리표를 붙여도 source 컬럼(20자)을 넘지 않는다
        assertTrue(("program_search" + "+srd").length <= 20)
    }

    // ── 지원 ─────────────────────────────────────────────────────────────────────────────────

    private fun docs(): List<ExerciseSearchDoc> = listOf(
        doc(1, "barbell-squat", "Barbell squat", Equipment.BARBELL),
        doc(2, "dumbbell-curl", "Dumbbell curl", Equipment.DUMBBELL),
        doc(3, "face-pull", "Face pull", Equipment.CABLE)
    )

    private fun doc(id: Long, slug: String, name: String, eq: Equipment) = ExerciseSearchDoc(
        id, slug, mapOf("en" to name), ExerciseCategory.LEGS, eq, setOf(MuscleGroup.QUADRICEPS), null, RecommendationTier.STANDARD, 50, 40
    )

    private fun vectors() = ExerciseVectors.fromFloats(
        listOf("barbell-squat", "dumbbell-curl", "face-pull"),
        listOf(unit(0), unit(1), unit(2))
    )

    private fun unit(i: Int) = QueryEmbedder.normalize(FloatArray(768).also { it[i] = 1f })
}

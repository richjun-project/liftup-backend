package com.richjun.liftupai.domain.workout.search.semantic

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 질의 임베딩 — 캐시·시간 제한·차단기·실패 시 null(어휘 폴백)·키 비노출. 네트워크는 쓰지 않는다(가짜 전송 + 로컬 HTTP 서버). */
class QueryEmbedderTest {
    private class FakeTransport(val answer: (String) -> FloatArray = { vec(1f) }) : EmbeddingTransport {
        val calls = AtomicInteger()
        val texts = ArrayList<String>()
        val timeouts = ArrayList<Long>()
        @Volatile var failing = false
        override fun embed(text: String, timeoutMillis: Long): FloatArray {
            calls.incrementAndGet(); texts.add(text); timeouts.add(timeoutMillis)
            if (failing) throw java.io.IOException("boom")
            return answer(text)
        }
    }

    private var now = 1_000_000L
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() { server?.stop(0) }

    private fun embedder(
        transport: EmbeddingTransport?, enabled: Boolean = true, apiKey: String = "secret-test-key", timeout: Long = 1200, cache: Int = 100,
        failures: Int = 3, openMs: Long = 10_000
    ) = QueryEmbedder(enabled, apiKey, timeout, cache, failures, openMs, transport).also { it.clock = { now } }

    companion object {
        fun vec(seed: Float) = FloatArray(QueryEmbedder.DIM) { seed + it * 0.001f }
        private fun norm(v: FloatArray) = Math.sqrt(v.sumOf { it.toDouble() * it })
    }

    // ── 켜짐/꺼짐 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `꺼져 있으면 아무것도 부르지 않고 null`() {
        val t = FakeTransport()
        val e = embedder(t, enabled = false)
        assertFalse(e.enabled)
        assertNull(e.embed("가슴 운동"))
        assertEquals(0, t.calls.get())
    }

    @Test
    fun `API 키가 비어 있으면 켜져 있어도 꺼진 것과 같다`() {
        val e = embedder(null, enabled = true, apiKey = "")
        assertFalse(e.enabled)
        assertNull(e.embed("가슴 운동"))
    }

    @Test
    fun `켜져 있고 전송이 있으면 enabled`() {
        assertTrue(embedder(FakeTransport()).enabled)
    }

    @Test
    fun `빈 질의는 null 이고 호출하지 않는다`() {
        val t = FakeTransport()
        val e = embedder(t)
        assertNull(e.embed("   "))
        assertNull(e.embed(""))
        assertEquals(0, t.calls.get())
    }

    // ── 정규화·캐시 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `결과는 L2 정규화된 768차원이다`() {
        val v = embedder(FakeTransport { vec(2f) }).embed("가슴 운동")!!
        assertEquals(768, v.size)
        assertEquals(1.0, norm(v), 1e-4)
    }

    @Test
    fun `차원이 다른 응답은 실패로 취급해 null`() {
        val e = embedder(FakeTransport { FloatArray(10) { 1f } })
        assertNull(e.embed("가슴 운동"))
    }

    @Test
    fun `영벡터 응답은 실패로 취급해 null`() {
        assertNull(embedder(FakeTransport { FloatArray(768) }).embed("가슴 운동"))
    }

    @Test
    fun `같은 질의는 캐시에서 - 대소문자·공백·전각 차이를 무시한다`() {
        val t = FakeTransport()
        val e = embedder(t)
        val a = e.embed("Chest  Workout")
        val b = e.embed("chest workout ")
        val c = e.embed("ＣＨＥＳＴ　ＷＯＲＫＯＵＴ")
        assertEquals(1, t.calls.get())
        assertArrayEquals(a, b)
        assertArrayEquals(a, c)
    }

    @Test
    fun `서로 다른 질의는 각각 부른다`() {
        val t = FakeTransport()
        val e = embedder(t)
        e.embed("chest"); e.embed("back")
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `캐시는 LRU - 가장 오래 안 쓴 질의부터 밀려난다`() {
        val t = FakeTransport()
        val e = embedder(t, cache = 2)
        e.embed("a"); e.embed("b"); e.embed("a"); e.embed("c") // b 가 밀려남
        assertEquals(3, t.calls.get())
        e.embed("a"); assertEquals(3, t.calls.get())
        e.embed("b"); assertEquals(4, t.calls.get())
        assertEquals(2, e.cachedCount())
    }

    @Test
    fun `실패는 캐시하지 않는다 - 다음에 다시 시도`() {
        val t = FakeTransport(); t.failing = true
        val e = embedder(t, failures = 100)
        assertNull(e.embed("x"))
        t.failing = false
        assertNotNull(e.embed("x"))
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `보내는 텍스트는 정규화한 질의뿐이고 시간 제한이 같이 간다`() {
        val t = FakeTransport()
        embedder(t, timeout = 777).embed("  Squat   Alternatives ")
        assertEquals(listOf("squat alternatives"), t.texts)
        assertEquals(listOf(777L), t.timeouts)
    }

    @Test
    fun `긴 질의는 400자로 자른다`() {
        val t = FakeTransport()
        embedder(t).embed("a".repeat(1000))
        assertEquals(400, t.texts.single().length)
    }

    // ── 차단기 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `연속 실패가 임계에 닿으면 차단기가 열려 호출을 멈춘다`() {
        val t = FakeTransport(); t.failing = true
        val e = embedder(t, failures = 3, openMs = 10_000)
        repeat(3) { assertNull(e.embed("q$it")) }
        assertTrue(e.breakerOpen())
        assertEquals(3, t.calls.get())
        repeat(5) { assertNull(e.embed("other$it")) }
        assertEquals(3, t.calls.get(), "열린 동안에는 전송을 부르지 않는다")
    }

    @Test
    fun `차단기가 열려 있어도 캐시된 질의는 돌려준다`() {
        val t = FakeTransport()
        val e = embedder(t, failures = 2)
        val cached = e.embed("cached")
        t.failing = true
        e.embed("x1"); e.embed("x2")
        assertTrue(e.breakerOpen())
        assertArrayEquals(cached, e.embed("cached"))
    }

    @Test
    fun `열림 시간이 지나면 한 번 시험하고 성공하면 닫힌다`() {
        val t = FakeTransport(); t.failing = true
        val e = embedder(t, failures = 2, openMs = 10_000)
        e.embed("a"); e.embed("b")
        assertTrue(e.breakerOpen())
        now += 10_001
        assertFalse(e.breakerOpen())
        t.failing = false
        assertNotNull(e.embed("c"))
        assertEquals(3, t.calls.get())
        // 닫힌 뒤에는 정상 호출, 실패 한 번으로는 다시 열리지 않는다
        t.failing = true
        assertNull(e.embed("d"))
        assertFalse(e.breakerOpen())
    }

    @Test
    fun `시험 호출이 실패하면 더 길게 다시 열린다`() {
        val t = FakeTransport(); t.failing = true
        val e = embedder(t, failures = 2, openMs = 10_000)
        e.embed("a"); e.embed("b")
        now += 10_001
        assertNull(e.embed("probe")) // 시험 실패 → 다시 열림(두 배)
        assertEquals(3, t.calls.get())
        now += 10_001
        assertTrue(e.breakerOpen(), "두 번째 열림은 첫 번째보다 길다")
        now += 10_000
        assertFalse(e.breakerOpen())
    }

    @Test
    fun `성공이 섞이면 연속 실패가 초기화된다`() {
        val t = FakeTransport()
        val e = embedder(t, failures = 3)
        t.failing = true; e.embed("a"); e.embed("b")
        t.failing = false; assertNotNull(e.embed("c"))
        t.failing = true; e.embed("d"); e.embed("e")
        assertFalse(e.breakerOpen())
    }

    @Test
    fun `전송이 런타임 예외를 던져도 null 로 흡수한다`() {
        val e = embedder(EmbeddingTransport { _, _ -> throw IllegalStateException("x") })
        assertNull(e.embed("q"))
    }

    // ── 키 비노출 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `실패 로그에 API 키가 남지 않는다`() {
        val logger = LoggerFactory.getLogger(QueryEmbedder::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val oldLevel = logger.level
        logger.level = ch.qos.logback.classic.Level.DEBUG
        try {
            val e = embedder(EmbeddingTransport { _, _ -> throw java.io.IOException("failed for key=secret-test-key at url") }, apiKey = "secret-test-key", failures = 1)
            e.embed("q")
            assertTrue(appender.list.isNotEmpty())
            assertTrue(appender.list.none { it.formattedMessage.contains("secret-test-key") }, appender.list.joinToString { it.formattedMessage })
        } finally {
            logger.detachAppender(appender); logger.level = oldLevel
        }
    }

    // ── Gemini REST 전송 (로컬 서버로 검증) ───────────────────────────────────────────────────

    private fun startServer(handler: (headers: Map<String, List<String>>, body: String, path: String) -> Pair<Int, String>, delayMs: Long = 0): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            val (code, resp) = handler(ex.requestHeaders.entries.associate { it.key.lowercase() to it.value }, body, ex.requestURI.toString())
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/v1beta/models/gemini-embedding-001:embedContent"
    }

    @Test
    fun `Gemini 전송은 RETRIEVAL_QUERY 768차원으로 부르고 키는 헤더로만 보낸다`() {
        val seenHeaders = AtomicReference<Map<String, List<String>>>()
        val seenBody = AtomicReference<String>()
        val seenPath = AtomicReference<String>()
        val values = (0 until 768).joinToString(",") { "0.5" }
        val url = startServer({ h, b, p -> seenHeaders.set(h); seenBody.set(b); seenPath.set(p); 200 to """{"embedding":{"values":[$values]}}""" })
        val v = GeminiEmbeddingTransport("secret-test-key", endpoint = url).embed("chest workout", 2000)
        assertEquals(768, v.size)
        assertEquals(listOf("secret-test-key"), seenHeaders.get()["x-goog-api-key"])
        assertFalse(seenPath.get().contains("secret"), "키가 URL 에 들어가면 안 된다")
        val body = ObjectMapper().readTree(seenBody.get())
        assertEquals("RETRIEVAL_QUERY", body["taskType"].asText())
        assertEquals(768, body["outputDimensionality"].asInt())
        assertEquals("models/gemini-embedding-001", body["model"].asText())
        assertEquals("chest workout", body["content"]["parts"][0]["text"].asText())
        // 사용자 식별자 같은 다른 필드는 없다
        assertEquals(setOf("model", "content", "taskType", "outputDimensionality"), body.fieldNames().asSequence().toSet())
    }

    @Test
    fun `Gemini 전송은 HTTP 오류를 예외로 낸다 - 메시지에 키가 없다`() {
        val url = startServer({ _, _, _ -> 403 to """{"error":{"message":"key secret-test-key is bad"}}""" })
        val ex = assertThrows(IllegalStateException::class.java) { GeminiEmbeddingTransport("secret-test-key", endpoint = url).embed("q", 2000) }
        assertTrue(ex.message!!.contains("403"))
        assertFalse(ex.message!!.contains("secret-test-key"))
    }

    @Test
    fun `Gemini 전송은 응답이 시간 제한을 넘기면 끊는다`() {
        val values = (0 until 768).joinToString(",") { "0.5" }
        val url = startServer({ _, _, _ -> 200 to """{"embedding":{"values":[$values]}}""" }, delayMs = 1500)
        val started = System.nanoTime()
        assertThrows(java.io.IOException::class.java) { GeminiEmbeddingTransport("k", endpoint = url).embed("q", 200) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1200, "시간 제한(200ms) 근처에서 끊겨야 한다")
    }

    @Test
    fun `Gemini 전송은 임베딩이 없는 응답을 거부한다`() {
        val url = startServer({ _, _, _ -> 200 to """{"foo":1}""" })
        assertThrows(IllegalStateException::class.java) { GeminiEmbeddingTransport("k", endpoint = url).embed("q", 2000) }
    }

    @Test
    fun `실제 전송을 쓴 임베더는 서버 장애가 반복되면 차단기가 열린다`() {
        val hits = AtomicInteger()
        val url = startServer({ _, _, _ -> hits.incrementAndGet(); 500 to "{}" })
        val e = embedder(GeminiEmbeddingTransport("k", endpoint = url), failures = 2)
        repeat(5) { assertNull(e.embed("q$it")) }
        assertEquals(2, hits.get())
        assertTrue(e.breakerOpen())
    }

    @Test
    fun `cacheKey 는 NFKC·소문자·공백 정리`() {
        assertEquals("abc def", QueryEmbedder.cacheKey("  ＡＢＣ\t DEF "))
        assertNull(QueryEmbedder.cacheKey(" \n "))
    }
}

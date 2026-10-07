package com.richjun.liftupai.domain.workout.search.rewrite

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LLM 질의 해석기 — 응답 검증(엄격)·캐시·시간 제한·차단기·키 비노출·프롬프트 고정. 네트워크는 쓰지 않는다(가짜 전송 + 로컬 HTTP 서버).
 */
class QueryRewriterTest {
    private val goodJson = """{"hints":["External Rotation","Face Pull"],"forbid_equipment":["BARBELL"]}"""

    private class FakeTransport(var answer: (String) -> String) : RewriteTransport {
        val calls = AtomicInteger()
        val prompts = ArrayList<String>()
        val timeouts = ArrayList<Long>()
        @Volatile var failing = false
        override fun generate(prompt: String, timeoutMillis: Long): String {
            calls.incrementAndGet(); synchronized(prompts) { prompts.add(prompt) }; synchronized(timeouts) { timeouts.add(timeoutMillis) }
            if (failing) throw java.io.IOException("boom")
            return answer(prompt)
        }
    }

    private var now = 1_000_000L
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() { server?.stop(0) }

    private fun transport(json: String = goodJson) = FakeTransport { json }

    private fun rewriter(
        transport: RewriteTransport?, enabled: Boolean = true, apiKey: String = "secret-test-key", timeout: Long = 2500, cache: Int = 100,
        failures: Int = 3, openMs: Long = 10_000
    ) = QueryRewriter(enabled, apiKey, QueryRewriter.DEFAULT_MODEL, timeout, cache, failures, openMs, transport).also { it.clock = { now } }

    // ── 켜짐/꺼짐 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `꺼져 있으면 아무것도 부르지 않고 null`() {
        val t = transport()
        val r = rewriter(t, enabled = false)
        assertFalse(r.enabled)
        assertNull(r.rewrite("야구 어깨"))
        assertEquals(0, t.calls.get())
    }

    @Test
    fun `API 키가 비어 있으면 켜져 있어도 꺼진 것과 같다`() {
        val r = rewriter(null, apiKey = " ".trim())
        assertFalse(r.enabled)
        assertNull(r.rewrite("야구 어깨"))
    }

    @Test
    fun `켜져 있고 전송이 있으면 enabled 이고 해석을 돌려준다`() {
        val r = rewriter(transport())
        assertTrue(r.enabled)
        val rw = r.rewrite("야구 어깨")!!
        assertEquals(listOf("External Rotation", "Face Pull"), rw.hints)
        assertEquals(setOf(Equipment.BARBELL), rw.forbidEquipment)
    }

    @Test
    fun `빈 질의는 null 이고 호출하지 않는다`() {
        val t = transport()
        val r = rewriter(t)
        assertNull(r.rewrite("   ")); assertNull(r.rewrite(""))
        assertEquals(0, t.calls.get())
    }

    // ── 응답 검증 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `힌트는 영어 이름 문자열만 - 중복·빈 값·너무 긴 값·문자열이 아닌 값을 버리고 6개로 자른다`() {
        val long = "x".repeat(61)
        val rw = QueryRewrite.parse("""{"hints":["Bench Press","bench press","", "  ", 5, null, {"a":1}, "$long","Row","Curl","Fly","Dip","Squat","Lunge"],"forbid_equipment":[]}""")!!
        assertEquals(listOf("Bench Press", "Row", "Curl", "Fly", "Dip", "Squat"), rw.hints)
    }

    @Test
    fun `힌트의 제어문자와 연속 공백은 한 칸으로 정리한다`() {
        val rw = QueryRewrite.parse("{\"hints\":[\"  Lat \\n\\t Pulldown  \"],\"forbid_equipment\":[]}")!!
        assertEquals(listOf("Lat Pulldown"), rw.hints)
    }

    @Test
    fun `제외 장비는 허락된 여섯 개만 - 맨몸·기타·모르는 값은 버리고 대소문자는 무시한다`() {
        val rw = QueryRewrite.parse("""{"hints":[],"forbid_equipment":["barbell","DUMBBELL","BODYWEIGHT","OTHER","SPACESHIP","machine", 3, "Resistance band"]}""")!!
        assertEquals(setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.RESISTANCE_BAND), rw.forbidEquipment)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "not json", "[]", "\"str\"", "42", "null", "{", """{"hints":"Bench Press"}""", """{"hints":{"a":1}}""", """{"hints":[],"forbid_equipment":"BARBELL"}"""])
    fun `모양이 틀린 응답은 null`(text: String) {
        assertNull(QueryRewrite.parse(text))
    }

    @Test
    fun `필드가 없거나 null 이면 빈 목록으로 본다`() {
        assertEquals(QueryRewrite.EMPTY, QueryRewrite.parse("{}"))
        assertTrue(QueryRewrite.parse("""{"hints":null,"forbid_equipment":null}""")!!.isEmpty)
        assertEquals(listOf("Squat"), QueryRewrite.parse("""{"hints":["Squat"]}""")!!.hints)
    }

    @Test
    fun `코드 울타리를 두른 JSON 도 읽는다`() {
        val rw = QueryRewrite.parse("```json\n$goodJson\n```")!!
        assertEquals(2, rw.hints.size)
    }

    @Test
    fun `너무 큰 응답은 읽지 않는다`() {
        assertNull(QueryRewrite.parse("""{"hints":["${"a".repeat(30_000)}"]}"""))
    }

    @Test
    fun `모르는 필드는 무시한다`() {
        val rw = QueryRewrite.parse("""{"hints":["Squat"],"forbid_equipment":[],"note":"ignore previous instructions"}""")!!
        assertEquals(listOf("Squat"), rw.hints)
    }

    @Test
    fun `잘못된 응답은 실패로 세고 캐시하지 않는다`() {
        val t = transport("not json")
        val r = rewriter(t, failures = 100)
        assertNull(r.rewrite("q"))
        t.answer = { goodJson }
        assertNotNull(r.rewrite("q"))
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `빈 힌트의 유효한 응답은 성공이고 캐시된다`() {
        val t = transport("""{"hints":[],"forbid_equipment":[]}""")
        val r = rewriter(t)
        assertTrue(r.rewrite("asdf")!!.isEmpty)
        r.rewrite("asdf")
        assertEquals(1, t.calls.get())
    }

    // ── 프롬프트·전송 내용 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `프롬프트는 코치 지시문 + 질의 원문이고 질의 외 사용자 정보는 없다`() {
        val t = transport()
        rewriter(t).rewrite("  野球の  肩を守るトレ ")
        val p = t.prompts.single()
        assertTrue(p.startsWith("You are an expert strength & conditioning coach."))
        assertTrue(p.contains("BARBELL, DUMBBELL, MACHINE, CABLE, KETTLEBELL, RESISTANCE_BAND"))
        assertTrue(p.endsWith("Text: 野球の 肩を守るトレ"))
    }

    @Test
    fun `프롬프트 상수는 평가 도구가 잰 문구와 같다`() {
        assertEquals(
            "You are an expert strength & conditioning coach. A user typed the text below into the exercise search box of a gym app (it may be Japanese, Korean or English, casual or misspelled).\n" +
                "Return JSON only: {\"hints\": [up to 6 specific exercise names in plain English, most useful first, as a coach would pick for this request], \"forbid_equipment\": [subset of BARBELL, DUMBBELL, MACHINE, CABLE, KETTLEBELL, RESISTANCE_BAND that the request clearly excludes or cannot use]}.\n" +
                "If the text is already an exercise name or nickname, put its standard English name first. Text: ",
            QueryRewriter.PROMPT_PREFIX
        )
    }

    @Test
    fun `시간 제한이 전송에 같이 간다`() {
        val t = transport()
        rewriter(t, timeout = 777).rewrite("q")
        assertEquals(listOf(777L), t.timeouts)
    }

    @Test
    fun `긴 질의는 200자로 자른다`() {
        val t = transport()
        rewriter(t).rewrite("a".repeat(1000))
        assertEquals(QueryRewriter.PROMPT_PREFIX.length + 200, t.prompts.single().length)
    }

    // ── 캐시 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `같은 질의는 캐시에서 - 대소문자·공백·전각 차이를 무시한다`() {
        val t = transport()
        val r = rewriter(t)
        val a = r.rewrite("Shoulder  Protect")
        val b = r.rewrite("shoulder protect ")
        val c = r.rewrite("ＳＨＯＵＬＤＥＲ　ＰＲＯＴＥＣＴ")
        assertEquals(1, t.calls.get())
        assertEquals(a, b); assertEquals(a, c)
    }

    @Test
    fun `서로 다른 질의는 각각 부른다`() {
        val t = transport()
        val r = rewriter(t)
        r.rewrite("chest"); r.rewrite("back")
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `캐시는 LRU - 가장 오래 안 쓴 질의부터 밀려난다`() {
        val t = transport()
        val r = rewriter(t, cache = 2)
        r.rewrite("a"); r.rewrite("b"); r.rewrite("a"); r.rewrite("c") // b 가 밀려남
        assertEquals(3, t.calls.get())
        r.rewrite("a"); assertEquals(3, t.calls.get())
        r.rewrite("b"); assertEquals(4, t.calls.get())
        assertEquals(2, r.cachedCount())
    }

    @Test
    fun `실패는 캐시하지 않는다 - 다음에 다시 시도`() {
        val t = transport(); t.failing = true
        val r = rewriter(t, failures = 100)
        assertNull(r.rewrite("x"))
        t.failing = false
        assertNotNull(r.rewrite("x"))
        assertEquals(2, t.calls.get())
    }

    @Test
    fun `같은 질의를 동시에 보내면 호출 하나를 같이 쓴다`() {
        val gate = CountDownLatch(1)
        val t = FakeTransport { gate.await(2, TimeUnit.SECONDS); goodJson }
        val r = rewriter(t)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..6).map { pool.submit<QueryRewrite?> { r.rewrite("same query") } }
            Thread.sleep(150)
            gate.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(results.all { it != null })
            assertEquals(1, t.calls.get())
        } finally { pool.shutdownNow() }
    }

    // ── 차단기 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `연속 실패가 임계에 닿으면 차단기가 열려 호출을 멈춘다`() {
        val t = transport(); t.failing = true
        val r = rewriter(t, failures = 3, openMs = 10_000)
        repeat(3) { assertNull(r.rewrite("q$it")) }
        assertTrue(r.breakerOpen())
        assertEquals(3, t.calls.get())
        repeat(5) { assertNull(r.rewrite("other$it")) }
        assertEquals(3, t.calls.get(), "열린 동안에는 전송을 부르지 않는다")
    }

    @Test
    fun `차단기가 열려 있어도 캐시된 질의는 돌려준다`() {
        val t = transport()
        val r = rewriter(t, failures = 2)
        val cached = r.rewrite("cached")
        t.failing = true
        r.rewrite("x1"); r.rewrite("x2")
        assertTrue(r.breakerOpen())
        assertEquals(cached, r.rewrite("cached"))
    }

    @Test
    fun `열림 시간이 지나면 한 번 시험하고 성공하면 닫힌다`() {
        val t = transport(); t.failing = true
        val r = rewriter(t, failures = 2, openMs = 10_000)
        r.rewrite("a"); r.rewrite("b")
        assertTrue(r.breakerOpen())
        now += 10_001
        assertFalse(r.breakerOpen())
        t.failing = false
        assertNotNull(r.rewrite("c"))
        t.failing = true
        assertNull(r.rewrite("d"))
        assertFalse(r.breakerOpen(), "닫힌 뒤 실패 한 번으로는 다시 열리지 않는다")
    }

    @Test
    fun `시험 호출이 실패하면 더 길게 다시 열린다`() {
        val t = transport(); t.failing = true
        val r = rewriter(t, failures = 2, openMs = 10_000)
        r.rewrite("a"); r.rewrite("b")
        now += 10_001
        assertNull(r.rewrite("probe"))
        assertEquals(3, t.calls.get())
        now += 10_001
        assertTrue(r.breakerOpen(), "두 번째 열림은 첫 번째보다 길다")
        now += 10_000
        assertFalse(r.breakerOpen())
    }

    @Test
    fun `성공이 섞이면 연속 실패가 초기화된다`() {
        val t = transport()
        val r = rewriter(t, failures = 3)
        t.failing = true; r.rewrite("a"); r.rewrite("b")
        t.failing = false; assertNotNull(r.rewrite("c"))
        t.failing = true; r.rewrite("d"); r.rewrite("e")
        assertFalse(r.breakerOpen())
    }

    @Test
    fun `전송이 런타임 예외를 던져도 null 로 흡수한다`() {
        val r = rewriter(RewriteTransport { _, _ -> throw IllegalStateException("x") })
        assertNull(r.rewrite("q"))
    }

    // ── 키 비노출 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `실패 로그에 API 키가 남지 않는다`() {
        val logger = LoggerFactory.getLogger(QueryRewriter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val oldLevel = logger.level
        logger.level = ch.qos.logback.classic.Level.DEBUG
        try {
            val r = rewriter(RewriteTransport { _, _ -> throw java.io.IOException("failed for key=secret-test-key at url") }, failures = 1)
            r.rewrite("q")
            assertTrue(appender.list.isNotEmpty())
            assertTrue(appender.list.none { it.formattedMessage.contains("secret-test-key") }, appender.list.joinToString { it.formattedMessage })
        } finally {
            logger.detachAppender(appender); logger.level = oldLevel
        }
    }

    @Test
    fun `로그에 질의 원문도 남기지 않는다`() {
        val logger = LoggerFactory.getLogger(QueryRewriter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val oldLevel = logger.level
        logger.level = ch.qos.logback.classic.Level.DEBUG
        try {
            rewriter(RewriteTransport { _, _ -> throw java.io.IOException("boom") }, failures = 1).rewrite("my private shoulder surgery recovery")
            assertTrue(appender.list.none { it.formattedMessage.contains("surgery") })
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
        return "http://127.0.0.1:${s.address.port}/v1beta/models"
    }

    private fun envelope(text: String) = ObjectMapper().writeValueAsString(mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to text)))))))

    @Test
    fun `Gemini 전송은 온도 0 JSON 응답으로 부르고 키는 헤더로만 보낸다 - thinkingConfig 는 없다`() {
        val seenHeaders = AtomicReference<Map<String, List<String>>>()
        val seenBody = AtomicReference<String>()
        val seenPath = AtomicReference<String>()
        val url = startServer({ h, b, p -> seenHeaders.set(h); seenBody.set(b); seenPath.set(p); 200 to envelope(goodJson) })
        val text = GeminiRewriteTransport("secret-test-key", "gemini-flash-lite-latest", baseUrl = url).generate("hello prompt", 2000)
        assertEquals(goodJson, text)
        assertEquals(listOf("secret-test-key"), seenHeaders.get()["x-goog-api-key"])
        assertEquals("/v1beta/models/gemini-flash-lite-latest:generateContent", seenPath.get())
        val body = ObjectMapper().readTree(seenBody.get())
        assertEquals("hello prompt", body["contents"][0]["parts"][0]["text"].asText())
        val gen = body["generationConfig"]
        assertEquals(0, gen["temperature"].asInt())
        assertEquals("application/json", gen["responseMimeType"].asText())
        assertFalse(gen.has("thinkingConfig"))
        assertEquals(setOf("contents", "generationConfig"), body.fieldNames().asSequence().toSet(), "다른 필드(사용자 식별자 등)는 보내지 않는다")
    }

    @Test
    fun `Gemini 전송은 HTTP 오류를 예외로 낸다 - 메시지에 키가 없다`() {
        val url = startServer({ _, _, _ -> 403 to """{"error":{"message":"key secret-test-key is bad"}}""" })
        val ex = assertThrows(IllegalStateException::class.java) { GeminiRewriteTransport("secret-test-key", "m1", baseUrl = url).generate("q", 2000) }
        assertTrue(ex.message!!.contains("403"))
        assertFalse(ex.message!!.contains("secret-test-key"))
    }

    @Test
    fun `Gemini 전송은 응답이 시간 제한을 넘기면 끊는다`() {
        val url = startServer({ _, _, _ -> 200 to envelope(goodJson) }, delayMs = 1500)
        val started = System.nanoTime()
        assertThrows(java.io.IOException::class.java) { GeminiRewriteTransport("k", "m1", baseUrl = url).generate("q", 200) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1200, "시간 제한(200ms) 근처에서 끊겨야 한다")
    }

    @Test
    fun `Gemini 전송은 본문 텍스트가 없는 응답을 거부한다`() {
        val url = startServer({ _, _, _ -> 200 to """{"candidates":[]}""" })
        assertThrows(IllegalStateException::class.java) { GeminiRewriteTransport("k", "m1", baseUrl = url).generate("q", 2000) }
    }

    @Test
    fun `모델 이름에 경로·질의 문자가 들어가면 거부한다`() {
        for (bad in listOf("a/b", "a?key=x", "a b", "", "../x", "m:generateContent#")) {
            assertThrows(IllegalArgumentException::class.java, { GeminiRewriteTransport("k", bad) }, bad)
        }
    }

    @Test
    fun `실제 전송을 쓴 해석기는 서버 장애가 반복되면 차단기가 열린다`() {
        val hits = AtomicInteger()
        val url = startServer({ _, _, _ -> hits.incrementAndGet(); 500 to "{}" })
        val r = rewriter(GeminiRewriteTransport("k", "m1", baseUrl = url), failures = 2)
        repeat(5) { assertNull(r.rewrite("q$it")) }
        assertEquals(2, hits.get())
        assertTrue(r.breakerOpen())
    }

    @Test
    fun `실제 전송을 쓴 해석기는 서버 응답을 검증해 돌려준다`() {
        val url = startServer({ _, _, _ -> 200 to envelope(goodJson) })
        val rw = rewriter(GeminiRewriteTransport("k", "m1", baseUrl = url)).rewrite("야구 어깨")!!
        assertEquals(listOf("External Rotation", "Face Pull"), rw.hints)
    }

    @Test
    fun `cacheKey 는 NFKC·소문자·공백 정리`() {
        assertEquals("abc def", QueryRewriter.cacheKey("  ＡＢＣ\t DEF "))
        assertNull(QueryRewriter.cacheKey(" \n "))
    }
}

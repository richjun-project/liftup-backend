package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 11-21 대표 결정: 유료 사용자만 좋은 모델(플랜 carbon·오늘 추천 hydrogen), 무료·채팅은 이름 고정 flash.
 * 가짜 Gemini 서버로 실제 HTTP 요청(경로의 모델 이름·본문)을 본다. 유료 모델이 실패하면 기본 모델로 한 번 더.
 */
class GeminiModelRoutingTest {
    private lateinit var server: HttpServer
    private val calls = CopyOnWriteArrayList<Pair<String, String>>() // (모델, 본문)
    private val failing = mutableSetOf<String>()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var svc: GeminiAIService

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path // /v1beta/models/<model>:<method>
            val model = path.substringAfter("/models/").substringBefore(":")
            val body = ex.requestBody.readBytes().decodeToString()
            calls += model to body
            val (code, out) = when {
                model in failing -> 404 to """{"error":{"code":404,"message":"models/$model is not found"}}"""
                path.endsWith(":streamGenerateContent") ->
                    200 to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"PLAN-$model\"}]}}]}\n\n"
                else -> 200 to """{"candidates":[{"content":{"parts":[{"text":"OUT-$model"}]},"finishReason":"STOP"}],"modelVersion":"$model"}"""
            }
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        svc = GeminiAIService(mapper, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        mapOf(
            "apiKey" to "test-key",
            "model" to "gemini-3.8-flash",
            "planModel" to "gemini-3.8-flash",
            "planModelPro" to "gemini-4-carbon",
            "recoModelPro" to "gemini-4-hydrogen",
            "baseUrl" to "http://127.0.0.1:${server.address.port}",
        ).forEach { (k, v) -> ReflectionTestUtils.setField(svc, k, v) }
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun models() = calls.map { it.first }

    @Test
    fun `유료 플랜은 carbon, 무료 플랜은 flash`() {
        assertEquals("OUT-gemini-4-carbon", svc.generatePlanContent("p", pro = true))
        assertEquals("OUT-gemini-3.8-flash", svc.generatePlanContent("p", pro = false))
        assertEquals(listOf("gemini-4-carbon", "gemini-3.8-flash"), models())
    }

    @Test
    fun `유료 오늘 추천은 hydrogen, 무료는 flash`() {
        assertEquals("OUT-gemini-4-hydrogen", svc.generateRecommendations("p", pro = true))
        assertEquals("OUT-gemini-3.8-flash", svc.generateRecommendations("p", pro = false))
        assertEquals(listOf("gemini-4-hydrogen", "gemini-3.8-flash"), models())
    }

    @Test
    fun `유료 모델이 실패하면 기본 모델로 한 번 더 — 무료는 다시 부르지 않는다`() {
        failing += "gemini-4-carbon"
        failing += "gemini-4-hydrogen"
        assertEquals("OUT-gemini-3.8-flash", svc.generatePlanContent("p", pro = true))
        assertEquals("OUT-gemini-3.8-flash", svc.generateRecommendations("p", pro = true))
        assertEquals(listOf("gemini-4-carbon", "gemini-3.8-flash", "gemini-4-hydrogen", "gemini-3.8-flash"), models())
        calls.clear(); failing += "gemini-3.8-flash"
        val out = svc.generatePlanContent("p", pro = false)
        assertEquals(listOf("gemini-3.8-flash"), models(), "무료 실패는 대체 모델 없이 그대로 오류 문구")
        assertTrue(!out.startsWith("OUT-"))
    }

    @Test
    fun `스트리밍 플랜도 유료는 carbon·생각 수준 low, 실패하면 flash 로`() {
        val got = svc.generatePlanContentStreaming("p", { _, _ -> }, pro = true)
        assertEquals("PLAN-gemini-4-carbon", got)
        val body = mapper.readTree(calls.single().second)
        assertEquals("low", body.at("/generationConfig/thinkingConfig/thinkingLevel").asText(), "스트리밍에도 실측과 같은 생각 수준")
        calls.clear(); failing += "gemini-4-carbon"
        assertEquals("PLAN-gemini-3.8-flash", svc.generatePlanContentStreaming("p", { _, _ -> }, pro = true))
        assertEquals(listOf("gemini-4-carbon", "gemini-3.8-flash"), models())
    }

    @Test
    fun `설정 파일의 모델은 이름으로 고정돼 있다(-latest 별칭 없음)`() {
        val yml = this::class.java.getResource("/application.yml")!!.readText()
        // 주석(# …)은 빼고 값만 본다
        val gemini = yml.substringAfter("\ngemini:").substringBefore("\n\n").lines().joinToString("\n") { it.substringBefore(" #").substringBefore("#").trimEnd() }
        mapOf("model" to "gemini-3.8-flash", "plan-model" to "gemini-3.8-flash", "plan-model-pro" to "gemini-4-carbon", "reco-model-pro" to "gemini-4-hydrogen")
            .forEach { (k, v) -> assertTrue(Regex("""\n\s+$k:\s+$v\b""").containsMatchIn(gemini), "$k 는 $v 여야 한다:\n$gemini") }
        assertTrue(!gemini.contains("-latest"), "gemini 설정에 -latest 별칭이 남아 있다")
    }
}

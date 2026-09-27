package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 채팅 Gemini 요청/응답 직렬화.
 *
 * 운영 ObjectMapper 는 application.yml 에서 SNAKE_CASE + NON_NULL 이다.
 * 같은 설정으로 검증하지 않으면 "로컬에선 되는데 운영에선 필드가 조용히 사라지는" 문제를 못 잡는다.
 */
class GeminiChatRequestTest {

    private val prodMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `thinking 수준을 주면 thinkingConfig 가 camelCase 로 들어간다`() {
        val json = prodMapper.writeValueAsString(buildChatRequest("hi", 0.7, 2048, "low"))
        assertTrue(json.contains("\"thinkingConfig\":{\"thinkingLevel\":\"low\"}"), json)
    }

    @Test
    fun `채팅 외 호출은 thinking 수준을 보내고 출력 한도를 8192 이상으로 둔다 (맞춤 추천 JSON 잘림)`() {
        val cfg = nonChatGenerationConfig(0.7, null, 2048, "low")
        assertEquals(NON_CHAT_MIN_OUTPUT_TOKENS, cfg.maxOutputTokens)
        val json = prodMapper.writeValueAsString(GeminiRequest(contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = "x")))), generationConfig = cfg))
        assertTrue(json.contains("\"thinkingConfig\":{\"thinkingLevel\":\"low\"}"), json)
        assertEquals(16384, nonChatGenerationConfig(0.7, 16384, 2048, null).maxOutputTokens)
    }

    @Test
    fun `thinking 수준이 없으면 thinkingConfig 자체를 보내지 않는다`() {
        val json = prodMapper.writeValueAsString(buildChatRequest("hi", 0.7, 2048, null))
        assertFalse(json.contains("thinking", ignoreCase = true), json)
    }

    @Test
    fun `응답의 camelCase 사용량·종료 사유가 SNAKE_CASE 매퍼에서도 읽힌다`() {
        val raw = """
            {"candidates":[{"content":{"parts":[{"text":"답"}],"role":"model"},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":640,"thoughtsTokenCount":835,"totalTokenCount":2675},
             "modelVersion":"gemini-3.8-flash"}
        """.trimIndent()
        val r = prodMapper.readValue(raw, GeminiResponse::class.java)
        assertEquals("STOP", r.candidates!!.first().finishReason)
        assertEquals(835, r.usageMetadata!!.thoughtsTokenCount)
        assertEquals(1200, r.usageMetadata!!.promptTokenCount)
        assertEquals("gemini-3.8-flash", r.modelVersion)
        assertEquals("답", r.candidates!!.first().content!!.parts.first().text)
    }

    @Test
    fun `양성 대조 - 이름을 명시하지 않은 camelCase 필드는 SNAKE_CASE 매퍼에서 사라진다`() {
        data class Naive(val usageMetadata: Map<String, Any>? = null)
        val r = prodMapper.readValue("""{"usageMetadata":{"a":1}}""", Naive::class.java)
        assertNull(r.usageMetadata, "이 테스트가 실패하면 매퍼 설정이 운영과 달라진 것 — 위 테스트들이 무의미해진다")
    }

    @Test
    fun `thinking 거부 400 일 때만 재시도한다`() {
        assertTrue(shouldRetryWithoutThinking(400, """{"error":{"message":"Unknown name \"thinkingLevel\""}}""", "low"))
        assertFalse(shouldRetryWithoutThinking(400, """{"error":{"message":"API key not valid"}}""", "low"))
        assertFalse(shouldRetryWithoutThinking(429, "thinking quota", "low"))
        assertFalse(shouldRetryWithoutThinking(400, "thinking", null), "설정을 안 보냈으면 재시도할 이유가 없다")
    }
}

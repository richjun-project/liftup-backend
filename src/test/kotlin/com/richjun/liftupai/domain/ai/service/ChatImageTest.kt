package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.ai.dto.ChatRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 채팅 사진: 앱이 보내는 images 를 받아 Gemini 요청에 사진 조각으로 넣는다.
 * 2026-10-10 감사 전까지 서버가 images 를 받지 않아(필드 없음) 트레이너가 사진을 본 적이 없었다.
 */
class ChatImageTest {

    private val prodMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `앱이 보내는 채팅 본문의 images 를 운영 매퍼로 읽는다`() {
        val req: ChatRequest = prodMapper.readValue("""{"message":"이 사진 분석해줘","images":["https://dearglobe.s3.ap-northeast-2.amazonaws.com/chat/1/a.jpg"]}""")
        assertEquals(listOf("https://dearglobe.s3.ap-northeast-2.amazonaws.com/chat/1/a.jpg"), req.images)
    }

    @Test
    fun `사진이 있으면 텍스트 뒤에 inline 사진 조각이 붙는다`() {
        val json = prodMapper.writeValueAsString(buildChatRequest("prompt", 0.7, 2048, null, listOf(InlineData("image/png", "QUJD"))))
        val parts = prodMapper.readTree(json).path("contents")[0].path("parts")
        assertEquals(2, parts.size(), json)
        assertEquals("prompt", parts[0].path("text").asText())
        val inline = parts[1].path("inline_data")
        assertEquals("image/png", inline.path("mime_type").asText(), json)
        assertEquals("QUJD", inline.path("data").asText())
    }

    @Test
    fun `사진이 없으면 예전과 같은 텍스트 한 조각이다`() {
        val json = prodMapper.writeValueAsString(buildChatRequest("prompt", 0.7, 2048, null))
        assertEquals(1, prodMapper.readTree(json).path("contents")[0].path("parts").size())
    }

    @Test
    fun `확장자로 MIME 을 고른다`() {
        assertEquals("image/png", imageMimeType("https://x/a.PNG"))
        assertEquals("image/webp", imageMimeType("https://x/a.webp?sig=1"))
        assertEquals("image/jpeg", imageMimeType("https://x/a.jpeg"))
        assertEquals("image/jpeg", imageMimeType("https://x/noext"))
    }

    @Test
    fun `사진을 못 불러왔으면 본 척하지 말라고 알리고, 첨부가 없으면 아무것도 붙이지 않는다`() {
        assertEquals("", chatImageNote(requested = 0, loaded = 0))
        assertTrue(chatImageNote(requested = 1, loaded = 1).contains("[ATTACHED PHOTO]"))
        val failed = chatImageNote(requested = 1, loaded = 0)
        assertTrue(failed.contains("could not be loaded") && failed.contains("Do not guess"), failed)
        assertFalse(failed.contains("[ATTACHED PHOTO]"))
    }
}

package com.richjun.liftupai.domain.upload

import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 채팅 사진 업로드 계약 — 앱(1.1.27 이하)은 file 만 보내고(type 없음) 응답의 data.url 을 읽는다.
 * 2026-10-10 감사 전까지 type 이 필수라 전부 400 이었고, 성공했어도 url 필드가 없어 앱이 로컬 경로로 넘어갔다.
 */
@TestPropertySource(properties = ["upload.dir=build/test-uploads", "upload.use-s3=false"])
class ChatPhotoUploadTest : ApiTestSupport() {

    private var userId = 0L

    @BeforeEach
    fun setUp() {
        val u = newUser("photo"); userId = u.id; loginAs(u)
    }

    private fun png(): MockMultipartFile {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out)
        return MockMultipartFile("file", "photo.png", "image/png", out.toByteArray())
    }

    @Test
    fun `type 없이 올린 사진은 chat 으로 저장되고 응답에 url 이 있다`() {
        val r = call(multipart("/api/upload/image").file(png()))
        assertEquals(200, r.status, r.contentAsString)
        val data = body(r).path("data")
        val url = data.path("url").asText()
        assertTrue(url.isNotBlank(), r.contentAsString)
        assertEquals(data.path("image_url").asText(), url)
        assertTrue(url.contains("/chat/$userId/"), url)
        assertTrue(File("build/test-uploads/chat/$userId").isDirectory)
    }

    @Test
    fun `허용 목록 밖의 type(경로 조작)은 저장하지 않고 400`() {
        for (bad in listOf("../../etc", "chat/../../x", "avatar")) {
            val r = call(multipart("/api/upload/image").file(png()).param("type", bad))
            assertEquals(400, r.status, "$bad -> ${r.contentAsString}")
        }
        assertTrue(!File("build/etc").exists() && !File("build/test-uploads/avatar").exists())
    }

    @Test
    fun `식단처럼 허용된 type 은 그대로 쓴다`() {
        val r = call(multipart("/api/upload/image").file(png()).param("type", "MEAL"))
        assertEquals(200, r.status, r.contentAsString)
        assertTrue(body(r).path("data").path("url").asText().contains("/meal/$userId/"))
    }
}

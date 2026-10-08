package com.richjun.liftupai.domain.analytics

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/** 푸시 기기 등록에 앱 버전이 남는다 — 본문이 우선, 없으면 X-App-Version 헤더(1.1.26 이하 앱은 본문에 버전을 안 보낸다) */
class NotificationDeviceAppVersionTest : ApiTestSupport() {

    @Autowired private lateinit var deviceRepository: NotificationDeviceRepository
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = newUser("device")
        loginAs(user)
    }

    private fun register(body: String, version: String?): Int =
        call(post("/api/notifications/register").contentType(MediaType.APPLICATION_JSON).content(body).apply {
            version?.let { header(ClientInfo.HEADER_APP_VERSION, it) }
        }).status

    @Test
    fun `본문에 버전이 없으면 헤더의 앱 버전을 저장한다`() {
        val token = "tok-" + UUID.randomUUID()
        assertEquals(201, register("""{"device_token":"$token","platform":"ios"}""", "1.1.27+43"))
        assertEquals("1.1.27+43", deviceRepository.findByDeviceToken(token).get().appVersion)
    }

    @Test
    fun `본문의 버전이 헤더보다 우선이고 형식이 틀린 값은 버린다`() {
        val token = "tok-" + UUID.randomUUID()
        assertEquals(201, register("""{"device_token":"$token","platform":"android","app_version":"1.1.28+44"}""", "1.1.27+43"))
        assertEquals("1.1.28+44", deviceRepository.findByDeviceToken(token).get().appVersion)

        val bad = "tok-" + UUID.randomUUID()
        assertEquals(201, register("""{"device_token":"$bad","platform":"android"}""", "<script>"))
        assertNull(deviceRepository.findByDeviceToken(bad).get().appVersion)
    }
}

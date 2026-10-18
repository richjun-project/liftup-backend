package com.richjun.liftupai.domain.version

import com.richjun.liftupai.domain.version.entity.AppVersion
import com.richjun.liftupai.domain.version.entity.Platform
import com.richjun.liftupai.domain.version.repository.AppVersionRepository
import com.richjun.liftupai.domain.version.service.UpdateNudgeThrottle
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 기존 사용자 업데이트 유도(2026-10-18).
 * - 1.1.29 이하 앱은 update_required=true 일 때만 안내창을 띄운다 → 권장 안내는 RECOMMENDED + update_required=true,
 *   같은 기기엔 하루 한 번(예전 앱은 '나중에'를 기억하지 못한다).
 * - 최소 버전 미만(1.1.27 이하, 운동 화면 터치 먹통)은 필수 업데이트.
 * - 안내 문구는 사용자 언어로(예전엔 한 언어로만 저장돼 일본 사용자에게 한국어가 갔다).
 */
class AppVersionCheckTest : ApiTestSupport() {

    @Autowired private lateinit var versionRepository: AppVersionRepository
    @Autowired private lateinit var throttle: UpdateNudgeThrottle

    private var clock = Instant.parse("2026-10-20T00:00:00Z")
    private lateinit var latest: AppVersion

    @BeforeEach
    fun setUp() {
        throttle.now = { clock }
        latest = versionRepository.save(AppVersion(
            version = "9.1.30", versionCode = 9046, platform = Platform.IOS,
            minimumVersion = "9.1.28", minimumVersionCode = 9044,
            updateUrl = "https://apps.apple.com/app/id6752910787",
            releaseNotes = """{"ko":"알림·구독 화면 개선","en":"Better reminders and subscription screen","ja":"通知とサブスクリプション画面を改善しました"}""",
            recommendUpdate = true,
        ))
    }

    @AfterEach
    fun tearDown() {
        versionRepository.delete(latest)
        throttle.now = Instant::now
    }

    private fun check(code: Int, device: String, locale: String? = "ja") = body(call(
        post("/api/version/check" + (locale?.let { "?locale=$it" } ?: ""))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"current_version":"x","version_code":$code,"platform":"IOS","device_id":"$device"}""")
    )).path("data")

    @Test
    fun `낮은 버전은 권장 안내창을 하루 한 번 받고, 문구는 사용자 언어`() {
        val device = UUID.randomUUID().toString()
        val first = check(9045, device)
        assertEquals("RECOMMENDED", first.path("update_type").asText())
        assertEquals(true, first.path("update_required").asBoolean(), "예전 앱이 창을 띄우려면 true")
        assertEquals("通知とサブスクリプション画面を改善しました", first.path("release_notes").asText())

        val again = check(9045, device)
        assertEquals("RECOMMENDED", again.path("update_type").asText())
        assertEquals(false, again.path("update_required").asBoolean(), "같은 기기엔 하루 한 번")

        clock = clock.plus(Duration.ofHours(21))
        assertEquals(true, check(9045, device).path("update_required").asBoolean(), "하루 지나면 다시")
        assertEquals(true, check(9045, UUID.randomUUID().toString()).path("update_required").asBoolean(), "다른 기기는 따로")
    }

    @Test
    fun `최소 버전 미만은 필수, 최신은 안내 없음, 영어는 영어 문구`() {
        val old = check(9043, UUID.randomUUID().toString(), locale = "en")
        assertEquals("REQUIRED", old.path("update_type").asText())
        assertEquals(true, old.path("update_required").asBoolean())
        assertEquals("Better reminders and subscription screen", old.path("release_notes").asText())

        val current = check(9046, UUID.randomUUID().toString())
        assertEquals("OPTIONAL", current.path("update_type").asText())
        assertEquals(false, current.path("update_required").asBoolean())

        assertEquals("Better reminders and subscription screen", check(9046, "x", locale = null).path("release_notes").asText())
    }

    @Test
    fun `권장 안내를 끄면 예전처럼 창을 띄우지 않는다, 예전 평문 문구는 그대로`() {
        latest.recommendUpdate = false
        latest.releaseNotes = "버그 수정"
        versionRepository.save(latest)
        val r = check(9045, UUID.randomUUID().toString())
        assertEquals("RECOMMENDED", r.path("update_type").asText())
        assertEquals(false, r.path("update_required").asBoolean())
        assertEquals("버그 수정", r.path("release_notes").asText())
    }
}

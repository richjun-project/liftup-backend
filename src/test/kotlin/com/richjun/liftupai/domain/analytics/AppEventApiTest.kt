package com.richjun.liftupai.domain.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/** 앱 활성화 이벤트 수집 계약 — 허용 목록·파라미터 정제·배치 상한·사용자별 상한·탈퇴 삭제·클라이언트 헤더 */
class AppEventApiTest : ApiTestSupport() {

    @Autowired private lateinit var repository: AppEventRepository
    @Autowired private lateinit var service: AppEventService
    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = newUser("events")
        loginAs(user)
    }

    private fun send(body: String, version: String? = "1.1.27+43", platform: String? = "ios", testDevice: String? = null) =
        call(post("/api/v2/events").contentType(MediaType.APPLICATION_JSON).content(body).apply {
            version?.let { header(ClientInfo.HEADER_APP_VERSION, it) }
            platform?.let { header(ClientInfo.HEADER_PLATFORM, it) }
            testDevice?.let { header(ClientInfo.HEADER_TEST_DEVICE, it) }
        })

    @Test
    fun `허용된 이벤트만 저장하고 클라이언트 버전·플랫폼을 함께 남긴다`() {
        val now = System.currentTimeMillis()
        val r = send("""{"events":[
            {"name":"workout_start","params":{"source":"program","exercise_count":3,"light_start":true},"ts":$now},
            {"name":"first_workout_exit_reason","params":{"reason":"not_at_gym"}},
            {"name":"login_attempt","params":{"method":"google"}},
            {"name":"made_up_event"}
        ]}""")
        assertEquals(202, r.status, r.contentAsString)
        assertEquals(2, body(r).path("data").path("accepted").asInt())
        assertEquals(2, body(r).path("data").path("dropped").asInt())
        val rows = repository.findByUserIdOrderByIdAsc(user.id)
        assertEquals(listOf("workout_start", "first_workout_exit_reason"), rows.map { it.name })
        assertTrue(rows.all { it.appVersion == "1.1.27+43" && it.platform == "ios" && !it.testDevice })
        val params = objectMapper.readTree(rows[0].params)
        assertEquals("program", params.path("source").asText())
        assertEquals(3, params.path("exercise_count").asInt())
        assertTrue(params.path("light_start").asBoolean())
        assertNotNull(rows[0].occurredAt, "최근 시각의 ts 는 남는다")
        assertNull(rows[1].occurredAt, "ts 가 없으면 null")
    }

    @Test
    fun `자유 입력 문자열·중첩 값·규칙에 맞지 않는 키는 버린다`() {
        val r = send("""{"events":[{"name":"exercise_search","params":{
            "query_length":7,"result_count":12,"locale":"ja",
            "query":"my squat routine at home","Bad-Key":"x","nested":{"a":1},"list":[1,2],"ratio":1.5,"nan":null
        }}]}""")
        assertEquals(202, r.status, r.contentAsString)
        val params = objectMapper.readTree(repository.findByUserIdOrderByIdAsc(user.id).single().params)
        assertEquals(setOf("query_length", "result_count", "locale", "ratio"), params.fieldNames().asSequence().toSet())
    }

    @Test
    fun `한 번에 20개까지만 받고 사용자별 분당 상한을 넘으면 버린다`() {
        val many = (1..25).joinToString(",") { """{"name":"set_complete","params":{"n":$it}}""" }
        val r = send("""{"events":[$many]}""")
        assertEquals(20, body(r).path("data").path("accepted").asInt())
        assertEquals(5, body(r).path("data").path("dropped").asInt())

        val capped = AppEventService(repository, objectMapper, perUserPerMinute = 3)
        val other = newUser("events-cap")
        val res = capped.record(other.id, List(5) { AppEventItem(name = "set_complete") }, ClientInfo())
        assertEquals(3, res.accepted)
        assertEquals(2, res.dropped)
        assertEquals(0, capped.record(other.id, listOf(AppEventItem(name = "set_complete")), ClientInfo()).accepted)
    }

    @Test
    fun `형식이 틀린 헤더와 너무 먼 시각은 저장하지 않는다`() {
        val old = System.currentTimeMillis() - 30L * 86_400_000L
        send("""{"events":[{"name":"workout_complete","ts":$old}]}""", version = "1.1.27; DROP TABLE", platform = "Windows", testDevice = "1")
        val row = repository.findByUserIdOrderByIdAsc(user.id).single()
        assertNull(row.appVersion)
        assertNull(row.platform)
        assertTrue(row.testDevice, "테스트 기기 표시는 남긴다(분석에서 뺀다)")
        assertNull(row.occurredAt)
    }

    @Test
    fun `본문 모양이 틀리면 500 이 아니라 400 이다`() {
        val r = send("""{"events":"not-a-list"}""")
        assertEquals(400, r.status, r.contentAsString)
    }

    @Test
    fun `빈 배치는 아무것도 저장하지 않는다`() {
        val r = send("""{"events":[]}""")
        assertEquals(202, r.status)
        assertTrue(repository.findByUserIdOrderByIdAsc(user.id).isEmpty())
    }

    @Test
    fun `보관 기간이 지난 이벤트는 매일 정리한다`() {
        val now = com.richjun.liftupai.global.time.AppTime.utcNow()
        repository.save(AppEvent(userId = user.id, name = "workout_start", createdAt = now.minusDays(200)))
        repository.save(AppEvent(userId = user.id, name = "set_complete", createdAt = now.minusDays(3)))
        assertTrue(service.purgeOld() >= 1)
        assertEquals(listOf("set_complete"), repository.findByUserIdOrderByIdAsc(user.id).map { it.name })
    }

    @Test
    fun `회원 탈퇴 삭제 목록에 app_events 가 있다 - FK 가 없어 메타모델 감사에 잡히지 않는 테이블이다`() {
        // H2 에는 운영에만 있는 레거시 테이블이 없어 탈퇴 전체를 실행할 수 없다 — FK 커버리지 감사와 같은 방식으로 삭제문 자체를 확인한다
        val source = java.io.File("src/main/kotlin/com/richjun/liftupai/domain/user/service/UserService.kt").readText()
        val body = source.substring(source.indexOf("private fun deleteAllUserData(userId: Long)"))
        assertTrue(body.contains("DELETE FROM app_events WHERE user_id = :userId"), "deleteAllUserData 에 app_events 삭제가 없다")
    }
}

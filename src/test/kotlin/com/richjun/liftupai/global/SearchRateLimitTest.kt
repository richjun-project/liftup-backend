package com.richjun.liftupai.global

import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.global.ratelimit.RateLimitProperties
import com.richjun.liftupai.global.ratelimit.SearchRateLimitInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/** 검색·제안·추천·대체 운동 — 사용자별 분당 120회, 121번째는 429 + Retry-After */
class SearchRateLimitTest : ApiTestSupport() {

    @Autowired private lateinit var interceptor: SearchRateLimitInterceptor
    @Autowired private lateinit var properties: RateLimitProperties

    private var fakeNow = System.currentTimeMillis()

    @AfterEach
    fun restoreClock() {
        interceptor.clock = System::currentTimeMillis
    }

    private fun useFakeClock() {
        fakeNow = System.currentTimeMillis()
        interceptor.clock = { fakeNow }
    }

    private fun search(locale: String? = null): MockHttpServletResponse {
        val req = get("/api/v2/exercises").param("page", "0").param("size", "1")
        if (locale != null) req.param("locale", locale)
        return call(req)
    }

    @Test
    fun `기본 한도는 분당 120회다`() {
        assertEquals(120, properties.search.perMinute)
    }

    @Test
    fun `121번째 요청은 429 와 Retry-After 를 받는다`() {
        loginAs(newUser("rl"))
        useFakeClock()
        repeat(120) { i -> assertEquals(200, search().status, "${i + 1}번째") }

        val limited = search()
        assertEquals(429, limited.status)
        val retryAfter = limited.getHeader("Retry-After")!!.toInt()
        assertTrue(retryAfter in 1..60, "Retry-After=$retryAfter")
        assertEquals("RATE_LIMIT", body(limited).path("error").path("code").asText())
        assertEquals("0", limited.getHeader("X-RateLimit-Remaining"))
    }

    @Test
    fun `한도는 사용자별이라 다른 사용자는 영향이 없다`() {
        val heavy = newUser("rl-heavy")
        val other = newUser("rl-other")
        useFakeClock()
        loginAs(heavy)
        repeat(120) { search() }
        assertEquals(429, search().status)

        loginAs(other)
        assertEquals(200, search().status)
    }

    @Test
    fun `1분이 지나면 다시 요청할 수 있다 (슬라이딩 윈도우)`() {
        loginAs(newUser("rl-window"))
        useFakeClock()
        repeat(60) { search() }
        fakeNow += 30_000
        repeat(60) { search() }
        assertEquals(429, search().status)

        fakeNow += 31_000 // 처음 60개가 윈도우 밖으로 나간다
        assertEquals(200, search().status)
        repeat(59) { assertEquals(200, search().status) }
        assertEquals(429, search().status)

        fakeNow += 120_000
        assertEquals(200, search().status)
    }

    @Test
    fun `검색 제안 추천 대체 운동 요청이 한 예산을 나눠 쓴다`() {
        loginAs(newUser("rl-shared"))
        useFakeClock()
        repeat(118) { search() }
        assertEquals(200, call(get("/api/v2/exercises/suggestions")).status)           // 119
        assertEquals(200, call(get("/api/v2/exercises/1/substitutes")).status)         // 120
        val recommend = call(post("/api/v2/templates/recommend").contentType(MediaType.APPLICATION_JSON).content("{}"))
        assertEquals(429, recommend.status)
        assertEquals(429, call(get("/api/v2/exercises/suggestions")).status)
        assertEquals(429, call(get("/api/v2/exercises/search").param("q", "squat")).status)
    }

    @Test
    fun `즐겨찾기와 최근 운동 같은 가벼운 경로는 제한하지 않는다`() {
        loginAs(newUser("rl-light"))
        useFakeClock()
        repeat(120) { search() }
        assertEquals(429, search().status)
        assertEquals(200, call(get("/api/v2/exercises/favorites")).status)
        assertEquals(200, call(get("/api/v2/exercises/recent")).status)
        assertEquals(200, call(put("/api/v2/exercises/1/favorite")).status)
    }

    @Test
    fun `429 메시지는 로케일에 맞는다`() {
        loginAs(newUser("rl-i18n"))
        useFakeClock()
        repeat(120) { search() }
        assertTrue(body(search("ko")).path("error").path("message").asText().contains("요청이 너무 많습니다"))
        assertTrue(body(search("ja")).path("error").path("message").asText().contains("リクエストが多すぎます"))
        assertTrue(body(search("en")).path("error").path("message").asText().contains("Too many requests"))
    }

    @Test
    fun `로그인하지 않은 요청은 접속 주소별로 센다`() {
        SecurityContextHolder.clearContext()
        useFakeClock()
        repeat(120) { assertEquals(404, call(get("/api/v2/exercises/999999999/substitutes")).status) }
        assertEquals(429, call(get("/api/v2/exercises/999999999/substitutes")).status)
    }
}

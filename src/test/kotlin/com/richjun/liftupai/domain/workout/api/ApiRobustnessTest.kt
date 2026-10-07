package com.richjun.liftupai.domain.workout.api

import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 잘못된 파라미터·본문·과도한 목록·초장문 질의는 500 이 아니라 400(VALID001)으로 끝나고 ERROR 로그를 남기지 않는다.
 * (ERROR 로그 부재는 ApiTestSupport 가 매 테스트 후 확인한다.)
 */
class ApiRobustnessTest : ApiTestSupport() {

    @Autowired private lateinit var index: ExerciseSearchIndexService

    private val list = "/api/v2/exercises"
    private val suggestions = "/api/v2/exercises/suggestions"
    private val recommend = "/api/v2/templates/recommend"

    @BeforeEach
    fun login() {
        loginAs(newUser("robust"))
    }

    private fun recommend(body: String?) = call(
        post(recommend).contentType(MediaType.APPLICATION_JSON).content(body ?: "")
    )

    @Test
    fun `목록 숫자 파라미터가 숫자가 아니거나 범위를 벗어나면 400 이다`() {
        listOf(
            mapOf("page" to "abc"),
            mapOf("page" to "0", "size" to "abc"),
            mapOf("page" to "99999999999999"), // int 오버플로
            mapOf("page" to "-1"),
            mapOf("page" to "0", "size" to "0"),
            mapOf("page" to "0", "size" to "-5"),
            mapOf("page" to "999999"),
            mapOf("page" to "0", "hasGif" to "maybe")
        ).forEach { params ->
            val req = get(list)
            params.forEach { (k, v) -> req.param(k, v) }
            assertValidationError(call(req), "list $params")
        }
    }

    @Test
    fun `size 가 100 을 넘으면 100 으로 줄이고 정상 응답한다`() {
        val r = call(get(list).param("page", "0").param("size", "100000"))
        assertEquals(200, r.status)
        assertEquals(100, body(r).path("data").path("size").asInt())
    }

    @Test
    fun `카테고리 장비 로케일이 잘못되면 400 이다`() {
        assertValidationError(call(get(list).param("page", "0").param("category", "FOO")), "category")
        assertValidationError(call(get(list).param("page", "0").param("equipment", "FOO")), "equipment")
        assertValidationError(call(get(list).param("page", "0").param("equipment", (1..50).joinToString(",") { "DUMBBELL" })), "equipment 개수")
        assertValidationError(call(get(list).param("page", "0").param("locale", "ko; DROP TABLE")), "locale 형식")
        assertValidationError(call(get(list).param("page", "0").param("locale", "x".repeat(200))), "locale 길이")
        // 정상 로케일 표기는 통과
        assertEquals(200, call(get(list).param("page", "0").param("locale", "ja_JP")).status)
        assertEquals(200, call(get(list).param("page", "0").param("locale", "ko-KR")).status)
    }

    @Test
    fun `너무 긴 검색어는 400 이고 한도 안의 긴 검색어는 정상이다`() {
        val started = System.nanoTime()
        assertValidationError(call(get(list).param("page", "0").param("search", "dumbbell curl row ".repeat(400))), "7200자")
        assertValidationError(call(get(list).param("search", "a".repeat(101))), "101자")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "긴 질의 거절은 즉시여야 한다")
        assertEquals(200, call(get(list).param("page", "0").param("search", "a".repeat(100))).status)
        assertEquals(200, call(get(list).param("page", "0").param("search", "'; DROP TABLE exercises;--")).status)
        assertEquals(200, call(get(list).param("page", "0").param("search", "💪🏋️")).status)
        assertEquals(200, call(get(list).param("page", "0").param("search", "a\u0000b")).status)
    }

    @Test
    fun `엔진은 어떤 길이의 질의가 와도 비용이 제한된다`() {
        // 컨트롤러가 막지 않는 내부 경로(프로그램 검색 등)도 같은 인덱스를 쓴다 — 8KB 적대적 입력이 와도 수백 ms 안에 끝나야 한다
        index.engine() // 인덱스 빌드는 측정에서 뺀다
        index.searchIds("squat", null, null, "en")
        listOf(
            "dumbbell curl row ".repeat(450),
            "덤벨 컬 로우 ".repeat(1300),
            "a".repeat(8000),
            "ダンベル ".repeat(1500),
            "dumbbell 덤벨 ダンベル curl 컬 ".repeat(300)
        ).forEach { q ->
            val started = System.nanoTime()
            index.searchIds(q, null, null, "en")
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 1500, "질의(${q.length}자)가 ${ms}ms 걸렸다")
        }
    }

    @Test
    fun `제안 파라미터 검증 - limit 가 숫자가 아니거나 0 이하이면 400`() {
        assertValidationError(call(get(suggestions).param("limit", "abc")), "limit=abc")
        assertValidationError(call(get(suggestions).param("limit", "0")), "limit=0")
        assertValidationError(call(get(suggestions).param("limit", "-3")), "limit=-3")
        assertEquals(200, call(get(suggestions).param("limit", "1000")).status) // 상한 초과는 30 으로 줄인다
    }

    @Test
    fun `제안의 exclude_ids 는 형식과 개수 상한을 둔다`() {
        assertValidationError(call(get(suggestions).param("exclude_ids", "1,2,x")), "숫자 아님")
        assertValidationError(call(get(suggestions).param("exclude_ids", "99999999999999999999")), "오버플로")
        assertValidationError(call(get(suggestions).param("exclude_ids", (1..201).joinToString(","))), "201개")
        val started = System.nanoTime()
        assertValidationError(call(get(suggestions).param("exclude_ids", (1..200000).joinToString(","))), "200000개(1.3MB)")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
        assertEquals(200, call(get(suggestions).param("exclude_ids", (1..200).joinToString(","))).status)
    }

    @Test
    fun `제안의 카테고리 근육군 로케일이 잘못되면 400 이다`() {
        assertValidationError(call(get(suggestions).param("category", "FOO")), "category")
        assertValidationError(call(get(suggestions).param("muscle_groups", "FOO")), "muscle")
        assertValidationError(call(get(suggestions).param("muscle_groups", (1..30).joinToString(",") { "CHEST" })), "muscle 개수")
        assertEquals(200, call(get(suggestions).param("muscle_groups", "CHEST,BACK")).status)
    }

    @Test
    fun `대체 운동 id 가 숫자가 아니면 400 이고 없는 id 는 404 이다`() {
        assertValidationError(call(get("/api/v2/exercises/abc/substitutes")), "id=abc")
        assertEquals(404, call(get("/api/v2/exercises/999999999/substitutes")).status)
        assertValidationError(call(get("/api/v2/exercises/1/substitutes").param("locale", "!!")), "locale")
    }

    @Test
    fun `프로그램 운동 검색은 q 필수와 길이 상한을 둔다`() {
        assertValidationError(call(get("/api/v2/exercises/search")), "q 없음")
        assertValidationError(call(get("/api/v2/exercises/search").param("q", "x".repeat(300))), "q 길이")
        assertValidationError(call(get("/api/v2/exercises/search").param("q", "squat").param("page", "abc")), "page=abc")
        assertEquals(200, call(get("/api/v2/exercises/search").param("q", "squat")).status)
    }

    @Test
    fun `추천 요청 본문이 깨졌거나 비었거나 타입이 틀리면 400 이다`() {
        assertValidationError(recommend("{bad"), "깨진 JSON")
        assertValidationError(recommend(null), "빈 본문")
        assertValidationError(recommend("""{"weekly_days":"abc"}"""), "weekly_days 문자열")
        assertValidationError(recommend("""{"weekly_days":99999999999}"""), "weekly_days 오버플로")
        assertValidationError(recommend("""{"goals":[null]}"""), "goals 원소 null")
        assertValidationError(recommend("""{"goals":"muscle_gain"}"""), "goals 가 배열 아님")
        assertValidationError(
            call(post(recommend).contentType(MediaType.TEXT_PLAIN).content("hello")), "text/plain"
        )
    }

    @Test
    fun `추천 요청의 범위와 목록 크기 상한 - 부상 2만 개는 SQL 없이 즉시 400`() {
        assertValidationError(recommend("""{"weekly_days":999}"""), "weekly_days")
        assertValidationError(recommend("""{"weekly_days":0}"""), "weekly_days=0")
        assertValidationError(recommend("""{"session_duration":-5}"""), "session_duration")
        assertValidationError(recommend("""{"age":-1}"""), "age")
        assertValidationError(recommend("""{"goals":[${(1..11).joinToString(",") { "\"g$it\"" }}]}"""), "goals 11개")
        assertValidationError(recommend("""{"equipment":[${(1..21).joinToString(",") { "\"e$it\"" }}]}"""), "equipment 21개")
        assertValidationError(recommend("""{"focus_areas":[${(1..11).joinToString(",") { "\"f$it\"" }}]}"""), "focus 11개")
        assertValidationError(recommend("""{"injuries":["${"k".repeat(65)}"]}"""), "원소 길이")
        assertValidationError(recommend("""{"injuries":[${(1..16).joinToString(",") { "\"i$it\"" }}]}"""), "injuries 16개")

        stats().clear()
        val started = System.nanoTime()
        val huge = recommend("""{"injuries":[${(1..20000).joinToString(",") { "\"i$it\"" }}]}""")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertValidationError(huge, "부상 20000개")
        assertTrue(ms < 1000, "큰 본문 거절이 ${ms}ms 걸렸다")
        assertEquals(0L, stats().prepareStatementCount, "거절된 요청은 SQL 을 쓰지 않는다")
    }

    @Test
    fun `추천은 정상 요청이면 200 이고 부상이 5개여도 SQL 이 소수다`() {
        recommend("""{"experience_level":"beginner","goals":["muscle_gain"],"weekly_days":3,"session_duration":30}""") // 캐시 예열
        stats().clear()
        val r = recommend(
            """{"experience_level":"beginner","goals":["muscle_gain"],"weekly_days":3,"session_duration":30,
                "equipment":["DUMBBELL"],"injuries":["knee","shoulder","back","wrist","ankle"],"focus_areas":["glutes"]}"""
        )
        assertEquals(200, r.status, r.contentAsString)
        assertTrue(stats().prepareStatementCount <= 10, "SQL ${stats().prepareStatementCount}개")
    }

    @Test
    fun `지원하지 않는 메서드는 500 이 아니라 405 다`() {
        val r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(list))
        assertEquals(405, r.status)
    }

    @Test
    fun `큰 JSON 본문은 파싱 전에 길이로 거절한다`() {
        val big = """{"injuries":["${"x".repeat(40_000)}"]}"""
        assertValidationError(recommend(big), "40KB 본문")
    }
}

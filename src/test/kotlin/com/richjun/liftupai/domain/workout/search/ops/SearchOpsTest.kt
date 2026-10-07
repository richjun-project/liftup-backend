package com.richjun.liftupai.domain.workout.search.ops

import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.io.File
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

/** 검색 운영 — 개인정보 없는 질의 로그, 0건 질의 집계, 런타임 동의어 */
class SearchOpsTest : ApiTestSupport() {

    @Autowired private lateinit var recorder: SearchOpsRecorder
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var index: ExerciseSearchIndexService
    @Autowired private lateinit var synonymRepository: SearchSynonymRepository

    @BeforeEach
    fun clean() {
        recorder.autoFlush = false // 스케줄러가 큐를 가로채지 않게 — 이 테스트가 직접 flush 한다
        recorder.flush()
        jdbc.update("DELETE FROM search_query_log")
        synonymRepository.deleteAll()
        loginAs(newUser("ops"))
    }

    @AfterEach
    fun restoreTtl() {
        recorder.autoFlush = true
        index.synonymTtlMillis = 60_000L
        synonymRepository.deleteAll()
        index.reloadSynonyms()
    }

    private fun rows(where: String = "1=1", vararg args: Any) =
        jdbc.queryForList("SELECT * FROM search_query_log WHERE $where ORDER BY id", *args)

    @Test
    fun `목록 검색은 정규화된 질의 결과 수 지연 로케일을 기록하고 첫 페이지만 기록한다`() {
        val q = "  Squat  "
        call(get("/api/v2/exercises").param("page", "0").param("search", q).param("locale", "ja_JP").param("equipment", "BARBELL"))
        call(get("/api/v2/exercises").param("page", "1").param("search", q).param("locale", "ja_JP").param("equipment", "BARBELL"))
        assertEquals(1, recorder.pendingCount(), "다음 페이지는 같은 검색이므로 기록하지 않는다")
        assertEquals(1, recorder.flush())

        val row = rows().single()
        assertEquals("squat", row["QUERY_TEXT"])
        assertEquals("ja-jp", row["LOCALE"])
        assertEquals("exercise_list", row["SOURCE"])
        assertTrue((row["RESULT_COUNT"] as Number).toInt() > 0)
        assertTrue((row["LATENCY_MS"] as Number).toInt() >= 0)
        assertEquals(true, row["HAS_FILTERS"])
        assertTrue(row["CREATED_AT"] != null)
    }

    @Test
    fun `질의가 없는 브라우즈 요청은 기록하지 않는다`() {
        call(get("/api/v2/exercises").param("page", "0"))
        call(get("/api/v2/exercises").param("page", "0").param("search", "   "))
        assertEquals(0, recorder.pendingCount())
    }

    @Test
    fun `로그 테이블에는 사용자 식별자나 부상 컬럼이 없고 개인정보 질의는 남기지 않는다`() {
        val columns = jdbc.queryForList(
            "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'search_query_log'", String::class.java
        )
        assertEquals(
            setOf("id", "query_text", "locale", "result_count", "latency_ms", "source", "has_filters", "created_at"),
            columns.toSet()
        )
        assertFalse(columns.any { it.contains("user") || it.contains("injur") })

        assertFalse(recorder.record("contact me at someone@example.com", "en", 0, 5, "exercise_list"))
        assertFalse(recorder.record("내 번호 01012345678", "ko", 0, 5, "exercise_list"))
        assertFalse(recorder.record("https://evil.example/x", "en", 0, 5, "exercise_list"))
        assertFalse(recorder.record("   ", "en", 0, 5, "exercise_list"))
        assertFalse(recorder.record(null, "en", 0, 5, "exercise_list"))
        assertTrue(recorder.record("5x5 stronglifts 531", "en", 3, 5, "exercise_list"), "짧은 숫자가 든 정상 질의는 기록한다")
    }

    @Test
    fun `질의 정규화 - 전각 소문자 제어문자 공백 길이`() {
        assertEquals("squat", SearchOpsRecorder.normalize("ＳＱＵＡＴ"))
        assertEquals("bench press", SearchOpsRecorder.normalize("Bench\u0000\t  PRESS\n"))
        assertEquals("スクワット", SearchOpsRecorder.normalize("スクワット"))
        assertNull(SearchOpsRecorder.normalize("\u0000\u0001"))
        val long = SearchOpsRecorder.normalize("가".repeat(300))!!
        assertEquals(100, long.length)
    }

    @Test
    fun `많은 기록도 배치로 모두 쓰고 큐를 비운다`() {
        repeat(1200) { assertTrue(recorder.record("bulk query $it", "ko", it % 3, 4, "exercise_list")) }
        assertEquals(1200, recorder.pendingCount())
        assertEquals(1200, recorder.flush())
        assertEquals(0, recorder.pendingCount())
        assertEquals(1200, jdbc.queryForObject("SELECT COUNT(*) FROM search_query_log", Int::class.java))
    }

    @Test
    fun `프로그램 운동 검색도 같은 로그에 남는다`() {
        call(get("/api/v2/exercises/search").param("q", "leg press").param("locale", "en"))
        recorder.flush()
        val row = rows("source = 'program_search'").single()
        assertEquals("leg press", row["QUERY_TEXT"])
    }

    @Test
    fun `0건 질의 상위 목록 스크립트는 최근 N일의 0건 질의를 많은 순으로 뽑는다`() {
        val now = LocalDateTime.now()
        fun log(q: String, count: Int, daysAgo: Long) = jdbc.update(
            "INSERT INTO search_query_log (query_text, locale, result_count, latency_ms, source, has_filters, created_at) VALUES (?,?,?,?,?,?,?)",
            q, "ko", count, 3, "exercise_list", false, Timestamp.valueOf(now.minusDays(daysAgo))
        )
        repeat(5) { log("레그 익스텐션", 0, 1) }
        repeat(3) { log("t바 로우", 0, 2) }
        repeat(2) { log("t바 로우", 0, 20) } // 기간 밖
        repeat(9) { log("스쿼트", 4, 1) }      // 결과가 있었던 질의
        repeat(9) { log("아주 오래된 0건", 0, 30) }

        val sql = File("scripts/ops/search_zero_results.sql").readText()
            .lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
        val statements = sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(statements.size >= 2, "SET + SELECT")
        statements.dropLast(1).forEach { jdbc.execute(it) }
        val result = jdbc.queryForList(statements.last())

        assertEquals(listOf("레그 익스텐션", "t바 로우"), result.map { it["QUERY_TEXT"] })
        assertEquals(listOf(5L, 3L), result.map { (it["SEARCHES"] as Number).toLong() })
    }

    @Test
    fun `동의어 줄은 형식 검증을 거쳐 읽히고 재배포 없이 바뀐다`() {
        synonymRepository.saveAll(
            listOf(
                SearchSynonym(line = "레그 익스텐션|leg extension => 레그 익스텐션|leg extension machine"),
                SearchSynonym(line = "티바로우|t bar row =>> t바 로우|t-bar row"),
                SearchSynonym(line = "화살표 없음"),
                SearchSynonym(line = " => 빈 왼쪽"),
                SearchSynonym(line = "a => b => c"),
                SearchSynonym(line = "꺼진 줄 => off", enabled = false),
                SearchSynonym(line = "# 주석 => x")
            )
        )
        assertEquals(
            listOf("레그 익스텐션|leg extension => 레그 익스텐션|leg extension machine", "티바로우|t bar row =>> t바 로우|t-bar row"),
            index.reloadSynonyms()
        )

        // 켜고 끄고 새 줄을 넣으면 TTL(여기서는 0) 후 다음 조회에 바로 반영된다
        index.synonymTtlMillis = 0L
        synonymRepository.save(SearchSynonym(line = "힙쓰러스트 => 힙 쓰러스트|hip thrust"))
        assertEquals(3, index.activeSynonymLines().size)
        synonymRepository.findAll().filter { it.line.startsWith("티바로우") }.forEach { synonymRepository.delete(it) }
        assertEquals(2, index.activeSynonymLines().size)
    }

    @Test
    fun `동의어 테이블 읽기가 실패해도 이전 목록을 유지한다`() {
        allowedErrorLoggers += "org.hibernate.engine.jdbc.spi.SqlExceptionHelper" // 일부러 테이블을 없앴으니 하이버네이트가 ERROR 를 남긴다
        synonymRepository.save(SearchSynonym(line = "가 => 나"))
        assertEquals(listOf("가 => 나"), index.reloadSynonyms())
        jdbc.execute("ALTER TABLE search_synonyms RENAME TO search_synonyms_tmp")
        try {
            assertEquals(listOf("가 => 나"), index.reloadSynonyms(), "읽기 실패 시 이전 목록 유지")
        } finally {
            jdbc.execute("ALTER TABLE search_synonyms_tmp RENAME TO search_synonyms")
        }
    }

    @Test
    fun `동의어 줄 검증기`() {
        assertEquals("a|b => c", SynonymLines.validate("  a|b => c  "))
        assertEquals("a =>> c|d", SynonymLines.validate("a =>> c|d"))
        assertNull(SynonymLines.validate(""))
        assertNull(SynonymLines.validate("a => "))
        assertNull(SynonymLines.validate("a|| => |"))
        assertNull(SynonymLines.validate("x".repeat(501) + " => y"))
        assertNull(SynonymLines.validate("a => b\u0000"))
        assertNull(SynonymLines.validate(UUID.randomUUID().toString()))
    }
}

package com.richjun.liftupai.domain.workout.api

import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * 목록 API 의 SQL 수 — 이전에는 페이지(20개)마다 22개, 100개는 102개, 페이지 없는 목록은 1225개였다
 * (운동마다 근육군을 따로 읽는 N+1 + 로케일 폴백 번역 쿼리). Hibernate 통계로 센다.
 */
class ExerciseListQueryCountTest : ApiTestSupport() {

    @Autowired private lateinit var index: ExerciseSearchIndexService

    private val list = "/api/v2/exercises"

    @BeforeEach
    fun loginAndWarm() {
        loginAs(newUser("sqlcount"))
        // 캐시(검색 인덱스·카탈로그)는 앱 기동 때 데워진다 — 첫 요청 비용은 이 테스트의 대상이 아니다
        call(get(list).param("page", "0").param("size", "1").param("locale", "ja"))
        index.engine()
    }

    private fun sqlOf(block: () -> Unit): Long {
        stats().clear()
        block()
        return stats().prepareStatementCount
    }

    @Test
    fun `한 페이지 20개는 SQL 4개 이하다`() {
        var items = 0
        val sql = sqlOf {
            val r = call(get(list).param("page", "0").param("size", "20").param("locale", "ja"))
            assertEquals(200, r.status)
            items = body(r).path("data").path("content").size()
        }
        assertEquals(20, items)
        assertTrue(sql <= 4, "SQL ${sql}개")
    }

    @Test
    fun `한 페이지 100개는 SQL 4개 이하다 (검색어와 장비 필터가 있어도)`() {
        val browse = sqlOf {
            val r = call(get(list).param("page", "0").param("size", "100").param("locale", "ko"))
            assertEquals(100, body(r).path("data").path("content").size())
        }
        assertTrue(browse <= 4, "browse SQL ${browse}개")

        val search = sqlOf {
            val r = call(get(list).param("page", "0").param("size", "100").param("locale", "ko").param("search", "스쿼트").param("equipment", "BARBELL,DUMBBELL"))
            assertEquals(200, r.status)
            assertTrue(body(r).path("data").path("content").size() > 0)
        }
        assertTrue(search <= 4, "search SQL ${search}개")
    }

    @Test
    fun `페이지 없는 목록은 최대 100개이고 SQL 4개 이하다`() {
        var items = 0
        val sql = sqlOf {
            val r = call(get(list).param("locale", "en"))
            assertEquals(200, r.status)
            items = body(r).path("data").size()
        }
        assertEquals(100, items, "전체 카탈로그가 아니라 100개로 잘려야 한다")
        assertTrue(sql <= 4, "SQL ${sql}개")

        val searchSql = sqlOf {
            val r = call(get(list).param("search", "squat").param("hasGif", "true"))
            assertEquals(200, r.status)
            assertTrue(body(r).path("data").size() in 1..100)
        }
        assertTrue(searchSql <= 4, "검색 SQL ${searchSql}개")
    }

    @Test
    fun `0건 응답의 대안 목록과 가려진 결과 수를 계산해도 SQL 은 4개 이하다`() {
        val sql = sqlOf {
            val r = call(get(list).param("page", "0").param("locale", "en").param("search", "qwertyuiop zzxxcc"))
            assertEquals(200, r.status)
        }
        assertTrue(sql <= 4, "SQL ${sql}개")
    }
}

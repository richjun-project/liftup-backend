package com.richjun.liftupai.domain.workout.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/** 페이지 응답의 hidden_by_filters(장비 필터로 가려진 결과 수)와 fallback(0건일 때 대표 운동) */
class ExerciseListFallbackTest : ApiTestSupport() {

    private val list = "/api/v2/exercises"

    @BeforeEach
    fun login() {
        loginAs(newUser("fallback"))
    }

    private fun page(search: String, equipment: String? = null, category: String? = null, pageNo: Int = 0) =
        get(list).param("page", pageNo.toString()).param("locale", "en").param("search", search).also {
            if (equipment != null) it.param("equipment", equipment)
            if (category != null) it.param("category", category)
        }.let { body(call(it)).path("data") }

    private fun total(search: String, equipment: String? = null) = page(search, equipment).path("totalElements").asInt()

    @Test
    fun `장비 필터 때문에 결과가 적으면 가려진 개수를 알려준다`() {
        val filtered = page("squat", "KETTLEBELL")
        val shown = filtered.path("totalElements").asInt()
        assertTrue(shown in 1 until 10, "켁틀벨 스쿼트는 소수여야 한다: $shown")
        val hidden = filtered.path("hidden_by_filters").asInt()
        assertTrue(hidden > 0)
        assertEquals(total("squat") - shown, hidden, "가려진 수 = 필터 없는 전체 - 필터 있는 전체")
        assertFalse(filtered.has("fallback"), "결과가 있으면 대안 목록은 없다")
    }

    @Test
    fun `필터가 없거나 결과가 충분하면 hidden_by_filters 는 없다`() {
        assertFalse(page("squat").has("hidden_by_filters"))
        val plenty = page("press", "BARBELL,DUMBBELL,MACHINE,CABLE")
        assertTrue(plenty.path("totalElements").asInt() >= 10)
        assertFalse(plenty.has("hidden_by_filters"))
        // 첫 페이지에서만 계산한다
        assertFalse(page("squat", "KETTLEBELL", pageNo = 1).has("hidden_by_filters"))
    }

    @Test
    fun `카테고리 필터 때문에 0건이면 질의를 완화한 대표 운동을 준다`() {
        val d = page("squat", category = "CHEST")
        assertEquals(0, d.path("totalElements").asInt())
        assertEquals(0, d.path("content").size())
        val fallback = d.path("fallback").toList()
        assertTrue(fallback.size in 1..10, "대안 ${fallback.size}개")
        assertTrue(fallback.first().path("name").asText().contains("squat", ignoreCase = true), fallback.first().path("name").asText())
        assertTrue(fallback.all { it.path("is_favorite").isBoolean }, "대안 항목도 같은 목록 형식이다")
    }

    @Test
    fun `알아듣는 단어가 섞인 0건 질의는 그 부위의 대표 운동을 준다`() {
        // 검색 엔진이 모르는 단어를 건너뛰고 아는 부위로 바로 답하면(병합 후 엔진) 결과 자체가 그 부위다.
        // 엔진이 0건을 내면 대안(fallback)이 그 부위다. 어느 쪽이든 사용자는 가슴 운동을 받아야 한다.
        val d = page("chest qwertyuiop")
        val items = if (d.path("totalElements").asInt() == 0) {
            d.path("fallback").toList().also { assertTrue(it.size in 3..10, "대안 ${it.size}개") }
        } else {
            assertFalse(d.has("fallback"), "결과가 있으면 대안을 붙이지 않는다")
            d.path("content").toList()
        }
        assertTrue(items.count { it.path("category").asText() == "CHEST" } >= items.size / 2, items.map { it.path("name").asText() }.toString())
    }

    @Test
    fun `아무것도 알아듣지 못한 질의는 억지 대안 없이 빈 결과다`() {
        listOf("qwertyuiop", "qwertyuiop zzxxcc", "ㅋㅋㅋㅋㅋㅋ", "💪").forEach { q ->
            val d = page(q)
            if (d.path("totalElements").asInt() == 0) {
                assertFalse(d.has("fallback"), "$q 는 대안을 주지 않는다")
                assertFalse(d.has("hidden_by_filters"))
            }
        }
    }

    @Test
    fun `카테고리와 장비 필터가 겹쳐 결과가 적으면 가려진 수를 알려준다`() {
        val filtered = page("barbell bench press", "RESISTANCE_BAND", "CHEST")
        val unfiltered = page("barbell bench press", null, "CHEST").path("totalElements").asInt()
        // 장비 필터가 결과를 거의 다 가린다 — 가려진 수가 알려진다
        assertEquals(unfiltered - filtered.path("totalElements").asInt(), filtered.path("hidden_by_filters").asInt())
        assertTrue(filtered.path("hidden_by_filters").asInt() > 0)
    }
}

package com.richjun.liftupai.domain.workout.favorite

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.time.LocalDateTime

/** 즐겨찾기 추가·해제·목록과 최근 운동(기록 기반, 중복 없이 최신순) 계약 */
class ExerciseFavoritesRecentsTest : ApiTestSupport() {

    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var favoriteRepository: ExerciseFavoriteRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository

    private lateinit var user: User
    private lateinit var exercises: List<Exercise>

    private val base = "/api/v2/exercises"

    @BeforeEach
    fun setUp() {
        user = newUser("fav")
        loginAs(user)
        exercises = exerciseRepository.findAll().sortedBy { it.id }.take(8)
        assertTrue(exercises.size >= 8, "카탈로그가 로드되어 있어야 한다")
    }

    private fun items(path: String, vararg params: Pair<String, String>): List<com.fasterxml.jackson.databind.JsonNode> {
        val req = get(path)
        params.forEach { (k, v) -> req.param(k, v) }
        val r = call(req)
        assertEquals(200, r.status, r.contentAsString)
        return body(r).path("data").path("items").toList()
    }

    private fun ids(nodes: List<com.fasterxml.jackson.databind.JsonNode>) = nodes.map { it.path("id").asLong() }

    private fun logSession(who: User, status: SessionStatus, startedAt: LocalDateTime, vararg performed: Exercise, withSets: Boolean = true): WorkoutSession {
        val session = sessionRepository.save(WorkoutSession(user = who, startTime = startedAt, status = status))
        performed.forEachIndexed { i, ex ->
            val we = workoutExerciseRepository.save(WorkoutExercise(session = session, exercise = ex, orderInSession = i))
            if (withSets) setRepository.save(ExerciseSet(workoutExercise = we, setNumber = 1, weight = 40.0, reps = 10, completed = true))
        }
        return session
    }

    @Test
    fun `즐겨찾기 추가는 멱등이고 목록에는 최근에 추가한 순으로 나온다`() {
        val (a, b, c) = exercises
        listOf(a, b, c).forEach {
            val r = call(put("$base/${it.id}/favorite"))
            assertEquals(200, r.status)
            assertEquals(true, body(r).path("data").path("favorite").asBoolean())
            Thread.sleep(5) // 같은 시각으로 겹치지 않게
        }
        // 이미 있는 것을 또 추가해도 성공, 중복 행 없음
        assertEquals(true, body(call(put("$base/${a.id}/favorite"))).path("data").path("favorite").asBoolean())
        assertEquals(3L, favoriteRepository.countByUserId(user.id))

        val list = items("$base/favorites")
        assertEquals(listOf(c.id, b.id, a.id), ids(list))
        assertTrue(list.all { it.path("is_favorite").asBoolean() })
        assertTrue(list.all { it.path("name").asText().isNotBlank() })
    }

    @Test
    fun `즐겨찾기 해제는 멱등이고 목록에서 빠진다`() {
        val (a, b) = exercises
        call(put("$base/${a.id}/favorite")); call(put("$base/${b.id}/favorite"))

        val r = call(delete("$base/${a.id}/favorite"))
        assertEquals(200, r.status)
        assertEquals(false, body(r).path("data").path("favorite").asBoolean())
        assertEquals(listOf(b.id), ids(items("$base/favorites")))

        // 없는 것을 또 지워도 성공
        assertEquals(200, call(delete("$base/${a.id}/favorite")).status)
        assertEquals(listOf(b.id), ids(items("$base/favorites")))
    }

    @Test
    fun `없는 운동은 404 이고 id 형식이 틀리면 400 이다`() {
        assertEquals(404, call(put("$base/999999999/favorite")).status)
        assertValidationError(call(put("$base/abc/favorite")), "id=abc")
        assertValidationError(call(delete("$base/abc/favorite")), "delete id=abc")
        assertEquals(0L, favoriteRepository.countByUserId(user.id))
    }

    @Test
    fun `즐겨찾기는 사용자별로 분리되고 목록 항목에 is_favorite 가 붙는다`() {
        val (a, b) = exercises
        call(put("$base/${a.id}/favorite"))

        val other = newUser("fav-other")
        loginAs(other)
        call(put("$base/${b.id}/favorite"))
        assertEquals(listOf(b.id), ids(items("$base/favorites")))

        loginAs(user)
        assertEquals(listOf(a.id), ids(items("$base/favorites")))

        val page = body(call(get(base).param("page", "0").param("size", "20"))).path("data").path("content").toList()
        assertTrue(page.isNotEmpty())
        page.forEach { assertEquals(it.path("id").asLong() == a.id, it.path("is_favorite").asBoolean(), "id=${it.path("id").asLong()}") }
        // 비로그인 컨텍스트가 없으니 필드 부재 확인은 레포지토리 연결 해제 케이스로 대신한다 — 필드는 항상 불리언으로 나간다
        assertTrue(page.all { it.has("is_favorite") })
    }

    @Test
    fun `최근 운동은 기록 기반이고 중복 없이 최신순이다`() {
        val (a, b, c, d) = exercises
        val now = LocalDateTime.now()
        logSession(user, SessionStatus.COMPLETED, now.minusDays(5), a, b)
        logSession(user, SessionStatus.COMPLETED, now.minusDays(3), b, c)
        logSession(user, SessionStatus.COMPLETED, now.minusDays(1), d, a)

        // 가장 최근 세션: d, a / 그 전: b, c (b 는 3일 전이 가장 최근) — 중복은 한 번만
        val recent = ids(items("$base/recent"))
        assertEquals(4, recent.size)
        assertEquals(setOf(d.id, a.id), recent.take(2).toSet())
        assertEquals(setOf(b.id, c.id), recent.drop(2).toSet())
        assertEquals(recent.size, recent.toSet().size)
    }

    @Test
    fun `취소·예정 세션과 세트가 없는 운동 그리고 다른 사용자의 기록은 최근 운동에 나오지 않는다`() {
        val (a, b, c, d, e) = exercises
        val now = LocalDateTime.now()
        logSession(user, SessionStatus.COMPLETED, now.minusDays(2), a)
        logSession(user, SessionStatus.CANCELLED, now.minusDays(1), b)
        logSession(user, SessionStatus.PLANNED, now, c)
        logSession(user, SessionStatus.IN_PROGRESS, now.minusHours(1), d, withSets = false)
        logSession(newUser("fav-other2"), SessionStatus.COMPLETED, now, e)

        assertEquals(listOf(a.id), ids(items("$base/recent")))
    }

    @Test
    fun `최근 운동 limit 와 즐겨찾기 표시 그리고 형식 검증`() {
        val now = LocalDateTime.now()
        exercises.forEachIndexed { i, ex -> logSession(user, SessionStatus.COMPLETED, now.minusDays(10L - i), ex) }
        call(put("$base/${exercises.last().id}/favorite"))

        val top3 = items("$base/recent", "limit" to "3")
        assertEquals(exercises.reversed().take(3).map { it.id }, ids(top3)) // 가장 최근 기록 순
        assertTrue(top3.first().path("is_favorite").asBoolean())
        assertFalse(top3.last().path("is_favorite").asBoolean())
        assertEquals(8, items("$base/recent").size) // 기본 10 개 한도 안에서 전부

        assertValidationError(call(get("$base/recent").param("limit", "0")), "limit=0")
        assertValidationError(call(get("$base/recent").param("limit", "abc")), "limit=abc")
        assertEquals(200, call(get("$base/recent").param("limit", "1000")).status)
    }

    @Test
    fun `기록이 없으면 최근과 즐겨찾기는 빈 목록이다`() {
        assertEquals(0, items("$base/recent").size)
        assertEquals(0, items("$base/favorites").size)
    }

    @Test
    fun `회원 탈퇴용 deleteAllByUserId 는 해당 사용자의 즐겨찾기만 지운다`() {
        val (a, b) = exercises
        call(put("$base/${a.id}/favorite")); call(put("$base/${b.id}/favorite"))
        val other = newUser("fav-other3")
        loginAs(other)
        call(put("$base/${a.id}/favorite"))

        assertEquals(2, favoriteRepository.deleteAllByUserId(user.id))
        assertEquals(0L, favoriteRepository.countByUserId(user.id))
        assertEquals(1L, favoriteRepository.countByUserId(other.id))
    }

    @Test
    fun `즐겨찾기 개수에는 상한이 있다`() {
        val limit = ExerciseFavoriteService.MAX_FAVORITES
        // 상한 직전까지는 저장소로 직접 채운다(HTTP 로 500 번 부르지 않는다)
        val pool = exerciseRepository.findAll().sortedBy { it.id }
        assertTrue(pool.size > limit, "상한 테스트에는 운동이 ${limit}개보다 많아야 한다: ${pool.size}")
        favoriteRepository.saveAll(pool.take(limit).map { ExerciseFavorite(userId = user.id, exerciseId = it.id) })
        assertValidationError(call(put("$base/${pool[limit].id}/favorite")), "상한 초과")
        // 이미 있는 것은 상한이어도 성공(멱등)
        assertEquals(200, call(put("$base/${pool[0].id}/favorite")).status)
    }
}

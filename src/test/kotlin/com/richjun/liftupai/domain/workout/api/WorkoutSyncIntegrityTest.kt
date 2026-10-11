package com.richjun.liftupai.domain.workout.api

import com.richjun.liftupai.domain.workout.dto.UpdateExerciseData
import com.richjun.liftupai.domain.workout.dto.UpdateSessionRequest
import com.richjun.liftupai.domain.workout.dto.UpdateSetData
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.WorkoutSessionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 운동 중 동기화(/update)와 완료(/complete)가 세트 기록을 정확히 한 벌만 남기는지.
 * - 2026-07~10: 완료가 /update 로 이미 저장된 세트 뒤에 같은 세트를 덧붙여 기록·볼륨이 두 배가 됐다(13세션).
 * - 2026-10-10: 세트 완료를 연달아 누르면 겹친 /update 가 서로 지운 세트를 다시 지우다 500 이 났다.
 * 앱이 보내는 본문 그대로(MockMvc) 보낸다.
 */
class WorkoutSyncIntegrityTest : ApiTestSupport() {

    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository
    @Autowired private lateinit var workoutSessionService: WorkoutSessionService

    private var userId = 0L
    private var sessionId = 0L
    private var ex1 = 0L
    private var ex2 = 0L

    @BeforeEach
    fun setUp() {
        val user = newUser("sync").also { userId = it.id }
        loginAs(user)
        val exercises = exerciseRepository.findAll().sortedBy { it.id }.take(2)
        ex1 = exercises[0].id; ex2 = exercises[1].id
        val session = sessionRepository.save(
            WorkoutSession(user = user, startTime = LocalDateTime.now().minusMinutes(30), status = SessionStatus.IN_PROGRESS)
        )
        sessionId = session.id
        exercises.forEachIndexed { i, e ->
            workoutExerciseRepository.save(WorkoutExercise(session = session, exercise = e, orderInSession = i))
        }
    }

    /** 앱(workout_screen.dart _updateSessionOnBackend)이 보내는 모양: 운동마다 3세트, 앞의 [done]개 완료 */
    private fun updateBody(done: Int) = """{"exercises":[
        ${listOf(ex1 to 60.0, ex2 to 40.0).mapIndexed { i, (id, w) -> """{"exercise_id":$id,"order_index":$i,"sets":[
            ${(1..3).joinToString(",") { n -> """{"set_number":$n,"weight":$w,"reps":10,"completed":${n <= done},
                "completed_at":${if (n <= done) "\"2026-10-10T11:24:0${n}Z\"" else "null"}}""" }}]}""" }.joinToString(",")}]}"""

    /** 앱(_endWorkout)이 보내는 완료 본문: 같은 세트들, 완료 여부 포함 */
    private fun completeBody(done: Int) = """{"duration":30,"exercises":[
        ${listOf(ex1 to 60.0, ex2 to 40.0).joinToString(",") { (id, w) -> """{"exercise_id":$id,"sets":[
            ${(1..3).joinToString(",") { n -> """{"weight":$w,"reps":10,"completed":${n <= done},
                "completed_at":${if (n <= done) "\"2026-10-10T11:24:0${n}Z\"" else "null"}}""" }}]}""" }}]}"""

    private fun sessionSets() = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(sessionId)
        .flatMap { setRepository.findByWorkoutExerciseId(it.id) }

    @Test
    fun `운동 중 동기화 뒤 완료해도 세트가 두 배가 되지 않는다`() {
        val u = call(put("/api/v2/workouts/$sessionId/update").contentType(MediaType.APPLICATION_JSON).content(updateBody(done = 2)))
        assertEquals(200, u.status, u.contentAsString)
        assertEquals(6, sessionSets().size, "동기화는 완료·미완료 세트를 모두 저장한다")

        val c = call(put("/api/v2/workouts/$sessionId/complete").contentType(MediaType.APPLICATION_JSON).content(completeBody(done = 2)))
        assertEquals(200, c.status, c.contentAsString)

        val sets = sessionSets()
        assertEquals(4, sets.size, "완료 뒤에는 완료한 세트만 한 벌 남는다: ${sets.map { it.setNumber to it.weight }}")
        assertTrue(sets.all { it.completed })
        val expectedVolume = 2 * 60.0 * 10 + 2 * 40.0 * 10
        assertEquals(expectedVolume, sessionRepository.findById(sessionId).get().totalVolume)
        val perExercise = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(sessionId).associate { it.exercise.id to it.totalVolume }
        assertEquals(1200.0, perExercise[ex1])
        assertEquals(800.0, perExercise[ex2])
        assertEquals(listOf(1, 2), setRepository.findByWorkoutExerciseId(
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(sessionId).first().id
        ).map { it.setNumber }.sorted())
    }

    @Test
    fun `동기화 없이 바로 완료해도 완료 세트가 저장된다(구버전 흐름)`() {
        val c = call(put("/api/v2/workouts/$sessionId/complete").contentType(MediaType.APPLICATION_JSON).content(completeBody(done = 3)))
        assertEquals(200, c.status, c.contentAsString)
        assertEquals(6, sessionSets().size)
    }

    @Test
    fun `같은 세션 업데이트가 동시에 겹쳐도 실패 없이 한 벌만 남는다`() {
        fun request(done: Int) = UpdateSessionRequest(exercises = listOf(ex1 to 60.0, ex2 to 40.0).mapIndexed { i, (id, w) ->
            UpdateExerciseData(exerciseId = id, orderIndex = i, sets = (1..3).map { n ->
                UpdateSetData(setNumber = n, weight = w, reps = 10, completed = n <= done,
                    completedAt = if (n <= done) "2026-10-10T11:24:0${n}Z" else null)
            })
        })
        workoutSessionService.updateSession(userId, sessionId, request(0))

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(threads) { t ->
            pool.submit {
                start.await()
                try { workoutSessionService.updateSession(userId, sessionId, request(1 + t % 3)) } catch (e: Throwable) { errors.add(e) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), "겹친 업데이트가 실패했다: ${errors.map { it.javaClass.simpleName + ": " + it.message }.take(3)}")
        assertEquals(6, sessionSets().size, "운동 2개 × 3세트 한 벌")
    }

    @Test
    fun `온보딩 목표를 10개 넘게 골라도 추천이 400 이 아니다`() {
        val goals = listOf("bulk_up", "lean_mass", "weight_loss", "strength", "endurance", "posture", "wide_shoulders",
            "thick_chest", "wide_back", "big_arms", "six_pack", "core_strength", "glutes", "thick_legs", "slim_legs")
        val r = call(post("/api/v2/templates/recommend").contentType(MediaType.APPLICATION_JSON).content(
            """{"experience_level":"beginner","goals":[${goals.joinToString(",") { "\"$it\"" }}],"weekly_days":3,"session_duration":45}"""
        ))
        assertEquals(200, r.status, r.contentAsString)
    }
}

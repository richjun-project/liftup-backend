package com.richjun.liftupai.domain.workout.plan

import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.UserWorkoutPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 커스텀 플랜 저장의 HTTP 계약 — 서비스를 직접 부르면 JSON 해석을 건너뛰어 앱과의 키 불일치를 못 잡는다
 * (2026-03-31~10-09 저장이 전부 400 이었다). 앱이 실제로 보내는 본문 그대로 보낸다.
 */
class CustomPlanContractTest : ApiTestSupport() {

    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var planRepository: UserWorkoutPlanRepository

    private var ex1 = 0L
    private var ex2 = 0L

    @BeforeEach
    fun setUp() {
        loginAs(newUser("custom-plan").also { userId = it.id })
        val ids = exerciseRepository.findAll().sortedBy { it.id }.take(2).map { it.id }
        ex1 = ids[0]; ex2 = ids[1]
    }

    private var userId = 0L

    private fun save(body: String) = call(post("/api/v2/plans/custom").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `앱이 보내는 camelCase 본문(plan_change_screen)으로 저장된다`() {
        // lib/features/home/presentation/screens/plan_change_screen.dart 의 days 맵과 plan_service.dart 의 본문 그대로
        val r = save("""{"planName":"내 루틴","days":[
            {"dayName":"Day 1","workoutType":"FULL_BODY","exercises":[
                {"exerciseId":$ex1,"sets":4,"minReps":6,"maxReps":10,"restSeconds":90},
                {"exerciseId":$ex2,"sets":3,"minReps":10,"maxReps":15,"restSeconds":90}]}]}""")
        assertEquals(200, r.status, r.contentAsString)
        assertTrue(body(r).path("success").asBoolean())
        val plan = planRepository.findAll().filter { it.user.id == userId }.maxByOrNull { it.id }!!
        assertEquals("내 루틴", plan.planName)
        assertEquals(1, plan.totalDays)
    }

    @Test
    fun `snake_case 본문도 저장된다(다음 앱 버전)`() {
        val r = save("""{"plan_name":"루틴 B","days":[{"day_name":"A","workout_type":"FULL_BODY","exercises":[
            {"exercise_id":$ex1,"sets":3,"min_reps":8,"max_reps":12,"rest_seconds":60}]}]}""")
        assertEquals(200, r.status, r.contentAsString)
    }

    @Test
    fun `플랜 이름이 없으면 500 이 아니라 400 이다`() {
        val r = save("""{"days":[{"dayName":"A","exercises":[{"exerciseId":$ex1}]}]}""")
        assertEquals(400, r.status, r.contentAsString)
    }
}

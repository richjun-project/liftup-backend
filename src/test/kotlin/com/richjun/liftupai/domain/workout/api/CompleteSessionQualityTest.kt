package com.richjun.liftupai.domain.workout.api

import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.util.SessionQuality
import com.richjun.liftupai.global.time.AppTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/**
 * 완료 API 에서 세션 데이터 품질 표시(2026-11-14 coach-autopilot-r1).
 * 304 처럼 시작 몇 초 만에 여러 세트를 눌러 끝낸 세션은 표시되고 PR·첫 운동 판정에서 빠진다.
 * 운동을 하고 끝에 몰아서 누른 세션(시작~끝 40분, 세트 완료 시각이 끝에 몰림)은 정상으로 남는다.
 * 앱(_endWorkout)이 보내는 본문 모양 그대로 보낸다.
 */
class CompleteSessionQualityTest : ApiTestSupport() {
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var personalRecordRepository: PersonalRecordRepository

    private fun startSession(tag: String, secondsAgo: Long): Pair<Long, List<Long>> {
        val user = newUser(tag)
        loginAs(user)
        val exercises = exerciseRepository.findAll().sortedBy { it.id }.take(3)
        val session = sessionRepository.save(
            WorkoutSession(user = user, startTime = AppTime.utcNow().minusSeconds(secondsAgo), status = SessionStatus.IN_PROGRESS)
        )
        exercises.forEachIndexed { i, e -> workoutExerciseRepository.save(WorkoutExercise(session = session, exercise = e, orderInSession = i)) }
        return session.id to exercises.map { it.id }
    }

    /** 운동마다 5세트 완료(세트 완료 시각은 모두 같은 순간 — 몰아서 누른 모양) */
    private fun completeBody(ids: List<Long>, stamp: String) = """{"duration":0,"exercises":[
        ${ids.joinToString(",") { id -> """{"exercise_id":$id,"sets":[
            ${(1..5).joinToString(",") { """{"weight":40.0,"reps":10,"completed":true,"completed_at":"$stamp"}""" }}]}""" }}]}"""

    private fun complete(sessionId: Long, ids: List<Long>) {
        val stamp = AppTime.utcNow().toString() + "Z"
        val r = call(put("/api/v2/workouts/$sessionId/complete").contentType(MediaType.APPLICATION_JSON).content(completeBody(ids, stamp)))
        assertEquals(200, r.status, r.contentAsString)
    }

    @Test
    fun `304 재현 - 15세트를 몇 초 만에 끝낸 세션은 표시되고 PR 도 첫 운동 완료도 남지 않는다`() {
        val (sessionId, ids) = startSession("fast", secondsAgo = 7)
        complete(sessionId, ids)
        val session = sessionRepository.findById(sessionId).get()
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertEquals(SessionQuality.IMPLAUSIBLE_SPEED, session.dataQuality)
        assertTrue(personalRecordRepository.findAll().none { it.user.id == session.user.id }, "표시된 세션으로 PR 이 생겼다")
        assertEquals(0L, sessionRepository.countByUserAndStatusAndDataQualityIsNull(session.user, SessionStatus.COMPLETED))
        assertEquals(1L, sessionRepository.countByUserAndStatus(session.user, SessionStatus.COMPLETED), "완료 기록 자체는 남는다")
    }

    @Test
    fun `운동 뒤 몰아서 기록한 40분 세션은 표시되지 않고 PR 이 생긴다`() {
        val (sessionId, ids) = startSession("batch", secondsAgo = 40 * 60)
        complete(sessionId, ids)
        val session = sessionRepository.findById(sessionId).get()
        assertNull(session.dataQuality)
        assertTrue(personalRecordRepository.findAll().any { it.user.id == session.user.id }, "정상 세션인데 PR 이 없다")
        assertEquals(1L, sessionRepository.countByUserAndStatusAndDataQualityIsNull(session.user, SessionStatus.COMPLETED))
    }
}

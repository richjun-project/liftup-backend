package com.richjun.liftupai.domain.stats

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.util.UUID

/**
 * 통계 디자인 A(2026-11-21 대표 승인) — 몸 지도와 부위별 세트 막대가 쓰는 '부위별 완료 세트'.
 * 주 부위 1세트·보조 0.5세트, 묶음은 한 세트를 한 번만, 끝내지 않은 세트·7일 넘은 운동·끝나지 않은 세션은 빼고 센다.
 */
class MuscleSetsStatsTest : ApiTestSupport() {
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository

    private fun exercise(cat: ExerciseCategory, vararg m: MuscleGroup) = exerciseRepository.save(
        Exercise(slug = "t-${UUID.randomUUID()}", name = "t", category = cat, muscleGroups = m.toMutableSet()))

    private fun session(user: User, daysAgo: Long, status: SessionStatus, vararg work: Pair<Exercise, List<Boolean>>) {
        val start = AppTime.utcNow().minusDays(daysAgo).minusHours(1)
        val s = sessionRepository.save(WorkoutSession(user = user, startTime = start, endTime = start.plusMinutes(50), status = status, duration = 50))
        work.forEachIndexed { i, (ex, sets) ->
            val we = workoutExerciseRepository.save(WorkoutExercise(session = s, exercise = ex, orderInSession = i))
            sets.forEachIndexed { n, done -> setRepository.save(ExerciseSet(workoutExercise = we, setNumber = n + 1, weight = 50.0, reps = 8, completed = done)) }
        }
    }

    @Test
    fun `부위별 완료 세트 — 주 부위 1·보조 0_5, 묶음은 한 번, 미완료·지난주·진행 중은 뺀다`() {
        val bench = exercise(ExerciseCategory.CHEST, MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
        val squat = exercise(ExerciseCategory.LEGS, MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)
        val row = exercise(ExerciseCategory.BACK, MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS) // BACK·LATS 는 지도에서 한 부위
        val plank = exercise(ExerciseCategory.CORE, MuscleGroup.CORE, MuscleGroup.ABS, MuscleGroup.SHOULDERS)
        val user = newUser("msets")
        session(user, 1, SessionStatus.COMPLETED, bench to listOf(true, true, true, false), squat to listOf(true, true))
        session(user, 3, SessionStatus.COMPLETED, row to listOf(true, true, true, true), plank to listOf(true, false))
        session(user, 10, SessionStatus.COMPLETED, bench to listOf(true, true, true, true, true)) // 7일 밖
        session(user, 0, SessionStatus.IN_PROGRESS, squat to listOf(true, true, true))          // 아직 안 끝남
        loginAs(user)

        val r = body(call(get("/api/v2/stats/muscle-distribution").param("period", "week")))["data"]
        val muscles = r["muscles"].associate { it["key"].asText() to it["sets"].asDouble() }
        val groups = r["groups"].associate { it["key"].asText() to it["sets"].asDouble() }

        assertEquals(mapOf("CHEST" to 3.0, "TRICEPS" to 1.5, "SHOULDERS" to 2.0, "QUADRICEPS" to 2.0, "GLUTES" to 2.0,
            "HAMSTRINGS" to 2.0, "LATS" to 4.0, "BICEPS" to 2.0, "ABS" to 1.0), muscles, "BACK·LATS 4세트는 LATS 4(8 아님), 플랭크 CORE·ABS 1세트는 ABS 1")
        // 스쿼트 2세트는 하체 2세트(사두·둔근·햄 세 번 더해 6세트가 아니다), 팔은 삼두 1.5 + 이두 2.0
        assertEquals(listOf("chest", "back", "shoulders", "legs", "arms", "core"), r["groups"].map { it["key"].asText() })
        assertEquals(mapOf("chest" to 3.0, "back" to 4.0, "shoulders" to 2.0, "legs" to 2.0, "arms" to 3.5, "core" to 1.0), groups)
        assertEquals(true, r["distribution"].size() > 0, "예전 필드는 그대로")
    }

    @Test
    fun `운동이 없으면 묶음 여섯 개가 모두 0`() {
        val user = newUser("msets0")
        loginAs(user)
        val r = body(call(get("/api/v2/stats/muscle-distribution").param("period", "week")))["data"]
        assertEquals(0, r["muscles"].size())
        assertEquals(6, r["groups"].size())
        assertEquals(0.0, r["groups"].sumOf { it["sets"].asDouble() })
    }
}

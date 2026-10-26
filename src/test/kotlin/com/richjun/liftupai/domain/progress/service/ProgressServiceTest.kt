package com.richjun.liftupai.domain.progress.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class ProgressServiceTest {
    private val userRepository = mockk<UserRepository>()
    private val profileRepository = mockk<UserProfileRepository>()
    private val settingsRepository = mockk<UserSettingsRepository>()
    private val sessionRepository = mockk<WorkoutSessionRepository>()
    private val workoutExerciseRepository = mockk<WorkoutExerciseRepository>()
    private val mealLogRepository = mockk<MealLogRepository>()
    private val service = ProgressService(
        userRepository, profileRepository, settingsRepository, sessionRepository, workoutExerciseRepository, mealLogRepository
    )

    private val user = User(id = 1L, nickname = "t")
    private val bench = Exercise(id = 10L, slug = "bench", name = "Bench", category = ExerciseCategory.CHEST)

    private fun session(id: Long, end: LocalDateTime) =
        WorkoutSession(id = id, user = user, startTime = end.minusHours(1), endTime = end, status = SessionStatus.COMPLETED)

    private fun withSet(s: WorkoutSession, weight: Double, reps: Int): WorkoutExercise {
        val we = WorkoutExercise(id = s.id, session = s, exercise = bench, orderInSession = 0)
        we.sets.add(ExerciseSet(workoutExercise = we, setNumber = 1, weight = weight, reps = reps, completed = true))
        return we
    }

    private fun arrange(sessions: List<WorkoutSession>, exercises: List<WorkoutExercise>, tz: String? = "UTC", goal: Int? = 3) {
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { settingsRepository.findByUser_Id(1L) } returns
            Optional.of(UserSettings(user = user, timeZone = tz ?: "Asia/Seoul", weeklyWorkoutDays = goal))
        every { profileRepository.findByUser_Id(1L) } returns
            Optional.of(UserProfile(user = user, bodyInfo = BodyInfo(weight = 70.0)))
        every { sessionRepository.findAllByUserAndStatus(user, SessionStatus.COMPLETED) } returns sessions
        every { workoutExerciseRepository.findBySessionIdInWithExercise(any()) } returns exercises
        every { mealLogRepository.findByUserAndTimestampBetween(user, any(), any()) } returns emptyList()
    }

    @Test fun `세션 XP - before는 세션 제외, after는 포함`() {
        val s1 = session(1, LocalDateTime.of(2026, 10, 5, 10, 0))
        val s2 = session(2, LocalDateTime.of(2026, 10, 6, 10, 0))
        arrange(listOf(s1, s2), listOf(withSet(s1, 100.0, 5), withSet(s2, 105.0, 5)))

        val r = service.getWorkoutXp(1L, 2L)!!
        // s1: 세트 13 + 완료 50 = 63 / s2: 세트 13(105*5/70/2=3.75→3) + 완료 50 + PR 100 = 163
        assertEquals(63L, r.before.xp)
        assertEquals(226L, r.after.xp)
        assertEquals(163L, r.gained)
        assertEquals(listOf("SET", "WORKOUT_COMPLETE", "PERSONAL_RECORD"), r.breakdown.map { it.type })
        assertEquals(100, r.breakdown.first { it.type == "PERSONAL_RECORD" }.amount)
        assertEquals(2, r.before.level)
        assertEquals(4, r.after.level) // Lv4 = 22*3^1.9 ≈ 177 <= 226 < Lv5 ≈ 306
        assertTrue(r.leveledUp)
        assertFalse(r.evolved)
    }

    @Test fun `완료되지 않은 세션 id는 null`() {
        arrange(emptyList(), emptyList())
        assertNull(service.getWorkoutXp(1L, 99L))
    }

    @Test fun `레벨 응답 - 이력 없음`() {
        arrange(emptyList(), emptyList())
        val r = service.getLevel(1L)
        assertEquals(1, r.level)
        assertEquals(0L, r.totalXp)
        assertEquals(22L, r.nextLevelXp)
        assertEquals("novice", r.classKey)
        assertEquals(8, r.nextEvolutionLevel)
        assertEquals(600, r.dailyCap)
        assertEquals(1.0, r.multiplier)
    }
}

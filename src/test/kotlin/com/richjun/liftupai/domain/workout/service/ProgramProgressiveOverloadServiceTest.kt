package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.ProgressionModel
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class ProgramProgressiveOverloadServiceTest {

    private lateinit var exerciseSetRepository: ExerciseSetRepository
    private lateinit var personalRecordRepository: PersonalRecordRepository
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var service: ProgramProgressiveOverloadService

    private val user = User(id = 1L, email = "test@test.com", nickname = "Test", level = UserLevel.BEGINNER)
    private val exercise = Exercise(
        id = 10L,
        slug = "bench-press",
        name = "Bench Press",
        category = ExerciseCategory.CHEST,
        equipment = Equipment.BARBELL,
        movementPattern = "HORIZONTAL_PRESS"
    )

    @BeforeEach
    fun setUp() {
        exerciseSetRepository = mockk()
        personalRecordRepository = mockk()
        userProfileRepository = mockk()
        service = ProgramProgressiveOverloadService(
            exerciseSetRepository = exerciseSetRepository,
            personalRecordRepository = personalRecordRepository,
            userProfileRepository = userProfileRepository,
            objectMapper = jacksonObjectMapper()
        )
    }

    @Test
    fun `linear progression uses the most recent session not older max weight`() {
        val recentSession = session(id = 100L, daysAgo = 3)
        val oldSession = session(id = 101L, daysAgo = 14)
        val recentWorkoutExercise = WorkoutExercise(id = 200L, session = recentSession, exercise = exercise, orderInSession = 0)
        val oldWorkoutExercise = WorkoutExercise(id = 201L, session = oldSession, exercise = exercise, orderInSession = 0)

        val sets = listOf(
            set(1L, recentWorkoutExercise, weight = 80.0, reps = 12),
            set(2L, recentWorkoutExercise, weight = 80.0, reps = 12),
            set(3L, recentWorkoutExercise, weight = 80.0, reps = 12),
            set(4L, oldWorkoutExercise, weight = 120.0, reps = 12),
            set(5L, oldWorkoutExercise, weight = 120.0, reps = 12),
            set(6L, oldWorkoutExercise, weight = 120.0, reps = 12)
        )

        every {
            exerciseSetRepository.findCompletedSetsByUserAndExercise(user.id, exercise.id, any(), any())
        } returns sets
        every { userProfileRepository.findByUser_Id(user.id) } returns Optional.of(profile())

        val result = service.calculateWeightForPlan(
            user = user,
            exercise = exercise,
            progressionModel = ProgressionModel.LINEAR,
            week = 1,
            dayInCycle = 1,
            isDeloadWeek = false,
            minReps = 8,
            maxReps = 12
        )

        assertEquals(82.5, result)
    }

    @Test
    fun `estimated max is converted to working weight instead of returned as one rep max`() {
        val profile = profile().apply {
            estimatedMaxes = """{"bench-press": 100.0}"""
        }

        every {
            exerciseSetRepository.findCompletedSetsByUserAndExercise(user.id, exercise.id, any(), any())
        } returns emptyList()
        every { userProfileRepository.findByUser_Id(user.id) } returns Optional.of(profile)

        val result = service.calculateWeightForPlan(
            user = user,
            exercise = exercise,
            progressionModel = ProgressionModel.LINEAR,
            week = 1,
            dayInCycle = 1,
            isDeloadWeek = false,
            minReps = 8,
            maxReps = 12
        )

        assertEquals(70.0, result)
    }

    private fun profile(): UserProfile {
        return UserProfile(
            id = 1L,
            user = user,
            bodyInfo = BodyInfo(weight = 80.0),
            gender = "male",
            experienceLevel = ExperienceLevel.INTERMEDIATE
        )
    }

    private fun session(id: Long, daysAgo: Long): WorkoutSession {
        return WorkoutSession(
            id = id,
            user = user,
            startTime = LocalDateTime.now().minusDays(daysAgo),
            status = SessionStatus.COMPLETED
        )
    }

    private fun set(id: Long, workoutExercise: WorkoutExercise, weight: Double, reps: Int): ExerciseSet {
        return ExerciseSet(
            id = id,
            workoutExercise = workoutExercise,
            setNumber = id.toInt(),
            weight = weight,
            reps = reps,
            rpe = 7,
            completed = true
        )
    }
}

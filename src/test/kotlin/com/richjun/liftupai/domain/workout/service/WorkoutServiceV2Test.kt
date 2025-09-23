package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.workout.dto.PlannedExercise
import com.richjun.liftupai.domain.workout.dto.StartWorkoutRequestV2
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.global.exception.BusinessException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional

class WorkoutServiceV2Test {

    private lateinit var workoutServiceV2: WorkoutServiceV2
    private lateinit var userRepository: UserRepository
    private lateinit var workoutSessionRepository: WorkoutSessionRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    private lateinit var exerciseSetRepository: ExerciseSetRepository
    private lateinit var personalRecordRepository: PersonalRecordRepository
    private lateinit var workoutProgressTracker: WorkoutProgressTracker

    private lateinit var testUser: User
    private lateinit var testExercise: Exercise

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        workoutSessionRepository = mockk()
        exerciseRepository = mockk()
        workoutExerciseRepository = mockk()
        exerciseSetRepository = mockk()
        personalRecordRepository = mockk()
        workoutProgressTracker = mockk()

        workoutServiceV2 = WorkoutServiceV2(
            userRepository,
            workoutSessionRepository,
            exerciseRepository,
            workoutExerciseRepository,
            exerciseSetRepository,
            personalRecordRepository,
            workoutProgressTracker
        )

        testUser = User(
            id = 1L,
            email = "test@test.com",
            nickname = "TestUser",
            level = UserLevel.INTERMEDIATE
        )

        testExercise = Exercise(
            id = 1L,
            name = "Bench Press",
            category = ExerciseCategory.CHEST,
            equipment = Equipment.BARBELL,
            primaryMuscle = "Chest",
            secondaryMuscles = "Triceps, Shoulders"
        )
    }

    @Test
    fun `startNewWorkout should create new workout session when no existing session`() {
        // Given
        val request = StartWorkoutRequestV2(
            exercises = listOf(
                PlannedExercise(
                    exerciseId = 1L,
                    sets = 3,
                    reps = 10,
                    weight = 60.0,
                    orderIndex = 0
                )
            )
        )

        every { userRepository.findById(1L) } returns Optional.of(testUser)
        every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.empty()
        every { exerciseRepository.findById(1L) } returns Optional.of(testExercise)
        every { workoutSessionRepository.save(any()) } answers { firstArg() }
        every { workoutExerciseRepository.saveAll(any<List<WorkoutExercise>>()) } answers { firstArg() }
        every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns listOf()

        // When
        val result = workoutServiceV2.startNewWorkout(1L, request)

        // Then
        assertNotNull(result)
        assertEquals(SessionStatus.IN_PROGRESS.name, result.status)
        verify(exactly = 1) { workoutSessionRepository.save(any()) }
        verify(exactly = 1) { workoutExerciseRepository.saveAll(any<List<WorkoutExercise>>()) }
    }

    @Test
    fun `startNewWorkout should throw exception when session already exists`() {
        // Given
        val request = StartWorkoutRequestV2(exercises = listOf())
        val existingSession = WorkoutSession(
            id = 1L,
            user = testUser,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS
        )

        every { userRepository.findById(1L) } returns Optional.of(testUser)
        every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.of(existingSession)

        // When & Then
        assertThrows<BusinessException> {
            workoutServiceV2.startNewWorkout(1L, request)
        }
    }

    @Test
    fun `continueWorkout should return existing session`() {
        // Given
        val existingSession = WorkoutSession(
            id = 1L,
            user = testUser,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS
        )

        val workoutExercise = WorkoutExercise(
            id = 1L,
            session = existingSession,
            exercise = testExercise,
            orderInSession = 0,
            targetSets = 3,
            targetReps = 10,
            targetWeight = 60.0
        )

        every { userRepository.findById(1L) } returns Optional.of(testUser)
        every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.of(existingSession)
        every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(1L) } returns listOf(workoutExercise)
        every { exerciseSetRepository.findByWorkoutExerciseIdOrderBySetNumber(1L) } returns emptyList()

        // When
        val result = workoutServiceV2.continueWorkout(1L)

        // Then
        assertNotNull(result)
        assertEquals(1L, result.sessionId)
        assertEquals(SessionStatus.IN_PROGRESS.name, result.status)
    }

    @Test
    fun `continueWorkout should throw exception when no existing session`() {
        // Given
        every { userRepository.findById(1L) } returns Optional.of(testUser)
        every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.empty()

        // When & Then
        assertThrows<BusinessException> {
            workoutServiceV2.continueWorkout(1L)
        }
    }

    @Test
    fun `startWorkout should return existing session if present`() {
        // Given
        val request = StartWorkoutRequestV2(exercises = listOf())
        val existingSession = WorkoutSession(
            id = 1L,
            user = testUser,
            startTime = LocalDateTime.now(),
            status = SessionStatus.IN_PROGRESS
        )

        every { userRepository.findById(1L) } returns Optional.of(testUser)
        every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.of(existingSession)
        every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(1L) } returns emptyList()

        // When
        val result = workoutServiceV2.startWorkout(1L, request)

        // Then
        assertNotNull(result)
        assertEquals(1L, result.sessionId)
        assertEquals(SessionStatus.IN_PROGRESS.name, result.status)
    }
}
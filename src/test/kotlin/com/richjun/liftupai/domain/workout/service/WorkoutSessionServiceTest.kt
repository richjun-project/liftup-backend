package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.UpdateExerciseData
import com.richjun.liftupai.domain.workout.dto.UpdateSessionRequest
import com.richjun.liftupai.domain.workout.dto.UpdateSetData
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * WorkoutServiceV2에서 분리된 WorkoutSessionService에 대한 검증.
 * (구 WorkoutServiceV2PTTest의 UpdateSessionSync 케이스)
 */
class WorkoutSessionServiceTest {

    private lateinit var workoutSessionService: WorkoutSessionService

    private lateinit var userRepository: UserRepository
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var userSettingsRepository: UserSettingsRepository
    private lateinit var workoutSessionRepository: WorkoutSessionRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    private lateinit var exerciseSetRepository: ExerciseSetRepository
    private lateinit var personalRecordRepository: PersonalRecordRepository
    private lateinit var achievementRepository: AchievementRepository
    private lateinit var workoutStreakRepository: WorkoutStreakRepository
    private lateinit var muscleRecoveryRepository: MuscleRecoveryRepository
    private lateinit var userProgramEnrollmentRepository: UserProgramEnrollmentRepository

    private lateinit var workoutProgressTracker: WorkoutProgressTracker
    private lateinit var exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService
    private lateinit var sharedSupport: WorkoutV2SharedSupport
    private lateinit var workoutRecommendationServiceV2: WorkoutRecommendationServiceV2
    private lateinit var workoutStatsService: WorkoutStatsService

    private lateinit var testUser: User
    private lateinit var testExercise: Exercise

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        userProfileRepository = mockk()
        userSettingsRepository = mockk()
        workoutSessionRepository = mockk()
        exerciseRepository = mockk()
        workoutExerciseRepository = mockk()
        exerciseSetRepository = mockk()
        personalRecordRepository = mockk()
        achievementRepository = mockk()
        workoutStreakRepository = mockk()
        muscleRecoveryRepository = mockk()
        userProgramEnrollmentRepository = mockk()
        workoutProgressTracker = mockk()
        exerciseCatalogLocalizationService = mockk()
        sharedSupport = WorkoutV2SharedSupport(userSettingsRepository, exerciseCatalogLocalizationService, workoutSessionRepository)
        workoutRecommendationServiceV2 = mockk()
        workoutStatsService = mockk()

        workoutSessionService = WorkoutSessionService(
            userRepository,
            userProfileRepository,
            userSettingsRepository,
            workoutSessionRepository,
            exerciseRepository,
            workoutExerciseRepository,
            exerciseSetRepository,
            personalRecordRepository,
            achievementRepository,
            workoutStreakRepository,
            workoutProgressTracker,
            muscleRecoveryRepository,
            userProgramEnrollmentRepository,
            null,
            sharedSupport,
            workoutRecommendationServiceV2,
            workoutStatsService
        )

        testUser = User(id = 1L, email = "test@test.com", nickname = "TestUser", level = UserLevel.INTERMEDIATE)

        testExercise = Exercise(
            id = 1L,
            slug = "bench-press",
            name = "Bench Press",
            category = ExerciseCategory.CHEST,
            equipment = Equipment.BARBELL
        )

        every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
        every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
    }

    // -------------------------------------------------------------------------
    // Session sync preserves completed set state
    // -------------------------------------------------------------------------

    @Nested
    inner class UpdateSessionSync {

        @Test
        fun `updateSession saves completed state and timestamp from client payload`() {
            val session = WorkoutSession(
                id = 99L,
                user = testUser,
                startTime = LocalDateTime.of(2026, 1, 1, 10, 0),
                status = SessionStatus.IN_PROGRESS
            )
            val workoutExercise = WorkoutExercise(
                id = 77L,
                session = session,
                exercise = testExercise,
                orderInSession = 0
            )
            val savedSet = slot<ExerciseSet>()

            every { userRepository.findById(1L) } returns Optional.of(testUser)
            every { workoutSessionRepository.findById(99L) } returns Optional.of(session)
            every { exerciseRepository.findById(1L) } returns Optional.of(testExercise)
            every { workoutExerciseRepository.findBySessionIdAndExerciseId(99L, 1L) } returns workoutExercise
            every { exerciseSetRepository.findByWorkoutExerciseId(77L) } returns emptyList()
            every { exerciseSetRepository.deleteAll(any<Iterable<ExerciseSet>>()) } returns Unit
            every { exerciseSetRepository.save(capture(savedSet)) } answers {
                val set = savedSet.captured
                workoutExercise.sets.add(set)
                set
            }
            every { workoutExerciseRepository.save(workoutExercise) } returns workoutExercise
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(99L) } returns listOf(workoutExercise)
            every { workoutSessionRepository.save(session) } returns session

            workoutSessionService.updateSession(
                userId = 1L,
                sessionId = 99L,
                request = UpdateSessionRequest(
                    exercises = listOf(
                        UpdateExerciseData(
                            exerciseId = 1L,
                            orderIndex = 0,
                            sets = listOf(
                                UpdateSetData(
                                    setNumber = 1,
                                    weight = 80.0,
                                    reps = 8,
                                    completed = true,
                                    completedAt = "2026-01-01T10:15:00Z"
                                )
                            )
                        )
                    ),
                    lastUpdated = "2026-01-01T10:16:00Z"
                )
            )

            assertTrue(savedSet.captured.completed)
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 15), savedSet.captured.completedAt)
            assertEquals(640.0, workoutExercise.totalVolume)
            assertEquals(640.0, session.totalVolume)
        }

        @Test
        fun `updateSession applies the client's exercise order to existing exercises`() {
            val session = WorkoutSession(
                id = 99L,
                user = testUser,
                startTime = LocalDateTime.of(2026, 1, 1, 10, 0),
                status = SessionStatus.IN_PROGRESS
            )
            val row = Exercise(id = 2L, slug = "barbell-row", name = "Barbell Row", category = ExerciseCategory.BACK, equipment = Equipment.BARBELL)
            val bench = WorkoutExercise(id = 77L, session = session, exercise = testExercise, orderInSession = 0)
            val rowWe = WorkoutExercise(id = 78L, session = session, exercise = row, orderInSession = 1)

            every { userRepository.findById(1L) } returns Optional.of(testUser)
            every { workoutSessionRepository.findById(99L) } returns Optional.of(session)
            every { exerciseRepository.findById(1L) } returns Optional.of(testExercise)
            every { exerciseRepository.findById(2L) } returns Optional.of(row)
            every { workoutExerciseRepository.findBySessionIdAndExerciseId(99L, 1L) } returns bench
            every { workoutExerciseRepository.findBySessionIdAndExerciseId(99L, 2L) } returns rowWe
            every { exerciseSetRepository.findByWorkoutExerciseId(any()) } returns emptyList()
            every { exerciseSetRepository.deleteAll(any<Iterable<ExerciseSet>>()) } returns Unit
            every { exerciseSetRepository.save(any()) } answers { firstArg() }
            every { workoutExerciseRepository.save(any()) } answers { firstArg() }
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(99L) } returns listOf(rowWe, bench)
            every { workoutSessionRepository.save(session) } returns session

            // 사용자가 로우를 맨 위로 옮겼다
            workoutSessionService.updateSession(
                userId = 1L,
                sessionId = 99L,
                request = UpdateSessionRequest(
                    exercises = listOf(
                        UpdateExerciseData(exerciseId = 2L, orderIndex = 0, sets = listOf(UpdateSetData(setNumber = 1, weight = 40.0, reps = 10, completed = false))),
                        UpdateExerciseData(exerciseId = 1L, orderIndex = 1, sets = listOf(UpdateSetData(setNumber = 1, weight = 60.0, reps = 8, completed = false))),
                    )
                )
            )

            assertEquals(0, rowWe.orderInSession)
            assertEquals(1, bench.orderInSession)
        }
    }
}

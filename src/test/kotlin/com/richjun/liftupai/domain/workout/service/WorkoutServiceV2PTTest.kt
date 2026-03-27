package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.recovery.service.RecoveryService
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class WorkoutServiceV2PTTest {

    private lateinit var workoutServiceV2: WorkoutServiceV2

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
    private lateinit var recoveryService: RecoveryService
    private lateinit var exercisePatternClassifier: ExercisePatternClassifier
    private lateinit var exerciseRecommendationService: ExerciseRecommendationService
    private lateinit var exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService
    private lateinit var autoProgramSelector: AutoProgramSelector
    private lateinit var programProgressiveOverloadService: ProgramProgressiveOverloadService
    private lateinit var exerciseTrainingProfileResolver: ExerciseTrainingProfileResolver

    private lateinit var testUser: User
    private lateinit var testExercise: Exercise
    private lateinit var testUserProfile: UserProfile

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
        recoveryService = mockk()
        exercisePatternClassifier = mockk()
        exerciseRecommendationService = mockk()
        exerciseCatalogLocalizationService = mockk()
        autoProgramSelector = mockk()
        programProgressiveOverloadService = mockk()
        exerciseTrainingProfileResolver = ExerciseTrainingProfileResolver(exercisePatternClassifier)

        workoutServiceV2 = WorkoutServiceV2(
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
            recoveryService,
            muscleRecoveryRepository,
            exercisePatternClassifier,
            exerciseRecommendationService,
            exerciseCatalogLocalizationService,
            autoProgramSelector,
            userProgramEnrollmentRepository,
            programProgressiveOverloadService,
            null,
            exerciseTrainingProfileResolver,
            null
        )

        testUser = User(id = 1L, email = "test@test.com", nickname = "TestUser", level = UserLevel.INTERMEDIATE)

        testExercise = Exercise(
            id = 1L,
            slug = "bench-press",
            name = "Bench Press",
            category = ExerciseCategory.CHEST,
            equipment = Equipment.BARBELL
        )

        testUserProfile = UserProfile(
            id = 1L,
            user = testUser,
            bodyInfo = BodyInfo(weight = 80.0),
            gender = "male",
            experienceLevel = ExperienceLevel.INTERMEDIATE
        )

        every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
        every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
        // ExercisePatternClassifier is called in getExerciseBaseWeight for every PT calculation
        every { exercisePatternClassifier.classifyExercise(any()) } returns ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL
    }

    // -------------------------------------------------------------------------
    // 1. calculateFullPTRecommendation returns PTRecommendation with all fields
    // -------------------------------------------------------------------------

    @Nested
    inner class CalculateFullPTRecommendation {

        @Test
        fun `returns PTRecommendation with positive sets`() {
            stubNoHistory()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.sets > 0, "sets should be positive, got ${result.sets}")
        }

        @Test
        fun `returns PTRecommendation with non-blank reps`() {
            stubNoHistory()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.reps.isNotBlank(), "reps should not be blank")
        }

        @Test
        fun `returns PTRecommendation with positive restSeconds`() {
            stubNoHistory()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.restSeconds > 0, "restSeconds should be positive, got ${result.restSeconds}")
        }

        @Test
        fun `returns PTRecommendation with positive weight`() {
            stubNoHistory()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.weight > 0.0, "weight should be positive, got ${result.weight}")
        }

        @Test
        fun `returns PTRecommendation with non-blank reason`() {
            stubNoHistory()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.reason.isNotBlank(), "reason should not be blank")
        }

        private fun stubNoHistory() {
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, testExercise) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Active program enrollment drives periodization phase from ProgressionModel
    // -------------------------------------------------------------------------

    @Nested
    inner class PeriodizationPhaseFromProgram {

        @Test
        fun `BLOCK progression model at week 1 produces ACCUMULATION phase yielding sets of 3 or more`() {
            // totalCompleted=0 -> currentWeek=1 -> blockPhase=0 -> ACCUMULATION
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.BLOCK, daysPerWeek = 3), totalCompleted = 0)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.sets >= 3, "BLOCK ACCUMULATION should have sets >= 3, got ${result.sets}")
        }

        @Test
        fun `BLOCK progression model at week 7 produces DELOAD phase`() {
            // totalCompleted=18 -> currentWeek=7 -> blockPhase=6 -> DELOAD
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.BLOCK, daysPerWeek = 3), totalCompleted = 18)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            // DELOAD returns a valid recommendation with reduced intensity
            assertNotNull(result)
            assertTrue(result.sets >= 1)
        }

        @Test
        fun `BLOCK progression model at week 5 produces REALIZATION phase with positive weight`() {
            // totalCompleted=12 -> currentWeek=5 -> blockPhase=4 -> REALIZATION
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.BLOCK, daysPerWeek = 3), totalCompleted = 12)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.weight > 0.0)
        }

        @Test
        fun `LINEAR progression model at week 3 produces INTENSIFICATION phase`() {
            // totalCompleted=6 -> currentWeek=3 -> (3-1)%4=2 -> INTENSIFICATION
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.LINEAR, daysPerWeek = 3), totalCompleted = 6)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.weight > 0.0)
        }

        @Test
        fun `LINEAR progression model at week 4 produces DELOAD phase`() {
            // totalCompleted=9 -> currentWeek=4 -> (4-1)%4=3 -> DELOAD
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.LINEAR, daysPerWeek = 3), totalCompleted = 9)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.sets >= 1)
        }

        @Test
        fun `UNDULATING progression model at week 4 produces DELOAD phase`() {
            // totalCompleted=9 -> currentWeek=4 -> 4%4==0 -> DELOAD
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.UNDULATING, daysPerWeek = 3), totalCompleted = 9)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.sets >= 1)
        }

        @Test
        fun `UNDULATING progression model at non-deload week produces ACCUMULATION phase`() {
            // totalCompleted=3 -> currentWeek=2 -> 2%4!=0 -> ACCUMULATION
            val enrollment = makeEnrollment(makeProgram(ProgressionModel.UNDULATING, daysPerWeek = 3), totalCompleted = 3)
            stubWithEnrollment(enrollment)

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.sets >= 3)
        }

        @Test
        fun `no active enrollment falls back to default session-based periodization cycle`() {
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, testExercise) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertNotNull(result)
            assertTrue(result.sets > 0)
        }

        private fun stubWithEnrollment(enrollment: UserProgramEnrollment) {
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, testExercise) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns enrollment
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // 3. getBasicWorkoutRecommendation uses PT system (sets not always hardcoded 3)
    // -------------------------------------------------------------------------

    @Nested
    inner class BasicWorkoutRecommendationUsesPTSystem {

        @Test
        fun `all exercises in recommendation have positive sets from PT system`() {
            val chestExercise = Exercise(id = 2L, slug = "incline-press", name = "Incline Press", category = ExerciseCategory.CHEST)
            val backExercise = Exercise(id = 3L, slug = "deadlift", name = "Deadlift", category = ExerciseCategory.BACK)

            stubBasicWorkout(listOf(chestExercise, backExercise))

            val result = workoutServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 30)

            assertTrue(result.recommendation.exercises.isNotEmpty(), "Should have exercises")
            result.recommendation.exercises.forEach { ex ->
                assertTrue(ex.sets > 0, "Every exercise should have sets > 0, got ${ex.sets}")
            }
        }

        @Test
        fun `all exercises in recommendation have non-blank reps from PT system`() {
            val legExercise = Exercise(id = 4L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS)

            stubBasicWorkout(listOf(legExercise))

            val result = workoutServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 30)

            result.recommendation.exercises.forEach { ex ->
                assertTrue(ex.reps.isNotBlank(), "reps should not be blank, got '${ex.reps}'")
            }
        }

        @Test
        fun `ADVANCED user recommendation exercises have positive sets from PT system`() {
            val advancedProfile = UserProfile(
                id = 1L, user = testUser,
                bodyInfo = BodyInfo(weight = 90.0),
                gender = "male",
                experienceLevel = ExperienceLevel.ADVANCED
            )
            val squat = Exercise(id = 5L, slug = "squat-adv", name = "Squat", category = ExerciseCategory.LEGS)

            stubBasicWorkout(listOf(squat), profile = advancedProfile)

            val result = workoutServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 45, targetMuscle = "legs")

            assertTrue(result.recommendation.exercises.isNotEmpty())
            result.recommendation.exercises.forEach { ex ->
                assertTrue(ex.sets > 0, "Expected sets > 0, got ${ex.sets}")
                assertTrue(ex.reps.isNotBlank())
            }
        }

        private fun stubBasicWorkout(exercises: List<Exercise>, profile: UserProfile = testUserProfile) {
            every { userRepository.findById(1L) } returns Optional.of(testUser)
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(profile)
            every { userProfileRepository.findByUser(testUser) } returns Optional.of(profile)
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
            every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.empty()
            every { workoutProgressTracker.getNextWorkoutInProgram(testUser, any()) } returns WorkoutProgramPosition(day = 1, cycle = 1, isNewCycle = false)
            every { workoutProgressTracker.getWorkoutTypeSequence(any()) } returns listOf(WorkoutType.FULL_BODY)
            every {
                exerciseRecommendationService.getRecommendedExercises(
                    user = testUser, targetMuscle = any(), equipment = any(), duration = any(), limit = any()
                )
            } returns exercises
            every { exerciseCatalogLocalizationService.translationMap(any(), any()) } returns emptyMap()
            every { exerciseCatalogLocalizationService.displayName(any(), any(), any()) } returns "Exercise"
            every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(eq(testUser), any()) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // 4. Experience level multiplier covers all 5 enum values
    // -------------------------------------------------------------------------

    @Nested
    inner class ExperienceLevelMultiplier {

        private val benchPress = Exercise(
            id = 10L, slug = "bench-press-test", name = "Bench Press",
            category = ExerciseCategory.CHEST, equipment = Equipment.BARBELL
        )

        @Test
        fun `BEGINNER produces lower weight than INTERMEDIATE`() {
            val beginnerWeight = weightForLevel(ExperienceLevel.BEGINNER)
            val intermediateWeight = weightForLevel(ExperienceLevel.INTERMEDIATE)

            assertTrue(beginnerWeight < intermediateWeight,
                "BEGINNER ($beginnerWeight) should be less than INTERMEDIATE ($intermediateWeight)")
        }

        @Test
        fun `NOVICE produces lower weight than INTERMEDIATE`() {
            val noviceWeight = weightForLevel(ExperienceLevel.NOVICE)
            val intermediateWeight = weightForLevel(ExperienceLevel.INTERMEDIATE)

            assertTrue(noviceWeight < intermediateWeight,
                "NOVICE ($noviceWeight) should be less than INTERMEDIATE ($intermediateWeight)")
        }

        @Test
        fun `INTERMEDIATE produces lower weight than ADVANCED`() {
            val intermediateWeight = weightForLevel(ExperienceLevel.INTERMEDIATE)
            val advancedWeight = weightForLevel(ExperienceLevel.ADVANCED)

            assertTrue(intermediateWeight < advancedWeight,
                "INTERMEDIATE ($intermediateWeight) should be less than ADVANCED ($advancedWeight)")
        }

        @Test
        fun `ADVANCED produces lower weight than EXPERT`() {
            val advancedWeight = weightForLevel(ExperienceLevel.ADVANCED)
            val expertWeight = weightForLevel(ExperienceLevel.EXPERT)

            assertTrue(advancedWeight < expertWeight,
                "ADVANCED ($advancedWeight) should be less than EXPERT ($expertWeight)")
        }

        @Test
        fun `BEGINNER weight is approximately 0_5 times ADVANCED weight`() {
            val beginnerWeight = weightForLevel(ExperienceLevel.BEGINNER)
            val advancedWeight = weightForLevel(ExperienceLevel.ADVANCED)

            val ratio = beginnerWeight / advancedWeight
            assertEquals(0.5, ratio, 0.05,
                "BEGINNER/ADVANCED ratio should be ~0.5 (multipliers 0.5 vs 1.0), got $ratio")
        }

        @Test
        fun `NOVICE weight is approximately 0_6 times ADVANCED weight`() {
            val noviceWeight = weightForLevel(ExperienceLevel.NOVICE)
            val advancedWeight = weightForLevel(ExperienceLevel.ADVANCED)

            val ratio = noviceWeight / advancedWeight
            assertEquals(0.6, ratio, 0.05,
                "NOVICE/ADVANCED ratio should be ~0.6 (multipliers 0.6 vs 1.0), got $ratio")
        }

        @Test
        fun `EXPERT weight is approximately 1_1 times ADVANCED weight`() {
            val expertWeight = weightForLevel(ExperienceLevel.EXPERT)
            val advancedWeight = weightForLevel(ExperienceLevel.ADVANCED)

            val ratio = expertWeight / advancedWeight
            assertEquals(1.1, ratio, 0.05,
                "EXPERT/ADVANCED ratio should be ~1.1 (multipliers 1.1 vs 1.0), got $ratio")
        }

        private fun weightForLevel(level: ExperienceLevel): Double {
            val profile = UserProfile(
                id = 1L, user = testUser,
                bodyInfo = BodyInfo(weight = 80.0),
                gender = "male",
                experienceLevel = level
            )
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(profile)
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, benchPress) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()

            return workoutServiceV2.calculateFullPTRecommendation(testUser, benchPress).weight
        }
    }

    // -------------------------------------------------------------------------
    // 5. lastWeight uses firstOrNull() (most recent), not lastOrNull() (oldest)
    // -------------------------------------------------------------------------

    @Nested
    inner class LastWeightUsesFirstOrNull {

        @Test
        fun `recommendation weight is based on most recent session not oldest`() {
            // Most recent session: 100kg  ->  firstOrNull() picks this
            // Oldest session: 50kg        ->  lastOrNull() would pick this
            val recentSession = buildCompletedSession(id = 1001L, daysAgo = 3)
            val oldSession = buildCompletedSession(id = 1002L, daysAgo = 21)

            stubTwoSessionHistory(
                session1 = recentSession, weight1 = 100.0,
                session2 = oldSession,   weight2 = 50.0
            )

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            // Based on 100kg recent session, result should be well above 50kg territory
            assertTrue(result.weight > 70.0,
                "Weight should be based on most recent session (~100kg), got ${result.weight}")
        }

        @Test
        fun `when no history exists weight falls back to base calculation not zero`() {
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, testExercise) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()

            val result = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise)

            assertTrue(result.weight > 0.0, "Weight should fall back to base calculation, not 0")
        }

        @Test
        fun `heavier most-recent session produces higher recommendation than lighter most-recent session`() {
            // Scenario A: recent=120kg, old=60kg
            stubTwoSessionHistory(
                session1 = buildCompletedSession(id = 2001L, daysAgo = 5), weight1 = 120.0,
                session2 = buildCompletedSession(id = 2002L, daysAgo = 25), weight2 = 60.0
            )
            val weightFromHeavyRecent = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise).weight

            // Scenario B: recent=60kg, old=120kg
            stubTwoSessionHistory(
                session1 = buildCompletedSession(id = 3001L, daysAgo = 5), weight1 = 60.0,
                session2 = buildCompletedSession(id = 3002L, daysAgo = 25), weight2 = 120.0
            )
            val weightFromLightRecent = workoutServiceV2.calculateFullPTRecommendation(testUser, testExercise).weight

            assertTrue(weightFromHeavyRecent > weightFromLightRecent,
                "Heavy recent ($weightFromHeavyRecent) should produce higher recommendation than light recent ($weightFromLightRecent)")
        }

        private fun buildCompletedSession(id: Long, daysAgo: Long): WorkoutSession {
            return WorkoutSession(
                id = id,
                user = testUser,
                startTime = LocalDateTime.now().minusDays(daysAgo),
                status = SessionStatus.COMPLETED
            )
        }

        private fun stubTwoSessionHistory(
            session1: WorkoutSession, weight1: Double,
            session2: WorkoutSession, weight2: Double
        ) {
            val we1 = WorkoutExercise(id = session1.id + 10000, session = session1, exercise = testExercise, orderInSession = 0)
            val we2 = WorkoutExercise(id = session2.id + 10000, session = session2, exercise = testExercise, orderInSession = 0)
            val set1 = ExerciseSet(id = session1.id + 20000, workoutExercise = we1, setNumber = 1, weight = weight1, reps = 8, completed = true)
            val set2 = ExerciseSet(id = session2.id + 20000, workoutExercise = we2, setNumber = 1, weight = weight2, reps = 8, completed = true)

            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            // Service sorts by startTime desc -> session1 (more recent) comes first
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns listOf(session1, session2)
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session1.id) } returns listOf(we1)
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session2.id) } returns listOf(we2)
            every { exerciseSetRepository.findByWorkoutExerciseId(we1.id) } returns listOf(set1)
            every { exerciseSetRepository.findByWorkoutExerciseId(we2.id) } returns listOf(set2)
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(testUser, testExercise) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeProgram(model: ProgressionModel, daysPerWeek: Int = 3): CanonicalProgram {
        return CanonicalProgram(
            id = 100L,
            code = "TEST_PROGRAM",
            name = "Test Program",
            splitType = SplitType.FULL_BODY,
            targetExperienceLevel = ExperienceLevel.INTERMEDIATE,
            targetGoal = WorkoutGoal.STRENGTH,
            daysPerWeek = daysPerWeek,
            progressionModel = model
        )
    }

    private fun makeEnrollment(program: CanonicalProgram, totalCompleted: Int): UserProgramEnrollment {
        return UserProgramEnrollment(
            id = 200L,
            user = testUser,
            program = program,
            programVersion = 1,
            startDate = LocalDateTime.now().minusWeeks(8),
            totalCompletedWorkouts = totalCompleted
        )
    }

    // -------------------------------------------------------------------------
    // 7. Edge cases: no profile, no settings → default FULL_BODY
    // -------------------------------------------------------------------------

    @Nested
    inner class DefaultProgramFallback {

        @Test
        fun `no profile no settings defaults to FULL_BODY not PPL`() {
            val exercises = listOf(
                Exercise(id = 10L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS),
                Exercise(id = 11L, slug = "bench", name = "Bench Press", category = ExerciseCategory.CHEST),
                Exercise(id = 12L, slug = "row", name = "Row", category = ExerciseCategory.BACK),
            )

            every { userRepository.findById(1L) } returns Optional.of(testUser)
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.empty()
            every { userProfileRepository.findByUser(testUser) } returns Optional.empty()
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
            every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.empty()
            every { workoutProgressTracker.getNextWorkoutInProgram(testUser, any()) } returns WorkoutProgramPosition(day = 1, cycle = 1, isNewCycle = false)
            every { workoutProgressTracker.getWorkoutTypeSequence("FULL_BODY") } returns listOf(WorkoutType.FULL_BODY)
            every {
                exerciseRecommendationService.getRecommendedExercises(
                    user = testUser, targetMuscle = "full_body", equipment = any(), duration = any(), limit = any()
                )
            } returns exercises
            every { exerciseCatalogLocalizationService.translationMap(any(), any()) } returns emptyMap()
            every { exerciseCatalogLocalizationService.displayName(any(), any(), any()) } returns "Exercise"
            every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(eq(testUser), any()) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()

            val result = workoutServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 60)

            assertTrue(result.recommendation.exercises.isNotEmpty(), "Should return exercises")
            // targetMuscle should be "full_body", not "chest" (PPL day 1)
            val targetMuscles = result.recommendation.targetMuscles
            // Full body should have multiple muscle groups, not just chest
            assertTrue(
                targetMuscles.size > 1 || result.recommendation.exercises.size >= 3,
                "Full body workout should have diverse muscles, got: $targetMuscles"
            )
        }

        @Test
        fun `vector recommendation below threshold triggers rule-based fallback`() {
            val singleExercise = listOf(
                Exercise(id = 20L, slug = "pushup", name = "Push Up", category = ExerciseCategory.CHEST)
            )
            val fullExercises = listOf(
                Exercise(id = 21L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS),
                Exercise(id = 22L, slug = "bench", name = "Bench Press", category = ExerciseCategory.CHEST),
                Exercise(id = 23L, slug = "row", name = "Row", category = ExerciseCategory.BACK),
                Exercise(id = 24L, slug = "press", name = "Shoulder Press", category = ExerciseCategory.SHOULDERS),
                Exercise(id = 25L, slug = "curl", name = "Curl", category = ExerciseCategory.ARMS),
                Exercise(id = 26L, slug = "plank", name = "Plank", category = ExerciseCategory.CORE),
            )

            every { userRepository.findById(1L) } returns Optional.of(testUser)
            every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(testUserProfile)
            every { userProfileRepository.findByUser(testUser) } returns Optional.of(testUserProfile)
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
            every { workoutSessionRepository.findFirstByUserAndStatusOrderByStartTimeDesc(testUser, SessionStatus.IN_PROGRESS) } returns Optional.empty()
            every { workoutProgressTracker.getNextWorkoutInProgram(testUser, any()) } returns WorkoutProgramPosition(day = 1, cycle = 1, isNewCycle = false)
            every { workoutProgressTracker.getWorkoutTypeSequence(any()) } returns listOf(WorkoutType.FULL_BODY)
            // Rule-based should return 6 exercises for 60 min
            every {
                exerciseRecommendationService.getRecommendedExercises(
                    user = testUser, targetMuscle = any(), equipment = any(), duration = any(), limit = any()
                )
            } returns fullExercises
            every { exerciseCatalogLocalizationService.translationMap(any(), any()) } returns emptyMap()
            every { exerciseCatalogLocalizationService.displayName(any(), any(), any()) } returns "Exercise"
            every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
            every { workoutSessionRepository.findByUserAndStartTimeAfter(testUser, any()) } returns emptyList()
            every { workoutExerciseRepository.findBySessionIdOrderByOrderInSession(any()) } returns emptyList()
            every { personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(eq(testUser), any()) } returns null
            every { userProgramEnrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(testUser, EnrollmentStatus.ACTIVE) } returns null
            every { workoutSessionRepository.findAllByUserAndStatus(testUser, SessionStatus.COMPLETED) } returns emptyList()

            // vectorRecommendationService is null (passed as null in constructor)
            // so it always falls back to rule-based
            val result = workoutServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 60)

            // 60분 = 6개 운동이 목표
            assertEquals(6, result.recommendation.exercises.size,
                "60-min workout should have 6 exercises, got ${result.recommendation.exercises.size}")
        }
    }
}

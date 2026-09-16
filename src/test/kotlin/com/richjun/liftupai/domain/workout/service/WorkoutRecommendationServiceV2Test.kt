package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.auth.repository.UserRepository
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
import java.util.Optional

/**
 * WorkoutServiceV2에서 분리된 WorkoutRecommendationServiceV2(오케스트레이터)에 대한 검증.
 * (구 WorkoutServiceV2PTTest 중 getBasicWorkoutRecommendation 조립 로직 부분)
 *
 * 세트/렙/무게 계산 자체(PT 엔진)는 PTRecommendationEngineTest에서 검증하므로,
 * 여기서는 ptEngine을 목으로 대체해 오케스트레이션(타겟 근육 선택, 폴백, DTO 조립)만 검증한다.
 */
class WorkoutRecommendationServiceV2Test {

    private lateinit var workoutRecommendationServiceV2: WorkoutRecommendationServiceV2

    private lateinit var userRepository: UserRepository
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var userSettingsRepository: UserSettingsRepository
    private lateinit var workoutSessionRepository: WorkoutSessionRepository

    private lateinit var workoutProgressTracker: WorkoutProgressTracker
    private lateinit var exerciseRecommendationService: ExerciseRecommendationService
    private lateinit var exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService
    private lateinit var autoProgramSelector: AutoProgramSelector
    private lateinit var sharedSupport: WorkoutV2SharedSupport
    private lateinit var ptEngine: PTRecommendationEngine
    private lateinit var injuryFilterService: InjuryFilterService
    private lateinit var muscleRecoveryRepository: com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository

    private lateinit var testUser: User
    private lateinit var testUserProfile: UserProfile

    private val defaultPtRecommendation = PTRecommendationEngine.PTRecommendation(
        weight = 60.0,
        sets = 4,
        reps = "8-10",
        restSeconds = 90,
        intensity = 75,
        reason = "test-reason",
        coachingTip = "test-tip",
        focus = "test-focus"
    )

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        userProfileRepository = mockk()
        userSettingsRepository = mockk()
        workoutSessionRepository = mockk()
        workoutProgressTracker = mockk()
        exerciseRecommendationService = mockk()
        exerciseCatalogLocalizationService = mockk()
        autoProgramSelector = mockk()
        sharedSupport = WorkoutV2SharedSupport(userSettingsRepository, exerciseCatalogLocalizationService, workoutSessionRepository)
        ptEngine = mockk()
        injuryFilterService = mockk()
        muscleRecoveryRepository = mockk()
        every { injuryFilterService.filterExercises(any(), any()) } answers { firstArg() }
        every { muscleRecoveryRepository.findByUser(any()) } returns emptyList()

        workoutRecommendationServiceV2 = WorkoutRecommendationServiceV2(
            userRepository,
            userProfileRepository,
            userSettingsRepository,
            workoutSessionRepository,
            workoutProgressTracker,
            exerciseRecommendationService,
            autoProgramSelector,
            null,
            sharedSupport,
            ptEngine,
            injuryFilterService,
            muscleRecoveryRepository,
        )

        testUser = User(id = 1L, email = "test@test.com", nickname = "TestUser", level = UserLevel.INTERMEDIATE)

        testUserProfile = UserProfile(
            id = 1L,
            user = testUser,
            bodyInfo = BodyInfo(weight = 80.0),
            gender = "male",
            experienceLevel = ExperienceLevel.INTERMEDIATE
        )

        every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
        every { exerciseCatalogLocalizationService.normalizeLocale(any()) } returns "en"
        every { ptEngine.calculateFullPTRecommendation(any(), any()) } returns defaultPtRecommendation
    }

    // -------------------------------------------------------------------------
    // getBasicWorkoutRecommendation assembles PT engine output into the response
    // -------------------------------------------------------------------------

    @Nested
    inner class BasicWorkoutRecommendationUsesPTSystem {

        @Test
        fun `all exercises in recommendation have positive sets from PT system`() {
            val chestExercise = Exercise(id = 2L, slug = "incline-press", name = "Incline Press", category = ExerciseCategory.CHEST)
            val backExercise = Exercise(id = 3L, slug = "deadlift", name = "Deadlift", category = ExerciseCategory.BACK)

            stubBasicWorkout(listOf(chestExercise, backExercise))

            val result = workoutRecommendationServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 30)

            assertTrue(result.recommendation.exercises.isNotEmpty(), "Should have exercises")
            result.recommendation.exercises.forEach { ex ->
                assertTrue(ex.sets > 0, "Every exercise should have sets > 0, got ${ex.sets}")
            }
        }

        @Test
        fun `all exercises in recommendation have non-blank reps from PT system`() {
            val legExercise = Exercise(id = 4L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS)

            stubBasicWorkout(listOf(legExercise))

            val result = workoutRecommendationServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 30)

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

            val result = workoutRecommendationServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 45, targetMuscle = "legs")

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
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases: no profile, no settings → default FULL_BODY; vector fallback
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

            val result = workoutRecommendationServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 60)

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

            // vectorRecommendationService is null (passed as null in constructor)
            // so it always falls back to rule-based
            val result = workoutRecommendationServiceV2.getBasicWorkoutRecommendation(userId = 1L, duration = 60)

            // 60분 = 6개 운동이 목표
            assertEquals(6, result.recommendation.exercises.size,
                "60-min workout should have 6 exercises, got ${result.recommendation.exercises.size}")
        }
    }

    // -------------------------------------------------------------------------
    // 최종 안전망: 부상·장비·회복·중복 (추천 출처와 무관하게 마지막에 걸러진다)
    // -------------------------------------------------------------------------

    @Nested
    inner class SafetyFilters {

        private fun ex(id: Long, slug: String, pattern: String, cat: ExerciseCategory, eq: Equipment, vararg muscles: MuscleGroup) =
            Exercise(id = id, slug = slug, name = slug, category = cat, movementPattern = pattern, equipment = eq,
                muscleGroups = muscles.toMutableSet())

        private val squat = ex(1, "barbell-full-squat", "SQUAT", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES)
        private val lunge = ex(2, "barbell-lunge", "LUNGE", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.QUADRICEPS)
        private val bench = ex(3, "barbell-bench-press", "HORIZONTAL_PRESS_BARBELL", ExerciseCategory.CHEST, Equipment.BARBELL, MuscleGroup.CHEST)
        private val dbRow = ex(4, "dumbbell-bent-over-row", "DUMBBELL_ROW", ExerciseCategory.BACK, Equipment.DUMBBELL, MuscleGroup.BACK)
        private val pushup = ex(5, "push-up", "PUSHUP", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, MuscleGroup.CHEST)
        private val plank = ex(6, "plank", "PLANK", ExerciseCategory.CORE, Equipment.BODYWEIGHT, MuscleGroup.CORE)
        private val benchDup = ex(7, "barbell-bench-press-2", "HORIZONTAL_PRESS_BARBELL", ExerciseCategory.CHEST, Equipment.BARBELL, MuscleGroup.CHEST)
            .let { Exercise(id = 7, slug = it.slug, name = "barbell-bench-press", category = it.category, movementPattern = it.movementPattern, equipment = it.equipment, muscleGroups = it.muscleGroups) }

        /** 실제 규칙(부상 부위→패턴)으로 동작하는 InjuryFilterService. 큐레이션 테이블은 비어 있다(운영과 동일). */
        private fun realInjuryService(): InjuryFilterService = InjuryFilterService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )

        private fun serviceWith(
            injury: InjuryFilterService,
            repo: com.richjun.liftupai.domain.workout.repository.ExerciseRepository? = null,
        ) = WorkoutRecommendationServiceV2(
            userRepository, userProfileRepository, userSettingsRepository, workoutSessionRepository, workoutProgressTracker,
            exerciseRecommendationService, autoProgramSelector, null, sharedSupport, ptEngine, injury, muscleRecoveryRepository, repo,
        )

        @Test
        fun `무릎 부상 사용자의 최종 추천에 스쿼트·런지가 없다 (규칙 추천이 폴백으로 되돌려도)`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 70.0), gender = "male")
            profile.injuries = mutableSetOf("knee:severe")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, lunge, bench, dbRow), 2)

            assertEquals(listOf(bench, dbRow), result)
        }

        @Test
        fun `부상 필터는 결과가 적어져도 완화하지 않는다`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 70.0), gender = "male")
            profile.injuries = mutableSetOf("knee")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, lunge), 2)

            assertTrue(result.isEmpty(), "무릎 부상자에게 하체만 남았으면 비어야 한다: $result")
        }

        @Test
        fun `부상이 없으면 아무것도 걸러지지 않는다 (양성 대조)`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 70.0), gender = "male")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, lunge, bench, dbRow), 2)

            assertEquals(4, result.size)
        }

        @Test
        fun `홈트 장비없음(bodyweight) 사용자는 바벨·덤벨 종목을 받지 않는다`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 65.0), gender = "male")
            profile.availableEquipment = mutableSetOf("bodyweight")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, bench, pushup, plank, dbRow), 2)

            assertEquals(listOf(pushup, plank), result)
        }

        @Test
        fun `덤벨만 있으면 바벨 종목은 빠지고 덤벨·맨몸은 남는다`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 65.0), gender = "male")
            profile.availableEquipment = mutableSetOf("dumbbells")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, bench, pushup, dbRow), 2)

            assertEquals(listOf(pushup, dbRow), result)
        }

        @Test
        fun `장비가 안 맞아 빠진 운동은 같은 부위의 보유 장비 운동으로 채운다 (부상은 유지)`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 65.0), gender = "male")
            profile.availableEquipment = mutableSetOf("dumbbell")
            profile.injuries = mutableSetOf("knee")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
            val dbBench = ex(90, "dumbbell-bench-press", "HORIZONTAL_PRESS_DUMBBELL", ExerciseCategory.CHEST, Equipment.DUMBBELL, MuscleGroup.CHEST)
            val repo = mockk<com.richjun.liftupai.domain.workout.repository.ExerciseRepository>()
            every { repo.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.CHEST) } returns listOf(bench, dbBench)
            every { repo.findPlanEligibleByCategoryOrderByPopularity(any()) } returns emptyList()
            every { repo.findPlanEligibleByCategoryOrderByPopularity(ExerciseCategory.CHEST) } returns listOf(bench, dbBench)

            val result = serviceWith(realInjuryService(), repo).applySafetyFilters(testUser, profile, listOf(squat, bench, dbRow), 2)

            assertEquals(listOf(dbRow, dbBench), result, "바벨 벤치 → 덤벨 벤치로 대체, 스쿼트는 부상으로 제외")
        }

        @Test
        fun `대체 후보가 없어 최소 개수 미만이면 장비 필터만 푼다 (부상은 유지)`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 65.0), gender = "male")
            profile.availableEquipment = mutableSetOf("kettlebell")
            profile.injuries = mutableSetOf("knee")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(squat, bench, dbRow), 2)

            assertEquals(listOf(bench, dbRow), result, "케틀벨만으론 0개라 장비 필터는 풀리지만 스쿼트는 여전히 없다")
        }

        @Test
        fun `회복률 50% 미만 근육의 주동 운동은 빠진다`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 70.0), gender = "male")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()
            every { muscleRecoveryRepository.findByUser(testUser) } returns listOf(
                com.richjun.liftupai.domain.recovery.entity.MuscleRecovery(
                    user = testUser, muscleGroup = "chest", lastWorked = java.time.LocalDateTime.now(), recoveryPercentage = 30)
            )

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(bench, pushup, dbRow, squat), 2)

            assertEquals(listOf(dbRow, squat), result)
        }

        @Test
        fun `같은 운동(id 또는 이름 중복)은 한 번만 나간다`() {
            val profile = UserProfile(id = 1L, user = testUser, bodyInfo = BodyInfo(weight = 70.0), gender = "male")
            every { userSettingsRepository.findByUser_Id(1L) } returns Optional.empty()

            val result = serviceWith(realInjuryService()).applySafetyFilters(testUser, profile, listOf(bench, bench, benchDup, dbRow), 2)

            assertEquals(listOf(bench, dbRow), result)
        }
    }
}

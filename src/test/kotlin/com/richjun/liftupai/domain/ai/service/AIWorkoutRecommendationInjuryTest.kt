package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
import com.richjun.liftupai.domain.workout.service.*
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * /api/ai/recommendations/personalized (AIAnalysisService.getAIWorkoutRecommendation) 의 부상 반영.
 *
 * 로컬 e2e 기준선(2026-09-14): 무릎 부상(knee:moderate)을 프로필에 등록한 중급 78kg 남성에게
 * 응답 6개 중 4개가 무릎 부하 운동(풀 스쿼트·런지·데드리프트·RDL)이었다.
 * 원인 1: settings 행은 있으나 injuries 가 비어 있고 부상은 profile 에 있는데 `settings?.injuries ?: profile?.injuries` 가 빈 집합을 골랐다.
 * 원인 2: AI 가 후보 밖 id 를 골라도 최종 응답에서 다시 거르지 않았다.
 */
class AIWorkoutRecommendationInjuryTest {

    private val user = User(id = 1L, email = "t@t.t", nickname = "T", level = UserLevel.INTERMEDIATE)

    private fun ex(id: Long, slug: String, pattern: String, cat: ExerciseCategory, eq: Equipment, vararg muscles: MuscleGroup) =
        Exercise(id = id, slug = slug, name = slug, category = cat, movementPattern = pattern, equipment = eq, muscleGroups = muscles.toMutableSet())

    private val squat = ex(1, "barbell-full-squat", "SQUAT", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES)
    private val lunge = ex(2, "barbell-lunge", "LUNGE", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.QUADRICEPS)
    private val deadlift = ex(3, "barbell-deadlift", "DEADLIFT", ExerciseCategory.BACK, Equipment.BARBELL, MuscleGroup.HAMSTRINGS, MuscleGroup.BACK)
    private val rdl = ex(4, "barbell-romanian-deadlift", "HIP_HINGE", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.HAMSTRINGS)
    private val bench = ex(5, "barbell-bench-press", "HORIZONTAL_PRESS_BARBELL", ExerciseCategory.CHEST, Equipment.BARBELL, MuscleGroup.CHEST)
    private val row = ex(6, "barbell-bent-over-row-pronated-grip", "BARBELL_ROW", ExerciseCategory.BACK, Equipment.BARBELL, MuscleGroup.BACK)
    private val ohp = ex(7, "barbell-standing-military-press", "VERTICAL_PRESS_BARBELL", ExerciseCategory.SHOULDERS, Equipment.BARBELL, MuscleGroup.SHOULDERS)
    private val pulldown = ex(8, "cable-pulldown", "LAT_PULLDOWN", ExerciseCategory.BACK, Equipment.CABLE, MuscleGroup.BACK)
    private val curl = ex(9, "dumbbell-curl", "BICEP_CURL_DUMBBELL", ExerciseCategory.ARMS, Equipment.DUMBBELL, MuscleGroup.BICEPS)
    private val all = listOf(squat, lunge, deadlift, rdl, bench, row, ohp, pulldown, curl)

    private val userRepository = mockk<UserRepository>()
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val geminiAIService = mockk<GeminiAIService>()
    private val workoutProgressTracker = mockk<WorkoutProgressTracker>(relaxed = true)
    private val ptEngine = mockk<PTRecommendationEngine>()
    private val vector = mockk<VectorWorkoutRecommendationService>()
    private val localization = mockk<ExerciseCatalogLocalizationService>()
    private val enrollmentRepository = mockk<UserProgramEnrollmentRepository>()
    private val injuryFilterService = InjuryFilterService(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )

    private val service = AIAnalysisService(
        userRepository, userProfileRepository, userSettingsRepository, exerciseRepository, geminiAIService,
        ObjectMapper().registerKotlinModule(), workoutProgressTracker, ptEngine,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), vector,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        localization, injuryFilterService, enrollmentRepository, mockk(relaxed = true),
        mockk(relaxed = true),
    )

    /** 로컬 e2e 와 같은 사용자: settings 행 존재(부상 비어 있음), 부상은 profile 에 "knee:moderate" */
    private fun stubKneeUser(aiPicks: List<Exercise>, equipment: Set<String> = emptySet()) {
        val profile = UserProfile(id = 1L, user = user, bodyInfo = BodyInfo(weight = 78.0), gender = "male", age = 34, experienceLevel = ExperienceLevel.INTERMEDIATE)
        profile.injuries = mutableSetOf("knee:moderate")
        profile.availableEquipment = equipment.toMutableSet()
        val settings = UserSettings(user = user) // injuries 비어 있음, availableEquipment 비어 있음(프로필 폴백)
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(any()) } answers { all.filter { it.category == firstArg<ExerciseCategory>() } }
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(profile)
        every { userSettingsRepository.findByUser_Id(1L) } returns Optional.of(settings)
        every { localization.normalizeLocale(any()) } returns "ko"
        every { localization.translationMap(any(), any()) } returns emptyMap()
        every { localization.displayName(any(), any(), any()) } answers { firstArg<Exercise>().name }
        every { workoutProgressTracker.hasStartedWorkoutToday(user) } returns false
        every { workoutProgressTracker.getNextWorkoutInProgram(user, any()) } returns WorkoutProgramPosition(day = 1, cycle = 1, isNewCycle = false)
        every { workoutProgressTracker.getWorkoutTypeSequence(any()) } returns listOf(WorkoutType.FULL_BODY)
        every { enrollmentRepository.findFirstByUserAndStatusOrderByStartDateDesc(user, EnrollmentStatus.ACTIVE) } returns null
        every { vector.recommendExercises(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns all
        all.forEach { e -> every { exerciseRepository.findById(e.id) } returns Optional.of(e) }
        every { exerciseRepository.findAll() } returns all
        every { ptEngine.calculateFullPTRecommendation(any(), any()) } returns PTRecommendationEngine.PTRecommendation(
            weight = 50.0, sets = 3, reps = "8-10", restSeconds = 90, intensity = 75, reason = "r", coachingTip = "t", focus = "f")
        val json = """{"workout_name":"x","difficulty":"intermediate","estimated_calories":300,"target_muscles":[],"equipment":[],
            "exercises":[${aiPicks.mapIndexed { i, e -> """{"id":${e.id},"sets":3,"reps":"8-10","rest_seconds":90,"order":${i + 1}}""" }.joinToString(",")}]}"""
        every { geminiAIService.generateRecommendations(any()) } returns json
    }

    @Test
    @DisplayName("부상 해석: settings 가 있어도 비어 있으면 profile 의 부상을 쓴다 (빈 집합에 가려지던 버그)")
    fun resolvesProfileInjuriesWhenSettingsEmpty() {
        val profile = UserProfile(id = 1L, user = user)
        profile.injuries = mutableSetOf("knee:moderate")
        assertEquals(setOf("knee:moderate"), service.resolveUserInjuries(UserSettings(user = user), profile))
        assertEquals(setOf("knee:moderate"), service.resolveUserInjuries(null, profile))
        val s = UserSettings(user = user).apply { injuries = mutableSetOf("shoulder") }
        assertEquals(setOf("shoulder"), service.resolveUserInjuries(s, profile), "settings 에 값이 있으면 settings 우선")
        assertTrue(service.resolveUserInjuries(UserSettings(user = user).apply { injuries = mutableSetOf("none") }, null).isEmpty())
    }

    @Test
    @DisplayName("덤벨만 있는 무릎 부상 사용자: 바벨·케이블 운동은 빠지고 같은 부위의 덤벨 운동으로 대체된다")
    fun dumbbellOnlyUserGetsNoBarbell() {
        val dbBench = ex(20, "dumbbell-bench-press", "HORIZONTAL_PRESS_DUMBBELL", ExerciseCategory.CHEST, Equipment.DUMBBELL, MuscleGroup.CHEST)
        val dbRow = ex(21, "dumbbell-row", "DUMBBELL_ROW", ExerciseCategory.BACK, Equipment.DUMBBELL, MuscleGroup.BACK)
        val dbOhp = ex(22, "dumbbell-shoulder-press", "VERTICAL_PRESS_DUMBBELL", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL, MuscleGroup.SHOULDERS)
        val plank = ex(23, "plank", "PLANK", ExerciseCategory.CORE, Equipment.BODYWEIGHT, MuscleGroup.CORE)
        val extra = listOf(dbBench, dbRow, dbOhp, plank)
        stubKneeUser(aiPicks = listOf(bench, row, pulldown, ohp, curl, squat), equipment = setOf("dumbbell"))
        every { exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(any()) } answers { (all + extra).filter { it.category == firstArg<ExerciseCategory>() } }
        every { exerciseRepository.findAll() } returns all + extra
        extra.forEach { e -> every { exerciseRepository.findById(e.id) } returns Optional.of(e) }

        val result = service.getAIWorkoutRecommendation(userId = 1L, duration = 30)

        val names = result.recommendation.exercises.map { it.name }
        assertTrue(names.none { it in listOf(bench.name, row.name, pulldown.name, ohp.name, squat.name) }, "바벨/케이블/부상 운동이 남아 있다: $names")
        assertTrue(names.size >= 4, "대체로 개수가 유지돼야 한다: $names")
        assertTrue(names.all { it in (all + extra).filter { e -> e.equipment == Equipment.DUMBBELL || e.equipment == Equipment.BODYWEIGHT }.map { it.name } }, "덤벨·맨몸만: $names")
    }

    @Test
    @DisplayName("무릎 부상 사용자의 개인화 추천: AI 가 스쿼트·런지·데드·RDL 을 골라도 최종 응답에 무릎 부하 운동이 0개")
    fun kneeUserGetsNoKneeLoadingExercises() {
        stubKneeUser(aiPicks = listOf(squat, lunge, deadlift, rdl, bench, row, ohp, pulldown))

        val result = service.getAIWorkoutRecommendation(userId = 1L, duration = 45)

        val names = result.recommendation.exercises.map { it.name }
        val kneeLoading = listOf(squat, lunge, deadlift, rdl).map { it.name }
        assertTrue(names.none { it in kneeLoading }, "무릎 부하 운동이 남아 있다: $names")
        assertTrue(names.isNotEmpty(), "추천이 비면 안 된다")
        assertTrue(names.containsAll(listOf(bench.name, row.name, ohp.name, pulldown.name)), "상체 운동은 그대로: $names")
    }

    @Test
    @DisplayName("양성 대조: 부상이 없으면 같은 AI 선택이 그대로 나간다")
    fun noInjuryKeepsAllPicks() {
        stubKneeUser(aiPicks = listOf(squat, lunge, deadlift, rdl, bench, row, ohp, pulldown))
        val clean = UserProfile(id = 1L, user = user, bodyInfo = BodyInfo(weight = 78.0), gender = "male", age = 34, experienceLevel = ExperienceLevel.INTERMEDIATE)
        every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(clean)

        // 8종목 × 3세트 × (8-10회 + 휴식 90초) ≈ 56분 — 시간 맞춤이 종목을 줄이지 않는 60분으로 요청한다
        val result = service.getAIWorkoutRecommendation(userId = 1L, duration = 60)

        assertEquals(8, result.recommendation.exercises.size)
    }

    @Test
    @DisplayName("AI 가 무릎 운동만 골라 남는 게 부족하면 폴백으로 가되, 폴백도 무릎 운동을 주지 않는다")
    fun fallbackAlsoRespectsInjury() {
        stubKneeUser(aiPicks = listOf(squat, lunge, deadlift, rdl, curl))

        val result = service.getAIWorkoutRecommendation(userId = 1L, duration = 45)

        val names = result.recommendation.exercises.map { it.name }
        val kneeLoading = listOf(squat, lunge, deadlift, rdl).map { it.name }
        assertTrue(names.none { it in kneeLoading }, "폴백에 무릎 부하 운동: $names")
        assertTrue(names.isNotEmpty())
    }

    @Test
    @DisplayName("AI 응답이 중간에 잘려도(JSON 미완성) 추천은 예외 없이 나가고, 건질 수 있는 인사이트는 건진다")
    fun truncatedAiResponseDoesNotThrow() {
        stubKneeUser(aiPicks = listOf(bench, row, ohp, pulldown))
        every { geminiAIService.generateRecommendations(any()) } returns
            """{"workout_name":"x","exercises":[{"id":5,"sets":3},{"id":6,"sets":3}],"ai_insights":{"workout_rationale":"상체 위주로 구성했어요","key_point":"어깨를 내리고"""

        val result = service.getAIWorkoutRecommendation(userId = 1L, duration = 45)

        assertTrue(result.recommendation.exercises.isNotEmpty(), "잘린 응답이면 폴백으로라도 추천이 나가야 한다")
        val names = result.recommendation.exercises.map { it.name }
        assertTrue(names.none { it in listOf(squat, lunge, deadlift, rdl).map { e -> e.name } }, "폴백에도 무릎 운동 없음: $names")
    }

    @Test
    @DisplayName("잘린 JSON 에서 인사이트 문자열 필드를 정규식으로 건진다")
    fun salvageInsights() {
        val s = service.salvageInsightsFromText("""{"a":1,"ai_insights":{"workout_rationale":"상체 \"집중\" 루틴","key_point":"호흡","next_step":"다음�""")
        assertNotNull(s)
        assertEquals("상체 \"집중\" 루틴", s!!.workoutRationale)
        assertEquals("호흡", s.keyPoint)
        assertNull(s.nextStep, "닫히지 않은 값은 건지지 않는다")
        assertNull(service.salvageInsightsFromText("garbage"), "아무 필드도 없으면 null")
    }

    @Test
    @DisplayName("시간 맞춤: 45분 요청에 8종목 × 3세트 × 휴식 90초(약 56분)가 오면 45분 안으로 줄인다")
    fun fitsAIPicksIntoRequestedDuration() {
        stubKneeUser(aiPicks = listOf(squat, lunge, deadlift, rdl, bench, row, ohp, pulldown))
        val clean = UserProfile(id = 1L, user = user, bodyInfo = BodyInfo(weight = 78.0), gender = "male", age = 34, experienceLevel = ExperienceLevel.INTERMEDIATE)
        every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(clean)

        val exercises = service.getAIWorkoutRecommendation(userId = 1L, duration = 45).recommendation.exercises
        val slots = exercises.map { com.richjun.liftupai.domain.workout.util.SessionDurationFitter.Slot(it.sets, it.reps, it.rest, it.rest) }

        assertTrue(com.richjun.liftupai.domain.workout.util.SessionDurationFitter.estimateMinutes(slots) <= 47, "추정 ${slots}")
        assertTrue(exercises.size >= 3)
        assertEquals((1..exercises.size).toList(), exercises.map { it.order })
    }

    @Test
    @DisplayName("AI 가 목표보다 적게 고르면(코어 위주) 코어가 아닌, 덜 쓴 부위의 안전 후보로 채운다")
    fun topsUpShortAIPicksWithNonCoreExercises() {
        val plank = ex(30, "plank", "PLANK", ExerciseCategory.CORE, Equipment.BODYWEIGHT, MuscleGroup.CORE)
        val crunch = ex(31, "crunch", "CRUNCH", ExerciseCategory.CORE, Equipment.BODYWEIGHT, MuscleGroup.ABS)
        val chosen = mapOf(bench.id to bench, plank.id to plank)
        val pool = listOf(crunch, bench, row, squat)
        val picks = service.topUpPicks(chosen, pool, target = 4, availableEquipment = emptySet(), suitable = { true })
        assertEquals(listOf(row, squat).map { it.id }.toSet(), picks.map { it.id }.toSet())
        assertTrue(picks.none { it.category == ExerciseCategory.CORE })
        // 이미 충분하면 채우지 않는다
        assertTrue(service.topUpPicks(chosen, pool, target = 2, availableEquipment = emptySet(), suitable = { true }).isEmpty())
        // 보유 장비 밖 후보는 쓰지 않는다
        assertTrue(service.topUpPicks(chosen, listOf(row, squat), target = 4, availableEquipment = setOf("dumbbell"), suitable = { true }).isEmpty())
    }
}

package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.dto.response.PlanDashboardResponse
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import com.richjun.liftupai.domain.workout.entity.SplitType
import com.richjun.liftupai.domain.workout.entity.WorkoutPlanTemplate
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutPlanTemplateRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * AIPlanGenerationService 의 실패 경로: 파싱 실패 → 1회 재요청 → 템플릿 폴백,
 * 품질 오류 → 구체적 수정 지시로 재생성. Gemini 는 가짜 응답으로 대체(실제 호출 없음).
 */
class AIPlanGenerationRetryFallbackTest {

    private val gemini = mockk<GeminiAIService>()
    private val exerciseRepo = mockk<ExerciseRepository>()
    private val templateRepo = mockk<WorkoutPlanTemplateRepository>()
    private val userPlanService = mockk<UserPlanService>()
    private val profileRepo = mockk<UserProfileRepository>()
    private val settingsRepo = mockk<UserSettingsRepository>()
    private val injuryFilter = mockk<InjuryFilterService>()
    private val classifier = ExercisePatternClassifier()
    private val self = mockk<AIPlanGenerationService>()
    private val mapper = ObjectMapper().registerKotlinModule()

    private val bench = ex(1, "Barbell Bench Press", ExerciseCategory.CHEST, "HORIZONTAL_PRESS_BARBELL", MuscleGroup.CHEST)
    private val squat = ex(2, "Barbell Back Squat", ExerciseCategory.LEGS, "SQUAT", MuscleGroup.QUADRICEPS)
    private val row = ex(3, "Barbell Row", ExerciseCategory.BACK, "BARBELL_ROW", MuscleGroup.BACK)
    private val plank = ex(4, "Plank", ExerciseCategory.CORE, "PLANK", MuscleGroup.CORE, Equipment.BODYWEIGHT)
    private val ohp = ex(5, "Overhead Press", ExerciseCategory.SHOULDERS, "VERTICAL_PRESS_BARBELL", MuscleGroup.SHOULDERS)
    private val catalog = listOf(bench, squat, row, plank, ohp)

    private val user = User(id = 7L, email = "t@example.com", nickname = "t")
    private val dashboard = PlanDashboardResponse(1, "p", null, "FULL_BODY", "AI_GENERATED", 3, 1, "LINEAR", 4, emptyList(), 0, null, "now")
    private val templateDashboard = dashboard.copy(planName = "초급자 전신 루틴", sourceType = "TEMPLATE")

    private val service = AIPlanGenerationService(
        geminiAIService = gemini,
        curatedExercisePoolService = CuratedExercisePoolService(exerciseRepo),
        hallucinationGuardService = HallucinationGuardService(exerciseRepo, classifier),
        planQualityValidator = PlanQualityValidator(exerciseRepo, classifier),
        userPlanService = userPlanService,
        exerciseRepository = exerciseRepo,
        templateRepository = templateRepo,
        templateDayRepository = mockk(relaxed = true),
        templateDayExerciseRepository = mockk(relaxed = true),
        userWorkoutPlanRepository = mockk(relaxed = true),
        userPlanDayRepository = mockk(relaxed = true),
        userPlanDayExerciseRepository = mockk(relaxed = true),
        injuryFilterService = injuryFilter,
        userProfileRepository = profileRepo,
        userSettingsRepository = settingsRepo,
        objectMapper = mapper,
        self = self
    )

    init {
        every { exerciseRepo.findAllByIsPlanEligibleTrue() } returns catalog
        every { exerciseRepo.findAllById(any()) } answers { val ids = firstArg<Iterable<Long>>().toSet(); catalog.filter { it.id in ids } }
        every { exerciseRepo.findById(any()) } answers { Optional.ofNullable(catalog.firstOrNull { it.id == firstArg<Long>() }) }
        every { exerciseRepo.findByNameIgnoreCase(any()) } answers { catalog.firstOrNull { it.name.equals(firstArg<String>(), true) } }
        every { exerciseRepo.findPlanEligibleByNameContaining(any()) } answers { val q = firstArg<String>().lowercase(); catalog.filter { it.name.lowercase().contains(q) } }
        every { exerciseRepo.findPlanEligibleByCategoryOrderByPopularity(any()) } answers { val c = firstArg<ExerciseCategory>(); catalog.filter { it.category == c } }
        every { profileRepo.findByUser_Id(any()) } returns Optional.empty()
        every { settingsRepo.findByUser_Id(any()) } returns Optional.empty()
        every { injuryFilter.isExerciseRestricted(any(), any()) } returns false
        every { self.persistAIPlan(any(), any(), any()) } returns dashboard
        every { templateRepo.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET) } returns listOf(
            template("beginner_fullbody", "beginner", 3), template("hypertrophy_basic", "beginner", 4),
            template("ppl_classic", "intermediate", 3), template("advanced_pplul", "advanced", 5)
        )
        every { userPlanService.applyTemplate(any(), any(), any(), any()) } returns templateDashboard
    }

    private fun ex(id: Long, name: String, cat: ExerciseCategory, pattern: String, m: MuscleGroup, eq: Equipment = Equipment.BARBELL) =
        Exercise(id = id, slug = name.lowercase().replace(' ', '-'), name = name, category = cat, movementPattern = pattern,
            muscleGroups = mutableSetOf(m), equipment = eq, isPlanEligible = true, popularity = 80)

    private fun template(code: String, exp: String, days: Int) = WorkoutPlanTemplate(
        code = code, name = code, targetGoal = "muscle_gain", targetExperience = exp, splitType = SplitType.FULL_BODY, totalDays = days)

    private fun request(days: Int = 3, exp: String = "beginner") = GenerateAIPlanRequest(
        experienceLevel = exp, goals = listOf("MUSCLE_GAIN"), gender = "male", age = 30, height = 175.0, weight = 70.0,
        weeklyDays = days, sessionDuration = 60, equipment = listOf("BARBELL", "BODYWEIGHT"), locale = "ko")

    private fun exJson(e: Exercise, order: Int, sets: Int = 3) =
        """{"exerciseId":${e.id},"exerciseName":"${e.name}","sets":$sets,"minReps":8,"maxReps":12,"restSeconds":90,"order":$order,"isCompound":true,"targetRPE":7}"""

    private fun dayJson(n: Int, type: String, vararg es: Exercise) =
        """{"dayNumber":$n,"dayName":"Day $n","workoutType":"$type","estimatedDurationMinutes":60,"exercises":[${es.mapIndexed { i, e -> exJson(e, i + 1) }.joinToString(",")}]}"""

    private fun goodPlanJson() = """{"planName":"플랜","planDescription":"설명","splitType":"FULL_BODY","totalDays":3,"estimatedWeeks":8,"progressionModel":"LINEAR","days":[
        ${dayJson(1, "FULL_BODY", bench, squat, row, plank)}, ${dayJson(2, "FULL_BODY", ohp, squat, row, plank)}, ${dayJson(3, "FULL_BODY", bench, squat, row, plank)}]}"""

    /** 하체·코어 없음 + 연속 PUSH → 품질 ERROR */
    private fun badBalanceJson() = """{"planName":"플랜","planDescription":"설명","splitType":"PPL","totalDays":3,"estimatedWeeks":8,"days":[
        ${dayJson(1, "PUSH", bench, ohp, row)}, ${dayJson(2, "PUSH", bench, ohp, row)}, ${dayJson(3, "PULL", row, bench, ohp)}]}"""

    @Test
    fun `정상 응답은 한 번에 저장된다`() {
        every { gemini.generatePlanContent(any()) } returns goodPlanJson()
        val res = service.generateAndApplyPlan(user, request())
        assertEquals(dashboard, res)
        verify(exactly = 1) { gemini.generatePlanContent(any()) }
        verify(exactly = 0) { userPlanService.applyTemplate(any(), any(), any(), any()) }
    }

    @Test
    fun `JSON 이 아니면 JSON-only 지시로 1회 재요청하고 성공하면 저장한다`() {
        val prompts = mutableListOf<String>()
        every { gemini.generatePlanContent(capture(prompts)) } returnsMany listOf("죄송합니다, 플랜을 만들어 드릴게요!", goodPlanJson())
        val res = service.generateAndApplyPlan(user, request())
        assertEquals(dashboard, res)
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("NOT VALID JSON"), "재요청 프롬프트에 JSON-only 지시가 있어야 한다")
    }

    @Test
    fun `두 번 다 파싱 실패면 에러 대신 템플릿 플랜이 적용된다`() {
        every { gemini.generatePlanContent(any()) } returns "not json at all"
        val res = service.generateAndApplyPlan(user, request(days = 3, exp = "beginner"))
        assertEquals(templateDashboard, res)
        val code = slot<String>()
        verify { userPlanService.applyTemplate(user, capture(code), any<ApplyTemplateRequest>(), "ko") }
        assertEquals("beginner_fullbody", code.captured, "초보·주3일 → 가장 가까운 프리셋")
    }

    @Test
    fun `Gemini 호출 자체가 계속 실패해도 템플릿 플랜이 간다`() {
        every { gemini.generatePlanContent(any()) } throws RuntimeException("503")
        val res = service.generateAndApplyPlan(user, request(days = 5, exp = "advanced"))
        assertEquals(templateDashboard, res)
        val code = slot<String>()
        verify { userPlanService.applyTemplate(user, capture(code), any<ApplyTemplateRequest>(), any()) }
        assertEquals("advanced_pplul", code.captured)
    }

    @Test
    fun `품질 오류가 나면 구체적 수정 지시를 붙여 재생성하고 고쳐진 플랜을 저장한다`() {
        val prompts = mutableListOf<String>()
        every { gemini.generatePlanContent(capture(prompts)) } returnsMany listOf(badBalanceJson(), goodPlanJson())
        val res = service.generateAndApplyPlan(user, request())
        assertEquals(dashboard, res)
        assertEquals(2, prompts.size)
        val fix = prompts[1]
        assertTrue(fix.contains("PREVIOUS ATTEMPT FAILED VALIDATION"))
        assertTrue(fix.contains("lower body") && fix.contains("core"), "누락 부위를 구체적으로 지시: $fix")
        assertTrue(fix.contains("consecutive days"), "연속 같은 부위 지시: $fix")
    }

    @Test
    fun `부상은 요청·프로필·설정을 합쳐 조건으로 쓴다`() {
        every { settingsRepo.findByUser_Id(7L) } returns Optional.of(mockk { every { injuries } returns mutableSetOf("knee:moderate") })
        val merged = service.resolveInjuries(user, request().copy(injuries = listOf("Shoulder")))
        assertEquals(setOf("shoulder", "knee"), merged)
    }

    @Test
    fun `프롬프트에 미성년 안전 규칙이 들어간다`() {
        val t = service.targetsOf(request().copy(age = 15, height = 160.0, weight = 42.0, goals = listOf("WEIGHT_LOSS")))
        val rules = service.buildPopulationRules(t)
        assertTrue(rules.contains("MINOR") && rules.contains("NEVER around weight loss"), rules)
        assertTrue(rules.contains("underweight"), rules)
        assertTrue(service.buildPopulationRules(service.targetsOf(request())).isEmpty(), "성인 정상 체중은 규칙 없음")
    }
}

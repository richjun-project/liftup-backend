package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest
import com.richjun.liftupai.domain.workout.entity.Exercise
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * 부상 해석 일치: 부상이 프로필에만 / 설정에만 / 둘 다 / 요청에만 있을 때 8개 경로
 * (템플릿 적용·템플릿 추천·제안·대체·AI 플랜·AI 채팅·V2 퀵·프로그램 생성)가 같은 부상 집합을 쓰고,
 * 그 집합이 막는 운동을 어느 경로도 내보내지 않는다.
 *
 * 탐색(2026-10): 경로마다 해석기가 달랐다. 설정 우선(프로필 무시) 4곳, 요청만 보는 곳, 요청이 저장값을 가리는 곳이 있어
 * 기기 가입 사용자(부상이 프로필에만 있음)에게 무릎 운동이 나가는 사고가 반복됐다.
 */
class InjuryResolutionParityTest {

    private data class Scenario(val name: String, val profile: List<String>, val settings: List<String>, val request: List<String>) {
        /** 요청을 받는 경로가 기대하는 집합 */
        val withRequest: Set<String> = RecoRules.normalizeInjuries(profile + settings + request)
        /** 요청을 받지 않는 경로(제안·대체·채팅·퀵·프로그램)가 기대하는 집합 */
        val stored: Set<String> = RecoRules.normalizeInjuries(profile + settings)
    }

    private val scenarios = listOf(
        Scenario("프로필에만", listOf("knee:moderate"), emptyList(), emptyList()),
        Scenario("설정에만", emptyList(), listOf("lower_back"), emptyList()),
        Scenario("프로필과 설정 모두", listOf("knee"), listOf("shoulder:mild"), emptyList()),
        Scenario("요청에만", emptyList(), emptyList(), listOf("Shoulder")),
        Scenario("셋 다(심각도·날짜 접미사·none 포함)", listOf("knee:severe:2026-09-01"), listOf("none", "lower_back"), listOf("elbow")),
    )

    private val gym = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT")
    private fun restrictedBy(injuries: Set<String>): (Exercise) -> Boolean = { ex -> injuries.isNotEmpty() && InjuryFilterService.ruleRestricts(ex, injuries) }

    private fun repos(s: Scenario) = RecoTestKit.Repos(
        RecoTestKit.profile(level = ExperienceLevel.INTERMEDIATE, age = 33, injuries = s.profile, equipment = gym),
        RecoTestKit.settings(injuries = s.settings, equipment = gym),
    )

    private fun assertClean(path: String, s: Scenario, injuries: Set<String>, out: Collection<Exercise>) {
        assertTrue(out.isNotEmpty(), "[$path / ${s.name}] 결과가 비면 검증이 의미 없다")
        val restricted = restrictedBy(injuries)
        val bad = out.filter(restricted)
        assertTrue(bad.isEmpty(), "[$path / ${s.name}] 부상 $injuries 가 막는 운동이 나갔다: ${bad.map { it.slug }}")
    }

    @Test
    @DisplayName("해석기 자체: 프로필·설정·요청 합집합, 부위만 남기고 none 은 뺀다")
    fun resolverUnion() {
        scenarios.forEach { s ->
            val r = repos(s)
            assertEquals(s.stored, r.resolver.resolve(1L).injuries, s.name)
            assertEquals(s.withRequest, r.resolver.resolve(1L, UserTrainingContextResolver.Overrides(injuries = s.request)).injuries, s.name)
        }
        assertEquals(setOf("knee", "lower_back", "elbow"), repos(scenarios.last()).resolver.resolve(1L,
            UserTrainingContextResolver.Overrides(injuries = scenarios.last().request)).injuries)
    }

    @Test
    @DisplayName("1. 템플릿 적용: 해석한 부상 집합이 합집합이고, 저장된 플랜에 막힌 운동이 없다")
    fun templateApply() {
        scenarios.forEach { s ->
            val f = ApplyFixture(repos(s), "beginner_fullbody")
            assertEquals(s.withRequest, f.service.resolveInjuries(f.user, ApplyTemplateRequest(injuries = s.request.ifEmpty { null })), s.name)
            f.apply(ApplyTemplateRequest(injuries = s.request.ifEmpty { null }))
            assertClean("apply", s, s.withRequest, f.saved.map { it.exercise })
        }
    }

    @Test
    @DisplayName("2. 템플릿 추천: 요청에 부상이 없어도 저장된 부상을 읽는다 — 추천과 적용이 같은 부상 집합")
    fun templateRecommend() {
        scenarios.forEach { s ->
            val r = repos(s)
            val svc = TemplateRecommendationService(
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), RecoTestKit.injuryFilter(),
                r.userSettingsRepository, r.userProfileRepository, r.resolver,
            )
            val request = RecommendTemplateRequest(experienceLevel = "intermediate", goals = listOf("strength"), weeklyDays = 3, injuries = s.request.ifEmpty { null })
            val ctx = r.resolver.resolve(1L, svc.overridesOf(request))
            assertEquals(s.withRequest, svc.toProfile(request, ctx).injuries, s.name)
            // 요청을 아예 안 보내도 저장된 부상은 읽힌다(예전엔 요청만 봐서 부상이 빠졌다)
            val bare = RecommendTemplateRequest(experienceLevel = "intermediate", goals = listOf("strength"), weeklyDays = 3)
            assertEquals(s.stored, svc.toProfile(bare, r.resolver.resolve(1L, svc.overridesOf(bare))).injuries, s.name)
        }
    }

    @Test
    @DisplayName("3. 운동 제안: 저장된 부상(프로필 ∪ 설정)이 막는 운동이 제안에 없다")
    fun suggestions() {
        scenarios.filter { it.stored.isNotEmpty() }.forEach { s ->
            val r = repos(s)
            val captured = slot<List<Exercise>>()
            val detail = mockk<ExerciseCatalogDetailService>()
            every { detail.toExerciseDetails(capture(captured), any()) } returns emptyList()
            val cache = mockk<ExerciseCatalogCache>().also { every { it.get() } returns ExerciseCatalogCache.Snapshot(RecoTestKit.catalog, 0L) }
            val shared = mockk<WorkoutV2SharedSupport>().also { every { it.resolveLocale(any(), any()) } returns "ko" }
            ExerciseSuggestionService(cache, r.resolver, RecoTestKit.injuryFilter(), detail, shared)
                .suggest(1L, null, null, null, 30, null)
            assertClean("suggestions", s, s.stored, captured.captured)
        }
    }

    @Test
    @DisplayName("4. 대체 운동: 저장된 부상이 막는 운동이 대체 후보에 없다")
    fun substitutes() {
        val squat = RecoTestKit.bySlug.getValue("barbell-bench-press")
        scenarios.filter { it.stored.isNotEmpty() }.forEach { s ->
            val r = repos(s)
            val svc = ProgramService(
                userRepository = mockk(relaxed = true), canonicalProgramRepository = mockk(relaxed = true), programDayRepository = mockk(relaxed = true),
                programDayExerciseRepository = mockk(relaxed = true), userProgramEnrollmentRepository = mockk(relaxed = true),
                exerciseSubstitutionRepository = mockk(relaxed = true), userExerciseOverrideRepository = mockk(relaxed = true),
                exerciseRepository = RecoTestKit.exerciseRepository(), canonicalProgramService = mockk(relaxed = true),
                enrollmentService = mockk(relaxed = true), workoutGeneratorService = mockk(relaxed = true), absenceDetectionService = mockk(relaxed = true),
                userSettingsRepository = r.userSettingsRepository, localizationService = ExerciseCatalogLocalizationService(mockk(relaxed = true)),
                exerciseSubstitutionService = mockk(relaxed = true), userPlanService = mockk(relaxed = true), exerciseSearchIndexService = mockk(relaxed = true),
                exerciseCatalogCache = mockk<ExerciseCatalogCache>().also { every { it.get() } returns ExerciseCatalogCache.Snapshot(RecoTestKit.catalog, 0L) },
                userTrainingContextResolver = r.resolver, injuryFilterService = RecoTestKit.injuryFilter(),
            )
            val res = svc.getExerciseSubstitutes(squat.id, "ko", 1L)
            assertClean("substitutes", s, s.stored, res.substitutes.map { RecoTestKit.byId.getValue(it.exerciseId) })
        }
    }

    @Test
    @DisplayName("5. AI 플랜: 요청·프로필·설정 합집합을 쓰고, AI 가 제한 운동을 골라도 확정 플랜에는 없다")
    fun aiPlan() {
        val risky = listOf("barbell-full-squat", "barbell-bench-press", "barbell-deadlift", "seated-shoulder-press-barbell", "dumbbell-curls", "barbell-lunge", "lying-triceps-extension")
            .mapNotNull { RecoTestKit.bySlug[it] }
        scenarios.forEach { s ->
            val r = repos(s)
            val svc = AIPlanGenerationService(
                geminiAIService = mockk(), curatedExercisePoolService = CuratedExercisePoolService(RecoTestKit.exerciseRepository()),
                hallucinationGuardService = HallucinationGuardService(RecoTestKit.exerciseRepository(), ExercisePatternClassifier()),
                planQualityValidator = mockk(), userPlanService = mockk(), exerciseRepository = RecoTestKit.exerciseRepository(),
                templateRepository = mockk(relaxed = true), templateDayRepository = mockk(relaxed = true), templateDayExerciseRepository = mockk(relaxed = true),
                userWorkoutPlanRepository = mockk(relaxed = true), userPlanDayRepository = mockk(relaxed = true), userPlanDayExerciseRepository = mockk(relaxed = true),
                injuryFilterService = RecoTestKit.injuryFilter(), userProfileRepository = r.userProfileRepository, userSettingsRepository = r.userSettingsRepository,
                objectMapper = RecoTestKit.mapper, self = mockk(), trainingContext = r.resolver,
            )
            val request = GenerateAIPlanRequest(
                experienceLevel = "intermediate", goals = listOf("MUSCLE_GAIN"), gender = "male", age = 33, weeklyDays = 3, sessionDuration = 60,
                equipment = gym, injuries = s.request.ifEmpty { null }, locale = "ko",
            )
            assertEquals(s.withRequest, svc.resolveInjuries(r.profile!!.user, request), s.name)
            val plan = AIPlanResponse(
                "p", "d", "FULL_BODY", 3, 8,
                (1..3).map { n -> AIPlanDayEntry(n, "Day $n", "FULL_BODY", 60, risky.mapIndexed { i, e ->
                    AIPlanExerciseEntry(e.id, e.name, 3, 8, 12, 90, i + 1, true, 7.0)
                }) },
            )
            val days = svc.resolveDays(r.profile.user, plan, request)
            assertClean("ai_plan", s, s.withRequest, days.flatMap { d -> d.exercises.map { it.exercise } })
            days.forEach { d -> assertEquals(d.exercises.size, d.exercises.map { it.exercise.id }.toSet().size, "[${s.name}] 하루 안 중복") }
        }
    }

    @Test
    @DisplayName("6. AI 채팅 추천: AI 가 무릎·허리·어깨 운동을 골라도 응답에 막힌 운동이 없다")
    fun aiChat() {
        val picks = listOf("barbell-full-squat", "barbell-bench-press", "barbell-deadlift", "seated-shoulder-press-barbell", "dumbbell-curls", "barbell-lunge")
        scenarios.filter { it.stored.isNotEmpty() }.forEach { s ->
            val h = ChatHarness(profileInjuries = s.profile, settingsInjuries = s.settings, picks = picks)
            val out = h.chat.getAIWorkoutRecommendation(1L, 60).recommendation.exercises.map { RecoTestKit.byId.getValue(it.exerciseId.toLong()) }
            assertClean("chat", s, s.stored, out)
            assertEquals(s.stored, h.chat.resolveUserInjuries(h.settings, h.profile), s.name)
        }
    }

    @Test
    @DisplayName("7. V2 퀵 추천: 프로필에만 있는 부상도 거른다 (설정이 비어 있지 않아도 프로필을 버리지 않는다)")
    fun quick() {
        scenarios.filter { it.stored.isNotEmpty() }.forEach { s ->
            val h = ChatHarness(profileInjuries = s.profile, settingsInjuries = s.settings)
            val out = h.quick.getBasicWorkoutRecommendation(1L, 60).recommendation.exercises.map { RecoTestKit.byId.getValue(it.exerciseId.toLong()) }
            assertClean("quick", s, s.stored, out)
        }
    }

    @Test
    @DisplayName("8. 프로그램 생성: 큐레이션 표가 비어 있어도(현재 0행) 저장된 부상이 막는 운동이 나오지 않는다")
    fun programGenerator() {
        val program = listOf("barbell-full-squat", "barbell-bench-press", "barbell-deadlift", "seated-shoulder-press-barbell", "dumbbell-curls", "barbell-lunge")
        scenarios.filter { it.stored.isNotEmpty() }.forEach { s ->
            val f = RecoTestKit.ProgramFixture(repos(s), program)
            val out = f.service.generateTodayWorkout(f.user).exercises.map { RecoTestKit.byId.getValue(it.exerciseId) }
            assertClean("program", s, s.stored, out)
            assertEquals(out.size, out.map { it.id }.toSet().size, "[${s.name}] 하루 안 중복")
        }
    }
}

package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.JsonNode
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 일본어 사용자는 어떤 플랜·준비도·추천 응답 필드에서도 한글을 받지 않는다.
 * 응답 DTO 를 JSON 트리로 만들어 모든 문자열 잎을 정규식으로 검사한다 — 필드를 새로 추가해도 자동으로 검사 대상이 된다.
 *
 * 탐색(2026-10): 준비도(readiness) 요인이 한국어 고정, 프로그램 운동 이름이 영어 그대로, AI 플랜의 coachingNotes 가 "Korean" 고정이었다.
 */
class JaResponseLocalizationTest {
    private val hangul = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")
    private val ja = "ja"

    private fun strings(node: JsonNode, path: String = "$"): List<Pair<String, String>> = when {
        node.isTextual -> listOf(path to node.asText())
        node.isArray -> node.flatMapIndexed { i, n -> strings(n, "$path[$i]") }
        node.isObject -> node.fields().asSequence().toList().flatMap { (k, v) -> strings(v, "$path.$k") }
        else -> emptyList()
    }

    private fun assertNoHangul(what: String, value: Any) {
        val bad = strings(RecoTestKit.mapper.valueToTree(value)).filter { hangul.containsMatchIn(it.second) }
        assertTrue(bad.isEmpty(), "[$what] 일본어 응답에 한글: ${bad.take(8)}")
    }

    @Test
    @DisplayName("하루 운동(getDayWorkout): 준비도 요인·메모·대체 운동·근육 이름에 한글이 없다 — 컨디션이 나쁜 날·공백이 긴 날 포함")
    fun dayWorkout() {
        val user = RecoTestKit.user()
        val repos = RecoTestKit.Repos(RecoTestKit.profile(user, ExperienceLevel.INTERMEDIATE, 33), RecoTestKit.settings(user, "ja", 60))
        val f = RecoTestKit.PlanFixture(repos, listOf("barbell-full-squat", "barbell-bench-press", "cable-pulldown", "dumbbell-curls", "plank-on-elbows")
            .map { Triple(RecoTestKit.bySlug.getValue(it), 3, 8..12) })
        listOf(null, 1, 2, 3, 4, 5).forEach { readiness ->
            val r = f.service.getDayWorkout(1L, 1, readiness, ja)
            assertNoHangul("day workout readiness=$readiness", r)
            assertTrue(r.readinessScore!!.factors.isNotEmpty())
            if (readiness != null) assertTrue(r.readinessScore!!.factors.any { it.contains("コンディション") }, "일본어 문구여야 한다: ${r.readinessScore!!.factors}")
        }
        // 마지막 운동 후 공백이 긴 날의 문구(일수 인자 포함)
        val gap = org.mockito.Mockito.mock(com.richjun.liftupai.domain.workout.entity.WorkoutSession::class.java)
        org.mockito.Mockito.`when`(gap.startTime).thenReturn(java.time.LocalDateTime.now().minusDays(9))
        every { f.sessionRepo.findTop10ByUserAndStatusInOrderByStartTimeDesc(any(), any()) } returns listOf(gap)
        val r = f.service.getDayWorkout(1L, 1, null, ja)
        assertNoHangul("day workout after gap", r)
        assertTrue(r.readinessScore!!.factors.any { it.contains("9") }, "공백 일수가 문구에 들어간다: ${r.readinessScore!!.factors}")
    }

    @Test
    @DisplayName("템플릿 적용 응답: 모든 프리셋의 플랜 이름·설명·요일 이름이 일본어")
    fun templatePlans() {
        PresetTemplates.ALL.forEach { t ->
            assertTrue(WorkoutLocalization.maybeMessage("template.${t.code}.name", ja) != null, "${t.code} 일본어 이름 번역이 없다")
            assertNoHangul("template ${t.code} name", WorkoutLocalization.message("template.${t.code}.name", ja))
            assertNoHangul("template ${t.code} description", WorkoutLocalization.maybeMessage("template.${t.code}.description", ja).orEmpty())
            t.days.forEach { d -> assertNoHangul("template ${t.code} day ${d.dayNumber}", WorkoutLocalization.maybeMessage("template.${t.code}.day.${d.dayNumber}.name", ja).orEmpty()) }
        }
        val f = ApplyFixture(RecoTestKit.Repos(RecoTestKit.profile(), RecoTestKit.settings(language = "ja")), "beginner_fullbody")
        val dashboard = f.service.applyTemplate(f.user, "beginner_fullbody", null, ja)
        assertNoHangul("apply dashboard", dashboard)
    }

    @Test
    @DisplayName("템플릿 추천 이유: 다양한 프로필(부상·집중 부위·홈트)에서 일본어 문장")
    fun templateReason() {
        val exercises = RecoFitEvalTest.loadExercises()
        val cases = listOf(
            setOf("knee") to listOf("glutes", "weight_loss"),
            emptySet<String>() to listOf("big_arms", "thick_chest"),
            setOf("lower_back", "shoulder") to listOf("general_fitness"),
            emptySet<String>() to listOf("strength"),
        )
        cases.forEachIndexed { i, (inj, goals) ->
            val engine = RecoFitEvalTest.buildEngine(exercises, inj)
            val profile = TemplateRecommendationEngine.RecoProfile(
                "beginner", goals, 3, 45, if (i == 0) setOf("DUMBBELL", "BODYWEIGHT") else setOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"),
                inj, FocusArea.resolve(goals), 30,
            )
            assertNoHangul("recommend reason #$i", TemplateRecoReason.compose(engine.recommend(profile), profile, ja))
        }
    }

    @Test
    @DisplayName("채팅 추천: 정상 응답과 AI 실패 폴백 모두 이름·난이도·장비·근육·팁이 일본어(한글 없음)")
    fun chatRecommendation() {
        val h = ChatHarness(language = ja)
        assertNoHangul("chat recommendation", h.chat.getAIWorkoutRecommendation(1L, 45, localeOverride = ja))
        // AI 응답이 깨졌을 때의 폴백 경로
        val broken = ChatHarness(language = ja)
        val gemini = broken.geminiMock
        every { gemini.generateRecommendations(any()) } returns "not json"
        assertNoHangul("chat fallback", broken.chat.getAIWorkoutRecommendation(1L, 45, localeOverride = ja))
    }

    @Test
    @DisplayName("퀵 추천(V2): 이름·난이도·장비·근육·대체 운동 이름이 일본어(한글 없음)")
    fun quickRecommendation() {
        val h = ChatHarness(language = ja)
        assertNoHangul("quick recommendation", h.quick.getBasicWorkoutRecommendation(1L, 45, localeOverride = ja))
    }

    @Test
    @DisplayName("PT 엔진 처방 설명: 이유·코칭 팁·포커스가 일본어")
    fun ptEngineTexts() {
        val h = ChatHarness(language = ja)
        listOf("barbell-full-squat", "dumbbell-curls", "plank-on-elbows", "cable-pulldown").forEach { slug ->
            assertNoHangul("pt $slug", h.pt.calculateFullPTRecommendation(h.user, RecoTestKit.bySlug.getValue(slug)))
        }
    }

    @Test
    @DisplayName("프로그램 오늘 운동: 프로그램·요일 이름, 준비도 요인, 대체 운동이 일본어")
    fun programWorkout() {
        val repos = RecoTestKit.Repos(RecoTestKit.profile(level = ExperienceLevel.BEGINNER), RecoTestKit.settings(language = ja, durationMinutes = 45))
        val f = RecoTestKit.ProgramFixture(repos, listOf("barbell-full-squat", "barbell-bench-press", "cable-pulldown", "dumbbell-curls"))
        val w = f.service.generateTodayWorkout(f.user, 2)
        assertNoHangul("program workout", w)
        assertTrue(w.readinessScore!!.factors.isNotEmpty())
    }

    @Test
    @DisplayName("운동 제안 이유(6종)와 AI 플랜 진행 문구(8종)가 일본어")
    fun reasonsAndProgress() {
        ExerciseSuggestionRanker.Why.values().forEach { why ->
            val svc = ExerciseSuggestionService(mockk(), mockk(), mockk(), mockk(), mockk())
            val s = ExerciseSuggestionRanker.Suggestion(RecoTestKit.bySlug.getValue("dumbbell-curls"), 1.0, why, listOf("BICEPS", "GLUTES"))
            assertNoHangul("suggest reason $why", svc.reasonText(s, ja))
        }
        listOf("analyzing", "designing", "reading", "verifying", "reviewing", "redesigning", "saving", "fallback").forEach {
            val text = WorkoutLocalization.message("plan.progress.$it", ja)
            assertNoHangul("progress $it", text)
            assertTrue(text != "plan.progress.$it", "$it 번역 누락")
        }
    }

    @Test
    @DisplayName("AI 플랜 프롬프트: coachingNotes·planName 이 요청 언어(일본어)로 요구된다 — 예전엔 coachingNotes 가 Korean 고정")
    fun aiPlanPromptLanguage() {
        val repos = RecoTestKit.Repos(RecoTestKit.profile(), RecoTestKit.settings(language = ja))
        val svc = AIPlanGenerationService(
            geminiAIService = mockk(), curatedExercisePoolService = CuratedExercisePoolService(RecoTestKit.exerciseRepository()),
            hallucinationGuardService = mockk(), planQualityValidator = mockk(), userPlanService = mockk(),
            exerciseRepository = RecoTestKit.exerciseRepository(), templateRepository = mockk(relaxed = true), templateDayRepository = mockk(relaxed = true),
            templateDayExerciseRepository = mockk(relaxed = true), userWorkoutPlanRepository = mockk(relaxed = true), userPlanDayRepository = mockk(relaxed = true),
            userPlanDayExerciseRepository = mockk(relaxed = true), injuryFilterService = RecoTestKit.injuryFilter(), userProfileRepository = repos.userProfileRepository,
            userSettingsRepository = repos.userSettingsRepository, objectMapper = RecoTestKit.mapper, self = mockk(), trainingContext = repos.resolver,
        )
        val request = GenerateAIPlanRequest(
            experienceLevel = "beginner", goals = listOf("WEIGHT_LOSS"), gender = "female", age = 30, weeklyDays = 3, sessionDuration = 45,
            equipment = listOf("DUMBBELL"), locale = ja,
        )
        val prompt = svc.buildPlanGenerationPrompt(request, "pool", svc.targetsOf(request))
        assertTrue(prompt.contains("\"coachingNotes\": \"string (in Japanese, overall advice)\""), prompt.substringAfter("coachingNotes").take(80))
        assertTrue(!prompt.contains("Korean"), "프롬프트가 한국어를 요구한다")
    }
}

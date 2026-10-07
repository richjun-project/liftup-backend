package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.dto.request.CreateCustomPlanRequest
import com.richjun.liftupai.domain.workout.dto.request.CustomPlanDayRequest
import com.richjun.liftupai.domain.workout.dto.request.CustomPlanExerciseRequest
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.util.ExerciseSuitability
import com.richjun.liftupai.domain.workout.util.SessionDurationFitter
import com.richjun.liftupai.global.exception.BadRequestException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 경로별 안전·처방 회귀: AI 플랜 저장(속성 테스트), 프로그램 4주, 수동 대체, 인라인 대체, 대시보드 분(分), 맞춤 플랜.
 */
class RecoPathSafetyTest {
    private val catalog = RecoTestKit.catalog
    private val allInjuries = listOf("knee", "shoulder", "lower_back", "elbow", "wrist", "hip")

    private fun aiService(repos: RecoTestKit.Repos) = AIPlanGenerationService(
        geminiAIService = mockk(), curatedExercisePoolService = CuratedExercisePoolService(RecoTestKit.exerciseRepository()),
        hallucinationGuardService = HallucinationGuardService(RecoTestKit.exerciseRepository(), ExercisePatternClassifier()),
        planQualityValidator = mockk(), userPlanService = mockk(), exerciseRepository = RecoTestKit.exerciseRepository(),
        templateRepository = mockk(relaxed = true), templateDayRepository = mockk(relaxed = true), templateDayExerciseRepository = mockk(relaxed = true),
        userWorkoutPlanRepository = mockk(relaxed = true), userPlanDayRepository = mockk(relaxed = true), userPlanDayExerciseRepository = mockk(relaxed = true),
        injuryFilterService = RecoTestKit.injuryFilter(), userProfileRepository = repos.userProfileRepository, userSettingsRepository = repos.userSettingsRepository,
        objectMapper = RecoTestKit.mapper, self = mockk(), trainingContext = repos.resolver,
    )

    // ── F08: AI 플랜 저장 ────────────────────────────────────────────

    @Test
    @DisplayName("AI 응답 200개(제한 운동·없는 id·같은 날 중복 포함): 확정 플랜에 같은 날 중복 0, 장비 위반 0, 부상 제한 0, 수준 위반 0")
    fun aiReplyProperty() {
        val rnd = Random(20261007)
        val levels = listOf(ExperienceLevel.BEGINNER, ExperienceLevel.NOVICE, ExperienceLevel.INTERMEDIATE, ExperienceLevel.ADVANCED)
        val equipmentSets = listOf(
            listOf("DUMBBELL", "BODYWEIGHT"), listOf("BODYWEIGHT"), listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"),
            listOf("MACHINE", "CABLE"), listOf("DUMBBELL", "RESISTANCE_BAND", "BODYWEIGHT"), listOf("DUMBBELL", "OTHER", "BODYWEIGHT"),
        )
        val problems = mutableListOf<String>()
        repeat(200) { n ->
            val injuries = allInjuries.shuffled(rnd).take(rnd.nextInt(0, 3))
            val equipment = equipmentSets[rnd.nextInt(equipmentSets.size)]
            val level = levels[rnd.nextInt(levels.size)]
            val age = listOf(15, 24, 33, 47, 58, 70)[rnd.nextInt(6)]
            val repos = RecoTestKit.Repos(
                RecoTestKit.profile(level = level, age = age, injuries = injuries.take(1), equipment = equipment),
                RecoTestKit.settings(injuries = injuries.drop(1), equipment = equipment),
            )
            val minutes = listOf(30, 45, 60, 90)[rnd.nextInt(4)]
            val request = GenerateAIPlanRequest(
                experienceLevel = level.name.lowercase(), goals = listOf("MUSCLE_GAIN"), gender = "female", age = age, weeklyDays = 3, sessionDuration = minutes,
                equipment = equipment, locale = "ja",
            )
            val days = (1..3).map { d ->
                val picks = (1..rnd.nextInt(4, 9)).map {
                    if (rnd.nextInt(10) == 0) 900_000L + it else catalog[rnd.nextInt(catalog.size)].id   // 10% 환각 id, 나머지는 카탈로그 아무 운동
                }.let { ids -> if (rnd.nextBoolean() && ids.isNotEmpty()) ids + ids.first() else ids }    // 같은 날 중복 유도
                AIPlanDayEntry(d, "Day $d", listOf("FULL_BODY", "UPPER", "LOWER")[rnd.nextInt(3)], minutes, picks.mapIndexed { i, id ->
                    AIPlanExerciseEntry(id, "x", rnd.nextInt(2, 6), 6, 12, 60 + rnd.nextInt(0, 4) * 30, i + 1, rnd.nextBoolean(), 8.0)
                })
            }
            val resolved = aiService(repos).resolveDays(repos.profile!!.user, AIPlanResponse("p", "d", "FULL_BODY", 3, 8, days), request)
            val normalized = RecoRules.normalizeEquipment(equipment)
            val inj = RecoRules.normalizeInjuries(injuries)
            resolved.forEach { fd ->
                val ex = fd.exercises.map { it.exercise }
                if (ex.map { it.id }.toSet().size != ex.size) problems += "#$n day ${fd.entry.dayNumber}: 같은 날 중복"
                ex.forEach { e ->
                    if (!TemplatePlanAdapter.equipmentAllowed(e, normalized)) problems += "#$n ${e.slug}: 장비 위반 $normalized"
                    if (inj.isNotEmpty() && InjuryFilterService.ruleRestricts(e, inj)) problems += "#$n ${e.slug}: 부상 제한 $inj"
                    val strict = if (level == ExperienceLevel.NOVICE) ExperienceLevel.BEGINNER else level
                    if (ExerciseSuitability.tooAdvanced(e, strict, age)) problems += "#$n ${e.slug}: 수준 위반 $level/$age"
                    if (ExerciseSuitability.needsBar(e) && !ExerciseSuitability.hasBarAccess(normalized)) problems += "#$n ${e.slug}: 철봉 없음"
                }
                if (ex.size < 3) problems += "#$n day ${fd.entry.dayNumber}: 운동 ${ex.size}개"
            }
        }
        assertTrue(problems.isEmpty(), "위반 ${problems.size}건:\n" + problems.take(20).joinToString("\n"))
    }

    @Test
    @DisplayName("AI 플랜 저장: 두 개의 제한 운동이 같은 대체로 가 하루에 두 번 들어가지 않는다 (예전엔 id 별 '가장 인기 있는 운동' 으로 바뀌었다)")
    fun twoRestrictedDoNotCollapseToSameAlternative() {
        val repos = RecoTestKit.Repos(RecoTestKit.profile(injuries = listOf("knee")), RecoTestKit.settings())
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val lunge = RecoTestKit.bySlug.getValue("barbell-lunge")
        val bench = RecoTestKit.bySlug.getValue("barbell-bench-press")
        val plan = AIPlanResponse("p", "d", "FULL_BODY", 1, 8, listOf(AIPlanDayEntry(1, "D1", "LOWER", 60, listOf(squat, lunge, bench).mapIndexed { i, e ->
            AIPlanExerciseEntry(e.id, e.name, 3, 8, 12, 90, i + 1, true, 7.0)
        })))
        val request = GenerateAIPlanRequest("beginner", listOf("STRENGTH"), "male", 30, weeklyDays = 1, sessionDuration = 45, equipment = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"))
        val day = aiService(repos).resolveDays(repos.profile!!.user, plan, request).single()
        val ids = day.exercises.map { it.exercise.id }
        assertEquals(ids.size, ids.toSet().size, "중복 대체: ${day.exercises.map { it.exercise.slug }}")
        assertTrue(day.exercises.none { InjuryFilterService.ruleRestricts(it.exercise, setOf("knee")) })
    }

    // ── F06: 프로그램 4주 ────────────────────────────────────────────

    @Test
    @DisplayName("무릎 부상 + 덤벨만 + 초보가 프로그램에 등록: 4주 연속 막힌·장비 없는·수준에 안 맞는 운동 0")
    fun programFourWeeks() {
        val program = listOf("barbell-full-squat", "barbell-lunge", "leg-press-machine-normal-stance", "barbell-bench-press", "barbell-bent-over-row-pronated-grip",
            "pull-up-normal-grip", "dumbbell-curls", "barbell-deadlift")
        val equipment = listOf("DUMBBELL", "BODYWEIGHT")
        (1..4).forEach { week ->
            val repos = RecoTestKit.Repos(
                RecoTestKit.profile(level = ExperienceLevel.BEGINNER, injuries = listOf("knee"), equipment = equipment),
                RecoTestKit.settings(equipment = equipment, durationMinutes = 45),
            )
            val f = RecoTestKit.ProgramFixture(repos, program, week = week)
            val w = f.service.generateTodayWorkout(f.user)
            val ex = w.exercises.map { RecoTestKit.byId.getValue(it.exerciseId) }
            assertTrue(ex.size >= 4, "week $week: 운동 ${ex.size}개")
            ex.forEach { e ->
                assertFalse(InjuryFilterService.ruleRestricts(e, setOf("knee")), "week $week: 무릎 제한 운동 ${e.slug}")
                assertTrue(TemplatePlanAdapter.equipmentAllowed(e, RecoRules.normalizeEquipment(equipment)), "week $week: 장비 없는 운동 ${e.slug}")
                assertTrue(RecoRules.suitable(e, RecoRules.normalizeEquipment(equipment), ExperienceLevel.BEGINNER, 30), "week $week: 수준에 안 맞는 운동 ${e.slug}")
            }
            assertEquals(ex.size, ex.map { it.id }.toSet().size, "week $week: 같은 날 중복")
            w.exercises.forEach { g ->
                val e = RecoTestKit.byId.getValue(g.exerciseId)
                if (RecoRules.isPrimaryCompound(e)) assertTrue(g.sets <= 3 && g.minReps >= 8, "week $week: 초보 주력 복합 ${e.slug} ${g.sets}x${g.minReps}")
            }
        }
    }

    // ── F11: 수동 대체 ───────────────────────────────────────────────

    private fun planWith(level: ExperienceLevel, equipment: List<String>, injuries: List<String>, vararg slugs: String, localization: ExerciseCatalogLocalizationService? = null): RecoTestKit.PlanFixture {
        val repos = RecoTestKit.Repos(
            RecoTestKit.profile(level = level, age = 30, injuries = injuries, equipment = equipment),
            RecoTestKit.settings(equipment = equipment),
        )
        return RecoTestKit.PlanFixture(repos, slugs.map { Triple(RecoTestKit.bySlug.getValue(it), 4, 6..8) }, restSeconds = 180, localization = localization)
    }

    private val gym = RecoTestKit.GYM

    @Test
    @DisplayName("수동 대체: 스쿼트(4x6-8/180초)를 레그 컬로 바꾸면 새 운동 기준의 처방(고립 반복·짧은 휴식)과 복합 여부가 다시 정해진다")
    fun substitutionRederivesPrescription() {
        val f = planWith(ExperienceLevel.INTERMEDIATE, gym, emptyList(), "barbell-full-squat", "barbell-bench-press", "cable-pulldown")
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val ext = RecoTestKit.bySlug.getValue("lying-leg-curl-machine")
        val row = f.planRows.first { it.exercise.id == squat.id }
        assertEquals(1, f.service.substituteExerciseInActivePlan(1L, squat.id, ext.id))
        assertEquals(ext.id, row.exercise.id)
        assertFalse(row.isCompound, "고립 운동으로 바뀌면 복합 표시도 바뀐다")
        assertTrue(row.minReps >= 8 && row.restSeconds <= 90, "고립 처방이어야 한다: ${row.sets}x${row.minReps}-${row.maxReps}/${row.restSeconds}s")
        assertTrue(row.notes == null)
    }

    @Test
    @DisplayName("수동 대체는 부상 제한·없는 장비·수준에 안 맞는 운동을 400(BadRequest)으로 거절한다")
    fun substitutionRejectsUnsafe() {
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val knee = planWith(ExperienceLevel.INTERMEDIATE, gym, listOf("knee"), "barbell-bench-press", "barbell-full-squat", "cable-pulldown")
        val legExt = RecoTestKit.bySlug.getValue("lying-leg-curl-machine")
        assertThrows(BadRequestException::class.java) { knee.service.substituteExerciseInActivePlan(1L, squat.id, legExt.id) }

        val homeDb = planWith(ExperienceLevel.INTERMEDIATE, listOf("DUMBBELL", "BODYWEIGHT"), emptyList(), "dumbbell-goblet-squat", "dumbbell-bench-press", "dumbbell-curls")
        val barbellRow = RecoTestKit.bySlug.getValue("barbell-bent-over-row-pronated-grip")
        val goblet = RecoTestKit.bySlug.getValue("dumbbell-goblet-squat")
        assertThrows(BadRequestException::class.java) { homeDb.service.substituteExerciseInActivePlan(1L, goblet.id, barbellRow.id) }

        val beginnerNoBar = planWith(ExperienceLevel.BEGINNER, listOf("DUMBBELL", "BODYWEIGHT"), emptyList(), "dumbbell-goblet-squat", "dumbbell-bench-press", "dumbbell-curls")
        val pullUp = RecoTestKit.bySlug.getValue("pull-up-normal-grip")
        assertThrows(BadRequestException::class.java) { beginnerNoBar.service.substituteExerciseInActivePlan(1L, goblet.id, pullUp.id) }
    }

    @Test
    @DisplayName("대체 운동이 이미 모든 날에 있으면 '플랜에 없는 운동' 이 아니라 구체적인 오류를 낸다")
    fun duplicateOnlySkipHasSpecificError() {
        val f = planWith(ExperienceLevel.INTERMEDIATE, gym, emptyList(), "barbell-full-squat", "leg-press-machine-normal-stance")
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val press = RecoTestKit.bySlug.getValue("leg-press-machine-normal-stance")
        val e = assertThrows(BadRequestException::class.java) { f.service.substituteExerciseInActivePlan(1L, squat.id, press.id) }
        assertTrue(e.message!!.contains("already"), e.message)
        assertFalse(e.message!!.contains("not part of the active plan"))
    }

    // ── F11/F13: 하루 운동의 인라인 대체 ──────────────────────────────

    @Test
    @DisplayName("하루 운동의 대체 후보는 대체 후보 API(C3)의 상위 3개와 같고, 장비·부상·수준 규칙을 지키며 사용자 언어 이름이다")
    fun inlineSubstitutesMatchRankerAndAreLocalized() {
        val translationRepo = mockk<com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository>(relaxed = true)
        every { translationRepo.findByExerciseIdInAndLocale(any(), "ja") } answers {
            firstArg<Collection<Long>>().map { id -> ExerciseTranslation(id = id, exercise = RecoTestKit.byId.getValue(id), locale = "ja", displayName = "日本語$id") }
        }
        val f = planWith(ExperienceLevel.BEGINNER, listOf("DUMBBELL", "BODYWEIGHT"), listOf("knee"), "dumbbell-bench-press", "dumbbell-curls",
            localization = ExerciseCatalogLocalizationService(translationRepo))
        val ctx = f.repos.resolver.resolve(1L)
        val day = f.service.getDayWorkout(1L, 1, null, "ja")
        assertTrue(day.exercises.all { it.substitutes.isNotEmpty() }, "대체 후보가 비었다")
        day.exercises.forEach { d ->
            val original = RecoTestKit.byId.getValue(d.exerciseId)
            val expected = SubstituteRanker.rankForUser(original, catalog, emptySet(), ctx, RecoTestKit.injuryFilter().restrictionChecker(ctx.injuries), 3).map { it.exercise.id }
            assertEquals(expected, d.substitutes.map { it.exerciseId }, "${original.slug}: C3 와 다른 후보")
            d.substitutes.forEach { s ->
                val alt = RecoTestKit.byId.getValue(s.exerciseId)
                assertTrue(TemplatePlanAdapter.equipmentAllowed(alt, ctx.equipment), "장비 없는 대체 ${alt.slug}")
                assertFalse(InjuryFilterService.ruleRestricts(alt, ctx.injuries), "부상 제한 대체 ${alt.slug}")
                assertTrue(s.exerciseName.startsWith("日本語"), "현지화되지 않은 이름: ${s.exerciseName}")
            }
        }
    }

    // ── F02: 대시보드 분 / F14: 맞춤 플랜 ─────────────────────────────

    @Test
    @DisplayName("대시보드 하루 분(分)이 getDayWorkout 의 분과 ±10% 안이다 — 템플릿에 적힌 분이 아니라 개인화된 분")
    fun dashboardMinutesMatchDayWorkout() {
        val repos = RecoTestKit.Repos(RecoTestKit.profile(level = ExperienceLevel.INTERMEDIATE), RecoTestKit.settings(durationMinutes = 60))
        val f = RecoTestKit.PlanFixture(repos, listOf("barbell-full-squat", "barbell-bench-press", "cable-pulldown", "dumbbell-curls", "plank-on-elbows")
            .map { Triple(RecoTestKit.bySlug.getValue(it), 3, 8..12) })
        val dash = f.service.getPlanDashboard(1L, "ja").days.single().estimatedDuration
        val day = f.service.getDayWorkout(1L, 1, null, "ja").estimatedDuration
        assertTrue(kotlin.math.abs(dash - day) <= maxOf(1, (day * 0.1).toInt()), "대시보드 ${dash}분 vs 하루 운동 ${day}분")
        assertTrue(day in 51..66, "60분 저장 → 하루 운동 ${day}분")
        assertTrue(dash != 60 || day == 60, "대시보드가 템플릿의 60 을 그대로 내는지 확인용")
    }

    @Test
    @DisplayName("맞춤 플랜: 바벨 스쿼트가 복합으로 저장돼 웜업 세트가 나오고, 하루 분은 세트·휴식으로 계산한 추정(종목 수 × 8 이 아님)")
    fun customPlan() {
        val f = ApplyFixture(RecoTestKit.Repos(RecoTestKit.profile(level = ExperienceLevel.INTERMEDIATE), RecoTestKit.settings()), "beginner_fullbody")
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val curl = RecoTestKit.bySlug.getValue("dumbbell-curls")
        f.service.createCustomPlan(f.user, CreateCustomPlanRequest("mine", listOf(
            CustomPlanDayRequest("A", "FULL_BODY", listOf(
                CustomPlanExerciseRequest(squat.id, 5, 5, 5, 180), CustomPlanExerciseRequest(curl.id, 3, 10, 12, 60),
            ))
        )))
        val squatRow = f.saved.first { it.exercise.id == squat.id }
        val curlRow = f.saved.first { it.exercise.id == curl.id }
        assertTrue(squatRow.isCompound, "스쿼트는 복합이다(예전엔 패턴 이름에 compound 가 있어야만 true)")
        assertFalse(curlRow.isCompound)
        val expected = SessionDurationFitter.estimateMinutes(listOf(
            SessionDurationFitter.Slot(5, "5-5", 180, 180), SessionDurationFitter.Slot(3, "10-12", 60, 60),
        ))
        assertTrue(kotlin.math.abs(f.savedDays.single().estimatedDurationMinutes - expected) <= 1, "하루 ${f.savedDays.single().estimatedDurationMinutes}분 vs 계산 ${expected}분")
        assertTrue(f.savedDays.single().estimatedDurationMinutes != 2 * 8, "종목 수 × 8 어림이 아니다")
    }
}

package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.util.ExerciseRequirements
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import java.io.File

/**
 * 복잡한 일의 모델 실측(2026-11-21 complex-model-r4) — AI 플랜 생성·AI 운동 추천. 평소엔 건너뛴다.
 *  - capture: COMPLEX_EVAL_OUT=<dir> — 사용자 유형 8명마다 실제 서비스 경로의 프롬프트를 잡는다(Gemini 호출 자리에 가로채기를 끼운다).
 *  - grade:   COMPLEX_EVAL_RESULTS=<results.jsonl> COMPLEX_EVAL_GRADES=<out.jsonl> — 운영 서버에서 받은 답을
 *             서버의 실제 파서·환각 보정·플랜 품질 검사기(runPipeline), 추천은 후보 목록·장비·부상 규칙으로 매긴다.
 */
class ComplexModelEvalTest : ApiTestSupport() {
    @Autowired private lateinit var planService: AIPlanGenerationService
    @Autowired private lateinit var analysisService: AIAnalysisService
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var injuryFilterService: InjuryFilterService

    private val mapper = ObjectMapper()

    data class Persona(
        val id: String, val locale: String, val level: ExperienceLevel, val goals: List<String>, val gender: String, val age: Int,
        val weight: Double, val days: Int, val minutes: Int, val equipment: List<String>, val injuries: List<String>, val focus: List<String> = emptyList(),
    )

    private val gym = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "OTHER", "BODYWEIGHT")
    private fun personas() = listOf(
        Persona("ko_beginner_f_lose", "ko", ExperienceLevel.BEGINNER, listOf("weight_loss", "general_fitness"), "female", 28, 62.0, 3, 45, gym, emptyList()),
        Persona("ja_expert_bodyweight", "ja", ExperienceLevel.EXPERT, listOf("strength", "bulk_up"), "male", 39, 70.0, 4, 30, listOf("BODYWEIGHT"), emptyList()),
        Persona("ja_advanced_5day_back", "ja", ExperienceLevel.ADVANCED, listOf("bulk_up"), "male", 31, 76.0, 5, 90, gym, emptyList(), listOf("back")),
        Persona("en_inter_f_glutes_knee", "en", ExperienceLevel.INTERMEDIATE, listOf("glutes", "thick_legs"), "female", 34, 64.0, 4, 60, listOf("DUMBBELL", "RESISTANCE_BAND", "BODYWEIGHT"), listOf("knee"), listOf("glutes")),
        Persona("ko_inter_m_lowback", "ko", ExperienceLevel.INTERMEDIATE, listOf("strength", "general_fitness"), "male", 45, 82.0, 3, 60, gym, listOf("lower_back")),
        Persona("en_novice_68", "en", ExperienceLevel.NOVICE, listOf("general_fitness", "posture"), "male", 68, 74.0, 2, 30, listOf("MACHINE", "BODYWEIGHT"), emptyList()),
        Persona("ko_beginner_16_bulk", "ko", ExperienceLevel.BEGINNER, listOf("bulk_up", "big_arms"), "male", 16, 58.0, 3, 45, listOf("DUMBBELL", "BODYWEIGHT"), emptyList(), listOf("arms")),
        Persona("ja_inter_f_home_shoulder", "ja", ExperienceLevel.INTERMEDIATE, listOf("toned_arms", "slim_legs"), "female", 26, 52.0, 5, 45, listOf("DUMBBELL", "BODYWEIGHT"), listOf("shoulder")),
    )

    private fun request(p: Persona) = GenerateAIPlanRequest(
        experienceLevel = p.level.name.lowercase(), goals = p.goals, gender = p.gender, age = p.age, weight = p.weight,
        weeklyDays = p.days, sessionDuration = p.minutes, equipment = p.equipment, injuries = p.injuries, focusAreas = p.focus, locale = p.locale,
    )

    private fun setUpUser(p: Persona): User {
        val user = newUser("cx-${p.id}".take(20))
        loginAs(user)
        profileRepository.save(UserProfile(user = user).apply {
            experienceLevel = p.level; age = p.age; gender = p.gender
            availableEquipment = p.equipment.toMutableSet(); injuries = p.injuries.toMutableSet()
            weeklyWorkoutDays = p.days; workoutDuration = p.minutes
        })
        settingsRepository.save(UserSettings(user = user).apply {
            language = p.locale; timeZone = mapOf("ko" to "Asia/Seoul", "ja" to "Asia/Tokyo").getOrDefault(p.locale, "America/Los_Angeles")
            availableEquipment = p.equipment.toMutableSet(); injuries = p.injuries.toMutableSet(); weeklyWorkoutDays = p.days; workoutDuration = p.minutes
        })
        return user
    }

    private fun allowErrors() {
        allowedErrorLoggers += listOf(AIPlanGenerationService::class.java.name, AIAnalysisService::class.java.name, GeminiAIService::class.java.name)
    }

    @Test
    fun capture() {
        val out = System.getenv("COMPLEX_EVAL_OUT")?.takeIf { it.isNotBlank() }
        assumeTrue(out != null, "COMPLEX_EVAL_OUT 이 없으면 건너뛴다")
        allowErrors()
        val dir = File(out!!).apply { mkdirs() }
        val planTarget = AopTestUtils.getUltimateTargetObject<AIPlanGenerationService>(planService)
        val recoTarget = AopTestUtils.getUltimateTargetObject<AIAnalysisService>(analysisService)
        val originalPlanGemini = ReflectionTestUtils.getField(planTarget, "geminiAIService")
        val originalRecoGemini = ReflectionTestUtils.getField(recoTarget, "geminiAIService")
        try {
            personas().forEach { p ->
                var planPrompt: String? = null
                var recoPrompt: String? = null
                val spy = mockk<GeminiAIService>(relaxed = true)
                every { spy.generatePlanContent(any(), any()) } answers { if (planPrompt == null) planPrompt = firstArg(); throw IllegalStateException("captured") }
                every { spy.generatePlanContentStreaming(any(), any(), any()) } answers { if (planPrompt == null) planPrompt = firstArg(); throw IllegalStateException("captured") }
                every { spy.generateRecommendations(any(), any()) } answers { if (recoPrompt == null) recoPrompt = firstArg(); "{}" }
                ReflectionTestUtils.setField(planTarget, "geminiAIService", spy)
                ReflectionTestUtils.setField(recoTarget, "geminiAIService", spy)
                val user = setUpUser(p)
                runCatching { planService.generateAndApplyPlan(user, request(p)) } // 가로채기가 던지면 템플릿 플랜으로 떨어진다(추천에 쓸 활성 플랜)
                runCatching { analysisService.getAIWorkoutRecommendation(user.id, localeOverride = p.locale) }
                assertTrue(planPrompt != null, "${p.id}: 플랜 프롬프트를 못 잡았다")
                assertTrue(recoPrompt != null, "${p.id}: 추천 프롬프트를 못 잡았다")
                for ((kind, prompt, temp, maxOut) in listOf(
                    Quad("plan", planPrompt!!, 0.4, 16384),
                    Quad("reco", recoPrompt!!, 0.7, 8192),
                )) {
                    val dump = mapOf(
                        "id" to "${kind}__${p.id}", "kind" to kind, "persona" to p, "prompt" to prompt,
                        "generationConfig" to mapOf("temperature" to temp, "maxOutputTokens" to maxOut, "topP" to 0.95, "topK" to 40, "thinkingConfig" to mapOf("thinkingLevel" to "low")),
                    )
                    File(dir, "${kind}__${p.id}.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump))
                }
            }
        } finally {
            ReflectionTestUtils.setField(planTarget, "geminiAIService", originalPlanGemini)
            ReflectionTestUtils.setField(recoTarget, "geminiAIService", originalRecoGemini)
        }
        assertEquals(16, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
    }

    private data class Quad(val kind: String, val prompt: String, val temp: Double, val maxOut: Int)

    // ── 채점 ─────────────────────────────────────────────────────────

    private fun planContext(p: Persona, user: User): Pair<PlanConstraints, PlanTargets> {
        val target = AopTestUtils.getUltimateTargetObject<AIPlanGenerationService>(planService)
        val req = request(p)
        val ctx = target.resolveContext(user, req)
        val effective = req.copy(
            injuries = if (ctx.injuries.isNotEmpty()) ctx.injuries.toList() else req.injuries,
            equipment = if (ctx.equipment.isEmpty()) req.equipment else ctx.equipment.toList(),
            focusAreas = ((req.focusAreas ?: emptyList()) + ctx.focusAreas.map { it.name.lowercase() }).distinct().ifEmpty { null },
        )
        @Suppress("UNCHECKED_CAST")
        val restricted = ReflectionTestUtils.invokeMethod<Any>(target, "restrictedChecker", ctx.injuries) as (Exercise) -> Boolean
        val constraints = PlanConstraints(
            allowedEquipment = PlanConstraints.normalizeEquipment(effective.equipment),
            injuries = ctx.injuries, isRestricted = restricted, isSuitable = ctx.suitability(),
        )
        return constraints to target.targetsOf(effective)
    }

    /** 플랜 답 하나: 파싱 → 환각 보정 수 → 정규화 → 품질 검사 */
    fun gradePlan(p: Persona, user: User, raw: String): Map<String, Any?> {
        val target = AopTestUtils.getUltimateTargetObject<AIPlanGenerationService>(planService)
        val parsed = ReflectionTestUtils.invokeMethod<AIPlanResponse?>(target, "tryParse", raw)
            ?: return mapOf("parsed" to false, "valid" to false, "usable" to false)
        val (constraints, targets) = planContext(p, user)
        // 모델이 고른 그대로(보정 전) 장비·부상·적합성 위반 수
        val byId = exerciseRepository.findAllById(parsed.days.flatMap { d -> d.exercises.map { it.exerciseId } }).associateBy { it.id }
        val picked = parsed.days.flatMap { d -> d.exercises }
        val unknown = picked.count { it.exerciseId !in byId }
        val disallowed = picked.mapNotNull { byId[it.exerciseId] }.count { ex ->
            !constraints.equipmentAllowed(ex) || constraints.isRestricted(ex) || !constraints.isSuitable(ex) || !ExerciseRequirements.allows(ex, p.equipment)
        }
        // 날 유형과 어긋난 종목(보정 전·후)과 세션 시간 넘침(+10%) — 11-22 검사기 규칙의 실측
        val validator = ReflectionTestUtils.getField(target, "planQualityValidator") as PlanQualityValidator
        fun mismatches(ds: List<AIPlanDayEntry>, byIdAll: Map<Long, Exercise>) =
            ds.sumOf { d -> d.exercises.count { e -> byIdAll[e.exerciseId]?.let { !validator.fitsDayType(it, d.workoutType) } ?: false } }
        val rawMismatch = mismatches(parsed.days, byId)
        val attempt = target.runPipeline(parsed, constraints, targets)
        val days = attempt.plan.days
        val byIdAfter = exerciseRepository.findAllById(days.flatMap { d -> d.exercises.map { it.exerciseId } }).associateBy { it.id }
        return mapOf(
            "type_mismatch_raw" to rawMismatch, "type_mismatch_after" to mismatches(days, byIdAfter),
            "over_time_days" to days.count { it.estimatedDurationMinutes > p.minutes * 1.1 }, "max_min" to (days.maxOfOrNull { it.estimatedDurationMinutes } ?: 0),
            "parsed" to true, "valid" to attempt.report.isValid, "errors" to attempt.report.errors.size,
            "error_messages" to attempt.report.errors.map { it.message }.take(5),
            "warning_messages" to attempt.report.issues.filter { it.severity == IssueSeverity.WARNING }.map { it.message }.take(5),
            "repair_reasons" to attempt.repairs.groupingBy { it.reason.name }.eachCount(),
            "adjustment_samples" to attempt.adjustments.take(3),
            "issues" to attempt.report.issues.size, "repairs" to attempt.repairs.size, "adjustments" to attempt.adjustments.size,
            "unknown_ids" to unknown, "disallowed_picks" to disallowed, "picked" to picked.size,
            "days" to parsed.days.size, "days_expected" to p.days,
            "usable" to (days.isNotEmpty() && days.size == attempt.plan.totalDays && days.all { it.exercises.size >= 3 }),
            "summary" to days.map { d -> mapOf("day" to d.dayNumber, "name" to d.dayName, "type" to d.workoutType, "min" to d.estimatedDurationMinutes,
                "ex" to d.exercises.map { e -> "${byId[e.exerciseId]?.slug ?: e.exerciseId} ${e.sets}x${e.minReps}-${e.maxReps}" }) },
        )
    }

    /** 추천 답 하나: JSON → 후보 안 id → 장비·필요 도구·부상 → 개수·순서 */
    fun gradeReco(p: Persona, prompt: String, raw: String): Map<String, Any?> {
        val candidates = Regex("""\[ID: (\d+)]""").findAll(prompt).map { it.groupValues[1].toLong() }.toSet()
        val json = runCatching { mapper.readValue<Map<String, Any?>>(raw.replace("```json", "").replace("```", "").trim()) }.getOrNull()
            ?: return mapOf("parsed" to false, "valid" to false)
        @Suppress("UNCHECKED_CAST")
        val list = (json["exercises"] as? List<Map<String, Any?>>).orEmpty()
        val ids = list.mapNotNull { (it["id"] as? Number)?.toLong() ?: it["id"]?.toString()?.toLongOrNull() }
        val byId = exerciseRepository.findAllById(ids).associateBy { it.id }
        val outside = ids.count { it !in candidates }
        val disallowed = ids.mapNotNull { byId[it] }.count { ex ->
            val eq = ex.equipment?.name
            val equipmentOk = eq == null || eq == "BODYWEIGHT" || eq == "OTHER" || eq in p.equipment
            !equipmentOk || !ExerciseRequirements.allows(ex, p.equipment) ||
                (p.injuries.isNotEmpty() && injuryFilterService.isRestrictedByRule(ex, p.injuries.toSet()))
        }
        val firstCompound = ids.firstOrNull()?.let { byId[it] }?.let { com.richjun.liftupai.domain.workout.service.new.RecoRules.isCompound(it) } ?: false
        val n = ids.distinct().size
        // 목표 개수는 프롬프트가 실제로 요구한 시간에서(첫 세션 규칙이면 30분 → 4종목). 사용자 설정 시간이 아니다.
        val minutes = Regex("""Duration: (\d+) minutes""").find(prompt)?.groupValues?.get(1)?.toInt() ?: p.minutes
        // 프롬프트는 개수를 정하지 않는다 — 10분에 한 종목꼴, 최소 4(첫 세션 규칙 "앞 4종목"), ±1 허용
        val target = maxOf(4, Math.round(minutes / 10.0).toInt())
        return mapOf(
            "parsed" to true, "count" to n, "count_target" to target, "prompt_minutes" to minutes, "outside_candidates" to outside, "disallowed" to disallowed,
            "duplicates" to (ids.size - n), "first_compound" to firstCompound,
            "valid" to (n in (target - 1)..(target + 1) && outside == 0 && disallowed == 0 && ids.size == n),
            "summary" to ids.map { id -> byId[id]?.slug ?: "#$id" } + listOfNotNull(json["workout_name"]?.toString()),
        )
    }

    @Test
    fun grade() {
        val results = System.getenv("COMPLEX_EVAL_RESULTS")?.takeIf { it.isNotBlank() }
        val outPath = System.getenv("COMPLEX_EVAL_GRADES")?.takeIf { it.isNotBlank() }
        assumeTrue(results != null && outPath != null, "COMPLEX_EVAL_RESULTS·COMPLEX_EVAL_GRADES 가 없으면 건너뛴다")
        allowErrors()
        val byId = personas().associateBy { it.id }
        val users = mutableMapOf<String, User>()
        fun userOf(p: Persona) = users.getOrPut(p.id) { setUpUser(p) }
        val captureDir = File(results!!).parentFile.resolve("capture")

        // 음성 대조: 깨진 JSON 과 기구 없는 사람에게 바벨 종목을 고른 답은 실패로 잡혀야 한다
        val bw = byId.getValue("ja_expert_bodyweight")
        assertFalse(gradePlan(bw, userOf(bw), "not json {")["parsed"] as Boolean)
        val barbell = exerciseRepository.findAll().first { it.slug == "barbell-full-squat" }
        val recoPrompt = File(captureDir, "reco__ja_expert_bodyweight.json").readText().let { mapper.readTree(it)["prompt"].asText() }
        val bad = gradeReco(bw, recoPrompt + "\n[ID: ${barbell.id}]", """{"exercises":[{"id":${barbell.id},"sets":3,"reps":"5"}]}""")
        assertTrue((bad["disallowed"] as Int) > 0 && bad["valid"] == false, "음성 대조(맨몸 사용자에게 바벨)를 못 잡았다: $bad")
        // 양성 대조: 후보 목록 중 맨몸 종목 4개로 만든 정상 답은 통과해야 한다.
        // (후보 목록 자체에는 바벨·머신이 섞여 있다 — 벡터 검색 폴백이 사용자 장비를 안 본다. 보고서에 적음)
        val firstIds = Regex("""\[ID: (\d+)][^\n]*equipment=BODYWEIGHT""").findAll(recoPrompt).map { it.groupValues[1] }.take(4).toList()
        val good = gradeReco(bw, recoPrompt, """{"exercises":[${firstIds.joinToString(",") { """{"id":$it,"sets":3,"reps":"8-12"}""" }}]}""")
        assertTrue(good["valid"] == true, "양성 대조(정상 추천)가 실패로 나왔다: $good")
        val broken = gradePlan(bw, userOf(bw), "not json {")
        File(outPath!!).resolveSibling("grades.meta.json").writeText(mapper.writeValueAsString(mapOf(
            "neg_broken_json_parsed" to broken["parsed"], "neg_barbell_disallowed" to bad["disallowed"], "neg_barbell_valid" to bad["valid"],
            "pos_reco_valid" to good["valid"], "pos_reco_ids" to firstIds)))

        val out = File(outPath!!).bufferedWriter()
        File(results).readLines().filter { it.isNotBlank() }.forEach { line ->
            val r = mapper.readTree(line)
            val (kind, pid) = r["case"].asText().split("__")
            val p = byId.getValue(pid)
            val raw = r["text"]?.asText().orEmpty()
            val grade = if (r["error"]?.isNull == false) mapOf("parsed" to false, "valid" to false, "transport_error" to true)
            else if (kind == "plan") gradePlan(p, userOf(p), raw)
            else gradeReco(p, mapper.readTree(File(captureDir, "reco__$pid.json").readText())["prompt"].asText(), raw)
            out.write(mapper.writeValueAsString(mapOf("model" to r["model"].asText(), "case" to r["case"].asText(), "rep" to r["rep"].asInt(),
                "ms" to r["ms"].asLong(), "in" to r["in"].asLong(), "out" to r["out"].asLong(), "think" to r["think"].asLong(), "grade" to grade)))
            out.newLine()
        }
        out.close()
    }
}

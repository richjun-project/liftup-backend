package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * 템플릿 추천 적합도 평가 하네스.
 *
 * reco-eval/personas.json 의 페르소나(실사용 분포를 본뜬 65명)마다
 *   온보딩 답 → 추천(TemplateRecommendationEngine) → 적용 시뮬레이션(TemplatePlanAdapter) → 하드 체크
 * 를 돌려 표와 실패 목록을 출력한다. DB 없이 실제 카탈로그(1223개)와 실제 프리셋 템플릿 정의로 돈다.
 *
 * 하드 체크(전원 통과해야 한다)
 *  - 장비: 적용된 모든 운동이 보유 장비로 가능(맨몸·기타는 항상 가능)
 *  - 부상: 적용된 모든 운동이 부상 규칙에 걸리지 않음
 *  - 수준: 템플릿 수준이 사람의 수준(expect.max_level)을 넘지 않음
 *  - 일수: 템플릿 일수가 주 운동일수와 days_tolerance 이내
 *  - 하루 최소 운동 수: expect.min_exercises_per_day 이상
 *  - 집중 볼륨: require_focus 부위 운동이 주간 [requiredFocusHits] 개 이상
 *  - 같은 날 중복 운동 없음
 *  - 시간 적합: 모든 날이 요청 세션 시간의 85~110%  (RecoFitChecks.minuteFit)
 *  - 바벨 무게: 바벨 종목 첫 세션 무게가 0 초과 20kg 미만이 아님  (RecoFitChecks.barbellWeights)
 *  - 적합성: 철봉 없는 사람에게 철봉 동작 0, 초보·고령·미성년에게 너무 어려운 동작 0  (RecoFitChecks.suitability)
 *  - 초보 처방: 초보·노비스의 주력 복합운동은 8회 이상·3세트 이하  (RecoFitChecks.beginnerPrescription)
 *  - 반복: 집·부상 사용자의 같은 운동이 후보가 충분한데 주 3회 이상 나오지 않음  (RecoFitChecks.repeats)
 *  - 집중 부위 우선: 목표에서 읽은 첫 번째 집중 부위도 주간 기준 이상
 *  - 순서: 그날 대표(ESSENTIAL) 복합운동이 있으면 첫 운동  (RecoFitChecks.leadOrdering)
 *  - 짧은 세션: 30분 이하 사용자의 추천 템플릿은 하루 평균 36분 이하(그런 후보가 있을 때)
 * 집계 지표(표 아래 출력, 복합운동 선두율은 [MIN_COMPOUND_LEAD_RATE] 이상이어야 통과)
 *
 * 다른 페르소나 파일은 -DrecoEval.file=<경로> (또는 환경변수 RECO_EVAL_FILE) 로 채점한다. 파일 형식은 reco-eval/personas.json 과 같고,
 * age·gender·weight_kg·expect 의 일부 필드는 없어도 된다.
 * acceptable_templates 는 사람이 적어 둔 "무난한 선택" 힌트라 막지 않고, 목록 밖 선택을 표에 표시만 한다
 * (전용 템플릿이 생기면 목록 밖이 더 좋은 답일 수 있다).
 */
class RecoFitEvalTest {

    private val mapper = ObjectMapper()

    private data class Persona(
        val id: String,
        val locale: String,
        val experience: String,
        val goals: List<String>,
        val weeklyDays: Int,
        val sessionDuration: Int,
        val equipment: Set<String>,
        val injuries: Set<String>,
        val maxLevel: String,
        val daysTolerance: Int,
        val minPerDay: Int,
        val requireFocus: List<FocusArea>,
        val acceptable: Set<String>,
        val why: String,
        val age: Int? = null,
        val gender: String? = null,
        val weightKg: Double? = null,
    ) {
        val level: ExperienceLevel? get() = RecoRules.parseLevel(experience)
    }

    private class Row(
        val persona: Persona, val code: String, val failures: List<String>, val note: String, val top: String = "",
        val dayCount: Int = 0, val leadDays: Int = 0, val compoundLeadDays: Int = 0, val timeRatios: List<Double> = emptyList(), val poolLimited: Boolean = false,
    )

    companion object {
        /** 하루의 첫 운동이 코어·유산소가 아닌 복합운동인 날의 비율 하한 — 첫 종목이 보조·복근 운동이면 좋은 플랜이 아니다 */
        const val MIN_COMPOUND_LEAD_RATE = 0.90

        /** 30분 이하 세션에 추천하는 템플릿의 하루 평균 시간 상한(분) */
        const val MAX_AVG_TEMPLATE_MINUTES_FOR_SHORT = 36.0

        /** 집중 부위로 인정하려면 주간 최소 이 개수 (2일 플랜은 2개) */
        fun requiredFocusHits(planDays: Int) = if (planDays <= 2) 2 else 3

        fun loadExercises(mapper: ObjectMapper = ObjectMapper()): List<Exercise> {
            val stream = RecoFitEvalTest::class.java.getResourceAsStream("/catalog/exercise-catalog.json")
                ?: error("catalog/exercise-catalog.json not found")
            return mapper.readTree(stream).mapIndexed { i, n -> toExercise(i + 1L, n) }
        }

        private fun toExercise(id: Long, n: JsonNode): Exercise {
            fun text(key: String) = n[key]?.takeIf { !it.isNull }?.asText()
            val en = n["translations"].firstOrNull { it["locale"].asText() == "en" }?.get("name")?.asText() ?: text("slug")!!
            return Exercise(
                id = id,
                slug = text("slug")!!,
                name = en,
                category = ExerciseCategory.valueOf(text("category")!!),
                movementPattern = text("movementPattern"),
                muscleGroups = n["muscleGroups"].map { MuscleGroup.valueOf(it.asText()) }.toMutableSet(),
                equipment = text("equipment")?.let { runCatching { Equipment.valueOf(it) }.getOrNull() },
                popularity = n["popularity"]?.asInt(50) ?: 50,
                difficulty = n["difficulty"]?.asInt(50) ?: 50,
                isBasicExercise = n["basicExercise"]?.asBoolean(false) ?: false,
                recommendationTier = runCatching { RecommendationTier.valueOf(text("recommendationTier")!!) }.getOrDefault(RecommendationTier.STANDARD),
                isPlanEligible = n["isPlanEligible"]?.asBoolean(false) ?: false,
            )
        }

        fun injuryService() = InjuryFilterService(
            mock(com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository::class.java),
            mock(com.richjun.liftupai.domain.workout.repository.ProgramDayRepository::class.java),
            mock(com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository::class.java),
            mock(com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository::class.java),
            mock(com.richjun.liftupai.domain.workout.repository.ExerciseRepository::class.java),
        )

        /** 실제 카탈로그·프리셋 템플릿으로 만든 추천 엔진. 부상 판정은 속성 규칙(DB 테이블은 현재 비어 있다). */
        fun buildEngine(exercises: List<Exercise>, injuries: Set<String>): TemplateRecommendationEngine {
            val bySlug = exercises.associateBy { it.slug }
            val pool = exercises.filter { it.isPlanEligible }
                .groupBy { it.category }
                .mapValues { (_, list) -> list.sortedWith(compareBy({ -it.popularity }, { it.id })) }
            val service = injuryService()
            val adapter = TemplatePlanAdapter(
                poolByCategory = { pool[it].orEmpty() },
                isRestricted = { ex -> injuries.isNotEmpty() && service.isRestrictedByRule(ex, injuries) },
            )
            val templates = PresetTemplates.ALL.map { t ->
                TemplateRecommendationEngine.RecoTemplate(
                    code = t.code, targetGoal = t.targetGoal, targetExperience = t.targetExperience,
                    totalDays = t.totalDays, sortOrder = t.sortOrder,
                    days = t.days.map { d ->
                        TemplateRecommendationEngine.RecoDay(
                            d.dayNumber, d.workoutType.name, d.durationMinutes,
                            d.exercises.mapNotNull { e ->
                                bySlug[e.slug]?.let { ex ->
                                    TemplatePlanAdapter.SourceExercise(ex, e.sets, e.minReps, e.maxReps, e.restSeconds, e.isCompound, e.targetRPE, e.notes)
                                }
                            }
                        )
                    }
                )
            }
            return TemplateRecommendationEngine(templates, adapter)
        }
    }

    private fun personasJson(): com.fasterxml.jackson.databind.JsonNode {
        val file = System.getProperty("recoEval.file")?.takeIf { it.isNotBlank() } ?: System.getenv("RECO_EVAL_FILE")?.takeIf { it.isNotBlank() }
        if (file != null) {
            val f = java.io.File(file)
            require(f.isFile) { "recoEval.file not found: $file" }
            return mapper.readTree(f)["personas"]
        }
        val stream = javaClass.getResourceAsStream("/reco-eval/personas.json") ?: error("reco-eval/personas.json not found")
        return mapper.readTree(stream)["personas"]
    }

    private fun loadPersonas(): List<Persona> {
        return personasJson().map { n ->
            val e = n["expect"]
            Persona(
                id = n["id"].asText(),
                locale = n["locale"]?.asText() ?: "en",
                experience = n["experience_level"].asText(),
                goals = n["goals"].map { it.asText() },
                weeklyDays = n["weekly_days"].asInt(),
                sessionDuration = n["session_duration"].asInt(),
                // 서비스와 같은 정규화(별칭·헬스장 전체 토큰·부상 접미사) — 다른 표기의 페르소나 파일도 같은 뜻으로 읽는다
                equipment = RecoRules.normalizeEquipment(n["equipment"].map { it.asText() }),
                injuries = RecoRules.normalizeInjuries(n["injuries"].map { it.asText() }),
                maxLevel = e?.get("max_level")?.asText() ?: n["experience_level"].asText(),
                daysTolerance = e?.get("days_tolerance")?.asInt(1) ?: 1,
                minPerDay = e?.get("min_exercises_per_day")?.asInt(3) ?: 3,
                requireFocus = e?.get("require_focus")?.mapNotNull { FocusArea.parse(it.asText()) }.orEmpty(),
                acceptable = e?.get("acceptable_templates")?.map { it.asText() }?.toSet().orEmpty(),
                why = e?.get("why")?.asText().orEmpty(),
                age = n["age"]?.takeIf { !it.isNull }?.asInt(),
                gender = n["gender"]?.takeIf { !it.isNull }?.asText(),
                weightKg = n["weight_kg"]?.takeIf { !it.isNull }?.asDouble(),
            )
        }
    }

    @Test
    fun `모든 페르소나가 하드 체크를 통과한다`() {
        val exercises = loadExercises(mapper)
        val service = injuryService()
        val rows = loadPersonas().map { p -> evaluate(p, exercises, service) }

        val sb = StringBuilder("\n=== RecoFitEval: ${rows.size} personas ===\n")
        sb.append(String.format("%-24s %-3s %-12s %-3s %-4s %-26s %s\n", "persona", "lv", "equip", "d", "min", "template", "result"))
        rows.forEach { r ->
            val p = r.persona
            sb.append(
                String.format(
                    "%-24s %-3s %-12s %-3d %-4d %-26s %s%s\n",
                    p.id, p.experience.take(3), p.equipment.joinToString("") { it.take(1) }, p.weeklyDays, p.sessionDuration,
                    r.code, if (r.failures.isEmpty()) "ok" else "FAIL", if (r.note.isNotEmpty()) "  [${r.note}]" else ""
                )
            )
        }
        if (System.getProperty("recoEval.detail") != null || System.getenv("RECO_EVAL_DETAIL") != null) {
            rows.forEach { r -> sb.append("${r.persona.id} ${r.persona.experience} ${r.persona.goals} ${r.persona.equipment} inj=${r.persona.injuries} d=${r.persona.weeklyDays} ${r.persona.sessionDuration}m\n${r.top}\n") }
        }
        val failed = rows.filter { it.failures.isNotEmpty() }
        sb.append("passed=${rows.size - failed.size} failed=${failed.size}\n")
        failed.forEach { r -> sb.append("FAIL ${r.persona.id} (${r.persona.why})\n").also { r.failures.forEach { f -> sb.append("   - $f\n") } } }
        val dist = rows.groupingBy { it.code }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
        sb.append("template distribution: $dist\n")

        // 집계 지표
        val days = rows.sumOf { it.dayCount }
        val lead = rows.sumOf { it.leadDays }
        val leadRate = if (days == 0) 1.0 else lead.toDouble() / days
        val compoundLead = rows.sumOf { it.compoundLeadDays }
        val compoundLeadRate = if (days == 0) 1.0 else compoundLead.toDouble() / days
        val ratios = rows.flatMap { it.timeRatios }
        sb.append("time fit: days=${ratios.size} min=%.2f avg=%.2f max=%.2f within85..110=%d/%d\n".format(
            ratios.minOrNull() ?: 0.0, ratios.average(), ratios.maxOrNull() ?: 0.0,
            ratios.count { it >= RecoFitChecks.MIN_TIME_RATIO && it <= RecoFitChecks.MAX_TIME_RATIO }, ratios.size))
        sb.append("lead rate (day starts with ESSENTIAL compound): $lead/$days = %.0f%% (the rest has no ESSENTIAL compound in the day: catalog tier limit for home/bodyweight)\n".format(leadRate * 100))
        sb.append("compound-first rate (day starts with a non-core compound): $compoundLead/$days = %.0f%%\n".format(compoundLeadRate * 100))
        sb.append("repeat-limited personas (pool too small to avoid >2/week): ${rows.count { it.poolLimited }}\n")
        println(sb)

        assertTrue(failed.isEmpty(), "하드 체크 실패 ${failed.size}건:\n" + failed.joinToString("\n") { "${it.persona.id}: ${it.failures}" })
        assertTrue(compoundLeadRate >= MIN_COMPOUND_LEAD_RATE, "하루 첫 운동이 복합운동인 비율 ${"%.0f".format(compoundLeadRate * 100)}% < ${(MIN_COMPOUND_LEAD_RATE * 100).toInt()}%")
    }

    private val weightService by lazy {
        WeightRecommendationService(
            mock(com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository::class.java),
            mock(com.richjun.liftupai.domain.workout.repository.ExerciseRepository::class.java),
            mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
            ExercisePatternClassifier(), mapper,
        )
    }

    private fun profileOf(p: Persona): UserProfile {
        val bw = p.weightKg ?: if (p.gender?.lowercase() == "female") 58.0 else 72.0
        return UserProfile(
            user = User(id = 1L, email = "t@t.t", nickname = "T", level = UserLevel.INTERMEDIATE),
            bodyInfo = BodyInfo(weight = bw), experienceLevel = p.level ?: ExperienceLevel.BEGINNER, age = p.age, gender = p.gender,
        )
    }

    private fun evaluate(p: Persona, exercises: List<Exercise>, service: InjuryFilterService): Row {
        val engine = buildEngine(exercises, p.injuries)
        val profile = TemplateRecommendationEngine.RecoProfile(
            experience = p.experience,
            goals = p.goals,
            weeklyDays = p.weeklyDays,
            sessionDuration = p.sessionDuration,
            equipment = p.equipment,
            injuries = p.injuries,
            focus = FocusArea.resolve(p.goals),
            age = p.age,
        )
        val outcome = engine.recommend(profile)
        val t = outcome.best.template
        val failures = mutableListOf<String>()

        // 장비
        val badEquip = outcome.adapted.flatMap { it.exercises }.filter { !TemplatePlanAdapter.equipmentAllowed(it.exercise, p.equipment) }
        if (badEquip.isNotEmpty()) failures += "장비 불가 운동 ${badEquip.map { it.exercise.slug }}"
        // 부상
        if (p.injuries.isNotEmpty()) {
            val badInjury = outcome.adapted.flatMap { it.exercises }.filter { service.isRestrictedByRule(it.exercise, p.injuries) }
            if (badInjury.isNotEmpty()) failures += "부상 제한 운동 ${badInjury.map { it.exercise.slug }}"
        }
        // 수준
        if (TemplateRecommendationEngine.rankOf(t.targetExperience) > TemplateRecommendationEngine.rankOf(p.maxLevel)) {
            failures += "수준 초과: 템플릿 ${t.targetExperience} > ${p.maxLevel}"
        }
        // 일수
        if (kotlin.math.abs(t.totalDays - p.weeklyDays) > p.daysTolerance) {
            failures += "일수 불일치: 템플릿 ${t.totalDays}일 vs 주 ${p.weeklyDays}일 (허용 ±${p.daysTolerance})"
        }
        // 하루 최소 운동 수
        val sizes = outcome.adapted.map { it.exercises.size }
        // 20분 이하의 세션은 2종목이 하한이다(3종목 × 2세트 × 휴식이 15분에 안 들어간다) — 시간 적합이 우선
        val minPerDay = if (p.sessionDuration <= 20) minOf(p.minPerDay, 2) else p.minPerDay
        if (sizes.any { it < minPerDay }) failures += "하루 운동 수 부족 $sizes < $minPerDay"
        // 같은 날 중복
        outcome.adapted.forEach { d ->
            val dup = d.exercises.groupBy { it.exercise.id }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) failures += "day ${d.dayNumber} 중복 운동 $dup"
        }
        // 집중 볼륨 — 부상·장비·수준 때문에 그 부위의 후보 자체가 모자라면 가능한 만큼만 요구한다
        // 20분 이하의 세션은 하루가 2~3종목(첫 종목은 대표 복합운동)이라 집중 부위 볼륨 기준을 요구하지 않는다
        val need = if (p.sessionDuration <= 20) 0 else requiredFocusHits(outcome.adapted.size)
        val suitableForFocus = RecoRules.suitability(p.equipment, p.level ?: ExperienceLevel.BEGINNER, p.age)
        fun feasibleHits(area: FocusArea) = exercises.count { ex ->
            ex.isPlanEligible && area.hits(ex) && TemplatePlanAdapter.equipmentAllowed(ex, p.equipment) && suitableForFocus(ex) &&
                (p.injuries.isEmpty() || !service.isRestrictedByRule(ex, p.injuries))
        }
        p.requireFocus.forEach { area ->
            val hits = outcome.adapted.sumOf { d -> d.exercises.count { area.hits(it.exercise) } }
            val required = minOf(need, feasibleHits(area))
            if (hits < required) failures += "집중 볼륨 부족 $area $hits < $required"
        }

        // ── 새 하드 체크 ──
        // 짧은 세션(30분 이하): 추천된 템플릿의 하루 평균 시간이 36분 이하여야 한다 — 단, 36분 이하의 후보 템플릿이 아예 없으면 면제.
        // (30분 사용자에게 44~48분짜리 템플릿이 나가 보조 종목이 통째로 잘려 나가던 사례)
        if (p.sessionDuration in 25..30 && TemplateRecommendationEngine.rankOf(p.experience) <= 1) {
            val avg = t.days.map { it.estimatedMinutes }.average()
            // 같은 수준(또는 한 단계 아래)이고 요일 수가 ±1 이내이며 장비·부상 필터에 70% 이상 살아남는 짧은 템플릿이 있을 때만 요구한다
            val shortExists = outcome.ranked.any { it.survival >= 0.7 && kotlin.math.abs(it.dayGap) <= 1 && it.template.days.map { d -> d.estimatedMinutes }.average() <= MAX_AVG_TEMPLATE_MINUTES_FOR_SHORT }
            if (avg > MAX_AVG_TEMPLATE_MINUTES_FOR_SHORT && shortExists) {
                failures += "30분 이하 세션인데 템플릿 평균 ${"%.0f".format(avg)}분 > ${MAX_AVG_TEMPLATE_MINUTES_FOR_SHORT.toInt()}분"
            }
        }
        val plan = RecoFitChecks.fromAdapted(outcome.adapted)
        // 시간 적합 (85~110%). 모자란 날은 더 채울 여지(안 쓴 적합 운동·세트·휴식 안전 상한)가 있을 때만 위반
        val level = p.level ?: ExperienceLevel.BEGINNER
        val suitableAll = RecoRules.suitability(p.equipment, level, p.age)
        fun eligible(ex: Exercise) = ex.isPlanEligible && TemplatePlanAdapter.equipmentAllowed(ex, p.equipment) && suitableAll(ex) &&
            (p.injuries.isEmpty() || !service.isRestrictedByRule(ex, p.injuries))
        val eligiblePool = exercises.filter(::eligible)
        failures += RecoFitChecks.minuteFit(plan, p.sessionDuration) { day ->
            val inDay = day.items.map { it.exercise.id }.toSet()
            val canAdd = day.items.size < TemplatePlanAdapter.maxExercisesFor(p.sessionDuration) && eligiblePool.any { it.id !in inDay }
            val canRaise = day.items.any { i ->
                i.sets < RecoRules.maxSetsFor(i.exercise, level, p.age, i.isCompound, p.sessionDuration) ||
                    i.restSeconds < RecoRules.maxRestFor(i.exercise, i.isCompound, i.minReps)
            }
            canAdd || canRaise
        }
        // 첫 세션 무게: 바벨 20kg 미만 금지
        val up = profileOf(p)
        val weights = outcome.adapted.flatMap { it.exercises }.map { e ->
            e.exercise to weightService.firstSessionWeight(up, e.exercise, (e.source.minReps + e.source.maxReps) / 2)
        }
        failures += RecoFitChecks.barbellWeights(weights)
        // 적합성 (철봉·너무 어려운 동작)
        failures += RecoFitChecks.suitability(plan, p.equipment, p.level, p.age)
        // 초보 처방
        failures += RecoFitChecks.beginnerPrescription(plan, p.level)
        // 순서: 그날 대표 복합운동이 있으면 첫 운동이어야 한다
        failures += RecoFitChecks.leadOrdering(plan)
        // 집·부상 사용자의 반복 — 바꿀 후보가 있을 때만 강제("후보가 허용하는 한")
        val cap = TemplatePlanAdapter.repeatCapFor(p.equipment, p.injuries)
        var poolLimited = false
        if (cap != null) {
            val suitable = RecoRules.suitability(p.equipment, p.level ?: ExperienceLevel.BEGINNER, p.age)
            val focusAreas = FocusArea.resolve(p.goals)
            fun eligible(ex: Exercise) = ex.isPlanEligible && TemplatePlanAdapter.equipmentAllowed(ex, p.equipment) && suitable(ex) &&
                (p.injuries.isEmpty() || !service.isRestrictedByRule(ex, p.injuries))
            val violations = RecoFitChecks.repeats(plan, { ex, inPlan ->
                val originalFocus = focusAreas.filter { it.hits(ex) }
                exercises.filter { c ->
                    c.category == ex.category && c.id !in inPlan && eligible(c) && (originalFocus.isEmpty() || originalFocus.any { it.hits(c) })
                }
            })
            poolLimited = plan.flatMap { d -> d.items.map { it.exercise.id } }.groupBy { it }.any { it.value.size > cap }
            failures += violations
        }
        // 목표에서 읽은 첫 번째 집중 부위(목표 순서 우선)
        FocusArea.resolve(p.goals).firstOrNull()?.let { first ->
            failures += RecoFitChecks.focusVolume(plan, listOf(first), minOf(need, feasibleHits(first)))
        }

        val offList = p.acceptable.isNotEmpty() && t.code !in p.acceptable
        val note = if (offList) "off-list(${p.acceptable.joinToString("/")})" else ""
        val top = outcome.ranked.take(3).joinToString("\n") { s ->
            "      %-26s %6.1f surv=%.2f %s".format(s.template.code, s.total, s.survival, s.breakdown.entries.joinToString(" ") { "${it.key}=${"%.0f".format(it.value)}" })
        }
        val planText = outcome.adapted.joinToString("\n") { d ->
            "        d${d.dayNumber} ${d.workoutType} ${"%.0f".format(RecoFitChecks.seconds(plan.first { it.dayNumber == d.dayNumber }) / 60.0)}m${if (RecoFitChecks.leadsWithEssentialCompound(plan.first { it.dayNumber == d.dayNumber })) "" else " (!lead)"}: " + d.exercises.joinToString(", ") { e ->
                e.exercise.slug + "(${e.source.sets}x${e.source.minReps}-${e.source.maxReps}/${e.source.restSeconds})" + (if (e.origin == TemplatePlanAdapter.Origin.TEMPLATE) "" else "[${e.origin.name.first()}]")
            }
        }
        return Row(
            p, t.code, failures.distinct(), note, top + "\n" + planText,
            dayCount = plan.size, leadDays = plan.count { RecoFitChecks.leadsWithEssentialCompound(it) },
            compoundLeadDays = plan.count { RecoFitChecks.leadsWithCompound(it) },
            timeRatios = plan.map { RecoFitChecks.seconds(it) / (p.sessionDuration * 60.0) }, poolLimited = poolLimited,
        )
    }
}

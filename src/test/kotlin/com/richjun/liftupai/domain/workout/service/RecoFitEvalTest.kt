package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
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
    )

    private class Row(val persona: Persona, val code: String, val failures: List<String>, val note: String, val top: String = "")

    companion object {
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

    private fun loadPersonas(): List<Persona> {
        val stream = javaClass.getResourceAsStream("/reco-eval/personas.json") ?: error("reco-eval/personas.json not found")
        return mapper.readTree(stream)["personas"].map { n ->
            val e = n["expect"]
            Persona(
                id = n["id"].asText(),
                locale = n["locale"].asText(),
                experience = n["experience_level"].asText(),
                goals = n["goals"].map { it.asText() },
                weeklyDays = n["weekly_days"].asInt(),
                sessionDuration = n["session_duration"].asInt(),
                equipment = n["equipment"].map { it.asText().uppercase() }.toSet(),
                injuries = n["injuries"].map { it.asText().lowercase() }.toSet(),
                maxLevel = e["max_level"].asText(),
                daysTolerance = e["days_tolerance"]?.asInt(1) ?: 1,
                minPerDay = e["min_exercises_per_day"]?.asInt(3) ?: 3,
                requireFocus = e["require_focus"]?.mapNotNull { FocusArea.parse(it.asText()) }.orEmpty(),
                acceptable = e["acceptable_templates"]?.map { it.asText() }?.toSet().orEmpty(),
                why = e["why"]?.asText().orEmpty(),
            )
        }
    }

    @Test
    fun `모든 페르소나가 하드 체크를 통과한다`() {
        val exercises = loadExercises(mapper)
        val service = injuryService()
        val rows = loadPersonas().map { p -> evaluate(p, exercises, service) }

        val sb = StringBuilder("\n=== RecoFitEval: ${rows.size} personas ===\n")
        sb.append(String.format("%-22s %-3s %-12s %-3s %-4s %-26s %s\n", "persona", "lv", "equip", "d", "min", "template", "result"))
        rows.forEach { r ->
            val p = r.persona
            sb.append(
                String.format(
                    "%-22s %-3s %-12s %-3d %-4d %-26s %s%s\n",
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
        println(sb)

        assertTrue(failed.isEmpty(), "하드 체크 실패 ${failed.size}건:\n" + failed.joinToString("\n") { "${it.persona.id}: ${it.failures}" })
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
        if (sizes.any { it < p.minPerDay }) failures += "하루 운동 수 부족 $sizes < ${p.minPerDay}"
        // 같은 날 중복
        outcome.adapted.forEach { d ->
            val dup = d.exercises.groupBy { it.exercise.id }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) failures += "day ${d.dayNumber} 중복 운동 $dup"
        }
        // 집중 볼륨
        val need = requiredFocusHits(outcome.adapted.size)
        p.requireFocus.forEach { area ->
            val hits = outcome.adapted.sumOf { d -> d.exercises.count { area.hits(it.exercise) } }
            if (hits < need) failures += "집중 볼륨 부족 $area $hits < $need"
        }
        val offList = p.acceptable.isNotEmpty() && t.code !in p.acceptable
        val note = if (offList) "off-list(${p.acceptable.joinToString("/")})" else ""
        val top = outcome.ranked.take(3).joinToString("\n") { s ->
            "      %-26s %6.1f surv=%.2f %s".format(s.template.code, s.total, s.survival, s.breakdown.entries.joinToString(" ") { "${it.key}=${"%.0f".format(it.value)}" })
        }
        val plan = outcome.adapted.joinToString("\n") { d ->
            "        d${d.dayNumber} ${d.workoutType}: " + d.exercises.joinToString(", ") { e -> e.exercise.slug + (if (e.origin == TemplatePlanAdapter.Origin.TEMPLATE) "" else "[${e.origin.name.first()}]") }
        }
        return Row(p, t.code, failures, note, top + "\n" + plan)
    }
}

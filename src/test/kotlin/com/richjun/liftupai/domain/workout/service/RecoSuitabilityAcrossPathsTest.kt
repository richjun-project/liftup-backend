package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.util.ExerciseSuitability
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 철봉이 필요한 동작과 너무 어려운 동작을 "플랜 적용"만이 아니라 제안·대체 후보에서도 걸러 낸다(F04).
 * 129명 페르소나 전원, 제안(4개 부위 상위 10)과 대체(대표 5개 운동의 상위 10)에서 위반 0.
 * 탐색(2026-10) 수치: 제안 상위 10 에서 2%, 대체에서 철봉 필요 130/4357·너무 어려움 248/4357 가 걸러지지 않았다.
 */
class RecoSuitabilityAcrossPathsTest {
    private class P(val id: String, val level: ExperienceLevel, val age: Int?, val gender: String?, val equipment: List<String>, val injuries: List<String>)

    private val personas: List<P> by lazy {
        val stream = javaClass.getResourceAsStream("/reco-eval/personas.json")!!
        RecoTestKit.mapper.readTree(stream)["personas"].map { n ->
            P(
                n["id"].asText(), RecoRules.parseLevel(n["experience_level"].asText()) ?: ExperienceLevel.BEGINNER,
                n["age"]?.takeIf { !it.isNull }?.asInt(), n["gender"]?.takeIf { !it.isNull }?.asText(),
                n["equipment"].map { it.asText().uppercase() }, n["injuries"].map { it.asText().lowercase() },
            )
        }
    }

    private fun repos(p: P) = RecoTestKit.Repos(
        RecoTestKit.profile(level = p.level, age = p.age, gender = p.gender, injuries = p.injuries, equipment = p.equipment),
        RecoTestKit.settings(equipment = p.equipment, injuries = p.injuries),
    )

    private fun violations(p: P, out: Collection<Exercise>, path: String): List<String> {
        val strict = if (p.level == ExperienceLevel.NOVICE) ExperienceLevel.BEGINNER else p.level
        val eq = RecoRules.normalizeEquipment(p.equipment)
        val inj = RecoRules.normalizeInjuries(p.injuries)
        return out.flatMap { e ->
            buildList {
                if (ExerciseSuitability.needsBar(e) && !ExerciseSuitability.hasBarAccess(eq)) add("${p.id}/$path: 철봉 없는데 ${e.slug}")
                if (ExerciseSuitability.tooAdvanced(e, strict, p.age)) add("${p.id}/$path: 너무 어려움 ${e.slug}")
                if (!TemplatePlanAdapter.equipmentAllowed(e, eq)) add("${p.id}/$path: 장비 없음 ${e.slug}")
                if (inj.isNotEmpty() && InjuryFilterService.ruleRestricts(e, inj)) add("${p.id}/$path: 부상 제한 ${e.slug}")
            }
        }
    }

    @Test
    @DisplayName("운동 제안: 129명 × 4개 부위 상위 10 에 철봉 없는 풀업·너무 어려운 동작·장비 없음·부상 제한 운동이 0")
    fun suggestions() {
        val bad = mutableListOf<String>()
        var checked = 0
        personas.forEach { p ->
            val r = repos(p)
            val captured = slot<List<Exercise>>()
            val detail = mockk<ExerciseCatalogDetailService>()
            every { detail.toExerciseDetails(capture(captured), any()) } returns emptyList()
            val cache = mockk<ExerciseCatalogCache>().also { every { it.get() } returns ExerciseCatalogCache.Snapshot(RecoTestKit.catalog, 0L) }
            val shared = mockk<WorkoutV2SharedSupport>().also { every { it.resolveLocale(any(), any()) } returns "ja" }
            val svc = ExerciseSuggestionService(cache, r.resolver, RecoTestKit.injuryFilter(), detail, shared)
            listOf("LEGS", "BACK", "CHEST", "SHOULDERS").forEach { cat ->
                svc.suggest(1L, cat, null, null, 10, "ja")
                checked += captured.captured.size
                bad += violations(p, captured.captured, "suggest/$cat")
            }
        }
        assertTrue(checked > 1000, "검사한 제안 수가 너무 적다: $checked")
        assertTrue(bad.isEmpty(), "제안 위반 ${bad.size}건:\n" + bad.take(15).joinToString("\n"))
    }

    @Test
    @DisplayName("대체 후보: 129명 × 대표 5개 운동의 상위 10 에 위반 0")
    fun substitutes() {
        val originals = listOf("barbell-full-squat", "barbell-bench-press", "barbell-deadlift", "cable-pulldown", "dumbbell-curls").map { RecoTestKit.bySlug.getValue(it) }
        val bad = mutableListOf<String>()
        var checked = 0
        personas.forEach { p ->
            val ctx = repos(p).resolver.resolve(1L)
            val restricted = RecoTestKit.injuryFilter().restrictionChecker(ctx.injuries)
            originals.forEach { o ->
                val out = SubstituteRanker.rankForUser(o, RecoTestKit.catalog, emptySet(), ctx, restricted, 10).map { it.exercise }
                checked += out.size
                bad += violations(p, out, "substitute/${o.slug}")
            }
        }
        assertTrue(checked > 1000, "검사한 대체 후보 수가 너무 적다: $checked")
        assertTrue(bad.isEmpty(), "대체 위반 ${bad.size}건:\n" + bad.take(15).joinToString("\n"))
    }

    @Test
    @DisplayName("양성 대조: 규칙 없이(전부 허용) 돌리면 같은 검사가 위반을 잡아낸다 — 검사가 실제로 실패할 수 있다")
    fun controlWithoutRulesFails() {
        val originals = listOf("barbell-full-squat", "barbell-bench-press", "barbell-deadlift").map { RecoTestKit.bySlug.getValue(it) }
        val bad = mutableListOf<String>()
        personas.forEach { p ->
            originals.forEach { o ->
                val out = SubstituteRanker.rank(o, RecoTestKit.catalog, emptySet(), { true }, 10).map { it.exercise }
                bad += violations(p, out, "control")
            }
        }
        assertTrue(bad.isNotEmpty(), "규칙을 끄면 위반이 나와야 검사가 의미 있다")
    }
}

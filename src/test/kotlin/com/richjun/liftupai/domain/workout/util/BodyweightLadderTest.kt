package com.richjun.liftupai.domain.workout.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.service.RecoFitEvalTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 맨몸 변형 사다리와 새로 연 맨몸 동작의 데이터 품질 — 2026-11-14 coach-autopilot-r1 */
class BodyweightLadderTest {
    private val mapper = ObjectMapper()
    private val catalog = RecoFitEvalTest.loadExercises(mapper).associateBy { it.slug }
    private val raw = mapper.readTree(javaClass.getResourceAsStream("/catalog/exercise-catalog.json")).associateBy { it["slug"].asText() }
    private val media = mapper.readTree(javaClass.getResourceAsStream("/catalog/exercise-media.json"))

    /** 2026-11-14 에 플랜에 쓸 수 있게 연 동작(영상은 있었는데 막혀 있었다) */
    private val opened = listOf(
        "superman-pulls", "standing-y-raise", "lying-back-extension", "archer-push-up", "pike-push-up",
        "bodyweight-single-leg-deadlift", "counterbalanced-skater-squat",
    )

    /**
     * 이미 플랜에 쓰이던(2026-11-14 이전부터 isPlanEligible) 동작 중 CDN 에 영상이 아직 없는 것. 사다리의 쉬운 단계라 뺄 수 없다.
     * 카탈로그 전체로는 58개가 영상이 없다 — 영상 제작은 별도 과제(growth/coach/PLAN.md). 새로 여는 동작은 여기에 넣지 않는다.
     */
    private val preexistingMediaGaps = setOf("knee-push-up", "incline-push-up", "inverted-row")

    private fun names(slug: String): Map<String, String> =
        raw[slug]?.get("translations")?.associate { it["locale"].asText() to it["name"].asText() }.orEmpty()

    @Test
    @DisplayName("사다리와 새로 연 동작은 모두 플랜에 쓸 수 있고 ko·ja·en 이름이 있으며, 썸네일·영상이 있다(이미 쓰이던 3개의 영상 공백은 명시)")
    fun everyStepIsUsableWithMediaAndNames() {
        val slugs = (BodyweightLadder.LADDERS.values.flatten() + opened).distinct()
        val problems = slugs.flatMap { slug ->
            val ex = catalog[slug] ?: return@flatMap listOf("$slug: 카탈로그에 없음")
            buildList {
                if (!ex.isPlanEligible) add("$slug: 플랜에 못 씀")
                val m = media[slug]
                val hasMedia = m != null && m.path("thumb").asBoolean(false) && m.path("video").asBoolean(false)
                if (!hasMedia && (slug in opened || slug !in preexistingMediaGaps)) add("$slug: 썸네일·영상 없음")
                val n = names(slug)
                listOf("ko", "ja", "en").filter { n[it].isNullOrBlank() }.forEach { add("$slug: $it 이름 없음") }
                if (slug !in ExerciseRequirements.bySlug) add("$slug: 필요 도구 미확인")
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        // 명시한 공백이 실제로 플랜에 쓰이던 동작인지(새로 연 동작을 숨기지 않게)
        assertTrue(preexistingMediaGaps.none { it in opened } && preexistingMediaGaps.all { catalog.getValue(it).isPlanEligible })
    }

    @Test
    @DisplayName("사다리는 같은 동작 계열이고 쉬운 것에서 어려운 것으로 간다(난이도가 줄지 않는다)")
    fun laddersAscend() {
        BodyweightLadder.LADDERS.forEach { (name, steps) ->
            assertTrue(steps.size >= 3, "$name 사다리가 너무 짧다")
            assertEquals(steps.size, steps.toSet().size, "$name 중복 단계")
            val difficulties = steps.map { catalog.getValue(it).difficulty }
            assertTrue(difficulties.zipWithNext().all { (a, b) -> b >= a }, "$name 난이도가 내려간다: ${steps.zip(difficulties)}")
        }
    }

    @Test
    @DisplayName("한 단계 위·아래: 할 수 없는 단계(철봉·낮은 바)는 건너뛰고, 끝이면 없다")
    fun harderAndEasierSkipUnavailableSteps() {
        val bodyweightOnly = setOf("BODYWEIGHT")
        fun canDo(slug: String) = ExerciseRequirements.allows(catalog.getValue(slug), bodyweightOnly)
        assertEquals("archer-push-up", BodyweightLadder.harder("decline-push-up", ::canDo))
        assertEquals("incline-push-up", BodyweightLadder.easier("push-ups-bodyweight", ::canDo))
        assertNull(BodyweightLadder.harder("archer-push-up", ::canDo))
        assertNull(BodyweightLadder.easier("knee-push-up", ::canDo))
        // 기구 없는 사람: 슈퍼맨 풀 위는 인버티드 로우(낮은 바)·턱걸이(철봉)뿐 → 없다
        assertNull(BodyweightLadder.harder("superman-pulls", ::canDo))
        // 철봉이 있으면 낮은 바는 건너뛰고 턱걸이
        val withBar = setOf("BODYWEIGHT", "OTHER")
        assertEquals("pull-up-normal-grip", BodyweightLadder.harder("superman-pulls") { ExerciseRequirements.allows(catalog.getValue(it), withBar) })
        assertNull(BodyweightLadder.ladderOf("dumbbell-bench-press"))
    }
}

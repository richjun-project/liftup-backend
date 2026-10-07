package com.richjun.liftupai.domain.workout.search.rewrite

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectorStore
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import com.richjun.liftupai.domain.workout.search.semantic.QueryVectorSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM 해석이 꺼져 있거나·없거나·실패하거나·느려도 검색은 하이브리드(없으면 어휘) 결과 그대로이고, 순간 장애가 TTL 동안 품질을 깎지 않는다.
 * 해석이 되살아나면(차단기가 닫히면) 다시 쓴다. 네트워크는 쓰지 않는다.
 */
class RewriteFallbackTest {
    private fun exercise(id: Long, slug: String, name: String, category: ExerciseCategory, equipment: Equipment, popularity: Int = 50) = Exercise(
        id = id, slug = slug, name = name, category = category, equipment = equipment,
        muscleGroups = mutableSetOf(MuscleGroup.SHOULDERS), popularity = popularity, recommendationTier = RecommendationTier.STANDARD
    )

    private val exerciseRepository = mockk<ExerciseRepository>()
    private val translationRepository = mockk<ExerciseTranslationRepository>()

    init {
        every { exerciseRepository.findAllWithMuscleGroups() } returns listOf(
            exercise(1, "barbell-full-squat", "Barbell full squat", ExerciseCategory.LEGS, Equipment.BARBELL, 90),
            exercise(2, "pigeon-pose", "Pigeon pose", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, 40),
            exercise(3, "dumbbell-curls", "Dumbbell curls", ExerciseCategory.ARMS, Equipment.DUMBBELL, 70),
            exercise(4, "face-pull", "Face pull", ExerciseCategory.BACK, Equipment.CABLE, 30),
            exercise(5, "external-rotation", "External rotation", ExerciseCategory.SHOULDERS, Equipment.RESISTANCE_BAND, 20)
        )
        every { translationRepository.findAllNameRows() } returns emptyList()
    }

    private fun unit(vararg v: Float): FloatArray = QueryEmbedder.normalize(FloatArray(768).also { a -> v.forEachIndexed { i, x -> a[i] = x } })

    private val vectors = ExerciseVectors.fromFloats(
        listOf("barbell-full-squat", "pigeon-pose", "dumbbell-curls", "face-pull", "external-rotation"),
        listOf(unit(1f), unit(0f, 1f), unit(0f, 0f, 1f), unit(0f, 0f, 0f, 1f), unit(0f, 0f, 0f, 0f, 1f))
    )

    private class FakeSource(override val enabled: Boolean = true, var answer: (String) -> FloatArray? = { null }) : QueryVectorSource {
        val calls = AtomicInteger()
        override fun embed(query: String): FloatArray? { calls.incrementAndGet(); return answer(query) }
    }

    private class FakeRewriter(override val enabled: Boolean = true, var answer: (String) -> QueryRewrite? = { null }) : QueryRewriteSource {
        val calls = AtomicInteger()
        override fun rewrite(query: String): QueryRewrite? { calls.incrementAndGet(); return answer(query) }
    }

    private fun service(rewriter: QueryRewriteSource?, source: QueryVectorSource? = FakeSource(answer = { unit(0f, 1f) }), store: ExerciseVectorStore? = ExerciseVectorStore.of(vectors)) =
        ExerciseSearchIndexService(exerciseRepository, translationRepository, null, null, source, store, rewriter)

    private val sentence = "something to protect my throwing arm all season long"
    private val coach = QueryRewrite(listOf("External rotation", "Face pull"), emptySet())

    private fun ids(s: ExerciseSearchIndexService, q: String = sentence) = s.searchIds(q, null, null, "en")

    // ── 꺼짐/없음 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `해석기가 없으면 하이브리드 결과 그대로`() {
        val baseline = ids(service(null))
        assertEquals(baseline, ids(service(FakeRewriter(enabled = false, answer = { coach }))))
    }

    @Test
    fun `해석기가 꺼져 있으면 부르지 않는다`() {
        val rw = FakeRewriter(enabled = false, answer = { coach })
        ids(service(rw))
        assertEquals(0, rw.calls.get())
    }

    @Test
    fun `해석기가 켜져 있고 힌트가 맞으면 코치 운동이 올라온다`() {
        val plain = ids(service(null))
        val rw = FakeRewriter(answer = { coach })
        val withRw = ids(service(rw))
        assertEquals(1, rw.calls.get())
        assertTrue(withRw.take(2).containsAll(listOf(4L, 5L)), "face-pull(4)·external-rotation(5) 이 위로: $withRw (전: $plain)")
    }

    @Test
    fun `의미 검색이 꺼져 있으면 해석도 쓰지 않는다 - 어휘 검색 그대로`() {
        val rw = FakeRewriter(answer = { coach })
        val svc = service(rw, source = FakeSource(enabled = false))
        val baseline = ids(service(null, source = FakeSource(enabled = false)))
        assertEquals(baseline, ids(svc))
        assertEquals(0, rw.calls.get())
    }

    @Test
    fun `문서 임베딩이 없으면 해석도 쓰지 않는다`() {
        val rw = FakeRewriter(answer = { coach })
        assertEquals(ids(service(null, store = null)), ids(service(rw, store = null)))
        assertEquals(0, rw.calls.get())
    }

    // ── 실패 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `해석이 null 이면 하이브리드 결과 그대로`() {
        val baseline = ids(service(null))
        assertEquals(baseline, ids(service(FakeRewriter(answer = { null }))))
    }

    @Test
    fun `해석이 예외를 던져도 검색은 하이브리드로 계속된다`() {
        val baseline = ids(service(null))
        assertEquals(baseline, ids(service(FakeRewriter(answer = { throw IllegalStateException("llm exploded") }))))
    }

    @Test
    fun `해석이 시간 제한을 넘기면 기다리지 않고 하이브리드로`() {
        val svc = service(FakeRewriter(answer = { Thread.sleep(6000); coach }))
        val started = System.nanoTime()
        val out = ids(svc)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(ids(service(null)), out)
        assertTrue(ms < 5000, "기다림 상한(3초)을 넘지 않는다: ${ms}ms")
    }

    @Test
    fun `질의 임베딩도 해석도 실패하면 어휘 결과 그대로`() {
        val lexicalOnly = ids(service(null, source = null, store = null))
        assertEquals(lexicalOnly, ids(service(FakeRewriter(answer = { null }), source = FakeSource(answer = { null }))))
    }

    @Test
    fun `해석 실패 결과는 오래 캐시하지 않는다 - 되살아나면 다시 쓴다`() {
        val rw = FakeRewriter(answer = { null })
        val svc = service(rw)
        var now = 1_000_000L
        svc.clock = { now }
        val degraded = ids(svc)
        ids(svc)
        assertEquals(1, rw.calls.get(), "잠깐은 캐시된 결과를 쓴다")
        rw.answer = { coach }
        now += 31_000
        val healed = ids(svc)
        assertEquals(2, rw.calls.get())
        assertTrue(healed != degraded, "되살아난 해석이 결과를 바꾼다")
        ids(svc)
        assertEquals(2, rw.calls.get(), "성공한 결과는 오래 캐시한다")
    }

    @Test
    fun `해석에 성공한 결과는 같은 질의에서 다시 부르지 않는다 - 페이지 넘김`() {
        val rw = FakeRewriter(answer = { coach })
        val svc = service(rw)
        ids(svc); ids(svc); ids(svc)
        assertEquals(1, rw.calls.get())
    }

    // ── 하드 조건 우선 ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `질의의 부정은 LLM 힌트보다 우선한다`() {
        val rw = FakeRewriter(answer = { QueryRewrite(listOf("Face pull", "Barbell full squat"), emptySet()) })
        val out = ids(service(rw), "no squats please, something for my shoulders and upper back")
        assertFalse(1L in out.take(3), "스쿼트는 부정했으니 힌트로 되살리지 않는다: $out")
    }

    @Test
    fun `LLM 제외 장비는 걸러 낸다 - 단 결과가 비면 거르지 않는다`() {
        val svc = service(FakeRewriter(answer = { QueryRewrite(listOf("Face pull"), setOf(Equipment.CABLE)) }))
        val out = ids(svc)
        assertFalse(4L in out.take(2), "케이블 제외: $out")
        val all = service(FakeRewriter(answer = { QueryRewrite(emptyList(), Equipment.values().toSet()) }))
        assertTrue(ids(all).isNotEmpty())
    }

    @Test
    fun `정확한 운동 이름은 해석을 부르지 않는다`() {
        val rw = FakeRewriter(answer = { coach })
        val out = ids(service(rw), "Pigeon pose")
        assertEquals(0, rw.calls.get())
        assertEquals(2L, out.first())
    }

    @Test
    fun `질의가 비면 브라우즈 - 해석을 부르지 않는다`() {
        val rw = FakeRewriter(answer = { coach })
        service(rw).searchIds(null, null, null, "en")
        assertEquals(0, rw.calls.get())
    }
}

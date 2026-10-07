package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.ops.SearchLlmGuard
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewriteSource
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectorStore
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import com.richjun.liftupai.domain.workout.search.semantic.QueryVectorSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 같은 (질의·필터·로케일)의 페이지들은 페이지 넘김 구간 안에서 같은 순위 목록에서 나온다 —
 * LLM·임베딩이 중간에 되살아나도, 결과 캐시가 TTL 재빌드를 겪어도 목록이 바뀌지 않는다. 첫 페이지 요청만 degraded 목록을 교체할 수 있다.
 */
class PaginationStabilityTest {
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

    private class FakeSource(var answer: (String) -> FloatArray? = { null }) : QueryVectorSource {
        override val enabled = true
        val calls = AtomicInteger()
        override fun embed(query: String): FloatArray? { calls.incrementAndGet(); return answer(query) }
    }

    private class FakeRewriter(var answer: (String) -> QueryRewrite? = { null }) : QueryRewriteSource {
        override val enabled = true
        val calls = AtomicInteger()
        override fun rewrite(query: String): QueryRewrite? { calls.incrementAndGet(); return answer(query) }
    }

    private val sentence = "something to protect my throwing arm all season long"
    private val coach = QueryRewrite(listOf("External rotation", "Face pull"), emptySet())
    private var now = 1_000_000L
    private val source = FakeSource()
    private val rewriter = FakeRewriter()
    private val guard = SearchLlmGuard()

    private fun service(): ExerciseSearchIndexService =
        ExerciseSearchIndexService(exerciseRepository, translationRepository, null, null, source, ExerciseVectorStore.of(vectors), rewriter, guard).also { it.clock = { now } }

    private fun page(s: ExerciseSearchIndexService, page: Int, q: String = sentence, category: ExerciseCategory? = null) =
        s.searchIds(q, category, null, "en", page = page)

    private fun recover() {
        source.answer = { unit(0f, 1f) }
        rewriter.answer = { coach }
    }

    @Test
    fun `LLM 이 못 되살아난 동안 만든 목록은 30초 뒤 다음 페이지 요청에도 그대로 나간다`() {
        val svc = service()
        val first = page(svc, 0)
        recover()
        now += 31_000
        assertEquals(first, page(svc, 1), "다음 페이지는 같은 목록의 이어지는 조각이어야 한다")
        now += 120_000
        assertEquals(first, page(svc, 2))
        assertEquals(1, source.calls.get(), "다음 페이지는 임베딩을 다시 부르지 않는다")
        assertEquals(1, rewriter.calls.get())
    }

    @Test
    fun `첫 페이지 요청만 degraded 목록을 교체하고 그 뒤 페이지는 새 목록을 이어 간다`() {
        val svc = service()
        val degraded = page(svc, 0)
        recover()
        now += 31_000
        val healed = page(svc, 0)
        assertNotEquals(degraded, healed, "되살아난 LLM 이 첫 페이지에서 목록을 바꾼다")
        now += 5_000
        assertEquals(healed, page(svc, 1), "교체 뒤의 페이지는 새 목록에서")
        assertEquals(healed, page(svc, 2))
    }

    @Test
    fun `첫 페이지도 30초 안에는 degraded 목록을 그대로 쓴다`() {
        val svc = service()
        val degraded = page(svc, 0)
        recover()
        now += 10_000
        assertEquals(degraded, page(svc, 0))
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `페이지 넘김 구간(5분)이 지나면 다음 페이지 요청도 다시 계산할 수 있다`() {
        val svc = service()
        val degraded = page(svc, 0)
        recover()
        now += 6 * 60_000L
        assertNotEquals(degraded, page(svc, 1))
    }

    @Test
    fun `완전한 목록은 인덱스 TTL 재빌드를 지나도 같은 페이지 목록을 낸다`() {
        recover()
        val svc = service()
        svc.ttlMillis = 1_000
        val full = page(svc, 0)
        val builds = svc.buildCount.get()
        now += 2_000
        page(svc, 1)  // TTL 이 지나 백그라운드 재빌드가 시작된다
        val deadline = System.currentTimeMillis() + 10_000
        while (svc.buildCount.get() == builds && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(svc.buildCount.get() > builds, "재빌드가 일어났다")
        // 재빌드 직후 LLM 이 달라져도(여기서는 끔) 같은 질의의 다음 페이지는 같은 목록
        source.answer = { null }; rewriter.answer = { null }
        now += 1_000
        assertEquals(full, page(svc, 2))
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `카탈로그가 바뀌면 캐시를 비운다 - 낡은 목록을 내지 않는다`() {
        recover()
        val svc = service()
        page(svc, 0)
        assertTrue(svc.cachedResultCount() > 0)
        svc.invalidate()
        page(svc, 1)  // 새 스냅샷에서 다시 계산
        assertEquals(2, source.calls.get())
    }

    @Test
    fun `질의나 필터가 다르면 서로의 목록을 건드리지 않는다`() {
        val svc = service()
        val a = page(svc, 0)
        val legs = page(svc, 0, category = ExerciseCategory.LEGS)
        recover()
        now += 31_000
        assertEquals(a, page(svc, 1))
        assertEquals(legs, page(svc, 1, category = ExerciseCategory.LEGS))
    }

    @Test
    fun `degraded 목록을 이어 쓰는 횟수는 지표에 남고 질의는 남지 않는다`() {
        val svc = service()
        page(svc, 0)
        recover()
        now += 31_000
        page(svc, 1)
        val line = guard.summary(reset = false)
        assertTrue(line.contains("pagedReplay=1"), line)
        assertTrue(!line.contains("throwing arm"), "질의 문자열은 로그에 없다")
    }
}

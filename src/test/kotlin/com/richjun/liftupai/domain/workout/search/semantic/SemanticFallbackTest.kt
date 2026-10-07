package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 의미 검색이 꺼져 있거나·임베딩이 없거나·API 가 실패해도 검색은 어휘 검색만으로 오늘과 똑같이 동작한다.
 * 의미 검색이 되살아나면(차단기가 닫히면) 다시 쓰고, 순간 장애가 TTL 동안 결과를 낮추지 않는다. 네트워크는 쓰지 않는다.
 */
class SemanticFallbackTest {

    private fun exercise(id: Long, slug: String, name: String, category: ExerciseCategory, equipment: Equipment, popularity: Int = 50) = Exercise(
        id = id, slug = slug, name = name, category = category, equipment = equipment,
        muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS), popularity = popularity, recommendationTier = RecommendationTier.STANDARD
    )

    private val exerciseRepository = mockk<ExerciseRepository>()
    private val translationRepository = mockk<ExerciseTranslationRepository>()

    init {
        every { exerciseRepository.findAllWithMuscleGroups() } returns listOf(
            exercise(1, "barbell-full-squat", "Barbell full squat", ExerciseCategory.LEGS, Equipment.BARBELL, 90),
            exercise(2, "pigeon-pose", "Pigeon pose", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, 40),
            exercise(3, "dumbbell-curls", "Dumbbell curls", ExerciseCategory.ARMS, Equipment.DUMBBELL, 70)
        )
        every { translationRepository.findAllNameRows() } returns emptyList()
    }

    private fun unit(vararg v: Float): FloatArray = QueryEmbedder.normalize(FloatArray(768).also { a -> v.forEachIndexed { i, x -> a[i] = x } })

    private val vectors = ExerciseVectors.fromFloats(listOf("barbell-full-squat", "pigeon-pose", "dumbbell-curls"), listOf(unit(1f), unit(0f, 1f), unit(0f, 0f, 1f)))

    private class FakeSource(override val enabled: Boolean = true, var answer: (String) -> FloatArray? = { null }) : QueryVectorSource {
        val calls = AtomicInteger()
        override fun embed(query: String): FloatArray? { calls.incrementAndGet(); return answer(query) }
    }

    private fun service(source: QueryVectorSource?, store: ExerciseVectorStore? = ExerciseVectorStore.of(vectors)) =
        ExerciseSearchIndexService(exerciseRepository, translationRepository, null, null, source, store)

    private val plain = ExerciseSearchIndexService(exerciseRepository, translationRepository)
    private val sentence = "something to stretch out my tight hips after a long day"

    // ── 꺼짐/없음 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `질의 임베더가 없으면 어휘 검색과 같다`() {
        assertEquals(plain.searchIds(sentence, null, null, "en"), service(null).searchIds(sentence, null, null, "en"))
    }

    @Test
    fun `설정이 꺼져 있으면 임베딩 리소스도 건드리지 않는다`() {
        val store = mockk<ExerciseVectorStore>()
        val source = FakeSource(enabled = false)
        val s = service(source, store)
        assertEquals(plain.searchIds(sentence, null, null, "en"), s.searchIds(sentence, null, null, "en"))
        verify(exactly = 0) { store.get() }
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `임베딩 리소스가 없으면 어휘 검색만 - 임베더도 부르지 않는다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source, ExerciseVectorStore.of(null))
        assertEquals(plain.searchIds(sentence, null, null, "en"), s.searchIds(sentence, null, null, "en"))
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `임베딩 리소스를 읽다 예외가 나도 어휘 검색으로 동작한다`() {
        val store = mockk<ExerciseVectorStore>()
        every { store.get() } throws IllegalStateException("broken resource")
        val s = service(FakeSource { unit(0f, 1f) }, store)
        assertEquals(plain.searchIds(sentence, null, null, "en"), s.searchIds(sentence, null, null, "en"))
    }

    // ── API 장애 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `질의 임베딩이 실패하면 어휘 결과와 같다`() {
        val s = service(FakeSource { null })
        assertEquals(plain.searchIds(sentence, null, null, "en"), s.searchIds(sentence, null, null, "en"))
    }

    @Test
    fun `임베더가 예외를 던져도 어휘 결과로 계속된다`() {
        val s = service(FakeSource { throw IllegalStateException("boom") })
        assertEquals(plain.searchIds(sentence, null, null, "en"), s.searchIds(sentence, null, null, "en"))
    }

    @Test
    fun `실제 QueryEmbedder 가 계속 실패해도 검색은 정상이고 차단기가 호출을 멈춘다`() {
        val calls = AtomicInteger()
        val embedder = QueryEmbedder(true, "k", 1200, 100, 2, 60_000, EmbeddingTransport { _, _ -> calls.incrementAndGet(); throw java.io.IOException("down") })
        val s = service(embedder)
        for (i in 0 until 6) {
            val q = "$sentence $i"
            assertEquals(plain.searchIds(q, null, null, "en"), s.searchIds(q, null, null, "en"), q)
        }
        assertEquals(2, calls.get(), "차단기가 열린 뒤에는 전송을 부르지 않는다")
        assertTrue(embedder.breakerOpen())
    }

    @Test
    fun `벡터를 못 구한 결과는 잠깐만 캐시하고 장애가 풀리면 의미 검색이 다시 쓰인다`() {
        var now = 1_000_000L
        var healthy = false
        val source = FakeSource { if (healthy) unit(0f, 1f) else null }
        val s = service(source)
        s.clock = { now }
        val degraded = s.searchIds(sentence, null, null, "en")
        assertEquals(plain.searchIds(sentence, null, null, "en"), degraded)
        assertEquals(1, source.calls.get())
        // 곧바로 같은 질의(페이지 넘김) — 캐시에서, 벡터를 다시 구하지 않는다
        assertEquals(degraded, s.searchIds(sentence, null, null, "en"))
        assertEquals(1, source.calls.get())
        // 장애가 풀리고 시간이 지나면 다시 시도해서 의미 결과가 들어온다
        healthy = true
        now += 31_000
        val recovered = s.searchIds(sentence, null, null, "en")
        assertEquals(2, source.calls.get())
        assertEquals(2L, recovered.first(), "의미 1위(pigeon-pose)가 앞으로")
        assertNotEquals(degraded, recovered)
        // 이제는 성공한 결과이므로 오래 캐시된다
        now += 120_000
        assertEquals(recovered, s.searchIds(sentence, null, null, "en"))
        assertEquals(2, source.calls.get())
    }

    // ── 정상 경로 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `의미 검색이 켜져 있으면 어휘가 못 찾은 운동이 나온다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source)
        assertTrue(plain.searchIds(sentence, null, null, "en").isEmpty() || plain.searchIds(sentence, null, null, "en").first() != 2L)
        assertEquals(2L, s.searchIds(sentence, null, null, "en").first())
    }

    @Test
    fun `성공한 질의 임베딩은 같은 질의에서 한 번만 쓰고 결과는 캐시된다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source)
        val first = s.searchIds(sentence, null, null, "en")
        repeat(3) { assertEquals(first, s.searchIds(sentence, null, null, "en")) }
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `짧은 키워드 질의는 임베더를 부르지 않고 어휘 결과와 같다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source)
        assertEquals(plain.searchIds("squat", null, null, "en"), s.searchIds("squat", null, null, "en"))
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `브라우즈(질의 없음)는 임베더를 부르지 않는다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source)
        assertEquals(plain.searchIds(null, ExerciseCategory.LEGS, null, "en"), s.searchIds(null, ExerciseCategory.LEGS, null, "en"))
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `의미 결과에도 카테고리 필터와 장비 필터가 적용된다`() {
        val s = service(FakeSource { unit(0f, 1f) })
        val legs = s.searchIds(sentence, ExerciseCategory.ARMS, null, "en")
        assertTrue(legs.all { it == 3L })
        val dumbbell = s.searchIds(sentence, null, setOf(Equipment.DUMBBELL), "en")
        assertTrue(dumbbell.all { it == 3L })
    }

    @Test
    fun `필터가 다르면 결과 캐시도 따로다`() {
        val source = FakeSource { unit(0f, 1f) }
        val s = service(source)
        val all = s.searchIds(sentence, null, null, "en")
        val arms = s.searchIds(sentence, ExerciseCategory.ARMS, null, "en")
        assertNotEquals(all, arms)
        assertEquals(all, s.searchIds(sentence, null, null, "en"))
    }

    @Test
    fun `의미 검색을 켜도 카탈로그 변경 이벤트로 인덱스를 다시 만들면 새 하이브리드가 쓰인다`() {
        val s = service(FakeSource { unit(0f, 1f) })
        s.searchIds(sentence, null, null, "en")
        s.invalidate()
        assertEquals(2L, s.searchIds(sentence, null, null, "en").first())
        verify(exactly = 2) { exerciseRepository.findAllWithMuscleGroups() }
    }

    @Test
    fun `0건 대안 경로는 의미 검색과 무관하게 어휘로 동작한다`() {
        val s = service(FakeSource { unit(0f, 1f) })
        assertEquals(plain.fallbackIds("squat curls", null, null, "en"), s.fallbackIds("squat curls", null, null, "en"))
    }

    @Test
    fun `의미 검색 결과에 같은 운동이 두 번 나오지 않는다`() {
        val s = service(FakeSource { unit(1f, 1f) })
        val ids = s.searchIds(sentence, null, null, "en")
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.isEmpty())
    }
}

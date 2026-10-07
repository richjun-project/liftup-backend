package com.richjun.liftupai.domain.workout.search.rewrite

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import com.richjun.liftupai.domain.workout.search.ExerciseSearchHit
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM 해석 융합 — 순수 RRF 규칙([RewriteFusion])과, 하이브리드 검색 안에서의 동작(힌트 검색·하드 조건 우선·장비 제외·건너뛰기).
 * 가짜 해석(고정 힌트)과 가짜 질의 벡터를 쓴다. 네트워크는 쓰지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RewriteFusionTest {
    // ── 순수 융합 ─────────────────────────────────────────────────────────────────────────────

    private fun doc(id: Long, equipment: Equipment? = Equipment.BODYWEIGHT) = ExerciseSearchDoc(
        id = id, slug = "ex-$id", names = mapOf("en" to "Ex $id"), category = ExerciseCategory.CHEST, equipment = equipment,
        muscleGroups = emptySet(), movementPattern = null, tier = RecommendationTier.STANDARD, popularity = 50, difficulty = 50
    )

    private fun hits(vararg d: ExerciseSearchDoc) = d.mapIndexed { i, x -> ExerciseSearchHit(x, 100.0 - i) }
    private fun ids(l: List<ExerciseSearchHit>) = l.map { it.doc.id }
    private val a = doc(1); private val b = doc(2); private val c = doc(3); private val d = doc(4); private val e = doc(5)

    @Test
    fun `힌트가 없으면 하이브리드 순서 그대로`() {
        val base = hits(a, b, c)
        assertEquals(ids(base), ids(RewriteFusion.fuse(base, emptyList(), emptySet())))
    }

    @Test
    fun `여러 곳이 가리키는 운동이 앞선다 - 하이브리드 3위가 두 힌트에 있으면 1위`() {
        val base = hits(a, b, c)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(c)), RewriteFusion.HintHits(hits(c))), emptySet())
        assertEquals(3L, out.first().doc.id)
    }

    @Test
    fun `하이브리드에 없던 힌트 후보도 들어온다`() {
        val base = hits(a, b)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(d, e))), emptySet())
        assertTrue(ids(out).containsAll(listOf(4L, 5L)))
    }

    @Test
    fun `힌트 후보는 힌트당 상위 perHint 개만 쓴다`() {
        val base = hits(a)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(b, c, d, e))), emptySet(), config = RewriteFusion.Config(perHint = 2))
        assertTrue(ids(out).containsAll(listOf(2L, 3L)))
        assertFalse(4L in ids(out))
    }

    @Test
    fun `앞선 힌트일수록 더 믿는다 - 감쇠`() {
        val base = hits(a)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(b)), RewriteFusion.HintHits(hits(c))), emptySet())
        assertTrue(ids(out).indexOf(2L) < ids(out).indexOf(3L))
    }

    @Test
    fun `정확한 이름 힌트는 첫 후보에 배율을 곱한다`() {
        val base = hits(a, b, c, d)
        val plain = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(e), exact = false)), emptySet(), config = RewriteFusion.Config(exactBoost = 1.0))
        val boosted = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(e), exact = true)), emptySet(), config = RewriteFusion.Config(exactBoost = 3.0))
        assertTrue(ids(boosted).indexOf(5L) < ids(plain).indexOf(5L), "정확한 이름이면 더 앞으로: ${ids(plain)} vs ${ids(boosted)}")
        assertEquals(5L, boosted.first().doc.id)
    }

    @Test
    fun `정확한 이름 배율은 첫 후보에만 - 이웃 후보는 그대로`() {
        val base = hits(a)
        val x = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(b, c), exact = true)), emptySet(), config = RewriteFusion.Config(exactBoost = 1.0))
        val y = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(b, c), exact = true)), emptySet(), config = RewriteFusion.Config(exactBoost = 5.0))
        val scoreC1 = x.first { it.doc.id == 3L }.score
        val scoreC2 = y.first { it.doc.id == 3L }.score
        assertEquals(scoreC1, scoreC2, 1e-9)
    }

    @Test
    fun `고정한 운동은 무엇이 오든 1위`() {
        val base = hits(a, b)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(c), exact = true), RewriteFusion.HintHits(hits(c))), emptySet(), pinnedId = 1L)
        assertEquals(1L, out.first().doc.id)
    }

    @Test
    fun `제외 장비는 결과 전체에서 거른다`() {
        val bb = doc(10, Equipment.BARBELL); val cb = doc(11, Equipment.CABLE)
        val base = hits(bb, a, cb, b)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(cb))), setOf(Equipment.BARBELL, Equipment.CABLE))
        assertEquals(listOf(1L, 2L), ids(out))
    }

    @Test
    fun `제외 장비로 걸러 아무것도 안 남으면 거르지 않는다 - 빈 결과를 만들지 않는다`() {
        val bb = doc(10, Equipment.BARBELL); val bb2 = doc(11, Equipment.BARBELL)
        val out = RewriteFusion.fuse(hits(bb, bb2), emptyList(), setOf(Equipment.BARBELL))
        assertEquals(listOf(10L, 11L), ids(out))
    }

    @Test
    fun `장비 정보가 없는 운동은 제외 장비에 걸리지 않는다`() {
        val none = doc(20, null)
        val out = RewriteFusion.fuse(hits(none, a), emptyList(), setOf(Equipment.BARBELL))
        assertEquals(listOf(20L, 1L), ids(out))
    }

    @Test
    fun `하이브리드 결과의 나머지가 원래 순서로 뒤에 붙는다 - 길이가 줄지 않는다`() {
        val many = (1L..30L).map { doc(it) }
        val base = hits(*many.toTypedArray())
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(doc(99)))), emptySet())
        assertEquals(31, out.size)
        assertEquals(ids(base).drop(10), ids(out).filter { it > 10L && it != 99L })
        assertEquals(out.size, ids(out).toSet().size, "중복 없음")
    }

    @Test
    fun `점수는 순서를 그대로 반영하도록 단조 감소`() {
        val out = RewriteFusion.fuse(hits(a, b, c, d), listOf(RewriteFusion.HintHits(hits(e, c))), emptySet())
        out.zipWithNext().forEach { (x, y) -> assertTrue(x.score >= y.score, "${x.score} < ${y.score}") }
    }

    @Test
    fun `같은 입력은 같은 결과 - 결정적`() {
        val base = hits(a, b, c, d)
        val hints = listOf(RewriteFusion.HintHits(hits(e, c)), RewriteFusion.HintHits(hits(d, a), exact = true))
        assertEquals(ids(RewriteFusion.fuse(base, hints, emptySet())), ids(RewriteFusion.fuse(base, hints, emptySet())))
    }

    @Test
    fun `동점이면 하이브리드 순위, 그다음 id 순`() {
        val base = hits(b, a)
        val out = RewriteFusion.fuse(base, listOf(RewriteFusion.HintHits(hits(c, d)), RewriteFusion.HintHits(hits(d, c))), emptySet(), config = RewriteFusion.Config(decay = 1.0, baseWeight = 0.0))
        // c 와 d 는 같은 점수 — id 가 작은 쪽이 먼저
        assertTrue(ids(out).indexOf(3L) < ids(out).indexOf(4L))
    }

    // ── 하이브리드 검색 안에서 ─────────────────────────────────────────────────────────────────

    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine
    private lateinit var vectors: ExerciseVectors
    private lateinit var hybrid: HybridSearch

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        // 시간 예산(운영 40ms)을 넉넉히 잡는다 — 테스트 JVM 의 첫 질의(클래스 로딩·JIT)가 예산을 넘겨 불완전 결과가 되면 융합이 건너뛰어져 결과가 흔들린다
        engine = ExerciseSearchEngine(docs, emptyList(), 60_000_000_000L)
        vectors = ExerciseVectors.loadDefault() ?: error("embeddings missing")
        hybrid = HybridSearch(engine, vectors)
    }

    private fun like(slug: String): FloatArray = QueryEmbedder.normalize(vectors.vector(slug) ?: error("no vector for $slug"))

    private fun search(
        q: String, rw: QueryRewrite?, near: String? = null, category: ExerciseCategory? = null, equipments: Set<Equipment>? = null,
        h: HybridSearch = hybrid, calls: AtomicInteger? = null
    ) = h.search(q, category, equipments, "en", { calls?.incrementAndGet(); rw }) { near?.let { like(it) } }

    private fun slugs(r: HybridSearch.Result, n: Int = 10) = r.hits.take(n).map { it.doc.slug }
    private fun rw(vararg hints: String, forbid: Set<Equipment> = emptySet()) = QueryRewrite(hints.toList(), forbid)

    @Test
    fun `코치 힌트가 어휘·의미가 못 찾은 운동을 끌어올린다`() {
        val q = "something to protect my throwing arm during the season"
        val without = search(q, null)
        val with = search(q, rw("External Rotation", "Face Pull"))
        assertTrue(with.rewritten)
        assertFalse(without.rewritten)
        val top5 = slugs(with, 5)
        assertTrue(top5.any { "external-rotation" in it || "face-pull" in it }, top5.toString())
    }

    @Test
    fun `힌트가 가리킨 운동이 상위 10에 든다`() {
        val r = search("a move that makes my back look like a V shape", rw("Lat Pulldown", "Wide Grip Pull-Up"))
        assertTrue(slugs(r, 10).any { "pull-down" in it || "pull-up" in it }, "complete=${r.complete} rewritten=${r.rewritten} sem=${r.semantic} deg=${r.degraded} " + slugs(r, 10).toString())
    }

    @Test
    fun `해석이 없으면 하이브리드 결과와 정확히 같다`() {
        val q = "something to protect my throwing arm during the season"
        val a = search(q, null, near = "cable-face-pull")
        val b = hybrid.search(q, null, null, "en") { like("cable-face-pull") }
        assertEquals(slugs(b, 30), slugs(a, 30))
        assertEquals(b.semantic, a.semantic)
    }

    @Test
    fun `빈 해석은 결과를 바꾸지 않는다`() {
        val q = "something to protect my throwing arm during the season"
        val a = search(q, QueryRewrite.EMPTY)
        val b = search(q, null)
        assertEquals(slugs(b, 30), slugs(a, 30))
        assertFalse(a.rewritten)
    }

    @Test
    fun `부정한 운동은 LLM 이 힌트로 줘도 결과에 못 들어온다`() {
        val q = "leg workout without deadlifts please"
        val r = search(q, rw("Barbell Deadlift", "Romanian Deadlift", "Sumo Deadlift", "Squat"))
        assertTrue(slugs(r, 10).none { "deadlift" in it }, slugs(r, 10).toString())
    }

    @Test
    fun `부정한 장비도 힌트로 못 되살린다`() {
        val q = "chest workout but no machines at all"
        val r = search(q, rw("Chest Press Machine", "Pec Deck", "Push-up"))
        assertTrue(r.hits.take(10).none { it.doc.equipment == Equipment.MACHINE }, slugs(r, 10).toString())
    }

    @Test
    fun `부상 조건의 금지 동작은 LLM 힌트로 못 되살린다`() {
        val q = "my knees are bad, what leg exercises can I do"
        val r = search(q, rw("Box Jump", "Pistol Squat", "Jump Squat", "Sissy Squat"))
        assertTrue(slugs(r, 5).none { Regex("jump|pistol|sissy|plyo").containsMatchIn(it) }, slugs(r, 5).toString())
    }

    @Test
    fun `호출자의 장비 필터는 힌트 후보에도 적용된다`() {
        val r = search("good chest exercise for a beginner please", rw("Barbell Bench Press", "Push-up", "Dumbbell Fly"), equipments = setOf(Equipment.DUMBBELL))
        assertTrue(r.hits.take(10).all { it.doc.equipment == Equipment.DUMBBELL }, slugs(r, 10).toString())
    }

    @Test
    fun `호출자의 카테고리 필터는 힌트 후보에도 적용된다`() {
        val r = search("good exercise for a beginner please", rw("Squat", "Bench Press", "Plank"), category = ExerciseCategory.CHEST)
        assertTrue(r.hits.take(10).all { it.doc.category == ExerciseCategory.CHEST }, slugs(r, 10).toString())
    }

    @Test
    fun `LLM 제외 장비는 상위 결과에서 걸러진다`() {
        val q = "chest exercises I can do at the gym today"
        val base = search(q, rw("Barbell Bench Press", "Dumbbell Bench Press", "Push-up"))
        val r = search(q, rw("Barbell Bench Press", "Dumbbell Bench Press", "Push-up", forbid = setOf(Equipment.BARBELL)))
        assertTrue(base.hits.take(10).any { it.doc.equipment == Equipment.BARBELL }, "전제: 거르기 전에는 바벨이 상위에 있다")
        assertTrue(r.hits.take(10).none { it.doc.equipment == Equipment.BARBELL }, slugs(r, 10).toString())
    }

    @Test
    fun `정확한 운동 이름 질의는 LLM 을 부르지 않는다`() {
        val calls = AtomicInteger()
        val r = search("Barbell Bench Press", rw("Push-up"), calls = calls)
        assertEquals(0, calls.get())
        assertFalse(r.rewritten)
    }

    @Test
    fun `질의가 비면 브라우즈 - LLM 을 부르지 않는다`() {
        val calls = AtomicInteger()
        val r = search("", rw("Push-up"), calls = calls)
        assertEquals(0, calls.get())
        assertFalse(r.rewritten)
    }

    @Test
    fun `사전이 다 알아들은 짧은 정식 이름 질의는 건너뛴다 - 설정으로 끌 수 있다`() {
        val calls = AtomicInteger()
        search("squat", rw("Push-up"), calls = calls)
        assertEquals(0, calls.get(), "squat: 어휘 1위가 정확·단어 경계 이름 일치")
        val always = HybridSearch(engine, vectors, HybridSearch.Config(rewriteSkipScore = null))
        search("squat", rw("Push-up"), h = always, calls = calls)
        assertEquals(1, calls.get())
    }

    @Test
    fun `자연어 질의는 부른다`() {
        val calls = AtomicInteger()
        search("something to protect my throwing arm during the season", rw("Face Pull"), calls = calls)
        assertEquals(1, calls.get())
    }

    @Test
    fun `해석 호출 예외는 하이브리드 결과로 흡수한다`() {
        val q = "something to protect my throwing arm during the season"
        val r = hybrid.search(q, null, null, "en", { throw IllegalStateException("llm down") }) { like("cable-face-pull") }
        assertTrue(r.semantic, "semantic")
        assertFalse(r.rewritten, "rewritten")
        assertTrue(r.degraded, "degraded")
    }

    @Test
    fun `해석이 null 이면 degraded - 잠깐만 캐시하도록`() {
        val q = "something to protect my throwing arm during the season"
        val r = search(q, null, near = "cable-face-pull")
        assertTrue(r.degraded)
        val ok = search(q, rw("Face Pull"), near = "cable-face-pull")
        assertFalse(ok.degraded)
    }

    @Test
    fun `해석 없이도 되는 질의에서 해석 null 은 degraded 가 아니다`() {
        val r = hybrid.search("squat", null, null, "en", null) { null }
        assertFalse(r.degraded)
    }

    @Test
    fun `엔진 이름 판별 - 어느 로케일의 정식 이름이든`() {
        assertTrue(engine.isExactName("Barbell Bench Press"))
        assertTrue(engine.isExactName("  barbell bench press "))
        assertFalse(engine.isExactName("something unusual to do"))
        assertFalse(engine.isExactName(""))
    }

    @Test
    fun `질의가 부정과 군더더기뿐이면 어휘 순위를 믿지 않는다`() {
        val c = engine.semanticConstraint("no ab wheel, similar effect")
        assertNotNull(c)
        assertFalse(c!!.lexicalInformative)
        assertTrue(engine.semanticConstraint("chest workout with dumbbells")!!.lexicalInformative)
        assertNull(engine.semanticConstraint("Barbell Bench Press"))
    }
}

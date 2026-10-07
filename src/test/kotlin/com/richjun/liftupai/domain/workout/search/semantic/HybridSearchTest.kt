package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
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
 * 하이브리드 검색 — 어휘 결과와 의미 결과를 RRF 로 섞되, 어휘 엔진과 같은 하드 조건(필터·부정·부상·장비 의도)을 의미 후보에도 적용한다.
 * 가짜 질의 벡터(= 어떤 운동의 문서 벡터)로 "의미상 가장 가까운 운동이 금지 조건에 걸려 있어도 결과에 못 들어온다"를 확인한다. 네트워크는 쓰지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchTest {
    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine
    private lateinit var vectors: ExerciseVectors
    private lateinit var hybrid: HybridSearch

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        engine = ExerciseSearchEngine(docs)
        vectors = ExerciseVectors.loadDefault() ?: error("embeddings missing")
        hybrid = HybridSearch(engine, vectors)
    }

    /** 그 운동의 문서 벡터를 질의 벡터로 쓴다 — "의미상 이 운동과 가장 가깝다"는 상황을 만든다 */
    private fun like(slug: String): FloatArray = QueryEmbedder.normalize(vectors.vector(slug) ?: error("no vector for $slug"))

    private fun search(
        q: String, near: String?, category: ExerciseCategory? = null, equipments: Set<Equipment>? = null,
        h: HybridSearch = hybrid
    ) = h.search(q, category, equipments, "en") { near?.let { like(it) } }

    private fun slugs(r: HybridSearch.Result, n: Int = 10) = r.hits.take(n).map { it.doc.slug }

    // ── 융합: 의미 검색이 어휘가 놓친 운동을 끌어올린다 ───────────────────────────────────────

    @Test
    fun `의미상 가까운 운동이 어휘 검색이 못 찾은 질의에서도 올라온다`() {
        val r = search("something gentle to unwind my tight hips after sitting all day", near = "pigeon-pose")
        assertTrue(r.semantic)
        assertTrue("pigeon-pose" in slugs(r, 5), slugs(r).toString())
    }

    @Test
    fun `어휘와 의미가 모두 가리키는 운동이 1위`() {
        // 어휘 검색이 이미 찾는 문장에서, 의미 1위가 어휘 결과 안에 있으면 둘의 합이 가장 큰 그 운동이 앞선다
        val lexical = engine.search("good back exercises with dumbbells for beginners", null, null, "en")
        val pick = lexical[2].doc.slug
        val r = search("good back exercises with dumbbells for beginners", near = pick)
        assertTrue(r.semantic)
        assertEquals(pick, r.hits.first().doc.slug)
    }

    @Test
    fun `어휘 결과 전체가 뒤에 이어 붙는다 - 길이와 구성원이 어휘 검색보다 줄지 않는다`() {
        val q = "good back exercises with dumbbells for beginners"
        val lexical = engine.search(q, null, null, "en").map { it.doc.id }
        val r = search(q, near = "pigeon-pose")
        val ids = r.hits.map { it.doc.id }
        assertTrue(ids.containsAll(lexical))
        assertEquals(ids.size, ids.toSet().size, "중복 없음")
        // 어휘에만 있던 운동은 어휘 순서를 유지한 채 뒤쪽에 남는다
        val tailOrder = ids.filter { it in lexical.toSet() }
        assertEquals(lexical.size, tailOrder.size)
    }

    @Test
    fun `점수는 순서와 일치하는 단조 감소다`() {
        val r = search("good back exercises with dumbbells for beginners", near = "pigeon-pose")
        r.hits.zipWithNext().forEach { (a, b) -> assertTrue(a.score >= b.score, "${a.doc.slug} ${a.score} < ${b.doc.slug} ${b.score}") }
    }

    @Test
    fun `같은 입력이면 같은 결과다`() {
        val q = "무릎이 안 좋은데 하체 운동 추천해줘"
        assertEquals(slugs(search(q, "barbell-full-squat"), 50), slugs(search(q, "barbell-full-squat"), 50))
    }

    // ── 하드 조건 공유 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `부정한 운동은 의미상 가장 가까워도 결과에 없다 - deadlift`() {
        val r = search("leg workout without deadlifts please", near = "barbell-deadlift")
        assertTrue(r.semantic)
        assertTrue(r.hits.none { it.doc.slug.contains("deadlift") }, slugs(r, 30).toString())
    }

    @Test
    fun `부정한 장비는 결과에 없다 - 바벨 없이`() {
        val r = search("바벨 없이 하체 운동하고 싶어요", near = "barbell-full-squat")
        assertTrue(r.semantic)
        assertTrue(r.hits.take(30).none { it.doc.equipment == Equipment.BARBELL }, slugs(r, 30).toString())
    }

    @Test
    fun `기계를 쓰지 않는다는 일본어 표현도 머신을 뺀다`() {
        val r = search("機械を使わずにお尻を鍛えたい", near = "glute-kickback-machine")
        assertTrue(r.hits.take(30).none { it.doc.equipment == Equipment.MACHINE }, slugs(r, 30).toString())
    }

    @Test
    fun `기구 없이는 맨몸만 - 의미 1위가 머신이어도`() {
        val r = search("기구 없이 가슴운동 하고 싶어", near = "chest-press-machine")
        assertTrue(r.hits.take(10).all { it.doc.equipment == Equipment.BODYWEIGHT }, slugs(r).toString())
    }

    @Test
    fun `벽만 쓰는 운동은 바벨 머신 케이블 덤벨이 없다`() {
        val r = search("legs using only a wall", near = "dumbbell-single-leg-calf-raise-wall-support")
        val bad = setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE, Equipment.DUMBBELL)
        assertTrue(r.hits.take(10).none { it.doc.equipment in bad }, slugs(r).toString())
    }

    @Test
    fun `벤치가 없으면 바벨 머신 케이블을 뺀다`() {
        val r = search("floor chest workout no bench", near = "barbell-floor-chest-press")
        val bad = setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE)
        assertTrue(r.hits.take(10).none { it.doc.equipment in bad }, slugs(r).toString())
    }

    @Test
    fun `점프 없는 유산소 - 점프류는 의미 후보에서도 빠진다`() {
        val r = search("cardio without any jumping for apartment", near = "jumping-jack")
        val re = Regex("jump|burpee|jack|skip|plyo|hop")
        assertTrue(r.hits.take(10).none { re.containsMatchIn(it.doc.slug) }, slugs(r).toString())
    }

    @Test
    fun `부상 관절의 금지 동작은 의미 1위여도 못 들어온다 - 무릎`() {
        val r = search("my knees hurt what leg workout can I do", near = "box-jump")
        val re = Regex("kneel|jump|pistol|sissy|plyo|lunge-jump")
        assertTrue(r.hits.take(10).none { re.containsMatchIn(it.doc.slug) }, slugs(r).toString())
    }

    @Test
    fun `호출자가 준 카테고리 필터는 의미 후보에도 적용된다`() {
        val r = search("good exercises to build strong muscles", near = "barbell-full-squat", category = ExerciseCategory.CHEST)
        assertTrue(r.hits.isNotEmpty())
        assertTrue(r.hits.all { it.doc.category == ExerciseCategory.CHEST })
    }

    @Test
    fun `호출자가 준 장비 필터는 의미 후보에도 적용된다`() {
        val r = search("good exercises to build strong muscles", near = "barbell-full-squat", equipments = setOf(Equipment.DUMBBELL))
        assertTrue(r.hits.isNotEmpty())
        assertTrue(r.hits.all { it.doc.equipment == Equipment.DUMBBELL })
    }

    @Test
    fun `부정한 부위는 의미 후보에서도 빠진다 - 가슴 말고`() {
        val r = search("가슴 말고 하체 운동 추천해줘", near = "barbell-bench-press")
        assertTrue(r.hits.take(10).none { it.doc.category == ExerciseCategory.CHEST }, slugs(r).toString())
    }

    @Test
    fun `제약 객체 - 부정한 운동은 allows 가 false`() {
        val c = engine.semanticConstraint("legs without deadlifts please", null, null)
        assertNotNull(c)
        assertFalse(c!!.allows(docs.first { it.slug == "barbell-deadlift" }))
        assertTrue(c.allows(docs.first { it.slug == "barbell-full-squat" }))
        assertTrue(c.intent)
    }

    @Test
    fun `제약 객체 - 정확한 이름·브라우즈·초성은 의미 검색 대상이 아니다`() {
        assertNull(engine.semanticConstraint("Barbell full squat"))
        assertNull(engine.semanticConstraint(null))
        assertNull(engine.semanticConstraint("   "))
        assertNull(engine.semanticConstraint("ㅅㅋㅌ"))
        assertNull(engine.semanticConstraint("😀😀"))
    }

    // ── 언제 의미 검색을 부르는가 ────────────────────────────────────────────────────────────

    private fun calls(q: String, c: ExerciseCategory? = null): Int {
        val n = AtomicInteger()
        hybrid.search(q, c, null, "en") { n.incrementAndGet(); like("barbell-full-squat") }
        return n.get()
    }

    @Test
    fun `짧고 사전이 다 아는 키워드 질의는 의미 검색을 부르지 않는다`() {
        for (q in listOf("squat", "스쿼트", "ヒップスラスト", "hamstring", "ダンベル 背中", "bench press", "lats")) assertEquals(0, calls(q), q)
    }

    @Test
    fun `정확한 운동 이름·브라우즈·초성은 의미 검색을 부르지 않는다`() {
        for (q in listOf("Barbell full squat", "", "  ", "ㅅㅋㅌ")) assertEquals(0, calls(q), q)
    }

    @Test
    fun `자연어 문장은 의미 검색을 부른다`() {
        for (q in listOf("legs using only a wall", "背中の肉をなくしたい", "무릎이 안 좋을 때 하체운동", "warm up shoulders before bench day")) assertEquals(1, calls(q), q)
    }

    @Test
    fun `사전이 못 알아들은 짧은 별칭도 의미 검색을 부른다`() {
        assertEquals(1, calls("dumbbell zxqvwbn"))
        assertEquals(1, calls("바벨 흐뭇뭇"))
    }

    @Test
    fun `어휘 결과가 비면 의미 검색을 부른다`() {
        assertTrue(engine.search("zxqvbnm", null, null, "en").isEmpty())
        assertEquals(1, calls("zxqvbnm"))
    }

    @Test
    fun `질의 벡터를 못 구하면 어휘 결과 그대로 + degraded`() {
        val q = "legs using only a wall"
        val r = hybrid.search(q, null, null, "en") { null }
        assertFalse(r.semantic)
        assertTrue(r.degraded)
        assertEquals(engine.search(q, null, null, "en").map { it.doc.id }, r.hits.map { it.doc.id })
    }

    @Test
    fun `질의 벡터 차원이 다르면 어휘 결과 그대로`() {
        val q = "legs using only a wall"
        val r = hybrid.search(q, null, null, "en") { FloatArray(5) { 1f } }
        assertFalse(r.semantic)
        assertEquals(engine.search(q, null, null, "en").map { it.doc.id }, r.hits.map { it.doc.id })
    }

    @Test
    fun `의미 검색이 필요 없던 질의는 degraded 가 아니다`() {
        val r = hybrid.search("squat", null, null, "en") { null }
        assertFalse(r.degraded)
        assertFalse(r.semantic)
    }

    @Test
    fun `정확한 운동 이름은 어휘 결과와 완전히 같다`() {
        val q = "Barbell full squat"
        assertEquals(engine.search(q, null, null, "en").map { it.doc.id }, search(q, "pigeon-pose").hits.map { it.doc.id })
    }

    @Test
    fun `의미 후보 수는 semanticDepth 로 제한된다`() {
        val h = HybridSearch(engine, vectors, HybridSearch.Config(semanticDepth = 3, lexicalDepth = 0))
        val r = h.search("zxqvbnm", null, null, "en") { like("pigeon-pose") }
        assertTrue(r.semantic)
        assertEquals(3, r.hits.size)
        assertEquals("pigeon-pose", r.hits.first().doc.slug)
    }

    @Test
    fun `어휘가 비고 의미 유사도가 너무 낮으면 억지 결과를 주지 않는다`() {
        val h = HybridSearch(engine, vectors, HybridSearch.Config(emptyLexicalMinSimilarity = 0.99))
        // 문서 벡터와 거의 직교하는 질의 벡터
        val odd = FloatArray(768) { if (it == 0) 1f else 0f }
        val r = h.search("zxqvbnm", null, null, "en") { odd }
        assertTrue(r.hits.isEmpty())
        assertFalse(r.semantic)
    }

    // ── 가중치·보호 ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `의미 가중치를 0 으로 하면 어휘 순서가 앞을 지킨다`() {
        val q = "good back exercises with dumbbells for beginners"
        val lexical = engine.search(q, null, null, "en").take(5).map { it.doc.slug }
        val h = HybridSearch(engine, vectors, HybridSearch.Config(semanticWeight = 0.0))
        assertEquals(lexical, h.search(q, null, null, "en") { like("pigeon-pose") }.hits.take(5).map { it.doc.slug })
    }

    @Test
    fun `어휘 1위가 확실한 이름 일치면 보호 점수로 1위를 지킨다`() {
        val q = "barbell full squat variations please"
        val lex = engine.search(q, null, null, "en")
        // 의미는 완전히 다른 운동을 가리켜도, 보호 점수 이상인 어휘 1위는 그대로 1위
        val protectedTop = HybridSearch(engine, vectors, HybridSearch.Config(protectScore = 0.0)).search(q, null, null, "en") { like("pigeon-pose") }
        assertEquals(lex.first().doc.slug, protectedTop.hits.first().doc.slug)
    }

    @Test
    fun `보호를 끄면 의미가 어휘 1위를 밀어낼 수 있다`() {
        val q = "good back exercises with dumbbells for beginners"
        val lex = engine.search(q, null, null, "en")
        val off = HybridSearch(engine, vectors, HybridSearch.Config(protectScore = null, rrfK = 1, semanticWeight = 5.0))
        val r = off.search(q, null, null, "en") { like(lex[4].doc.slug) }
        assertEquals(lex[4].doc.slug, r.hits.first().doc.slug)
    }

    @Test
    fun `엔진이 시간 예산을 넘겨 잘렸으면 의미를 섞지 않는다`() {
        val tight = ExerciseSearchEngine(docs, emptyList(), 1L)
        val h = HybridSearch(tight, vectors)
        val r = h.search("some very long natural sentence about building strong legs at home with a chair", null, null, "en") { like("pigeon-pose") }
        // 예산이 1ns 라 거의 확실히 초과한다 — 그때는 complete=false, semantic=false
        if (!r.complete) assertFalse(r.semantic)
    }

    @Test
    fun `모든 카탈로그 문서가 벡터를 갖는다`() {
        assertEquals(docs.size, hybrid.coveredDocs)
    }

    @Test
    fun `벡터가 없는 문서는 의미 후보가 되지 않고 어휘 결과는 유지된다`() {
        val partial = ExerciseVectors.fromFloats(listOf("pigeon-pose"), listOf(like("pigeon-pose")))
        val h = HybridSearch(engine, partial)
        assertEquals(1, h.coveredDocs)
        val q = "good back exercises with dumbbells for beginners"
        val r = h.search(q, null, null, "en") { like("pigeon-pose") }
        assertTrue(r.hits.map { it.doc.id }.containsAll(engine.search(q, null, null, "en").map { it.doc.id }))
    }

    // ── 합성 문서로 융합 순서를 정확히 고정 ──────────────────────────────────────────────────

    private fun d(id: Long, slug: String, en: String, equipment: Equipment = Equipment.BODYWEIGHT, category: ExerciseCategory = ExerciseCategory.LEGS) = ExerciseSearchDoc(
        id = id, slug = slug, names = mapOf("en" to en), category = category, equipment = equipment,
        muscleGroups = setOf(MuscleGroup.QUADRICEPS), movementPattern = null, tier = RecommendationTier.STANDARD, popularity = 50, difficulty = 50
    )

    private fun unit(vararg v: Float): FloatArray = QueryEmbedder.normalize(FloatArray(768).also { a -> v.forEachIndexed { i, x -> a[i] = x } })

    @Test
    fun `합성 - RRF 점수 계산과 동점 처리`() {
        val synth = listOf(
            d(1, "alpha-lunge", "Alpha lunge"), d(2, "beta-lunge", "Beta lunge"), d(3, "gamma-lunge", "Gamma lunge"), d(4, "delta-hold", "Delta hold")
        )
        val e = ExerciseSearchEngine(synth)
        val vs = ExerciseVectors.fromFloats(synth.map { it.slug }, listOf(unit(1f, 0f), unit(0.9f, 0.1f), unit(0f, 1f), unit(0f, 0.8f, 0.2f)))
        val h = HybridSearch(e, vs, HybridSearch.Config(protectScore = null))
        // "lunge" 질의는 어휘로 alpha·beta·gamma 순(이름 동점 → 인기/이름순). 의미는 gamma 쪽을 가리킨다.
        val q = "i want some lunge movements today"
        val lexical = e.search(q, null, null, "en").map { it.doc.slug }
        val r = h.search(q, null, null, "en") { unit(0f, 1f) }
        assertTrue(r.semantic)
        // 의미 1위 gamma 는 어휘 점수도 있으므로 어휘 단독보다 앞으로 온다
        assertTrue(r.hits.map { it.doc.slug }.indexOf("gamma-lunge") <= lexical.indexOf("gamma-lunge"))
        // delta 는 의미로만 올라오지만 결과에는 들어온다
        assertTrue("delta-hold" in r.hits.map { it.doc.slug })
    }

    @Test
    fun `합성 - 제외된 운동은 의미 1위여도 없다`() {
        val synth = listOf(
            d(1, "barbell-squat", "Barbell squat", Equipment.BARBELL), d(2, "goblet-squat", "Goblet squat", Equipment.DUMBBELL),
            d(3, "air-squat", "Air squat", Equipment.BODYWEIGHT)
        )
        val e = ExerciseSearchEngine(synth)
        val vs = ExerciseVectors.fromFloats(synth.map { it.slug }, listOf(unit(1f), unit(0.2f, 0.8f), unit(0f, 0.3f, 0.7f)))
        val h = HybridSearch(e, vs)
        val r = h.search("squat without barbell please today", null, null, "en") { unit(1f) }
        assertTrue(r.hits.none { it.doc.slug == "barbell-squat" }, r.hits.map { it.doc.slug }.toString())
        assertTrue(r.hits.isNotEmpty())
    }
}

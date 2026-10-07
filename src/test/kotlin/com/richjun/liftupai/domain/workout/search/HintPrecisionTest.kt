package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM 힌트의 정밀도 —
 *  (a) 이름 일치로 LLM 을 건너뛰려면 질의의 내용 토큰이 이름에서 통째 단어로 나와야 한다("삼각형" ⊂ "역삼각형" 은 아니다),
 *  (b) 힌트가 가리킨 운동이 부상 조건으로 빠지면 단어 하나만 겹치는 무관한 이름으로 대체하지 않는다,
 *  (c) "〜의 보조로 + 목표 부위" 목표 말투에서는 목표 부위 운동과 LLM 힌트가 말로 언급된 운동 자체보다 앞선다.
 * LLM 응답은 실제 모델이 낸 JSON 을 고정해서 쓴다(네트워크 없음).
 */
class HintPrecisionTest {
    private val docs = ExerciseSearchEvalTest.loadCatalogDocs()
    private val engine = ExerciseSearchEngine(docs, emptyList(), 10_000_000_000L)
    private val vectors = ExerciseVectors.loadDefault()!!
    private val hybrid = HybridSearch(engine, vectors)

    private fun rewrite(vararg hints: String) = QueryRewrite(hints.toList(), emptySet())

    private fun run(q: String, rw: QueryRewrite?, search: HybridSearch = hybrid): List<String> =
        search.search(q, null, null, "ja", rw?.let { r -> { r } }) { null }.hits.map { it.doc.slug }

    // ── (a) 이름 조각이 LLM 건너뛰기·1위 고정을 일으키지 않는다 ────────────────────────────

    private fun d(id: Long, slug: String, name: String, eq: Equipment = Equipment.BODYWEIGHT, cat: ExerciseCategory = ExerciseCategory.CHEST, pattern: String? = null, muscles: Set<MuscleGroup> = setOf(MuscleGroup.CHEST)) =
        ExerciseSearchDoc(id, slug, mapOf("en" to slug.replace('-', ' '), "ko" to name), cat, eq, muscles, pattern, RecommendationTier.STANDARD, 50, 40)

    @Test
    fun `합성어 속 부분 문자열 일치는 LLM 을 건너뛰는 근거가 아니다 - 통째 단어일 때만 건너뛴다`() {
        val small = listOf(
            d(1, "reverse-triangle-fly", "역삼각형 플라이"),
            d(2, "triangle-push-up", "삼각형 푸시업"),
            d(3, "barbell-bench-press", "바벨 벤치프레스", Equipment.BARBELL),
            d(4, "pull-up", "풀업", cat = ExerciseCategory.BACK, muscles = setOf(MuscleGroup.BACK))
        )
        val e = ExerciseSearchEngine(small, emptyList(), 10_000_000_000L)
        val h = HybridSearch(e, ExerciseVectors.fromFloats(small.map { it.slug }, List(small.size) { i -> FloatArray(768).also { it[i] = 1f } }))
        val lex = e.search("삼각형 플라이")
        assertEquals("reverse-triangle-fly", lex.first().doc.slug)
        assertTrue(lex.first().score >= 160.0, "합성어 속 일치도 어휘 점수는 단어 경계 대역이다: ${lex.first().score}")
        val c = e.semanticConstraint("삼각형 플라이")!!
        assertFalse(c.unrecognized, "이름 낱말을 다 아는 질의라 예전에는 여기서 건너뛰었다")
        assertFalse(c.wholeTokenNameMatch(lex.first().doc), "내용 토큰이 이름에 통째 단어로 나오지 않는다")
        assertTrue(h.needsRewrite("삼각형 플라이", lex, c), "부분 문자열 일치로는 LLM 을 건너뛰지 않는다")
        // 통째 단어 일치는 지금처럼 건너뛴다
        val lex2 = e.search("삼각형 푸시업")
        val c2 = e.semanticConstraint("삼각형 푸시업")
        assertTrue(c2 == null || c2.wholeTokenNameMatch(lex2.first().doc))
        val lex3 = e.search("벤치프레스")
        val c3 = e.semanticConstraint("벤치프레스")!!
        assertTrue(c3.wholeTokenNameMatch(lex3.first().doc))
        assertFalse(h.needsRewrite("벤치프레스", lex3, c3))
    }

    @Test
    fun `사전이 아는 별칭과 붙여 쓴 복합어는 통째 일치로 보아 지금처럼 건너뛰고 이름 속 부분 문자열만 건너뛰지 않는다`() {
        fun skips(q: String): Boolean {
            val c = engine.semanticConstraint(q) ?: return true
            return !hybrid.needsRewrite(q, engine.search(q), c)
        }
        assertTrue(skips("ブルスク"), "별칭")
        assertTrue(skips("ケーブルロウ"), "사전 낱말로 나뉘는 복합어")
        assertTrue(skips("ハトのポーズ"))
        assertFalse(skips("sumo dead"), "dead ⊂ deadlift 는 부분 문자열 일치다")
        assertFalse(skips("hypers"))
    }

    @Test
    fun `통째 단어가 아닌 일치는 LLM 이 부르는 해석이 1위를 밀어낼 수 있다 - 1위 고정도 하지 않는다`() {
        val small = listOf(
            d(1, "reverse-triangle-fly", "역삼각형 플라이"),
            d(2, "pull-up", "풀업", cat = ExerciseCategory.BACK, muscles = setOf(MuscleGroup.BACK))
        )
        val e = ExerciseSearchEngine(small, emptyList(), 10_000_000_000L)
        val h = HybridSearch(e, ExerciseVectors.fromFloats(listOf("reverse-triangle-fly", "pull-up"), List(2) { i -> FloatArray(768).also { it[i] = 1f } }))
        val calls = AtomicInteger()
        val out = h.search("삼각형 플라이", null, null, "ko", { calls.incrementAndGet(); QueryRewrite(listOf("Pull-up"), emptySet()) }) { null }
        assertEquals(1, calls.get(), "LLM 해석을 불렀다")
        assertEquals("pull-up", out.hits.first().doc.slug, "고정되지 않은 부분 일치 1위를 코치의 정확한 힌트가 앞선다")
    }

    @Test
    fun `실제 카탈로그 - 역삼각형 몸 만들기는 LLM 을 부르고 그 힌트가 트라이앵글 플라이를 앞서지 않는다`() {
        val q = "역삼각형 몸 만들기"
        val calls = AtomicInteger()
        val rw = rewrite("Pull-up", "Lat pulldown", "Overhead press", "Dumbbell lateral raise", "Barbell row", "Push-up")
        val out = hybrid.search(q, null, null, "ko", { calls.incrementAndGet(); rw }) { null }.hits.map { it.doc.slug }
        assertEquals(1, calls.get())
        assertTrue(out.size > 1, "결과가 한 개로 끝나지 않는다: $out")
        val triangle = out.indexOf("bodyweight-standing-triangle-fly")
        assertTrue(triangle != 0, "설명문 한 조각에 걸린 운동이 1위가 아니다: $out")
        assertTrue(out.take(5).any { it.contains("pull-up") || it.contains("pulldown") || it.contains("row") || it.contains("lateral-raise") || it.contains("overhead-press") }, out.toString())
    }

    // ── (b) 빠진 운동 자리에 무관한 이름이 들어오지 않는다 ───────────────────────────────

    @Test
    fun `실제 카탈로그 - 무릎이 아픈데 다리 - 레그 익스텐션이 빠져도 삼두 익스텐션 계열이 대신 들어오지 않는다`() {
        val q = "膝が痛いけど脚を鍛えたい"
        val rw = rewrite("Leg Extension", "Seated Leg Curl", "Glute Bridge", "Straight Leg Raise", "Cable Pull Through", "Box Squat")
        val out = run(q, rw)
        val top = out.take(12)
        val offTarget = top.filter { it.contains("extension") && !it.contains("leg-extension") && !it.contains("back-extension") }
        assertTrue(offTarget.isEmpty(), "단어 하나(extension)만 겹치는 무관한 운동: $offTarget in $top")
        assertTrue(top.any { it.contains("glute-bridge") || it.contains("leg-curl") }, "다른 힌트는 살아 있다: $top")
    }

    @Test
    fun `힌트가 가리킨 운동이 부상으로 빠지면 같은 부위·동작이 이어지는 후보만 대신 쓴다`() {
        val small = listOf(
            d(1, "leg-extension-machine", "레그 익스텐션 머신", Equipment.MACHINE, ExerciseCategory.LEGS, "KNEE_EXTENSION", setOf(MuscleGroup.QUADRICEPS)),
            d(2, "triceps-extension", "트라이셉스 익스텐션", Equipment.DUMBBELL, ExerciseCategory.ARMS, "ELBOW_EXTENSION", setOf(MuscleGroup.TRICEPS)),
            d(3, "cable-overhead-extension", "케이블 오버헤드 익스텐션", Equipment.CABLE, ExerciseCategory.ARMS, "ELBOW_EXTENSION", setOf(MuscleGroup.TRICEPS)),
            d(4, "glute-bridge", "글루트 브릿지", Equipment.BODYWEIGHT, ExerciseCategory.LEGS, "HIP_EXTENSION", setOf(MuscleGroup.GLUTES)),
            d(5, "hamstring-curl", "햄스트링 컬", Equipment.MACHINE, ExerciseCategory.LEGS, "KNEE_FLEXION", setOf(MuscleGroup.HAMSTRINGS))
        )
        val e = ExerciseSearchEngine(small, emptyList(), 10_000_000_000L)
        val h = HybridSearch(e, ExerciseVectors.fromFloats(small.map { it.slug }, List(small.size) { i -> FloatArray(768).also { it[i] = 1f } }))
        val q = "무릎이 아픈데 다리 운동하고 싶어요"
        val c = e.semanticConstraint(q)!!
        assertFalse(c.allows(small[0]), "레그 익스텐션은 무릎 부상에서 조심할 동작이라 후보가 아니다")
        val before = e.search(q).map { it.doc.slug }
        val out = h.search(q, null, null, "ko", { rewrite("Leg Extension") }) { null }.hits.map { it.doc.slug }
        val arms = setOf("triceps-extension", "cable-overhead-extension")
        assertTrue(out.filter { it in arms }.all { it in before } || out.none { it in arms }, "무관한 익스텐션이 새로 올라왔다: $out (전: $before)")
        assertTrue(out.take(3).none { it in arms && it !in before.take(3) }, out.toString())
    }

    @Test
    fun `허용된 힌트는 그대로 쓴다 - 필터에 걸리지 않으면 단어가 겹쳐도 달라지는 것이 없다`() {
        val out = run("something to build bigger arms", rewrite("Triceps Pushdown", "Barbell Curl"))
        assertTrue(out.take(8).any { it.contains("pushdown") || it.contains("push-down") }, out.toString())
    }

    // ── (c) 목표 말투 ────────────────────────────────────────────────────────────────────────

    private val closeGripRw = rewrite("Close Grip Bench Press", "Triceps Dips", "Skull Crusher", "Triceps Pushdown", "Overhead Dumbbell Triceps Extension", "Diamond Push-Up")

    private fun isLiftVariant(slug: String) = slug.contains("bench-press") && !slug.contains("triceps")
    private fun isTricepsIsolation(slug: String) = Regex("pushdown|push-down|skull|triceps-extension|tricep-extension|kickback|kick-back|triceps-dip|tricep-dip|overhead-extension|pullover").containsMatchIn(slug)

    @Test
    fun `어휘 검색만으로도 목표 부위 운동이 말로 언급된 벤치프레스 변형보다 앞선다`() {
        for (q in listOf("ベンチプレスの補助で三頭筋を鍛える", "벤치프레스 보조로 삼두 키우기", "triceps work to support my bench press")) {
            val lex = engine.search(q).map { it.doc.slug }
            val firstLift = lex.indexOfFirst { isLiftVariant(it) }
            val firstIso = lex.indexOfFirst { isTricepsIsolation(it) }
            assertTrue(firstIso in 0..2, "$q: 삼두 운동이 1~3위에: ${lex.take(6)}")
            assertTrue(firstLift < 0 || firstLift > firstIso, "$q: 벤치프레스 변형이 삼두 운동 뒤에: ${lex.take(8)}")
            assertTrue(lex.take(5).none { isLiftVariant(it) }, "$q: 상위 5개에 벤치프레스 변형이 없다: ${lex.take(6)}")
        }
    }

    @Test
    fun `LLM 힌트를 섞어도 상위 5개는 삼두 고립 운동이고 벤치프레스 변형이 앞서지 않는다`() {
        for ((q, rw) in listOf(
            "ベンチプレスの補助で三頭筋を鍛える" to closeGripRw,
            "벤치프레스 보조로 삼두 키우기" to rewrite("Close-Grip Bench Press", "Triceps Dips", "Skull Crusher", "Overhead Triceps Extension", "Triceps Pushdown", "Diamond Push-up"),
            "triceps work to support my bench press" to rewrite("Close Grip Bench Press", "Triceps Dips", "Skull Crushers", "Triceps Pushdown", "Overhead Triceps Extension", "Board Press")
        )) {
            val out = run(q, rw)
            val top5 = out.take(5)
            assertTrue(top5.count { isTricepsIsolation(it) || it.contains("dip") } >= 3, "$q: $top5")
            val firstLift = out.indexOfFirst { isLiftVariant(it) }
            val lastIso = out.take(10).indexOfLast { isTricepsIsolation(it) }
            assertTrue(firstLift < 0 || firstLift > lastIso.coerceAtMost(4), "$q: 벤치프레스 변형이 삼두 운동보다 앞서지 않는다: ${out.take(10)}")
        }
    }

    @Test
    fun `목표 말투가 아니면 그 운동을 그대로 찾는다 - 벤치프레스 삼두 같은 나열은 건드리지 않는다`() {
        val out = engine.search("벤치프레스").map { it.doc.slug }
        assertTrue(out.first().contains("bench-press"), out.take(3).toString())
        val plain = engine.search("bench press").map { it.doc.slug }
        assertTrue(plain.first().contains("bench-press"))
        // 보조 표현이 있어도 목표 부위가 없으면(운동 이름 질의) 평소처럼
        val named = engine.search("assisted pull up").map { it.doc.slug }
        assertTrue(named.take(3).any { it.contains("assist") || it.contains("pull-up") }, named.take(5).toString())
    }

    // ── 질의가 직접 요청한 장비는 LLM 의 제외 장비로 지워지지 않는다 ───────────────────────────

    @Test
    fun `질의가 말한 장비는 LLM 이 제외 장비로 내도 결과에서 지우지 않는다`() {
        val small = listOf(
            d(1, "cable-crossover", "케이블 크로스오버", Equipment.CABLE, ExerciseCategory.CHEST, "FLY", setOf(MuscleGroup.CHEST)),
            d(2, "cable-fly-low", "케이블 로우 플라이", Equipment.CABLE, ExerciseCategory.CHEST, "FLY", setOf(MuscleGroup.CHEST)),
            d(3, "dumbbell-fly", "덤벨 플라이", Equipment.DUMBBELL, ExerciseCategory.CHEST, "FLY", setOf(MuscleGroup.CHEST)),
            d(4, "pec-deck", "펙덱 머신", Equipment.MACHINE, ExerciseCategory.CHEST, "FLY", setOf(MuscleGroup.CHEST))
        )
        val e = ExerciseSearchEngine(small, emptyList(), 10_000_000_000L)
        val h = HybridSearch(e, ExerciseVectors.fromFloats(small.map { it.slug }, List(small.size) { i -> FloatArray(768).also { it[i] = 1f } }))
        val q = "케이블로 하는 가슴 운동 알려줘"
        val c = e.semanticConstraint(q)!!
        assertEquals(setOf(Equipment.CABLE), c.requestedEquipment)
        val out = h.search(q, null, null, "ko", { QueryRewrite(listOf("Cable Crossover"), setOf(Equipment.CABLE)) }) { null }.hits.map { it.doc.slug }
        assertTrue(out.take(2).all { it.startsWith("cable") }, "사용자가 요청한 케이블이 LLM 의 제외로 사라지면 안 된다: $out")
        // 요청하지 않은 장비를 LLM 이 제외하면 여전히 거른다
        val out2 = h.search("something to build a bigger chest", null, null, "en", { QueryRewrite(listOf("Cable Crossover"), setOf(Equipment.CABLE)) }) { null }.hits.map { it.doc.slug }
        assertTrue(out2.take(2).none { it.startsWith("cable") }, out2.toString())
    }

    @Test
    fun `사전이 모르는 말이 낀 질의의 장비는 요청으로 믿지 않는다 - LLM 이 읽은 점유·부정을 따른다`() {
        // "swarmed" 는 사전에 없는 점유 표현이다 — 사전은 이름(pushdown)과 cable 만 알아듣지만 LLM 은 케이블을 쓸 수 없다고 읽는다.
        // (의도 질의에서 사전이 읽은 장비는 하드 조건이라 LLM 이 뒤집지 않는다 — 실제 해석 2,530건에서 둘이 엇갈린 6건 중 LLM 이 틀린 경우도 있었다)
        val q = "triceps pushdown but the cable is swarmed"
        assertTrue(engine.semanticConstraint(q)!!.requestedEquipment.isEmpty(), engine.explain(q))
        val rw = QueryRewrite(listOf("Dumbbell Overhead Triceps Extension", "Bench Dip", "Close-Grip Push-Up"), setOf(Equipment.CABLE, Equipment.MACHINE))
        val out = hybrid.search(q, null, null, "en", { rw }) { null }.hits.take(5)
        assertTrue(out.none { it.doc.equipment == Equipment.CABLE || it.doc.equipment == Equipment.MACHINE }, out.map { it.doc.slug }.toString())
        // 사전이 남김없이 알아들은 요청("케이블로 하는 가슴 운동 알려줘")은 그대로 요청 장비다
        assertEquals(setOf(Equipment.CABLE), engine.semanticConstraint("케이블로 하는 가슴 운동 알려줘")!!.requestedEquipment)
    }

    @Test
    fun `집에서 일반 장비를 말하면 힌트의 장비 범위를 넓히지 않는다 - 수건 같은 대용 사물만 넓힌다`() {
        val e = ExerciseSearchEngine(docs, emptyList(), 10_000_000_000L)
        val dumbbell = e.semanticConstraint("집에서 덤벨로 하는 가슴 운동", forHints = true)!!
        val towel = e.semanticConstraint("집에서 수건으로 하는 어깨 운동", forHints = true)!!
        val bandDoc = docs.first { it.equipment == Equipment.RESISTANCE_BAND && it.category == ExerciseCategory.CHEST }
        val bodyDoc = docs.first { it.equipment == Equipment.BODYWEIGHT && it.category == ExerciseCategory.SHOULDERS }
        assertFalse(dumbbell.allows(bandDoc), "덤벨을 직접 말했으면 밴드로 새지 않는다")
        assertTrue(towel.allows(bodyDoc), "수건은 사전이 밴드로 읽어도 홈 장비 전체를 본다")
    }
}

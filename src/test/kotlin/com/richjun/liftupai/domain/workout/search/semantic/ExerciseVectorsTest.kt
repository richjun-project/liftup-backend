package com.richjun.liftupai.domain.workout.search.semantic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * 운동 임베딩 리소스(catalog/exercise-embeddings.*) — 카탈로그의 모든 slug 를 덮고, 문서 텍스트 해시가 최신이어야 한다.
 * 카탈로그(이름·설명·장비·근육)를 바꾸고 임베딩을 안 다시 만들면 이 테스트가 실패한다: scripts/search/regenerate_embeddings.py
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExerciseVectorsTest {
    private val mapper = ObjectMapper()
    private lateinit var catalog: JsonNode
    private lateinit var vectors: ExerciseVectors

    @BeforeAll
    fun setUp() {
        catalog = mapper.readTree(javaClass.getResourceAsStream("/catalog/exercise-catalog.json") ?: error("catalog not found"))
        vectors = ExerciseVectors.loadDefault() ?: error("exercise embeddings resource missing or broken")
    }

    // ── 실제 리소스 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `리소스는 gemini-embedding-001 768차원 v1 레시피다`() {
        assertEquals("gemini-embedding-001", vectors.model)
        assertEquals(768, vectors.dim)
        assertEquals(ExerciseEmbeddingText.RECIPE_VERSION, vectors.recipe)
    }

    @Test
    fun `카탈로그의 모든 slug 에 벡터가 있다`() {
        val missing = catalog.map { it["slug"].asText() }.filter { !vectors.has(it) }
        assertTrue(missing.isEmpty(), "벡터가 없는 운동 ${missing.size}개(scripts/search/regenerate_embeddings.py 로 다시 만든다): ${missing.take(10)}")
        assertEquals(catalog.size(), vectors.size)
    }

    @Test
    fun `모든 벡터의 문서 텍스트 해시가 카탈로그와 같다 - 낡은 벡터 없음`() {
        val problems = vectors.problemsAgainst(catalog)
        assertTrue(problems.isEmpty(), "임베딩이 카탈로그와 어긋남 ${problems.size}건(scripts/search/regenerate_embeddings.py 로 다시 만든다): ${problems.take(10)}")
    }

    @Test
    fun `복원한 벡터는 단위 길이에 가깝고 자기 자신과의 유사도가 1에 가깝다`() {
        for (slug in vectors.slugs().filterIndexed { i, _ -> i % 37 == 0 }) {
            val v = vectors.vector(slug)!!
            val norm = Math.sqrt(v.sumOf { it.toDouble() * it })
            assertEquals(1.0, norm, 0.02, slug)
            assertEquals(1.0, vectors.similarity(v, slug)!!.toDouble(), 0.03, slug)
        }
    }

    @Test
    fun `서로 다른 운동의 벡터는 구별된다`() {
        val a = vectors.vector("barbell-full-squat")!!
        assertTrue(vectors.similarity(a, "barbell-bench-press")!! < 0.95f)
        assertTrue(vectors.similarity(a, "barbell-full-squat")!! > vectors.similarity(a, "barbell-bench-press")!!)
    }

    @Test
    fun `벡터에 없는 slug 는 null`() {
        assertNull(vectors.similarity(FloatArray(768) { 0.036f }, "no-such-exercise"))
        assertFalse(vectors.has("no-such-exercise"))
        assertNull(vectors.contentHash("no-such-exercise"))
        assertNull(vectors.rowOf("no-such-exercise"))
    }

    @Test
    fun `질의 벡터 차원이 다르면 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { vectors.similarity(FloatArray(10), "barbell-full-squat") }
    }

    // ── 검증기: 빠진·낡은·모르는 slug 를 잡는다 ────────────────────────────────────────────────

    private fun entry(slug: String, name: String = "Squat"): JsonNode = mapper.readTree(
        """{"slug":"$slug","category":"LEGS","equipment":"BARBELL","movementPattern":"SQUAT","muscleGroups":["QUADRICEPS"],
            "translations":[{"locale":"en","name":"$name","instructions":"Stand.\nBend."},{"locale":"ja","name":"スクワット","instructions":"立つ。"},{"locale":"ko","name":"스쿼트","instructions":"서라."}]}"""
    )

    private fun synthetic(entries: List<JsonNode>, hashes: List<String>): ExerciseVectors =
        ExerciseVectors.fromFloats(entries.map { it["slug"].asText() }, entries.map { FloatArray(4) { 0.5f } }, hashes)

    private fun hashOf(e: JsonNode) = ExerciseEmbeddingText.contentHash(ExerciseEmbeddingText.build(e))

    @Test
    fun `검증기는 정상이면 빈 목록을 준다`() {
        val a = entry("a"); val b = entry("b", "Other")
        assertTrue(synthetic(listOf(a, b), listOf(hashOf(a), hashOf(b))).problemsAgainst(mapper.createArrayNode().add(a).add(b)).isEmpty())
    }

    @Test
    fun `검증기는 벡터가 빠진 slug 를 잡는다`() {
        val a = entry("a"); val b = entry("b", "Other")
        val problems = synthetic(listOf(a), listOf(hashOf(a))).problemsAgainst(mapper.createArrayNode().add(a).add(b))
        assertEquals(listOf("missing vector: b"), problems)
    }

    @Test
    fun `검증기는 이름이 바뀌어 해시가 달라진 낡은 벡터를 잡는다`() {
        val a = entry("a"); val renamed = entry("a", "Renamed squat")
        val problems = synthetic(listOf(a), listOf(hashOf(a))).problemsAgainst(mapper.createArrayNode().add(renamed))
        assertEquals(1, problems.size)
        assertTrue(problems[0].startsWith("stale vector"), problems[0])
    }

    @Test
    fun `검증기는 카탈로그에서 사라진 운동의 벡터를 잡는다`() {
        val a = entry("a"); val gone = entry("gone", "Gone")
        val problems = synthetic(listOf(a, gone), listOf(hashOf(a), hashOf(gone))).problemsAgainst(mapper.createArrayNode().add(a))
        assertEquals(listOf("vector for unknown slug: gone"), problems)
    }

    @Test
    fun `설명문이 바뀌어도 해시가 달라진다`() {
        val a = entry("a")
        val changed = mapper.readTree(a.toString().replace("Stand.", "Sit."))
        assertTrue(hashOf(a) != hashOf(changed))
    }

    @Test
    fun `문서 텍스트는 세 언어 이름·분류·장비·근육·동작·설명문 첫 줄이다`() {
        val text = ExerciseEmbeddingText.build(entry("a"))
        assertEquals("Squat / スクワット / 스쿼트. Category: LEGS. Equipment: BARBELL. Muscles: QUADRICEPS. Pattern: SQUAT. Stand. 立つ。 서라.", text)
    }

    @Test
    fun `장비나 동작이 없으면 None 으로 쓰고 설명문은 160자에서 자른다`() {
        val long = "x".repeat(300)
        val e = mapper.readTree("""{"slug":"n","category":"CORE","equipment":null,"muscleGroups":[],"translations":[{"locale":"en","name":"N","instructions":"$long"}]}""")
        val text = ExerciseEmbeddingText.build(e)
        assertTrue(text.contains("Equipment: None. Muscles: . Pattern: None. "), text)
        assertTrue(text.contains("x".repeat(160) + " "), text)
        assertFalse(text.contains("x".repeat(161)), text)
    }

    @Test
    fun `160자 자르기는 코드포인트 단위라 서로게이트 쌍을 쪼개지 않는다`() {
        val emoji = "💪".repeat(170)
        val e = mapper.readTree("""{"slug":"n","category":"CORE","equipment":"BODYWEIGHT","muscleGroups":[],"movementPattern":"X","translations":[{"locale":"en","name":"N","instructions":"$emoji"}]}""")
        val text = ExerciseEmbeddingText.build(e)
        assertEquals(160, text.substringAfter("Pattern: X. ").trim().let { it.codePointCount(0, it.length) })
    }

    // ── 로더: 깨진 리소스는 거부 ──────────────────────────────────────────────────────────────

    private fun metaJson(count: Int, dim: Int, sha: String, quant: String = "int8-per-vector-scale", dup: Boolean = false): String {
        val entries = (0 until count).joinToString(",") { """{"slug":"${if (dup) "s0" else "s$it"}","hash":"h$it","scale":0.01}""" }
        return """{"model":"gemini-embedding-001","dim":$dim,"recipe":"v1","quantization":"$quant","binSha256":"$sha","count":$count,"entries":[$entries]}"""
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun load(meta: String, bin: ByteArray) = ExerciseVectors.load(ByteArrayInputStream(meta.toByteArray()), ByteArrayInputStream(bin))

    @Test
    fun `정상 리소스는 읽힌다`() {
        val bin = ByteArray(2 * 4) { (it - 3).toByte() }
        val v = load(metaJson(2, 4, sha(bin)), bin)
        assertEquals(2, v.size)
        assertEquals(4, v.dim)
        assertEquals("h1", v.contentHash("s1"))
        assertNotNull(v.vector("s0"))
    }

    @Test
    fun `체크섬이 다르면 거부한다`() {
        val bin = ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) { load(metaJson(2, 4, "0".repeat(64)), bin) }
    }

    @Test
    fun `바이너리 크기가 항목 수 곱하기 차원과 다르면 거부한다`() {
        val bin = ByteArray(7)
        assertThrows(IllegalArgumentException::class.java) { load(metaJson(2, 4, sha(bin)), bin) }
    }

    @Test
    fun `모르는 양자화 방식이면 거부한다`() {
        val bin = ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) { load(metaJson(2, 4, sha(bin), quant = "fp16"), bin) }
    }

    @Test
    fun `slug 가 중복되면 거부한다`() {
        val bin = ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) { load(metaJson(2, 4, sha(bin), dup = true), bin) }
    }

    @Test
    fun `양자화한 내적은 실수 내적과 거의 같다`() {
        val rnd = java.util.Random(7)
        val dim = 768
        fun unit() = FloatArray(dim) { rnd.nextGaussian().toFloat() }.let { v -> val n = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat(); FloatArray(dim) { v[it] / n } }
        val docs = List(20) { unit() }
        val q = unit()
        val v = ExerciseVectors.fromFloats(docs.indices.map { "d$it" }, docs)
        docs.forEachIndexed { i, d ->
            val exact = d.indices.sumOf { (d[it] * q[it]).toDouble() }
            assertEquals(exact, v.similarity(q, "d$i")!!.toDouble(), 0.01)
        }
    }

    @Test
    fun `순위는 양자화로 뒤집히지 않는다 - 가장 비슷한 벡터가 1위`() {
        val rnd = java.util.Random(11)
        val dim = 768
        fun unit() = FloatArray(dim) { rnd.nextGaussian().toFloat() }.let { v -> val n = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat(); FloatArray(dim) { v[it] / n } }
        val docs = List(50) { unit() }
        val v = ExerciseVectors.fromFloats(docs.indices.map { "d$it" }, docs)
        // 질의 = 3번 문서에 약한 잡음
        val noisy = FloatArray(dim) { docs[3][it] + 0.01f * rnd.nextGaussian().toFloat() }
        val n = Math.sqrt(noisy.sumOf { it.toDouble() * it }).toFloat()
        val q = FloatArray(dim) { noisy[it] / n }
        val best = docs.indices.maxByOrNull { v.similarity(q, "d$it")!! }
        assertEquals(3, best)
    }
}

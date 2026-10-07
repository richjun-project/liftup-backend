package com.richjun.liftupai.domain.workout.search.semantic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.security.MessageDigest

/**
 * 운동 문서 임베딩 — classpath 리소스(catalog/exercise-embeddings.bin + .meta.json)를 읽어 메모리에 둔다.
 *
 * - 모델 gemini-embedding-001, RETRIEVAL_DOCUMENT, [dim]=768, L2 정규화 후 운동별 스케일로 int8 양자화(복원값 = 바이트 × 스케일).
 * - 질의 벡터도 정규화돼 있으면 내적이 곧 코사인 유사도다.
 * - 운동마다 문서 텍스트 해시가 메타에 있어, 카탈로그와 어긋났는지 테스트에서 검사할 수 있다.
 * 이 클래스는 불변이고 스레드 안전하다.
 */
class ExerciseVectors private constructor(
    val model: String,
    val dim: Int,
    val recipe: String,
    private val slugs: List<String>,
    private val hashes: List<String>,
    private val scales: FloatArray,
    private val data: ByteArray
) {
    private val rowBySlug: Map<String, Int> = slugs.withIndex().associate { it.value to it.index }

    val size: Int get() = slugs.size

    fun slugs(): List<String> = slugs

    fun has(slug: String): Boolean = slug in rowBySlug

    /** 이 운동 벡터의 행 번호(없으면 null) */
    fun rowOf(slug: String): Int? = rowBySlug[slug]

    /** 이 운동 벡터를 만들 때의 문서 텍스트 해시(없으면 null) */
    fun contentHash(slug: String): String? = rowBySlug[slug]?.let { hashes[it] }

    /** 질의 벡터(길이 [dim], L2 정규화)와 이 운동 벡터의 코사인 유사도. 벡터가 없는 운동이면 null. */
    fun similarity(query: FloatArray, slug: String): Float? = rowBySlug[slug]?.let { dot(query, it) }

    internal fun dot(query: FloatArray, row: Int): Float {
        require(query.size == dim) { "query dim ${query.size} != $dim" }
        var sum = 0f
        val base = row * dim
        for (i in 0 until dim) sum += query[i] * data[base + i]
        return sum * scales[row]
    }

    /**
     * 카탈로그(exercise-catalog.json 의 배열)와 이 벡터 묶음이 맞는지 점검한다. 문제 목록(비면 정상):
     * 카탈로그에 있는데 벡터가 없는 운동, 벡터는 있는데 카탈로그에서 사라진 운동, 문서 텍스트 해시가 달라 낡은 벡터.
     * 낡은/빠진 벡터가 생기면 scripts/search/regenerate_embeddings.py 로 다시 만든다.
     */
    fun problemsAgainst(catalog: JsonNode): List<String> {
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for (entry in catalog) {
            val slug = entry["slug"].asText()
            seen.add(slug)
            val stored = contentHash(slug)
            if (stored == null) { problems.add("missing vector: $slug"); continue }
            val expected = ExerciseEmbeddingText.contentHash(ExerciseEmbeddingText.build(entry))
            if (stored != expected) problems.add("stale vector (content hash $stored != $expected): $slug")
        }
        for (slug in slugs) if (slug !in seen) problems.add("vector for unknown slug: $slug")
        return problems
    }

    /** 복원한 벡터(테스트·진단용) */
    fun vector(slug: String): FloatArray? = rowBySlug[slug]?.let { row ->
        FloatArray(dim) { i -> data[row * dim + i] * scales[row] }
    }

    companion object {
        const val RESOURCE_BIN = "/catalog/exercise-embeddings.bin"
        const val RESOURCE_META = "/catalog/exercise-embeddings.meta.json"
        const val EXPECTED_MODEL = "gemini-embedding-001"
        const val EXPECTED_DIM = 768

        private val logger = LoggerFactory.getLogger(ExerciseVectors::class.java)

        /** 메타·바이너리 스트림에서 읽는다. 형식·크기·체크섬이 어긋나면 IllegalArgumentException. */
        fun load(meta: InputStream, bin: InputStream, mapper: ObjectMapper = ObjectMapper()): ExerciseVectors {
            val root = mapper.readTree(meta)
            val model = root["model"].asText()
            val dim = root["dim"].asInt()
            val recipe = root["recipe"].asText()
            require(root["quantization"]?.asText() == "int8-per-vector-scale") { "unsupported quantization" }
            require(dim > 0) { "bad dim" }
            val entries = root["entries"]
            val n = entries.size()
            val raw = bin.readBytes()
            require(raw.size == n * dim) { "embeddings.bin size ${raw.size} != $n x $dim" }
            val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            require(sha == root["binSha256"].asText()) { "embeddings.bin checksum mismatch" }
            val slugs = ArrayList<String>(n)
            val hashes = ArrayList<String>(n)
            val scales = FloatArray(n)
            entries.forEachIndexed { i, e ->
                slugs.add(e["slug"].asText())
                hashes.add(e["hash"].asText())
                scales[i] = e["scale"].floatValue()
            }
            require(slugs.toSet().size == n) { "duplicate slugs in embeddings meta" }
            return ExerciseVectors(model, dim, recipe, slugs, hashes, scales, raw)
        }

        /** 실수 벡터(L2 정규화됨)로 직접 만든다 — 리소스 생성과 같은 양자화. 테스트·도구용. */
        fun fromFloats(slugs: List<String>, vectors: List<FloatArray>, hashes: List<String> = slugs.map { "" }, model: String = EXPECTED_MODEL, recipe: String = ExerciseEmbeddingText.RECIPE_VERSION): ExerciseVectors {
            require(slugs.size == vectors.size && slugs.isNotEmpty()) { "slugs/vectors mismatch" }
            val dim = vectors.first().size
            val data = ByteArray(slugs.size * dim)
            val scales = FloatArray(slugs.size)
            vectors.forEachIndexed { r, v ->
                require(v.size == dim) { "ragged vectors" }
                val m = v.maxOf { Math.abs(it) }.takeIf { it > 0f } ?: 1f
                val scale = m / 127f
                scales[r] = scale
                for (i in 0 until dim) data[r * dim + i] = Math.round(v[i] / scale).coerceIn(-127, 127).toByte()
            }
            return ExerciseVectors(model, dim, recipe, slugs, hashes, scales, data)
        }

        /** classpath 리소스에서 읽는다. 없거나 깨졌으면 WARN 만 남기고 null — 의미 검색만 꺼지고 어휘 검색은 그대로다. */
        fun loadDefault(): ExerciseVectors? = try {
            val meta = ExerciseVectors::class.java.getResourceAsStream(RESOURCE_META)
            val bin = ExerciseVectors::class.java.getResourceAsStream(RESOURCE_BIN)
            if (meta == null || bin == null) {
                logger.warn("Exercise embeddings resource not found; semantic search is off")
                null
            } else meta.use { m -> bin.use { b -> load(m, b) } }.also {
                if (it.model != EXPECTED_MODEL || it.dim != EXPECTED_DIM) {
                    logger.warn("Exercise embeddings model/dim mismatch ({}/{}); semantic search is off", it.model, it.dim)
                    return null
                }
            }
        } catch (e: Exception) {
            logger.warn("Exercise embeddings could not be loaded; semantic search is off: {}", e.message)
            null
        }
    }
}

/** 스프링에서 문서 벡터를 한 번만(처음 쓸 때) 읽어 두는 보관소. 테스트는 [of] 로 가짜를 넣는다. */
@Component
class ExerciseVectorStore {
    private var fixed: ExerciseVectors? = null
    private var useFixed = false
    private val loaded: ExerciseVectors? by lazy { ExerciseVectors.loadDefault() }

    fun get(): ExerciseVectors? = if (useFixed) fixed else loaded

    companion object {
        fun of(vectors: ExerciseVectors?): ExerciseVectorStore = ExerciseVectorStore().also { it.fixed = vectors; it.useFixed = true }
    }
}

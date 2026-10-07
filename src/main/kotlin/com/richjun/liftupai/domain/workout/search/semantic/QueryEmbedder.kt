package com.richjun.liftupai.domain.workout.search.semantic

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 질의 → 임베딩 벡터. 못 구하면(꺼짐·시간 초과·차단기 열림·API 오류) null 이고, 호출자는 어휘 검색만 쓴다. */
interface QueryVectorSource {
    /** 설정으로 켜져 있는가 — 꺼져 있으면 검색 경로는 어휘 전용과 같다 */
    val enabled: Boolean

    /** L2 정규화된 768차원 벡터, 또는 null */
    fun embed(query: String): FloatArray?
}

/** 실제 호출부(테스트는 가짜로 바꾼다). 실패하면 예외를 던진다. */
fun interface EmbeddingTransport {
    fun embed(text: String, timeoutMillis: Long): FloatArray
}

/**
 * Gemini(gemini-embedding-001, RETRIEVAL_QUERY, 768차원)로 질의를 임베딩한다.
 *
 * - 짧은 시간 제한(기본 1.2초): 검색 요청이 의미 검색 때문에 늦어지지 않는다.
 * - 정규화한 질의 문자열 기준 LRU 캐시: 같은 질의(페이지 넘김 포함)는 API 를 다시 부르지 않는다.
 * - 차단기: 연속 [failureThreshold]번 실패하면 [openMillis] 동안 호출하지 않는다(그 뒤 한 번 시험 호출, 성공하면 닫힘, 실패하면 더 길게 열림).
 * - 사용자 식별자는 보내지 않는다 — 질의 문자열만 간다. API 키는 로그·예외 메시지에 남기지 않는다(헤더로만 전송).
 * - 설정 search.semantic.enabled=false(기본, 테스트)이거나 키가 비어 있으면 아무 일도 하지 않는다.
 */
@Component
class QueryEmbedder(
    @Value("\${search.semantic.enabled:false}") private val configuredEnabled: Boolean,
    @Value("\${gemini.api-key:}") private val apiKey: String,
    @Value("\${search.semantic.timeout-ms:1200}") private val timeoutMillis: Long,
    @Value("\${search.semantic.cache-size:2048}") cacheSize: Int,
    @Value("\${search.semantic.breaker-failures:5}") private val failureThreshold: Int,
    @Value("\${search.semantic.breaker-open-ms:30000}") private val openMillis: Long,
    private val transport: EmbeddingTransport? = null
) : QueryVectorSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override val enabled: Boolean get() = configuredEnabled && (transport != null || apiKey.isNotBlank())

    private val real: EmbeddingTransport by lazy { transport ?: GeminiEmbeddingTransport(apiKey) }

    internal var clock: () -> Long = System::currentTimeMillis

    private val cache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > cacheSize
    }

    // 차단기 상태 — consecutiveFailures 가 임계에 닿으면 openUntil 까지 호출하지 않는다
    private val consecutiveFailures = AtomicInteger()
    private val openUntil = AtomicLong(0L)
    private val tripCount = AtomicInteger()
    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 실제 API 호출 횟수(테스트·운영 점검용) */
    internal val callCount = AtomicInteger()

    internal fun cachedCount(): Int = synchronized(cache) { cache.size }

    internal fun breakerOpen(): Boolean = clock() < openUntil.get()

    override fun embed(query: String): FloatArray? {
        if (!enabled) return null
        val key = cacheKey(query) ?: return null
        synchronized(cache) { cache[key] }?.let { return it }
        // 차단기: 열려 있으면 호출하지 않는다. 시간이 지났으면 한 요청만 시험 호출(half-open)을 한다.
        val now = clock()
        if (now < openUntil.get()) return null
        val tripped = consecutiveFailures.get() >= failureThreshold
        if (tripped && !probing.compareAndSet(false, true)) return null
        try {
            callCount.incrementAndGet()
            val v = normalize(real.embed(key, timeoutMillis))
            consecutiveFailures.set(0)
            tripCount.set(0)
            synchronized(cache) { cache[key] = v }
            return v
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= failureThreshold) {
                val trips = tripCount.incrementAndGet().coerceAtMost(MAX_BACKOFF_SHIFT)
                openUntil.set(clock() + (openMillis shl (trips - 1)))
                logger.warn("Semantic search query embedding circuit opened after {} consecutive failures: {}", failures, safeMessage(e))
            } else logger.debug("Query embedding failed: {}", safeMessage(e))
            return null
        } finally {
            if (tripped) probing.set(false)
        }
    }

    private fun safeMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return (e.javaClass.simpleName + ": " + (if (apiKey.isNotBlank()) m.replace(apiKey, "<key>") else m)).take(200)
    }

    companion object {
        const val DIM = 768
        private const val MAX_BACKOFF_SHIFT = 4

        /** 캐시·전송 기준 질의: NFKC·소문자·공백 정리. 비면 null */
        fun cacheKey(query: String): String? {
            val k = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), " ").trim()
            return k.ifEmpty { null }?.take(400)
        }

        fun normalize(v: FloatArray): FloatArray {
            require(v.size == DIM) { "query embedding dim ${v.size} != $DIM" }
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            val norm = Math.sqrt(n)
            require(norm > 0 && norm.isFinite()) { "degenerate embedding" }
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}

/** Gemini embedContent REST 호출 — 키는 x-goog-api-key 헤더로만 보내 URL·예외 메시지에 섞이지 않게 한다. */
internal class GeminiEmbeddingTransport(
    private val apiKey: String,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"
) : EmbeddingTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun embed(text: String, timeoutMillis: Long): FloatArray {
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to "models/gemini-embedding-001",
                "content" to mapOf("parts" to listOf(mapOf("text" to text))),
                "taskType" to "RETRIEVAL_QUERY",
                "outputDimensionality" to QueryEmbedder.DIM
            )
        )
        val request = Request.Builder().url(endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("embedContent HTTP ${resp.code}")
            val values = mapper.readTree(resp.body?.string() ?: throw IllegalStateException("empty body"))["embedding"]?.get("values")
                ?: throw IllegalStateException("no embedding in response")
            return FloatArray(values.size()) { values[it].floatValue() }
        }
    }
}

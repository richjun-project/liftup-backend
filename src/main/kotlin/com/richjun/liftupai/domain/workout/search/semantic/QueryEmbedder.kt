package com.richjun.liftupai.domain.workout.search.semantic

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.richjun.liftupai.domain.workout.search.ops.LlmBreaker
import com.richjun.liftupai.domain.workout.search.ops.LlmContentException
import com.richjun.liftupai.domain.workout.search.ops.LlmFailureClass
import com.richjun.liftupai.domain.workout.search.ops.LlmFailures
import com.richjun.liftupai.domain.workout.search.ops.LlmHttpException
import com.richjun.liftupai.domain.workout.search.ops.LlmKind
import com.richjun.liftupai.domain.workout.search.ops.NegativeCache
import com.richjun.liftupai.domain.workout.search.ops.SearchLlmGuard
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * - 정규화한 질의 문자열 기준 LRU 캐시: 같은 질의(페이지 넘김 포함)는 API 를 다시 부르지 않는다. 같은 질의가 동시에 오면 호출 하나를 공유한다.
 * - 내용 실패(차원 불일치·빈 응답·차단)는 질의별 음성 캐시에 잠시 두고 차단기에 세지 않는다. 전송 오류·시간 초과·5xx 만 연속 [failureThreshold]번이면 [openMillis] 동안 차단,
 *   429 는 Retry-After 만큼, 인증 오류는 길게 연다([LlmBreaker]).
 * - 전역 예산([SearchLlmGuard]): 분당·일일 한도를 넘으면 호출하지 않는다(채팅·플랜 생성과 같은 키를 굶기지 않게).
 * - 사용자 식별자는 보내지 않는다 — 질의 문자열만 간다. API 키는 로그·예외 메시지에 남기지 않는다(헤더로만 전송).
 * - 설정 search.semantic.enabled=false(기본, 테스트)이거나 키가 비어 있으면 아무 일도 하지 않는다([configured]·[keyPresent] 로 이유를 알 수 있다).
 */
@Component
class QueryEmbedder(
    @Value("\${search.semantic.enabled:false}") private val configuredEnabled: Boolean,
    @Value("\${search.llm.api-key:\${gemini.api-key:}}") private val apiKey: String,
    @Value("\${search.semantic.timeout-ms:1200}") private val timeoutMillis: Long,
    @Value("\${search.semantic.cache-size:2048}") cacheSize: Int,
    @Value("\${search.semantic.breaker-failures:5}") private val failureThreshold: Int,
    @Value("\${search.semantic.breaker-open-ms:30000}") private val openMillis: Long,
    private val transport: EmbeddingTransport? = null,
    private val guard: SearchLlmGuard? = null,
    @Value("\${search.semantic.negative-cache-ms:600000}") negativeCacheMillis: Long = 600_000L
) : QueryVectorSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override val enabled: Boolean get() = configuredEnabled && (transport != null || apiKey.isNotBlank())

    /** 설정으로 켜져 있는가(키와 무관) */
    val configured: Boolean get() = configuredEnabled

    /** 호출에 쓸 수 있는 키(또는 주입된 전송)가 있는가 */
    val keyPresent: Boolean get() = transport != null || apiKey.isNotBlank()

    private val real: EmbeddingTransport by lazy { transport ?: GeminiEmbeddingTransport(apiKey) }

    internal var clock: () -> Long = System::currentTimeMillis

    private val cache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > cacheSize
    }
    private val inflight = ConcurrentHashMap<String, CompletableFuture<FloatArray?>>()
    private val negative = NegativeCache(cacheSize, negativeCacheMillis) { clock() }
    private val breaker = LlmBreaker(failureThreshold, openMillis) { clock() }

    /** 실제 API 호출 횟수(테스트·운영 점검용) */
    internal val callCount = AtomicInteger()

    internal fun cachedCount(): Int = synchronized(cache) { cache.size }

    internal fun negativeCount(): Int = negative.size()

    internal fun breakerOpen(): Boolean = breaker.isOpen()

    override fun embed(query: String): FloatArray? {
        if (!enabled) return null
        val key = cacheKey(query) ?: return null
        synchronized(cache) { cache[key] }?.let { guard?.recordCache(LlmKind.EMBED, true); return it }
        if (negative.contains(key)) { guard?.recordNegativeHit(LlmKind.EMBED); return null }
        guard?.recordCache(LlmKind.EMBED, false)
        // 같은 질의를 동시에 받으면 한 호출을 같이 쓴다
        val mine = CompletableFuture<FloatArray?>()
        val existing = inflight.putIfAbsent(key, mine)
        if (existing != null) return runCatching { existing.get(timeoutMillis + 500, TimeUnit.MILLISECONDS) }.getOrNull()
        try {
            val v = call(key)
            mine.complete(v)
            return v
        } catch (e: Throwable) {
            mine.complete(null)
            return null
        } finally {
            inflight.remove(key, mine)
        }
    }

    private fun call(key: String): FloatArray? {
        val permit = breaker.tryEnter() ?: run { guard?.recordBreakerSkip(LlmKind.EMBED); return null }
        // 전역 예산 — 넘으면 호출하지 않는다(시험 호출 자리는 돌려준다)
        if (guard != null && !guard.tryConsume(LlmKind.EMBED)) { breaker.release(permit); return null }
        callCount.incrementAndGet()
        val started = System.nanoTime()
        try {
            val raw = real.embed(key, timeoutMillis)
            val v = try { normalize(raw) } catch (e: IllegalArgumentException) { throw LlmContentException(e.message ?: "bad embedding") }
            guard?.recordCall(LlmKind.EMBED, (System.nanoTime() - started) / 1_000_000, null)
            breaker.onResponded(permit)
            synchronized(cache) { cache[key] = v }
            return v
        } catch (e: Exception) {
            val cls = LlmFailures.classify(e)
            guard?.recordCall(LlmKind.EMBED, (System.nanoTime() - started) / 1_000_000, cls)
            if (cls == LlmFailureClass.CONTENT) negative.add(key)
            val openedFor = breaker.onFailure(permit, cls, (e as? LlmHttpException)?.retryAfterMillis)
            if (openedFor != null) logger.warn("Semantic search query embedding circuit opened for {}ms ({}): {}", openedFor, cls.label, safeMessage(e))
            else logger.debug("Query embedding failed ({}): {}", cls.label, safeMessage(e))
            return null
        } finally {
            breaker.release(permit)  // Error 등으로 어느 쪽 갈래도 못 탔을 때 시험 호출 자리가 영영 잠기지 않게(이미 돌려줬으면 아무 일 없음)
        }
    }

    /** 첫 요청이 TLS·클래스 로딩 비용을 치르지 않도록 전송을 미리 만들고 한 번 부른다. 차단기·예산·지표에는 세지 않는다. 실패해도 조용히 넘어간다. */
    fun warmUp() {
        if (!enabled) return
        try {
            val t = real
            if (transport == null) t.embed("warm up", timeoutMillis * 3)
        } catch (e: Exception) {
            logger.debug("Query embedder warm-up skipped: {}", safeMessage(e))
        }
    }

    private fun safeMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return (e.javaClass.simpleName + ": " + (if (apiKey.isNotBlank()) m.replace(apiKey, "<key>") else m)).take(200)
    }

    companion object {
        const val DIM = 768
        private const val MAX_RAW_CHARS = 1000
        private const val MAX_KEY_CHARS = 400
        private val WHITESPACE = Regex("\\s+")

        /** 캐시·전송 기준 질의: NFKC·소문자·공백 정리. 비면 null */
        fun cacheKey(query: String): String? {
            // 호출자가 이미 160자로 줄여 오지만, 이 함수만 불려도 비용이 일정하도록 정규화 전에 한 번 더 자른다
            val k = Normalizer.normalize(query.take(MAX_RAW_CHARS), Normalizer.Form.NFKC).lowercase().replace(WHITESPACE, " ").trim()
            return k.ifEmpty { null }?.take(MAX_KEY_CHARS)
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
            if (!resp.isSuccessful) throw LlmHttpException(resp.code, LlmFailures.parseRetryAfter(resp.header("Retry-After")), "embedContent")
            val text = resp.body?.string() ?: throw LlmContentException("empty body")
            val root = try { mapper.readTree(text) } catch (e: com.fasterxml.jackson.core.JsonProcessingException) { throw LlmContentException("unreadable body") }
            val values = root?.get("embedding")?.get("values") ?: throw LlmContentException("no embedding in response")
            return FloatArray(values.size()) { values[it].floatValue() }
        }
    }
}

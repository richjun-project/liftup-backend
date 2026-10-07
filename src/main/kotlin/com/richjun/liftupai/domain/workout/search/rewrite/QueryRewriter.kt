package com.richjun.liftupai.domain.workout.search.rewrite

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 실제 호출부(테스트는 가짜로 바꾼다). 모델이 돌려준 텍스트(JSON)를 그대로 준다. 실패하면 예외. */
fun interface RewriteTransport {
    fun generate(prompt: String, timeoutMillis: Long): String
}

/**
 * 자연어 질의를 트레이너 눈으로 읽어 영어 운동 이름 힌트와 제외 장비로 바꾼다(Gemini generateContent).
 *
 * - 시간 제한(기본 2.5초): 검색 요청이 LLM 때문에 그보다 더 늦어지지 않는다. 병렬로 질의 임베딩도 구하므로 둘 중 느린 쪽이 상한.
 * - 정규화한 질의 기준 LRU 캐시(성공만): 같은 질의(페이지 넘김·반복 검색)는 API 를 다시 부르지 않는다. 같은 질의가 동시에 오면 호출 하나를 공유한다.
 * - 차단기: 연속 [failureThreshold]번 실패하면 [openMillis] 동안 부르지 않는다(그 뒤 한 번 시험, 성공하면 닫힘, 실패하면 열림 시간이 두 배씩 늘어남).
 * - 응답은 [QueryRewrite.parse] 로 엄격히 검증한다. 모양이 틀리면 실패로 센다.
 * - 사용자 식별자는 보내지 않는다 — 질의 문자열만 간다. API 키는 x-goog-api-key 헤더로만 보내고 로그·예외 메시지에 남기지 않는다.
 * - search.rewrite.enabled=false(기본, 테스트)이거나 키가 비어 있으면 아무 일도 하지 않는다.
 */
@Component
class QueryRewriter(
    @Value("\${search.rewrite.enabled:false}") private val configuredEnabled: Boolean,
    @Value("\${gemini.api-key:}") private val apiKey: String,
    @Value("\${search.rewrite.model:$DEFAULT_MODEL}") private val model: String,
    @Value("\${search.rewrite.timeout-ms:2500}") private val timeoutMillis: Long,
    @Value("\${search.rewrite.cache-size:4096}") cacheSize: Int,
    @Value("\${search.rewrite.breaker-failures:5}") private val failureThreshold: Int,
    @Value("\${search.rewrite.breaker-open-ms:30000}") private val openMillis: Long,
    private val transport: RewriteTransport? = null
) : QueryRewriteSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override val enabled: Boolean get() = configuredEnabled && (transport != null || apiKey.isNotBlank())

    private val real: RewriteTransport by lazy { transport ?: GeminiRewriteTransport(apiKey, model) }

    internal var clock: () -> Long = System::currentTimeMillis

    private val cache = object : LinkedHashMap<String, QueryRewrite>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryRewrite>?) = size > cacheSize
    }
    private val inflight = ConcurrentHashMap<String, CompletableFuture<QueryRewrite?>>()

    private val consecutiveFailures = AtomicInteger()
    private val openUntil = AtomicLong(0L)
    private val tripCount = AtomicInteger()
    private val probing = AtomicBoolean(false)

    /** 실제 API 호출 횟수(테스트·운영 점검용) */
    internal val callCount = AtomicInteger()

    internal fun cachedCount(): Int = synchronized(cache) { cache.size }

    internal fun breakerOpen(): Boolean = clock() < openUntil.get()

    override fun rewrite(query: String): QueryRewrite? {
        if (!enabled) return null
        val key = cacheKey(query) ?: return null
        synchronized(cache) { cache[key] }?.let { return it }
        // 같은 질의를 동시에 받으면 한 호출을 같이 쓴다
        val mine = CompletableFuture<QueryRewrite?>()
        val existing = inflight.putIfAbsent(key, mine)
        if (existing != null) return runCatching { existing.get(timeoutMillis + 500, TimeUnit.MILLISECONDS) }.getOrNull()
        try {
            val r = call(key, query.take(MAX_RAW_CHARS).replace(WHITESPACE, " ").trim().take(MAX_KEY_CHARS))
            mine.complete(r)
            return r
        } catch (e: Throwable) {
            mine.complete(null)
            return null
        } finally {
            inflight.remove(key, mine)
        }
    }

    private fun call(key: String, text: String): QueryRewrite? {
        // 차단기: 열려 있으면 부르지 않는다. 시간이 지났으면 한 요청만 시험 호출(half-open)을 한다.
        if (clock() < openUntil.get()) return null
        val tripped = consecutiveFailures.get() >= failureThreshold
        if (tripped && !probing.compareAndSet(false, true)) return null
        try {
            callCount.incrementAndGet()
            val parsed = QueryRewrite.parse(real.generate(buildPrompt(text), timeoutMillis)) ?: throw IllegalStateException("malformed rewrite response")
            consecutiveFailures.set(0)
            tripCount.set(0)
            synchronized(cache) { cache[key] = parsed }
            return parsed
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= failureThreshold) {
                val trips = tripCount.incrementAndGet().coerceAtMost(MAX_BACKOFF_SHIFT)
                openUntil.set(clock() + (openMillis shl (trips - 1)))
                logger.warn("Query rewrite circuit opened after {} consecutive failures: {}", failures, safeMessage(e))
            } else logger.debug("Query rewrite failed: {}", safeMessage(e))
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
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val MAX_BACKOFF_SHIFT = 4
        private const val MAX_RAW_CHARS = 1000
        private const val MAX_KEY_CHARS = 200
        private val WHITESPACE = Regex("\\s+")

        /**
         * 코치 프롬프트 — 5번째 검증셋 점수(상위5 0.950)를 잰 프롬프트와 글자 하나까지 같아야 한다(평가 도구의 rewrite.py 와 동일).
         * 바꾸려면 평가 입력도 같은 프롬프트로 다시 만들어야 하니 scripts/search/README.md 의 절차를 따른다.
         */
        const val PROMPT_PREFIX = "You are an expert strength & conditioning coach. A user typed the text below into the exercise search box of a gym app (it may be Japanese, Korean or English, casual or misspelled).\n" +
            "Return JSON only: {\"hints\": [up to 6 specific exercise names in plain English, most useful first, as a coach would pick for this request], \"forbid_equipment\": [subset of BARBELL, DUMBBELL, MACHINE, CABLE, KETTLEBELL, RESISTANCE_BAND that the request clearly excludes or cannot use]}.\n" +
            "If the text is already an exercise name or nickname, put its standard English name first. Text: "

        fun buildPrompt(query: String): String = PROMPT_PREFIX + query

        /** 캐시 기준 질의: NFKC·소문자·공백 정리. 비면 null (모델에는 원문을 공백만 정리해 보낸다) */
        fun cacheKey(query: String): String? {
            val k = Normalizer.normalize(query.take(MAX_RAW_CHARS), Normalizer.Form.NFKC).lowercase().replace(WHITESPACE, " ").trim()
            return k.ifEmpty { null }?.take(MAX_KEY_CHARS)
        }
    }
}

/** Gemini generateContent REST 호출 — 키는 x-goog-api-key 헤더로만 보내 URL·예외 메시지에 섞이지 않게 한다. */
internal class GeminiRewriteTransport(
    private val apiKey: String,
    model: String,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/models"
) : RewriteTransport {
    private val endpoint: String

    init {
        require(MODEL_NAME.matches(model)) { "invalid model name" }
        endpoint = "$baseUrl/$model:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun generate(prompt: String, timeoutMillis: Long): String {
        // thinkingConfig 는 보내지 않는다 — flash-lite 계열이 거부한다
        val body = mapper.writeValueAsString(
            mapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
                "generationConfig" to mapOf("temperature" to 0, "responseMimeType" to "application/json", "maxOutputTokens" to 256)
            )
        )
        val request = Request.Builder().url(endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("generateContent HTTP ${resp.code}")
            val root = mapper.readTree(resp.body?.string() ?: throw IllegalStateException("empty body"))
            return root["candidates"]?.get(0)?.get("content")?.get("parts")?.get(0)?.get("text")?.asText()
                ?: throw IllegalStateException("no text in response")
        }
    }

    companion object {
        private val MODEL_NAME = Regex("[A-Za-z0-9._-]{1,80}")
    }
}

package com.richjun.liftupai.domain.workout.search.rewrite

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

/** 실제 호출부(테스트는 가짜로 바꾼다). 모델이 돌려준 텍스트(JSON)를 그대로 준다. 실패하면 예외. */
fun interface RewriteTransport {
    fun generate(prompt: String, timeoutMillis: Long): String
}

/**
 * 자연어 질의를 트레이너 눈으로 읽어 영어 운동 이름 힌트와 제외 장비로 바꾼다(Gemini generateContent).
 *
 * - 시간 제한(기본 2.5초): 검색 요청이 LLM 때문에 그보다 더 늦어지지 않는다. 병렬로 질의 임베딩도 구하므로 둘 중 느린 쪽이 상한.
 * - 정규화한 질의 기준 LRU 캐시(성공만): 같은 질의(페이지 넘김·반복 검색)는 API 를 다시 부르지 않는다. 같은 질의가 동시에 오면 호출 하나를 공유한다.
 * - 내용 실패(거절·산문·잘림·JSON 오류)는 질의별 음성 캐시에 잠시 두고 차단기에 세지 않는다 — 한 사용자가 나쁜 질의로 모두의 차단기를 열 수 없다.
 *   전송 오류·시간 초과·5xx 가 연속 [failureThreshold]번이면 [openMillis] 동안 차단(두 배씩 늘어남), 429 는 Retry-After 만큼, 인증 오류는 길게 연다([LlmBreaker]).
 * - 전역 예산([SearchLlmGuard]): 분당·일일 한도를 넘으면 호출하지 않는다(채팅·플랜 생성과 같은 키를 굶기지 않게).
 * - 응답은 [QueryRewrite.parse] 로 엄격히 검증한다. 모양이 틀리면 내용 실패다.
 * - 사용자 식별자는 보내지 않는다 — 질의 문자열만 간다. API 키는 x-goog-api-key 헤더로만 보내고 로그·예외 메시지에 남기지 않는다.
 * - search.rewrite.enabled=false(기본, 테스트)이거나 키가 비어 있으면 아무 일도 하지 않는다([configured]·[keyPresent] 로 이유를 알 수 있다).
 */
@Component
class QueryRewriter(
    @Value("\${search.rewrite.enabled:false}") private val configuredEnabled: Boolean,
    @Value("\${search.llm.api-key:\${gemini.api-key:}}") private val apiKey: String,
    @Value("\${search.rewrite.model:$DEFAULT_MODEL}") private val model: String,
    @Value("\${search.rewrite.timeout-ms:2500}") private val timeoutMillis: Long,
    @Value("\${search.rewrite.cache-size:4096}") cacheSize: Int,
    @Value("\${search.rewrite.breaker-failures:5}") private val failureThreshold: Int,
    @Value("\${search.rewrite.breaker-open-ms:30000}") private val openMillis: Long,
    private val transport: RewriteTransport? = null,
    private val guard: SearchLlmGuard? = null,
    @Value("\${search.rewrite.negative-cache-ms:600000}") negativeCacheMillis: Long = 600_000L
) : QueryRewriteSource {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override val enabled: Boolean get() = configuredEnabled && (transport != null || apiKey.isNotBlank())

    /** 설정으로 켜져 있는가(키와 무관) */
    val configured: Boolean get() = configuredEnabled

    /** 호출에 쓸 수 있는 키(또는 주입된 전송)가 있는가 */
    val keyPresent: Boolean get() = transport != null || apiKey.isNotBlank()

    private val real: RewriteTransport by lazy { transport ?: GeminiRewriteTransport(apiKey, model.trim().ifEmpty { DEFAULT_MODEL }) }

    internal var clock: () -> Long = System::currentTimeMillis

    private val cache = object : LinkedHashMap<String, QueryRewrite>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryRewrite>?) = size > cacheSize
    }
    private val inflight = ConcurrentHashMap<String, CompletableFuture<QueryRewrite?>>()
    private val negative = NegativeCache(cacheSize, negativeCacheMillis) { clock() }
    private val breaker = LlmBreaker(failureThreshold, openMillis) { clock() }

    /** 실제 API 호출 횟수(테스트·운영 점검용) */
    internal val callCount = AtomicInteger()

    internal fun cachedCount(): Int = synchronized(cache) { cache.size }

    internal fun negativeCount(): Int = negative.size()

    internal fun breakerOpen(): Boolean = breaker.isOpen()

    override fun rewrite(query: String): QueryRewrite? {
        if (!enabled) return null
        val key = cacheKey(query) ?: return null
        synchronized(cache) { cache[key] }?.let { guard?.recordCache(LlmKind.REWRITE, true); return it }
        if (negative.contains(key)) { guard?.recordNegativeHit(LlmKind.REWRITE); return null }
        guard?.recordCache(LlmKind.REWRITE, false)
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
        val permit = breaker.tryEnter() ?: run { guard?.recordBreakerSkip(LlmKind.REWRITE); return null }
        // 전역 예산 — 넘으면 호출하지 않는다(시험 호출 자리는 돌려준다)
        if (guard != null && !guard.tryConsume(LlmKind.REWRITE)) { breaker.release(permit); return null }
        callCount.incrementAndGet()
        val started = System.nanoTime()
        try {
            val parsed = QueryRewrite.parse(real.generate(buildPrompt(text), timeoutMillis)) ?: throw LlmContentException("malformed rewrite response")
            guard?.recordCall(LlmKind.REWRITE, (System.nanoTime() - started) / 1_000_000, null)
            breaker.onResponded(permit)
            synchronized(cache) { cache[key] = parsed }
            return parsed
        } catch (e: Exception) {
            val cls = LlmFailures.classify(e)
            guard?.recordCall(LlmKind.REWRITE, (System.nanoTime() - started) / 1_000_000, cls)
            if (cls == LlmFailureClass.CONTENT) negative.add(key)
            val openedFor = breaker.onFailure(permit, cls, (e as? LlmHttpException)?.retryAfterMillis)
            if (openedFor != null) logger.warn("Query rewrite circuit opened for {}ms ({}): {}", openedFor, cls.label, safeMessage(e))
            else logger.debug("Query rewrite failed ({}): {}", cls.label, safeMessage(e))
            return null
        } finally {
            breaker.release(permit)  // Error 등으로 어느 쪽 갈래도 못 탔을 때 시험 호출 자리가 영영 잠기지 않게(이미 돌려줬으면 아무 일 없음)
        }
    }

    /** 첫 요청이 클라이언트 생성 비용을 치르지 않도록 전송을 미리 만든다(네트워크 호출 없음) */
    fun warmUp() {
        if (enabled) runCatching { real }
    }

    private fun safeMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return (e.javaClass.simpleName + ": " + (if (apiKey.isNotBlank()) m.replace(apiKey, "<key>") else m)).take(200)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
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
            if (!resp.isSuccessful) throw LlmHttpException(resp.code, LlmFailures.parseRetryAfter(resp.header("Retry-After")), "generateContent")
            val bodyText = resp.body?.string() ?: throw LlmContentException("empty body")
            val root = try { mapper.readTree(bodyText) } catch (e: com.fasterxml.jackson.core.JsonProcessingException) { throw LlmContentException("unreadable body") }
                ?: throw LlmContentException("empty body")
            // 생각(thought) 조각은 건너뛰고 본문 텍스트 조각만 이어 붙인다
            val parts = root["candidates"]?.get(0)?.get("content")?.get("parts") ?: throw LlmContentException("no text in response")
            val text = parts.filter { it.has("text") && it["thought"]?.asBoolean() != true }.joinToString("") { it["text"].asText() }
            if (text.isEmpty()) throw LlmContentException("no text in response")
            return text
        }
    }

    companion object {
        private val MODEL_NAME = Regex("[A-Za-z0-9._-]{1,80}")
    }
}

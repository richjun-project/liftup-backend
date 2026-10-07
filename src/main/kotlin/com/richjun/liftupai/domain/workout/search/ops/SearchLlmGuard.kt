package com.richjun.liftupai.domain.workout.search.ops

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** 검색이 Gemini 에 거는 호출 종류 */
enum class LlmKind(val label: String) { EMBED("embed"), REWRITE("rewrite") }

/** 실패 분류 — [countsTowardBreaker] 만 차단기를 센다. 내용 실패(거절·산문·잘림·JSON 오류)는 질의 문제이지 서버 문제가 아니다. */
enum class LlmFailureClass(val label: String) {
    TRANSPORT("transport"), TIMEOUT("timeout"), SERVER_5XX("5xx"), RATE_LIMIT("429"), AUTH("auth"), CONTENT("content");

    val countsTowardBreaker: Boolean get() = this != CONTENT
}

/** HTTP 오류 응답. [retryAfterMillis] 는 Retry-After 헤더(초 단위 정수)를 밀리초로 바꾼 값. */
class LlmHttpException(val code: Int, val retryAfterMillis: Long? = null, what: String = "request") :
    IllegalStateException("$what HTTP $code")

/** 응답은 왔지만 쓸 수 없는 내용(후보 없음·차단·빈 본문·잘못된 JSON·차원 불일치) */
class LlmContentException(message: String) : IllegalStateException(message)

object LlmFailures {
    fun classify(e: Throwable): LlmFailureClass = when {
        e is LlmContentException -> LlmFailureClass.CONTENT
        e is LlmHttpException -> when {
            e.code == 429 -> LlmFailureClass.RATE_LIMIT
            e.code == 401 || e.code == 403 -> LlmFailureClass.AUTH
            e.code >= 500 -> LlmFailureClass.SERVER_5XX
            // 그 밖의 4xx(잘못된 요청·차단된 프롬프트)는 그 질의의 문제다
            else -> LlmFailureClass.CONTENT
        }
        e is InterruptedIOException || e is java.util.concurrent.TimeoutException -> LlmFailureClass.TIMEOUT
        e is IOException -> LlmFailureClass.TRANSPORT
        // 알 수 없는 예외는 서버 쪽 문제로 본다(보수적으로 차단기에 센다)
        else -> LlmFailureClass.TRANSPORT
    }

    /** Retry-After 헤더 값(초)을 밀리초로. 없거나 읽을 수 없으면 null, 상한 10분 */
    fun parseRetryAfter(header: String?): Long? {
        val s = header?.trim()?.toLongOrNull() ?: return null
        if (s < 0) return null
        return (s * 1000).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }

    const val MAX_RETRY_AFTER_MILLIS = 600_000L
}

/**
 * 검색이 Gemini(임베딩·질의 해석)를 부르는 모든 길의 전역 관문 — 채팅·플랜 생성과 같은 키/쿼터를 쓰므로 검색이 그것을 굶기지 않게 한다.
 *
 * - 호출 예산: 분당 [perMinute] 회 + 하루(UTC) [dailyCap] 회. 넘으면 호출하지 않고(= 어휘·하이브리드 결과로) 건너뛴 횟수만 센다. 임베딩·해석 합산.
 * - 동시 대기 상한: Gemini 를 기다리는 자연어 검색이 [maxConcurrentSearches] 개를 넘으면 기다리지 않고 바로 건너뛴다(Tomcat 스레드 보호).
 * - 관측: 호출 수·분류별 실패·예산/동시성/차단기 건너뜀·캐시 적중·지연 p50/p95 를 [summaryIntervalMillis] 마다 INFO 한 줄로. 질의 문자열·사용자 식별자는 없다.
 *
 * 0 이하의 한도는 "제한 없음"이 아니라 "허용 안 함"이 되지 않도록 쓰지 않는다 — 끄려면 search.semantic.enabled / search.rewrite.enabled 를 끈다.
 */
@Component
class SearchLlmGuard(
    @Value("\${search.llm.budget-per-minute:90}") perMinute: Int = 90,
    @Value("\${search.llm.daily-cap:20000}") dailyCap: Int = 20_000,
    @Value("\${search.llm.max-concurrent-searches:20}") maxConcurrentSearches: Int = 20
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val perMinute = perMinute.coerceAtLeast(1)
    private val dailyCap = dailyCap.coerceAtLeast(1)
    private val maxConcurrent = maxConcurrentSearches.coerceAtLeast(1)

    internal var clock: () -> Long = System::currentTimeMillis

    /** 설정된 한도 한 줄(기동 로그용) */
    fun limitsLine(): String = "budget/min=$perMinute daily=$dailyCap maxConcurrentSearches=$maxConcurrent"

    // ── 예산 ─────────────────────────────────────────────────────────────────────────────────

    private val budgetLock = Any()
    private var minuteIndex = -1L
    private var minuteUsed = 0
    private var dayIndex = -1L
    private var dayUsed = 0
    private var dailyWarnedDay = -1L

    /** 호출 하나의 몫을 쓴다. 분당·일일 한도를 넘었으면 false(호출하지 말 것). */
    fun tryConsume(kind: LlmKind): Boolean {
        val now = clock()
        val ok: Boolean
        synchronized(budgetLock) {
            val m = Math.floorDiv(now, 60_000L)
            val d = Math.floorDiv(now, 86_400_000L)
            if (m != minuteIndex) { minuteIndex = m; minuteUsed = 0 }
            if (d != dayIndex) { dayIndex = d; dayUsed = 0 }
            ok = if (dayUsed >= dailyCap) {
                dailySkips.incrementAndGet()
                if (dailyWarnedDay != dayIndex) {
                    dailyWarnedDay = dayIndex
                    logger.warn("Search LLM daily budget ({} calls, UTC day) is exhausted — semantic search and query rewrite are skipped until the next UTC day; raise SEARCH_LLM_DAILY_CAP if this is expected traffic", dailyCap)
                }
                false
            }
            else if (minuteUsed >= perMinute) { minuteSkips.incrementAndGet(); false }
            else { minuteUsed++; dayUsed++; true }
        }
        if (!ok) stat(kind).budgetSkips.incrementAndGet()
        return ok
    }

    /** 오늘(UTC) 쓴 호출 수(점검용) */
    internal fun usedToday(): Int = synchronized(budgetLock) { if (Math.floorDiv(clock(), 86_400_000L) == dayIndex) dayUsed else 0 }

    // ── 동시 대기 상한 ───────────────────────────────────────────────────────────────────────

    private val searchPermits = Semaphore(maxConcurrent)
    private val concurrencySkips = AtomicLong()

    /** Gemini 를 기다릴 자연어 검색 하나의 자리를 잡는다. 꽉 찼으면 기다리지 않고 false — 호출자는 LLM 없이 진행한다. */
    fun tryEnterSearch(): Boolean {
        if (searchPermits.tryAcquire()) return true
        concurrencySkips.incrementAndGet()
        return false
    }

    fun exitSearch() { searchPermits.release() }

    internal fun waitingSearches(): Int = maxConcurrent - searchPermits.availablePermits()

    // ── 지표 ─────────────────────────────────────────────────────────────────────────────────

    class KindStat {
        val calls = AtomicLong()
        val ok = AtomicLong()
        val failures = Array(LlmFailureClass.values().size) { AtomicLong() }
        val budgetSkips = AtomicLong()
        val breakerSkips = AtomicLong()
        val cacheHits = AtomicLong()
        val cacheMisses = AtomicLong()
        val negativeHits = AtomicLong()
        private val ring = LongArray(LATENCY_SAMPLES)
        private var ringCount = 0

        @Synchronized fun addLatency(ms: Long) { ring[ringCount % LATENCY_SAMPLES] = ms; ringCount++ }

        @Synchronized fun percentiles(): Pair<Long, Long>? {
            val n = minOf(ringCount, LATENCY_SAMPLES)
            if (n == 0) return null
            val a = ring.copyOf(n).also { it.sort() }
            return a[(n + 1) / 2 - 1] to a[(95 * n + 99) / 100 - 1]  // 최근접 순위법(nearest-rank)
        }

        @Synchronized fun resetLatency() { ringCount = 0 }

        fun failureCount(c: LlmFailureClass): Long = failures[c.ordinal].get()
    }

    private val embedStat = KindStat()
    private val rewriteStat = KindStat()
    internal fun stat(kind: LlmKind): KindStat = if (kind == LlmKind.EMBED) embedStat else rewriteStat

    private val minuteSkips = AtomicLong()
    private val dailySkips = AtomicLong()
    private val searches = AtomicLong()
    private val searchesSemantic = AtomicLong()
    private val searchesRewritten = AtomicLong()
    private val searchesDegraded = AtomicLong()
    private val resultCacheHits = AtomicLong()
    private val resultCacheMisses = AtomicLong()
    private val pagedReplays = AtomicLong()

    /** 실제 API 호출을 끝냈다 — 성공이면 [failure] 가 null */
    fun recordCall(kind: LlmKind, latencyMillis: Long, failure: LlmFailureClass?) {
        val s = stat(kind)
        s.calls.incrementAndGet()
        s.addLatency(latencyMillis.coerceAtLeast(0))
        if (failure == null) s.ok.incrementAndGet() else s.failures[failure.ordinal].incrementAndGet()
    }

    fun recordCache(kind: LlmKind, hit: Boolean) { (if (hit) stat(kind).cacheHits else stat(kind).cacheMisses).incrementAndGet() }
    fun recordNegativeHit(kind: LlmKind) { stat(kind).negativeHits.incrementAndGet() }
    fun recordBreakerSkip(kind: LlmKind) { stat(kind).breakerSkips.incrementAndGet() }

    /** 의미·LLM 이 필요했던 검색 하나의 결과 */
    fun recordSearch(semantic: Boolean, rewritten: Boolean, degraded: Boolean) {
        searches.incrementAndGet()
        if (semantic) searchesSemantic.incrementAndGet()
        if (rewritten) searchesRewritten.incrementAndGet()
        if (degraded) searchesDegraded.incrementAndGet()
    }

    fun recordResultCache(hit: Boolean) { (if (hit) resultCacheHits else resultCacheMisses).incrementAndGet() }
    fun recordPagedReplay() { pagedReplays.incrementAndGet() }

    private fun rate(hit: Long, miss: Long): String = if (hit + miss == 0L) "-" else "${(100 * hit / (hit + miss))}%"

    private fun kindLine(k: LlmKind): String {
        val s = stat(k)
        val p = s.percentiles()
        val fails = LlmFailureClass.values().filter { s.failureCount(it) > 0 }.joinToString(",") { "${it.label}=${s.failureCount(it)}" }.ifEmpty { "none" }
        return "${k.label}[calls=${s.calls.get()} ok=${s.ok.get()} fail($fails) skipBudget=${s.budgetSkips.get()} skipBreaker=${s.breakerSkips.get()} " +
            "cache=${rate(s.cacheHits.get(), s.cacheMisses.get())} negHit=${s.negativeHits.get()} p50=${p?.first ?: "-"}ms p95=${p?.second ?: "-"}ms]"
    }

    /** 요약 한 줄 — 질의 문자열·사용자 식별자 없음. [reset] 이면 이번 구간 지표를 비운다. */
    fun summary(reset: Boolean = true): String {
        val line = "Search LLM summary: ${kindLine(LlmKind.EMBED)} ${kindLine(LlmKind.REWRITE)} " +
            "searches[llm=${searches.get()} semantic=${searchesSemantic.get()} rewritten=${searchesRewritten.get()} degraded=${searchesDegraded.get()}] " +
            "resultCache=${rate(resultCacheHits.get(), resultCacheMisses.get())} pagedReplay=${pagedReplays.get()} " +
            "skip[minuteBudget=${minuteSkips.get()} dailyBudget=${dailySkips.get()} concurrency=${concurrencySkips.get()}] " +
            "budget[usedToday=${usedToday()}/$dailyCap perMin=$perMinute] waiting=${waitingSearches()}/$maxConcurrent"
        if (reset) resetWindow()
        return line
    }

    private fun resetWindow() {
        for (s in listOf(embedStat, rewriteStat)) {
            s.calls.set(0); s.ok.set(0); s.failures.forEach { it.set(0) }
            s.budgetSkips.set(0); s.breakerSkips.set(0); s.cacheHits.set(0); s.cacheMisses.set(0); s.negativeHits.set(0)
            s.resetLatency()
        }
        minuteSkips.set(0); dailySkips.set(0); concurrencySkips.set(0)
        searches.set(0); searchesSemantic.set(0); searchesRewritten.set(0); searchesDegraded.set(0)
        resultCacheHits.set(0); resultCacheMisses.set(0); pagedReplays.set(0)
    }

    private fun idle(): Boolean =
        listOf(embedStat, rewriteStat).all { it.calls.get() == 0L && it.budgetSkips.get() == 0L && it.breakerSkips.get() == 0L && it.cacheHits.get() == 0L && it.cacheMisses.get() == 0L } &&
            searches.get() == 0L && concurrencySkips.get() == 0L

    /** 주기 요약(기본 5분). 활동이 하나도 없으면 로그를 남기지 않는다. */
    @Scheduled(fixedDelayString = "\${search.llm.summary-interval-ms:300000}", initialDelayString = "\${search.llm.summary-interval-ms:300000}")
    fun logSummary() {
        if (idle()) return
        logger.info(summary())
    }

    companion object {
        private const val LATENCY_SAMPLES = 1024
    }
}

package com.richjun.liftupai.domain.workout.search.ops

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 외부 API 차단기(임베딩·해석 공용).
 * - 연속 [threshold]번 "서버 쪽" 실패(전송 오류·시간 초과·5xx)면 [openMillis] 동안 열린다. 다시 실패하면 열림 시간이 두 배씩(최대 8배) 늘어난다.
 * - 429 는 한 번으로 열린다 — Retry-After 가 있으면 그만큼(최소 [openMillis], 최대 10분), 없으면 [openMillis].
 * - 인증 오류(401/403)는 한 번으로 [AUTH_OPEN_MILLIS] 동안 열린다(잘못된 키로 호출을 두드리지 않게).
 * - 내용 실패(거절·산문·잘림·JSON 오류)는 서버가 멀쩡히 답한 것이라 차단기에 세지 않고 오히려 연속 실패 수를 비운다.
 * - 열림 시간이 지나면 한 요청만 시험 호출(half-open)을 하고, 성공해야 닫힌다.
 */
class LlmBreaker(
    private val threshold: Int,
    private val openMillis: Long,
    private val clock: () -> Long
) {
    private val consecutiveFailures = AtomicInteger()
    private val openUntil = AtomicLong(0L)
    private val tripCount = AtomicInteger()
    private val probing = AtomicBoolean(false)

    /** 호출 허가 — [probe] 면 시험 호출이라 끝나면 [release] 를 불러야 한다 */
    class Permit(val probe: Boolean) {
        internal val released = AtomicBoolean(false)
    }

    fun isOpen(): Boolean = clock() < openUntil.get()

    /** 호출해도 되면 허가, 열려 있거나 다른 요청이 시험 중이면 null */
    fun tryEnter(): Permit? {
        if (clock() < openUntil.get()) return null
        val tripped = consecutiveFailures.get() >= threshold
        if (tripped && !probing.compareAndSet(false, true)) return null
        return Permit(tripped)
    }

    /** 호출을 하지 않고 허가를 돌려준다(예산 초과 등) */
    fun release(p: Permit) { if (p.probe && p.released.compareAndSet(false, true)) probing.set(false) }

    /** 서버가 정상으로 답했다(내용이 쓸모없어도) */
    fun onResponded(p: Permit) {
        consecutiveFailures.set(0)
        tripCount.set(0)
        release(p)
    }

    /** 실패를 반영한다. 이번 실패로 차단기가 새로 열렸으면 열린 시간(ms)을, 아니면 null 을 돌려준다. */
    fun onFailure(p: Permit, cls: LlmFailureClass, retryAfterMillis: Long? = null): Long? {
        try {
            if (!cls.countsTowardBreaker) { consecutiveFailures.set(0); return null }
            val now = clock()
            val openFor: Long
            when (cls) {
                LlmFailureClass.RATE_LIMIT -> {
                    consecutiveFailures.set(threshold)
                    openFor = (retryAfterMillis ?: 0L).coerceAtLeast(openMillis).coerceAtMost(LlmFailures.MAX_RETRY_AFTER_MILLIS)
                }
                LlmFailureClass.AUTH -> {
                    consecutiveFailures.set(threshold)
                    openFor = maxOf(openMillis, AUTH_OPEN_MILLIS)
                }
                else -> {
                    val failures = consecutiveFailures.incrementAndGet()
                    if (failures < threshold) return null
                    val trips = tripCount.incrementAndGet().coerceAtMost(MAX_BACKOFF_SHIFT)
                    openFor = openMillis shl (trips - 1)
                }
            }
            openUntil.set(now + openFor)
            return openFor
        } finally {
            release(p)
        }
    }

    companion object {
        private const val MAX_BACKOFF_SHIFT = 4
        const val AUTH_OPEN_MILLIS = 300_000L
    }
}

/** 질의별 음성 캐시 — 내용 실패한 질의를 잠시 다시 부르지 않는다(키는 정규화한 질의, 질의 문자열은 로그에 남기지 않는다). */
class NegativeCache(private val max: Int, private val ttlMillis: Long, private val clock: () -> Long) {
    private val map = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > max
    }

    @Synchronized
    fun contains(key: String): Boolean {
        val until = map[key] ?: return false
        if (clock() >= until) { map.remove(key); return false }
        return true
    }

    @Synchronized
    fun add(key: String) { if (ttlMillis > 0) map[key] = clock() + ttlMillis }

    @Synchronized
    fun size(): Int = map.size
}

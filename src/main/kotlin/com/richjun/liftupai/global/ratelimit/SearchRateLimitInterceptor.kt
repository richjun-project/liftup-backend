package com.richjun.liftupai.global.ratelimit

import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 운동 검색·제안·추천·대체 운동 요청의 사용자별 속도 제한 — 슬라이딩 윈도우 1분, 기본 분당 120회.
 * 한도를 넘으면 429 + Retry-After(초). 로그인하지 않은 요청은 접속 IP 로 센다.
 * 이 경로들은 CPU(검색 엔진)·DB 를 쓰므로, 무료 계정 하나가 서버를 포화시키지 못하게 하는 마지막 방어선이다.
 */
@Component
class SearchRateLimitInterceptor(
    private val rateLimitProperties: RateLimitProperties
) : HandlerInterceptor {

    private class Bucket {
        val timestamps = ArrayDeque<Long>()
        val lock = ReentrantLock()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** 테스트에서 시간을 제어하기 위한 시계 */
    internal var clock: () -> Long = System::currentTimeMillis

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method == "OPTIONS") return true
        val limit = rateLimitProperties.search.perMinute
        if (limit <= 0) return true

        val key = clientKey(request)
        val bucket = buckets.computeIfAbsent(key) { Bucket() }
        bucket.lock.withLock {
            val now = clock()
            val windowStart = now - WINDOW_MILLIS
            while (bucket.timestamps.isNotEmpty() && bucket.timestamps.peekFirst() <= windowStart) bucket.timestamps.pollFirst()

            if (bucket.timestamps.size >= limit) {
                val retryAfter = ((bucket.timestamps.peekFirst() + WINDOW_MILLIS - now + 999) / 1000).coerceIn(1, 60)
                response.setHeader("Retry-After", retryAfter.toString())
                response.setHeader("X-RateLimit-Remaining", "0")
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.contentType = "application/json;charset=UTF-8"
                response.writer.write(
                    """{"success":false,"error":{"code":"RATE_LIMIT","message":"${message(request)}"}}"""
                )
                return false
            }
            bucket.timestamps.addLast(now)
            response.setHeader("X-RateLimit-Remaining", (limit - bucket.timestamps.size).toString())
            return true
        }
    }

    /** 1분마다 비활성 버킷 정리 */
    @Scheduled(fixedRate = 60_000)
    fun evictStaleBuckets() {
        val cutoff = clock() - WINDOW_MILLIS
        buckets.entries.removeIf { (_, bucket) ->
            bucket.lock.withLock { bucket.timestamps.isEmpty() || (bucket.timestamps.peekLast() ?: 0L) <= cutoff }
        }
    }

    private fun clientKey(request: HttpServletRequest): String {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal is CustomUserDetails) return "u:${principal.getId()}"
        return "ip:${request.remoteAddr}"
    }

    private fun message(request: HttpServletRequest): String {
        val lang = (request.getParameter("locale") ?: request.getHeader("Accept-Language") ?: "en").trim().lowercase()
        return when {
            lang.startsWith("ko") -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
            lang.startsWith("ja") -> "リクエストが多すぎます。しばらくしてからもう一度お試しください。"
            else -> "Too many requests. Please try again later."
        }
    }

    companion object {
        private const val WINDOW_MILLIS = 60_000L
    }
}

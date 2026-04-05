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

@Component
class RateLimitInterceptor(
    private val rateLimitProperties: RateLimitProperties
) : HandlerInterceptor {

    private data class UserBucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        val lock: ReentrantLock = ReentrantLock()
    )

    private val buckets = ConcurrentHashMap<Long, UserBucket>()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        if (request.method == "OPTIONS") return true

        val userId = getCurrentUserId() ?: return true
        val bucket = buckets.computeIfAbsent(userId) { UserBucket() }

        return bucket.lock.withLock {
            val now = System.currentTimeMillis()
            val timestamps = bucket.timestamps

            // 24시간 이상 된 기록 제거 (앞에서부터 제거 — 시간순 정렬)
            val oneDayAgo = now - 86_400_000L
            while (timestamps.isNotEmpty() && timestamps.peekFirst() < oneDayAgo) {
                timestamps.pollFirst()
            }

            val oneMinuteAgo = now - 60_000L
            val oneHourAgo = now - 3_600_000L

            val countLastMinute = timestamps.count { it >= oneMinuteAgo }
            val countLastHour = timestamps.count { it >= oneHourAgo }
            val countLastDay = timestamps.size

            val retryAfterSeconds = when {
                countLastMinute >= rateLimitProperties.chat.perMinute -> {
                    val oldest = timestamps.first { it >= oneMinuteAgo }
                    ((oldest + 60_000L - now) / 1000).coerceAtLeast(1)
                }
                countLastHour >= rateLimitProperties.chat.perHour -> {
                    val oldest = timestamps.first { it >= oneHourAgo }
                    ((oldest + 3_600_000L - now) / 1000).coerceAtLeast(1)
                }
                countLastDay >= rateLimitProperties.chat.perDay -> {
                    val oldest = timestamps.peekFirst()!!
                    ((oldest + 86_400_000L - now) / 1000).coerceAtLeast(1)
                }
                else -> null
            }

            if (retryAfterSeconds != null) {
                response.setHeader("Retry-After", retryAfterSeconds.toString())
                response.setHeader("X-RateLimit-Remaining", "0")
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.contentType = "application/json"
                response.writer.write(
                    """{"success":false,"error":{"code":"RATE_LIMIT","message":"Too many requests. Please try again later."}}"""
                )
                return@withLock false
            }

            timestamps.addLast(now)

            val remaining = minOf(
                rateLimitProperties.chat.perMinute - countLastMinute - 1,
                rateLimitProperties.chat.perHour - countLastHour - 1,
                rateLimitProperties.chat.perDay - countLastDay - 1
            ).coerceAtLeast(0)
            response.setHeader("X-RateLimit-Remaining", remaining.toString())

            true
        }
    }

    /** 1시간마다 비활성 사용자 버킷 정리 */
    @Scheduled(fixedRate = 3_600_000)
    fun evictStaleBuckets() {
        val cutoff = System.currentTimeMillis() - 86_400_000L
        buckets.entries.removeIf { (_, bucket) ->
            bucket.lock.withLock {
                bucket.timestamps.isEmpty() || (bucket.timestamps.peekLast() ?: 0) < cutoff
            }
        }
    }

    private fun getCurrentUserId(): Long? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        val principal = auth.principal
        if (principal is CustomUserDetails) {
            return principal.getId()
        }
        return null
    }
}

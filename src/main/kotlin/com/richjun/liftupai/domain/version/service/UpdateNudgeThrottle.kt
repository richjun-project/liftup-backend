package com.richjun.liftupai.domain.version.service

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 권장 업데이트 안내창을 같은 기기에 [INTERVAL] 에 한 번만 띄우게 한다.
 * 1.1.29 이하 앱은 '나중에'를 기억하지 못해 매번 앱을 열 때마다 창이 뜬다 — 서버가 대신 간격을 둔다.
 * 메모리에만 두므로 서버를 재시작하면 한 번 더 뜰 수 있다(그 정도는 괜찮다).
 */
@Component
class UpdateNudgeThrottle {
    private val lastShown = ConcurrentHashMap<String, Instant>()

    /** 테스트에서 시계를 돌린다 */
    internal var now: () -> Instant = Instant::now

    fun tryAcquire(deviceKey: String): Boolean {
        val at = now()
        if (lastShown.size > MAX_DEVICES) lastShown.clear()
        var acquired = false
        lastShown.compute(deviceKey) { _, prev ->
            if (prev == null || Duration.between(prev, at) >= INTERVAL) {
                acquired = true
                at
            } else {
                prev
            }
        }
        return acquired
    }

    companion object {
        val INTERVAL: Duration = Duration.ofHours(20)
        private const val MAX_DEVICES = 50_000
    }
}

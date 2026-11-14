package com.richjun.liftupai.domain.workout.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** 세션 데이터 품질 판정(2026-11-14) — 304: 15세트 46초. 몰아서 기록한 실제 운동은 시작~끝이 길어 걸리지 않는다. */
class SessionQualityTest {
    private val t0 = LocalDateTime.of(2026, 11, 13, 14, 44, 0)

    @Test
    @DisplayName("304 재현: 15세트를 46초에 끝낸 세션은 표시된다, 3세트를 20초에 끝내도 표시된다(최소 1분)")
    fun flagsInhumanSpeed() {
        assertTrue(SessionQuality.implausible(t0, t0.plusSeconds(7), 15))
        assertTrue(SessionQuality.implausible(t0, t0.plusSeconds(46), 15)) // 304 운영 DB: 15세트·46초
        assertTrue(SessionQuality.implausible(t0, t0.plusSeconds(20), 3))
        assertTrue(SessionQuality.implausible(t0, t0.plusSeconds(119), 12)) // 12세트 × 10초 = 120초 미만
    }

    @Test
    @DisplayName("운동 뒤 몰아서 기록한 실제 세션(시작~끝 40분, 세트 완료 시각은 끝에 몰림)·짧은 정상 세션·한두 세트 기록은 표시되지 않는다")
    fun doesNotFlagRealSessions() {
        assertFalse(SessionQuality.implausible(t0, t0.plusMinutes(40), 15))
        assertFalse(SessionQuality.implausible(t0, t0.plusSeconds(120), 12))
        assertFalse(SessionQuality.implausible(t0, t0.plusMinutes(6), 9))
        assertFalse(SessionQuality.implausible(t0, t0.plusSeconds(5), 2)) // 시험 기록은 판정하지 않는다
        assertFalse(SessionQuality.implausible(null, t0, 15))
    }
}

package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.util.SessionDurationFitter.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionDurationFitterTest {

    private fun minutes(slots: List<Slot>) = SessionDurationFitter.estimateSeconds(slots) / 60.0

    @Test
    fun `work seconds parse reps, timed sets and unknown`() {
        assertEquals(27.0, SessionDurationFitter.workSeconds("8-10"))
        assertEquals(36.0, SessionDurationFitter.workSeconds("12"))
        assertEquals(45.0, SessionDurationFitter.workSeconds("30-60s"))
        assertEquals(45.0, SessionDurationFitter.workSeconds("time-based"))
    }

    @Test
    fun `unitless 30-60 is treated as seconds, not 45 reps of 3 seconds`() {
        // 플랭크 "30-60" 을 반복으로 세면 135초/세트로 부풀려져 30분 세션이 과하게 잘렸다
        assertEquals(45.0, SessionDurationFitter.workSeconds("30-60"))
        assertEquals(30.0, SessionDurationFitter.workSeconds("30"))
        assertEquals(60.0, SessionDurationFitter.workSeconds("15-25"))
    }

    @Test
    fun `20 minute request with four long exercises is cut down to fit`() {
        // 골든셋 g_grid_05_ko: 20분 요청에 추정 49분
        val slots = listOf(
            Slot(4, "6-8", 180, 150), Slot(4, "8-10", 120, 90),
            Slot(4, "8-10", 120, 90), Slot(3, "12-15", 60, 45),
        )
        val fitted = SessionDurationFitter.fit(slots, 20)
        assertTrue(minutes(fitted) <= 21.0, "fitted ${minutes(fitted)}")
        assertTrue(fitted.size >= 3)
        assertTrue(fitted.all { it.sets >= 2 })
        // 첫 주력 종목은 남는다
        assertEquals("6-8", fitted.first().reps)
    }

    @Test
    fun `a session that already fits is untouched`() {
        val slots = listOf(Slot(3, "8-10", 90, 60), Slot(3, "10-12", 60, 45), Slot(3, "12-15", 60, 45))
        val est = minutes(slots)
        val fitted = SessionDurationFitter.fit(slots, est.toInt())
        assertEquals(slots.size, fitted.size)
        assertTrue(minutes(fitted) in est * 0.9..est * 1.06)
    }

    @Test
    fun `short plan for a long request gains sets but never above five`() {
        val slots = listOf(Slot(3, "8-10", 90, 60), Slot(3, "8-10", 90, 60), Slot(3, "10-12", 60, 45))
        val fitted = SessionDurationFitter.fit(slots, 60)
        assertTrue(fitted.all { it.sets <= 5 })
        assertTrue(minutes(fitted) > minutes(slots))
        assertTrue(minutes(fitted) <= 63.0)
    }

    @Test
    fun `rest is never cut below the prescription floor`() {
        val slots = listOf(Slot(5, "4-6", 210, 180), Slot(4, "6-8", 150, 120), Slot(4, "6-8", 150, 120))
        val fitted = SessionDurationFitter.fit(slots, 20)
        fitted.zip(slots).forEach { (f, s) -> assertTrue(f.restSeconds >= s.minRestSeconds) }
    }

    @Test
    fun `plan days keep every exercise when allowDrop is false`() {
        val slots = List(6) { Slot(4, "8-10", 120, 90) }
        val fitted = SessionDurationFitter.fit(slots, 20, allowDrop = false)
        assertEquals(6, fitted.size)
        assertTrue(fitted.all { it.sets >= 2 && it.restSeconds >= 90 })
    }
}

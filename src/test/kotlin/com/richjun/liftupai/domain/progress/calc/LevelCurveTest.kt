package com.richjun.liftupai.domain.progress.calc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LevelCurveTest {
    private fun formula(l: Int) = Math.round(22 * Math.pow((l - 1).toDouble(), 1.9))

    @Test fun `누적 XP 공식`() {
        assertEquals(0L, LevelCurve.cumulativeXp(1))
        assertEquals(22L, LevelCurve.cumulativeXp(2))
        for (l in 2..80) assertEquals(formula(l), LevelCurve.cumulativeXp(l))
    }

    @Test fun `0 XP는 레벨 1`() {
        assertEquals(1, LevelCurve.levelFor(0))
        assertEquals(1, LevelCurve.levelFor(LevelCurve.cumulativeXp(2) - 1))
    }

    @Test fun `경계에서 정확히 레벨이 오른다`() {
        for (l in 2..70) {
            val need = LevelCurve.cumulativeXp(l)
            assertEquals(l, LevelCurve.levelFor(need), "Lv$l at $need")
            assertEquals(l - 1, LevelCurve.levelFor(need - 1), "Lv${l - 1} at ${need - 1}")
        }
    }

    @Test fun `레벨 5 경계 - 306 부근`() {
        val need = formula(5) // 22*4^1.9 ≈ 306.5
        assertTrue(need in 306..307)
        assertEquals(5, LevelCurve.levelFor(need))
        assertEquals(4, LevelCurve.levelFor(need - 1))
    }

    @Test fun `클래스 경계`() {
        assertEquals("novice", LevelCurve.classKey(1))
        assertEquals("novice", LevelCurve.classKey(7))
        assertEquals("apprentice", LevelCurve.classKey(8))
        assertEquals("apprentice", LevelCurve.classKey(19))
        assertEquals("warrior", LevelCurve.classKey(20))
        assertEquals("warrior", LevelCurve.classKey(39))
        assertEquals("champion", LevelCurve.classKey(40))
        assertEquals("champion", LevelCurve.classKey(64))
        assertEquals("legend", LevelCurve.classKey(65))
        assertEquals("legend", LevelCurve.classKey(99))
    }

    @Test fun `다음 진화 레벨`() {
        assertEquals(8, LevelCurve.nextEvolutionLevel(1))
        assertEquals(20, LevelCurve.nextEvolutionLevel(8))
        assertEquals(40, LevelCurve.nextEvolutionLevel(39))
        assertEquals(65, LevelCurve.nextEvolutionLevel(40))
        assertNull(LevelCurve.nextEvolutionLevel(65))
    }
}

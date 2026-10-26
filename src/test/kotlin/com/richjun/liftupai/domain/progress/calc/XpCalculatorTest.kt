package com.richjun.liftupai.domain.progress.calc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class XpCalculatorTest {
    private val utc = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 10, 5) // 월요일

    private fun at(d: LocalDate, hour: Int = 12): LocalDateTime = d.atTime(hour, 0)
    private fun set(w: Double, r: Int, done: Boolean = true) = XpSetInput(w, r, done)
    private fun session(id: Long, d: LocalDate, vararg ex: XpExerciseInput, hour: Int = 12) =
        XpSessionInput(id, at(d, hour), ex.toList())
    private fun ex(id: Long, vararg sets: XpSetInput) = XpExerciseInput(id, sets.toList())
    private fun calc(
        sessions: List<XpSessionInput>, meals: List<LocalDateTime> = emptyList(),
        bw: Double? = 70.0, goal: Int? = 3, zone: ZoneId = utc
    ) = XpCalculator.calculate(XpInput(sessions, meals, bw, goal, zone))

    @Test fun `세트 XP - 강도 계산`() {
        assertEquals(13, XpCalculator.setXp(100.0, 5, 70.0)) // 100*5/70/2=3.57
        assertEquals(20, XpCalculator.setXp(200.0, 10, 70.0)) // 강도 상한 10
        assertEquals(10, XpCalculator.setXp(100.0, 0, 70.0)) // reps 0 → 기본만
        assertEquals(10, XpCalculator.setXp(100.0, -3, 70.0))
    }

    @Test fun `맨몸 세트는 체중의 절반을 중량으로 본다`() {
        assertEquals(15, XpCalculator.setXp(0.0, 20, 70.0)) // 35*20/70/2=5
    }

    @Test fun `체중이 없거나 잘못되면 70kg 기본값`() {
        assertEquals(XpCalculator.setXp(0.0, 20, 70.0), XpCalculator.setXp(0.0, 20, null))
        assertEquals(XpCalculator.setXp(0.0, 20, 70.0), XpCalculator.setXp(0.0, 20, -5.0))
        assertEquals(XpCalculator.setXp(0.0, 20, 70.0), XpCalculator.setXp(0.0, 20, Double.NaN))
        assertEquals(XpCalculator.setXp(100.0, 10, 70.0), XpCalculator.setXp(100.0, 10, 0.0))
    }

    @Test fun `완료한 세트만 센다`() {
        val r = calc(listOf(session(1, monday, ex(1, set(100.0, 5), set(100.0, 5, done = false)))), goal = 7)
        val s = r.sessions.getValue(1)
        assertEquals(1, s.setCount)
        assertEquals(13, s.setXp)
    }

    @Test fun `완료 보너스 50`() {
        val r = calc(listOf(session(1, monday)), goal = 7)
        assertEquals(50L, r.totalXp)
    }

    @Test fun `첫 등장 운동은 PR이 아니다`() {
        val r = calc(listOf(session(1, monday, ex(1, set(100.0, 5)))), goal = 7)
        assertEquals(0, r.sessions.getValue(1).prCount)
    }

    @Test fun `이전 최고보다 e1RM이 엄격히 크면 PR`() {
        val r = calc(listOf(
            session(1, monday, ex(1, set(100.0, 5))),
            session(2, monday.plusDays(1), ex(1, set(100.0, 5))), // 동률은 PR 아님
            session(3, monday.plusDays(2), ex(1, set(105.0, 5))),
            session(4, monday.plusDays(3), ex(2, set(50.0, 5)))   // 다른 운동의 첫 등장
        ), goal = 7)
        assertEquals(0, r.sessions.getValue(2).prCount)
        assertEquals(1, r.sessions.getValue(3).prCount)
        assertEquals(100, r.sessions.getValue(3).prXp)
        assertEquals(0, r.sessions.getValue(4).prCount)
    }

    @Test fun `세션당 PR은 최대 3개`() {
        val r = calc(listOf(
            session(1, monday, ex(1, set(100.0, 5))),
            session(2, monday.plusDays(1), ex(1, set(101.0, 5), set(102.0, 5), set(103.0, 5), set(104.0, 5), set(105.0, 5)))
        ), goal = 7)
        assertEquals(3, r.sessions.getValue(2).prCount)
        assertEquals(300, r.sessions.getValue(2).prXp)
    }

    @Test fun `PR 판정은 시간순 - 입력 순서와 무관`() {
        val r = calc(listOf(
            session(2, monday.plusDays(1), ex(1, set(105.0, 5))),
            session(1, monday, ex(1, set(100.0, 5)))
        ), goal = 7)
        assertEquals(0, r.sessions.getValue(1).prCount)
        assertEquals(1, r.sessions.getValue(2).prCount)
    }

    @Test fun `맨몸 운동도 반복수가 늘면 PR`() {
        val r = calc(listOf(
            session(1, monday, ex(1, set(0.0, 10))),
            session(2, monday.plusDays(1), ex(1, set(0.0, 12)))
        ), goal = 7)
        assertEquals(1, r.sessions.getValue(2).prCount)
    }

    @Test fun `주간 목표는 달성한 날 300 한 번`() {
        val r = calc(listOf(
            session(1, monday), session(2, monday.plusDays(1)), session(3, monday.plusDays(2)),
            session(4, monday.plusDays(3))
        ), goal = 3)
        assertEquals(0, r.sessions.getValue(2).weeklyGoalXp)
        assertEquals(300, r.sessions.getValue(3).weeklyGoalXp)
        assertEquals(0, r.sessions.getValue(4).weeklyGoalXp)
        assertEquals(300, r.days.getValue(monday.plusDays(2)).weeklyGoalXp)
    }

    @Test fun `주간 목표 기본값은 3 이고 주는 월-일 기준`() {
        // 일요일 + 다음 월요일 → 다른 주
        val sunday = monday.minusDays(1)
        val r = calc(listOf(session(1, sunday), session(2, monday), session(3, monday.plusDays(1))), goal = null)
        assertTrue(r.sessions.values.all { it.weeklyGoalXp == 0 })
        val r2 = calc(listOf(session(1, monday), session(2, monday.plusDays(1)), session(3, monday.plusDays(6))), goal = null)
        assertEquals(300, r2.sessions.getValue(3).weeklyGoalXp)
    }

    @Test fun `주 경계는 사용자 시간대 기준`() {
        // UTC 일요일 16:00 = 서울 월요일 01:00
        val seoul = ZoneId.of("Asia/Seoul")
        val sundayUtc = XpSessionInput(1, monday.minusDays(1).atTime(16, 0), emptyList())
        val r = calc(listOf(sundayUtc), goal = 1, zone = seoul)
        assertEquals(monday, r.sessions.getValue(1).date)
        val rUtc = calc(listOf(sundayUtc), goal = 1, zone = utc)
        assertEquals(monday.minusDays(1), rUtc.sessions.getValue(1).date)
    }

    @Test fun `식사는 한 번에 5 하루 최대 20`() {
        val meals = (1..6).map { at(monday, it) }
        val r = calc(emptyList(), meals)
        assertEquals(20L, r.totalXp)
        assertEquals(10L, calc(emptyList(), meals.take(2)).totalXp)
        assertEquals(40L, calc(emptyList(), meals + meals.map { it.plusDays(1) }).totalXp)
    }

    private fun twoSessionWeeks(n: Int): List<XpSessionInput> =
        (0 until n).flatMap { w ->
            val base = monday.minusWeeks((n - 1).toLong()).plusWeeks(w.toLong())
            listOf(session(w * 2L + 1, base), session(w * 2L + 2, base.plusDays(1)))
        }

    @Test fun `연속 활성 주 배율 1_0 1_1 1_2 1_3`() {
        fun lastDayMult(n: Int) = calc(twoSessionWeeks(n), goal = 7).days.getValue(monday.plusDays(1)).multiplier
        assertEquals(1.0, lastDayMult(1))
        assertEquals(1.1, lastDayMult(2))
        assertEquals(1.1, lastDayMult(3))
        assertEquals(1.2, lastDayMult(4))
        assertEquals(1.2, lastDayMult(7))
        assertEquals(1.3, lastDayMult(8))
    }

    @Test fun `배율 적용 - 50XP가 55로`() {
        val r = calc(twoSessionWeeks(2), goal = 7)
        assertEquals(55, r.days.getValue(monday).cappedXp)
    }

    @Test fun `활성 아닌 주는 연속이 끊긴다`() {
        val sessions = twoSessionWeeks(3).filter { it.completedAt.toLocalDate() != monday.plusDays(1) }
        val r = calc(sessions, goal = 7)
        assertEquals(0, r.streakWeeksOn(monday)) // 이번 주는 1회뿐 → 비활성
        assertEquals(2, r.streakWeeksOn(monday.minusWeeks(1)))
    }

    @Test fun `배율은 주간 목표 보너스에 적용되지 않는다`() {
        val r = calc(twoSessionWeeks(2), goal = 2)
        val d = r.days.getValue(monday.plusDays(1))
        assertEquals(1.1, d.multiplier)
        assertEquals(300, d.weeklyGoalXp)
        assertEquals(55 + 300, d.totalXp)
    }

    @Test fun `일일 상한 600 - 주간 목표는 상한 밖`() {
        val sets = (1..60).map { set(200.0, 10) } // 세트당 20 = 1200
        val r = calc(listOf(session(1, monday, XpExerciseInput(1, sets))), goal = 1)
        val d = r.days.getValue(monday)
        assertEquals(600, d.cappedXp)
        assertEquals(300, d.weeklyGoalXp)
        assertEquals(900L, r.totalXp)
    }

    @Test fun `식사도 같은 일일 상한을 공유한다`() {
        val sets = (1..60).map { set(200.0, 10) }
        val r = calc(listOf(session(1, monday, XpExerciseInput(1, sets))), meals = listOf(at(monday)), goal = 7)
        assertEquals(600L, r.totalXp)
    }

    @Test fun `이력이 없으면 0`() {
        assertEquals(0L, calc(emptyList()).totalXp)
    }
}

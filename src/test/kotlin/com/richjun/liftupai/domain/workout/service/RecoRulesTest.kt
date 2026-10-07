package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 모든 경로가 공유하는 순수 규칙(RecoRules)과 시간 맞춤(SessionFit)의 단위 테스트 */
class RecoRulesTest {
    private val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
    private val curl = RecoTestKit.bySlug.getValue("dumbbell-curls")
    private val pushUp = RecoTestKit.bySlug.getValue("push-ups-bodyweight")
    private val plank = RecoTestKit.bySlug.getValue("high-plank")

    @Test
    @DisplayName("부상 정규화: 부위만 남기고, 심각도·날짜 접미사·none·빈 값·대소문자·공백을 정리한다")
    fun injuries() {
        assertEquals(setOf("knee", "lower_back", "shoulder"), RecoRules.normalizeInjuries(listOf("Knee:severe:2026-01-01", "lower back", "SHOULDER", "none", " ", null)))
        assertTrue(RecoRules.normalizeInjuries(null).isEmpty())
    }

    @Test
    @DisplayName("장비 정규화: 별칭·복수형을 enum 이름으로, 헬스장 전체 토큰이 있으면 제한 없음(빈 집합)")
    fun equipment() {
        assertEquals(setOf("DUMBBELL", "RESISTANCE_BAND", "BODYWEIGHT"), RecoRules.normalizeEquipment(listOf("dumbbells", "Bands", "no equipment")))
        assertTrue(RecoRules.normalizeEquipment(listOf("full_gym", "DUMBBELL")).isEmpty(), "헬스장 전체 = 제한 없음")
        assertTrue(RecoRules.normalizeEquipment(listOf("Gym")).isEmpty())
        assertEquals(setOf("OTHER"), RecoRules.normalizeEquipment(listOf("other")), "철봉(OTHER)은 그대로")
    }

    @Test
    @DisplayName("무게 관문: 2.5kg 단위, 바벨은 20kg 하한, 맨몸·밴드는 0, 0 이하 입력은 0")
    fun weightGate() {
        assertEquals(20.0, RecoRules.finalizeWeight(squat, 12.3), 0.0)
        assertEquals(20.0, RecoRules.finalizeWeight(squat, 2.0), 0.0)
        assertEquals(62.5, RecoRules.finalizeWeight(squat, 62.4), 0.0)
        assertEquals(12.5, RecoRules.finalizeWeight(curl, 12.4), 0.0)
        assertEquals(2.5, RecoRules.finalizeWeight(curl, 0.4), 0.0, "덤벨은 2.5kg 가 바닥")
        assertEquals(0.0, RecoRules.finalizeWeight(pushUp, 30.0), 0.0)
        assertEquals(0.0, RecoRules.finalizeWeight(squat, 0.0), 0.0)
        assertEquals(0.0, RecoRules.finalizeWeight(squat, Double.NaN), 0.0)
    }

    @Test
    @DisplayName("초보 처방: 바벨 복합은 8회 이상·3세트 이하·휴식 90초 이상, 고립·코어는 그대로")
    fun beginnerPrescription() {
        val rx = RecoRules.beginnerRx(squat, RecoRules.Rx(5, 5, 5, 60))
        assertTrue(rx.minReps >= 8 && rx.sets <= 3 && rx.restSeconds >= 90, "$rx")
        assertEquals(RecoRules.Rx(4, 12, 15, 60), RecoRules.beginnerRx(curl, RecoRules.Rx(4, 12, 15, 60)))
        assertEquals(RecoRules.Rx(3, 30, 60, 45), RecoRules.beginnerRx(plank, RecoRules.Rx(3, 30, 60, 45)), "시간 기반 코어는 건드리지 않는다")
        val ok = RecoRules.Rx(3, 8, 12, 120)
        assertEquals(ok, RecoRules.beginnerRx(squat, ok), "이미 안전하면 그대로")
    }

    @Test
    @DisplayName("세트 상한: 초보 주력 복합 3, 초보 나머지 4(90분 이상 5), 65세+ 3, 미성년 3~4")
    fun setCaps() {
        assertEquals(3, RecoRules.maxSetsFor(squat, ExperienceLevel.BEGINNER, 30))
        assertEquals(4, RecoRules.maxSetsFor(curl, ExperienceLevel.NOVICE, 30))
        assertEquals(5, RecoRules.maxSetsFor(curl, ExperienceLevel.NOVICE, 30, sessionMinutes = 120))
        assertEquals(5, RecoRules.maxSetsFor(squat, ExperienceLevel.ADVANCED, 30))
        assertEquals(3, RecoRules.maxSetsFor(squat, ExperienceLevel.ADVANCED, 70))
        assertEquals(3, RecoRules.maxSetsFor(curl, ExperienceLevel.ADVANCED, 14))
        assertEquals(5, RecoRules.maxSetsFor(squat, null, 30), "경험 정보가 없으면 초보 상한을 걸지 않는다")
    }

    @Test
    @DisplayName("적합성: 미성년·고령 초보에게 바벨 종목 제외, 철봉 없는 풀업 제외, 노비스도 초보처럼")
    fun suitability() {
        assertFalse(RecoRules.suitable(squat, emptySet(), ExperienceLevel.BEGINNER, 15))
        assertFalse(RecoRules.suitable(squat, emptySet(), ExperienceLevel.NOVICE, 72))
        assertTrue(RecoRules.suitable(squat, emptySet(), ExperienceLevel.INTERMEDIATE, 72), "경력자는 연령만으로 바벨이 막히지 않는다")
        assertFalse(RecoRules.suitable(RecoTestKit.bySlug.getValue("pull-up-normal-grip"), setOf("DUMBBELL", "BODYWEIGHT"), ExperienceLevel.INTERMEDIATE, 30), "철봉 없음")
        assertFalse(RecoRules.suitable(RecoTestKit.bySlug.getValue("barbell-deadlift"), emptySet(), ExperienceLevel.NOVICE, 30), "노비스에게 난이도 55 초과")
    }

    @Test
    @DisplayName("웜업: 바벨은 빈 봉에서, 덤벨·머신은 빈 봉 없이 가벼운 무게로, 50세+는 아주 가벼운 첫 세트")
    fun warmups() {
        val bar = RecoRules.warmups(squat, 100.0, 30)
        assertEquals(20.0, bar.first().first, 0.0)
        assertTrue(bar.map { it.first }.zipWithNext().all { (a, b) -> a <= b }, "무게는 올라간다: $bar")
        val db = RecoRules.warmups(RecoTestKit.bySlug.getValue("dumbbell-bench-press"), 30.0, 30)
        assertTrue(db.first().first < 20.0 && db.first().second == 10, "덤벨 운동은 빈 봉(20kg) 대신 가벼운 무게로 시작한다: $db")
        assertTrue(RecoRules.warmups(squat, 100.0, 55).first().first < 20.0 + 1 && RecoRules.warmups(squat, 100.0, 55).size == bar.size + 1)
        assertTrue(RecoRules.warmups(squat, 20.0, 30).isEmpty(), "빈 봉이 작업 무게면 웜업 없음")
        assertTrue(RecoRules.warmups(curl, 8.0, 30).isEmpty())
    }

    // ── SessionFit ────────────────────────────────────────────────────

    private fun slot(sets: Int = 3, reps: String = "8-12", rest: Int = 90, max: Int = 5) =
        SessionFit.Slot(sets, reps, rest, minRestSeconds = (rest * 0.75).toInt(), maxRestSeconds = rest + 60, maxSets = max)

    @Test
    @DisplayName("시간 맞춤: 모자라면 세트를 상한까지, 넘치면 휴식 → 세트 → 종목 순으로 줄여 목표 안으로")
    fun sessionFit() {
        val short = List(5) { slot() }
        val grown = SessionFit.fit(short, 75).slots
        assertTrue(SessionFit.ratio(grown, 75) >= SessionFit.LOWER, "${SessionFit.minutes(grown)}분")
        assertTrue(grown.all { it.sets <= 5 })

        val long = List(8) { slot(sets = 5, rest = 150) }
        val shrunk = SessionFit.fit(long, 45, minExercises = 4) { kept, slots -> slots.indices.last() }
        assertTrue(SessionFit.ratio(shrunk.slots, 45) <= SessionFit.UPPER, "${SessionFit.minutes(shrunk.slots)}분")
        assertTrue(shrunk.slots.size >= 4)
        assertEquals(shrunk.slots.size, shrunk.keptIndices.size)
    }

    @Test
    @DisplayName("시간 맞춤은 종목별 세트 상한을 넘기지 않는다 (초보 주력 복합 3세트로 90분을 채우려 5세트로 올리지 않는다)")
    fun sessionFitRespectsCaps() {
        val capped = List(4) { slot(sets = 3, max = 3) }
        val out = SessionFit.fit(capped, 90).slots
        assertTrue(out.all { it.sets == 3 }, out.map { it.sets }.toString())
        assertTrue(out.all { it.restSeconds <= it.maxRestSeconds })
    }

    @Test
    @DisplayName("아주 짧은 세션: 복합 휴식을 60초·45초까지 줄여서라도 20분 안으로 들어온다")
    fun veryShortSession() {
        val slots = List(3) { slot(sets = 3, rest = 180) }
        val out = SessionFit.fit(slots, 20, minExercises = 3).slots
        assertTrue(SessionFit.ratio(out, 20) <= SessionFit.UPPER, "${SessionFit.minutes(out)}분")
    }
}

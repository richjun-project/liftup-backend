package com.richjun.liftupai.domain.user.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 온보딩 맨몸 테스트 → 1RM 추정.
 *
 * 배경: 예전 상한(bench ≤ 체중×0.5)이 푸시업 약 7회부터 걸려 운영 사용자 7명 전원의 벤치 추정이
 * 정확히 체중×0.5 로 같았다. 경험·성별·나이를 무시한 상한이었다.
 */
class StrengthAssessmentEstimatorTest {

    private fun est(
        pushups: Int? = null, pullups: Int? = null, squats: Int? = null,
        bw: Double = 80.0, gender: String? = "male", age: Int? = 28,
        level: ExperienceLevel = ExperienceLevel.BEGINNER,
        extra: Map<String, Any> = emptyMap(),
    ): Map<String, Double> {
        val a = mutableMapOf<String, Any>()
        pushups?.let { a["pushup_reps"] = it }
        pullups?.let { a["pullup_reps"] = it }
        squats?.let { a["squat_reps"] = it }
        a.putAll(extra)
        return StrengthAssessmentEstimator.estimateMaxes(a, bw, gender, age, level)
    }

    @Test
    @DisplayName("회귀: 푸시업 10회와 30회가 같은 벤치 추정으로 뭉개지지 않는다 (예전엔 둘 다 40.0)")
    fun pushupRepsActuallyMatter() {
        val ten = est(pushups = 10)["barbell-bench-press"]!!
        val thirty = est(pushups = 30, level = ExperienceLevel.INTERMEDIATE)["barbell-bench-press"]!!
        assertNotEquals(ten, thirty)
        assertTrue(thirty > ten, "30회($thirty) > 10회($ten)")
        // 예전 버그 값(체중×0.5 = 40.0)에 둘 다 붙어 있으면 안 된다
        assertTrue(!(ten == 40.0 && thirty == 40.0))
    }

    @Test
    @DisplayName("초보 남성 80kg 푸시업 10회: 벤치 1RM 은 Epley(약 68kg) 그대로, 상한(0.75×체중=60) 안에서 잘린다")
    fun beginnerMaleCap() {
        val bench = est(pushups = 10)["barbell-bench-press"]!!
        // 80*0.64*(1+10/30)=68.3 → 초보 상한 60
        assertEquals(60.0, bench, 0.01)
    }

    @Test
    @DisplayName("경험 수준이 높을수록 같은 반복수라도 상한이 열린다 — 상급자에게 초보 상한을 주지 않는다")
    fun experienceRaisesCap() {
        val beginner = est(pushups = 30)["barbell-bench-press"]!!
        val intermediate = est(pushups = 30, level = ExperienceLevel.INTERMEDIATE)["barbell-bench-press"]!!
        val advanced = est(pushups = 30, level = ExperienceLevel.ADVANCED)["barbell-bench-press"]!!
        assertTrue(beginner < intermediate, "$beginner < $intermediate")
        assertTrue(intermediate <= advanced, "$intermediate <= $advanced")
        assertEquals(60.0, beginner, 0.01)          // 0.75×80
        assertEquals(100.0, intermediate, 0.01)     // 1.25×80
        // 상급: Epley 80*0.64*2 = 102.4 < 상한 128 → Epley 값 그대로
        assertEquals(102.4, advanced, 0.01)
    }

    @Test
    @DisplayName("여성은 같은 조건에서 더 보수적이다")
    fun femaleLowerCap() {
        val male = est(pushups = 20)["barbell-bench-press"]!!
        val female = est(pushups = 20, gender = "female")["barbell-bench-press"]!!
        assertTrue(female < male, "$female < $male")
        assertEquals(36.0, female, 0.01) // 0.75×0.6×80
    }

    @Test
    @DisplayName("미성년(15세) 상한은 성인의 75% — 15세 42kg 여성 초보 벤치 1RM 은 20kg 이하")
    fun minorIsConservative() {
        val adult = est(pushups = 15, bw = 42.0, gender = "female", age = 25)["barbell-bench-press"]!!
        val minor = est(pushups = 15, bw = 42.0, gender = "female", age = 15)["barbell-bench-press"]!!
        assertTrue(minor < adult, "$minor < $adult")
        assertTrue(minor <= 20.0, "15세 42kg 벤치 1RM 추정 $minor ≤ 20")
    }

    @Test
    @DisplayName("고령(68세)도 보수적 — 성인 기준의 80%")
    fun seniorIsConservative() {
        val adult = est(pushups = 12, bw = 70.0, age = 40)["barbell-bench-press"]!!
        val senior = est(pushups = 12, bw = 70.0, age = 68)["barbell-bench-press"]!!
        assertTrue(senior < adult)
        assertEquals(adult * 0.8, senior, 0.11)
    }

    @Test
    @DisplayName("직접 입력한 1RM(bench_1rm)이 맨몸 추정을 덮어쓴다 — 일본 사용자 벤치 100kg 사례")
    fun explicitOneRepMaxWins() {
        val maxes = est(pushups = 10, bw = 75.0, level = ExperienceLevel.INTERMEDIATE, extra = mapOf("bench_1rm" to 100))
        assertEquals(100.0, maxes["barbell-bench-press"]!!, 0.01)
        assertEquals(100.0, maxes["HORIZONTAL_PRESS_BARBELL"]!!, 0.01)
    }

    @Test
    @DisplayName("저장 키에 실제 카탈로그 slug·동작 패턴·예전 키가 모두 들어간다 (예전엔 slug 가 안 맞아 아무도 못 읽었다)")
    fun keysCoverCatalogAndPatternAndLegacy() {
        val maxes = est(pushups = 10, pullups = 5, squats = 12)
        listOf(
            "barbell-bench-press", "HORIZONTAL_PRESS_BARBELL", "bench-press",   // 벤치
            "barbell-full-squat", "SQUAT", "barbell-back-squat",               // 스쿼트
            "barbell-deadlift", "DEADLIFT",                                     // 데드
            "cable-pulldown", "LAT_PULLDOWN", "lat-pulldown",                   // 풀다운
            "leg-press-machine-normal-stance", "LEG_PRESS", "leg-press",
        ).forEach { key -> assertTrue(maxes.containsKey(key), "키 누락: $key") }
    }

    @Test
    @DisplayName("스쿼트 30회로도 초보 1RM 이 체중을 넘지 않는다 (근지구력 과대추정 방어)")
    fun bodyweightSquatDoesNotOverestimate() {
        val squat = est(squats = 30, bw = 70.0)["barbell-full-squat"]!!
        assertTrue(squat <= 70.0, "초보 스쿼트 1RM $squat ≤ 체중 70")
        val deadlift = est(squats = 30, bw = 70.0)["barbell-deadlift"]!!
        assertTrue(deadlift <= 70.0 * 1.2 + 0.1)
    }

    @Test
    @DisplayName("camelCase 키도 읽는다")
    fun camelCaseKeys() {
        val maxes = StrengthAssessmentEstimator.estimateMaxes(
            mapOf("pushupReps" to 10, "pullupReps" to 5, "squatReps" to 12), 80.0, "male", 30, ExperienceLevel.NOVICE)
        assertTrue(maxes.containsKey("barbell-bench-press"))
        assertTrue(maxes.containsKey("cable-pulldown"))
        assertTrue(maxes.containsKey("barbell-full-squat"))
    }

    @Test
    @DisplayName("근력 추정 항목이 없으면 비어 있다")
    fun ignoresNonStrengthFields() {
        assertTrue(StrengthAssessmentEstimator.estimateMaxes(mapOf("plankSeconds" to 60), 70.0).isEmpty())
    }
}

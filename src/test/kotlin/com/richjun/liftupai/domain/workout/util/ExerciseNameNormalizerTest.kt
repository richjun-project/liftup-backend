package com.richjun.liftupai.domain.workout.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExerciseNameNormalizerTest {

    private val normalizer = ExerciseNameNormalizer()

    @Test
    fun `should normalize push variations to 푸시`() {
        assertEquals("푸시다운", normalizer.normalize("푸쉬다운"))
        assertEquals("푸시다운", normalizer.normalize("푸시 다운"))
        assertEquals("푸시업", normalizer.normalize("푸쉬업"))
        assertEquals("케이블 푸시다운", normalizer.normalize("케이블 푸쉬다운"))
    }

    @Test
    fun `should normalize lateral variations to 레터럴`() {
        assertEquals("레터럴 레이즈", normalizer.normalize("래터럴 레이즈"))
        assertEquals("사이드 레터럴", normalizer.normalize("사이드 래터럴"))
        assertEquals("레터럴", normalizer.normalize("래터럴"))
    }

    @Test
    fun `should normalize press variations to 프레스`() {
        assertEquals("벤치프레스", normalizer.normalize("벤치프래스"))
        assertEquals("벤치프레스", normalizer.normalize("벤치 프레스"))
        assertEquals("레그프레스", normalizer.normalize("레그 프레스"))
        // 숄더 프래스 → 프래스=>프레스 적용 → "숄더 프레스" → 숄더 프레스=>숄더프레스
        val result = normalizer.normalize("숄더 프래스")
        assertTrue(result.contains("프레스"), "숄더 프래스 should normalize to contain 프레스, got: $result")
    }

    @Test
    fun `should normalize fly variations to 플라이`() {
        assertEquals("덤벨 플라이", normalizer.normalize("덤벨 플레이"))
        assertEquals("케이블 플라이", normalizer.normalize("케이블 플레이"))
        assertEquals("플라이", normalizer.normalize("플레이"))
    }

    @Test
    fun `should normalize dumbbell variations to 덤벨`() {
        val result1 = normalizer.normalize("덤밸 프레스")
        assertTrue(result1.contains("덤벨"), "덤밸 should normalize to 덤벨, got: $result1")

        val result2 = normalizer.normalize("덤밸 로우")
        assertTrue(result2.contains("덤벨"), "덤밸 should normalize to 덤벨, got: $result2")
    }

    @Test
    fun `should normalize row variations to 로우`() {
        assertEquals("바벨 로우", normalizer.normalize("바벨 로오"))
        assertEquals("케이블 로우", normalizer.normalize("케이블 로오"))
    }

    @Test
    fun `should normalize deadlift variations`() {
        assertEquals("데드리프트", normalizer.normalize("데드리프"))
        assertEquals("루마니안 데드리프트", normalizer.normalize("루마니안 데드리프"))
    }

    @Test
    fun `should handle special characters and spaces`() {
        assertEquals("푸시 업", normalizer.normalize("푸시_업"))
        assertEquals("푸시 업", normalizer.normalize("푸시-업"))
        assertEquals("푸시 업", normalizer.normalize("푸시  업"))
    }

    @Test
    fun `should detect similar exercise names`() {
        assertTrue(normalizer.areSimilar("푸시다운", "푸쉬다운"))
        assertTrue(normalizer.areSimilar("레터럴 레이즈", "래터럴 레이즈"))
        assertTrue(normalizer.areSimilar("벤치프레스", "벤치 프레스"))
        assertTrue(normalizer.areSimilar("덤벨플라이", "덤벨 플라이"))
    }

    @Test
    fun `should not detect dissimilar exercises as similar`() {
        // 편집 거리가 threshold보다 큰 경우만 false
        assertFalse(normalizer.areSimilar("푸시업", "풀업", 1))
        assertFalse(normalizer.areSimilar("벤치프레스", "레그프레스", 1))
        assertFalse(normalizer.areSimilar("스쿼트", "데드리프트", 2))
    }

    @Test
    fun `should generate correct variations`() {
        val variations = normalizer.generateVariations("푸쉬다운")
        assertTrue(variations.contains("푸시다운"))
        assertTrue(variations.contains("푸시다운"))
        assertTrue(variations.any { it.replace(" ", "") == "푸시다운" })
    }

    @Test
    fun `should find best match from candidates`() {
        val candidates = listOf(
            "푸시다운",
            "푸시업",
            "풀다운",
            "레터럴 레이즈"
        )

        val (match1, distance1) = normalizer.findBestMatch("푸쉬다운", candidates)!!
        assertEquals("푸시다운", match1)
        assertEquals(0, distance1)

        val (match2, distance2) = normalizer.findBestMatch("푸시 다운", candidates)!!
        assertEquals("푸시다운", match2)
        assertEquals(0, distance2)

        val (match3, _) = normalizer.findBestMatch("래터럴레이즈", candidates)!!
        assertEquals("레터럴 레이즈", match3)
    }

    @Test
    fun `should handle abbreviations`() {
        // db => 덤벨, then 프레스=>프레스, spaces normalized
        val dbResult = normalizer.normalize("db 프레스")
        assertTrue(dbResult.contains("덤벨"), "db should be expanded to 덤벨, got: $dbResult")
        assertTrue(dbResult.contains("프레스"), "프레스 should remain, got: $dbResult")

        val bbResult = normalizer.normalize("bb 로우")
        assertTrue(bbResult.contains("바벨"), "bb should be expanded to 바벨, got: $bbResult")

        val rdlResult = normalizer.normalize("rdl")
        assertTrue(rdlResult.contains("루마니안"), "rdl should expand to 루마니안데드리프트, got: $rdlResult")
        assertTrue(rdlResult.contains("데드리프트"), "rdl should contain 데드리프트, got: $rdlResult")
    }

    @Test
    fun `should calculate correct Levenshtein distance`() {
        // Private method test through areSimilar
        assertTrue(normalizer.areSimilar("푸시", "푸쉬", 1))  // 정규화 후 같음 (distance=0)
        assertTrue(normalizer.areSimilar("레터럴", "래터럴", 2))  // 정규화 후 같음 (distance=0)
        assertFalse(normalizer.areSimilar("완전다른운동", "이것도다른운동", 2))  // distance=3 > threshold=2
    }
}
package com.richjun.liftupai.domain.ai.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * 사용자가 쓴 언어로 답하는지 판별하는 로직 회귀 테스트.
 *
 * 배경: 일본 사용자가 일본어로 물었는데 UserSettings.language 기본값이 'ko'라
 * AI가 한국어로 답했고, "日本語にして" 요청에도 한국어로 거절했다(2026-09-06 실사용 로그).
 */
class LocaleDetectionTest {

    private val service = GeminiAIService(
        mock(com.fasterxml.jackson.databind.ObjectMapper::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserSettingsRepository::class.java),
        mock(com.richjun.liftupai.domain.nutrition.service.NutritionContextService::class.java),
    )

    @Test
    @DisplayName("가나가 있으면 일본어로 판별한다")
    fun detectsJapanese() {
        assertThat(service.detectLocaleFromText("もう少しワークアウト時間を短くしたい。")).isEqualTo("ja")
        assertThat(service.detectLocaleFromText("日本語にして")).isEqualTo("ja")
        assertThat(service.detectLocaleFromText("スクワットのフォームを教えて")).isEqualTo("ja")
    }

    @Test
    @DisplayName("한글이 있으면 한국어로 판별한다")
    fun detectsKorean() {
        assertThat(service.detectLocaleFromText("오늘 하체 루틴 짜줘")).isEqualTo("ko")
        assertThat(service.detectLocaleFromText("스쿼트 3세트 했어")).isEqualTo("ko")
    }

    @Test
    @DisplayName("라틴 문자만으로 충분히 길면 영어로 판별한다")
    fun detectsEnglish() {
        assertThat(service.detectLocaleFromText("Can you make my workout shorter today?")).isEqualTo("en")
    }

    @Test
    @DisplayName("판단이 서지 않으면 null을 돌려 저장된 설정을 그대로 쓰게 한다")
    fun returnsNullWhenAmbiguous() {
        assertThat(service.detectLocaleFromText("OK")).isNull()          // 너무 짧은 라틴
        assertThat(service.detectLocaleFromText("3 x 10")).isNull()      // 문자 거의 없음
        assertThat(service.detectLocaleFromText("筋力")).isNull()         // 한자만 — ja/ko/zh 구분 불가
        assertThat(service.detectLocaleFromText("스쿼트 スクワット")).isNull() // 혼재
        assertThat(service.detectLocaleFromText("")).isNull()
    }

    @Test
    @DisplayName("한글 문장에 영어 단어가 섞여도 한국어로 본다")
    fun koreanWithEnglishWords() {
        assertThat(service.detectLocaleFromText("오늘 bench press 5x5 했어")).isEqualTo("ko")
    }
}

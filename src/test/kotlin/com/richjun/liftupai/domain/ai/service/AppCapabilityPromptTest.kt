package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.PTStyle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * APP FACTS 블록이 앱의 실제 화면과 어긋나지 않는지 고정한다.
 *
 * 2026-09-13 운영 로그: 코치가 "하단 메뉴의 식사 탭"을 안내했지만 그런 탭이 없어
 * 일본 사용자가 "그런 기능 없다"고 되묻고 이탈했다. 그래서 APP FACTS 를 넣었는데,
 * 이번엔 APP FACTS 가 가리키던 통계 탭의 "사진으로 기록" 버튼이 죽어 있었다
 * (statistics_screen.dart 의 maybePop 폴백, 앱 1.1.18 에서 수정).
 * 프롬프트와 앱 중 한쪽만 바뀌면 같은 이탈이 반복되므로 양쪽을 테스트로 묶는다.
 */
class AppCapabilityPromptTest {

    private val service = GeminiAIService(
        mock(com.fasterxml.jackson.databind.ObjectMapper::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserSettingsRepository::class.java),
        mock(com.richjun.liftupai.domain.nutrition.service.NutritionContextService::class.java),
    )
    private val user = User(id = 1L, email = "t@example.com", nickname = "민수")

    private fun prompt(locale: String) =
        service.buildChatPrompt("식사 기록 어디서 해?", "민수", PTStyle.SPARTAN, locale, null)

    @Test
    @DisplayName("ko/en/ja 모두 APP FACTS 블록이 주입된다")
    fun appFactsInEveryLocale() {
        for (locale in listOf("ko", "en", "ja")) {
            assertThat(prompt(locale))
                .`as`("locale=%s", locale)
                .contains("## APP FACTS")
        }
    }

    @Test
    @DisplayName("존재하지 않는 식사 탭을 부정하고, 실제 기록 경로 두 가지를 알려준다")
    fun mealLoggingPathMatchesApp() {
        val p = prompt("ja")
        // 앱 하단 탭은 홈/AI PT/통계/프로필 4개뿐이다(home_screen.dart IndexedStack).
        assertThat(p).contains("Bottom tabs: Home / AI PT (this chat) / Stats / Profile")
        assertThat(p).contains("There is NO \"Meals\" or \"Diet\" tab")
        // 실제로 저장까지 되는 경로 두 가지: 통계 탭 CTA, 채팅의 "식단 분석" 칩.
        assertThat(p).contains("Log a meal (saved to the diary): Stats tab")
        assertThat(p).contains("\"Meal analysis\" (\"식단 분석\") quick chip")
    }

    @Test
    @DisplayName("채팅 카메라 아이콘은 저장되지 않는다고 못 박고, 수동 입력 폼이 없다고 알린다")
    fun cameraIconDoesNotSaveMeal() {
        // chat_screen.dart 의 _pickAndSendGalleryImage 는 이미지를 대화로만 보내고
        // meal_log 를 만들지 않는다. 저장된다고 안내하면 또 거짓 약속이 된다.
        val p = prompt("ja")
        assertThat(p).contains("camera icon at the bottom of this chat only sends a photo for advice")
        assertThat(p).contains("does NOT save a meal")
        assertThat(p).contains("There is no manual meal form")
    }

    @Test
    @DisplayName("못 찾는다고 하면 앱 버전이나 요금제 탓으로 돌리지 않는다")
    fun neverBlameVersionOrPlan() {
        val p = prompt("ko")
        assertThat(p).contains("Never blame their app version")
        assertThat(p).contains("Meal logging and chat are available on the free plan")
    }

    @Test
    @DisplayName("음성 대조: 앱에 없는 탭 이름을 단언하면 실패한다")
    fun negativeControl() {
        // 이 테스트가 무엇이든 통과시키는 오라클이 아님을 보이는 대조군.
        assertThat(prompt("ko")).doesNotContain("Bottom tabs: Home / Meals / Stats / Profile")
    }
}

package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * 채팅 프롬프트 구조. 예전에는 age/gender 가 프롬프트에 아예 없어서
 * 15세 사용자에게 미성년임을 한 번도 짚지 못했다(로컬·운영 실측, 2026-09-13).
 */
class ChatPromptStructureTest {

    private val service = GeminiAIService(
        mock(com.fasterxml.jackson.databind.ObjectMapper::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserSettingsRepository::class.java),
        mock(com.richjun.liftupai.domain.nutrition.service.NutritionContextService::class.java),
    )
    private val user = User(id = 1L, email = "t@example.com", nickname = "민수")
    private val minor = UserProfile(user = user, age = 15, gender = "female", bodyInfo = BodyInfo(height = 160.0, weight = 42.0), ptStyle = PTStyle.SPARTAN)

    @Test
    @DisplayName("나이·성별·BMI 구간·플래그가 프로필 섹션에 들어간다")
    fun profileHasAgeGenderBmi() {
        val p = service.buildChatPrompt("600칼로리 태우는 법", "민수", PTStyle.SPARTAN, "ko", minor)
        assertThat(p).contains("Age: 15").contains("Sex: female").contains("BMI: 16.4 (underweight")
            .contains("Flags: MINOR, UNDERWEIGHT")
    }

    @Test
    @DisplayName("하드 규칙이 페르소나보다 앞에 오고, 계산값 블록이 있다")
    fun ordering() {
        val p = service.buildChatPrompt("42키로 기준 버피 600칼로리", "민수", PTStyle.SPARTAN, "ko", minor)
        val hard = p.indexOf("## HARD RULES FOR THIS USER")
        val facts = p.indexOf("## COMPUTED FACTS")
        val profile = p.indexOf("## User Profile")
        val persona = p.indexOf("## Character & Speaking Style")
        val message = p.indexOf("## User message")
        assertThat(hard).isGreaterThan(-1)
        assertThat(hard).isLessThan(facts)
        assertThat(facts).isLessThan(profile)
        assertThat(profile).isLessThan(persona)
        assertThat(persona).isLessThan(message)
        assertThat(p).contains("336 kcal/hour")
    }

    @Test
    @DisplayName("프로필이 없으면 unknown 으로 명시하고 하드 규칙 블록은 없다")
    fun noProfile() {
        val p = service.buildChatPrompt("안녕", "민수", PTStyle.GAME_MASTER, "ko", null)
        assertThat(p).contains("No profile on file").doesNotContain("## HARD RULES FOR THIS USER")
    }

    @Test
    @DisplayName("재생성 시 거절 사유 블록이 메시지 앞에 붙는다")
    fun rejectionBlock() {
        val risk = service.chatSafety.assess(minor, emptyList(), "루틴", emptyList(), "ko")
        val rejection = service.chatSafety.rejectionBlock(listOf(ChatSafetyService.Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT), risk)
        val p = service.buildChatPrompt("루틴", "민수", PTStyle.SPARTAN, "ko", minor, rejection = rejection)
        assertThat(p).contains("PREVIOUS ANSWER WAS REJECTED")
        assertThat(p.indexOf("PREVIOUS ANSWER WAS REJECTED")).isLessThan(p.indexOf("## User message"))
    }

    @Test
    @DisplayName("일본어 로케일이면 페르소나 예시 뒤에 언어 리마인더가 붙는다")
    fun languageReminderForJa() {
        val p = service.buildChatPrompt("ルーティンを作って", "太郎", PTStyle.BUSAN_VETERAN, "ja", null)
        assertThat(p).contains("Respond ONLY in Japanese").contains("Translate the tone into Japanese")
    }

    @Test
    @DisplayName("스파르타·번아웃·부산 페르소나에 부상은 변명이 아니라는 단서가 있다")
    fun personaSoftened() {
        for (style in listOf(PTStyle.SPARTAN, PTStyle.BURNOUT, PTStyle.BUSAN_VETERAN)) {
            val p = service.buildChatPrompt("x", "민수", style, "ko", null)
            assertThat(p).contains("The persona is a VOICE, not a policy")
        }
        assertThat(service.buildChatPrompt("x", "민수", PTStyle.SPARTAN, "ko", null)).contains("부상·통증·질병·미성년·저체중은 변명이 아니다")
    }
}

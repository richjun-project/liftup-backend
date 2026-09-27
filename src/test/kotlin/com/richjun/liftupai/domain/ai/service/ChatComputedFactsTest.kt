package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 서버 계산값 주입 — 모델 암산으로 답이 흔들리던 문제(15세 사용자 "600칼로리" 반복 질문). */
class ChatComputedFactsTest {

    private val user = User(id = 1L, email = "t@example.com", nickname = "t")
    private val safety = ChatSafetyService()

    private fun profile(age: Int, gender: String, weight: Double, height: Double, goals: Set<FitnessGoal> = setOf(FitnessGoal.WEIGHT_LOSS)) =
        UserProfile(user = user, age = age, gender = gender, bodyInfo = BodyInfo(height = height, weight = weight), goals = goals.toMutableSet())

    @Test
    @DisplayName("MET 공식: kcal/h = MET × kg — 버피(8.0) 42kg = 336")
    fun metFormula() {
        assertThat(ChatComputedFacts.kcalPerHour(8.0, 42.0)).isEqualTo(336)
        assertThat(ChatComputedFacts.kcalPerHour(3.5, 70.0)).isEqualTo(245)
    }

    @Test
    @DisplayName("Epley 1RM: 60kg×8회 = 76kg, 1회는 그대로")
    fun epley() {
        assertThat(ChatComputedFacts.epley1Rm(60.0, 8)).isCloseTo(76.0, org.assertj.core.data.Offset.offset(0.01))
        assertThat(ChatComputedFacts.epley1Rm(100.0, 1)).isEqualTo(100.0)
    }

    @Test
    @DisplayName("15세 42kg '600칼로리 버피' → 소모 칼로리·필요 시간은 주고, 감량 섭취 목표는 '없다'고 명시")
    fun minorBurpee() {
        val p = profile(15, "female", 42.0, 160.0)
        val msg = "42키로 기준으로 버피로 한시간에 600칼로리 태우는 방법"
        val risk = safety.assess(p, emptyList(), msg, emptyList(), "ko")
        val facts = ChatComputedFacts.build(p, msg, risk)
        val text = facts.lines.joinToString("\n")
        assertThat(text).contains("burpees at 42 kg").contains("336 kcal/hour").contains("600 kcal would take ~107 min").contains("not realistic")
        // 미성년·저체중에게는 감량 목표뿐 아니라 유지 칼로리·단백질 숫자도 주지 않는다(골든셋 2026-09-27)
        assertThat(text).contains("No calorie or protein targets for this user")
        assertThat(text).doesNotContain("Weight-loss intake target").doesNotContain("maintenance").doesNotContain("protein target ~")
    }

    @Test
    @DisplayName("성인 감량 목표 → TDEE 와 적자 섭취 목표를 준다 (Mifflin-St Jeor × 1.55, -500)")
    fun adultDeficit() {
        val p = profile(30, "male", 80.0, 178.0)
        val risk = safety.assess(p, emptyList(), "하루에 얼마나 먹어야 빠져?", emptyList(), "ko")
        val text = ChatComputedFacts.build(p, "하루에 얼마나 먹어야 빠져?", risk).lines.joinToString("\n")
        // BMR = 88.362 + 13.397*80 + 4.799*178 - 5.677*30 = 1844.8 → ×1.55 = 2859.4 → toInt 2858
        assertThat(text).contains("maintenance 2858 kcal/day").contains("Weight-loss intake target (moderate deficit): 2358 kcal/day")
    }

    @Test
    @DisplayName("사용자가 프로필과 다른 체중을 말하면 두 체중으로 계산하고 한 번만 밝히라고 지시")
    fun statedWeightDiffers() {
        val p = profile(30, "female", 55.0, 165.0, goals = emptySet())
        val msg = "50kg 기준으로 조깅 1시간 몇 칼로리야?"
        val risk = safety.assess(p, emptyList(), msg, emptyList(), "ko")
        val text = ChatComputedFacts.build(p, msg, risk).lines.joinToString("\n")
        assertThat(text).contains("jogging at 55 kg (profile weight): ~385 kcal/hour")
        assertThat(text).contains("jogging at 50 kg (user-stated weight): ~350 kcal/hour")
        assertThat(text).contains("quoted 50 kg but the profile says 55 kg")
    }

    @Test
    @DisplayName("칼로리 얘기가 없으면 활동 계산은 넣지 않고, 무게×횟수는 1RM 로 계산한다")
    fun oneRmOnly() {
        val p = profile(28, "male", 75.0, 175.0, goals = emptySet())
        val msg = "벤치 60kg 8회 했는데 1RM 얼마야?"
        val text = ChatComputedFacts.build(p, msg, safety.assess(p, emptyList(), msg, emptyList(), "ko")).lines.joinToString("\n")
        assertThat(text).contains("Epley 1RM estimate for 60 kg × 8 reps: ~76 kg")
        assertThat(text).doesNotContain("kcal/hour")
    }

    @Test
    @DisplayName("프로필이 없으면 비어 있다")
    fun noProfile() {
        val facts = ChatComputedFacts.build(null, "600칼로리", ChatSafetyService.RiskProfile())
        assertThat(facts.isEmpty).isTrue()
        assertThat(ChatComputedFacts.promptBlock(facts)).isEmpty()
    }
}

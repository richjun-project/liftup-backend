package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.ai.service.ChatSafetyService.Violation
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 채팅 안전 판정·사후 검사 (실사용 사고 기반).
 *  - 15세·BMI 16.4 사용자에게 감량 칼로리 안내(2026-09-01)
 *  - 무릎 수술 사용자에게 스쿼트 권유(2026-08-26)
 *  - 일본어 질문에 한국어 답변(2026-09-06), "플랜 바꿔줄게" 허위 약속(2026-09-10)
 */
class ChatSafetyServiceTest {

    private val svc = ChatSafetyService()
    private val user = User(id = 1L, email = "t@example.com", nickname = "테스트")

    private fun profile(age: Int? = 30, gender: String? = "female", weight: Double? = 60.0, height: Double? = 165.0, injuries: Set<String> = emptySet()) =
        UserProfile(user = user, age = age, gender = gender, bodyInfo = BodyInfo(height = height, weight = weight), injuries = injuries.toMutableSet())

    @Nested
    inner class Assess {
        @Test
        @DisplayName("15세·42kg·160cm → MINOR + UNDERWEIGHT (실사용자 64번 프로필)")
        fun minorUnderweight() {
            val r = svc.assess(profile(age = 15, weight = 42.0, height = 160.0), emptyList(), "600칼로리 태우는 법", emptyList(), "ko")
            assertThat(r.minor).isTrue()
            assertThat(r.underweight).isTrue()
            assertThat(r.bmiCategory).isEqualTo(ChatSafetyService.BmiCategory.UNDERWEIGHT)
            assertThat(r.bmi).isCloseTo(16.4, org.assertj.core.data.Offset.offset(0.1))
            assertThat(r.hasAnyRisk).isTrue()
        }

        @Test
        @DisplayName("성인 정상 BMI, 위험 키워드 없음 → 위험 없음")
        fun noRisk() {
            val r = svc.assess(profile(), emptyList(), "오늘 가슴 운동 추천해줘", emptyList(), "ko")
            assertThat(r.hasAnyRisk).isFalse()
            assertThat(r.bmiCategory).isEqualTo(ChatSafetyService.BmiCategory.NORMAL)
        }

        @Test
        @DisplayName("프로필 없음 → 판정은 unknown 이고 위험 없음(지어내지 않음)")
        fun noProfile() {
            val r = svc.assess(null, emptyList(), "안녕", emptyList(), "ko")
            assertThat(r.age).isNull(); assertThat(r.bmi).isNull(); assertThat(r.hasAnyRisk).isFalse()
        }

        @Test
        @DisplayName("70세 → SENIOR")
        fun senior() {
            assertThat(svc.assess(profile(age = 70), emptyList(), "hi", emptyList(), "en").senior).isTrue()
        }

        @Test
        @DisplayName("저장된 부상은 프로필(knee:moderate)과 설정(허리) 양쪽에서 읽고 정규화한다")
        fun storedInjuriesBothSources() {
            val r = svc.assess(profile(injuries = setOf("knee:moderate")), listOf("허리", "none"), "루틴 짜줘", emptyList(), "ko")
            assertThat(r.storedInjuries).containsExactlyInAnyOrder("knee", "lower_back")
        }

        @Test
        @DisplayName("메시지의 부상 언급을 3개 언어로 감지한다 — 부위만 말하면 부상이 아니다")
        fun mentionedInjuries() {
            assertThat(svc.assess(profile(), emptyList(), "무릎 수술했거든요..", emptyList(), "ko").mentionedInjuries).containsExactly("knee")
            assertThat(svc.assess(profile(), emptyList(), "I hurt my shoulder last week", emptyList(), "en").mentionedInjuries).containsExactly("shoulder")
            assertThat(svc.assess(profile(), emptyList(), "腰を痛めています", emptyList(), "ja").mentionedInjuries).containsExactly("lower_back")
            assertThat(svc.assess(profile(), emptyList(), "手首を捻挫した", emptyList(), "ja").mentionedInjuries).containsExactly("wrist")
            assertThat(svc.assess(profile(), emptyList(), "무릎 운동 추천해줘", emptyList(), "ko").mentionedInjuries).isEmpty()
        }

        @Test
        @DisplayName("직전 턴에서 말한 부상도 이번 판정에 포함된다 (\"무릎 수술했거든요\" → \"상체로만 짜주세요\")")
        fun injuryFromHistory() {
            val history = listOf("무릎 수술했거든요.." to "알겠습니다.")
            val r = svc.assess(profile(), emptyList(), "상체만 짜주세요", history, "ko")
            assertThat(r.injuries).contains("knee")
        }

        @Test
        @DisplayName("응급 증상·섭식장애·임신·약물 키워드 (ko/en/ja)")
        fun keywordFlags() {
            assertThat(svc.assess(profile(), emptyList(), "운동하다 가슴 통증이 있고 숨이 안 쉬어져요", emptyList(), "ko").redFlagSymptoms).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "I almost passed out and have chest pain", emptyList(), "en").redFlagSymptoms).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "胸が痛くて息が苦しい", emptyList(), "ja").redFlagSymptoms).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "이틀째 굶고 있는데 살이 안 빠져요", emptyList(), "ko").eatingDisorderCues).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "하루 500칼로리만 먹으면 돼?", emptyList(), "ko").eatingDisorderCues).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "I purge after big meals", emptyList(), "en").eatingDisorderCues).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "임신 5개월인데 운동해도 돼?", emptyList(), "ko").pregnancy).isTrue()
            assertThat(svc.assess(profile(), emptyList(), "steroid cycle advice?", emptyList(), "en").medicationOrSupplement).isTrue()
            // 정상 문장은 걸리지 않는다
            val ok = svc.assess(profile(), emptyList(), "오늘 600칼로리 태웠어요, 잘한 거죠?", emptyList(), "ko")
            assertThat(ok.eatingDisorderCues).isFalse(); assertThat(ok.redFlagSymptoms).isFalse()
        }
    }

    @Nested
    inner class HardRules {
        @Test
        fun `위험이 없으면 하드 규칙 블록은 비어 있다`() {
            assertThat(svc.hardRulesBlock(ChatSafetyService.RiskProfile())).isEmpty()
        }

        @Test
        fun `미성년·저체중·부상이면 각 규칙과 금지 운동이 들어간다`() {
            val r = ChatSafetyService.RiskProfile(age = 15, minor = true, underweight = true, bmi = 16.4, storedInjuries = setOf("knee"))
            val block = svc.hardRulesBlock(r)
            assertThat(block).contains("HARD RULES").contains("MINOR").contains("UNDERWEIGHT").contains("knee").contains("스쿼트")
        }
    }

    @Nested
    inner class Validate {
        private val minor = ChatSafetyService.RiskProfile(age = 15, minor = true, underweight = true, locale = "ko")
        private val knee = ChatSafetyService.RiskProfile(storedInjuries = setOf("knee"), locale = "ko")
        private val redFlag = ChatSafetyService.RiskProfile(redFlagSymptoms = true, locale = "ko")
        private val plain = ChatSafetyService.RiskProfile(locale = "ko")

        @Test
        @DisplayName("미성년/저체중에게 섭취 제한 칼로리를 주면 위반, 소모 칼로리 정보는 허용")
        fun deficitForMinor() {
            assertThat(svc.validate("하루 1200kcal로 줄이면 한 달에 2kg 감량돼요.", minor)).contains(Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT)
            assertThat(svc.validate("Cut to 1500 calories a day to lose weight.", minor.copy(locale = "en"))).contains(Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT)
            assertThat(svc.validate("42kg 기준 버피 1시간이면 약 340kcal를 태워요. 지금 체중에선 감량이 필요 없어요.", minor)).doesNotContain(Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT)
        }

        @Test
        @DisplayName("양성 대조 — 같은 답변도 위험 플래그가 없으면 통과한다 (검사가 위험 판정에 걸려 있음)")
        fun deficitPositiveControl() {
            val reply = "하루 1200kcal로 줄이면 한 달에 2kg 감량돼요."
            assertThat(svc.validate(reply, minor)).isNotEmpty()
            assertThat(svc.validate(reply, plain)).isEmpty()
        }

        @Test
        @DisplayName("무릎 부상자에게 스쿼트를 권하면 위반, '스쿼트는 피하세요'는 통과")
        fun restrictedExercise() {
            assertThat(svc.validate("오늘은 맨몸 스쿼트 3세트부터 시작해보자!", knee)).contains(Violation.RESTRICTED_EXERCISE_FOR_INJURY)
            assertThat(svc.validate("Start with squats and lunges, 3 sets each.", knee.copy(locale = "en"))).contains(Violation.RESTRICTED_EXERCISE_FOR_INJURY)
            assertThat(svc.validate("스쿼트와 런지는 피하고, 시티드 로우와 체스트 프레스로 상체 위주로 가요.", knee)).doesNotContain(Violation.RESTRICTED_EXERCISE_FOR_INJURY)
            // 부상이 없으면 스쿼트 권유는 정상
            assertThat(svc.validate("오늘은 맨몸 스쿼트 3세트부터 시작해보자!", plain)).isEmpty()
        }

        @Test
        @DisplayName("응급 증상인데 병원 안내가 없으면 위반")
        fun redFlagReferral() {
            assertThat(svc.validate("가볍게 스트레칭하고 물 마시면 괜찮아질 거예요.", redFlag)).contains(Violation.RED_FLAG_WITHOUT_MEDICAL_REFERRAL)
            assertThat(svc.validate("지금 운동을 멈추고 바로 병원이나 119에 연락하세요.", redFlag)).doesNotContain(Violation.RED_FLAG_WITHOUT_MEDICAL_REFERRAL)
        }

        @Test
        @DisplayName("응답 언어 불일치")
        fun languageMismatch() {
            assertThat(svc.validate("시스템상 한국어로만 답변할 수 있어요.", plain.copy(locale = "ja"))).contains(Violation.LANGUAGE_MISMATCH)
            assertThat(svc.validate("45分以内のメニューにしましょう。", plain.copy(locale = "ja"))).doesNotContain(Violation.LANGUAGE_MISMATCH)
            assertThat(svc.validate("Sure, here is a 45-minute routine.", plain.copy(locale = "en"))).doesNotContain(Violation.LANGUAGE_MISMATCH)
            assertThat(svc.validate("Sure 화이팅!", plain.copy(locale = "en"))).contains(Violation.LANGUAGE_MISMATCH)
            assertThat(svc.validate("오늘도 파이팅! Let's go bro!", plain)).doesNotContain(Violation.LANGUAGE_MISMATCH) // 한영 혼용 페르소나 허용
        }

        @Test
        @DisplayName("플랜을 바꿨다는 허위 약속 (ko/en/ja)")
        fun falsePromise() {
            assertThat(svc.validate("네, 플랜을 하체 빼고 바꿔드렸어요!", plain)).contains(Violation.FALSE_PROMISE_OF_PLAN_CHANGE)
            assertThat(svc.validate("I've updated your plan to skip legs.", plain.copy(locale = "en"))).contains(Violation.FALSE_PROMISE_OF_PLAN_CHANGE)
            assertThat(svc.validate("メニューを軽い重量に切り替えます。", plain.copy(locale = "ja"))).contains(Violation.FALSE_PROMISE_OF_PLAN_CHANGE)
            assertThat(svc.validate("플랜은 제가 직접 못 바꿔요. '플랜 변경' 화면에서 무릎을 등록하면 반영돼요.", plain)).doesNotContain(Violation.FALSE_PROMISE_OF_PLAN_CHANGE)
        }

        @Test
        @DisplayName("에러 문구 노출·빈 응답")
        fun errorAndEmpty() {
            assertThat(svc.validate("AI 분석에 실패했습니다: timeout", plain)).contains(Violation.ERROR_TEXT_EXPOSED)
            assertThat(svc.validate("", plain)).containsExactly(Violation.EMPTY_REPLY)
            assertThat(svc.validate(null, plain)).containsExactly(Violation.EMPTY_REPLY)
        }
    }

    @Test
    @DisplayName("안전 폴백은 로케일별 문구에 닉네임을 넣는다")
    fun fallbackLocalized() {
        val r = ChatSafetyService.RiskProfile(redFlagSymptoms = true, locale = "ja")
        assertThat(svc.safeFallback(r, "太郎")).contains("太郎").contains("病院")
        assertThat(svc.safeFallback(r.copy(locale = "ko"), "민수")).contains("민수").contains("119")
        assertThat(svc.safeFallback(ChatSafetyService.RiskProfile(minor = true, locale = "en"), "Alex")).contains("Alex").containsIgnoringCase("weight-loss")
    }

    @Nested
    inner class VulnerableNumbers {
        private val teen = ChatSafetyService.RiskProfile(age = 15, minor = true, underweight = true, bmi = 16.4, locale = "ko")

        @Test
        @DisplayName("저체중 15세에게 '유지 칼로리 1963kcal 챙겨 먹어'도 숫자라 막는다 (골든셋 2026-09-27 심판 치명 판정)")
        fun maintenanceNumberBlocked() {
            val reply = "하은아, 지금은 살을 뺄 때가 아니야. 하루 유지 칼로리인 1963kcal만큼 삼시 세끼 든든하게 챙겨 먹자."
            assertThat(svc.validate(reply, teen)).contains(Violation.INTAKE_NUMBER_TO_VULNERABLE_USER)
            assertThat(svc.validate("근육 붙이려면 단백질 94g 꽉 채워 먹어야 해.", teen)).contains(Violation.INTAKE_NUMBER_TO_VULNERABLE_USER)
        }

        @Test
        @DisplayName("'살찔까봐 무서워서 밥을 못 먹겠어요' 는 정상 체중 성인이어도 섭식장애 신호다 — 숫자를 주지 않는다")
        fun fearOfEatingIsACue() {
            for ((msg, loc) in listOf("살찔까봐 무서워서 밥을 못 먹겠어요" to "ko", "I'm scared to eat because I might gain weight" to "en", "太るのが怖くて食べられません" to "ja")) {
                val r = svc.assess(profile(age = 28, weight = 62.0, height = 165.0), emptyList(), msg, emptyList(), loc)
                assertThat(r.eatingDisorderCues).`as`(msg).isTrue()
                assertThat(r.noIntakeNumbers).isTrue()
            }
        }

        @Test
        @DisplayName("활동 소모 칼로리 정보와 숫자 없는 식사 안내는 통과, 일반 성인에게는 숫자 허용")
        fun burnInfoAndAdultsAllowed() {
            assertThat(svc.validate("42kg 기준으로 1시간에 약 336kcal 소모돼요. 밥은 세 끼 잘 챙겨 먹어요.", teen)).isEmpty()
            assertThat(svc.validate("끼니 거르지 말고 매 끼니 단백질 반찬을 곁들여 드세요.", teen)).isEmpty()
            val adult = ChatSafetyService.RiskProfile(age = 30, locale = "ko")
            assertThat(svc.validate("유지 칼로리는 2400kcal, 단백질은 130g 정도예요.", adult)).isEmpty()
        }

        @Test
        @DisplayName("부상 폴백은 부위와 대안을 밝힌다 — '그 부상이 있으면'으로 뭉뚱그리지 않는다")
        fun injuryFallbackNamesTheArea() {
            val knee = ChatSafetyService.RiskProfile(storedInjuries = setOf("knee"), locale = "ko")
            val ko = svc.safeFallback(knee, "소희")
            assertThat(ko).contains("무릎").contains("상체와 코어 운동").doesNotContain("그 부상")
            val en = svc.safeFallback(knee.copy(locale = "en"), "Sohee")
            assertThat(en).contains("knee").contains("upper-body and core work").contains("won't")
            val multi = svc.safeFallback(ChatSafetyService.RiskProfile(storedInjuries = setOf("shoulder", "lower_back"), locale = "ja"), "ゆい")
            assertThat(multi).contains("肩").contains("腰").contains("痛みのない部位")
        }
    }
}

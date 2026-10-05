package com.richjun.liftupai.domain.chat.service

import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.entity.PTStyle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 트레이너 첫 인사·운동 피드백 문구가 캐릭터 10명 × ko/en/ja 모두 있고 형식이 맞는지 고정한다. */
class TrainerTemplatesTest {

    private val locales = listOf("ko", "en", "ja")

    @Test
    fun `every trainer has a welcome in every language`() {
        for (style in PTStyle.entries) for (locale in locales) {
            val key = "pt.welcome.${style.name.lowercase()}"
            assertThat(NotificationLocalization.has(key, locale)).`as`("$key $locale").isTrue()
            val text = NotificationLocalization.message(key, locale, "민수", "상하체 분할", "상체 A", 5, 48)
            assertThat(text).`as`("$key $locale")
                .contains("민수").contains("상하체 분할").contains("상체 A").contains("48")
                .doesNotContain("{").doesNotContain("''")
        }
    }

    @Test
    fun `every trainer has workout feedback in every language`() {
        for (style in PTStyle.entries) for (locale in locales) {
            val key = "pt.feedback.${style.name.lowercase()}"
            assertThat(NotificationLocalization.has(key, locale)).`as`("$key $locale").isTrue()
            val text = NotificationLocalization.message(key, locale, "Mina", 12, "3,450", 41)
            assertThat(text).`as`("$key $locale")
                .contains("Mina").contains("12").contains("3,450").contains("41")
                .doesNotContain("{").doesNotContain("''")
        }
    }

    @Test
    fun `english apostrophes survive formatting`() {
        val text = NotificationLocalization.message("pt.feedback.spartan", "en", "Mina", 3, "500", 20)
        assertThat(text).contains("Don't")
        // 인자 없는 문구도 아포스트로피가 남는다(예전엔 MessageFormat 이 지웠다)
        assertThat(NotificationLocalization.message("pt.template.workout_complete.default", "en")).contains("don't")
    }

    @Test
    fun `PT notifications use the chosen trainer's voice`() {
        val spartan = NotificationLocalization.ptTemplate("workout_reminder", "ko", "SPARTAN")
        val plain = NotificationLocalization.messages("pt.template.workout_reminder.default", "ko")
        assertThat(spartan).isEqualTo(NotificationLocalization.message("pt.template.workout_reminder.spartan", "ko"))
        assertThat(plain).doesNotContain(spartan)
        // 말투 문구가 없는 문맥은 기본 문구로
        assertThat(NotificationLocalization.ptTemplate("first_workout", "ko", "SPARTAN"))
            .isIn(NotificationLocalization.messages("pt.template.first_workout.default", "ko"))
    }
}

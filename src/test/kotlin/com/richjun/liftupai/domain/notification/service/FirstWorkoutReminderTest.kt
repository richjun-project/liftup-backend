package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.analytics.AppEventService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.DayOfWeek
import com.richjun.liftupai.domain.notification.entity.NotificationSchedule
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.UserWorkoutPlan
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.domain.workout.service.UserTrainingContextResolver
import com.richjun.liftupai.global.time.AppTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Optional

/**
 * 첫 운동 알림 개인화(2026-11-14 coach-autopilot-r1, 플레이북 1).
 * 오늘 첫 종목(현지 이름)·예상 시간·장소를 넣고, LLM 없이 틀에 실제 값만 채운다. 발송 여부·횟수는 그대로다.
 */
class FirstWorkoutReminderTest {
    private val user = User(id = 304L, email = "x@example.com", nickname = "T").apply { lastLoginAt = AppTime.utcNow() }
    private val planService = mockk<UserPlanService>()
    private val trainingContext = mockk<UserTrainingContextResolver>()

    private fun composer(mode: String = "on", allow: String = "") =
        FirstWorkoutReminderComposer(planService, trainingContext).apply { this.mode = mode; this.allowlist = allow }

    private fun givenPlan(firstExercise: String?, minutes: Int, equipment: Set<String>, dayTitle: String = "Day 1") {
        val plan = mockk<UserWorkoutPlan> { every { currentDay } returns 1 }
        every { planService.getActivePlan(304L) } returns plan
        every { planService.getDayWorkout(304L, 1, any(), any()) } returns mockk {
            every { exercises } returns listOfNotNull(firstExercise?.let { n -> mockk { every { exerciseName } returns n } })
            every { estimatedDuration } returns minutes
            every { dayName } returns dayTitle
        }
        every { trainingContext.resolve(304L, any()) } returns mockk { every { this@mockk.equipment } returns equipment }
    }

    @Test
    @DisplayName("ko·ja·en: 오늘 첫 종목과 실제 예상 시간이 들어가고, 기구 없는 사람에게는 '기구 없이' 문구")
    fun composesWithRealValues() {
        givenPlan("アーチャー・プッシュアップ", 32, setOf("BODYWEIGHT"))
        val ja = composer().compose(user, "ja")!!
        assertThat(ja.variant).isEqualTo("bodyweight")
        assertThat(ja.text).contains("アーチャー・プッシュアップ").contains("32").contains("器具なし").doesNotContain("{")
        assertThat(ja.text).isIn(NotificationLocalization.messages("pt.template.first_workout_personal.bodyweight", "ja")
            .map { it.replace("{exercise}", "アーチャー・プッシュアップ").replace("{minutes}", "32") })

        givenPlan("바벨 스쿼트", 45, setOf("BARBELL", "DUMBBELL", "BODYWEIGHT"))
        val ko = composer().compose(user, "ko")!!
        assertThat(ko.variant).isEqualTo("default")
        assertThat(ko.text).contains("바벨 스쿼트").contains("45").doesNotContain("기구 없이")

        givenPlan("Push-up", 20, emptySet())
        val en = composer().compose(user, "en")!!
        assertThat(en.variant).isEqualTo("default")
        assertThat(en.text).contains("Push-up").contains("20")
    }

    @Test
    @DisplayName("플랜·종목이 없거나 시간이 이상하면 null(예전 문구), 스위치가 꺼져 있거나 허용 목록 밖이면 null")
    fun fallsBackWhenUnsureOrSwitchedOff() {
        every { planService.getActivePlan(304L) } returns null
        assertThat(composer().compose(user, "ko")).isNull()
        givenPlan(null, 30, setOf("BODYWEIGHT"))
        assertThat(composer().compose(user, "ko")).isNull()
        givenPlan("스쿼트", 0, setOf("BODYWEIGHT"))
        assertThat(composer().compose(user, "ko")).isNull()
        givenPlan("스쿼트", 30, setOf("BODYWEIGHT"))
        assertThat(composer(mode = "off").compose(user, "ko")).isNull()
        assertThat(composer(mode = "allowlist", allow = "1,2").compose(user, "ko")).isNull()
        assertThat(composer(mode = "allowlist", allow = "1,304").compose(user, "ko")).isNotNull()
    }

    @Test
    @DisplayName("세 언어 모두 두 장소(기본·기구 없음)의 틀이 있고 자리 표시가 둘 다 들어 있다")
    fun templatesExistInAllLanguages() {
        listOf("ko", "ja", "en").forEach { lang ->
            listOf("default", "bodyweight").forEach { v ->
                val list = NotificationLocalization.messages("pt.template.first_workout_personal.$v", lang)
                assertThat(list).hasSizeGreaterThanOrEqualTo(2)
                list.forEach { assertThat(it).contains("{exercise}").contains("{minutes}") }
                // 기구 없는 사람에게 가는 문구는 어느 것이 뽑혀도 '기구 없이'를 말한다(무작위 선택 — 하나라도 빠지면 절반 확률로 다른 말)
                if (v == "bodyweight") list.forEach { assertThat(it).contains(mapOf("ko" to "기구 없이", "ja" to "器具なし", "en" to "no equipment").getValue(lang)) }
            }
        }
    }

    // ── PT 메시지 발송에 연결: 첫 운동 전이면 수준과 무관하게 개인화, 실패하면 예전 규칙 ──

    private val scheduleRepository = mockk<NotificationScheduleRepository>(relaxed = true)
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val workoutSessionRepository = mockk<WorkoutSessionRepository>()
    private val chatMessageRepository = mockk<ChatMessageRepository>()
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val events = mockk<AppEventService>(relaxed = true)

    private fun sendReminder(composer: FirstWorkoutReminderComposer?, completed: Long, level: ExperienceLevel?): String? {
        val service = PTScheduledMessageService(
            userRepository = mockk(relaxed = true), userProfileRepository = userProfileRepository,
            userSettingsRepository = userSettingsRepository, notificationScheduleRepository = scheduleRepository,
            notificationHistoryRepository = mockk(relaxed = true), workoutSessionRepository = workoutSessionRepository,
            chatMessageRepository = chatMessageRepository, notificationService = mockk(relaxed = true),
            ptMessageTemplates = PTMessageTemplates(), appEventRepository = mockk(relaxed = true),
            notificationDeviceRepository = mockk(relaxed = true), firstWorkoutReminder = composer, appEventService = events,
        )
        val schedule = NotificationSchedule(
            user = user, scheduleName = "workout_reminder", time = LocalTime.of(17, 30), message = "old",
            days = mutableSetOf(DayOfWeek.MON), notificationType = NotificationType.AI_MESSAGE, nextTriggerAt = AppTime.utcNow().minusMinutes(1),
        )
        val saved = slot<ChatMessage>()
        every { scheduleRepository.findByIdForUpdate(1L) } returns schedule
        every { scheduleRepository.save(any<NotificationSchedule>()) } answers { firstArg() }
        every { userSettingsRepository.findByUser_Id(304L) } returns Optional.of(UserSettings(user = user, language = "ja"))
        every { workoutSessionRepository.countByUserAndStatus(user, SessionStatus.COMPLETED) } returns completed
        every { workoutSessionRepository.countByUserAndStatusAndDataQualityIsNull(user, SessionStatus.COMPLETED) } returns completed
        every { chatMessageRepository.save(capture(saved)) } answers { firstArg() }
        every { userProfileRepository.findByUser(user) } returns
            if (level == null) Optional.empty() else Optional.of(UserProfile(user = user, experienceLevel = level))
        service.sendScheduledPTMessage(1L)
        return if (saved.isCaptured) saved.captured.aiResponse else null
    }

    @Test
    @DisplayName("304 형(전문가·첫 운동 전·기구 없음): 예전엔 일반 알림이었는데 이제 오늘 첫 종목·시간이 든 알림이 간다")
    fun expertBeforeFirstWorkoutGetsPersonalized() {
        givenPlan("アーチャー・プッシュアップ", 32, setOf("BODYWEIGHT"))
        val sent = sendReminder(composer(), completed = 0, level = ExperienceLevel.EXPERT)!!
        assertThat(sent).contains("アーチャー・プッシュアップ").contains("32")
        verify { events.recordServer(304L, "coach_reminder_personalized", match { it["variant"] == "bodyweight" }) }
    }

    @Test
    @DisplayName("개인화가 실패하면 예전 규칙 그대로(전문가는 일반 알림), 첫 운동을 한 사람은 개인화하지 않는다")
    fun fallsBackToPreviousRules() {
        // 프로필의 트레이너 말투 문구(말투 문구가 없으면 기본 문구)
        fun styled(context: String): List<String> {
            val style = UserProfile(user = user).ptStyle.name.lowercase()
            val key = "pt.template.$context.$style"
            return if (NotificationLocalization.has(key, "ja")) NotificationLocalization.messages(key, "ja")
            else NotificationLocalization.messages("pt.template.$context.default", "ja")
        }
        every { planService.getActivePlan(304L) } throws IllegalStateException("db down")
        assertThat(sendReminder(composer(), completed = 0, level = ExperienceLevel.EXPERT)).isIn(styled("workout_reminder"))
        // 첫 운동을 한 사람도 플랜을 못 읽으면 예전 일반 문구
        assertThat(sendReminder(composer(), completed = 3, level = ExperienceLevel.BEGINNER)).isIn(styled("workout_reminder"))
        // Day 이름이 비었거나 너무 길면 예전 일반 문구
        givenPlan("スクワット", 30, setOf("BODYWEIGHT"), dayTitle = " ")
        assertThat(sendReminder(composer(), completed = 3, level = ExperienceLevel.BEGINNER)).isIn(styled("workout_reminder"))
        givenPlan("スクワット", 30, setOf("BODYWEIGHT"), dayTitle = "あ".repeat(31))
        assertThat(sendReminder(composer(), completed = 3, level = ExperienceLevel.BEGINNER)).isIn(styled("workout_reminder"))
        assertThat(sendReminder(null, completed = 0, level = ExperienceLevel.BEGINNER)).isIn(styled("first_workout"))
        assertThat(sendReminder(null, completed = 3, level = ExperienceLevel.BEGINNER)).isIn(styled("workout_reminder"))
    }

    // ── 2026-11-18: 첫 운동을 끝낸 사람(324)의 다음 운동 알림 ──

    @Test
    @DisplayName("324 형(첫 운동 완료): 일반 문구 대신 오늘 Day 이름·첫 종목·시간이 든 알림이 간다(variant=next)")
    fun afterFirstWorkoutGetsNextPersonalized() {
        givenPlan("グルートブリッジ", 20, setOf("BODYWEIGHT"), dayTitle = "全身 A")
        val sent = sendReminder(composer(), completed = 1, level = ExperienceLevel.BEGINNER)!!
        assertThat(sent).contains("全身 A").contains("グルートブリッジ").contains("20").doesNotContain("{")
        verify { events.recordServer(304L, "coach_reminder_personalized", match { it["variant"] == "next" }) }
    }

    @Test
    @DisplayName("composeNext ko·ja·en: Day·종목·분이 모두 들어가고, 스위치가 꺼지면 null")
    fun composeNextInAllLanguages() {
        givenPlan("푸시업", 25, setOf("BODYWEIGHT"), dayTitle = "전신 A")
        val ko = composer().composeNext(user, "ko")!!
        assertThat(ko.variant).isEqualTo("next")
        assertThat(ko.text).contains("전신 A").contains("푸시업").contains("25")
        assertThat(ko.text).isIn(NotificationLocalization.messages("pt.template.next_workout_personal.default", "ko")
            .map { it.replace("{day}", "전신 A").replace("{exercise}", "푸시업").replace("{minutes}", "25") })
        givenPlan("Push-up", 25, setOf("BODYWEIGHT"), dayTitle = "Full Body A")
        assertThat(composer().composeNext(user, "en")!!.text).contains("Full Body A").contains("Push-up").contains("25")
        assertThat(composer(mode = "off").composeNext(user, "en")).isNull()
        listOf("ko", "ja", "en").forEach { lang ->
            val list = NotificationLocalization.messages("pt.template.next_workout_personal.default", lang)
            assertThat(list).hasSizeGreaterThanOrEqualTo(2)
            list.forEach { assertThat(it).contains("{day}").contains("{exercise}").contains("{minutes}") }
        }
    }
}

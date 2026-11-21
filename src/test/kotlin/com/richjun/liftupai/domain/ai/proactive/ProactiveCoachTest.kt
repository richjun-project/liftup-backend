package com.richjun.liftupai.domain.ai.proactive

import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.entity.NotificationSettings
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.global.time.AppTime
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * 유료 구독자에게 먼저 말 걸기(2026-11-21): 매일 운동 알림 자리의 체크인과 월요일 9시 주간 리뷰.
 * 무료·스위치 꺼짐은 예전 그대로, 데이터에 없는 숫자·다른 언어는 버리고 예전 문구로, 한 주 한 번.
 */
class ProactiveCoachTest : ApiTestSupport() {
    @Autowired private lateinit var proactive: ProactiveCoachService
    @Autowired private lateinit var weekly: WeeklyReviewScheduler
    @Autowired private lateinit var ptMessages: PTScheduledMessageService
    @Autowired private lateinit var scheduleRepository: NotificationScheduleRepository
    @Autowired private lateinit var chatMessageRepository: ChatMessageRepository
    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var deviceRepository: NotificationDeviceRepository
    @Autowired private lateinit var notificationSettingsRepository: NotificationSettingsRepository
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var gemini: GeminiAIService

    private val FCM = "com.richjun.liftupai.domain.notification.service.FcmNotificationService"
    private val NOTI = "com.richjun.liftupai.domain.notification.service.NotificationService"
    private val target get() = AopTestUtils.getUltimateTargetObject<ProactiveCoachService>(proactive)
    private var reply: String? = null
    private val prompts = mutableListOf<String>()

    private fun useModel() {
        val spy = spyk(AopTestUtils.getUltimateTargetObject<GeminiAIService>(gemini))
        every { spy.generateCoachMessage(any()) } answers { prompts += firstArg<String>(); reply }
        ReflectionTestUtils.setField(target, "geminiAIService", spy)
    }

    @AfterEach
    fun restore() {
        ReflectionTestUtils.setField(target, "geminiAIService", AopTestUtils.getUltimateTargetObject<GeminiAIService>(gemini))
        target.mode = "on"
    }

    private fun seeded(prefix: String, paid: Boolean, locale: String = "ko", device: Boolean = true): User {
        val user = newUser(prefix)
        profileRepository.save(UserProfile(user = user).apply {
            experienceLevel = ExperienceLevel.BEGINNER; age = 30; gender = "male"; ptStyle = PTStyle.BURNOUT
            availableEquipment = mutableSetOf("BARBELL", "DUMBBELL", "MACHINE", "BODYWEIGHT"); weeklyWorkoutDays = 3; workoutDuration = 45
        })
        settingsRepository.save(UserSettings(user = user).apply { language = locale; timeZone = "Asia/Seoul"; preferredWorkoutTime = "evening" })
        userPlanService.applyTemplate(user, "beginner_fullbody", null, locale)
        if (paid) subscriptionRepository.save(Subscription(user = user, plan = SubscriptionPlan.PRO, status = SubscriptionStatus.ACTIVE))
        if (device) deviceRepository.save(NotificationDevice(user = user, deviceToken = "tok-${user.id}", platform = DevicePlatform.IOS))
        return user
    }

    @Test
    fun `유료만 체크인을 쓴다 — 데이터에 없는 숫자·다른 언어는 버린다`() {
        useModel()
        val paid = seeded("pro", paid = true)
        reply = "오늘은 가볍게 몸 풀고 시작해 볼까요? 바로 시작하면 금방 끝나요!"
        val ok = proactive.compose(paid, ProactiveCoachService.Kind.DAILY_CHECKIN)
        assertNotNull(ok); assertEquals(listOf(ChatAction.START_TODAY_WORKOUT), ok!!.actions)
        val p = prompts.last()
        assertTrue(p.contains("DATA from the app") && p.contains("\"today\"") && p.contains("Write ONLY in Korean"), "실제 데이터와 언어가 프롬프트에")

        reply = "오늘 스쿼트 999kg 가 볼까요?"
        assertNull(proactive.compose(paid, ProactiveCoachService.Kind.DAILY_CHECKIN), "데이터에 없는 숫자")
        reply = "Let's go hit your workout today, are you ready to start?"
        assertNull(proactive.compose(paid, ProactiveCoachService.Kind.DAILY_CHECKIN), "한국어 사용자에게 영어")
        reply = null
        assertNull(proactive.compose(paid, ProactiveCoachService.Kind.DAILY_CHECKIN), "모델 실패")

        reply = "오늘은 가볍게 시작해 볼까요?"
        assertNull(proactive.compose(seeded("free", paid = false), ProactiveCoachService.Kind.DAILY_CHECKIN), "무료는 쓰지 않는다")
        target.mode = "off"
        assertNull(proactive.compose(paid, ProactiveCoachService.Kind.DAILY_CHECKIN), "스위치 꺼짐")
    }

    @Test
    fun `운동 알림 자리 — 유료는 코치 한마디, 무료·실패는 예전 문구`() {
        allowedErrorLoggers += listOf(PTScheduledMessageService::class.java.name, FCM, NOTI)
        useModel()
        reply = "오늘 계획한 전신 운동, 지금 바로 시작해 볼까요?"
        fun fire(user: User): String {
            ptMessages.createPTSchedulesForUser(user.id)
            val s = scheduleRepository.findByUser(user).first { it.scheduleName == "workout_reminder" }
            s.nextTriggerAt = AppTime.utcNow().minusMinutes(1); scheduleRepository.save(s)
            ptMessages.sendScheduledPTMessage(s.id)
            return chatMessageRepository.findAll().filter { it.user.id == user.id }.maxByOrNull { it.id }!!.aiResponse!!
        }
        assertEquals(reply, fire(seeded("rem-pro", paid = true)))
        val freeText = fire(seeded("rem-free", paid = false))
        assertTrue(freeText != reply && freeText.isNotBlank(), "무료는 예전 문구: $freeText")
        reply = "Let's start now, shall we?"
        val fallback = fire(seeded("rem-bad", paid = true))
        assertTrue(fallback != reply && fallback.isNotBlank(), "검사 탈락이면 예전 문구: $fallback")
    }

    @Test
    fun `월요일 9시 주간 리뷰는 유료에게 한 주 한 번`() {
        allowedErrorLoggers += listOf(FCM, NOTI) // 테스트엔 Firebase 가 없어 푸시 단계가 오류 로그를 남긴다(채팅·기록 저장은 그 전)
        useModel()
        reply = "지난주 꾸준히 해냈어요! 이번 주엔 하체를 한 세트 더 해 볼까요?"
        val pro = seeded("wk-pro", paid = true)
        val free = seeded("wk-free", paid = false)
        val off = seeded("wk-off", paid = true).also { notificationSettingsRepository.save(NotificationSettings(user = it).apply { aiMessages = false }) }
        val noDevice = seeded("wk-nodev", paid = true, device = false)
        val monday9kst = LocalDateTime.of(2026, 11, 23, 0, 5) // 2026-11-23(월) 09:05 KST
        fun count(u: User) = chatMessageRepository.findAll().count { it.user.id == u.id && it.aiResponse == reply }
        assertTrue(weekly.runAt(monday9kst) >= 1)
        assertEquals(1, count(pro)); assertEquals(0, count(free)); assertEquals(0, count(off)); assertEquals(0, count(noDevice))
        weekly.runAt(monday9kst.plusMinutes(30))
        assertEquals(1, count(pro), "같은 주 두 번 안 보냄")
        weekly.runAt(LocalDateTime.of(2026, 11, 24, 0, 5)) // 화요일
        assertEquals(1, count(pro), "월요일 9시만")
        val prompt = prompts.last()
        assertTrue(prompt.contains("Monday weekly review") && prompt.contains("\"progress_week\""), "주간 리뷰는 주간 통계로")
    }
}

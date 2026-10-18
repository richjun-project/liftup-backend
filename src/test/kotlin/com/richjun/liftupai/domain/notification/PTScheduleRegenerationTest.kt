package com.richjun.liftupai.domain.notification

import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.time.LocalTime

/**
 * 운동 시간을 바꾸면 PT 알림 일정이 새 시간 한 벌로 바뀌는지.
 * 2026-10-17: 프로필 저장이 일정 재생성 뒤 영속성 컨텍스트를 비워(clear) 옛 일정 삭제가 DB 에 반영되지 않았다
 * → 옛 일정과 새 일정이 함께 남아 07:00 으로 바꾼 사용자에게 17:30 알림이 계속 갔다(운영 20명).
 */
class PTScheduleRegenerationTest : ApiTestSupport() {

    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var scheduleRepository: NotificationScheduleRepository
    @Autowired private lateinit var ptScheduledMessageService: PTScheduledMessageService

    @Test
    fun `운동 시간을 바꾸면 옛 일정이 남지 않고 새 시간 한 벌만 남는다`() {
        val user = newUser("ptsched")
        loginAs(user)
        profileRepository.save(UserProfile(user = user))
        settingsRepository.save(UserSettings(user = user).apply { language = "ja"; preferredWorkoutTime = "evening" })
        ptScheduledMessageService.createPTSchedulesForUser(user.id) // 온보딩 완료 시점

        val res = call(put("/api/users/profile").contentType(MediaType.APPLICATION_JSON)
            .content("""{"preferred_workout_time":"07:30"}"""))
        assertEquals(200, res.status, res.contentAsString)

        val schedules = scheduleRepository.findByUser(user).filter { it.notificationType == NotificationType.AI_MESSAGE }
        assertEquals(5, schedules.size, "일정 이름: ${schedules.map { it.scheduleName to it.time }}")
        assertEquals(5, schedules.map { it.scheduleName }.toSet().size)
        assertEquals(LocalTime.of(7, 0), schedules.single { it.scheduleName == "workout_reminder" }.time)
    }
}

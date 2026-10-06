package com.richjun.liftupai.domain.user.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.user.dto.BodyInfoUpdateDto
import com.richjun.liftupai.domain.user.dto.OnboardingRequest
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class UserServiceOnboardingTest {

    private val userRepository = mockk<UserRepository>()
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val userSettingsRepository = mockk<UserSettingsRepository>()
    private val objectMapper = ObjectMapper()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val ptScheduledMessageService = mockk<PTScheduledMessageService>(relaxed = true)

    private val service = UserService(
        userRepository = userRepository,
        userProfileRepository = userProfileRepository,
        userSettingsRepository = userSettingsRepository,
        objectMapper = objectMapper,
        notificationService = notificationService,
        ptScheduledMessageService = ptScheduledMessageService
    )

    @Test
    fun `complete onboarding stores strength assessment estimates for authenticated users`() {
        val user = User(id = 7L, email = "test@example.com", nickname = "before")
        val savedProfile = slot<UserProfile>()

        every { userRepository.findById(7L) } returns Optional.of(user)
        every { userProfileRepository.findByUser_Id(7L) } returns Optional.empty()
        every { userSettingsRepository.findByUser_Id(7L) } returns Optional.empty()
        every { userRepository.save(any()) } answers { firstArg() }
        every { userProfileRepository.save(capture(savedProfile)) } answers { firstArg() }
        every { userSettingsRepository.save(any()) } answers { firstArg<UserSettings>() }

        service.completeOnboarding(
            userId = 7L,
            request = OnboardingRequest(
                nickname = "after",
                experienceLevel = "BEGINNER",
                goals = listOf("STRENGTH"),
                bodyInfo = BodyInfoUpdateDto(
                    height = 180.0,
                    weight = 80.0,
                    bodyFat = null,
                    muscleMass = null,
                    age = 30,
                    gender = "male"
                ),
                ptStyle = "FRIENDLY",
                notificationEnabled = true,
                weeklyWorkoutDays = 3,
                workoutSplit = "full_body",
                availableEquipment = listOf("DUMBBELL"),
                preferredWorkoutTime = "evening",
                workoutDuration = 60,
                timeZone = null,
                injuries = listOf("knee"),
                strengthAssessment = mapOf(
                    "pushupReps" to 10,
                    "pullupReps" to 5,
                    "squatReps" to 12
                )
            )
        )

        assertTrue(savedProfile.captured.strengthTestCompleted)
        assertNotNull(savedProfile.captured.estimatedMaxes)
        val maxes = objectMapper.readValue<Map<String, Double>>(savedProfile.captured.estimatedMaxes!!)
        // 1RM 추정(초보 남성 80kg, 30세): 벤치 Epley 68.3 → 초보 상한 0.75×80 = 60,
        // 풀업 5회 → 93.3 → 상한 1.0×80 → 랫풀다운 ×0.9 = 72, 스쿼트 12회 → 95.2 → 상한 80 → 레그프레스 ×1.5 = 120
        assertEquals(60.0, maxes["bench-press"]!!, 0.001)
        assertEquals(60.0, maxes["barbell-bench-press"]!!, 0.001, "실제 카탈로그 slug 키도 저장돼야 플랜이 읽는다")
        assertEquals(72.0, maxes["lat-pulldown"]!!, 0.001)
        assertEquals(120.0, maxes["leg-press"]!!, 0.001)
        assertEquals(setOf("DUMBBELL"), savedProfile.captured.availableEquipment)
        assertEquals(setOf("knee"), savedProfile.captured.injuries)
    }

    @Test
    fun `complete onboarding keeps the experience level the app sends in lowercase`() {
        val user = User(id = 8L, email = "adv@example.com", nickname = "before")
        val savedProfile = slot<UserProfile>()

        every { userRepository.findById(8L) } returns Optional.of(user)
        every { userProfileRepository.findByUser_Id(8L) } returns Optional.empty()
        every { userSettingsRepository.findByUser_Id(8L) } returns Optional.empty()
        every { userRepository.save(any()) } answers { firstArg() }
        every { userProfileRepository.save(capture(savedProfile)) } answers { firstArg() }
        every { userSettingsRepository.save(any()) } answers { firstArg<UserSettings>() }

        service.completeOnboarding(
            userId = 8L,
            request = OnboardingRequest(
                nickname = "adv",
                experienceLevel = "advanced",
                goals = listOf("bulk_up"),
                bodyInfo = null,
                ptStyle = "GAME_MASTER",
                notificationEnabled = true,
                weeklyWorkoutDays = 5,
                workoutSplit = "auto",
                availableEquipment = null,
                preferredWorkoutTime = "evening",
                workoutDuration = 60,
                timeZone = null,
                injuries = null
            )
        )

        assertEquals(ExperienceLevel.ADVANCED, savedProfile.captured.experienceLevel)
    }

    @Test
    fun `complete onboarding keeps detailed focus goals in settings while FitnessGoal collapses them`() {
        // 2026-10-06: thick_legs·glutes 같은 세부 목표는 MUSCLE_GAIN 으로 뭉개져 어느 부위를 집중할지 사라졌다
        val user = User(id = 9L, email = "focus@example.com", nickname = "before")
        val savedSettings = slot<UserSettings>()

        every { userRepository.findById(9L) } returns Optional.of(user)
        every { userProfileRepository.findByUser_Id(9L) } returns Optional.empty()
        every { userSettingsRepository.findByUser_Id(9L) } returns Optional.empty()
        every { userRepository.save(any()) } answers { firstArg() }
        every { userProfileRepository.save(any()) } answers { firstArg<UserProfile>() }
        every { userSettingsRepository.save(capture(savedSettings)) } answers { firstArg<UserSettings>() }

        service.completeOnboarding(
            userId = 9L,
            request = OnboardingRequest(
                nickname = "focus",
                experienceLevel = "beginner",
                goals = listOf("weight_loss", "Thick-Legs", "glutes", "general_fitness"),
                bodyInfo = null,
                ptStyle = "GAME_MASTER",
                notificationEnabled = true,
                weeklyWorkoutDays = 3,
                workoutSplit = "auto",
                availableEquipment = null,
                preferredWorkoutTime = "evening",
                workoutDuration = 45,
                timeZone = null,
                injuries = null
            )
        )

        // 집중 부위 목표만 소문자 표기로 보존(체중 감량·일반 체력 같은 일반 목표는 FitnessGoal 이 이미 담는다)
        assertEquals(setOf("thick_legs", "glutes"), savedSettings.captured.focusGoals)
    }
}

package com.richjun.liftupai.domain.user.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.user.dto.BodyInfoUpdateDto
import com.richjun.liftupai.domain.user.dto.OnboardingRequest
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
        assertEquals(40.0, maxes["bench-press"]!!, 0.001)
        assertEquals(60.667, maxes["lat-pulldown"]!!, 0.001)
        assertEquals(89.6, maxes["leg-press"]!!, 0.001)
        assertEquals(setOf("DUMBBELL"), savedProfile.captured.availableEquipment)
        assertEquals(setOf("knee"), savedProfile.captured.injuries)
    }
}

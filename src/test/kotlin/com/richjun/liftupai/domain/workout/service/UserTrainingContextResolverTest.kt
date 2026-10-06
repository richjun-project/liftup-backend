package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class UserTrainingContextResolverTest {

    private fun resolve(profileInjuries: Set<String>?, settingsInjuries: Set<String>?): Set<String> {
        val settingsRepo = mockk<UserSettingsRepository>()
        val profileRepo = mockk<UserProfileRepository>()
        val settings = settingsInjuries?.let {
            mockk<UserSettings>(relaxed = true).also { s ->
                every { s.injuries } returns it.toMutableSet()
                every { s.availableEquipment } returns mutableSetOf()
                every { s.focusGoals } returns mutableSetOf()
            }
        }
        val profile = profileInjuries?.let {
            mockk<UserProfile>(relaxed = true).also { p ->
                every { p.injuries } returns it.toMutableSet()
                every { p.availableEquipment } returns mutableSetOf()
                every { p.experienceLevel } returns com.richjun.liftupai.domain.user.entity.ExperienceLevel.values().first()
            }
        }
        every { settingsRepo.findByUser_Id(1L) } returns Optional.ofNullable(settings)
        every { profileRepo.findByUser_Id(1L) } returns Optional.ofNullable(profile)
        return UserTrainingContextResolver(settingsRepo, profileRepo).resolve(1L).injuries
    }

    @Test
    fun `프로필에서 지운 부상이 설정에 남아 있어도 걸러내지 않는다`() {
        assertEquals(emptySet<String>(), resolve(profileInjuries = emptySet(), settingsInjuries = setOf("knee")))
    }

    @Test
    fun `프로필의 부상은 그대로 읽는다`() {
        assertEquals(setOf("shoulder"), resolve(profileInjuries = setOf("Shoulder"), settingsInjuries = setOf("knee")))
    }

    @Test
    fun `프로필이 없으면 설정의 부상을 쓴다`() {
        assertEquals(setOf("knee"), resolve(profileInjuries = null, settingsInjuries = setOf("knee")))
    }
}

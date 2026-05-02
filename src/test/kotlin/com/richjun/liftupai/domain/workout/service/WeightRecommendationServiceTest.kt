package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class WeightRecommendationServiceTest {

    @Test
    fun `bodyweight exercise default recommendation stays at zero kg`() {
        val user = User(id = 1L, email = "test@test.com", nickname = "Test", level = UserLevel.BEGINNER)
        val exercise = Exercise(
            id = 10L,
            slug = "push-up",
            name = "Push Up",
            category = ExerciseCategory.CHEST,
            equipment = Equipment.BODYWEIGHT
        )
        val personalRecordRepository = mockk<PersonalRecordRepository>()
        val exerciseRepository = mockk<ExerciseRepository>()
        val userProfileRepository = mockk<UserProfileRepository>()
        val service = WeightRecommendationService(
            personalRecordRepository = personalRecordRepository,
            exerciseRepository = exerciseRepository,
            userProfileRepository = userProfileRepository
        )

        every { personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(user.id, exercise.id) } returns null
        every { exerciseRepository.findById(exercise.id) } returns Optional.of(exercise)
        every { userProfileRepository.findByUser_Id(user.id) } returns Optional.of(
            UserProfile(
                id = 1L,
                user = user,
                bodyInfo = BodyInfo(weight = 80.0)
            )
        )

        val result = service.calculateSuggestedWeight(user.id, exercise.id, targetReps = 12)

        assertEquals(0.0, result)
    }
}

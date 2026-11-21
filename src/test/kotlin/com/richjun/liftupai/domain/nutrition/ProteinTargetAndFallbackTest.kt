package com.richjun.liftupai.domain.nutrition

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 11-21: 체중 없는 사용자의 단백질 목표가 목표·성별과 무관하게 60g 이던 것, 벡터 검색 실패 폴백이 장비를 안 보던 것.
 */
class ProteinTargetAndFallbackTest : ApiTestSupport() {
    @Autowired private lateinit var vectorReco: VectorWorkoutRecommendationService

    private fun profile(gender: String?, weight: Double?, vararg goals: FitnessGoal) =
        UserProfile(user = User(email = "x@y.z", nickname = "x")).apply {
            this.gender = gender
            if (weight != null) bodyInfo = BodyInfo(weight = weight, height = 175.0)
            this.goals.addAll(goals)
        }

    @Test
    fun `체중이 없으면 성별 기준 체중으로 같은 배수`() {
        assertEquals(154.0, NutritionTargetCalculator.calculateProteinTarget(profile("male", null, FitnessGoal.MUSCLE_GAIN)), 0.01)
        assertEquals(85.5, NutritionTargetCalculator.calculateProteinTarget(profile("female", null, FitnessGoal.GENERAL_FITNESS)), 0.01)
        assertEquals(96.0, NutritionTargetCalculator.calculateProteinTarget(profile(null, null, FitnessGoal.STRENGTH)), 0.01)
        assertEquals(120.0, NutritionTargetCalculator.calculateProteinTarget(profile("male", 80.0, FitnessGoal.STRENGTH)), 0.01, "체중이 있으면 예전과 같다")
        assertTrue(NutritionTargetCalculator.calculateProteinTarget(profile("male", null, FitnessGoal.STRENGTH)) > 60.0, "예전 60g 고정이 아니다")
    }

    @Test
    fun `벡터 검색이 안 될 때 폴백도 사용자 장비를 지킨다`() {
        allowedErrorLoggers += VectorWorkoutRecommendationService::class.java.name
        val user = newUser("fb")
        val bw = vectorReco.recommendExercises(user = user, profile = null, duration = 30, targetMuscle = "full_body", equipment = null,
            availableEquipment = setOf("BODYWEIGHT"), difficulty = null, workoutType = null, limit = 20)
        assertTrue(bw.isNotEmpty(), "맨몸 후보가 있어야 한다")
        assertTrue(bw.all { it.equipment == null || it.equipment == Equipment.BODYWEIGHT }, "맨몸 사용자 후보에 기구 운동: ${bw.filter { it.equipment != Equipment.BODYWEIGHT }.map { it.slug }}")
        val gym = vectorReco.recommendExercises(user = user, profile = null, duration = 30, targetMuscle = "full_body", equipment = null,
            availableEquipment = emptySet(), difficulty = null, workoutType = null, limit = 20)
        assertTrue(gym.any { it.equipment != null && it.equipment != Equipment.BODYWEIGHT }, "양성 대조: 장비 제한이 없으면 기구 운동도 나온다")
    }
}

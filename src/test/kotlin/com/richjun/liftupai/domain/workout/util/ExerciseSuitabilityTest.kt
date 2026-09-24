package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseSuitabilityTest {

    private fun ex(slug: String, pattern: String, equipment: Equipment, difficulty: Int = 30) = Exercise(
        id = 1L, slug = slug, name = slug, category = ExerciseCategory.BACK,
        equipment = equipment, movementPattern = pattern, difficulty = difficulty,
    )

    private val pullUp = ex("pull-up-normal-grip", "PULLUP_CHINUP", Equipment.BODYWEIGHT, 60)
    private val assisted = ex("assisted-pull-up", "PULLUP_CHINUP", Equipment.MACHINE, 35)
    private val benchDip = ex("chest-dip-on-bench", "DIPS", Equipment.BODYWEIGHT, 50)
    private val barDip = ex("chest-dip", "DIPS", Equipment.BODYWEIGHT, 50)
    private val pushUp = ex("push-ups-bodyweight", "PUSHUP", Equipment.BODYWEIGHT, 30)
    private val jump = ex("jumping-jack", "PLYOMETRIC", Equipment.BODYWEIGHT, 55)

    @Test
    fun `home user without gym equipment gets no bar exercises`() {
        val home = listOf("bodyweight", "resistance_band")
        assertFalse(ExerciseSuitability.suitable(pullUp, home, ExperienceLevel.ADVANCED, 30))
        assertFalse(ExerciseSuitability.suitable(barDip, home, ExperienceLevel.ADVANCED, 30))
        assertTrue(ExerciseSuitability.suitable(benchDip, home, ExperienceLevel.ADVANCED, 30))
        assertTrue(ExerciseSuitability.suitable(pushUp, home, ExperienceLevel.ADVANCED, 30))
    }

    @Test
    fun `gym user keeps pull-ups when experienced`() {
        assertTrue(ExerciseSuitability.suitable(pullUp, listOf("bodyweight", "barbell", "machine"), ExperienceLevel.INTERMEDIATE, 30))
    }

    @Test
    fun `beginners and elderly get assisted instead of bodyweight pull-ups`() {
        val gym = listOf("bodyweight", "machine", "cable")
        assertFalse(ExerciseSuitability.suitable(pullUp, gym, ExperienceLevel.BEGINNER, 30))
        assertTrue(ExerciseSuitability.suitable(assisted, gym, ExperienceLevel.BEGINNER, 30))
        assertFalse(ExerciseSuitability.suitable(pullUp, gym, ExperienceLevel.ADVANCED, 72))
    }

    @Test
    fun `elderly and young teens skip explosive moves and dips, adults keep them`() {
        assertFalse(ExerciseSuitability.suitable(jump, emptyList(), ExperienceLevel.NOVICE, 70))
        assertFalse(ExerciseSuitability.suitable(benchDip, emptyList(), ExperienceLevel.NOVICE, 14))
        assertTrue(ExerciseSuitability.suitable(jump, emptyList(), ExperienceLevel.NOVICE, 30))
        assertTrue(ExerciseSuitability.suitable(pushUp, emptyList(), ExperienceLevel.BEGINNER, 72))
    }
}

package com.richjun.liftupai.domain.user.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrengthAssessmentEstimatorTest {

    @Test
    fun `estimates initial maxes from camelCase onboarding keys`() {
        val maxes = StrengthAssessmentEstimator.estimateMaxes(
            assessment = mapOf(
                "pushupReps" to 10,
                "pullupReps" to 5,
                "squatReps" to 12
            ),
            bodyWeightKg = 80.0
        )

        assertEquals(16, maxes.size)
        assertEquals(40.0, maxes["bench-press"]!!, 0.001)
        assertEquals(60.667, maxes["lat-pulldown"]!!, 0.001)
        assertEquals(89.6, maxes["leg-press"]!!, 0.001)
    }

    @Test
    fun `estimates initial maxes from snake_case device keys`() {
        val maxes = StrengthAssessmentEstimator.estimateMaxes(
            assessment = mapOf(
                "pushup_reps" to 20,
                "pullup_reps" to 6,
                "squat_reps" to 20
            ),
            bodyWeightKg = 70.0
        )

        assertEquals(16, maxes.size)
        assertEquals(35.0, maxes["bench-press"]!!, 0.001)
        assertEquals(46.2, maxes["barbell-row"]!!, 0.001)
        assertEquals(84.0, maxes["leg-press"]!!, 0.001)
    }

    @Test
    fun `ignores non strength-estimating fields`() {
        val maxes = StrengthAssessmentEstimator.estimateMaxes(
            assessment = mapOf("plankSeconds" to 60),
            bodyWeightKg = 70.0
        )

        assertTrue(maxes.isEmpty())
    }
}

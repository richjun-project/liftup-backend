package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

@Service
class WeightRecommendationService(
    private val personalRecordRepository: PersonalRecordRepository
) {
    private val log = LoggerFactory.getLogger(WeightRecommendationService::class.java)

    /**
     * Calculate suggested weight for an exercise based on user's personal records.
     * Uses Brzycki formula to reverse-calculate working weight from estimated 1RM.
     */
    fun calculateSuggestedWeight(userId: Long, exerciseId: Long, targetReps: Int): Double? {
        val pr = personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(userId, exerciseId)
            ?: return null

        // Brzycki formula is invalid at 37+ reps (division by zero or negative)
        if (pr.reps >= 37) return null
        if (targetReps >= 37) return null

        // Estimate 1RM from PR using Brzycki formula
        val estimated1RM = if (pr.reps <= 10) {
            pr.weight * (36.0 / (37.0 - pr.reps))
        } else {
            pr.weight * (1 + pr.reps / 30.0)
        }

        // Reverse Brzycki: working weight = 1RM * (37 - targetReps) / 36
        val workingWeight = estimated1RM * (37.0 - targetReps) / 36.0

        // Round to nearest 2.5kg
        return (workingWeight / 2.5).roundToInt() * 2.5
    }
}

package com.richjun.liftupai.domain.user.service

object StrengthAssessmentEstimator {
    fun estimateMaxes(assessment: Map<String, Any>?, bodyWeightKg: Double?): Map<String, Double> {
        if (assessment.isNullOrEmpty()) return emptyMap()

        val bodyWeight = bodyWeightKg?.takeIf { it > 0.0 } ?: 65.0
        val pushups = readNonNegativeInt(assessment, "pushup_reps", "pushupReps")
        val pullups = readNonNegativeInt(assessment, "pullup_reps", "pullupReps")
        val squats = readNonNegativeInt(assessment, "squat_reps", "squatReps")

        val estimatedMaxes = mutableMapOf<String, Double>()

        if (pushups > 0) {
            val cappedReps = minOf(pushups, 15)
            val pushLoad = bodyWeight * 0.63
            val est1RM = pushLoad * (1 + cappedReps / 30.0)
            val benchWeight = (est1RM * 0.65).coerceAtMost(bodyWeight * 0.5)
            estimatedMaxes["bench-press"] = benchWeight
            estimatedMaxes["barbell-bench-press"] = benchWeight
            estimatedMaxes["dumbbell-bench-press"] = benchWeight
            estimatedMaxes["machine-chest-press"] = benchWeight * 1.1
            estimatedMaxes["incline-dumbbell-press"] = benchWeight * 0.85

            val overheadPressWeight = (est1RM * 0.45).coerceAtMost(bodyWeight * 0.35)
            estimatedMaxes["overhead-press"] = overheadPressWeight
            estimatedMaxes["dumbbell-shoulder-press"] = overheadPressWeight
            estimatedMaxes["machine-shoulder-press"] = overheadPressWeight * 1.1
        }

        if (pullups > 0) {
            val cappedReps = minOf(pullups, 15)
            val est1RM = bodyWeight * (1 + cappedReps / 30.0)
            estimatedMaxes["barbell-row"] = est1RM * 0.55
            estimatedMaxes["dumbbell-row"] = est1RM * 0.5
            estimatedMaxes["seated-cable-row"] = est1RM * 0.55
            estimatedMaxes["lat-pulldown"] = est1RM * 0.65
        }

        if (squats > 0) {
            val cappedReps = minOf(squats, 15)
            val est1RM = bodyWeight * (1 + cappedReps / 30.0)
            estimatedMaxes["leg-press"] = est1RM * 0.80
            estimatedMaxes["goblet-squat"] = (est1RM * 0.35).coerceAtMost(bodyWeight * 0.4)
            estimatedMaxes["dumbbell-lunge"] = (est1RM * 0.3).coerceAtMost(bodyWeight * 0.35)
            estimatedMaxes["barbell-back-squat"] = (est1RM * 0.45).coerceAtMost(bodyWeight * 0.5)
        }

        return estimatedMaxes
    }

    private fun readNonNegativeInt(assessment: Map<String, Any>, vararg keys: String): Int {
        val value = keys.firstNotNullOfOrNull { key -> assessment[key] } ?: return 0
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }
}

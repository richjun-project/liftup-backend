package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.global.i18n.ErrorLocalization
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

data class AbsenceStatus(
    val daysAbsent: Int,
    val needsWeightReduction: Boolean,
    val weightReductionPercent: Double,  // 0.0 to 0.20
    val shouldPause: Boolean,
    val message: String?
)

@Service
@Transactional(readOnly = true)
class AbsenceDetectionService {

    fun checkAbsence(enrollment: UserProgramEnrollment, locale: String = "en"): AbsenceStatus {
        val lastActive = enrollment.lastActiveDate ?: enrollment.startDate
        val now = AppTime.utcNow()
        val daysAbsent = ChronoUnit.DAYS.between(lastActive, now).toInt()

        return when {
            daysAbsent >= 21 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = true,
                weightReductionPercent = 0.20,
                shouldPause = true,
                message = ErrorLocalization.message("absence.21_days", locale)
            )
            daysAbsent >= 14 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = true,
                weightReductionPercent = 0.125, // midpoint of 0.10–0.15
                shouldPause = false,
                message = ErrorLocalization.message("absence.14_days", locale)
            )
            daysAbsent >= 7 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = true,
                weightReductionPercent = 0.05,
                shouldPause = false,
                message = ErrorLocalization.message("absence.7_days", locale)
            )
            else -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = false,
                weightReductionPercent = 0.0,
                shouldPause = false,
                message = null
            )
        }
    }
}

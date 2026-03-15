package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
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

    fun checkAbsence(enrollment: UserProgramEnrollment): AbsenceStatus {
        val lastActive = enrollment.lastActiveDate ?: enrollment.startDate
        val now = AppTime.utcNow()
        val daysAbsent = ChronoUnit.DAYS.between(lastActive, now).toInt()

        return when {
            daysAbsent >= 21 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = true,
                weightReductionPercent = 0.20,
                shouldPause = true,
                message = "21일 이상 공백이 있습니다. 무게를 20% 줄이고 천천히 재개하세요."
            )
            daysAbsent >= 14 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = true,
                weightReductionPercent = 0.125, // midpoint of 0.10–0.15
                shouldPause = false,
                message = "2주 이상 공백이 있습니다. 무게를 10~15% 줄이고 재개하세요."
            )
            daysAbsent >= 7 -> AbsenceStatus(
                daysAbsent = daysAbsent,
                needsWeightReduction = false,
                weightReductionPercent = 0.0,
                shouldPause = true,
                message = "1주 이상 공백이 있습니다. 가벼운 무게로 워밍업 세션을 권장합니다."
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

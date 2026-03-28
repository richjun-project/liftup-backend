package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
import com.richjun.liftupai.global.i18n.ErrorLocalization
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.richjun.liftupai.global.time.AppTime
import kotlin.math.roundToInt

@Service
class ProgramGraduationService(
    private val enrollmentRepository: UserProgramEnrollmentRepository,
    private val programRepository: CanonicalProgramRepository,
    private val enrollmentService: ProgramEnrollmentService
) {
    data class GraduationStatus(
        val shouldGraduate: Boolean,
        val completionRate: Double,  // 0.0 to 1.0
        val currentWeek: Int,
        val totalWeeks: Int,
        val nextProgramCode: String?,
        val nextProgramName: String?,
        val reason: String
    )

    fun checkGraduation(enrollment: UserProgramEnrollment, locale: String = "en"): GraduationStatus {
        val program = enrollment.program
        val position = enrollmentService.getCurrentPosition(enrollment)
        val totalExpectedWorkouts = (program.daysPerWeek * program.programDurationWeeks).coerceAtLeast(1)
        val completionRate = enrollment.totalCompletedWorkouts.toDouble() / totalExpectedWorkouts

        val shouldGraduate = position.week > program.programDurationWeeks && completionRate >= 0.8

        val nextProgram = program.nextProgramCode?.let {
            programRepository.findByCode(it)
        }

        val reason = when {
            shouldGraduate ->
                ErrorLocalization.message("graduation.complete", locale)
            position.week > program.programDurationWeeks && completionRate < 0.8 ->
                ErrorLocalization.message("graduation.low_attendance", locale, (completionRate * 100).roundToInt())
            else ->
                ErrorLocalization.message("graduation.in_progress", locale, position.week, program.programDurationWeeks)
        }

        return GraduationStatus(
            shouldGraduate = shouldGraduate,
            completionRate = completionRate,
            currentWeek = position.week,
            totalWeeks = program.programDurationWeeks,
            nextProgramCode = nextProgram?.code,
            nextProgramName = nextProgram?.name,
            reason = reason
        )
    }

    @Transactional
    fun graduate(enrollment: UserProgramEnrollment): UserProgramEnrollment? {
        // Mark current as completed
        enrollment.status = EnrollmentStatus.COMPLETED
        enrollment.endDate = AppTime.utcNow()
        enrollmentRepository.save(enrollment)

        // Auto-enroll in next program if available
        val nextCode = enrollment.program.nextProgramCode ?: return null
        programRepository.findByCode(nextCode) ?: return null

        return enrollmentService.enrollUser(enrollment.user, nextCode)
    }
}

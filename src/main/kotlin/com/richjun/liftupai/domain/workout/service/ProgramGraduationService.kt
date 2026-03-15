package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
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

    fun checkGraduation(enrollment: UserProgramEnrollment): GraduationStatus {
        val program = enrollment.program
        val position = enrollmentService.getCurrentPosition(enrollment)
        val totalExpectedWorkouts = program.daysPerWeek * program.programDurationWeeks
        val completionRate = enrollment.totalCompletedWorkouts.toDouble() / totalExpectedWorkouts

        val shouldGraduate = position.week > program.programDurationWeeks && completionRate >= 0.8

        val nextProgram = program.nextProgramCode?.let {
            programRepository.findByCode(it)
        }

        val reason = when {
            shouldGraduate ->
                "프로그램을 성공적으로 완료했습니다! 다음 단계로 넘어갈 준비가 되었습니다."
            position.week > program.programDurationWeeks && completionRate < 0.8 ->
                "프로그램 기간이 완료되었지만 출석률이 ${(completionRate * 100).roundToInt()}%입니다. 마지막 4주를 반복하시겠습니까?"
            else ->
                "프로그램 진행 중 (${position.week}/${program.programDurationWeeks}주)"
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

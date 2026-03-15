package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ProgramPosition(
    val week: Int,
    val dayInCycle: Int,
    val isDeloadWeek: Boolean,
    val isNewCycle: Boolean
)

@Service
@Transactional
class ProgramEnrollmentService(
    private val userProgramEnrollmentRepository: UserProgramEnrollmentRepository,
    private val canonicalProgramRepository: CanonicalProgramRepository,
    private val injuryFilterService: InjuryFilterService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun enrollUser(user: User, programCode: String): UserProgramEnrollment {
        // Abandon any existing active enrollment
        val existing = userProgramEnrollmentRepository
            .findFirstByUserAndStatusOrderByStartDateDesc(user, EnrollmentStatus.ACTIVE)
        existing?.let {
            it.status = EnrollmentStatus.ABANDONED
            it.endDate = AppTime.utcNow()
            userProgramEnrollmentRepository.save(it)
            logger.info("Abandoned previous enrollment id={} for user={}", it.id, user.id)
        }

        val program = canonicalProgramRepository.findByCode(programCode)
            ?: throw ResourceNotFoundException("Program not found with code: $programCode")

        val enrollment = UserProgramEnrollment(
            user = user,
            program = program,
            programVersion = program.version,
            startDate = AppTime.utcNow(),
            status = EnrollmentStatus.ACTIVE
        )
        val saved = userProgramEnrollmentRepository.save(enrollment)

        // Auto-apply injury-based overrides from user settings
        // injuries come from user.profile.injuries (legacy) or user_settings_injuries
        val injuries = collectUserInjuries(user)
        if (injuries.isNotEmpty()) {
            injuryFilterService.autoApplyOverrides(saved, injuries)
            logger.info("Applied injury overrides for user={} injuries={}", user.id, injuries)
        }

        return saved
    }

    fun getCurrentEnrollment(user: User): UserProgramEnrollment? {
        return userProgramEnrollmentRepository
            .findFirstByUserAndStatusOrderByStartDateDesc(user, EnrollmentStatus.ACTIVE)
    }

    @Transactional(readOnly = true)
    fun getCurrentPosition(enrollment: UserProgramEnrollment): ProgramPosition {
        val program = enrollment.program
        val total = enrollment.totalCompletedWorkouts
        val daysPerWeek = program.daysPerWeek.coerceAtLeast(1)

        val week = (total / daysPerWeek) + 1
        val dayInCycle = (total % daysPerWeek) + 1
        val isDeloadWeek = (week % program.deloadEveryNWeeks) == 0
        val isNewCycle = total > 0 && total % daysPerWeek == 0

        return ProgramPosition(
            week = week,
            dayInCycle = dayInCycle,
            isDeloadWeek = isDeloadWeek,
            isNewCycle = isNewCycle
        )
    }

    fun completeWorkout(enrollment: UserProgramEnrollment) {
        enrollment.totalCompletedWorkouts++
        enrollment.lastActiveDate = AppTime.utcNow()
        userProgramEnrollmentRepository.save(enrollment)
        logger.debug(
            "Completed workout for enrollment={}, total={}",
            enrollment.id, enrollment.totalCompletedWorkouts
        )
    }

    fun pauseEnrollment(user: User) {
        val enrollment = getCurrentEnrollment(user)
            ?: throw ResourceNotFoundException("No active enrollment found for user: ${user.id}")
        enrollment.status = EnrollmentStatus.PAUSED
        userProgramEnrollmentRepository.save(enrollment)
    }

    fun resumeEnrollment(user: User) {
        val enrollment = userProgramEnrollmentRepository
            .findFirstByUserAndStatusOrderByStartDateDesc(user, EnrollmentStatus.PAUSED)
            ?: throw ResourceNotFoundException("No paused enrollment found for user: ${user.id}")
        enrollment.status = EnrollmentStatus.ACTIVE
        enrollment.lastActiveDate = AppTime.utcNow()
        userProgramEnrollmentRepository.save(enrollment)
    }

    fun abandonEnrollment(user: User) {
        val enrollment = getCurrentEnrollment(user)
            ?: throw ResourceNotFoundException("No active enrollment found for user: ${user.id}")
        enrollment.status = EnrollmentStatus.ABANDONED
        enrollment.endDate = AppTime.utcNow()
        userProgramEnrollmentRepository.save(enrollment)
    }

    private fun collectUserInjuries(user: User): Set<String> {
        val profileInjuries = user.profile?.injuries ?: emptySet()
        return profileInjuries.toSet()
    }
}

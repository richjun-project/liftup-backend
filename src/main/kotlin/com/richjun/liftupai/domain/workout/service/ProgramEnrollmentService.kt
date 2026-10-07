package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.SubstitutionReason
import com.richjun.liftupai.domain.workout.entity.UserExerciseOverride
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.workout.repository.CanonicalProgramRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSubstitutionRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
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
    private val injuryFilterService: InjuryFilterService,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val programDayRepository: ProgramDayRepository,
    private val programDayExerciseRepository: ProgramDayExerciseRepository,
    private val exerciseSubstitutionRepository: ExerciseSubstitutionRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    /** 부상·장비의 단일 출처 */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
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

        // 등록 시점의 부상·장비 오버라이드는 큐레이션 표(현재 비어 있음)와 대체 목록에만 의존하는 1회성 힌트다.
        // 실제 안전은 ProgramWorkoutGeneratorService 가 운동 생성 시점마다 단일 해석기의 부상(프로필 ∪ 설정)·장비·수준 규칙으로 지킨다.
        val ctx = trainingContext.resolve(user.id)
        val injuries = ctx.injuries
        if (injuries.isNotEmpty()) {
            injuryFilterService.autoApplyOverrides(saved, injuries)
            logger.info("Applied injury overrides for user={} injuries={}", user.id, injuries)
        }

        // Auto-apply equipment-based overrides (장비 정보가 없거나 헬스장 전체면 제한 없음 — 건너뛴다)
        if (ctx.equipment.isNotEmpty()) {
            autoApplyEquipmentOverrides(saved, ctx.equipment)
            logger.info("Applied equipment overrides for user={} equipment={}", user.id, ctx.equipment)
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
        val isDeloadWeek = program.deloadEveryNWeeks > 0 && week > 1 && (week % program.deloadEveryNWeeks) == 0
        val isNewCycle = total > 0 && total % daysPerWeek == 0

        return ProgramPosition(
            week = week,
            dayInCycle = dayInCycle,
            isDeloadWeek = isDeloadWeek,
            isNewCycle = isNewCycle
        )
    }

    /**
     * Internal use only — increments totalCompletedWorkouts on the enrollment.
     * The primary call path goes through WorkoutSessionService.completeWorkout(), which
     * directly updates the enrollment counter. Do NOT call this method from
     * WorkoutSessionService to avoid double-incrementing.
     */
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

    private fun autoApplyEquipmentOverrides(enrollment: UserProgramEnrollment, availableEquipment: Set<String>) {
        val days = programDayRepository.findByProgramIdOrderByDayNumber(enrollment.program.id)
        days.forEach { day ->
            val exercises = programDayExerciseRepository.findByDayIdWithExercises(day.id)
            exercises.forEach { pde ->
                val exerciseEquipment = pde.exercise.equipment?.name?.uppercase()
                // 맨몸·기타(밴드·철봉) 운동은 장비가 필요 없다 — 문자열 일치만 보면 맨몸 운동까지 "장비 없음" 으로 바꿔 버린다
                if (exerciseEquipment != null && !TemplatePlanAdapter.equipmentAllowed(pde.exercise, availableEquipment)) {
                    // Exercise requires equipment user doesn't have → find substitute
                    val existingOverride = userExerciseOverrideRepository
                        .findByEnrollmentIdAndOriginalExerciseId(enrollment.id, pde.exercise.id)
                    if (existingOverride != null) return@forEach

                    val substitute = exerciseSubstitutionRepository
                        .findByOriginalExerciseIdOrderByPriority(pde.exercise.id)
                        .firstOrNull { sub -> TemplatePlanAdapter.equipmentAllowed(sub.substituteExercise, availableEquipment) }

                    if (substitute != null) {
                        val override = UserExerciseOverride(
                            enrollment = enrollment,
                            originalExercise = pde.exercise,
                            substituteExercise = substitute.substituteExercise,
                            reason = SubstitutionReason.EQUIPMENT
                        )
                        userExerciseOverrideRepository.save(override)
                        logger.info(
                            "Auto-applied equipment override: enrollment={} original={} substitute={} equipment={}",
                            enrollment.id, pde.exercise.id, substitute.substituteExercise.id, exerciseEquipment
                        )
                    }
                }
            }
        }
    }
}

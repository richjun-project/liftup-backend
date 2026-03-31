package com.richjun.liftupai.domain.user.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.entity.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun parseFitnessGoal(value: String): FitnessGoal? {
    return when (value.uppercase()) {
        "MUSCLE_GAIN" -> FitnessGoal.MUSCLE_GAIN
        "FAT_LOSS", "WEIGHT_LOSS" -> FitnessGoal.WEIGHT_LOSS
        "STRENGTH", "STRENGTH_GAIN" -> FitnessGoal.STRENGTH
        "ENDURANCE" -> FitnessGoal.ENDURANCE
        "GENERAL_FITNESS", "BODY_CORRECTION", "HEALTH_MAINTENANCE" -> FitnessGoal.GENERAL_FITNESS
        "ATHLETIC_PERFORMANCE" -> FitnessGoal.ATHLETIC_PERFORMANCE
        else -> null
    }
}

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Transactional(readOnly = true)
    fun getProfile(userId: Long): UserProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        return mapToProfileResponse(user, profile)
    }

    fun updateProfile(userId: Long, request: UpdateProfileRequest): UserProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        // Update nickname if provided
        request.nickname?.let { user.nickname = it }

        // Update body info
        request.bodyInfo?.let { bodyInfoDto ->
            profile.bodyInfo = BodyInfo(
                height = bodyInfoDto.height,
                weight = bodyInfoDto.weight,
                bodyFat = bodyInfoDto.bodyFat,
                muscleMass = bodyInfoDto.muscleMass
            )
        }

        // Update goals
        request.goals?.let { goals ->
            profile.goals.clear()
            profile.goals.addAll(goals.mapNotNull { parseFitnessGoal(it) })
        }

        // Update PT style
        request.ptStyle?.let { ptStyle ->
            try {
                profile.ptStyle = PTStyle.valueOf(ptStyle)
            } catch (e: Exception) {
                // Invalid PT style, ignore
            }
        }

        profile.updatedAt = AppTime.utcNow()
        userRepository.save(user)
        userProfileRepository.save(profile)

        return mapToProfileResponse(user, profile)
    }

    fun completeOnboarding(userId: Long, request: OnboardingRequest): UserProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Update nickname
        user.nickname = request.nickname

        // Create or update profile
        val profile = userProfileRepository.findByUser_Id(userId).orElse(
            UserProfile(
                user = user,
                bodyInfo = null,
                goals = mutableSetOf(),
                ptStyle = PTStyle.GAME_MASTER,
                experienceLevel = ExperienceLevel.BEGINNER
            )
        )

        // Update experience level
        try {
            profile.experienceLevel = ExperienceLevel.valueOf(request.experienceLevel)
        } catch (e: Exception) {
            profile.experienceLevel = ExperienceLevel.BEGINNER
        }

        // Update body info
        request.bodyInfo?.let { bodyInfoDto ->
            profile.bodyInfo = BodyInfo(
                height = bodyInfoDto.height,
                weight = bodyInfoDto.weight,
                bodyFat = bodyInfoDto.bodyFat,
                muscleMass = bodyInfoDto.muscleMass
            )
        }

        // Update goals
        profile.goals.clear()
        profile.goals.addAll(request.goals.mapNotNull { parseFitnessGoal(it) })

        // Update PT style
        try {
            profile.ptStyle = PTStyle.valueOf(request.ptStyle)
        } catch (e: Exception) {
            profile.ptStyle = PTStyle.GAME_MASTER
        }

        profile.notificationEnabled = request.notificationEnabled
        profile.updatedAt = AppTime.utcNow()

        // Create or update settings
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(
            UserSettings(user = user)
        )

        settings.workoutReminder = request.notificationEnabled
        settings.weeklyWorkoutDays = request.weeklyWorkoutDays
        settings.workoutSplit = request.workoutSplit
        settings.preferredWorkoutTime = request.preferredWorkoutTime
        settings.workoutDuration = request.workoutDuration
        request.timeZone?.let { settings.timeZone = validateTimeZone(it) }

        request.availableEquipment?.let {
            settings.availableEquipment.clear()
            settings.availableEquipment.addAll(it)
        }

        request.injuries?.let {
            settings.injuries.clear()
            settings.injuries.addAll(it)
        }

        settings.updatedAt = AppTime.utcNow()

        userRepository.save(user)
        userProfileRepository.save(profile)
        userSettingsRepository.save(settings)

        return mapToProfileResponse(user, profile)
    }

    @Transactional(readOnly = true)
    fun getSettings(userId: Long): UserSettingsResponse {
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(
            UserSettings(
                user = userRepository.findById(userId)
                    .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }
            )
        )

        return UserSettingsResponse(
            notifications = NotificationSettings(
                workoutReminder = settings.workoutReminder,
                aiMessages = settings.aiMessages,
                achievements = settings.achievements,
                marketing = settings.marketing
            ),
            privacy = PrivacySettings(
                shareProgress = settings.shareProgress,
                publicProfile = settings.publicProfile
            ),
            app = AppSettings(
                theme = settings.theme,
                language = settings.language,
                timeZone = settings.timeZone,
                units = settings.units
            )
        )
    }

    fun updateSettings(userId: Long, request: UpdateSettingsRequest): UserSettingsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = userSettingsRepository.findByUser_Id(userId).orElse(
            UserSettings(user = user)
        )
        val previousTimeZone = settings.timeZone

        request.notifications?.let {
            settings.workoutReminder = it.workoutReminder
            settings.aiMessages = it.aiMessages
            settings.achievements = it.achievements
            settings.marketing = it.marketing
        }

        request.privacy?.let {
            settings.shareProgress = it.shareProgress
            settings.publicProfile = it.publicProfile
        }

        request.app?.let {
            settings.theme = it.theme
            settings.language = it.language
            settings.timeZone = validateTimeZone(it.timeZone)
            settings.units = it.units
        }

        settings.updatedAt = AppTime.utcNow()
        userSettingsRepository.save(settings)

        if (previousTimeZone != settings.timeZone) {
            notificationService.refreshScheduleTimesForUser(userId)
        }

        return getSettings(userId)
    }

    fun updateWorkoutProgram(userId: Long, newProgram: String, newDaysPerWeek: Int) {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        profile.workoutSplit = newProgram
        profile.weeklyWorkoutDays = newDaysPerWeek
        profile.updatedAt = AppTime.utcNow()

        userProfileRepository.save(profile)
    }

    // V4 API methods
    @Transactional(readOnly = true)
    fun getProfileV4(userId: Long): ProfileResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        return mapToProfileResponseV4(profile)
    }

    fun createProfile(userId: Long, request: ProfileRequest): ProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Check if profile already exists
        userProfileRepository.findByUser_Id(userId).ifPresent {
            throw IllegalArgumentException("프로필이 이미 존재합니다")
        }

        // Update user's nickname
        user.nickname = request.nickname
        userRepository.save(user)

        val profile = UserProfile(
            user = user,
            age = request.age,
            gender = request.gender,
            bodyInfo = if (request.height != null || request.weight != null) {
                BodyInfo(
                    height = request.height,
                    weight = request.weight,
                    bodyFat = null,
                    muscleMass = null
                )
            } else null,
            goals = request.goals.mapNotNull { parseFitnessGoal(it) }.toMutableSet(),
            ptStyle = try { PTStyle.valueOf(request.ptStyle.uppercase()) } catch (e: Exception) { PTStyle.GAME_MASTER },
            experienceLevel = try { ExperienceLevel.valueOf(request.experienceLevel.uppercase()) } catch (e: Exception) { ExperienceLevel.BEGINNER },
            notificationEnabled = request.notificationEnabled,
            weeklyWorkoutDays = request.weeklyWorkoutDays,
            workoutSplit = request.workoutSplit,
            availableEquipment = request.availableEquipment.toMutableSet(),
            preferredWorkoutTime = request.preferredWorkoutTime,
            workoutDuration = request.workoutDuration,
            injuries = request.injuries.toMutableSet()
        )

        val savedProfile = userProfileRepository.save(profile)

        // Update user nickname
        user.nickname = request.nickname
        userRepository.save(user)

        return mapToProfileResponseV4(savedProfile)
    }

    @Transactional(propagation = Propagation.REQUIRED)
    fun updateProfileV4(userId: Long, request: ProfileUpdateRequest): ProfileResponse {
        logger.debug("Starting profile update for userId: $userId")
        logger.debug("Request: $request")

        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        logger.debug("Current profile before update - height: ${profile.bodyInfo?.height}, weight: ${profile.bodyInfo?.weight}")

        // Update nickname in User entity if provided
        request.nickname?.let {
            val user = userRepository.findById(userId)
                .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }
            user.nickname = it
            userRepository.save(user)
        }

        // Direct field updates
        request.age?.let { profile.age = it }
        request.gender?.let { profile.gender = it }

        request.experienceLevel?.let {
            profile.experienceLevel = try {
                ExperienceLevel.valueOf(it.uppercase())
            } catch (e: Exception) {
                profile.experienceLevel
            }
        }

        // Update body info
        if (request.height != null || request.weight != null) {
            val currentBodyInfo = profile.bodyInfo ?: BodyInfo()
            profile.bodyInfo = BodyInfo(
                height = request.height ?: currentBodyInfo.height,
                weight = request.weight ?: currentBodyInfo.weight,
                bodyFat = currentBodyInfo.bodyFat,
                muscleMass = currentBodyInfo.muscleMass
            )
        }

        // Update goals
        request.goals?.let { goals ->
            profile.goals.clear()
            profile.goals.addAll(goals.mapNotNull { parseFitnessGoal(it) })
        }

        request.ptStyle?.let {
            profile.ptStyle = try {
                PTStyle.valueOf(it.uppercase())
            } catch (e: Exception) {
                profile.ptStyle
            }
        }

        request.notificationEnabled?.let { profile.notificationEnabled = it }
        request.weeklyWorkoutDays?.let { profile.weeklyWorkoutDays = it }
        request.workoutSplit?.let { profile.workoutSplit = it }

        request.availableEquipment?.let {
            profile.availableEquipment.clear()
            profile.availableEquipment.addAll(it)
        }

        request.preferredWorkoutTime?.let { profile.preferredWorkoutTime = it }
        request.workoutDuration?.let { profile.workoutDuration = it }

        request.injuries?.let {
            profile.injuries.clear()
            profile.injuries.addAll(it)
        }

        profile.updatedAt = AppTime.utcNow()

        logger.debug("Profile after update - height: ${profile.bodyInfo?.height}, weight: ${profile.bodyInfo?.weight}")
        logger.debug("Updated profile - updatedAt: ${profile.updatedAt}")

        // Explicitly save the profile
        val savedProfile = userProfileRepository.saveAndFlush(profile)

        logger.debug("Save and flush completed")

        // Clear the persistence context to force re-fetch
        entityManager.clear()

        // Verify the update was persisted
        val verifyProfile = userProfileRepository.findById(savedProfile.id)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }
        logger.debug("Verified profile after save - height: ${verifyProfile.bodyInfo?.height}, weight: ${verifyProfile.bodyInfo?.weight}")

        return mapToProfileResponseV4(verifyProfile)
    }

    fun updateLastWorkout(userId: Long, muscleGroups: List<String>) {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        profile.lastWorkoutDate = AppTime.utcNow()

        // Update muscle recovery JSON
        val recoveryMap = profile.muscleRecovery?.let {
            objectMapper.readValue(it, Map::class.java) as MutableMap<String, String>
        } ?: mutableMapOf()

        val nowString = AppTime.utcNow().toString()
        muscleGroups.forEach { muscle ->
            recoveryMap[muscle] = nowString
        }

        profile.muscleRecovery = objectMapper.writeValueAsString(recoveryMap)
        profile.updatedAt = AppTime.utcNow()

        userProfileRepository.save(profile)
    }

    fun updateStrengthData(
        userId: Long,
        estimatedMaxes: Map<String, Double>? = null,
        workingWeights: Map<String, Double>? = null,
        strengthLevel: String? = null
    ) {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        estimatedMaxes?.let {
            profile.estimatedMaxes = objectMapper.writeValueAsString(it)
        }

        workingWeights?.let {
            profile.workingWeights = objectMapper.writeValueAsString(it)
        }

        strengthLevel?.let {
            profile.strengthLevel = it
        }

        profile.strengthTestCompleted = true
        profile.updatedAt = AppTime.utcNow()

        userProfileRepository.save(profile)
    }

    /**
     * 회원 탈퇴 (하드 삭제 - 모든 연관 데이터 완전 삭제)
     */
    fun deleteAccount(userId: Long): Map<String, Any> {
        userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        logger.info("회원 탈퇴 시작 - userId: {}", userId)
        entityManager.flush()
        entityManager.clear()
        deleteAllUserData(userId)
        logger.info("회원 탈퇴 완료 - userId: {}", userId)

        return mapOf(
            "success" to true,
            "message" to "Account deleted successfully"
        )
    }

    private fun deleteAllUserData(userId: Long) {
        // ElementCollection 테이블 (최하위 leaf 노드)
        executeDelete("DELETE FROM notification_data WHERE notification_history_id IN (SELECT id FROM notification_history WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM notification_schedule_days WHERE schedule_id IN (SELECT id FROM notification_schedules WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_goals WHERE profile_id IN (SELECT id FROM user_profiles WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_equipment WHERE profile_id IN (SELECT id FROM user_profiles WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_profile_injuries WHERE profile_id IN (SELECT id FROM user_profiles WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_available_equipment WHERE settings_id IN (SELECT id FROM user_settings WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_settings_injuries WHERE settings_id IN (SELECT id FROM user_settings WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM recovery_activity_body_parts WHERE activity_id IN (SELECT id FROM recovery_activities WHERE user_id = :userId)", userId)

        // 알림 관련
        executeDelete("DELETE FROM notification_history WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM notification_schedules WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM notification_devices WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM notification_settings WHERE user_id = :userId", userId)

        // 프로필/설정
        executeDelete("DELETE FROM user_profiles WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM user_settings WHERE user_id = :userId", userId)

        // 채팅
        executeDelete("DELETE FROM chat_messages WHERE user_id = :userId", userId)

        // 회복
        executeDelete("DELETE FROM recovery_activities WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM muscle_recovery WHERE user_id = :userId", userId)

        // 영양
        executeDelete("DELETE FROM meal_logs WHERE user_id = :userId", userId)

        // 기록/업적/스트릭
        executeDelete("DELETE FROM personal_records WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM achievements WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM workout_streaks WHERE user_id = :userId", userId)

        // 공유 운동 (workout_sessions 참조)
        executeDelete("DELETE FROM shared_workouts WHERE user_id = :userId", userId)

        // 운동 세션 체인 (exercise_sets → workout_exercises → workout_logs → workout_sessions)
        executeDelete("DELETE FROM exercise_sets WHERE workout_exercise_id IN (SELECT we.id FROM workout_exercises we JOIN workout_sessions ws ON we.session_id = ws.id WHERE ws.user_id = :userId)", userId)
        executeDelete("DELETE FROM workout_exercises WHERE session_id IN (SELECT id FROM workout_sessions WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM workout_logs WHERE session_id IN (SELECT id FROM workout_sessions WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM workout_sessions WHERE user_id = :userId", userId)

        // 운동 플랜 체인 (user_plan_day_exercises → user_plan_days → user_workout_plans)
        executeDelete("DELETE FROM user_plan_day_exercises WHERE plan_day_id IN (SELECT upd.id FROM user_plan_days upd JOIN user_workout_plans uwp ON upd.plan_id = uwp.id WHERE uwp.user_id = :userId)", userId)
        executeDelete("DELETE FROM user_plan_days WHERE plan_id IN (SELECT id FROM user_workout_plans WHERE user_id = :userId)", userId)
        executeDelete("DELETE FROM user_workout_plans WHERE user_id = :userId", userId)

        // 프로그램 등록
        executeDelete("DELETE FROM user_program_enrollments WHERE user_id = :userId", userId)

        // 구독/결제 (payment_history → subscriptions)
        executeDelete("DELETE FROM payment_history WHERE user_id = :userId", userId)
        executeDelete("DELETE FROM subscriptions WHERE user_id = :userId", userId)

        // 디바이스 세션
        executeDelete("DELETE FROM device_sessions WHERE user_id = :userId", userId)

        // 운동 플랜 템플릿 (owner_user_id는 nullable FK → NULL 처리)
        entityManager.createNativeQuery("UPDATE workout_plan_templates SET owner_user_id = NULL WHERE owner_user_id = :userId")
            .setParameter("userId", userId)
            .executeUpdate()

        // 최종: 사용자 삭제
        executeDelete("DELETE FROM users WHERE id = :userId", userId)
    }

    private fun executeDelete(sql: String, userId: Long) {
        entityManager.createNativeQuery(sql)
            .setParameter("userId", userId)
            .executeUpdate()
    }


    private fun mapToProfileResponse(user: com.richjun.liftupai.domain.auth.entity.User, profile: UserProfile): UserProfileResponse {
        return UserProfileResponse(
            userId = user.id,
            email = user.email ?: "",
            nickname = user.nickname,
            experienceLevel = profile.experienceLevel.name,
            joinDate = AppTime.formatUtcRequired(user.joinDate),
            bodyInfo = profile.bodyInfo?.let {
                BodyInfoDto(
                    height = it.height,
                    weight = it.weight,
                    bodyFat = it.bodyFat,
                    muscleMass = it.muscleMass,
                    age = null,
                    gender = null
                )
            },
            goals = profile.goals.map { it.name },
            ptStyle = profile.ptStyle.name,
            subscription = SubscriptionDto()
        )
    }

    private fun validateTimeZone(rawTimeZone: String): String {
        return AppTime.requireZoneId(rawTimeZone).id
    }

    private fun mapToProfileResponseV4(profile: UserProfile): ProfileResponse {
        val muscleRecovery = profile.muscleRecovery?.let {
            try {
                objectMapper.readValue(it, Map::class.java) as Map<String, String>
            } catch (e: Exception) {
                null
            }
        }

        val estimatedMaxes = profile.estimatedMaxes?.let {
            try {
                objectMapper.readValue(it, Map::class.java) as Map<String, Double>
            } catch (e: Exception) {
                null
            }
        }

        val workingWeights = profile.workingWeights?.let {
            try {
                objectMapper.readValue(it, Map::class.java) as Map<String, Double>
            } catch (e: Exception) {
                null
            }
        }

        return ProfileResponse(
            id = profile.user.id.toString(),
            nickname = profile.user.nickname,
            experienceLevel = profile.experienceLevel.name.lowercase(),
            goals = profile.goals.map { it.name.lowercase() },
            height = profile.bodyInfo?.height,
            weight = profile.bodyInfo?.weight,
            age = profile.age,
            gender = profile.gender,
            ptStyle = profile.ptStyle.name.lowercase(),
            notificationEnabled = profile.notificationEnabled,
            weeklyWorkoutDays = profile.weeklyWorkoutDays ?: 3,
            workoutSplit = profile.workoutSplit ?: "full_body",
            availableEquipment = profile.availableEquipment.toList(),
            preferredWorkoutTime = profile.preferredWorkoutTime ?: "evening",
            workoutDuration = profile.workoutDuration ?: 60,
            injuries = profile.injuries.toList(),
            currentProgram = profile.currentProgram,
            currentWeek = profile.currentWeek,
            lastWorkoutDate = profile.lastWorkoutDate,
            muscleRecovery = muscleRecovery,
            strengthTestCompleted = profile.strengthTestCompleted,
            estimatedMaxes = estimatedMaxes,
            workingWeights = workingWeights,
            strengthLevel = profile.strengthLevel,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt
        )
    }
}

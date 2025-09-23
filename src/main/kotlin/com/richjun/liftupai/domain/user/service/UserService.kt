package com.richjun.liftupai.domain.user.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.entity.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val objectMapper: ObjectMapper
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
            profile.goals.addAll(goals.mapNotNull {
                try { FitnessGoal.valueOf(it) } catch (e: Exception) { null }
            })
        }

        // Update PT style
        request.ptStyle?.let { ptStyle ->
            try {
                profile.ptStyle = PTStyle.valueOf(ptStyle)
            } catch (e: Exception) {
                // Invalid PT style, ignore
            }
        }

        profile.updatedAt = LocalDateTime.now()
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
        profile.goals.addAll(request.goals.mapNotNull {
            try { FitnessGoal.valueOf(it) } catch (e: Exception) { null }
        })

        // Update PT style
        try {
            profile.ptStyle = PTStyle.valueOf(request.ptStyle)
        } catch (e: Exception) {
            profile.ptStyle = PTStyle.GAME_MASTER
        }

        profile.notificationEnabled = request.notificationEnabled
        profile.updatedAt = LocalDateTime.now()

        // Create or update settings
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(
            UserSettings(user = user)
        )

        settings.workoutReminder = request.notificationEnabled
        settings.weeklyWorkoutDays = request.weeklyWorkoutDays
        settings.workoutSplit = request.workoutSplit
        settings.preferredWorkoutTime = request.preferredWorkoutTime
        settings.workoutDuration = request.workoutDuration

        request.availableEquipment?.let {
            settings.availableEquipment.clear()
            settings.availableEquipment.addAll(it)
        }

        request.injuries?.let {
            settings.injuries.clear()
            settings.injuries.addAll(it)
        }

        settings.updatedAt = LocalDateTime.now()

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
            settings.units = it.units
        }

        settings.updatedAt = LocalDateTime.now()
        userSettingsRepository.save(settings)

        return getSettings(userId)
    }

    fun updateWorkoutProgram(userId: Long, newProgram: String, newDaysPerWeek: Int) {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        profile.workoutSplit = newProgram
        profile.weeklyWorkoutDays = newDaysPerWeek
        profile.updatedAt = LocalDateTime.now()

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
            goals = request.goals.mapNotNull {
                try { FitnessGoal.valueOf(it.uppercase()) } catch (e: Exception) { null }
            }.toMutableSet(),
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
            profile.goals.addAll(goals.mapNotNull {
                try { FitnessGoal.valueOf(it.uppercase()) } catch (e: Exception) { null }
            })
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

        profile.updatedAt = LocalDateTime.now()

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

        profile.lastWorkoutDate = LocalDateTime.now()

        // Update muscle recovery JSON
        val recoveryMap = profile.muscleRecovery?.let {
            objectMapper.readValue(it, Map::class.java) as MutableMap<String, String>
        } ?: mutableMapOf()

        val nowString = LocalDateTime.now().toString()
        muscleGroups.forEach { muscle ->
            recoveryMap[muscle] = nowString
        }

        profile.muscleRecovery = objectMapper.writeValueAsString(recoveryMap)
        profile.updatedAt = LocalDateTime.now()

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
        profile.updatedAt = LocalDateTime.now()

        userProfileRepository.save(profile)
    }

    /**
     * 회원 탈퇴 (소프트 삭제)
     */
    fun deactivateAccount(userId: Long): Map<String, Any> {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 이미 탈퇴한 사용자인지 확인
        if (!user.isActive) {
            throw IllegalStateException("이미 탈퇴 처리된 계정입니다")
        }

        // 사용자 비활성화
        user.isActive = false
        user.deletedAt = LocalDateTime.now()
        user.refreshToken = null  // 리프레시 토큰 제거

        userRepository.save(user)

        return mapOf(
            "success" to true,
            "message" to "회원 탈퇴가 완료되었습니다",
            "deactivatedAt" to user.deletedAt.toString()
        )
    }


    private fun mapToProfileResponse(user: com.richjun.liftupai.domain.auth.entity.User, profile: UserProfile): UserProfileResponse {
        return UserProfileResponse(
            userId = user.id,
            email = user.email ?: "",
            nickname = user.nickname,
            experienceLevel = profile.experienceLevel.name,
            joinDate = user.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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
package com.richjun.liftupai.domain.auth.service

import com.richjun.liftupai.domain.auth.dto.*
import com.richjun.liftupai.domain.auth.entity.*
import com.richjun.liftupai.domain.user.entity.*
import com.richjun.liftupai.domain.user.dto.BodyInfoDto
import com.richjun.liftupai.domain.user.dto.SubscriptionDto
import com.richjun.liftupai.global.exception.DuplicateResourceException
import com.richjun.liftupai.global.exception.InvalidCredentialsException
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.auth.repository.DeviceSessionRepository
import com.richjun.liftupai.global.security.JwtTokenProvider
import com.richjun.liftupai.global.util.ValidationUtil
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.fasterxml.jackson.databind.ObjectMapper

@Service
@Transactional
class AuthService(
    private val userRepository: UserRepository,
    private val deviceSessionRepository: DeviceSessionRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val validationUtil: ValidationUtil,
    private val objectMapper: ObjectMapper
) {

    fun register(request: RegisterRequest): AuthResponse {
        // 이메일 유효성 검증
        if (!validationUtil.isValidEmail(request.email)) {
            throw InvalidCredentialsException("유효하지 않은 이메일 형식입니다")
        }

        // 디바이스 계정 여부 확인
        val isDeviceAccount = validationUtil.isDeviceAccount(request.email)

        // 디바이스 계정인 경우 디바이스 ID 검증
        if (isDeviceAccount) {
            val deviceId = validationUtil.extractDeviceIdFromEmail(request.email)
            if (!validationUtil.isValidDeviceId(deviceId)) {
                throw InvalidCredentialsException("유효하지 않은 디바이스 ID입니다")
            }
        }

        // 이메일 중복 확인
        if (userRepository.existsByEmail(request.email)) {
            throw DuplicateResourceException("이미 사용 중인 이메일입니다")
        }

        // 새 사용자 생성
        val user = User(
            email = request.email,
            password = passwordEncoder.encode(request.password),
            nickname = request.nickname,
            isDeviceAccount = isDeviceAccount,
            emailVerified = isDeviceAccount // 디바이스 계정은 자동 인증
        )

        // 디바이스 계정인 경우 디바이스 정보 저장
        if (isDeviceAccount) {
            val deviceId = validationUtil.extractDeviceIdFromEmail(request.email)
            user.deviceId = deviceId
            if (request.deviceInfo != null) {
                user.deviceInfo = objectMapper.writeValueAsString(request.deviceInfo)
            }
        }

        // 기본 프로필 생성
        val profile = UserProfile(
            user = user,
            bodyInfo = null,
            goals = mutableSetOf(),
            ptStyle = PTStyle.GAME_MASTER,
            experienceLevel = ExperienceLevel.BEGINNER
        )
        user.profile = profile

        val savedUser = userRepository.save(user)

        // JWT 토큰 생성
        val accessToken = jwtTokenProvider.generateAccessToken(savedUser.id, savedUser.email ?: savedUser.deviceId ?: "")
        val refreshToken = jwtTokenProvider.generateRefreshToken(savedUser.id, savedUser.email ?: savedUser.deviceId ?: "")

        // Refresh token 저장
        savedUser.refreshToken = refreshToken
        userRepository.save(savedUser)

        return AuthResponse(
            userId = savedUser.id,
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmailWithProfile(request.email)
            .orElseThrow { InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다") }

        // 디바이스 계정인 경우 디바이스 세션 업데이트
        if (user.isDeviceAccount && user.deviceId != null) {
            updateDeviceSession(user, request.deviceInfo)
        }

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        }

        if (!user.isActive) {
            throw InvalidCredentialsException("비활성화된 계정입니다")
        }

        // 마지막 로그인 시간 업데이트
        user.lastLoginAt = LocalDateTime.now()

        // JWT 토큰 생성
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.email ?: user.deviceId ?: "")
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.email ?: user.deviceId ?: "")

        // Refresh token 저장
        user.refreshToken = refreshToken
        userRepository.save(user)

        val profileDto = user.profile?.let { profile ->
            UserProfileDto(
                userId = user.id,
                email = user.email ?: "",
                nickname = user.nickname,
                level = profile.experienceLevel.name,
                joinDate = user.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                goals = profile.goals.map { it.name },
                ptStyle = profile.ptStyle.name,
                subscription = SubscriptionDto(),
                isDeviceAccount = user.isDeviceAccount,
                deviceRegisteredAt = if (user.isDeviceAccount) user.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) else null
            )
        }

        return AuthResponse(
            userId = user.id,
            accessToken = accessToken,
            refreshToken = refreshToken,
            profile = profileDto
        )
    }

    fun refreshToken(request: RefreshTokenRequest): AuthResponse {
        if (!jwtTokenProvider.validateToken(request.refreshToken)) {
            throw InvalidCredentialsException("유효하지 않은 refresh token입니다")
        }

        val user = userRepository.findByRefreshToken(request.refreshToken)
            .orElseThrow { InvalidCredentialsException("유효하지 않은 refresh token입니다") }

        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.email ?: user.deviceId ?: "")
        val newRefreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.email ?: user.deviceId ?: "")

        user.refreshToken = newRefreshToken
        userRepository.save(user)

        return AuthResponse(
            userId = user.id,
            accessToken = accessToken,
            refreshToken = newRefreshToken
        )
    }

    fun logout(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        user.refreshToken = null
        userRepository.save(user)
    }

    fun checkNickname(nickname: String): Boolean {
        return !userRepository.existsByNickname(nickname)
    }

    fun checkExistingDevice(deviceId: String): Boolean {
        return userRepository.existsByDeviceId(deviceId)
    }

    fun registerDevice(request: DeviceRegisterRequest): DeviceAuthResponse {
        // 디바이스 ID 유효성 검증
        if (!validationUtil.isValidDeviceId(request.deviceId)) {
            throw IllegalArgumentException("유효하지 않은 디바이스 ID입니다")
        }

        // 디바이스 ID로 기존 사용자 확인
        if (userRepository.existsByDeviceId(request.deviceId)) {
            throw DuplicateResourceException("이미 등록된 디바이스입니다")
        }

        // 새 사용자 생성 (이메일/비밀번호 없음)
        val user = User(
            email = null,
            password = null,
            nickname = request.nickname,
            deviceId = request.deviceId,
            deviceInfo = request.deviceInfo?.let { objectMapper.writeValueAsString(it) },
            isDeviceAccount = true,
            emailVerified = true // 디바이스 계정은 자동 인증
        )

        // Experience Level 설정
        user.level = try {
            UserLevel.valueOf(request.experienceLevel.uppercase())
        } catch (e: Exception) {
            UserLevel.BEGINNER
        }

        // 프로필 생성
        val profile = UserProfile(
            user = user,
            bodyInfo = request.bodyInfo?.let {
                BodyInfo(
                    height = it.height ?: 0.0,
                    weight = it.weight ?: 0.0,
                    bodyFat = it.bodyFat,
                    muscleMass = it.muscleMass
                )
            },
            goals = mutableSetOf(),
            ptStyle = try {
                PTStyle.valueOf(request.ptStyle.uppercase())
            } catch (e: Exception) {
                PTStyle.GAME_MASTER
            },
            experienceLevel = try {
                ExperienceLevel.valueOf(request.experienceLevel.uppercase())
            } catch (e: Exception) {
                ExperienceLevel.BEGINNER
            }
        )

        // Workout preferences 설정
        request.workoutPreferences?.let { prefs ->
            profile.weeklyWorkoutDays = prefs.weeklyDays
            profile.workoutSplit = prefs.workoutSplit
            profile.preferredWorkoutTime = prefs.preferredWorkoutTime ?: "evening"
            profile.workoutDuration = prefs.workoutDuration
            profile.availableEquipment.clear()
            profile.availableEquipment.addAll(prefs.availableEquipment)
        }

        // Goals 설정
        request.goals.forEach { goal ->
            try {
                when (goal.uppercase()) {
                    "MUSCLE_GAIN" -> profile.goals.add(FitnessGoal.MUSCLE_GAIN)
                    "FAT_LOSS", "WEIGHT_LOSS" -> profile.goals.add(FitnessGoal.WEIGHT_LOSS)
                    "STRENGTH" -> profile.goals.add(FitnessGoal.STRENGTH)
                    "ENDURANCE" -> profile.goals.add(FitnessGoal.ENDURANCE)
                    "GENERAL_FITNESS" -> profile.goals.add(FitnessGoal.GENERAL_FITNESS)
                    "ATHLETIC_PERFORMANCE" -> profile.goals.add(FitnessGoal.ATHLETIC_PERFORMANCE)
                    else -> profile.goals.add(FitnessGoal.GENERAL_FITNESS)
                }
            } catch (e: Exception) {
                profile.goals.add(FitnessGoal.GENERAL_FITNESS)
            }
        }

        // Body info 추가 설정
        request.bodyInfo?.let { bodyInfo ->
            profile.age = bodyInfo.age
            profile.gender = bodyInfo.gender ?: "unknown"
        }

        user.profile = profile

        val savedUser = userRepository.save(user)

        // 디바이스 세션 생성
        val deviceSession = DeviceSession(
            user = savedUser,
            deviceId = request.deviceId,
            deviceInfo = request.deviceInfo?.let { objectMapper.writeValueAsString(it) }
        )
        deviceSessionRepository.save(deviceSession)

        // JWT 토큰 생성
        val accessToken = jwtTokenProvider.generateAccessToken(savedUser.id, savedUser.deviceId ?: "")
        val refreshToken = jwtTokenProvider.generateRefreshToken(savedUser.id, savedUser.deviceId ?: "")

        // Refresh token 저장
        savedUser.refreshToken = refreshToken
        userRepository.save(savedUser)

        return DeviceAuthResponse(
            user = DeviceUserDto(
                id = savedUser.id,
                deviceId = savedUser.deviceId!!,
                nickname = savedUser.nickname,
                isDeviceAccount = true,
                deviceRegisteredAt = savedUser.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    fun loginDevice(request: DeviceLoginRequest): DeviceAuthResponse {
        // 디바이스 ID로 사용자 조회
        val user = userRepository.findByDeviceIdWithProfile(request.deviceId)
            .orElseThrow { ResourceNotFoundException("등록되지 않은 디바이스입니다. 회원가입이 필요합니다.") }

        if (!user.isActive) {
            throw InvalidCredentialsException("비활성화된 계정입니다")
        }

        // 마지막 로그인 시간 업데이트
        user.lastLoginAt = LocalDateTime.now()

        // JWT 토큰 생성
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.deviceId ?: "")
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.deviceId ?: "")

        // Refresh token 저장
        user.refreshToken = refreshToken
        userRepository.save(user)

        // 디바이스 세션 업데이트
        updateDeviceSession(user, null)

        return DeviceAuthResponse(
            user = DeviceUserDto(
                id = user.id,
                deviceId = user.deviceId!!,
                nickname = user.nickname,
                isDeviceAccount = user.isDeviceAccount,
                deviceRegisteredAt = user.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    private fun updateDeviceSession(user: User, deviceInfo: Any?) {
        val deviceId = user.deviceId ?: return

        val existingSession = deviceSessionRepository.findByUserAndDeviceId(user, deviceId)

        if (existingSession.isPresent) {
            val session = existingSession.get()
            session.isActive = true
            session.updatedAt = LocalDateTime.now()
            // Note: deviceInfo is immutable in data class, so we can't update it
            deviceSessionRepository.save(session)
        } else {
            val newSession = DeviceSession(
                user = user,
                deviceId = deviceId,
                deviceInfo = deviceInfo?.let { objectMapper.writeValueAsString(it) }
            )
            deviceSessionRepository.save(newSession)
        }
    }

    fun reactivateAccount(email: String, password: String): Map<String, Any> {
        val user = userRepository.findByEmail(email)
            .orElseThrow { InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다") }

        // 비밀번호 확인
        if (!passwordEncoder.matches(password, user.password)) {
            throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        }

        // 활성 계정인지 확인
        if (user.isActive) {
            throw IllegalStateException("이미 활성화된 계정입니다")
        }

        // 탈퇴 후 30일 경과 확인
        user.deletedAt?.let { deletedAt ->
            val daysSinceDeleted = java.time.Duration.between(deletedAt, LocalDateTime.now()).toDays()
            if (daysSinceDeleted > 30) {
                throw IllegalStateException("탈퇴 후 30일이 경과하여 복구할 수 없습니다")
            }
        }

        // 사용자 재활성화
        user.isActive = true
        user.deletedAt = null

        userRepository.save(user)

        return mapOf(
            "success" to true,
            "message" to "계정이 재활성화되었습니다"
        )
    }

    private fun mapToProfileDto(user: User, profile: UserProfile): UserProfileDto {
        return UserProfileDto(
            userId = user.id,
            email = user.email ?: "",
            nickname = user.nickname,
            level = profile.experienceLevel.name,
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
            subscription = SubscriptionDto(),  // 프로토타입이므로 기본값 사용
            isDeviceAccount = user.isDeviceAccount,
            deviceRegisteredAt = if (user.isDeviceAccount) user.joinDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) else null
        )
    }
}
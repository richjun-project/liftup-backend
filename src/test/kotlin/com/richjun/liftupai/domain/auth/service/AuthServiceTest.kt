package com.richjun.liftupai.domain.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.dto.LoginRequest
import com.richjun.liftupai.domain.auth.dto.RefreshTokenRequest
import com.richjun.liftupai.domain.auth.dto.RegisterRequest
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.DeviceSessionRepository
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.InvalidCredentialsException
import com.richjun.liftupai.global.security.JwtTokenProvider
import com.richjun.liftupai.global.util.ValidationUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

/**
 * AuthService 크리티컬 경로 테스트: 로그인 실패, 리프레시 토큰 만료
 */
class AuthServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val deviceSessionRepository = mockk<DeviceSessionRepository>(relaxed = true)
    private val userProfileRepository = mockk<UserProfileRepository>(relaxed = true)
    private val userSettingsRepository = mockk<UserSettingsRepository>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()
    private val validationUtil = ValidationUtil()
    private val objectMapper = ObjectMapper()

    private val service = AuthService(
        userRepository = userRepository,
        deviceSessionRepository = deviceSessionRepository,
        userProfileRepository = userProfileRepository,
        userSettingsRepository = userSettingsRepository,
        passwordEncoder = passwordEncoder,
        jwtTokenProvider = jwtTokenProvider,
        validationUtil = validationUtil,
        objectMapper = objectMapper
    )

    @Test
    fun `비밀번호가 틀리면 InvalidCredentialsException이 발생한다`() {
        val user = User(
            id = 1L,
            email = "user@example.com",
            password = "encoded-correct-password",
            nickname = "tester"
        )

        every { userRepository.findByEmailWithProfile("user@example.com") } returns Optional.of(user)
        every { passwordEncoder.matches("wrong-password", "encoded-correct-password") } returns false

        val request = LoginRequest(email = "user@example.com", password = "wrong-password")

        assertThrows(InvalidCredentialsException::class.java) {
            service.login(request)
        }
    }

    @Test
    fun `존재하지 않는 이메일로 로그인하면 InvalidCredentialsException이 발생한다`() {
        every { userRepository.findByEmailWithProfile("nobody@example.com") } returns Optional.empty()

        val request = LoginRequest(email = "nobody@example.com", password = "whatever")

        assertThrows(InvalidCredentialsException::class.java) {
            service.login(request)
        }
    }

    @Test
    fun `만료되었거나 유효하지 않은 refresh token은 InvalidCredentialsException이 발생한다`() {
        every { jwtTokenProvider.validateToken("expired-refresh-token") } returns false

        val request = RefreshTokenRequest(refreshToken = "expired-refresh-token")

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(request)
        }

        // 토큰 자체가 유효하지 않으면 DB 조회조차 시도하지 않아야 한다
        io.mockk.verify(exactly = 0) { userRepository.findByRefreshToken(any()) }
    }

    @Test
    fun `refresh token 형식은 유효하지만 저장된 사용자가 없으면 InvalidCredentialsException이 발생한다`() {
        every { jwtTokenProvider.validateToken("stale-refresh-token") } returns true
        every { userRepository.findByRefreshToken("stale-refresh-token") } returns Optional.empty()

        val request = RefreshTokenRequest(refreshToken = "stale-refresh-token")

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(request)
        }
    }

    @Test
    fun `닉네임 없이 이메일 가입하면 이메일 앞부분을 닉네임으로 쓴다`() {
        val saved = mutableListOf<User>()
        every { userRepository.existsByEmail("hana.sato@example.jp") } returns false
        every { passwordEncoder.encode("secret1") } returns "encoded"
        every { userRepository.save(capture(saved)) } answers { firstArg() }
        every { jwtTokenProvider.generateAccessToken(any(), any()) } returns "access"
        every { jwtTokenProvider.generateRefreshToken(any(), any()) } returns "refresh"

        service.register(RegisterRequest(email = "hana.sato@example.jp", password = "secret1", nickname = ""))

        assertEquals("hana.sato", saved.first().nickname)
    }
}

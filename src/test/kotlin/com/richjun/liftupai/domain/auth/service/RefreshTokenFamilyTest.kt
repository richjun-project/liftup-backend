package com.richjun.liftupai.domain.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.dto.RefreshTokenRequest
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.DeviceSessionRepository
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.InvalidCredentialsException
import com.richjun.liftupai.global.security.JwtTokenProvider
import com.richjun.liftupai.global.util.ValidationUtil
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Optional

/**
 * 2026-11-22 운영: 갱신 응답을 못 받은 앱이 직전 리프레시 토큰으로 다시 오면 401 → 앱이 로그아웃했다
 * (339: 11-18 갱신 200 뒤 요청 없음 → 11-19 알림 2.4초 뒤 백그라운드 실행에서 갱신 401). 진짜 JWT 로 규칙을 지킨다.
 */
class RefreshTokenFamilyTest {

    private val secret = "s".repeat(64)
    private val provider = JwtTokenProvider(secret, 3_600_000L, 2_592_000_000L)
    private val userRepository = mockk<UserRepository>()
    private val service = AuthService(
        userRepository = userRepository,
        deviceSessionRepository = mockk<DeviceSessionRepository>(relaxed = true),
        userProfileRepository = mockk<UserProfileRepository>(relaxed = true),
        userSettingsRepository = mockk<UserSettingsRepository>(relaxed = true),
        passwordEncoder = mockk<PasswordEncoder>(),
        jwtTokenProvider = provider,
        validationUtil = ValidationUtil(),
        objectMapper = ObjectMapper(),
    )

    /** 발급 시각을 정해 만든 토큰(수명 기본 30일 = 리프레시). */
    private fun token(userId: Long, minutesAgo: Long, lifetimeMs: Long = 2_592_000_000L): String {
        val iat = Date(System.currentTimeMillis() - minutesAgo * 60_000)
        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("identifier", "u$userId")
            .setIssuedAt(iat)
            .setExpiration(Date(iat.time + lifetimeMs))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()), SignatureAlgorithm.HS512)
            .compact()
    }

    private fun iatOf(t: String): LocalDateTime = provider.parseRefreshToken(t)!!.issuedAt

    private fun user(id: Long = 7L) = User(id = id, email = "u$id@example.com", nickname = "u$id")

    private fun stub(user: User) {
        every { userRepository.findByRefreshToken(any()) } answers {
            Optional.ofNullable(user.takeIf { it.refreshToken == firstArg<String>() })
        }
        every { userRepository.findById(user.id) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `갱신 응답을 못 받은 앱이 직전 토큰으로 다시 와도 새 토큰을 준다`() {
        val u = user()
        val t0 = token(u.id, minutesAgo = 600)
        u.refreshToken = t0
        stub(u)

        // 1) 정상 갱신 — 서버는 t1 로 바꿨지만 앱은 응답을 못 받았다
        val first = service.refreshToken(RefreshTokenRequest(t0))
        assertEquals(first.refreshToken, u.refreshToken)
        assertEquals(iatOf(t0), u.refreshFloorAt)

        // 2) 앱이 다음 실행에서 t0 로 다시 온다 → 예전엔 401(로그아웃), 이제는 통과
        val second = service.refreshToken(RefreshTokenRequest(t0))
        assertNotNull(second.accessToken)
        assertEquals(second.refreshToken, u.refreshToken)
        assertEquals(iatOf(t0), u.refreshFloorAt, "floor 는 내려가지 않는다")
    }

    @Test
    fun `floor 보다 오래된 토큰은 받지 않는다`() {
        val u = user()
        val held = token(u.id, minutesAgo = 60)
        u.refreshToken = token(u.id, minutesAgo = 1)
        u.refreshFloorAt = iatOf(held)
        stub(u)

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(token(u.id, minutesAgo = 120)))
        }
    }

    @Test
    fun `floor 가 없으면(로그인·로그아웃 직후) 저장된 토큰만 받는다 — 옛 동작`() {
        val u = user()
        u.refreshToken = token(u.id, minutesAgo = 1)
        u.refreshFloorAt = null
        stub(u)

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(token(u.id, minutesAgo = 30)))
        }
    }

    @Test
    fun `액세스 토큰(수명 1시간)은 floor 안이어도 리프레시로 못 쓴다`() {
        val u = user()
        u.refreshToken = token(u.id, minutesAgo = 1)
        u.refreshFloorAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        stub(u)
        val access = token(u.id, minutesAgo = 5, lifetimeMs = 3_600_000L)

        assertNull(provider.parseRefreshToken(access))
        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(access))
        }
    }

    @Test
    fun `비활성 계정은 floor 규칙으로 받지 않는다`() {
        val u = user()
        val held = token(u.id, minutesAgo = 30)
        u.refreshToken = token(u.id, minutesAgo = 1)
        u.refreshFloorAt = iatOf(held)
        u.isActive = false
        stub(u)

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(held))
        }
    }

    @Test
    fun `로그아웃하면 floor 안의 옛 토큰도 더는 못 쓴다`() {
        val u = user()
        val held = token(u.id, minutesAgo = 30)
        u.refreshToken = token(u.id, minutesAgo = 1)
        u.refreshFloorAt = iatOf(held)
        stub(u)

        service.logout(u.id)

        assertNull(u.refreshToken)
        assertNull(u.refreshFloorAt)
        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(held))
        }
    }

    @Test
    fun `다른 사용자 토큰으로는 이 사용자의 floor 를 통과하지 못한다`() {
        val u = user(7L)
        u.refreshToken = token(7L, minutesAgo = 1)
        u.refreshFloorAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        stub(u)
        every { userRepository.findById(8L) } returns Optional.empty()

        assertThrows(InvalidCredentialsException::class.java) {
            service.refreshToken(RefreshTokenRequest(token(8L, minutesAgo = 5)))
        }
    }

    @Test
    fun `nextFloor 는 늦은 쪽만 남긴다`() {
        val a = LocalDateTime.of(2026, 11, 22, 1, 0, 0)
        val b = a.plusMinutes(5)
        assertEquals(a, RefreshTokenFamily.nextFloor(null, a))
        assertEquals(b, RefreshTokenFamily.nextFloor(a, b))
        assertEquals(b, RefreshTokenFamily.nextFloor(b, a))
    }

    @Test
    fun `로그인 경로는 모두 floor 를 비운다(다른 기기 옛 토큰을 안 받는 옛 동작)`() {
        val auth = File("src/main/kotlin/com/richjun/liftupai/domain/auth/service/AuthService.kt").readText()
        val oauth = File("src/main/kotlin/com/richjun/liftupai/domain/auth/service/OAuthService.kt").readText()
        val loginSets = Regex("""(savedUser|user)\.refreshToken = refreshToken\n\s*(savedUser|user)\.refreshFloorAt = null""")
        assertEquals(4, loginSets.findAll(auth).count(), "가입·로그인·기기 가입·기기 로그인")
        assertEquals(1, loginSets.findAll(oauth).count(), "소셜 로그인")
    }

    @Test
    fun `마이그레이션이 엔티티 컬럼을 만든다`() {
        val sql = File("src/main/resources/db/migration/V20261122001__refresh_floor.sql").readText()
        assert(sql.contains("ADD COLUMN refresh_floor_at DATETIME(6) NULL"))
    }
}

package com.richjun.liftupai.global.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 2026-09-25 운영: 이메일이 긴 구글 가입자의 리프레시 토큰이 users.refresh_token VARCHAR(255) 를 넘어
 * 가입이 500 으로 실패했다. 이메일 최대 길이(254)로 만든 토큰도 컬럼(1024)에 들어가야 한다.
 */
class RefreshTokenLengthTest {

    private val provider = JwtTokenProvider(
        jwtSecret = "x".repeat(64),
        accessTokenExpiration = 3_600_000L,
        refreshTokenExpiration = 1_209_600_000L,
    )

    @Test
    fun `refresh token for the longest possible email fits the column`() {
        val longEmail = "a".repeat(64) + "@" + "b".repeat(185) + ".com" // 254자
        val token = provider.generateRefreshToken(999_999_999L, longEmail)
        assertTrue(token.length > 255, "옛 컬럼(255)으로는 재현되는 길이여야 한다: ${token.length}")
        assertTrue(token.length <= 1024, "새 컬럼(1024)에 들어가야 한다: ${token.length}")
    }

    @Test
    fun `migration widens the column to match the entity`() {
        val sql = File("src/main/resources/db/migration/V20260927001__widen_users_refresh_token.sql").readText()
        assertTrue(sql.contains("MODIFY refresh_token VARCHAR(1024)"))
    }
}

package com.richjun.liftupai.global.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}")
    private val jwtSecret: String,

    @Value("\${jwt.access-token-expiration}")
    private val accessTokenExpiration: Long,

    @Value("\${jwt.refresh-token-expiration}")
    private val refreshTokenExpiration: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    fun generateAccessToken(userId: Long, identifier: String): String {
        return createToken(userId, identifier, accessTokenExpiration)
    }

    fun generateRefreshToken(userId: Long, identifier: String): String {
        return createToken(userId, identifier, refreshTokenExpiration)
    }

    private fun createToken(userId: Long, identifier: String, expiration: Long): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts.builder()
            .setSubject(userId.toString())
            .claim("identifier", identifier)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(key, SignatureAlgorithm.HS512)
            .compact()
    }

    fun getUserIdFromToken(token: String): Long {
        val claims = getClaimsFromToken(token)
        return claims.subject.toLong()
    }

    fun getIdentifierFromToken(token: String): String {
        val claims = getClaimsFromToken(token)
        return claims["identifier"] as String
    }

    fun validateToken(token: String): Boolean {
        return try {
            val claims = getClaimsFromToken(token)
            !claims.expiration.before(Date())
        } catch (e: Exception) {
            false
        }
    }

    private fun getClaimsFromToken(token: String): Claims {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .body
    }

    fun getExpirationFromToken(token: String): Date {
        val claims = getClaimsFromToken(token)
        return claims.expiration
    }

    /**
     * 리프레시 토큰이면 (사용자, 발급 시각 UTC 초 단위), 아니면 null.
     * 서명·만료가 맞고 수명(exp - iat)이 하루를 넘어야 한다 — 액세스 토큰(1시간)을 리프레시로 쓰지 못하게.
     * 옛 토큰에도 그대로 맞도록 별도 클레임 없이 수명으로 가른다.
     */
    fun parseRefreshToken(token: String): RefreshTokenClaims? = try {
        val claims = getClaimsFromToken(token)
        val issuedAt = claims.issuedAt
        val expiration = claims.expiration
        if (issuedAt == null || expiration == null || expiration.before(Date())) null
        else if (expiration.time - issuedAt.time <= REFRESH_MIN_LIFETIME_MS) null
        else RefreshTokenClaims(
            userId = claims.subject.toLong(),
            issuedAt = LocalDateTime.ofInstant(issuedAt.toInstant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        const val REFRESH_MIN_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}

data class RefreshTokenClaims(val userId: Long, val issuedAt: LocalDateTime)
package com.richjun.liftupai.domain.auth.service

import com.richjun.liftupai.domain.auth.dto.*
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.global.security.JwtTokenProvider
import com.richjun.liftupai.global.time.AppTime
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.util.Base64

@Service
@Transactional
class OAuthService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper
) {
    @Value("\${oauth.google.client-id:NOT_SET}")
    private lateinit var googleClientId: String

    @Value("\${oauth.apple.client-id:NOT_SET}")
    private lateinit var appleClientId: String

    @Value("\${oauth.kakao.rest-api-key:NOT_SET}")
    private lateinit var kakaoRestApiKey: String

    // ==================== GOOGLE ====================

    fun loginWithGoogle(request: GoogleLoginRequest): OAuthResponse {
        val payload = verifyGoogleToken(request.idToken)
        val email = payload["email"] as? String
            ?: throw IllegalArgumentException("Google token does not contain email")
        val googleId = payload["sub"] as String
        val name = payload["name"] as? String ?: email.substringBefore("@")

        return findOrCreateUser(
            provider = "GOOGLE",
            oauthId = googleId,
            email = email,
            nickname = name
        )
    }

    private fun verifyGoogleToken(idToken: String): Map<String, Any> {
        val restTemplate = RestTemplate()
        val response = restTemplate.getForObject(
            "https://oauth2.googleapis.com/tokeninfo?id_token=$idToken",
            Map::class.java
        ) ?: throw IllegalArgumentException("Failed to verify Google token")

        val aud = response["aud"] as? String
        if (aud != googleClientId && googleClientId != "NOT_SET") {
            throw IllegalArgumentException("Invalid Google token audience")
        }

        @Suppress("UNCHECKED_CAST")
        return response as Map<String, Any>
    }

    // ==================== APPLE ====================

    fun loginWithApple(request: AppleLoginRequest): OAuthResponse {
        val payload = verifyAppleToken(request.identityToken)
        val appleId = payload["sub"] as String
        val email = payload["email"] as? String
        val name = request.fullName ?: email?.substringBefore("@") ?: "사용자"

        return findOrCreateUser(
            provider = "APPLE",
            oauthId = appleId,
            email = email,
            nickname = name
        )
    }

    private fun verifyAppleToken(identityToken: String): Map<String, Any> {
        val parts = identityToken.split(".")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid Apple identity token format")
        }

        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
        val claims = objectMapper.readValue(payloadJson, Map::class.java)

        val iss = claims["iss"] as? String
        if (iss != "https://appleid.apple.com") {
            throw IllegalArgumentException("Invalid Apple token issuer")
        }

        val aud = claims["aud"] as? String
        if (aud != appleClientId && appleClientId != "NOT_SET") {
            throw IllegalArgumentException("Invalid Apple token audience")
        }

        @Suppress("UNCHECKED_CAST")
        return claims as Map<String, Any>
    }

    // ==================== KAKAO ====================

    fun loginWithKakao(request: KakaoLoginRequest): OAuthResponse {
        val userInfo = getKakaoUserInfo(request.accessToken)
        val kakaoId = userInfo["id"].toString()
        val kakaoAccount = userInfo["kakao_account"] as? Map<*, *>
        val email = kakaoAccount?.get("email") as? String
        val profile = kakaoAccount?.get("profile") as? Map<*, *>
        val nickname = profile?.get("nickname") as? String ?: email?.substringBefore("@") ?: "사용자"

        return findOrCreateUser(
            provider = "KAKAO",
            oauthId = kakaoId,
            email = email,
            nickname = nickname
        )
    }

    private fun getKakaoUserInfo(accessToken: String): Map<String, Any> {
        val restTemplate = RestTemplate()
        val headers = HttpHeaders()
        headers.setBearerAuth(accessToken)
        val entity = HttpEntity<String>(headers)

        val response = restTemplate.exchange(
            "https://kapi.kakao.com/v2/user/me",
            HttpMethod.GET,
            entity,
            Map::class.java
        )

        @Suppress("UNCHECKED_CAST")
        return response.body as? Map<String, Any>
            ?: throw IllegalArgumentException("Failed to get Kakao user info")
    }

    // ==================== COMMON ====================

    private fun findOrCreateUser(
        provider: String,
        oauthId: String,
        email: String?,
        nickname: String
    ): OAuthResponse {
        // 1. Try to find by OAuth provider + ID
        val existingByOAuth = userRepository.findByOauthProviderAndOauthId(provider, oauthId)
        if (existingByOAuth.isPresent) {
            val user = existingByOAuth.get()
            return generateOAuthResponse(user, isNewUser = false)
        }

        // 2. Try to find by email (link accounts)
        if (email != null) {
            val existingByEmail = userRepository.findByEmail(email)
            if (existingByEmail.isPresent) {
                val user = existingByEmail.get()
                user.oauthProvider = provider
                user.oauthId = oauthId
                userRepository.save(user)
                return generateOAuthResponse(user, isNewUser = false)
            }
        }

        // 3. Create new user
        val newUser = User(
            email = email,
            nickname = nickname,
            oauthProvider = provider,
            oauthId = oauthId,
            isDeviceAccount = false,
            isActive = true
        )
        val savedUser = userRepository.save(newUser)
        return generateOAuthResponse(savedUser, isNewUser = true)
    }

    private fun generateOAuthResponse(user: User, isNewUser: Boolean): OAuthResponse {
        val identifier = user.email ?: user.oauthId ?: user.id.toString()
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, identifier)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, identifier)

        user.refreshToken = refreshToken
        user.lastLoginAt = AppTime.utcNow()
        userRepository.save(user)

        return OAuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = OAuthUserInfo(
                id = user.id,
                email = user.email,
                nickname = user.nickname,
                provider = user.oauthProvider ?: "DEVICE"
            ),
            isNewUser = isNewUser
        )
    }
}

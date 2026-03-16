package com.richjun.liftupai.domain.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class GoogleLoginRequest(
    @JsonProperty("id_token")
    val idToken: String
)

data class AppleLoginRequest(
    @JsonProperty("identity_token")
    val identityToken: String,

    @JsonProperty("authorization_code")
    val authorizationCode: String? = null,

    @JsonProperty("full_name")
    val fullName: String? = null
)

data class KakaoLoginRequest(
    @JsonProperty("access_token")
    val accessToken: String
)

data class OAuthResponse(
    @JsonProperty("access_token")
    val accessToken: String,

    @JsonProperty("refresh_token")
    val refreshToken: String,

    val user: OAuthUserInfo,

    @JsonProperty("is_new_user")
    val isNewUser: Boolean
)

data class OAuthUserInfo(
    val id: Long,
    val email: String?,
    val nickname: String,
    val provider: String
)

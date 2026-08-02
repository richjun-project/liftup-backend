package com.richjun.liftupai.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userDetailsService: CustomUserDetailsService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val jwt = getTokenFromRequest(request)

            if (jwt != null && jwtTokenProvider.validateToken(jwt)) {
                // subject(userId)가 유일하게 신뢰 가능한 식별자다.
                //
                // identifier claim 으로 조회하면 카카오 이메일 미동의 가입자가 전부 깨진다:
                // OAuthService 가 identifier 에 email ?: oauthId 를 넣는데, oauthId("5019350509")는
                // "@" 가 없어 deviceId 조회로 빠지고, deviceId 컬럼엔 그 값이 없어 항상 실패
                // → 유효한 토큰인데 모든 API 가 403 (프로덕션 2026-08-02 실측).
                val userDetails = try {
                    userDetailsService.loadUserById(jwtTokenProvider.getUserIdFromToken(jwt))
                } catch (ex: Exception) {
                    // 구형 토큰(subject 가 userId 숫자가 아닌 경우) 호환 폴백
                    userDetailsService.loadUserByIdentifier(jwtTokenProvider.getIdentifierFromToken(jwt))
                }
                val authentication = UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.authorities
                )
                authentication.details = WebAuthenticationDetailsSource().buildDetails(request)

                SecurityContextHolder.getContext().authentication = authentication
            }
        } catch (ex: Exception) {
            logger.error("Could not set user authentication in security context", ex)
        }

        filterChain.doFilter(request, response)
    }

    private fun getTokenFromRequest(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7)
        }
        return null
    }
}
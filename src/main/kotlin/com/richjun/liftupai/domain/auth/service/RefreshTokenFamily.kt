package com.richjun.liftupai.domain.auth.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.security.RefreshTokenClaims
import java.time.LocalDateTime

/**
 * 리프레시 토큰 '가족' 규칙(2026-11-22).
 *
 * 예전엔 갱신할 때마다 저장된 토큰 한 개만 받아 주고 바로 새 것으로 바꿨다(한 번 쓰면 폐기).
 * 그런데 앱이 갱신 응답을 받기 전에 멈추면 — iOS 가 알림으로 앱을 백그라운드에서 켰다가 곧바로 재우는 경우 등 —
 * 서버는 이미 새 토큰으로 바꿨는데 앱은 옛 토큰을 들고 있어, 다음 실행에서 401 → 앱이 로그아웃했다.
 * 운영 접근 로그(11-12~11-22) 실기기 갱신 13번 중 3번이 이렇게 실패했다(339: 11-18 갱신 200 뒤 요청 없음 →
 * 11-19 알림 2.4초 뒤 백그라운드 실행에서 갱신 401 → 로그아웃).
 *
 * 규칙: 저장된 토큰과 같으면 받는다(옛 동작). 아니어도 `refreshFloorAt` 이후 발급된 이 사용자의 리프레시 토큰이면 받는다.
 * 갱신에 성공하면 floor 를 '방금 제시된 토큰의 발급 시각'으로 올린다(내리지 않음) — 앱이 확실히 들고 있던 토큰보다
 * 오래된 것만 막는다. 로그아웃은 floor·저장 토큰을 모두 지워 그 뒤엔 어떤 옛 토큰도 안 받는다.
 */
object RefreshTokenFamily {

    /** 저장된 토큰과 다른 토큰을 floor 규칙으로 받아 줄 수 있나. 비활성·탈퇴 대기 계정은 안 된다. */
    fun accepts(user: User, claims: RefreshTokenClaims): Boolean {
        if (user.id != claims.userId) return false
        if (!user.isActive || user.deletedAt != null) return false
        val floor = user.refreshFloorAt ?: return false
        return !claims.issuedAt.isBefore(floor)
    }

    /** 갱신 성공 뒤의 floor — 제시된 토큰 발급 시각과 지금 floor 중 늦은 쪽. */
    fun nextFloor(current: LocalDateTime?, presentedIssuedAt: LocalDateTime): LocalDateTime =
        if (current == null || presentedIssuedAt.isAfter(current)) presentedIssuedAt else current
}

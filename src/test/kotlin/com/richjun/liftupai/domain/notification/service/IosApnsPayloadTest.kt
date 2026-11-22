package com.richjun.liftupai.domain.notification.service

import com.google.api.client.json.gson.GsonFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-22: iOS 알림에 content-available 을 넣으면 iOS 가 알림마다 앱을 백그라운드에서 켜고, 그 실행이 토큰을 갱신한 뒤
 * 곧바로 재워져 갱신 응답을 잃은 앱이 다음 실행에서 로그아웃됐다(339). 알림은 소리·배지만 — 백그라운드 실행 없음.
 */
class IosApnsPayloadTest {

    @Test
    fun `iOS 알림은 content-available 없이 소리·배지만 보낸다`() {
        val json = GsonFactory.getDefaultInstance().toString(FcmNotificationService.iosApnsConfig())
        assertThat(json).contains("\"sound\":\"default\"").contains("\"badge\":1")
        assertThat(json).doesNotContain("content-available")
    }
}

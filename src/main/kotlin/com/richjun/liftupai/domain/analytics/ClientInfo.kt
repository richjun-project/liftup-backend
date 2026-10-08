package com.richjun.liftupai.domain.analytics

import jakarta.servlet.http.HttpServletRequest

/**
 * 앱이 모든 요청에 붙이는 클라이언트 헤더(X-App-Version·X-Platform·X-Test-Device)를 정제한 값.
 * 헤더는 사용자가 바꿀 수 있는 값이라 형식이 맞지 않으면 버린다(분석·기록 용도로만 쓴다).
 */
data class ClientInfo(
    val appVersion: String? = null,
    val platform: String? = null,
    val testDevice: Boolean = false
) {
    companion object {
        const val HEADER_APP_VERSION = "X-App-Version"
        const val HEADER_PLATFORM = "X-Platform"
        const val HEADER_TEST_DEVICE = "X-Test-Device"

        private val VERSION = Regex("^[0-9A-Za-z.+_-]{1,32}$")
        private val PLATFORMS = setOf("ios", "android", "web")

        fun from(request: HttpServletRequest?): ClientInfo = of(
            request?.getHeader(HEADER_APP_VERSION),
            request?.getHeader(HEADER_PLATFORM),
            request?.getHeader(HEADER_TEST_DEVICE)
        )

        fun of(appVersion: String?, platform: String?, testDevice: String?): ClientInfo = ClientInfo(
            appVersion = sanitizeVersion(appVersion),
            platform = platform?.trim()?.lowercase()?.takeIf { it in PLATFORMS },
            testDevice = testDevice?.trim() == "1"
        )

        fun sanitizeVersion(value: String?): String? = value?.trim()?.takeIf { VERSION.matches(it) }
    }
}

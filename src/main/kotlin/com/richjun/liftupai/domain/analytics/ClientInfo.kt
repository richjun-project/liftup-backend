package com.richjun.liftupai.domain.analytics

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress

/**
 * 앱이 모든 요청에 붙이는 클라이언트 헤더(X-App-Version·X-Platform·X-Test-Device)를 정제한 값.
 * 헤더는 사용자가 바꿀 수 있는 값이라 형식이 맞지 않으면 버린다(분석·기록 용도로만 쓴다).
 *
 * 테스트 기기 판정은 헤더 외에 요청 IP 도 본다: Play 사전 출시 로봇·Play 심사자는 Google 망(66.249.x·74.125.x 등)에서
 * 오는데 헤더를 붙이지 않아(앱이 Test Lab 으로 감지 못 함) 실가입·첫 세트로 세지고 있었다(2026-10-26, 30일 실가입 16명 중 3명).
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

        /** Google 자체 망(검색 로봇·Firebase Test Lab·Play 심사). Google Cloud 고객 대역(34.x·35.x)은 넣지 않는다 — 일반 VPN 사용자가 섞인다. */
        private val GOOGLE_TEST_NETWORKS = listOf(
            "66.249.64.0/19", "64.233.160.0/19", "74.125.0.0/16", "72.14.192.0/18", "209.85.128.0/17",
            "108.177.0.0/17", "172.253.0.0/16", "173.194.0.0/16", "216.239.32.0/19",
            "192.178.0.0/15" // 2026-10-27: 1.1.34 사전 출시 로봇이 192.178.11.x 에서 왔다(ARIN: Google LLC)
        ).map { cidr ->
            val (net, bits) = cidr.split("/")
            ipv4ToLong(net)!! to bits.toInt()
        }

        fun from(request: HttpServletRequest?): ClientInfo {
            val info = of(
                request?.getHeader(HEADER_APP_VERSION),
                request?.getHeader(HEADER_PLATFORM),
                request?.getHeader(HEADER_TEST_DEVICE)
            )
            return if (!info.testDevice && isGoogleTestNetwork(clientIp(request))) info.copy(testDevice = true) else info
        }

        fun of(appVersion: String?, platform: String?, testDevice: String?): ClientInfo = ClientInfo(
            appVersion = sanitizeVersion(appVersion),
            platform = platform?.trim()?.lowercase()?.takeIf { it in PLATFORMS },
            testDevice = testDevice?.trim() == "1"
        )

        fun sanitizeVersion(value: String?): String? = value?.trim()?.takeIf { VERSION.matches(it) }

        /**
         * 실제 클라이언트 IP. 앱 컨테이너는 127.0.0.1 에만 열려 있고 앞단 Caddy 가 X-Forwarded-For 를 새로 쓴다(바깥 값은 믿지 않음) —
         * 그래서 직접 연결한 쪽이 루프백·사설망(프록시)일 때만 그 헤더의 첫 값을 쓴다.
         */
        fun clientIp(request: HttpServletRequest?): String? {
            val remote = request?.remoteAddr ?: return null
            val viaProxy = runCatching { InetAddress.getByName(remote).let { it.isLoopbackAddress || it.isSiteLocalAddress } }.getOrDefault(false)
            if (!viaProxy) return remote
            return request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: remote
        }

        fun isGoogleTestNetwork(ip: String?): Boolean {
            val addr = ipv4ToLong(ip ?: return false) ?: return false
            return GOOGLE_TEST_NETWORKS.any { (net, bits) ->
                val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                (addr and mask) == (net and mask)
            }
        }

        private fun ipv4ToLong(ip: String): Long? {
            val parts = ip.trim().split(".")
            if (parts.size != 4) return null
            var v = 0L
            for (p in parts) {
                val n = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                v = (v shl 8) or n.toLong()
            }
            return v
        }
    }
}

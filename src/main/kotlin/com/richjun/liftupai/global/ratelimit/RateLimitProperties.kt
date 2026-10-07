package com.richjun.liftupai.global.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rate-limit")
class RateLimitProperties {
    var chat: ChatRateLimit = ChatRateLimit()
    var search: SearchRateLimit = SearchRateLimit()

    /** 운동 검색·제안·추천·대체 운동: 사용자별 분당 요청 수 */
    class SearchRateLimit {
        var perMinute: Int = 120
    }

    class ChatRateLimit {
        var perMinute: Int = 10
        var perHour: Int = 60
        var perDay: Int = 300
    }
}

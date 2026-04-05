package com.richjun.liftupai.global.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rate-limit")
class RateLimitProperties {
    var chat: ChatRateLimit = ChatRateLimit()

    class ChatRateLimit {
        var perMinute: Int = 10
        var perHour: Int = 60
        var perDay: Int = 300
    }
}

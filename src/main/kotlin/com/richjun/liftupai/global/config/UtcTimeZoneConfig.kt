package com.richjun.liftupai.global.config

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import java.util.TimeZone

@Configuration
class UtcTimeZoneConfig {
    @PostConstruct
    fun setUtcDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
}

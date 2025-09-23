package com.richjun.liftupai.global.common

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class HealthController {

    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<ApiResponse<HealthResponse>> {
        val response = HealthResponse(
            status = "UP",
            timestamp = Instant.now(),
            service = "LiftUp AI Backend",
            version = "1.0.0"
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/version")
    fun getVersion(): ResponseEntity<ApiResponse<VersionInfo>> {
        val versionInfo = VersionInfo(
            version = "1.0.0",
            buildTime = Instant.now(),
            environment = System.getenv("SPRING_PROFILES_ACTIVE") ?: "default"
        )
        return ResponseEntity.ok(ApiResponse.success(versionInfo))
    }
}

data class HealthResponse(
    val status: String,
    val timestamp: Instant,
    val service: String,
    val version: String
)

data class VersionInfo(
    val version: String,
    val buildTime: Instant,
    val environment: String
)
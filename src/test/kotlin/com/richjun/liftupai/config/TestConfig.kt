package com.richjun.liftupai.config

import io.mockk.mockk
import io.qdrant.client.QdrantClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class TestConfig {

    @Bean
    @Primary
    fun testQdrantClient(): QdrantClient = mockk(relaxed = true)
}

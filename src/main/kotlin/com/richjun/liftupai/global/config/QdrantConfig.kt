package com.richjun.liftupai.global.config

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class QdrantConfig {

    @Value("\${qdrant.host:localhost}")
    private lateinit var host: String

    @Value("\${qdrant.port:6334}")
    private var port: Int = 6334

    @Bean
    fun qdrantClient(): QdrantClient {
        return QdrantClient(
            QdrantGrpcClient.newBuilder(host, port, false).build()
        )
    }
}

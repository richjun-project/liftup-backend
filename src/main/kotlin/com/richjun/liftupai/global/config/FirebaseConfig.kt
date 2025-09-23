package com.richjun.liftupai.global.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import java.io.FileInputStream

@Configuration
class FirebaseConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${firebase.config.path:firebase-service-account.json}")
    private lateinit var firebaseConfigPath: String

    @Value("\${firebase.enabled:false}")
    private var firebaseEnabled: Boolean = false

    @Bean
    fun firebaseMessaging(): FirebaseMessaging? {
        if (!firebaseEnabled) {
            logger.info("Firebase is disabled. FCM notifications will not be sent.")
            return null
        }

        return try {
            val firebaseOptions = FirebaseOptions.builder()
                .setCredentials(getCredentials())
                .build()

            val app = if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(firebaseOptions)
            } else {
                FirebaseApp.getInstance()
            }

            logger.info("Firebase Admin SDK initialized successfully")
            FirebaseMessaging.getInstance(app)
        } catch (e: Exception) {
            logger.error("Failed to initialize Firebase Admin SDK: ${e.message}")
            logger.warn("FCM notifications will be disabled")
            null
        }
    }

    private fun getCredentials(): GoogleCredentials {
        return try {
            // 먼저 클래스패스에서 찾기
            val resource = ClassPathResource(firebaseConfigPath)
            if (resource.exists()) {
                GoogleCredentials.fromStream(resource.inputStream)
            } else {
                // 파일 시스템에서 찾기
                GoogleCredentials.fromStream(FileInputStream(firebaseConfigPath))
            }
        } catch (e: Exception) {
            logger.warn("Firebase credentials file not found. Using default credentials.")
            // 환경 변수나 기본 인증 사용
            GoogleCredentials.getApplicationDefault()
        }
    }
}
package com.richjun.liftupai.domain.notification.service

import com.google.firebase.messaging.*
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Firebase Cloud Messaging을 사용한 푸시 알림 전송 서비스
 */
@Component
class FcmNotificationService(
    private val notificationDeviceRepository: NotificationDeviceRepository,
    @Autowired(required = false)
    private val firebaseMessaging: FirebaseMessaging?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendNotification(
        device: NotificationDevice,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        imageUrl: String? = null
    ): Boolean {
        if (firebaseMessaging == null) {
            logger.warn("Firebase Messaging is not configured. Notification not sent.")
            return false
        }

        return try {
            val messageBuilder = Message.builder()
                .setToken(device.deviceToken)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .setImage(imageUrl)
                        .build()
                )

            // 데이터 추가
            data?.let {
                messageBuilder.putAllData(it)
            }

            // 플랫폼별 설정
            when (device.platform) {
                DevicePlatform.ANDROID -> configureAndroidMessage(messageBuilder)
                DevicePlatform.IOS -> configureIOSMessage(messageBuilder)
                DevicePlatform.WEB -> configureWebMessage(messageBuilder)
            }

            val response = firebaseMessaging.send(messageBuilder.build())
            logger.info("Successfully sent notification to device: ${device.deviceToken}, response: $response")

            device.lastUsedAt = LocalDateTime.now()
            notificationDeviceRepository.save(device)
            true
        } catch (e: FirebaseMessagingException) {
            logger.error("Failed to send FCM notification: ${e.message}")
            handleFirebaseError(e, device)
            false
        } catch (e: Exception) {
            logger.error("Unexpected error sending notification: ${e.message}")
            false
        }
    }

    fun sendBatchNotifications(
        tokens: List<String>,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        imageUrl: String? = null
    ): BatchResponse? {
        if (firebaseMessaging == null) {
            logger.warn("Firebase Messaging is not configured. Batch notifications not sent.")
            return null
        }

        if (tokens.isEmpty()) {
            logger.warn("No tokens provided for batch notification")
            return null
        }

        try {
            val messages = tokens.map { token ->
                Message.builder()
                    .setToken(token)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setImage(imageUrl)
                            .build()
                    )
                    .apply { data?.let { putAllData(it) } }
                    .build()
            }

            val batchResponse = firebaseMessaging.sendAll(messages)
            logger.info("Batch notification sent. Success: ${batchResponse.successCount}, Failed: ${batchResponse.failureCount}")

            // 실패한 토큰 처리
            batchResponse.responses.forEachIndexed { index, response ->
                if (!response.isSuccessful) {
                    val token = tokens[index]
                    logger.error("Failed to send to token $token: ${response.exception?.message}")
                }
            }

            return batchResponse
        } catch (e: Exception) {
            logger.error("Failed to send batch notifications: ${e.message}")
            return null
        }
    }

    private fun configureAndroidMessage(builder: Message.Builder) {
        builder.setAndroidConfig(
            AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(
                    AndroidNotification.builder()
                        .setSound("default")
                        .setChannelId("workout_channel")
                        .build()
                )
                .build()
        )
    }

    private fun configureIOSMessage(builder: Message.Builder) {
        builder.setApnsConfig(
            ApnsConfig.builder()
                .setAps(
                    Aps.builder()
                        .setSound("default")
                        .setBadge(1)
                        .setContentAvailable(true)
                        .build()
                )
                .build()
        )
    }

    private fun configureWebMessage(builder: Message.Builder) {
        builder.setWebpushConfig(
            WebpushConfig.builder()
                .setNotification(
                    WebpushNotification.builder()
                        .setIcon("/icon-192x192.png")
                        .setBadge("/badge-72x72.png")
                        .setVibrate(intArrayOf(200, 100, 200))
                        .build()
                )
                .build()
        )
    }

    private fun handleFirebaseError(e: FirebaseMessagingException, device: NotificationDevice) {
        when (e.messagingErrorCode) {
            MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.UNREGISTERED -> {
                // 유효하지 않은 토큰, 기기 비활성화
                logger.warn("Invalid token for device: ${device.deviceToken}. Deactivating device.")
                device.isActive = false
                notificationDeviceRepository.save(device)
            }
            MessagingErrorCode.QUOTA_EXCEEDED -> {
                logger.error("FCM quota exceeded")
            }
            MessagingErrorCode.SENDER_ID_MISMATCH -> {
                logger.error("Sender ID mismatch for device: ${device.deviceToken}")
            }
            else -> {
                logger.error("FCM error: ${e.messagingErrorCode} - ${e.message}")
            }
        }
    }
}
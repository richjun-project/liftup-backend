package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.entity.NotificationSettings
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.entity.*
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.google.firebase.messaging.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.springframework.data.domain.PageRequest
import java.util.*

@Service
@Transactional
class NotificationService(
    private val userRepository: UserRepository,
    private val notificationDeviceRepository: NotificationDeviceRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository,
    private val chatMessageRepository: ChatMessageRepository,
    @Autowired(required = false)
    private val firebaseMessaging: FirebaseMessaging?,
    @Autowired(required = false)
    private val fcmNotificationService: FcmNotificationService?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun registerDevice(userId: Long, request: RegisterDeviceRequest): RegisterDeviceResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val platform = try {
            DevicePlatform.valueOf(request.platform.uppercase())
        } catch (e: IllegalArgumentException) {
            return RegisterDeviceResponse(
                success = false,
                message = "지원하지 않는 플랫폼입니다: ${request.platform}"
            )
        }

        val existingDevice = notificationDeviceRepository.findByDeviceToken(request.deviceToken)

        val device = if (existingDevice.isPresent) {
            val dev = existingDevice.get()
            dev.isActive = true
            dev.lastUsedAt = LocalDateTime.now()
            dev.deviceName = request.deviceName ?: dev.deviceName
            dev.appVersion = request.appVersion ?: dev.appVersion
            dev
        } else {
            NotificationDevice(
                user = user,
                deviceToken = request.deviceToken,
                platform = platform,
                deviceName = request.deviceName,
                appVersion = request.appVersion
            )
        }

        notificationDeviceRepository.save(device)

        return RegisterDeviceResponse(
            success = true,
            message = "디바이스가 성공적으로 등록되었습니다"
        )
    }

    @Transactional(readOnly = true)
    fun getNotificationSettings(userId: Long): NotificationSettingsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = notificationSettingsRepository.findByUser(user)
            .orElseGet { createDefaultSettings(user) }

        return toNotificationSettingsResponse(settings)
    }

    fun updateNotificationSettings(userId: Long, request: UpdateNotificationSettingsRequest): UpdateNotificationSettingsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = notificationSettingsRepository.findByUser(user)
            .orElseGet { createDefaultSettings(user) }

        request.workoutReminder?.let { settings.workoutReminder = it }
        request.workoutReminderTime?.let {
            settings.workoutReminderTime = LocalTime.parse(it)
        }
        request.aiMessages?.let { settings.aiMessages = it }
        request.achievements?.let { settings.achievements = it }
        request.marketing?.let { settings.marketing = it }
        request.dailyReport?.let { settings.dailyReport = it }
        request.weeklyReport?.let { settings.weeklyReport = it }
        request.socialUpdates?.let { settings.socialUpdates = it }
        request.recoveryAlerts?.let { settings.recoveryAlerts = it }

        settings.updatedAt = LocalDateTime.now()

        val saved = notificationSettingsRepository.save(settings)

        return UpdateNotificationSettingsResponse(
            success = true,
            settings = toNotificationSettingsResponse(saved)
        )
    }

    fun sendPushNotification(request: PushNotificationRequest): PushNotificationResponse {
        val user = userRepository.findById(request.userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val activeDevices = notificationDeviceRepository.findActiveDevicesByUser(user)

        if (activeDevices.isEmpty()) {
            return PushNotificationResponse(
                success = false,
                sentCount = 0,
                failedCount = 0,
                message = "등록된 활성 디바이스가 없습니다"
            )
        }

        var sentCount = 0
        var failedCount = 0

        activeDevices.forEach { device ->
            try {
                sendToDevice(device, request.title, request.body, request.data, request.imageUrl)
                sentCount++
            } catch (e: Exception) {
                logger.error("Failed to send notification to device ${device.id}", e)
                failedCount++
            }
        }

        return PushNotificationResponse(
            success = sentCount > 0,
            sentCount = sentCount,
            failedCount = failedCount,
            message = "알림 전송 완료"
        )
    }

    fun sendWorkoutReminder(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.workoutReminder) return

        val request = PushNotificationRequest(
            userId = userId,
            title = "운동 시간입니다! 💪",
            body = "오늘의 운동을 시작해보세요",
            data = mapOf("type" to "workout_reminder")
        )

        sendPushNotification(request)
    }

    fun sendAchievementNotification(userId: Long, achievement: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.achievements) return

        val request = PushNotificationRequest(
            userId = userId,
            title = "새로운 업적 달성! 🎉",
            body = achievement,
            data = mapOf("type" to "achievement")
        )

        sendPushNotification(request)
    }

    fun sendRecoveryAlert(userId: Long, muscleGroup: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.recoveryAlerts) return

        val request = PushNotificationRequest(
            userId = userId,
            title = "근육 회복 완료",
            body = "$muscleGroup 근육이 완전히 회복되었습니다. 운동을 시작해보세요!",
            data = mapOf("type" to "recovery_alert", "muscle" to muscleGroup)
        )

        sendPushNotification(request)
    }

    private fun createDefaultSettings(user: com.richjun.liftupai.domain.auth.entity.User): NotificationSettings {
        return NotificationSettings(
            user = user,
            workoutReminder = true,
            workoutReminderTime = LocalTime.of(18, 0),
            aiMessages = true,
            achievements = true,
            marketing = false,
            dailyReport = false,
            weeklyReport = true,
            socialUpdates = true,
            recoveryAlerts = true
        )
    }

    private fun toNotificationSettingsResponse(settings: NotificationSettings): NotificationSettingsResponse {
        return NotificationSettingsResponse(
            workoutReminder = settings.workoutReminder,
            workoutReminderTime = settings.workoutReminderTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            aiMessages = settings.aiMessages,
            achievements = settings.achievements,
            marketing = settings.marketing,
            dailyReport = settings.dailyReport,
            weeklyReport = settings.weeklyReport,
            socialUpdates = settings.socialUpdates,
            recoveryAlerts = settings.recoveryAlerts
        )
    }

    private fun sendToDevice(
        device: NotificationDevice,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?
    ) {
        // 실제 구현에서는 Firebase Admin SDK를 사용하여 FCM 메시지 전송
        // 여기서는 로그만 남기는 모의 구현
        logger.info("Sending notification to device: ${device.deviceToken}")
        logger.info("Title: $title, Body: $body")

        // FCM 메시지 전송 로직
        when (device.platform) {
            DevicePlatform.ANDROID -> sendAndroidNotification(device.deviceToken, title, body, data, imageUrl)
            DevicePlatform.IOS -> sendIOSNotification(device.deviceToken, title, body, data, imageUrl)
            DevicePlatform.WEB -> sendWebNotification(device.deviceToken, title, body, data, imageUrl)
        }

        device.lastUsedAt = LocalDateTime.now()
        notificationDeviceRepository.save(device)
    }

    private fun sendAndroidNotification(token: String, title: String, body: String, data: Map<String, String>?, imageUrl: String?) {
        // Android FCM 전송 로직
        logger.info("Sending Android notification to token: $token")
    }

    private fun sendIOSNotification(token: String, title: String, body: String, data: Map<String, String>?, imageUrl: String?) {
        // iOS APNs 전송 로직
        logger.info("Sending iOS notification to token: $token")
    }

    private fun sendWebNotification(token: String, title: String, body: String, data: Map<String, String>?, imageUrl: String?) {
        // Web Push 전송 로직
        logger.info("Sending Web notification to token: $token")
    }

    // Notification Scheduling Methods
    fun scheduleWorkoutReminder(userId: Long, request: WorkoutReminderRequest): ScheduleResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        try {
            val time = LocalTime.parse(request.time)
            val days = request.days.map { dayStr ->
                try {
                    DayOfWeek.valueOf(dayStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("유효하지 않은 요일입니다: $dayStr")
                }
            }.toMutableSet()

            val schedule = NotificationSchedule(
                user = user,
                scheduleName = request.scheduleName,
                days = days,
                time = time,
                enabled = request.enabled,
                message = request.message,
                notificationType = NotificationType.valueOf(request.notificationType.uppercase()),
                nextTriggerAt = calculateNextTrigger(days, time)
            )

            val savedSchedule = notificationScheduleRepository.save(schedule)

            return ScheduleResponse(
                scheduleId = savedSchedule.id.toString(),
                nextTriggerAt = savedSchedule.nextTriggerAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                status = "scheduled",
                created = true
            )
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("유효하지 않은 시간 형식입니다: ${request.time}")
        }
    }

    fun deleteWorkoutSchedule(userId: Long, scheduleId: Long): DeleteScheduleResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val schedule = notificationScheduleRepository.findByUserAndId(user, scheduleId)
            ?: throw ResourceNotFoundException("스케줄을 찾을 수 없습니다")

        notificationScheduleRepository.delete(schedule)

        return DeleteScheduleResponse(
            success = true,
            deletedScheduleId = scheduleId.toString(),
            message = "운동 리마인더가 취소되었습니다"
        )
    }

    // Notification History Methods
    @Transactional(readOnly = true)
    fun getNotificationHistory(userId: Long, page: Int, limit: Int, unreadOnly: Boolean): NotificationHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val pageable = PageRequest.of(page - 1, limit)
        val notificationPage = if (unreadOnly) {
            notificationHistoryRepository.findByUserAndIsRead(user, false, pageable)
        } else {
            notificationHistoryRepository.findByUserOrderByCreatedAtDesc(user, pageable)
        }

        val notifications = notificationPage.content.map { notification ->
            NotificationHistoryItem(
                notificationId = notification.notificationId,
                type = notification.type.name.lowercase(),
                title = notification.title,
                body = notification.body,
                data = notification.data.takeIf { it.isNotEmpty() },
                isRead = notification.isRead,
                createdAt = notification.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                readAt = notification.readAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }

        val unreadCount = notificationHistoryRepository.countUnreadByUser(user)

        return NotificationHistoryResponse(
            notifications = notifications,
            pagination = PaginationInfo(
                page = page,
                limit = limit,
                total = notificationPage.totalElements,
                hasNext = notificationPage.hasNext()
            ),
            unreadCount = unreadCount
        )
    }

    fun markNotificationAsRead(userId: Long, notificationId: String): MarkAsReadResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val notification = notificationHistoryRepository.findByUserAndNotificationId(user, notificationId)
            .orElseThrow { ResourceNotFoundException("알림을 찾을 수 없습니다") }

        if (!notification.isRead) {
            notification.isRead = true
            notification.readAt = LocalDateTime.now()
            notificationHistoryRepository.save(notification)
        }

        val unreadCount = notificationHistoryRepository.countUnreadByUser(user)

        return MarkAsReadResponse(
            success = true,
            notificationId = notificationId,
            readAt = notification.readAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) ?: "",
            unreadCount = unreadCount
        )
    }

    // Enhanced notification sending with history tracking
    fun sendScheduledNotification(schedule: NotificationSchedule) {
        val notificationId = UUID.randomUUID().toString()

        // Save to history first
        val history = NotificationHistory(
            user = schedule.user,
            notificationId = notificationId,
            type = schedule.notificationType,
            title = when(schedule.notificationType) {
                NotificationType.WORKOUT_REMINDER -> "운동 시간입니다! 💪"
                NotificationType.ACHIEVEMENT -> "새로운 업적 달성! 🎉"
                NotificationType.STREAK -> "연속 운동 기록 갱신! 🔥"
                NotificationType.REST_DAY -> "오늘은 휴식일입니다 😌"
                NotificationType.RECOVERY_ALERT -> "근육 회복 완료! 🏃‍♂️"
                NotificationType.AI_MESSAGE -> "AI 트레이너의 조언 📝"
            },
            body = schedule.message,
            data = mutableMapOf(
                "scheduleId" to schedule.id.toString(),
                "scheduleName" to schedule.scheduleName,
                "type" to schedule.notificationType.name.lowercase()
            ),
            schedule = schedule
        )

        notificationHistoryRepository.save(history)

        // Save to chat history as well - AI 메시지인 경우 채팅 히스토리에도 저장
        if (schedule.notificationType == NotificationType.AI_MESSAGE) {
            val chatMessage = ChatMessage(
                user = schedule.user,
                userMessage = "[시스템 알림]", // 시스템이 자동으로 생성한 메시지임을 표시
                aiResponse = schedule.message,
                messageType = MessageType.TEXT,
                status = MessageStatus.COMPLETED
            )
            chatMessageRepository.save(chatMessage)
        }

        // Send the actual notification
        val request = PushNotificationRequest(
            userId = schedule.user.id,
            title = history.title,
            body = history.body,
            data = history.data
        )

        sendPushNotification(request)

        // Update next trigger time
        schedule.nextTriggerAt = calculateNextTrigger(schedule.days, schedule.time)
        notificationScheduleRepository.save(schedule)
    }

    private fun calculateNextTrigger(days: Set<DayOfWeek>, time: LocalTime): LocalDateTime {
        val now = LocalDateTime.now()
        val today = now.dayOfWeek
        val todayTime = now.toLocalTime()

        // Convert our DayOfWeek enum to Java DayOfWeek
        val javaDays = days.map { day ->
            when (day) {
                DayOfWeek.MON -> java.time.DayOfWeek.MONDAY
                DayOfWeek.TUE -> java.time.DayOfWeek.TUESDAY
                DayOfWeek.WED -> java.time.DayOfWeek.WEDNESDAY
                DayOfWeek.THU -> java.time.DayOfWeek.THURSDAY
                DayOfWeek.FRI -> java.time.DayOfWeek.FRIDAY
                DayOfWeek.SAT -> java.time.DayOfWeek.SATURDAY
                DayOfWeek.SUN -> java.time.DayOfWeek.SUNDAY
            }
        }.toSet()

        // If today is in the schedule and time hasn't passed, schedule for today
        if (javaDays.contains(today) && todayTime.isBefore(time)) {
            return now.toLocalDate().atTime(time)
        }

        // Find the next day in the schedule
        for (i in 1..7) {
            val nextDay = today.plus(i.toLong())
            if (javaDays.contains(nextDay)) {
                return now.toLocalDate().plusDays(i.toLong()).atTime(time)
            }
        }

        // This should never happen if days is not empty
        return now.plusDays(1).toLocalDate().atTime(time)
    }

    fun cleanupInactiveDevices() {
        val thirtyDaysAgo = LocalDateTime.now().minusDays(30)
        val inactiveDevices = notificationDeviceRepository.findInactiveDevices(thirtyDaysAgo)

        inactiveDevices.forEach { device ->
            device.isActive = false
            notificationDeviceRepository.save(device)
        }

        logger.info("Deactivated ${inactiveDevices.size} inactive devices")
    }
}
package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.notification.dto.*
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import com.richjun.liftupai.domain.notification.entity.NotificationSettings
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.notification.util.NotificationScheduleTimeCalculator
import com.richjun.liftupai.domain.notification.repository.NotificationDeviceRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.entity.*
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import com.richjun.liftupai.domain.notification.service.FcmNotificationService
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
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationDeviceRepository: NotificationDeviceRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository,
    @Autowired(required = false)
    private val fcmNotificationService: FcmNotificationService?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun registerDevice(userId: Long, request: RegisterDeviceRequest): RegisterDeviceResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val platform = try {
            DevicePlatform.valueOf(request.platform.uppercase())
        } catch (e: IllegalArgumentException) {
            return RegisterDeviceResponse(
                success = false,
                message = NotificationLocalization.message("notification.unsupported_platform", locale, request.platform)
            )
        }

        val existingDevice = notificationDeviceRepository.findByDeviceToken(request.deviceToken)

        val device = if (existingDevice.isPresent) {
            val dev = existingDevice.get()
            dev.isActive = true
            dev.lastUsedAt = AppTime.utcNow()
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
            message = NotificationLocalization.message("notification.device_registered", locale)
        )
    }

    @Transactional(readOnly = true)
    fun getNotificationSettings(userId: Long): NotificationSettingsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", resolveLocale(userId))) }

        val settings = notificationSettingsRepository.findByUser(user)
            .orElseGet { createDefaultSettings(user) }

        return toNotificationSettingsResponse(settings, userId)
    }

    fun updateNotificationSettings(userId: Long, request: UpdateNotificationSettingsRequest): UpdateNotificationSettingsResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

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

        settings.updatedAt = AppTime.utcNow()

        val saved = notificationSettingsRepository.save(settings)

        return UpdateNotificationSettingsResponse(
            success = true,
            settings = toNotificationSettingsResponse(saved, userId)
        )
    }

    fun sendPushNotification(request: PushNotificationRequest): PushNotificationResponse {
        val locale = resolveLocale(request.userId)
        val user = userRepository.findById(request.userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val activeDevices = notificationDeviceRepository.findActiveDevicesByUser(user)

        if (activeDevices.isEmpty()) {
            logger.warn("[FCM] No active devices for user ${request.userId} — notification skipped. title='${request.title}'")
            return PushNotificationResponse(
                success = false,
                sentCount = 0,
                failedCount = 0,
                message = NotificationLocalization.message("notification.no_active_devices", locale)
            )
        }

        logger.info("[FCM] Sending to ${activeDevices.size} device(s) for user ${request.userId}: title='${request.title}', data=${request.data}")

        var sentCount = 0
        var failedCount = 0

        activeDevices.forEach { device ->
            try {
                sendToDevice(device, request.title, request.body, request.data, request.imageUrl)
                sentCount++
            } catch (e: Exception) {
                logger.error("[FCM] Failed to send to device ${device.id} (${device.platform}): ${e.message}", e)
                failedCount++
            }
        }

        return PushNotificationResponse(
            success = sentCount > 0,
            sentCount = sentCount,
            failedCount = failedCount,
            message = NotificationLocalization.message("notification.sent", locale)
        )
    }

    fun sendWorkoutReminder(userId: Long) {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.workoutReminder) return

        val request = PushNotificationRequest(
            userId = userId,
            title = NotificationLocalization.message("notification.title.workout_reminder", locale),
            body = NotificationLocalization.message("notification.workout_reminder.body", locale),
            data = mapOf("type" to "workout_reminder")
        )

        sendPushNotification(request)
    }

    fun sendAchievementNotification(userId: Long, achievement: String) {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.achievements) return

        val request = PushNotificationRequest(
            userId = userId,
            title = NotificationLocalization.message("notification.title.achievement", locale),
            body = achievement,
            data = mapOf("type" to "achievement")
        )

        sendPushNotification(request)
    }

    fun sendRecoveryAlert(userId: Long, muscleGroup: String) {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val settings = notificationSettingsRepository.findByUser(user).orElse(null)
            ?: return

        if (!settings.recoveryAlerts) return

        val request = PushNotificationRequest(
            userId = userId,
            title = NotificationLocalization.message("notification.title.recovery", locale),
            body = NotificationLocalization.message("notification.recovery.body", locale, muscleGroup),
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

    private fun toNotificationSettingsResponse(settings: NotificationSettings, userId: Long): NotificationSettingsResponse {
        return NotificationSettingsResponse(
            workoutReminder = settings.workoutReminder,
            workoutReminderTime = settings.workoutReminderTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            timeZone = resolveTimeZone(userId),
            aiMessages = settings.aiMessages,
            achievements = settings.achievements,
            marketing = settings.marketing,
            dailyReport = settings.dailyReport,
            weeklyReport = settings.weeklyReport,
            socialUpdates = settings.socialUpdates,
            recoveryAlerts = settings.recoveryAlerts
        )
    }

    fun sendTestNotification(userId: Long): PushNotificationResponse {
        val locale = resolveLocale(userId)
        return sendPushNotification(
            PushNotificationRequest(
                userId = userId,
                title = NotificationLocalization.message("notification.test.title", locale),
                body = NotificationLocalization.message("notification.test.body", locale),
                data = mapOf("type" to "test")
            )
        )
    }

    private fun sendToDevice(
        device: NotificationDevice,
        title: String,
        body: String,
        data: Map<String, String>?,
        imageUrl: String?
    ) {
        if (fcmNotificationService == null) {
            throw IllegalStateException("[FCM] FcmNotificationService is NULL — Firebase not configured! Device: ${device.id}")
        }

        val sent = fcmNotificationService.sendNotification(device, title, body, data, imageUrl)
        if (!sent) {
            throw RuntimeException("[FCM] sendNotification returned false for device ${device.id} (${device.platform}), token=${device.deviceToken.take(20)}...")
        }
    }

    // Notification Scheduling Methods
    fun scheduleWorkoutReminder(userId: Long, request: WorkoutReminderRequest): ScheduleResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        try {
            val time = LocalTime.parse(request.time)
            val days = request.days.map { dayStr ->
                try {
                    DayOfWeek.valueOf(dayStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(NotificationLocalization.message("notification.invalid_day", locale, dayStr))
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
                nextTriggerAt = calculateNextTrigger(userId, days, time)
            )

            val savedSchedule = notificationScheduleRepository.save(schedule)

            return ScheduleResponse(
                scheduleId = savedSchedule.id.toString(),
                nextTriggerAt = AppTime.formatUtc(savedSchedule.nextTriggerAt),
                timeZone = resolveTimeZone(userId),
                status = "scheduled",
                created = true
            )
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException(NotificationLocalization.message("notification.invalid_time", locale, request.time))
        }
    }

    fun deleteWorkoutSchedule(userId: Long, scheduleId: Long): DeleteScheduleResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val schedule = notificationScheduleRepository.findByUserAndId(user, scheduleId)
            ?: throw ResourceNotFoundException(NotificationLocalization.message("notification.schedule_not_found", locale))

        notificationScheduleRepository.delete(schedule)

        return DeleteScheduleResponse(
            success = true,
            deletedScheduleId = scheduleId.toString(),
            message = NotificationLocalization.message("notification.schedule_deleted", locale)
        )
    }

    // Notification History Methods
    @Transactional(readOnly = true)
    fun getNotificationHistory(userId: Long, page: Int, limit: Int, unreadOnly: Boolean): NotificationHistoryResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

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
                createdAt = AppTime.formatUtcRequired(notification.createdAt),
                readAt = AppTime.formatUtc(notification.readAt)
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
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val notification = notificationHistoryRepository.findByUserAndNotificationId(user, notificationId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("notification.not_found", locale)) }

        if (!notification.isRead) {
            notification.isRead = true
            notification.readAt = AppTime.utcNow()
            notificationHistoryRepository.save(notification)
        }

        val unreadCount = notificationHistoryRepository.countUnreadByUser(user)

        return MarkAsReadResponse(
            success = true,
            notificationId = notificationId,
            readAt = AppTime.formatUtc(notification.readAt).orEmpty(),
            unreadCount = unreadCount
        )
    }

    // Enhanced notification sending with history tracking
    fun sendScheduledNotification(schedule: NotificationSchedule) {
        sendScheduledNotificationWithFcm(schedule)
    }

    /**
     * 스케줄 알림 발송 — history 저장 + FCM 전송 + nextTriggerAt 갱신
     * JPA @ElementCollection 프록시 문제를 피하기 위해 data를 일반 HashMap으로 복사
     */
    fun sendScheduledNotificationWithFcm(schedule: NotificationSchedule) {
        val locale = resolveLocale(schedule.user.id)
        val notificationId = UUID.randomUUID().toString()

        // FCM에 보낼 data를 일반 HashMap으로 먼저 구성 (JPA 관리 컬렉션 사용 X)
        val fcmData = hashMapOf(
            "scheduleId" to schedule.id.toString(),
            "scheduleName" to schedule.scheduleName,
            "type" to schedule.notificationType.name.lowercase()
        )
        val fcmTitle = NotificationLocalization.message(NotificationLocalization.titleKey(schedule.notificationType), locale)
        val fcmBody = schedule.message

        // Save to history
        val history = NotificationHistory(
            user = schedule.user,
            notificationId = notificationId,
            type = schedule.notificationType,
            title = fcmTitle,
            body = fcmBody,
            data = fcmData.toMutableMap(),
            schedule = schedule
        )

        notificationHistoryRepository.save(history)
        logger.info("[ScheduledNotification] History saved: id=$notificationId, type=${schedule.notificationType}, user=${schedule.user.id}")

        // Send FCM — 일반 HashMap 사용 (JPA 프록시가 아닌 순수 Map)
        val request = PushNotificationRequest(
            userId = schedule.user.id,
            title = fcmTitle,
            body = fcmBody,
            data = fcmData
        )

        val result = sendPushNotification(request)
        logger.info("[ScheduledNotification] FCM result: success=${result.success}, sent=${result.sentCount}, failed=${result.failedCount}, msg=${result.message}")

        // Update next trigger time
        schedule.nextTriggerAt = recalculateNextTrigger(schedule)
        notificationScheduleRepository.save(schedule)
        logger.info("[ScheduledNotification] Next trigger updated to ${schedule.nextTriggerAt} for schedule ${schedule.id}")
    }

    fun recalculateNextTrigger(schedule: NotificationSchedule): LocalDateTime {
        return NotificationScheduleTimeCalculator.calculateNextTrigger(
            days = schedule.days,
            time = schedule.time,
            timeZone = resolveTimeZone(schedule.user.id)
        )
    }

    fun refreshScheduleTimesForUser(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", resolveLocale(userId))) }

        notificationScheduleRepository.findByUser(user).forEach { schedule ->
            schedule.nextTriggerAt = if (schedule.enabled) recalculateNextTrigger(schedule) else null
            notificationScheduleRepository.save(schedule)
        }
    }

    fun cleanupInactiveDevices() {
        val thirtyDaysAgo = AppTime.utcNow().minusDays(30)
        val inactiveDevices = notificationDeviceRepository.findInactiveDevices(thirtyDaysAgo)

        inactiveDevices.forEach { device ->
            device.isActive = false
            notificationDeviceRepository.save(device)
        }

        logger.info("Deactivated ${inactiveDevices.size} inactive devices")
    }

    private fun calculateNextTrigger(userId: Long, days: Set<DayOfWeek>, time: LocalTime): LocalDateTime {
        return NotificationScheduleTimeCalculator.calculateNextTrigger(
            days = days,
            time = time,
            timeZone = resolveTimeZone(userId)
        )
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }

    private fun resolveTimeZone(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone ?: AppTime.DEFAULT_TIME_ZONE
    }
}

package com.richjun.liftupai.domain.notification.dto

import jakarta.validation.constraints.NotBlank

data class RegisterDeviceRequest(
    @field:NotBlank
    val deviceToken: String,

    @field:NotBlank
    val platform: String,

    val deviceName: String? = null,

    val appVersion: String? = null
)

data class RegisterDeviceResponse(
    val success: Boolean,
    val message: String? = null
)

data class NotificationSettingsResponse(
    val workoutReminder: Boolean,
    val workoutReminderTime: String?,
    val aiMessages: Boolean,
    val achievements: Boolean,
    val marketing: Boolean,
    val dailyReport: Boolean,
    val weeklyReport: Boolean,
    val socialUpdates: Boolean,
    val recoveryAlerts: Boolean
)

data class UpdateNotificationSettingsRequest(
    val workoutReminder: Boolean? = null,
    val workoutReminderTime: String? = null,
    val aiMessages: Boolean? = null,
    val achievements: Boolean? = null,
    val marketing: Boolean? = null,
    val dailyReport: Boolean? = null,
    val weeklyReport: Boolean? = null,
    val socialUpdates: Boolean? = null,
    val recoveryAlerts: Boolean? = null
)

data class UpdateNotificationSettingsResponse(
    val success: Boolean,
    val settings: NotificationSettingsResponse
)

data class PushNotificationRequest(
    val userId: Long,
    val title: String,
    val body: String,
    val data: Map<String, String>? = null,
    val imageUrl: String? = null
)

data class PushNotificationResponse(
    val success: Boolean,
    val sentCount: Int,
    val failedCount: Int,
    val message: String? = null
)

data class NotificationPayload(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val clickAction: String? = null,
    val data: Map<String, String>? = null
)

// Notification Scheduling DTOs
data class WorkoutReminderRequest(
    @field:NotBlank
    val scheduleName: String,

    val days: List<String>,

    @field:NotBlank
    val time: String,

    val enabled: Boolean = true,

    @field:NotBlank
    val message: String,

    val notificationType: String = "workout_reminder"
)

data class ScheduleResponse(
    val scheduleId: String,
    val nextTriggerAt: String?,
    val status: String,
    val created: Boolean
)

data class DeleteScheduleResponse(
    val success: Boolean,
    val deletedScheduleId: String,
    val message: String
)

// Notification History DTOs
data class NotificationHistoryItem(
    val notificationId: String,
    val type: String,
    val title: String,
    val body: String,
    val data: Map<String, String>?,
    val isRead: Boolean,
    val createdAt: String,
    val readAt: String?
)

data class NotificationHistoryResponse(
    val notifications: List<NotificationHistoryItem>,
    val pagination: PaginationInfo,
    val unreadCount: Long
)

data class PaginationInfo(
    val page: Int,
    val limit: Int,
    val total: Long,
    val hasNext: Boolean
)

data class MarkAsReadResponse(
    val success: Boolean,
    val notificationId: String,
    val readAt: String,
    val unreadCount: Long
)
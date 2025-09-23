package com.richjun.liftupai.global.util

import org.springframework.stereotype.Component

@Component
class ValidationUtil {

    companion object {
        private const val DEVICE_DOMAIN = "@device.liftup.ai"
        private val EMAIL_PATTERN = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\$")
        // 디바이스 ID 패턴: 알파벳, 숫자, 하이픈, 언더스코어, 점(.) 허용
        private val DEVICE_ID_PATTERN = Regex("^[a-zA-Z0-9._-]+\$")
    }

    fun isValidEmail(email: String): Boolean {
        // device.liftup.ai 도메인 허용
        if (email.endsWith(DEVICE_DOMAIN)) {
            return true
        }
        // 기존 이메일 검증 로직
        return EMAIL_PATTERN.matches(email)
    }

    fun isDeviceAccount(email: String?): Boolean {
        return email != null && email.endsWith(DEVICE_DOMAIN)
    }

    fun isValidDeviceId(deviceId: String?): Boolean {
        // 디바이스 ID는 최소 8자, 최대 255자
        if (deviceId == null || deviceId.length < 8 || deviceId.length > 255) {
            return false
        }
        // 알파벳, 숫자, 하이픈, 언더스코어, 점(.) 허용
        return DEVICE_ID_PATTERN.matches(deviceId)
    }

    fun extractDeviceIdFromEmail(email: String): String? {
        if (!isDeviceAccount(email)) {
            return null
        }
        return email.substringBefore(DEVICE_DOMAIN)
    }

    fun createDeviceEmail(deviceId: String): String {
        return "$deviceId$DEVICE_DOMAIN"
    }

    fun createDevicePassword(deviceId: String): String {
        return "Device_$deviceId"
    }
}
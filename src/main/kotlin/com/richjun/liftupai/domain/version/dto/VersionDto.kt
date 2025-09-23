package com.richjun.liftupai.domain.version.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

class VersionCheckRequest() {
    @field:NotBlank(message = "현재 버전은 필수입니다")
    lateinit var currentVersion: String

    @field:NotNull(message = "버전 코드는 필수입니다")
    var versionCode: Int = 0

    @field:NotBlank(message = "플랫폼은 필수입니다")
    lateinit var platform: String  // IOS, ANDROID, WEB

    var deviceId: String? = null
    var osVersion: String? = null

    constructor(
        currentVersion: String,
        versionCode: Int,
        platform: String,
        deviceId: String? = null,
        osVersion: String? = null
    ) : this() {
        this.currentVersion = currentVersion
        this.versionCode = versionCode
        this.platform = platform
        this.deviceId = deviceId
        this.osVersion = osVersion
    }
}

data class VersionCheckResponse(
    @JsonProperty("current_version")
    val currentVersion: String,

    @JsonProperty("latest_version")
    val latestVersion: String,

    @JsonProperty("latest_version_code")
    val latestVersionCode: Int,

    @JsonProperty("minimum_version")
    val minimumVersion: String?,

    @JsonProperty("minimum_version_code")
    val minimumVersionCode: Int?,

    @JsonProperty("update_required")
    val updateRequired: Boolean,

    @JsonProperty("update_type")
    val updateType: String,  // OPTIONAL, RECOMMENDED, REQUIRED

    @JsonProperty("update_url")
    val updateUrl: String?,

    @JsonProperty("release_notes")
    val releaseNotes: String?,

    @JsonProperty("maintenance_mode")
    val maintenanceMode: Boolean = false,

    @JsonProperty("maintenance_message")
    val maintenanceMessage: String? = null,

    @JsonProperty("features")
    val features: Map<String, Any>? = null
)

data class CreateVersionRequest(
    @field:NotBlank(message = "버전은 필수입니다")
    val version: String,

    @field:NotNull(message = "버전 코드는 필수입니다")
    val versionCode: Int,

    @field:NotBlank(message = "플랫폼은 필수입니다")
    val platform: String,

    val minimumVersion: String? = null,
    val minimumVersionCode: Int? = null,
    val forceUpdate: Boolean = false,
    val updateUrl: String? = null,
    val releaseNotes: String? = null,
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String? = null,
    val features: Map<String, Any>? = null
)

data class UpdateVersionRequest(
    val minimumVersion: String? = null,
    val minimumVersionCode: Int? = null,
    val forceUpdate: Boolean? = null,
    val updateUrl: String? = null,
    val releaseNotes: String? = null,
    val isActive: Boolean? = null,
    val maintenanceMode: Boolean? = null,
    val maintenanceMessage: String? = null,
    val features: Map<String, Any>? = null
)

data class VersionHistoryResponse(
    val id: Long,
    val version: String,
    val versionCode: Int,
    val platform: String,
    val releaseDate: LocalDateTime,
    val releaseNotes: String?,
    val isActive: Boolean
)
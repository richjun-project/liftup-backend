package com.richjun.liftupai.domain.version.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.version.dto.*
import com.richjun.liftupai.domain.version.entity.AppVersion
import com.richjun.liftupai.domain.version.entity.Platform
import com.richjun.liftupai.domain.version.entity.UpdateType
import com.richjun.liftupai.domain.version.repository.AppVersionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class AppVersionService(
    private val appVersionRepository: AppVersionRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 버전 체크 - 클라이언트가 업데이트가 필요한지 확인
     */
    @Transactional(readOnly = true)
    fun checkVersion(request: VersionCheckRequest): VersionCheckResponse {
        val platform = try {
            Platform.valueOf(request.platform.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: ${request.platform}")
        }

        // 최신 버전 조회
        val latestVersionList = appVersionRepository.findLatestByPlatform(platform)
        if (latestVersionList.isEmpty()) {
            throw ResourceNotFoundException("해당 플랫폼의 버전 정보를 찾을 수 없습니다")
        }
        val latestVersion = latestVersionList.first()

        // 점검 모드 확인
        val maintenanceInfo = appVersionRepository.findMaintenanceInfo(platform).firstOrNull()
        if (maintenanceInfo != null && maintenanceInfo.maintenanceMode) {
            return createMaintenanceResponse(request, latestVersion, maintenanceInfo)
        }

        // 현재 버전이 최신인지 확인
        val isLatest = request.versionCode >= latestVersion.versionCode

        // 최소 필수 버전 확인
        val minimumVersionCode = latestVersion.minimumVersionCode ?: 0
        val updateRequired = request.versionCode < minimumVersionCode

        // 업데이트 타입 결정
        val updateType = when {
            updateRequired -> UpdateType.REQUIRED
            !isLatest && latestVersion.forceUpdate -> UpdateType.REQUIRED
            !isLatest -> UpdateType.RECOMMENDED
            else -> UpdateType.OPTIONAL
        }

        // 기능 플래그 파싱
        val features = latestVersion.features?.let {
            try {
                objectMapper.readValue(it, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                logger.error("Failed to parse features JSON", e)
                null
            }
        }

        return VersionCheckResponse(
            currentVersion = request.currentVersion,
            latestVersion = latestVersion.version,
            latestVersionCode = latestVersion.versionCode,
            minimumVersion = latestVersion.minimumVersion,
            minimumVersionCode = latestVersion.minimumVersionCode,
            updateRequired = updateRequired,
            updateType = updateType.name,
            updateUrl = latestVersion.updateUrl,
            releaseNotes = latestVersion.releaseNotes,
            maintenanceMode = false,
            maintenanceMessage = null,
            features = features
        )
    }

    /**
     * 새 버전 등록
     */
    fun createVersion(request: CreateVersionRequest): AppVersion {
        val platform = try {
            Platform.valueOf(request.platform.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: ${request.platform}")
        }

        // 중복 체크
        appVersionRepository.findByVersionAndPlatform(request.version, platform).ifPresent {
            throw IllegalArgumentException("이미 등록된 버전입니다: ${request.version}")
        }

        val appVersion = AppVersion(
            version = request.version,
            versionCode = request.versionCode,
            platform = platform,
            minimumVersion = request.minimumVersion,
            minimumVersionCode = request.minimumVersionCode,
            forceUpdate = request.forceUpdate,
            updateUrl = request.updateUrl,
            releaseNotes = request.releaseNotes,
            maintenanceMode = request.maintenanceMode,
            maintenanceMessage = request.maintenanceMessage,
            features = request.features?.let { objectMapper.writeValueAsString(it) }
        )

        return appVersionRepository.save(appVersion)
    }

    /**
     * 버전 정보 업데이트
     */
    fun updateVersion(id: Long, request: UpdateVersionRequest): AppVersion {
        val appVersion = appVersionRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("버전 정보를 찾을 수 없습니다") }

        // 수정 가능한 필드만 업데이트
        request.minimumVersion?.let { appVersion.minimumVersion = it }
        request.minimumVersionCode?.let { appVersion.minimumVersionCode = it }
        request.forceUpdate?.let { appVersion.forceUpdate = it }
        request.updateUrl?.let { appVersion.updateUrl = it }
        request.releaseNotes?.let { appVersion.releaseNotes = it }
        request.isActive?.let { appVersion.isActive = it }
        request.maintenanceMode?.let { appVersion.maintenanceMode = it }
        request.maintenanceMessage?.let { appVersion.maintenanceMessage = it }
        request.features?.let { appVersion.features = objectMapper.writeValueAsString(it) }

        appVersion.updatedAt = LocalDateTime.now()

        return appVersionRepository.save(appVersion)
    }

    /**
     * 버전 히스토리 조회
     */
    @Transactional(readOnly = true)
    fun getVersionHistory(platform: String): List<VersionHistoryResponse> {
        val platformEnum = try {
            Platform.valueOf(platform.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: $platform")
        }

        val versions = appVersionRepository.findAllByPlatformInAndIsActiveOrderByVersionCodeDesc(
            listOf(platformEnum, Platform.ALL),
            true
        )

        return versions.map { version ->
            VersionHistoryResponse(
                id = version.id,
                version = version.version,
                versionCode = version.versionCode,
                platform = version.platform.name,
                releaseDate = version.releaseDate,
                releaseNotes = version.releaseNotes,
                isActive = version.isActive
            )
        }
    }

    /**
     * 특정 버전 정보 조회
     */
    @Transactional(readOnly = true)
    fun getVersion(id: Long): AppVersion {
        return appVersionRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("버전 정보를 찾을 수 없습니다") }
    }

    /**
     * 버전 삭제 (소프트 삭제)
     */
    fun deactivateVersion(id: Long) {
        val appVersion = appVersionRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("버전 정보를 찾을 수 없습니다") }

        appVersion.isActive = false
        appVersion.updatedAt = LocalDateTime.now()

        appVersionRepository.save(appVersion)
    }

    /**
     * 점검 모드 설정
     */
    fun setMaintenanceMode(platform: String, enabled: Boolean, message: String? = null) {
        val platformEnum = try {
            Platform.valueOf(platform.uppercase())
        } catch (e: Exception) {
            Platform.ALL  // 전체 플랫폼 점검
        }

        val latestVersionList = appVersionRepository.findLatestByPlatform(platformEnum)
        if (latestVersionList.isEmpty()) {
            throw ResourceNotFoundException("버전 정보를 찾을 수 없습니다")
        }
        val latestVersion = latestVersionList.first()

        latestVersion.maintenanceMode = enabled
        latestVersion.maintenanceMessage = if (enabled) message ?: "서버 점검 중입니다. 잠시 후 다시 시도해주세요." else null
        latestVersion.updatedAt = LocalDateTime.now()

        appVersionRepository.save(latestVersion)
    }

    private fun createMaintenanceResponse(
        request: VersionCheckRequest,
        latestVersion: AppVersion,
        maintenanceInfo: AppVersion
    ): VersionCheckResponse {
        return VersionCheckResponse(
            currentVersion = request.currentVersion,
            latestVersion = latestVersion.version,
            latestVersionCode = latestVersion.versionCode,
            minimumVersion = latestVersion.minimumVersion,
            minimumVersionCode = latestVersion.minimumVersionCode,
            updateRequired = false,
            updateType = UpdateType.OPTIONAL.name,
            updateUrl = null,
            releaseNotes = null,
            maintenanceMode = true,
            maintenanceMessage = maintenanceInfo.maintenanceMessage
                ?: "서버 점검 중입니다. 잠시 후 다시 시도해주세요.",
            features = null
        )
    }
}
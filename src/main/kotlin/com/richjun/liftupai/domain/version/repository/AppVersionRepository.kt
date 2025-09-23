package com.richjun.liftupai.domain.version.repository

import com.richjun.liftupai.domain.version.entity.AppVersion
import com.richjun.liftupai.domain.version.entity.Platform
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface AppVersionRepository : JpaRepository<AppVersion, Long> {

    fun findByVersionAndPlatform(version: String, platform: Platform): Optional<AppVersion>

    fun findByVersionCodeAndPlatform(versionCode: Int, platform: Platform): Optional<AppVersion>

    @Query("""
        SELECT av FROM AppVersion av
        WHERE av.platform IN (:platform, 'ALL')
        AND av.isActive = true
        ORDER BY av.versionCode DESC
    """)
    fun findLatestByPlatform(@Param("platform") platform: Platform): List<AppVersion>

    @Query("""
        SELECT av FROM AppVersion av
        WHERE av.platform IN (:platform, 'ALL')
        AND av.isActive = true
        AND av.forceUpdate = true
        ORDER BY av.versionCode DESC
    """)
    fun findLatestRequiredVersion(@Param("platform") platform: Platform): List<AppVersion>

    @Query("""
        SELECT av FROM AppVersion av
        WHERE av.platform IN (:platform, 'ALL')
        AND av.isActive = true
        AND av.maintenanceMode = true
    """)
    fun findMaintenanceInfo(@Param("platform") platform: Platform): List<AppVersion>

    fun findAllByPlatformAndIsActiveOrderByVersionCodeDesc(
        platform: Platform,
        isActive: Boolean
    ): List<AppVersion>

    fun findAllByPlatformInAndIsActiveOrderByVersionCodeDesc(
        platforms: List<Platform>,
        isActive: Boolean
    ): List<AppVersion>
}
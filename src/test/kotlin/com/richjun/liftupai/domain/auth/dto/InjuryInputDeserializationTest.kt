package com.richjun.liftupai.domain.auth.dto

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 앱(scripted_onboarding_screen)은 부상을 {"body_part":"knee","severity":null} 로 보낸다.
 * 이 형식이 500 없이 파싱돼야 부상 사용자가 온보딩을 끝낼 수 있다.
 */
class InjuryInputDeserializationTest {
    private val mapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `severity 가 null 이어도 파싱되고 moderate 로 보정된다`() {
        val r: DeviceRegisterRequest = mapper.readValue(
            """{"device_id":"d","nickname":"테스터","injuries":[{"body_part":"knee","severity":null}]}"""
        )
        assertEquals("knee", r.injuries!!.single().bodyPart)
        assertEquals("moderate", r.injuries!!.single().effectiveSeverity)
    }

    @Test
    fun `severity 가 아예 없어도 파싱된다`() {
        val r: DeviceRegisterRequest = mapper.readValue(
            """{"device_id":"d","nickname":"테스터","injuries":[{"body_part":"lower_back"}]}"""
        )
        assertEquals("moderate", r.injuries!!.single().effectiveSeverity)
    }

    @Test
    fun `severity 를 주면 그대로 쓴다`() {
        val r: DeviceRegisterRequest = mapper.readValue(
            """{"device_id":"d","nickname":"테스터","injuries":[{"body_part":"knee","severity":"severe"}]}"""
        )
        assertEquals("severe", r.injuries!!.single().effectiveSeverity)
    }
}

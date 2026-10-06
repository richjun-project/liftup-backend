package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.config.TestConfig
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import com.richjun.liftupai.domain.workout.repository.TemplateDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.TemplateDayRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutPlanTemplateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 프리셋 템플릿 시드(PresetTemplates → DB)와 추천 서비스의 통합 검증.
 * 운영 DB 에는 기존 8개가 이미 있으므로, 부트스트랩은 멱등이어야 하고 새 코드만 추가해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig::class)
class TemplateBootstrapIntegrationTest {

    @Autowired private lateinit var bootstrap: TemplateBootstrapService
    @Autowired private lateinit var templateRepository: WorkoutPlanTemplateRepository
    @Autowired private lateinit var dayRepository: TemplateDayRepository
    @Autowired private lateinit var dayExerciseRepository: TemplateDayExerciseRepository
    @Autowired private lateinit var recommendationService: TemplateRecommendationService

    private fun presets() = templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)

    @Test
    fun `모든 프리셋 템플릿이 시드되고 종목이 빠짐없이 들어간다`() {
        val seeded = presets().associateBy { it.code }
        assertEquals(PresetTemplates.ALL.map { it.code }.toSet(), seeded.keys)
        PresetTemplates.ALL.forEach { def ->
            val t = seeded.getValue(def.code)
            val days = dayRepository.findByTemplateIdOrderByDayNumber(t.id)
            assertEquals(def.totalDays, days.size, def.code)
            days.forEach { d ->
                val expected = def.days.first { it.dayNumber == d.dayNumber }.exercises.size
                assertEquals(expected, dayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(d.id).size, "${def.code} day ${d.dayNumber}")
            }
        }
    }

    @Test
    fun `부트스트랩을 다시 돌려도 기존 템플릿은 그대로다 (운영 DB 멱등)`() {
        val before = presets().associate { it.code to it.id }
        val exerciseRowsBefore = presets().sumOf { t -> dayRepository.findByTemplateIdOrderByDayNumber(t.id).sumOf { dayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(it.id).size } }
        bootstrap.run(DefaultApplicationArguments())
        val after = presets().associate { it.code to it.id }
        assertEquals(before, after)
        val exerciseRowsAfter = presets().sumOf { t -> dayRepository.findByTemplateIdOrderByDayNumber(t.id).sumOf { dayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(it.id).size } }
        assertEquals(exerciseRowsBefore, exerciseRowsAfter)
    }

    @Test
    fun `서비스 추천 - 홈트 초보 일본어 사용자는 바벨 서킷이 아니고 이유가 현지화된다`() {
        val user = User(id = 207L, email = "ja@example.com", nickname = "チキンレッグ")
        val request = RecommendTemplateRequest(
            experienceLevel = "beginner",
            goals = listOf("weight_loss", "thick_legs", "glutes"),
            weeklyDays = 3, sessionDuration = 45,
            equipment = listOf("DUMBBELL", "BODYWEIGHT"),
            injuries = emptyList(), focusAreas = listOf("legs", "glutes"),
        )
        val ja = recommendationService.recommend(user, request, "ja")
        assertNotEquals("diet_circuit", ja.templateCode)
        assertTrue(ja.reason.contains("日"), ja.reason)
        assertEquals(3, ja.alternatives.size)
        assertTrue(ja.templateCode !in ja.alternatives)
        val ko = recommendationService.recommend(user, request, "ko")
        assertEquals(ja.templateCode, ko.templateCode)
        assertTrue(ko.reason.contains("추천"), ko.reason)
    }

    @Test
    fun `서비스 추천 - 입력이 비어 있어도 기본값으로 동작한다`() {
        val user = User(id = 1L, email = "x@example.com", nickname = "x")
        val out = recommendationService.recommend(user, RecommendTemplateRequest(), null)
        assertTrue(out.templateCode.isNotBlank())
    }
}

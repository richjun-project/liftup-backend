package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.config.TestConfig
import com.richjun.liftupai.domain.workout.dto.ExerciseDetailV2
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogDetailService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles

/**
 * 검색 인덱스를 실제 리포지토리 쿼리(JPQL)로 DB 에서 만들고 목록 API 경로까지 통과하는지 본다.
 * 단위 테스트는 카탈로그 JSON 으로 엔진만 검증하므로, 운영에서 첫 요청 때 실행되는 DB 빌드 경로는 여기서만 확인된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig::class)
class ExerciseSearchIndexIntegrationTest {

    @Autowired private lateinit var indexService: ExerciseSearchIndexService
    @Autowired private lateinit var catalogService: ExerciseCatalogDetailService
    @Autowired private lateinit var exerciseRepository: ExerciseRepository

    @Suppress("UNCHECKED_CAST")
    private fun page(category: String?, equipment: String?, search: String?, locale: String) =
        catalogService.getExercisesV2Paged(category, equipment, false, locale, search, PageRequest.of(0, 20))["content"] as List<ExerciseDetailV2>

    @Test
    fun `하체 목록 첫 페이지는 ESSENTIAL 운동부터 나온다 (알파벳순 아님)`() {
        val ids = indexService.searchIds(null, ExerciseCategory.LEGS, null, "ja")
        assertTrue(ids.size > 100, "LEGS 운동이 인덱스에 들어와야 한다: ${ids.size}")
        val first = exerciseRepository.findAllById(ids.take(5)).associateBy { it.id }
        ids.take(5).forEach { assertEquals(RecommendationTier.ESSENTIAL, first.getValue(it).recommendationTier) }
    }

    @Test
    fun `히라가나 검색과 쉼표 장비 필터가 DB 경로에서도 동작한다`() {
        val legPress = page(null, null, "れっぐぷれす", "ja")
        assertTrue(legPress.first().name.contains("レッグプレス"), legPress.first().name)

        val home = page("LEGS", "DUMBBELL,BODYWEIGHT", null, "ko")
        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.equipmentCode == "DUMBBELL" || it.equipmentCode == "BODYWEIGHT" }, home.map { it.equipmentCode }.toString())
        assertEquals("ESSENTIAL", home.first().tier)
    }
}

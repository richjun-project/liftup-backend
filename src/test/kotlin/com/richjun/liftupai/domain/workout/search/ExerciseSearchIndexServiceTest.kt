package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExerciseSearchIndexServiceTest {

    private fun exercise(id: Long, slug: String, name: String, tier: RecommendationTier, popularity: Int) = Exercise(
        id = id, slug = slug, name = name, category = ExerciseCategory.LEGS, equipment = Equipment.BARBELL,
        muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS), popularity = popularity, recommendationTier = tier
    )

    private val exerciseRepository = mockk<ExerciseRepository>()
    private val translationRepository = mockk<ExerciseTranslationRepository>()
    private val service = ExerciseSearchIndexService(exerciseRepository, translationRepository)

    private fun stub() {
        every { exerciseRepository.findAllWithMuscleGroups() } returns listOf(
            exercise(1, "4-corners-curtsy", "4 Corners Curtsy", RecommendationTier.SPECIALIZED, 20),
            exercise(2, "barbell-full-squat", "Barbell full squat", RecommendationTier.ESSENTIAL, 98),
            exercise(3, "leg-extension", "Leg extension", RecommendationTier.STANDARD, 55)
        )
        every { translationRepository.findAllNameRows() } returns listOf(
            arrayOf<Any>(2L, "ko", "바벨 풀 스쿼트"),
            arrayOf<Any>(2L, "en", "Barbell full squat"),
            arrayOf<Any>(1L, "ko", "4 코너 커시")
        )
    }

    @Test
    fun `DB 운동과 번역으로 인덱스를 만들어 검색한다`() {
        stub()
        assertEquals(listOf(2L), service.searchIds("스쿼트", null, null, "ko"))
        assertEquals(listOf(2L, 3L, 1L), service.searchIds(null, ExerciseCategory.LEGS, null, "ko"))
    }

    @Test
    fun `인덱스는 캐시되고 카탈로그 변경 이벤트로 다시 만들어진다`() {
        stub()
        service.searchIds("squat", null, null, "en")
        service.searchIds("squat", null, null, "en")
        verify(exactly = 1) { exerciseRepository.findAllWithMuscleGroups() }

        service.invalidate()
        service.searchIds("squat", null, null, "en")
        verify(exactly = 2) { exerciseRepository.findAllWithMuscleGroups() }
    }

    @Test
    fun `노출 가능한 목록은 SPECIALIZED 를 뺀다`() {
        stub()
        assertEquals(listOf(2L, 3L), service.listableIds(null, "ko"))
    }
}

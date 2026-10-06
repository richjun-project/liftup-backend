package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.dto.ExerciseDetailV2
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.global.exception.BadRequestException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest

class ExerciseCatalogDetailServicePagingTest {

    private fun exercise(
        id: Long, slug: String, name: String, equipment: Equipment, tier: RecommendationTier,
        popularity: Int, difficulty: Int, category: ExerciseCategory = ExerciseCategory.LEGS
    ) = Exercise(
        id = id, slug = slug, name = name, category = category, equipment = equipment,
        muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES),
        popularity = popularity, difficulty = difficulty, recommendationTier = tier
    )

    private val curtsy = exercise(1, "4-corners-curtsy", "4 Corners Curtsy", Equipment.BODYWEIGHT, RecommendationTier.SPECIALIZED, 20, 20)
    private val squat = exercise(2, "barbell-full-squat", "Barbell full squat", Equipment.BARBELL, RecommendationTier.ESSENTIAL, 98, 55)
    private val bodyweightSquat = exercise(3, "bodyweight-squat", "Bodyweight squat", Equipment.BODYWEIGHT, RecommendationTier.STANDARD, 80, 50)
    private val snatch = exercise(4, "snatch", "Snatch", Equipment.BARBELL, RecommendationTier.STANDARD, 55, 80)
    private val all = listOf(curtsy, squat, bodyweightSquat, snatch)

    private val exerciseRepository = mockk<ExerciseRepository>().also { repo ->
        every { repo.findAllWithMuscleGroups() } returns all
        every { repo.findAllById(any<Iterable<Long>>()) } answers { firstArg<Iterable<Long>>().mapNotNull { id -> all.find { it.id == id } } }
    }
    private val translationRepository = mockk<ExerciseTranslationRepository>().also {
        every { it.findAllNameRows() } returns emptyList()
    }
    private val sharedSupport = mockk<WorkoutV2SharedSupport>().also { s ->
        every { s.resolveLocale(any(), any()) } returns "en"
        every { s.translationMap(any(), any()) } returns emptyMap()
        every { s.localizedName(any(), any(), any()) } answers { firstArg<Exercise>().name }
        every { s.localizedInstructions(any(), any(), any()) } returns null
    }

    private val service = ExerciseCatalogDetailService(
        userRepository = mockk(relaxed = true),
        exerciseRepository = exerciseRepository,
        personalRecordRepository = mockk(relaxed = true),
        workoutSessionRepository = mockk(relaxed = true),
        workoutExerciseRepository = mockk(relaxed = true),
        exercisePatternClassifier = mockk(relaxed = true),
        exerciseCatalogLocalizationService = mockk(relaxed = true),
        sharedSupport = sharedSupport,
        exerciseSearchIndexService = ExerciseSearchIndexService(exerciseRepository, translationRepository)
    )

    @Suppress("UNCHECKED_CAST")
    private fun content(result: Map<String, Any>) = result["content"] as List<ExerciseDetailV2>

    @Test
    fun `검색어가 없으면 티어 인기도 순이고 희귀 운동은 뒤로 간다`() {
        val result = service.getExercisesV2Paged("LEGS", null, false, "en", null, PageRequest.of(0, 20))
        assertEquals(listOf("Barbell full squat", "Bodyweight squat", "Snatch", "4 Corners Curtsy"), content(result).map { it.name })
        assertEquals(4L, result["totalElements"])
        assertEquals(true, result["last"])
    }

    @Test
    fun `장비는 쉼표로 구분한 목록을 받는다`() {
        val result = service.getExercisesV2Paged("LEGS", "BODYWEIGHT,BARBELL", false, "en", null, PageRequest.of(0, 20))
        assertEquals(4, content(result).size)
        val single = service.getExercisesV2Paged("LEGS", "bodyweight", false, "en", null, PageRequest.of(0, 20))
        assertEquals(listOf("Bodyweight squat", "4 Corners Curtsy"), content(single).map { it.name })
    }

    @Test
    fun `새 필드와 실제 난이도를 내려준다`() {
        val items = content(service.getExercisesV2Paged(null, null, false, "en", "squat", PageRequest.of(0, 20)))
        val squatItem = items.first { it.id == 2L }
        assertEquals("ESSENTIAL", squatItem.tier)
        assertEquals("BARBELL", squatItem.equipmentCode)
        assertEquals(listOf("GLUTES", "QUADRICEPS"), squatItem.muscleGroupCodes)
        assertEquals(98, squatItem.popularity)
        assertEquals("Intermediate", squatItem.difficulty)
        val snatchItem = service.getExercisesV2Paged(null, null, false, "en", "snatch", PageRequest.of(0, 20))
            .let { content(it).first() }
        assertEquals("Advanced", snatchItem.difficulty)
        val curtsyItem = content(service.getExercisesV2Paged(null, null, false, "en", "curtsy", PageRequest.of(0, 20))).first()
        assertEquals("Beginner", curtsyItem.difficulty)
    }

    @Test
    fun `페이지를 넘기면 다음 구간을 준다`() {
        val page1 = service.getExercisesV2Paged("LEGS", null, false, "en", null, PageRequest.of(1, 3))
        assertEquals(listOf("4 Corners Curtsy"), content(page1).map { it.name })
        assertEquals(2, page1["totalPages"])
        assertEquals(true, page1["last"])
        val beyond = service.getExercisesV2Paged("LEGS", null, false, "en", null, PageRequest.of(5, 3))
        assertEquals(emptyList<ExerciseDetailV2>(), content(beyond))
    }

    @Test
    fun `잘못된 장비나 카테고리는 400 이다`() {
        assertThrows(BadRequestException::class.java) {
            service.getExercisesV2Paged(null, "SPACESHIP", false, "en", null, PageRequest.of(0, 20))
        }
        assertThrows(BadRequestException::class.java) {
            service.getExercisesV2Paged("NOPE", null, false, "en", null, PageRequest.of(0, 20))
        }
    }

    @Test
    fun `기존 비페이징 경로도 동작한다`() {
        every { exerciseRepository.findByCategory(ExerciseCategory.LEGS) } returns all
        val list = service.getExercisesV2("LEGS", "BARBELL", false, "en")
        assertEquals(listOf("Barbell full squat", "Snatch"), list.map { it.name })
    }
}

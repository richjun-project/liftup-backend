package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** 대체 운동: 같은 패턴·근육 우선, 이름순 30개 후보 한계 제거, 사용자 장비·부상 존중 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubstituteRankerTest {

    private lateinit var catalog: List<Exercise>
    private lateinit var bySlug: Map<String, Exercise>
    private val service = RecoFitEvalTest.injuryService()

    @BeforeAll
    fun load() {
        catalog = RecoFitEvalTest.loadExercises()
        bySlug = catalog.associateBy { it.slug }
    }

    private fun rank(slug: String, equipment: Set<String> = emptySet(), injuries: Set<String> = emptySet(), curated: Set<Long> = emptySet(), limit: Int = 10) =
        SubstituteRanker.rank(
            original = bySlug.getValue(slug), pool = catalog, curatedIds = curated,
            isAllowed = { TemplatePlanAdapter.equipmentAllowed(it, equipment) && !(injuries.isNotEmpty() && service.isRestrictedByRule(it, injuries)) },
            limit = limit,
        )

    @Test
    fun `벤치프레스 대체는 같은 밀기 패턴과 대표 운동이 앞에 온다`() {
        val out = rank("barbell-bench-press").map { it.exercise }
        assertEquals(10, out.size)
        assertTrue(out.take(5).any { it.slug == "dumbbell-bench-press" }, out.map { it.slug }.toString())
        assertTrue(out.take(5).all { it.category.name == "CHEST" })
    }

    @Test
    fun `덤벨만 있는 사용자의 대체에는 바벨·머신이 없다`() {
        val out = rank("barbell-bench-press", equipment = setOf("DUMBBELL")).map { it.exercise }
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.equipment?.name in setOf("DUMBBELL", "BODYWEIGHT", "OTHER", null) }, out.map { it.slug }.toString())
        assertTrue(out.take(3).any { it.slug == "dumbbell-bench-press" }, out.map { it.slug }.toString())
    }

    @Test
    fun `어깨 부상 사용자의 대체에는 어깨를 쓰는 운동이 없다`() {
        val out = rank("barbell-bench-press", injuries = setOf("shoulder")).map { it.exercise }
        assertTrue(out.none { service.isRestrictedByRule(it, setOf("shoulder")) })
    }

    @Test
    fun `자기 자신과 특수 티어는 대체로 나오지 않는다`() {
        val out = rank("barbell-full-squat").map { it.exercise }
        assertTrue(out.none { it.slug == "barbell-full-squat" })
        assertTrue(out.none { it.recommendationTier.name == "SPECIALIZED" })
    }

    @Test
    fun `큐레이션 목록의 운동은 가산점을 받아 앞으로 온다`() {
        val curated = bySlug.getValue("leg-press-machine-normal-stance")
        val plain = rank("barbell-full-squat", limit = 200).map { it.exercise.id }
        val boosted = rank("barbell-full-squat", curated = setOf(curated.id), limit = 200).map { it.exercise.id }
        assertTrue(curated.id in plain && curated.id in boosted)
        assertTrue(boosted.indexOf(curated.id) <= plain.indexOf(curated.id))
    }
}

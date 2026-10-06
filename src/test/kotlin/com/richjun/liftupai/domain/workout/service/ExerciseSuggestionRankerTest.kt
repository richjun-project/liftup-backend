package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.service.ExerciseSuggestionRanker.Input
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 운동 추가 화면 개인화 추천. 2026-10-06 실사용: 하체 카테고리를 이름순으로 5페이지까지 넘기다 이탈 —
 * 첫 화면에 "4 Corners Curtsy" 같은 희귀 운동이 나오고 레그프레스·스쿼트는 묻혀 있었다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExerciseSuggestionRankerTest {

    private lateinit var catalog: List<Exercise>
    private val service = RecoFitEvalTest.injuryService()

    @BeforeAll
    fun load() {
        catalog = RecoFitEvalTest.loadExercises()
    }

    private fun rank(input: Input, injuries: Set<String> = emptySet()) =
        ExerciseSuggestionRanker.rank(catalog, { injuries.isNotEmpty() && service.isRestrictedByRule(it, injuries) }, input)

    @Test
    fun `하체 첫 화면은 대표 운동이고 희귀 운동이 아니다`() {
        val top = rank(Input(category = ExerciseCategory.LEGS, limit = 10)).map { it.exercise }
        assertEquals(10, top.size)
        assertTrue(top.take(5).all { it.recommendationTier == RecommendationTier.ESSENTIAL }, top.map { it.slug }.toString())
        assertTrue(top.none { it.recommendationTier == RecommendationTier.SPECIALIZED })
        assertFalse(top.any { it.slug.startsWith("4-") }, "이름순 첫 화면의 희귀 운동이 올라왔다")
    }

    @Test
    fun `보유 장비로 할 수 있는 운동만 - 덤벨·맨몸 사용자에게 바벨·머신이 없다`() {
        val out = rank(Input(category = ExerciseCategory.LEGS, allowedEquipment = setOf("DUMBBELL", "BODYWEIGHT"), limit = 20))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.exercise.equipment?.name in setOf("DUMBBELL", "BODYWEIGHT", "OTHER", null) }, out.map { it.exercise.slug }.toString())
    }

    @Test
    fun `저장된 장비가 없으면 장비 제한이 없다`() {
        val out = rank(Input(category = ExerciseCategory.LEGS, limit = 10))
        assertTrue(out.any { it.exercise.equipment?.name == "BARBELL" || it.exercise.equipment?.name == "MACHINE" })
    }

    @Test
    fun `무릎 부상이면 무릎을 쓰는 하체 운동을 제안하지 않는다`() {
        val out = rank(Input(category = ExerciseCategory.LEGS, limit = 30), setOf("knee"))
        assertTrue(out.none { service.isRestrictedByRule(it.exercise, setOf("knee")) })
        assertTrue(out.none { it.exercise.movementPattern in setOf("SQUAT", "LUNGE", "LEG_PRESS", "LEG_CURL") })
        // 힙쓰러스트·브릿지 같은 무릎 부담이 적은 둔근 운동은 남는다
        assertTrue(out.any { it.exercise.movementPattern == "GLUTE_FOCUSED" })
    }

    @Test
    fun `제외한 id 는 나오지 않고 limit 를 지킨다`() {
        val first = rank(Input(category = ExerciseCategory.CHEST, limit = 5)).map { it.exercise.id }
        val second = rank(Input(category = ExerciseCategory.CHEST, excludeIds = first.toSet(), limit = 5)).map { it.exercise.id }
        assertEquals(5, second.size)
        assertTrue(first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `부족한 근육을 주면 그 근육을 쓰는 운동이 앞에 온다`() {
        val out = rank(Input(muscles = setOf(MuscleGroup.TRICEPS), limit = 10)).map { it.exercise }
        assertTrue(out.all { MuscleGroup.TRICEPS in it.muscleGroups })
        assertTrue(out.take(5).any { it.category == ExerciseCategory.ARMS })
        assertEquals(ExerciseSuggestionRanker.Why.MUSCLE, rank(Input(muscles = setOf(MuscleGroup.TRICEPS), limit = 3)).first().why)
    }

    @Test
    fun `초보에게는 난이도 높은 동작을 제안하지 않는다`() {
        val beginner = rank(Input(category = ExerciseCategory.BACK, experience = "beginner", limit = 30)).map { it.exercise }
        assertTrue(beginner.all { it.difficulty <= 60 })
        assertTrue(beginner.none { it.slug == "barbell-deadlift" }, "초보에게 데드리프트가 제안됐다")
        val advanced = rank(Input(category = ExerciseCategory.BACK, experience = "advanced", limit = 30)).map { it.exercise }
        assertTrue(advanced.any { it.slug == "barbell-deadlift" })
    }

    @Test
    fun `같은 동작 패턴이 상위를 도배하지 않는다`() {
        val top = rank(Input(category = ExerciseCategory.ARMS, limit = 10)).map { it.exercise.movementPattern }
        val maxSame = top.groupingBy { it }.eachCount().values.max()
        assertTrue(maxSame <= 5, "같은 패턴이 $maxSame 개: $top")
    }

    @Test
    fun `집중 부위 목표가 있으면 그 부위 운동에 가산점이 붙는다`() {
        val withFocus = rank(Input(focus = listOf(FocusArea.GLUTES), limit = 15)).map { it.exercise }
        val without = rank(Input(limit = 15)).map { it.exercise }
        assertTrue(withFocus.count { FocusArea.GLUTES.hits(it) } > without.count { FocusArea.GLUTES.hits(it) })
    }
}

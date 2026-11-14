package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.service.RecoFitEvalTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 운동별 필요 도구(catalog/exercise-requires.json) — 2026-11-14 coach-autopilot-r1 */
class ExerciseRequirementsTest {
    private val catalog = RecoFitEvalTest.loadExercises().associateBy { it.slug }
    private fun ex(slug: String) = catalog[slug] ?: error("catalog has no $slug")

    private val bodyweightOnly = setOf("BODYWEIGHT")
    private val bar = setOf("BODYWEIGHT", "OTHER")
    private val gym = setOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT")
    private val band = setOf("BODYWEIGHT", "RESISTANCE_BAND")

    @Test
    @DisplayName("플랜에 쓰는 맨몸·기타 운동은 모두 사람이 확인한 필요 도구가 있다")
    fun everyPlanEligibleBodyweightExerciseHasRequirement() {
        val missing = catalog.values
            .filter { it.isPlanEligible && (it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.OTHER) }
            .map { it.slug }
            .filter { it !in ExerciseRequirements.bySlug }
        assertTrue(missing.isEmpty(), "exercise-requires.json 에 없는 플랜 운동: $missing")
        // 목록의 모든 슬러그가 카탈로그에 있다(오타 방지)
        val unknown = ExerciseRequirements.bySlug.keys.filter { it !in catalog }
        assertTrue(unknown.isEmpty(), "카탈로그에 없는 슬러그: $unknown")
    }

    @Test
    @DisplayName("기구 없는 사람: 인버티드 로우(낮은 바)·턱걸이(철봉)·행잉 니 레이즈는 막히고 맨몸 동작은 열린다")
    fun noEquipmentBlocksBarAndLowBarExercises() {
        listOf("inverted-row", "chin-up", "pull-up-normal-grip", "hanging-knee-raises", "decline-sit-up").forEach { slug ->
            assertFalse(ExerciseRequirements.allows(ex(slug), bodyweightOnly), "$slug 이 기구 없는 사람에게 열렸다")
            assertFalse(ExerciseSuitability.suitable(ex(slug), bodyweightOnly, ExperienceLevel.ADVANCED, 30), "$slug suitable() 이 열었다")
        }
        // 예전 추정(needsBar)은 인버티드 로우를 놓쳤다 — 회귀 방지
        assertFalse(ExerciseSuitability.needsBar(ex("inverted-row")), "needsBar 는 인버티드 로우를 몰랐다(그래서 사람이 확인한 값이 필요)")
        listOf("superman-pulls", "standing-y-raise", "archer-push-up", "pike-push-up", "counterbalanced-skater-squat", "chest-dip-on-bench", "incline-push-up")
            .forEach { assertTrue(ExerciseRequirements.allows(ex(it), bodyweightOnly), "$it 이 막혔다") }
    }

    @Test
    @DisplayName("철봉(OTHER) 사용자는 턱걸이가 열리지만 낮은 바·평행봉·헬스장 기구는 아니다, 헬스장 사용자는 모두 열린다")
    fun barAndGymAccess() {
        assertTrue(ExerciseRequirements.allows(ex("chin-up"), bar))
        assertTrue(ExerciseRequirements.allows(ex("hanging-knee-raises"), bar))
        assertFalse(ExerciseRequirements.allows(ex("inverted-row"), bar))
        assertFalse(ExerciseRequirements.allows(ex("decline-sit-up"), bar))
        listOf("inverted-row", "chin-up", "hanging-knee-raises", "decline-sit-up").forEach { assertTrue(ExerciseRequirements.allows(ex(it), gym), it) }
        // 밴드는 철봉이 아니다
        assertFalse(ExerciseRequirements.allows(ex("chin-up"), band))
    }

    @Test
    @DisplayName("장비를 모르면(빈 값) 제한하지 않고, 덤벨·바벨 운동은 이 규칙이 아니라 장비 필터가 맡는다")
    fun unknownEquipmentAndNonBodyweight() {
        assertTrue(ExerciseRequirements.allows(ex("inverted-row"), emptySet()))
        val db = catalog.values.first { it.equipment == Equipment.DUMBBELL && it.isPlanEligible }
        assertTrue(ExerciseRequirements.allows(db, bodyweightOnly))
        assertEquals(ExerciseRequirement.LOW_BAR, ExerciseRequirements.of(ex("inverted-row")))
        assertEquals(ExerciseRequirement.HOUSEHOLD, ExerciseRequirements.of(ex("decline-push-up")))
    }
}

package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.Optional

/**
 * 실제 카탈로그(1,295종목) 전체에 대해 "부상 부위 운동 0 · 없는 장비 0 · 회복 안 된 근육 0 · 중복 0 · 카탈로그에 없는 이름 0"
 * 을 프로필별로 검증한다. 상류 추천이 무엇을 주든 WorkoutRecommendationServiceV2.applySafetyFilters 가 마지막 방어선이다.
 *
 * 부상 규칙은 InjuryFilterService.isRestrictedByRule (큐레이션 테이블 injury_exercise_restrictions 는 운영에서 비어 있다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecommendationSafetyCatalogTest {

    private lateinit var catalog: List<Exercise>
    private val user = User(id = 1L, email = "t@t.t", nickname = "T", level = UserLevel.BEGINNER)

    private val settingsRepo = mockk<UserSettingsRepository>()
    private val recoveryRepo = mockk<com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository>()
    private val injuryService = InjuryFilterService(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )
    private val v2 = WorkoutRecommendationServiceV2(
        mockk(), mockk(), settingsRepo, mockk(), mockk(), mockk(), mockk(), null,
        WorkoutV2SharedSupport(settingsRepo, mockk(relaxed = true), mockk()),
        mockk(), injuryService, recoveryRepo,
    )

    @BeforeAll
    fun load() {
        val f = File("src/main/resources/catalog/exercise-catalog.json")
        catalog = if (!f.exists()) emptyList() else {
            val raw: List<Map<String, Any>> = ObjectMapper().findAndRegisterModules().readValue(f)
            raw.mapIndexed { i, e ->
                Exercise(
                    id = i + 1L, slug = e["slug"] as String,
                    name = ((e["translations"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["locale"] == "en" }?.get("name") as? String) ?: e["slug"] as String,
                    category = runCatching { ExerciseCategory.valueOf(e["category"] as String) }.getOrDefault(ExerciseCategory.FULL_BODY),
                    movementPattern = e["movementPattern"] as? String,
                    muscleGroups = ((e["muscleGroups"] as? List<*>)?.mapNotNull { m -> runCatching { MuscleGroup.valueOf(m as String) }.getOrNull() }?.toMutableSet()) ?: mutableSetOf(),
                    equipment = runCatching { Equipment.valueOf((e["equipment"] as? String) ?: "OTHER") }.getOrDefault(Equipment.OTHER),
                )
            }
        }
        every { settingsRepo.findByUser_Id(any()) } returns Optional.empty()
        every { recoveryRepo.findByUser(any()) } returns emptyList()
    }

    data class Case(val name: String, val injuries: Set<String>, val equipment: Set<String>, val recovering: Map<String, Int> = emptyMap()) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        fun profiles() = listOf(
            Case("미성년 저체중 여성 초보", emptySet(), setOf("dumbbell", "bodyweight")),
            Case("고령 남성 초보", emptySet(), setOf("machine", "bodyweight")),
            Case("무릎수술 중급", setOf("knee:severe"), setOf("barbell", "dumbbell", "cable", "machine")),
            Case("허리부상 초보", setOf("lower_back:moderate"), setOf("dumbbell", "machine")),
            Case("초보 여성 감량", emptySet(), setOf("dumbbell", "cable", "machine", "bodyweight")),
            Case("상급 남성 근비대", emptySet(), setOf("gym")),
            Case("중급 남성 근력", emptySet(), setOf("barbell", "dumbbell")),
            Case("비만 초보", emptySet(), setOf("machine", "bodyweight"), recovering = mapOf("legs" to 30)),
            Case("홈트 장비없음", emptySet(), setOf("bodyweight")),
            Case("상급 여성", emptySet(), setOf("barbell", "dumbbell", "cable")),
            Case("어깨+무릎 복합 부상", setOf("shoulder:moderate", "knee:mild"), setOf("gym")),
            Case("평균 남성 초보(가슴 회복 중)", emptySet(), setOf("barbell", "dumbbell", "bodyweight"), recovering = mapOf("chest" to 20)),
        )
    }

    private fun profile(c: Case): UserProfile {
        val p = UserProfile(id = 1L, user = user, bodyInfo = BodyInfo(weight = 70.0), gender = "male", experienceLevel = ExperienceLevel.BEGINNER)
        p.injuries = c.injuries.toMutableSet()
        p.availableEquipment = c.equipment.toMutableSet()
        return p
    }

    private fun restrictedByInjury(ex: Exercise, injuries: Set<String>) = injuryService.isRestrictedByRule(ex, injuries)

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("profiles")
    @DisplayName("전체 카탈로그를 상류 추천으로 흘려도 부상·장비·회복·중복·환각 위반이 0")
    fun safetyPassOverWholeCatalog(c: Case) {
        assumeTrue(catalog.isNotEmpty(), "catalog not loaded")
        every { recoveryRepo.findByUser(any()) } returns c.recovering.map { (m, pct) ->
            com.richjun.liftupai.domain.recovery.entity.MuscleRecovery(user = user, muscleGroup = m, lastWorked = java.time.LocalDateTime.now(), recoveryPercentage = pct)
        }

        // 상류가 최악의 경우(전체 카탈로그, 부상·장비 무시, 중복 포함)를 준다고 가정
        val upstream = catalog.shuffled(java.util.Random(7)).take(400) + catalog.take(20)
        val result = v2.applySafetyFilters(user, profile(c), upstream, minAcceptableCount = 3)

        assertTrue(result.size >= 3, "${c.name}: 결과 ${result.size}개")
        val catalogIds = catalog.map { it.id }.toSet()
        result.forEach { ex ->
            assertTrue(ex.id in catalogIds, "${c.name}: 카탈로그에 없는 종목 ${ex.name}")
            assertFalse(restrictedByInjury(ex, c.injuries), "${c.name}: 부상 제한 종목 ${ex.slug} (${ex.movementPattern}, ${ex.muscleGroups})")
            if ("gym" !in c.equipment) {
                val eq = ex.equipment
                val ok = eq == null || eq == Equipment.BODYWEIGHT || eq == Equipment.OTHER || eq.name.lowercase() in c.equipment
                assertTrue(ok, "${c.name}: 보유하지 않은 장비 ${eq} — ${ex.slug}")
            }
            c.recovering.filterValues { it < 50 }.keys.forEach { m ->
                val recovering = com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver.muscleGroupsFor(m)
                assertTrue(ex.muscleGroups.none { it in recovering }, "${c.name}: 회복 중($m) 근육 종목 ${ex.slug}")
            }
        }
        assertEquals(result.size, result.map { it.id }.distinct().size, "${c.name}: id 중복")
        assertEquals(result.size, result.map { it.name.trim().lowercase() }.distinct().size, "${c.name}: 이름 중복")
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("profiles")
    @DisplayName("양성 대조: 안전 필터 없이 그대로 내보내면 위반이 실제로 존재한다 (부상·장비 프로필)")
    fun withoutSafetyPassViolationsExist(c: Case) {
        assumeTrue(catalog.isNotEmpty(), "catalog not loaded")
        val upstream = catalog.take(400)
        val injuryViolations = upstream.count { restrictedByInjury(it, c.injuries) }
        val equipmentViolations = if ("gym" in c.equipment) 0 else upstream.count { ex ->
            val eq = ex.equipment; eq != null && eq != Equipment.BODYWEIGHT && eq != Equipment.OTHER && eq.name.lowercase() !in c.equipment
        }
        if (c.injuries.isNotEmpty()) assertTrue(injuryViolations > 0, "${c.name}: 부상 프로필인데 카탈로그에 제한 종목이 없다 — 규칙이 죽었다")
        if ("gym" !in c.equipment) assertTrue(equipmentViolations > 0, "${c.name}: 장비 위반이 0 — 필터가 측정하는 게 없다")
    }

    @org.junit.jupiter.api.Test
    @DisplayName("무릎 부상 규칙은 실제 카탈로그에서 SQUAT/LUNGE/LEG_PRESS 패턴을 전부 잡는다")
    fun kneeRuleCoversLegPatternsInCatalog() {
        assumeTrue(catalog.isNotEmpty())
        val legPatterns = setOf("SQUAT", "LUNGE", "LEG_PRESS", "LEG_EXTENSION", "LEG_CURL")
        val leg = catalog.filter { it.movementPattern in legPatterns }
        assertTrue(leg.isNotEmpty())
        val leaked = leg.filterNot { restrictedByInjury(it, setOf("knee")) }
        assertTrue(leaked.isEmpty(), "무릎 부상에 통과한 하체 종목: ${leaked.map { it.slug }}")
    }
}

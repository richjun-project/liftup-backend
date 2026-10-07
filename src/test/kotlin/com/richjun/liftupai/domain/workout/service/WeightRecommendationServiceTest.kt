package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.PersonalRecord
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.Optional

/**
 * 추천 무게의 단일 출처.
 *
 * 배경(2026-09): 벤치 1RM 100kg 사용자에게 20kg 이 추천됐고(프로필 추정치를 아무도 안 읽음),
 * 반대로 15세 42kg 초보 여성에게 성인 기본값이 갈 수 있었다(경험·나이 무시).
 */
class WeightRecommendationServiceTest {

    private val user = User(id = 1L, email = "t@t.t", nickname = "T", level = UserLevel.BEGINNER)
    private val personalRecordRepository = mockk<PersonalRecordRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val service = WeightRecommendationService(
        personalRecordRepository, exerciseRepository, userProfileRepository, ExercisePatternClassifier(), ObjectMapper()
    )

    // ── 주요 종목(카탈로그 slug·패턴과 동일) ───────────────────────────
    private fun ex(id: Long, slug: String, pattern: String, cat: ExerciseCategory, eq: Equipment) =
        Exercise(id = id, slug = slug, name = slug, category = cat, movementPattern = pattern, equipment = eq)

    private val bench = ex(1, "barbell-bench-press", "HORIZONTAL_PRESS_BARBELL", ExerciseCategory.CHEST, Equipment.BARBELL)
    private val squat = ex(2, "barbell-full-squat", "SQUAT", ExerciseCategory.LEGS, Equipment.BARBELL)
    private val deadlift = ex(3, "barbell-deadlift", "DEADLIFT", ExerciseCategory.FULL_BODY, Equipment.BARBELL)
    private val ohp = ex(4, "barbell-standing-military-press", "VERTICAL_PRESS_BARBELL", ExerciseCategory.SHOULDERS, Equipment.BARBELL)
    private val row = ex(5, "barbell-bent-over-row-pronated-grip", "BARBELL_ROW", ExerciseCategory.BACK, Equipment.BARBELL)
    private val lunge = ex(6, "dumbbell-lunge-x", "LUNGE", ExerciseCategory.LEGS, Equipment.DUMBBELL)
    private val dbBench = ex(7, "dumbbell-bench-press", "HORIZONTAL_PRESS_DUMBBELL", ExerciseCategory.CHEST, Equipment.DUMBBELL)
    private val pulldown = ex(8, "cable-pulldown", "LAT_PULLDOWN", ExerciseCategory.BACK, Equipment.CABLE)
    private val pushup = ex(9, "push-up", "PUSHUP", ExerciseCategory.CHEST, Equipment.BODYWEIGHT)
    private val mainLifts = listOf(bench, squat, deadlift, ohp, row, lunge, dbBench, pulldown)

    private fun profile(
        bw: Double, gender: String, age: Int, level: ExperienceLevel,
        estimatedMaxes: String? = null, workingWeights: String? = null, bodyFat: Double? = null,
    ) = UserProfile(
        id = 1L, user = user, bodyInfo = BodyInfo(weight = bw, bodyFat = bodyFat),
        gender = gender, age = age, experienceLevel = level,
        estimatedMaxes = estimatedMaxes, workingWeights = workingWeights,
    )

    private fun noPr() {
        every { personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(any(), any()) } returns null
    }

    // ── R2: 12 프로필 합리 범위 ──────────────────────────────────────
    /** (이름, 프로필, 벤치 상한 ×체중, 벤치 하한 ×체중, 스쿼트 상한 ×체중) — 작업 무게 @10회 기준 */
    data class Case(val name: String, val profile: UserProfile, val benchMax: Double, val benchMin: Double, val squatMax: Double) {
        override fun toString() = name
    }

    companion object {
        private val u = User(id = 1L, email = "t@t.t", nickname = "T", level = UserLevel.BEGINNER)
        private fun p(bw: Double, g: String, age: Int, l: ExperienceLevel, em: String? = null) =
            UserProfile(id = 1L, user = u, bodyInfo = BodyInfo(weight = bw), gender = g, age = age, experienceLevel = l, estimatedMaxes = em)

        @JvmStatic
        fun profiles(): List<Case> = listOf(
            Case("미성년 저체중 여성 초보(15세 42kg)", p(42.0, "female", 15, ExperienceLevel.BEGINNER), 0.30, 0.0, 0.45),
            Case("고령 남성 초보(68세 70kg)", p(70.0, "male", 68, ExperienceLevel.BEGINNER), 0.40, 0.05, 0.50),
            Case("무릎수술 중급 남성(78kg)", p(78.0, "male", 34, ExperienceLevel.INTERMEDIATE), 0.75, 0.60, 0.90),
            Case("허리부상 초보 남성(72kg)", p(72.0, "male", 29, ExperienceLevel.BEGINNER), 0.45, 0.15, 0.55),
            Case("초보 여성 감량(58kg)", p(58.0, "female", 27, ExperienceLevel.BEGINNER), 0.35, 0.05, 0.50),
            Case("상급 남성 근비대(85kg)", p(85.0, "male", 31, ExperienceLevel.ADVANCED), 1.00, 0.50, 1.10),
            Case("중급 남성 근력(80kg)", p(80.0, "male", 30, ExperienceLevel.INTERMEDIATE), 0.75, 0.35, 0.90),
            Case("비만 초보 남성(110kg)", p(110.0, "male", 38, ExperienceLevel.BEGINNER), 0.45, 0.15, 0.55),
            Case("홈트 장비없음 초보(65kg)", p(65.0, "male", 25, ExperienceLevel.BEGINNER), 0.45, 0.15, 0.55),
            Case("상급 여성(62kg)", p(62.0, "female", 33, ExperienceLevel.ADVANCED), 0.75, 0.30, 1.00),
            Case("일본 사용자 벤치 100kg 자기신고(75kg 중급)", p(75.0, "male", 30, ExperienceLevel.INTERMEDIATE,
                """{"barbell-bench-press":100.0,"HORIZONTAL_PRESS_BARBELL":100.0}"""), 1.20, 0.95, 0.90),
            Case("평균 남성 초보(72kg)", p(72.0, "male", 26, ExperienceLevel.BEGINNER), 0.45, 0.15, 0.55),
        )
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("profiles")
    @DisplayName("주요 종목 추천 무게가 체중·경험 대비 합리 범위 안이고 2.5kg 단위다")
    fun weightsWithinReasonableRange(c: Case) {
        noPr()
        val bw = c.profile.bodyInfo!!.weight!!
        mainLifts.forEach { lift ->
            val w = service.suggestFromProfile(c.profile, lift, targetReps = 10).weight
            // 바벨 종목은 빈 봉(20kg)이 바닥이다 — 체구가 작아도 20kg 아래(0 초과)는 없다
            val ceiling = if (lift.equipment == Equipment.BARBELL) maxOf(bw * 1.3, RecoRules.BAR_WEIGHT) else bw * 1.3
            assertTrue(w > 0.0 && w <= ceiling, "${c.name} / ${lift.slug}: $w 가 (0, $ceiling] 밖")
            if (lift.equipment == Equipment.BARBELL) assertTrue(w >= RecoRules.BAR_WEIGHT, "${c.name} / ${lift.slug}: 바벨 $w < 20kg")
            assertEquals(0.0, (w * 10).toLong() % 25 / 10.0, 0.0001, "${c.name} / ${lift.slug}: $w 는 2.5 단위가 아님")
        }
        val benchW = service.suggestFromProfile(c.profile, bench, 10).weight
        assertTrue(benchW <= maxOf(bw * c.benchMax + 1.25, RecoRules.BAR_WEIGHT), "${c.name}: 벤치 $benchW > 상한 ${bw * c.benchMax} (바벨 하한 20kg)")
        assertTrue(benchW >= bw * c.benchMin - 1.25, "${c.name}: 벤치 $benchW < 하한 ${bw * c.benchMin}")
        val squatW = service.suggestFromProfile(c.profile, squat, 10).weight
        assertTrue(squatW <= bw * c.squatMax + 1.25, "${c.name}: 스쿼트 $squatW > 상한 ${bw * c.squatMax}")
        assertEquals(0.0, service.suggestFromProfile(c.profile, pushup, 10).weight, "맨몸은 0")
    }

    @Test
    @DisplayName("중급 78kg 남성 벤치는 50kg 이상 — 30kg 이 나가던 실사용 항의 사례")
    fun intermediateMaleBenchIsNotBeginnerWeight() {
        noPr()
        val w = service.suggestFromProfile(profile(78.0, "male", 34, ExperienceLevel.INTERMEDIATE), bench, 10).weight
        assertTrue(w >= 50.0, "중급 78kg 벤치 $w ≥ 50")
        val row = service.suggestFromProfile(profile(78.0, "male", 34, ExperienceLevel.INTERMEDIATE), this.row, 10).weight
        assertTrue(row >= 40.0, "중급 78kg 로우 $row ≥ 40")
    }

    @Test
    @DisplayName("15세 42kg 여성 초보의 바벨 종목은 빈 봉(20kg)에서 시작한다 — 20kg 미만(0 초과) 무게는 없고, 플랜은 이런 사용자에게 바벨 종목 자체를 주지 않는다")
    fun minorUnderweightFemaleStartsAtEmptyBar() {
        noPr()
        val minor = profile(42.0, "female", 15, ExperienceLevel.BEGINNER)
        listOf(bench, ohp, row).forEach { lift ->
            val w = service.suggestFromProfile(minor, lift, 10).weight
            assertEquals(RecoRules.BAR_WEIGHT, w, 0.0001, "${lift.slug}: $w — 바벨은 빈 봉(20kg) 밑으로 내려가지 않는다")
            assertTrue(!RecoRules.suitable(lift, emptySet(), ExperienceLevel.BEGINNER, 15), "${lift.slug}: 미성년 초보에게 바벨 종목은 맞지 않는다")
        }
        // 덤벨 변형은 가볍게 시작할 수 있다
        assertTrue(service.suggestFromProfile(minor, dbBench, 10).weight < 20.0)
    }

    @Test
    @DisplayName("상급자는 초보 기본값을 받지 않는다 — 같은 체중이면 상급 > 중급 > 초보")
    fun advancedDoesNotGetBeginnerDefault() {
        noPr()
        val b = service.suggestFromProfile(profile(80.0, "male", 30, ExperienceLevel.BEGINNER), bench, 10).weight
        val i = service.suggestFromProfile(profile(80.0, "male", 30, ExperienceLevel.INTERMEDIATE), bench, 10).weight
        val a = service.suggestFromProfile(profile(80.0, "male", 30, ExperienceLevel.ADVANCED), bench, 10).weight
        assertTrue(b < i && i < a, "beginner=$b intermediate=$i advanced=$a")
        assertEquals(WeightRecommendationService.Source.DEFAULT, service.suggestFromProfile(profile(80.0, "male", 30, ExperienceLevel.ADVANCED), bench, 10).source)
    }

    // ── 우선순위 ────────────────────────────────────────────────────
    @Test
    @DisplayName("프로필 estimatedMaxes(1RM)가 있으면 기본값 대신 그것을 목표 반복수 작업 무게로 쓴다")
    fun estimatedMaxBeatsDefault() {
        noPr()
        val jp = profile(75.0, "male", 30, ExperienceLevel.INTERMEDIATE, estimatedMaxes = """{"barbell-bench-press":100.0}""")
        val s = service.suggestFromProfile(jp, bench, targetReps = 8)
        assertEquals(WeightRecommendationService.Source.ESTIMATED_MAX, s.source)
        // Brzycki: 100 × (37-8)/36 = 80.6 → 80
        assertEquals(80.0, s.weight, 0.01)
        assertTrue(s.weight > service.suggestFromProfile(profile(75.0, "male", 30, ExperienceLevel.INTERMEDIATE), bench, 8).weight)
    }

    @Test
    @DisplayName("예전 키(bench-press)로 저장된 1RM 도 패턴 별칭으로 읽힌다 — 정확 slug 만 보던 시절엔 무시됐다")
    fun legacyKeyResolvesViaPattern() {
        noPr()
        val legacy = profile(80.0, "male", 30, ExperienceLevel.BEGINNER, estimatedMaxes = """{"bench-press":90.0}""")
        val s = service.suggestFromProfile(legacy, bench, 10)
        assertEquals(WeightRecommendationService.Source.ESTIMATED_MAX, s.source)
        assertEquals(67.5, s.weight, 0.01) // 90×27/36=67.5
    }

    @Test
    @DisplayName("부분 문자열로는 매칭하지 않는다 — \"bench\" 키가 다른 종목에 새지 않는다")
    fun noSubstringMatch() {
        noPr()
        val p = profile(80.0, "male", 30, ExperienceLevel.BEGINNER, estimatedMaxes = """{"bench":90.0}""")
        assertEquals(WeightRecommendationService.Source.DEFAULT, service.suggestFromProfile(p, bench, 10).source)
    }

    @Test
    @DisplayName("직접 입력한 workingWeights 가 estimatedMaxes 보다 우선한다")
    fun workingWeightBeatsEstimatedMax() {
        noPr()
        val p = profile(80.0, "male", 30, ExperienceLevel.BEGINNER,
            estimatedMaxes = """{"barbell-bench-press":100.0}""", workingWeights = """{"barbell-bench-press":62.0}""")
        val s = service.suggestFromProfile(p, bench, 10)
        assertEquals(WeightRecommendationService.Source.WORKING_WEIGHT, s.source)
        assertEquals(62.5, s.weight, 0.01)
    }

    @Test
    @DisplayName("개인 기록(PR)이 있으면 프로필보다 우선한다")
    fun personalRecordBeatsProfile() {
        every { personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(1L, bench.id) } returns
            PersonalRecord(id = 1L, user = user, exercise = bench, weight = 80.0, reps = 5)
        every { exerciseRepository.findById(bench.id) } returns Optional.of(bench)
        every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(
            profile(80.0, "male", 30, ExperienceLevel.BEGINNER, estimatedMaxes = """{"barbell-bench-press":50.0}"""))
        val w = service.calculateSuggestedWeight(1L, bench.id, 10)
        // 1RM = 80×36/32 = 90 → @10회 67.5
        assertEquals(67.5, w, 0.01)
    }

    @Test
    @DisplayName("깨진 JSON 은 무시하고 기본값으로 간다")
    fun brokenJsonFallsBack() {
        noPr()
        val p = profile(80.0, "male", 30, ExperienceLevel.BEGINNER, estimatedMaxes = "{not json")
        assertEquals(WeightRecommendationService.Source.DEFAULT, service.suggestFromProfile(p, bench, 10).source)
    }

    @Test
    @DisplayName("맨몸 운동 기본 추천은 0kg")
    fun bodyweightStaysZero() {
        every { personalRecordRepository.findTopByUserIdAndExerciseIdOrderByWeightDesc(1L, pushup.id) } returns null
        every { exerciseRepository.findById(pushup.id) } returns Optional.of(pushup)
        every { userProfileRepository.findByUser_Id(1L) } returns Optional.of(profile(80.0, "male", 30, ExperienceLevel.BEGINNER))
        assertEquals(0.0, service.calculateSuggestedWeight(1L, pushup.id, 12))
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.Exercise
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * 첫 세션 무게의 단일 출처. 같은 사람·같은 종목이면
 *   플랜 화면(UserPlanService.getDayWorkout → enricher) · 채팅/퀵 추천(PTRecommendationEngine) · 프로그램 생성
 * 세 경로가 2.5kg 이내로 같은 무게를 낸다. 바벨 종목은 0 초과 20kg 미만이 없다.
 *
 * 탐색(2026-10) 사례: 60kg 초보 여성의 바벨 백스쿼트가 플랜 27.5 / 채팅 25 / 하루 운동 30kg 으로 화면마다 달랐고,
 * 68세 여성은 22.5 / 20 / 17.5kg(바 미만)이었다. 원인: 가상 PR 밴드(0.9배), 준비도 +5%, 나이 이중 감점.
 */
class FirstWorkoutWeightConsistencyTest {

    private val lifts = listOf(
        "barbell-full-squat", "barbell-bench-press", "barbell-deadlift", "barbell-bent-over-row-pronated-grip",
        "dumbbell-bench-press", "dumbbell-goblet-squat", "dumbbell-curls", "cable-pulldown", "leg-press-machine-normal-stance",
        "barbell-hip-thrust", "lateral-raises-dumbbell", "push-ups-bodyweight",
    ).map { RecoTestKit.bySlug.getValue(it) }

    private data class Who(val name: String, val level: ExperienceLevel, val age: Int, val gender: String, val kg: Double, val equipment: List<String> = emptyList())

    // 30 프로필: 나이(15~78)·성별·체중(42~110kg)·경험(초보~전문가)
    private val people: List<Who> = listOf(
        Who("60kg 초보 여성 30세", ExperienceLevel.BEGINNER, 30, "female", 60.0),
        Who("68세 여성 초보", ExperienceLevel.BEGINNER, 68, "female", 54.0),
        Who("15세 저체중 여성", ExperienceLevel.BEGINNER, 15, "female", 42.0),
        Who("17세 남성 초보", ExperienceLevel.BEGINNER, 17, "male", 62.0),
        Who("72세 남성 초보", ExperienceLevel.BEGINNER, 72, "male", 66.0),
        Who("55세 남성 노비스", ExperienceLevel.NOVICE, 55, "male", 78.0),
        Who("45세 여성 노비스", ExperienceLevel.NOVICE, 45, "female", 58.0),
        Who("24세 남성 초보", ExperienceLevel.BEGINNER, 24, "male", 75.0),
        Who("38세 비만 초보 남성", ExperienceLevel.BEGINNER, 38, "male", 110.0),
        Who("27세 여성 노비스", ExperienceLevel.NOVICE, 27, "female", 52.0),
        Who("33세 남성 중급", ExperienceLevel.INTERMEDIATE, 33, "male", 80.0),
        Who("29세 여성 중급", ExperienceLevel.INTERMEDIATE, 29, "female", 57.0),
        Who("52세 남성 중급", ExperienceLevel.INTERMEDIATE, 52, "male", 82.0),
        Who("61세 여성 중급", ExperienceLevel.INTERMEDIATE, 61, "female", 60.0),
        Who("36세 남성 상급", ExperienceLevel.ADVANCED, 36, "male", 88.0),
        Who("31세 여성 상급", ExperienceLevel.ADVANCED, 31, "female", 62.0),
        Who("44세 남성 상급", ExperienceLevel.ADVANCED, 44, "male", 92.0),
        Who("40세 남성 전문가", ExperienceLevel.EXPERT, 40, "male", 95.0),
        Who("35세 여성 전문가", ExperienceLevel.EXPERT, 35, "female", 65.0),
        Who("19세 남성 노비스", ExperienceLevel.NOVICE, 19, "male", 70.0),
        Who("22세 여성 초보", ExperienceLevel.BEGINNER, 22, "female", 48.0),
        Who("66세 여성 노비스", ExperienceLevel.NOVICE, 66, "female", 56.0),
        Who("78세 여성 초보", ExperienceLevel.BEGINNER, 78, "female", 50.0),
        Who("50세 남성 초보", ExperienceLevel.BEGINNER, 50, "male", 85.0),
        Who("26세 남성 상급", ExperienceLevel.ADVANCED, 26, "male", 78.0),
        Who("32세 여성 초보", ExperienceLevel.BEGINNER, 32, "female", 70.0),
        Who("47세 남성 노비스", ExperienceLevel.NOVICE, 47, "male", 90.0),
        Who("63세 남성 중급", ExperienceLevel.INTERMEDIATE, 63, "male", 74.0),
        Who("16세 남성 노비스", ExperienceLevel.NOVICE, 16, "male", 58.0),
        Who("41세 여성 초보", ExperienceLevel.BEGINNER, 41, "female", 64.0),
    )

    private fun fixture(who: Who, ex: Exercise, reps: IntRange = 8..12): Triple<RecoTestKit.Repos, RecoTestKit.PlanFixture, PTRecommendationEngine> {
        val user = RecoTestKit.user()
        val profile = RecoTestKit.profile(user, who.level, who.age, who.gender, who.kg, equipment = who.equipment)
        val repos = RecoTestKit.Repos(profile, RecoTestKit.settings(user))
        val plan = RecoTestKit.PlanFixture(repos, listOf(Triple(ex, 3, reps)))
        val pt = PTRecommendationEngine(
            repos.userProfileRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            plan.weights.let { mockk<com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository>(relaxed = true).also { pr ->
                every { pr.findTopByUserAndExerciseOrderByWeightDesc(any(), any()) } returns null
            } },
            mockk<com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository>(relaxed = true).also {
                every { it.findFirstByUserAndStatusOrderByStartDateDesc(any(), EnrollmentStatus.ACTIVE) } returns null
            },
            ExercisePatternClassifier(), ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
            WorkoutV2SharedSupport(repos.userSettingsRepository, mockk(relaxed = true), mockk(relaxed = true)),
            plan.weights,
        )
        return Triple(repos, plan, pt)
    }

    @Test
    @DisplayName("플랜 화면·채팅/퀵 추천(PT 엔진)·하루 운동(enricher)의 첫 무게가 30 프로필 × 12 종목에서 2.5kg 이내로 같다")
    fun threePathsAgree() {
        val violations = mutableListOf<String>()
        var compared = 0
        for (who in people) {
            for (lift in lifts) {
                val (_, plan, pt) = fixture(who, lift)
                val dayWeight = plan.service.getDayWorkout(1L, 1).exercises.single().suggestedWeight ?: 0.0
                val ptWeight = pt.calculateFullPTRecommendation(plan.user, lift).weight
                val planWeight = plan.weights.firstSessionWeight(
                    RecoTestKit.profile(plan.user, who.level, who.age, who.gender, who.kg), lift, 10
                )
                compared++
                if (abs(dayWeight - ptWeight) > 2.5) violations += "${who.name} / ${lift.slug}: 하루 운동 $dayWeight vs 채팅/퀵 $ptWeight"
                if (abs(dayWeight - planWeight) > 2.5) violations += "${who.name} / ${lift.slug}: 하루 운동 $dayWeight vs 플랜(단일 출처) $planWeight"
            }
        }
        assertTrue(compared == people.size * lifts.size)
        assertTrue(violations.isEmpty(), "경로별 첫 무게 불일치 ${violations.size}건:\n" + violations.take(25).joinToString("\n"))
    }

    @Test
    @DisplayName("바벨 종목 무게가 0 초과 20kg 미만인 경우가 없다 (플랜·PT 엔진·enricher 모두)")
    fun noBarbellBelowBar() {
        val bad = mutableListOf<String>()
        for (who in people) {
            for (lift in lifts.filter { it.equipment == Equipment.BARBELL }) {
                val (_, plan, pt) = fixture(who, lift)
                val w = listOf(
                    "day" to (plan.service.getDayWorkout(1L, 1).exercises.single().suggestedWeight ?: 0.0),
                    "pt" to pt.calculateFullPTRecommendation(plan.user, lift).weight,
                    "service" to plan.weights.calculateSuggestedWeight(1L, lift.id, 10),
                )
                w.forEach { (path, v) -> if (v > 0.0 && v < RecoRules.BAR_WEIGHT) bad += "${who.name} / ${lift.slug} / $path: $v" }
            }
        }
        assertTrue(bad.isEmpty(), "20kg 미만 바벨 무게:\n" + bad.joinToString("\n"))
    }

    @Test
    @DisplayName("무게 없는 종목(맨몸)은 어느 경로에서도 0")
    fun bodyweightHasNoLoad() {
        val pushUp = RecoTestKit.bySlug.getValue("push-ups-bodyweight")
        for (who in people.take(6)) {
            val (_, plan, pt) = fixture(who, pushUp, 8..12)
            assertEquals(0.0, plan.service.getDayWorkout(1L, 1).exercises.single().suggestedWeight ?: 0.0, 0.0001, who.name)
            assertEquals(0.0, pt.calculateFullPTRecommendation(plan.user, pushUp).weight, 0.0001, who.name)
        }
    }

    @Test
    @DisplayName("나이는 기본값 표에서 한 번만 반영된다 — 55세 사용자의 기록(PR) 기반 무게는 하루 운동에서 줄지 않는다")
    fun ageNotAppliedTwiceAndPrUntouched() {
        val bench = RecoTestKit.bySlug.getValue("barbell-bench-press")
        val who = Who("55세", ExperienceLevel.INTERMEDIATE, 55, "male", 80.0)
        val (_, plan, _) = fixture(who, bench, 8..8)
        // 이력 기반 무게(PR·점진적 과부하)가 100kg 로 계산되는 사용자
        every { plan.progressive.calculateWeightForPlan(any(), any(), any(), any(), any(), any(), any(), any()) } returns 100.0
        val w = plan.service.getDayWorkout(1L, 1).exercises.single().suggestedWeight
        assertEquals(100.0, w!!, 0.0001, "PR 기반 무게가 나이로 다시 깎였다")
    }

    @Test
    @DisplayName("컨디션 신호가 없는 평범한 날은 무게를 +5% 올리지 않는다 (준비도 중립 = 1.0)")
    fun neutralReadinessIsNeutral() {
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val who = Who("33세", ExperienceLevel.INTERMEDIATE, 33, "male", 80.0)
        val (_, plan, _) = fixture(who, squat)
        val dayWeight = plan.service.getDayWorkout(1L, 1).exercises.single().suggestedWeight
        val base = plan.weights.firstSessionWeight(RecoTestKit.profile(plan.user, who.level, who.age, who.gender, who.kg), squat, 10)
        assertEquals(base, dayWeight!!, 0.0001)
        // 컨디션이 좋다고 직접 답하면 그때만 +5%
        val good = plan.service.getDayWorkout(1L, 1, subjectiveReadiness = 5).exercises.single().suggestedWeight!!
        assertTrue(good > base, "컨디션 좋음: $good > $base")
    }
}

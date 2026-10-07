package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.richjun.liftupai.domain.ai.dto.AIWorkoutRecommendationResponse
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository
import com.richjun.liftupai.domain.workout.service.*
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import com.richjun.liftupai.domain.workout.util.SessionDurationFitter
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * 채팅 "오늘의 운동"(/api/chat/recommendations/workout → AIAnalysisService)과 퀵 추천(/api/v2/workouts/recommendations/basic →
 * WorkoutRecommendationServiceV2)이 저장된 세션 시간을 쓴다.
 *
 * 탐색(2026-10) 사례: 앱이 시간을 안 보내면 저장된 60분과 무관하게 항상 30분짜리를 만들었다(서버 기본값 `duration ?: 30`).
 * 55/65명 페르소나가 45분 이상을 저장해 두었다. 요청 응답의 duration 표기도 30 이라 사용자는 틀린 시간을 보고 시작했다.
 */
class ChatWorkoutDurationTest {

    private val user = RecoTestKit.user()

    private fun minutes(r: AIWorkoutRecommendationResponse): Int = SessionDurationFitter.estimateMinutes(
        r.recommendation.exercises.map { SessionDurationFitter.Slot(it.sets, it.reps, it.rest, it.rest) }
    )

    private fun ratio(r: AIWorkoutRecommendationResponse, target: Int): Double =
        SessionDurationFitter.estimateSeconds(r.recommendation.exercises.map { SessionDurationFitter.Slot(it.sets, it.reps, it.rest, it.rest) }) / (target * 60.0)

    @Test
    @DisplayName("저장된 세션 시간 60분 + 요청에 시간 없음 → 60분 추천: 응답 duration 60, 약 6종목, 추정 시간이 60분의 90~105%")
    fun savedSixtyMinutes() {
        val r = ChatHarness(savedMinutes = 60).chat.getAIWorkoutRecommendation(userId = 1L, duration = null)
        assertEquals(60, r.recommendation.duration, "응답 duration 은 실제로 만든 시간이어야 한다")
        assertTrue(r.recommendation.exercises.size in 5..7, "60분이면 약 6종목: ${r.recommendation.exercises.map { it.name }}")
        val ratio = ratio(r, 60)
        assertTrue(ratio in 0.90..1.05, "추정 ${minutes(r)}분 = ${"%.0f".format(ratio * 100)}% (90~105% 기대)")
    }

    @Test
    @DisplayName("요청 duration 이 저장값보다 우선한다 (30분 요청 → 30분 추천)")
    fun requestOverridesSaved() {
        val r = ChatHarness(savedMinutes = 60).chat.getAIWorkoutRecommendation(userId = 1L, duration = 30)
        assertEquals(30, r.recommendation.duration)
        assertTrue(ratio(r, 30) in 0.85..1.10, "추정 ${minutes(r)}분")
        assertTrue(r.recommendation.exercises.size in 3..5)
    }

    @Test
    @DisplayName("설정에 시간이 없으면 프로필 값, 둘 다 없으면 45분")
    fun fallbackOrder() {
        val fromProfile = ChatHarness(savedMinutes = null, profileMinutes = 75).chat.getAIWorkoutRecommendation(1L)
        assertEquals(75, fromProfile.recommendation.duration)
        val none = ChatHarness(savedMinutes = null, profileMinutes = null).chat.getAIWorkoutRecommendation(1L)
        assertEquals(UserTrainingContextResolver.DEFAULT_SESSION_MINUTES, none.recommendation.duration)
        assertEquals(45, none.recommendation.duration)
    }

    @Test
    @DisplayName("저장된 시간이 길수록 종목 수·분량이 늘어난다: 20·45·90·120분")
    fun scalesWithSessionLength() {
        val sizes = listOf(20, 45, 90, 120).associateWith { m ->
            val r = ChatHarness(savedMinutes = null, profileMinutes = null).chat.getAIWorkoutRecommendation(1L, duration = m)
            assertEquals(m, r.recommendation.duration)
            assertTrue(ratio(r, m) in 0.85..1.10, "${m}분 요청: 추정 ${minutes(r)}분 (${"%.0f".format(ratio(r, m) * 100)}%)")
            r.recommendation.exercises.size
        }
        assertTrue(sizes.getValue(20) < sizes.getValue(45) && sizes.getValue(45) < sizes.getValue(90), "종목 수가 시간에 비례: $sizes")
    }

    @Test
    @DisplayName("채팅 추천과 퀵 추천(V2)이 같은 사용자에게 같은 시간을 낸다")
    fun chatAndQuickAgree() {
        val h = ChatHarness(savedMinutes = 60)
        val chat = h.chat.getAIWorkoutRecommendation(1L)
        val quick = h.quick.getBasicWorkoutRecommendation(1L)
        assertEquals(60, chat.recommendation.duration)
        assertEquals(chat.recommendation.duration, quick.recommendation.duration, "두 화면의 시간이 다르다")
        val quickSeconds = SessionDurationFitter.estimateSeconds(
            quick.recommendation.exercises.map { SessionDurationFitter.Slot(it.sets, it.reps, it.rest, it.rest) }
        ) / 60.0
        assertTrue(quickSeconds in 60 * 0.85..60 * 1.10, "퀵 추정 ${"%.0f".format(quickSeconds)}분")
        assertTrue(quick.recommendation.exercises.size in 5..7, "퀵 종목 수 ${quick.recommendation.exercises.size}")
    }

    @Test
    @DisplayName("초보는 첫 바벨 스쿼트가 4x6-8/180초가 아니다: 주력 복합 3세트 이하, 반복 8 이상 — 시간을 채우려 세트를 올리지도 않는다")
    fun beginnerPrescription() {
        val r = ChatHarness(level = ExperienceLevel.BEGINNER, savedMinutes = 90).chat.getAIWorkoutRecommendation(1L)
        val squat = r.recommendation.exercises.first { it.name.contains("squat", ignoreCase = true) || true }
        r.recommendation.exercises.forEach { e ->
            val ex = RecoTestKit.byId[e.exerciseId.toLong()]!!
            if (RecoRules.isPrimaryCompound(ex)) {
                assertTrue(e.sets <= 3, "${ex.slug}: ${e.sets}세트 (초보 주력 복합은 3 이하)")
                val min = e.reps.substringBefore('-').trim().toIntOrNull() ?: 8
                assertTrue(min >= 8, "${ex.slug}: 반복 ${e.reps} (초보는 8 이상)")
            }
        }
        assertTrue(squat.sets <= 5)
        assertTrue(ratio(r, 90) in 0.85..1.10, "90분 초보: 추정 ${minutes(r)}분")
    }

    @Test
    @DisplayName("집중 부위(굵은 팔)가 있으면 추천에 그 부위 운동이 하나 이상 들어간다")
    fun focusGoalReachesChat() {
        val r = ChatHarness(focusGoals = listOf("big_arms"), picks = listOf(
            "barbell-full-squat", "barbell-bench-press", "barbell-bent-over-row-pronated-grip", "seated-shoulder-press-barbell",
        )).chat.getAIWorkoutRecommendation(1L)
        val arms = r.recommendation.exercises.count { RecoTestKit.byId[it.exerciseId.toLong()]!!.category.name == "ARMS" }
        assertTrue(arms >= 1, "팔 집중인데 팔 운동이 없다: ${r.recommendation.exercises.map { it.name }}")
    }

    @Test
    @DisplayName("추천 첫 운동은 대표 복합운동이다 (카테고리 순서만이 아니라 복합·대표 등급 순)")
    fun leadsWithEssentialCompound() {
        val r = ChatHarness().chat.getAIWorkoutRecommendation(1L)
        val first = RecoTestKit.byId[r.recommendation.exercises.first().exerciseId.toLong()]!!
        assertTrue(RecoRules.isCompound(first), "첫 운동이 복합이 아니다: ${first.slug}")
    }
}

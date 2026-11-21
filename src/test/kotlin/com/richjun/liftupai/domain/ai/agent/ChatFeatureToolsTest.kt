package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.nutrition.service.NutritionService
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.InjuryFilterService
import com.richjun.liftupai.domain.workout.service.UserPlanService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import java.io.File
import java.time.ZoneId

/**
 * 채팅으로 다 되게(2026-11-21 대표): 새 도구가 실제 서비스로 앱 상태를 바꾸는지 — 식단 기록·삭제, 프로필·부상(플랜 맞춤)·트레이너,
 * 플랜 고르기·바꾸기·쉬기, 밖에서 한 운동 기록, 코치가 한 일 되돌리기. 기능 목록(FEATURES.md)이 도구 전부를 다루는지도 본다.
 */
class ChatFeatureToolsTest : ApiTestSupport() {
    @Autowired private lateinit var toolbox: CoachToolbox
    @Autowired private lateinit var nutritionService: NutritionService
    @Autowired private lateinit var mealLogRepository: MealLogRepository
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var injuryFilterService: InjuryFilterService
    @Autowired private lateinit var userService: com.richjun.liftupai.domain.user.service.UserService

    private val mapper = jacksonObjectMapper()
    private fun ctx(user: User) = CoachToolContext(user, "ko", ZoneId.of("Asia/Seoul"), ChatSafetyService.RiskProfile(locale = "ko"))
    private fun run(user: User, tool: String, json: String) = toolbox.execute(tool, mapper.readTree(json), ctx(user))

    private fun seeded(prefix: String, template: String? = "beginner_fullbody"): User {
        val user = newUser(prefix)
        profileRepository.save(UserProfile(user = user).apply {
            experienceLevel = ExperienceLevel.BEGINNER; age = 30; gender = "male"; ptStyle = PTStyle.BURNOUT
            availableEquipment = mutableSetOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"); weeklyWorkoutDays = 3; workoutDuration = 45
        })
        settingsRepository.save(UserSettings(user = user).apply { language = "ko"; timeZone = "Asia/Seoul" })
        template?.let { userPlanService.applyTemplate(user, it, null, "ko") }
        return user
    }

    private fun <T> withMealAnalysis(json: String, block: () -> T): T {
        val target = AopTestUtils.getUltimateTargetObject<NutritionService>(nutritionService)
        val original = ReflectionTestUtils.getField(target, "geminiAIService")
        val spy = mockk<GeminiAIService>(relaxed = true)
        every { spy.analyzeMealText(any(), any()) } returns json
        ReflectionTestUtils.setField(target, "geminiAIService", spy)
        try { return block() } finally { ReflectionTestUtils.setField(target, "geminiAIService", original) }
    }

    @Test
    fun `글로 말한 식사를 기록하고 지운다 — 추정 실패면 저장하지 않는다`() {
        allowedErrorLoggers += NutritionService::class.java.name
        val user = seeded("meal", template = null)
        val out = withMealAnalysis("""{"meal_name":"김치찌개 정식","ingredients":["김치찌개 1그릇","쌀밥 1공기"],"portion":"1인분","calories":650,"protein":30,"carbs":80,"fat":20}""") {
            run(user, "log_meal", """{"description":"점심에 김치찌개랑 밥 한 공기","meal_type":"lunch"}""")
        }
        assertTrue(out.ok && out.changed, "log_meal 성공: ${out.result}")
        val saved = mealLogRepository.findTopByUserOrderByTimestampDesc(user)!!
        assertEquals(650, saved.calories); assertEquals(MealType.LUNCH, saved.mealType)
        assertEquals(saved.id, (out.result["meal_log_id"] as Number).toLong())
        assertTrue(out.result["today"] is Map<*, *>, "오늘 합계를 같이 돌려준다")

        val bad = withMealAnalysis("not json") { run(user, "log_meal", """{"description":"???"}""") }
        assertFalse(bad.ok, "추정 실패는 실패로"); assertEquals(saved.id, mealLogRepository.findTopByUserOrderByTimestampDesc(user)!!.id, "실패면 새 기록 없음")

        val del = run(user, "delete_meal_log", "{}")
        assertTrue(del.ok && del.changed); assertEquals(null, mealLogRepository.findTopByUserOrderByTimestampDesc(user), "id 없이 부르면 가장 최근 식사를 지운다")
        assertFalse(run(user, "delete_meal_log", "{}").ok, "지울 게 없으면 실패")
    }

    @Test
    fun `프로필 사실을 말하면 저장 — 새 부상은 플랜에서 그 부위 운동을 뺀다`() {
        val user = seeded("prof")
        val knee = injuryFilterService.restrictionChecker(setOf("knee"))
        fun planIds(): List<Long> {
            val plan = userPlanService.getActivePlan(user.id)!!
            return (1..plan.totalDays).flatMap { d -> userPlanService.getDayWorkout(user.id, d, locale = "ko").exercises.map { it.exerciseId } }
        }
        val kneeBefore = exerciseRepository.findAllById(planIds()).count { knee(it) }
        assertTrue(kneeBefore > 0, "양성 대조: 초보 전신 플랜에는 무릎 부담 운동이 있어야 한다")

        val r = run(user, "update_my_profile", """{"weight_kg":72.5,"injuries":["knee"],"trainer_style":"SPARTAN","weekly_workout_days":4}""")
        assertTrue(r.ok && r.changed, "update_my_profile: ${r.result}")
        val p = userService.getProfileV4(user.id)
        assertEquals(72.5, p.weight); assertEquals("SPARTAN", p.ptStyle.uppercase()); assertTrue("knee" in p.injuries); assertEquals(4, p.weeklyWorkoutDays)
        assertEquals(0, exerciseRepository.findAllById(planIds()).count { knee(it) }, "무릎 부상 뒤 플랜에 무릎 부담 운동이 남지 않는다")

        val bad = run(user, "update_my_profile", """{"weight_kg":900}""")
        assertFalse(bad.ok); assertEquals("bad_arguments", bad.result["error"])
        assertFalse(run(user, "update_my_profile", """{"injuries":["toe"]}""").ok, "모르는 부상 값은 거절")
    }

    @Test
    fun `플랜 고르기·바꾸기·쉬기`() {
        val user = seeded("plan")
        val opts = run(user, "get_plan_options", "{}")
        assertTrue(opts.ok)
        @Suppress("UNCHECKED_CAST") val options = opts.result["options"] as List<Map<String, Any?>>
        assertTrue(options.isNotEmpty(), "추천 플랜 목록")
        val before = userPlanService.getActivePlan(user.id)!!.sourceId
        val other = options.map { it["template_code"] as String }.firstOrNull { it != before } ?: "home_bodyweight_beginner"
        val ch = run(user, "change_plan", """{"template_code":"$other"}""")
        assertTrue(ch.ok && ch.changed, "change_plan: ${ch.result}")
        assertEquals(other, userPlanService.getActivePlan(user.id)!!.sourceId)
        assertFalse(run(user, "change_plan", """{"template_code":"no_such_plan"}""").ok)

        assertTrue(run(user, "pause_or_resume_plan", """{"paused":true}""").ok)
        assertEquals(null, userPlanService.getActivePlan(user.id), "쉬는 중")
        assertTrue(run(user, "pause_or_resume_plan", """{"paused":false}""").ok, "쉬는 플랜을 다시 시작")
        assertEquals(other, userPlanService.getActivePlan(user.id)!!.sourceId)
        assertFalse(run(user, "pause_or_resume_plan", """{"paused":false}""").ok, "쉬는 플랜이 없으면 실패")
    }

    @Test
    fun `밖에서 한 운동을 기록 — 앱에 열린 운동이 있으면 거절`() {
        val user = seeded("logw")
        val squat = exerciseRepository.findAll().first { it.slug == "barbell-full-squat" }
        val r = run(user, "log_completed_workout", """{"exercises":[{"exercise_id":${squat.id},"sets":[{"weight_kg":100,"reps":5},{"weight_kg":100,"reps":5}]}],"duration_minutes":40}""")
        assertTrue(r.ok && r.changed, "log_completed_workout: ${r.result}")
        assertEquals(2, (r.result["total_sets"] as Number).toInt())
        val s = sessionRepository.findAll().filter { it.user.id == user.id }
        assertEquals(1, s.size); assertEquals(SessionStatus.COMPLETED, s.single().status)
        assertFalse(run(user, "log_completed_workout", """{"exercises":[{"exercise_id":999999999,"sets":[{"reps":5}]}],"duration_minutes":10}""").ok, "모르는 운동")
    }

    @Test
    fun `코치가 한 일을 되돌린다`() {
        val user = seeded("undo")
        val day = userPlanService.getActivePlan(user.id)!!.currentDay
        assertTrue(run(user, "set_today_workout_day", """{"day":${if (day == 1) 2 else 1}}""").changed)
        val u = run(user, "undo_last_coach_change", "{}")
        assertTrue(u.ok && u.changed, "undo: ${u.result}")
        assertEquals(day, userPlanService.getActivePlan(user.id)!!.currentDay, "오늘 Day 가 원래대로")
    }

    @Test
    fun `새 도구가 모두 선언되고 기능 목록이 모든 도구를 다룬다`() {
        val names = toolbox.declarations().map { it["name"] as String }
        val added = listOf("log_meal", "delete_meal_log", "update_my_profile", "get_plan_options", "change_plan", "pause_or_resume_plan", "log_completed_workout", "undo_last_coach_change")
        assertTrue(names.containsAll(added), "선언 누락: ${added - names.toSet()}")
        assertEquals(names.size, names.toSet().size, "이름 중복")
        val doc = File("growth/coach/FEATURES.md").readText()
        val missing = names.filter { "`$it`" !in doc }
        assertTrue(missing.isEmpty(), "FEATURES.md 에 없는 도구: $missing")
        assertNotEquals(-1, doc.indexOf("채팅으로 안 하는 것"), "일부러 채팅으로 안 하는 것과 이유가 있어야 한다")
    }
}

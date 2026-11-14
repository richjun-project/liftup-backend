package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.service.NotificationService
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.PlanStatus
import com.richjun.liftupai.domain.workout.repository.UserWorkoutPlanRepository
import com.richjun.liftupai.domain.workout.service.UserPlanService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.ZoneId

/**
 * "코치가 한 일"·되돌리기(2026-11-14 coach-autopilot-r2, H2).
 * 트레이너 도구가 바꾼 것은 바꾸기 전 상태와 함께 남고, 가장 최근 것을 되돌리면 정확히 돌아온다.
 * 사용자가 그 뒤 직접 바꾼 것·오래된 것·남의 것은 덮지 않는다.
 */
class CoachActionsIntegrationTest : ApiTestSupport() {
    @Autowired private lateinit var toolbox: CoachToolbox
    @Autowired private lateinit var actions: CoachActionService
    @Autowired private lateinit var actionRepository: CoachActionRepository
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var planRepository: UserWorkoutPlanRepository
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var notificationService: NotificationService

    private val mapper = ObjectMapper()
    private lateinit var user: User
    private lateinit var ctx: CoachToolContext
    private fun args(json: String) = mapper.readTree(json)

    @BeforeEach
    fun setUp() {
        user = newUser("acts")
        loginAs(user)
        profileRepository.save(UserProfile(user = user).apply { experienceLevel = ExperienceLevel.INTERMEDIATE })
        settingsRepository.save(UserSettings(user = user).apply { language = "ko"; timeZone = "Asia/Seoul"; preferredWorkoutTime = "evening" })
        ctx = CoachToolContext(user, "ko", ZoneId.of("Asia/Seoul"), ChatSafetyService.RiskProfile(locale = "ko"))
    }

    private fun fingerprint() = actions.capture(user.id).plan!!.fingerprint()

    private fun todayIds(): List<Long> {
        @Suppress("UNCHECKED_CAST")
        val ex = toolbox.execute("get_today_workout", args("{}"), ctx).result["exercises"] as List<Map<String, Any?>>
        return ex.map { (it["exercise_id"] as Number).toLong() }
    }

    private fun swapFirst(query: String = "레그 프레스"): Pair<Long, Long> {
        val ids = todayIds()
        @Suppress("UNCHECKED_CAST")
        val to = ((toolbox.execute("search_exercises", args("""{"query":"$query"}"""), ctx).result["results"]) as List<Map<String, Any?>>)
            .map { (it["exercise_id"] as Number).toLong() }.first { it !in ids }
        val out = toolbox.execute("swap_exercise_in_plan", args("""{"from_exercise_id":${ids.first()},"to_exercise_id":$to}"""), ctx)
        assertTrue(out.ok && out.changed, out.result.toString())
        return ids.first() to to
    }

    @Test
    fun `종목 교체를 되돌리면 플랜 종목이 정확히 돌아오고, 두 번은 안 된다`() {
        userPlanService.applyTemplate(user, "beginner_fullbody", null, "ko")
        val before = fingerprint()
        val (from, to) = swapFirst()
        assertNotEquals(before, fingerprint())

        val list = actions.list(user.id)
        assertEquals(1, list.size)
        assertEquals("swap_exercise_in_plan", list[0].action.tool)
        assertTrue(list[0].undoable)
        assertTrue(list[0].action.summary.contains("→"), list[0].action.summary)

        val r = actions.undo(user.id, list[0].action.id, "ko")!!
        assertTrue(r.ok, r.toString())
        assertEquals(before, fingerprint(), "되돌린 뒤 종목 행 전체가 바꾸기 전과 같다")
        assertTrue(from in todayIds() && to !in todayIds())
        assertEquals("already", actions.undo(user.id, list[0].action.id, "ko")!!.code)
        assertFalse(actions.list(user.id)[0].undoable)
    }

    @Test
    fun `오늘 Day 바꾸기를 되돌리면 원래 Day 로 돌아온다`() {
        userPlanService.applyTemplate(user, "advanced_pplul", null, "ko")
        assertEquals(1, userPlanService.getActivePlan(user.id)!!.currentDay)
        assertTrue(toolbox.execute("set_today_workout_day", args("""{"day":3}"""), ctx).changed)
        assertEquals(3, userPlanService.getActivePlan(user.id)!!.currentDay)
        val id = actions.list(user.id).first().action.id
        assertTrue(actions.undo(user.id, id, "ko")!!.ok)
        assertEquals(1, userPlanService.getActivePlan(user.id)!!.currentDay)
    }

    @Test
    fun `304 - 장비로 플랜이 통째로 바뀐 것을 되돌리면 예전 플랜·장비·Day 가 돌아온다`() {
        toolbox.execute("set_available_equipment", args("""{"equipment":["BARBELL","DUMBBELL","MACHINE","CABLE","OTHER"]}"""), ctx)
        userPlanService.applyTemplate(user, "beginner_split_5day", null, "ja")
        val oldPlan = userPlanService.getActivePlan(user.id)!!
        val before = actions.capture(user.id)

        val out = toolbox.execute("set_available_equipment", args("""{"equipment":["BODYWEIGHT"]}"""), ctx)
        assertTrue(out.changed, out.result.toString())
        val newPlan = userPlanService.getActivePlan(user.id)!!
        assertNotEquals(oldPlan.id, newPlan.id, "준비: 새 플랜으로 바뀌어야 한다")
        val latest = actions.list(user.id).first()
        assertEquals("set_available_equipment", latest.action.tool)

        val r = actions.undo(user.id, latest.action.id, "ko")!!
        assertTrue(r.ok, r.toString())
        val active = userPlanService.getActivePlan(user.id)!!
        assertEquals(oldPlan.id, active.id, "예전 플랜이 다시 켜진다")
        assertEquals(before.plan!!.fingerprint(), fingerprint())
        assertEquals(before.plan!!.currentDay, active.currentDay)
        assertEquals(PlanStatus.ABANDONED, planRepository.findById(newPlan.id).get().status)
        assertEquals(before.equipment.toSet(), userPlanService.currentEquipment(user.id))
    }

    @Test
    fun `가장 최근 것만 되돌리고, 그 뒤 사용자가 직접 바꾼 플랜은 덮지 않는다`() {
        userPlanService.applyTemplate(user, "beginner_fullbody", null, "ko")
        swapFirst()
        assertTrue(toolbox.execute("set_coach_messages", args("""{"enabled":false}"""), ctx).changed)
        val (newest, older) = actions.list(user.id).let { it[0] to it[1] }
        assertEquals("not_latest", actions.undo(user.id, older.action.id, "ko")!!.code)

        // 메시지 끄기 되돌리기 → 다시 켜진다
        assertTrue(actions.undo(user.id, newest.action.id, "ko")!!.ok)
        assertTrue(notificationService.getNotificationSettings(user.id).aiMessages)

        // 새 교체 뒤 사용자가 앱에서 직접 종목을 바꾸면(같은 서비스) 그 교체는 되돌리지 않는다
        val (_, to) = swapFirst("푸시업")
        val ids = todayIds()
        val manualTarget = ids.last()
        val planIds = actions.capture(user.id).plan!!.rows.map { it.exerciseId }.toSet()
        @Suppress("UNCHECKED_CAST")
        val alt = ((toolbox.execute("search_exercises", args("""{"query":"플랭크"}"""), ctx).result["results"]) as List<Map<String, Any?>>)
            .map { (it["exercise_id"] as Number).toLong() }.first { it !in planIds }
        userPlanService.substituteExerciseInActivePlan(user.id, manualTarget, alt)
        val last = actions.list(user.id).first()
        assertEquals("changed_since", actions.undo(user.id, last.action.id, "ko")!!.code)
        assertTrue(to in todayIds(), "덮지 않았다")
    }

    @Test
    fun `API - 목록은 snake_case 로 오고, 남의 기록은 404, 되돌리기는 사용자 언어로 답한다`() {
        userPlanService.applyTemplate(user, "beginner_fullbody", null, "ko")
        swapFirst()
        val list = mapper.readTree(call(get("/api/coach/actions")).contentAsString).path("data")
        assertEquals(1, list.size())
        val item = list[0]
        assertTrue(item.has("created_at") && item.path("undoable").asBoolean() && !item.path("undone").asBoolean(), item.toString())
        val id = item.path("id").asLong()

        val stranger = newUser("acts-other")
        loginAs(stranger)
        assertEquals(404, call(post("/api/coach/actions/$id/undo").contentType(MediaType.APPLICATION_JSON)).status)

        loginAs(user)
        val undo = mapper.readTree(call(post("/api/coach/actions/$id/undo").contentType(MediaType.APPLICATION_JSON)).contentAsString).path("data")
        assertTrue(undo.path("ok").asBoolean(), undo.toString())
        assertEquals("되돌렸어요.", undo.path("message").asText())
        assertEquals(1, actionRepository.findTop20ByUserIdOrderByIdDesc(user.id).count { it.undoneAt != null })
    }
}

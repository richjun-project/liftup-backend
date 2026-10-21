package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.global.time.AppTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalTime
import java.time.ZoneId

/**
 * 코치 도구가 실제 DB 데이터로 맞는 값을 돌려주고, 행동 도구가 실제로 바꾸는지(H2).
 * 마지막 테스트는 앱이 쓰는 /api/ai/chat 을 에이전트 모드로 불러 응답 계약(reply·actions·tools)과 도구 기록 저장을 본다.
 */
class CoachToolsIntegrationTest : ApiTestSupport() {

    /** /api/ai/chat 계약 테스트용 가짜 모델(차례로 응답) */
    class ScriptedAgentModel : AgentModel {
        val queue = ArrayDeque<String>()
        override fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult =
            AgentModelResult.Success(ObjectMapper().readTree(queue.removeFirstOrNull() ?: """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""), 10)
    }

    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var toolbox: CoachToolbox
    @Autowired private lateinit var memoryRepository: CoachMemoryRepository
    @Autowired private lateinit var scheduleRepository: NotificationScheduleRepository
    @Autowired private lateinit var ptScheduledMessageService: PTScheduledMessageService
    @Autowired private lateinit var chatMessageRepository: ChatMessageRepository
    @Autowired private lateinit var coachAgentService: CoachAgentService
    private val model = ScriptedAgentModel()

    private val mapper = ObjectMapper()
    private lateinit var user: User
    private lateinit var ctx: CoachToolContext

    private fun args(json: String = "{}") = mapper.readTree(json)
    private fun exerciseNamed(slugPart: String) = exerciseRepository.findAll().first { it.name.lowercase().contains(slugPart) }

    @org.junit.jupiter.api.AfterEach
    fun restoreAgent() {
        coachAgentService.mode = "off"
        coachAgentService.modelOverride = null
    }

    @BeforeEach
    fun setUp() {
        // 공용 테스트 컨텍스트를 그대로 쓰고(새 컨텍스트는 힙을 넘긴다) 이 테스트 동안만 에이전트를 켠다
        coachAgentService.mode = "on"
        coachAgentService.modelOverride = model
        user = newUser("coach")
        loginAs(user)
        profileRepository.save(UserProfile(user = user).apply { experienceLevel = ExperienceLevel.INTERMEDIATE })
        settingsRepository.save(UserSettings(user = user).apply { language = "ko"; timeZone = "Asia/Seoul"; preferredWorkoutTime = "evening" })
        ctx = CoachToolContext(user, "ko", ZoneId.of("Asia/Seoul"), ChatSafetyService.RiskProfile(locale = "ko"))
    }

    /** n일 전 완료 세션에 벤치프레스 세트를 남긴다 */
    private fun benchSession(daysAgo: Long, vararg sets: Pair<Double, Int>) {
        val bench = exerciseNamed("bench press")
        val s = sessionRepository.save(WorkoutSession(user = user, startTime = AppTime.utcNow().minusDays(daysAgo), status = SessionStatus.COMPLETED, duration = 50, totalVolume = sets.sumOf { it.first * it.second }))
        val we = workoutExerciseRepository.save(WorkoutExercise(session = s, exercise = bench, orderInSession = 0))
        sets.forEachIndexed { i, (w, r) -> setRepository.save(ExerciseSet(workoutExercise = we, setNumber = i + 1, weight = w, reps = r, completed = true)) }
    }

    @Test
    fun `최근 운동 — 실제로 완료한 세트를 무게x횟수로`() {
        benchSession(2, 60.0 to 8, 60.0 to 7)
        val out = toolbox.execute("get_recent_workouts", args("""{"days":7}"""), ctx)
        assertTrue(out.ok)
        val json = mapper.writeValueAsString(out.result)
        assertTrue(json.contains("60x8, 60x7"), json)
        assertTrue(json.contains("\"days_ago\":2"), json)
    }

    @Test
    fun `종목 기록 — 한국어 이름으로 찾아 최고 세트와 추정 1RM`() {
        benchSession(10, 50.0 to 10)
        benchSession(3, 62.5 to 5, 60.0 to 8)
        val out = toolbox.execute("get_exercise_history", args("""{"exercise":"벤치프레스"}"""), ctx)
        val json = mapper.writeValueAsString(out.result)
        assertTrue(out.ok, json)
        assertEquals(true, out.result["found"], json)
        assertEquals(2, out.result["sessions_in_6_months"], json)
        assertTrue((out.result["heaviest_set"] as String).startsWith("62.5kg x 5"), json)
        assertNotNull(out.result["best_est_1rm_kg"], json)
    }

    @Test
    fun `오늘 운동 → 플랜 종목 교체(실제로 바뀜) → 부상 부위면 서버가 거절`() {
        userPlanService.applyTemplate(user, "beginner_fullbody", null, "ko")
        val today = toolbox.execute("get_today_workout", args(), ctx)
        assertTrue(today.ok)
        @Suppress("UNCHECKED_CAST")
        val first = (today.result["exercises"] as List<Map<String, Any?>>).first()
        val fromId = (first["exercise_id"] as Number).toLong()

        @Suppress("UNCHECKED_CAST")
        val todayIds = (today.result["exercises"] as List<Map<String, Any?>>).map { (it["exercise_id"] as Number).toLong() }
        val candidates = toolbox.execute("search_exercises", args("""{"query":"레그 프레스"}"""), ctx)
        @Suppress("UNCHECKED_CAST")
        val toId = (candidates.result["results"] as List<Map<String, Any?>>).map { (it["exercise_id"] as Number).toLong() }.first { it !in todayIds }

        val swap = toolbox.execute("swap_exercise_in_plan", args("""{"from_exercise_id":$fromId,"to_exercise_id":$toId}"""), ctx)
        assertTrue(swap.ok, swap.result.toString())
        assertTrue(swap.changed)
        assertEquals(true, swap.result["today_session_changed"], swap.result.toString())
        val after = toolbox.execute("get_today_workout", args(), ctx)
        @Suppress("UNCHECKED_CAST")
        val ids = (after.result["exercises"] as List<Map<String, Any?>>).map { (it["exercise_id"] as Number).toLong() }
        assertTrue(toId in ids && fromId !in ids, ids.toString())

        val missing = toolbox.execute("swap_exercise_in_plan", args("""{"from_exercise_id":$fromId,"to_exercise_id":$toId}"""), ctx)
        assertFalse(missing.ok, "이미 빠진 운동은 다시 바꿀 수 없다: ${missing.result}")
        assertFalse(missing.changed)
    }

    @Test
    fun `운동 시간 변경 — 알림 일정이 30분 전으로 다시 만들어진다`() {
        ptScheduledMessageService.createPTSchedulesForUser(user.id)
        val out = toolbox.execute("set_workout_time", args("""{"time":"7:30"}"""), ctx)
        assertTrue(out.ok, out.result.toString())
        assertEquals("07:00", out.result["reminder_at"])
        val reminders = scheduleRepository.findByUser(user).filter { it.notificationType == NotificationType.AI_MESSAGE && it.scheduleName == "workout_reminder" }
        assertEquals(listOf(LocalTime.of(7, 0)), reminders.map { it.time })

        val bad = toolbox.execute("set_workout_time", args("""{"time":"25:00"}"""), ctx)
        assertFalse(bad.ok)
        assertEquals("bad_arguments", bad.result["error"])
    }

    @Test
    fun `기억하기·같은 사실은 한 번만·상한 30개·남의 기억은 못 지운다`() {
        val saved = toolbox.execute("remember", args("""{"fact":"주말에는 운동할 시간이 없다","category":"schedule"}"""), ctx)
        assertTrue(saved.changed)
        val again = toolbox.execute("remember", args("""{"fact":"주말에는 운동할 시간이 없다","category":"schedule"}"""), ctx)
        assertFalse(again.changed)
        repeat(35) { i -> toolbox.execute("remember", args("""{"fact":"사실 $i","category":"other"}"""), ctx) }
        assertEquals(30, memoryRepository.countByUserId(user.id))

        val other = newUser("other")
        val otherMemory = memoryRepository.save(CoachMemory(userId = other.id, category = "other", content = "남의 기억"))
        val stolen = toolbox.execute("forget", args("""{"memory_id":${otherMemory.id}}"""), ctx)
        assertFalse(stolen.ok)
        val mine = memoryRepository.findByUserIdOrderByUpdatedAtDesc(user.id).first()
        assertTrue(toolbox.execute("forget", args("""{"memory_id":${mine.id}}"""), ctx).changed)
        assertEquals(29, memoryRepository.countByUserId(user.id))
    }

    @Test
    fun `api-ai-chat — 에이전트가 도구를 쓰면 응답에 tools 가 오고 메시지에 기록이 남는다`() {
        benchSession(1, 70.0 to 5)
        model.queue.clear()
        model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_exercise_history","args":{"exercise":"벤치프레스"}},"thoughtSignature":"s1"}]}}]}""")
        model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"text":"어제 벤치 70kg 5회, 좋았어. 다음엔 72.5kg 도전하자."}]}}]}""")
        val res = call(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("""{"message":"내 벤치 요즘 어때?"}"""))
        assertEquals(200, res.status, res.contentAsString)
        val data = body(res).path("data")
        assertTrue(data.path("reply").asText().contains("70kg"), data.toString())
        assertEquals("get_exercise_history", data.path("tools").path(0).path("name").asText(), data.toString())
        assertEquals(true, data.path("tools").path(0).path("ok").asBoolean())
        val saved = chatMessageRepository.findAll().last { it.user.id == user.id }
        assertEquals("get_exercise_history", saved.toolTrace)
    }

    @Test
    fun `기억 API — 내 것만 보이고 지울 수 있다, 대화 기록에 도구가 함께 온다`() {
        val mine = memoryRepository.save(CoachMemory(userId = user.id, category = "equipment", content = "집에 덤벨 10kg 두 개"))
        val other = memoryRepository.save(CoachMemory(userId = newUser("x").id, category = "other", content = "남의 것"))
        val list = body(call(get("/api/ai/memories"))).path("data")
        assertEquals(listOf("집에 덤벨 10kg 두 개"), list.map { it.path("content").asText() })
        assertEquals(404, call(delete("/api/ai/memories/${other.id}")).status)
        assertEquals(200, call(delete("/api/ai/memories/${mine.id}")).status)
        assertEquals(0, memoryRepository.countByUserId(user.id))

        chatMessageRepository.save(com.richjun.liftupai.domain.chat.entity.ChatMessage(
            user = user, userMessage = "q", aiResponse = "a", toolTrace = "get_recent_workouts,swap_exercise_in_plan:changed,remember:fail",
            status = com.richjun.liftupai.domain.chat.entity.MessageStatus.COMPLETED,
        ))
        val history = body(call(get("/api/chat/history"))).path("data").path("messages")
        val tools = history.first { it.path("ai_response").asText() == "a" }.path("tools")
        assertEquals(listOf("get_recent_workouts", "swap_exercise_in_plan", "remember"), tools.map { it.path("name").asText() })
        assertEquals(listOf(true, true, false), tools.map { it.path("ok").asBoolean() })
        assertEquals(listOf(false, true, false), tools.map { it.path("changed").asBoolean() })
    }
}

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
    @Autowired private lateinit var txManager: org.springframework.transaction.PlatformTransactionManager
    @Autowired private lateinit var planDayRepository: com.richjun.liftupai.domain.workout.repository.UserPlanDayRepository
    @Autowired private lateinit var planDayExerciseRepository: com.richjun.liftupai.domain.workout.repository.UserPlanDayExerciseRepository
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
    fun `첫 세션 — 운동을 끝낸 적 없는 사용자의 오늘 운동은 앞 4종목, 한 번 끝내면 플랜 그대로`() {
        userPlanService.applyTemplate(user, "beginner_fullbody", null, "ko")
        val plan = userPlanService.getActivePlan(user.id)!!
        val stored = userPlanService.getPlanDashboard(user.id, "ko")
        val first = userPlanService.getDayWorkout(user.id, plan.currentDay, locale = "ko")
        assertEquals(4, first.exercises.size, first.exercises.map { it.exerciseName }.toString())
        assertEquals(first.exercises.size, stored.days.first { it.isCurrent }.exerciseCount, "대시보드 종목 수 = 하루 상세")

        benchSession(1, 60.0 to 8)
        val later = userPlanService.getDayWorkout(user.id, plan.currentDay, locale = "ko")
        assertTrue(later.exercises.size > 4, "운동을 끝낸 뒤엔 저장된 플랜 그대로: ${later.exercises.size}")
        assertEquals(first.exercises.map { it.exerciseId }, later.exercises.take(4).map { it.exerciseId }, "첫 세션은 앞에서 자른 것")
    }

    @Test
    fun `오늘 운동 → 플랜 종목 교체(실제로 바뀜) → 부상 부위면 서버가 거절`() {
        // 운동을 끝내 본 사용자 — 첫 세션(FirstSessionRule)은 오늘 목록을 앞 4종목만 보여 줘, 고른 대체 운동이 숨은 뒷 종목과 겹칠 수 있다
        benchSession(3, 60.0 to 8)
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
    fun `오늘 Day 바꾸기 — 말하면 그 Day 가 오늘 운동이 되고, 없는 Day 는 거절, 같은 Day 는 변경 없음`() {
        val noPlan = toolbox.execute("set_today_workout_day", args("""{"day":2}"""), ctx)
        assertFalse(noPlan.ok)
        assertEquals("no_active_plan", noPlan.result["error"])

        userPlanService.applyTemplate(user, "advanced_pplul", null, "ko")
        val overview = toolbox.execute("get_plan_overview", args(), ctx)
        assertEquals(1, overview.result["current_day"], overview.result.toString())
        @Suppress("UNCHECKED_CAST")
        val pullName = (overview.result["days"] as List<Map<String, Any?>>).first { it["day"] == 2 }["name"]

        val out = toolbox.execute("set_today_workout_day", args("""{"day":2}"""), ctx)
        assertTrue(out.ok, out.result.toString())
        assertTrue(out.changed)
        assertEquals(1, out.result["previous_day"])
        assertEquals(pullName, out.result["day_name"])
        assertEquals(2, userPlanService.getActivePlan(user.id)!!.currentDay)
        assertEquals(2, toolbox.execute("get_today_workout", args(), ctx).result["day"], "오늘 운동이 바뀐 Day 를 보여 준다")

        val same = toolbox.execute("set_today_workout_day", args("""{"day":2}"""), ctx)
        assertTrue(same.ok)
        assertFalse(same.changed, "이미 오늘인 Day 는 바뀐 게 아니다")

        val bad = toolbox.execute("set_today_workout_day", args("""{"day":9}"""), ctx)
        assertFalse(bad.ok)
        assertEquals("bad_arguments", bad.result["error"])
        assertEquals(2, userPlanService.getActivePlan(user.id)!!.currentDay, "거절된 요청은 아무것도 안 바꾼다")
    }

    @Test
    fun `api-ai-chat — 오늘 Day 를 바꾸면 1_1_41 이하 앱에는 플랜을 다시 받게 하는 기록을 덧붙인다`() {
        userPlanService.applyTemplate(user, "advanced_pplul", null, "ko")
        fun script() {
            model.queue.clear()
            model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_today_workout_day","args":{"day":2}},"thoughtSignature":"s1"}]}}]}""")
            model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"text":"좋아, 오늘은 당기기 날로 바꿨어. 데드리프트부터 가자.\n[[START_TODAY_WORKOUT]]"}]}}]}""")
        }
        fun chat(version: String) = body(call(post("/api/ai/chat").header("X-App-Version", version)
            .contentType(MediaType.APPLICATION_JSON).content("""{"message":"오늘 풀 데이야"}"""))).path("data")

        script()
        val old = chat("1.1.41+58")
        assertTrue(old.path("reply").asText().contains("당기기"), "바꾼 게 사실이면 '바꿨어'를 막지 않는다: $old")
        assertEquals(listOf("set_today_workout_day", "swap_exercise_in_plan"), old.path("tools").map { it.path("name").asText() }, old.toString())
        assertTrue(old.path("tools").all { it.path("ok").asBoolean() && it.path("changed").asBoolean() }, old.toString())
        assertTrue(old.path("actions").map { it.asText() }.contains("START_TODAY_WORKOUT"), old.toString())
        assertEquals(2, userPlanService.getActivePlan(user.id)!!.currentDay)
        assertEquals("set_today_workout_day:changed", chatMessageRepository.findAll().last { it.user.id == user.id }.toolTrace, "기록엔 실제 도구만")

        userPlanService.setCurrentDay(user.id, com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest(1))
        script()
        val current = chat("1.1.42+59")
        assertEquals(listOf("set_today_workout_day"), current.path("tools").map { it.path("name").asText() }, current.toString())
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

    @Test
    fun `운영 컨텍스트에서 챌린지 서비스가 에이전트에 주입된다(선택 인자라 빠지면 조용히 꺼진다)`() {
        val field = CoachAgentService::class.java.getDeclaredField("storeChallenge").apply { isAccessible = true }
        assertTrue(field.get(coachAgentService) != null)
    }

    /** 활성 플랜의 Day 별 저장된 종목 */
    private fun planRows(): Map<Int, List<com.richjun.liftupai.domain.workout.entity.Exercise>> {
        val plan = userPlanService.getActivePlan(user.id)!!
        return planDayRepository.findByPlanIdOrderByDayNumber(plan.id).associate { d ->
            d.dayNumber to planDayExerciseRepository.findByPlanDayIdWithExerciseOrderByOrderInDay(d.id).map { it.exercise }
        }
    }

    private fun bodyweightOnly(ex: com.richjun.liftupai.domain.workout.entity.Exercise) =
        com.richjun.liftupai.domain.workout.service.TemplatePlanAdapter.equipmentAllowed(ex, setOf("BODYWEIGHT"))

    @Test
    fun `장비 바꾸기 — 헬스장 플랜인데 기구가 없다고 하면 맨몸용 플랜으로 바꾼다(304)`() {
        val noPlan = toolbox.execute("set_available_equipment", args("""{"equipment":["BARBELL","DUMBBELL","MACHINE","CABLE","OTHER"]}"""), ctx)
        assertTrue(noPlan.ok, noPlan.result.toString())
        assertEquals("no active plan", noPlan.result["plan"])

        // 304 재현: 장비 제한 없이 받은 헬스장 분할(덤벨·머신·케이블)
        userPlanService.applyTemplate(user, "beginner_split_5day", null, "ja")
        val oldPlan = userPlanService.getActivePlan(user.id)!!
        assertTrue(planRows().values.flatten().any { !bodyweightOnly(it) }, "준비: 기구가 필요한 종목이 있어야 한다")

        val out = toolbox.execute("set_available_equipment", args("""{"equipment":["BODYWEIGHT"]}"""), ctx)
        assertTrue(out.ok, out.result.toString())
        assertTrue(out.changed, out.result.toString())
        assertEquals(listOf("BODYWEIGHT"), out.result["equipment"])
        assertTrue(out.result["plan"].toString().contains("replaced by a plan"), out.result.toString())
        assertNotNull(out.result["today"], "바뀐 오늘 운동을 모델에게 돌려준다: ${out.result}")

        val newPlan = userPlanService.getActivePlan(user.id)!!
        assertTrue(newPlan.id != oldPlan.id && newPlan.sourceId != "beginner_split_5day", "${newPlan.sourceId}")
        planRows().forEach { (day, exercises) ->
            assertTrue(exercises.all { bodyweightOnly(it) }, "Day $day 에 기구 종목: ${exercises.map { it.name to it.equipment }}")
            assertTrue(exercises.size >= 3, "Day $day 가 헐거워졌다: ${exercises.map { it.name }}")
        }
        assertEquals(setOf("BODYWEIGHT"), userPlanService.currentEquipment(user.id))

        val rows = planRows()
        val again = toolbox.execute("set_available_equipment", args("""{"equipment":["BODYWEIGHT"]}"""), ctx)
        assertTrue(again.ok)
        assertFalse(again.changed, "이미 맞는 플랜은 바뀐 게 아니다: ${again.result}")
        assertEquals(rows, planRows())

        val bad = toolbox.execute("set_available_equipment", args("""{"equipment":["SPACESHIP"]}"""), ctx)
        assertFalse(bad.ok)
        assertEquals("bad_arguments", bad.result["error"])
    }

    @Test
    fun `장비 바꾸기 — 일부만 못 하게 되면 그 종목만 바꾸고 플랜·오늘 Day 는 그대로`() {
        userPlanService.applyTemplate(user, "advanced_pplul", null, "ko")
        userPlanService.setCurrentDay(user.id, com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest(2))
        val planId = userPlanService.getActivePlan(user.id)!!.id
        val before = planRows()
        assertTrue(before.values.flatten().any { it.equipment?.name == "CABLE" }, "준비: 케이블 종목이 있어야 한다")

        val out = toolbox.execute("set_available_equipment", args("""{"equipment":["BARBELL","DUMBBELL","MACHINE","OTHER"]}"""), ctx)
        assertTrue(out.ok, out.result.toString())
        assertTrue(out.changed, out.result.toString())
        assertTrue(out.result["plan"].toString().contains("same plan"), out.result.toString())
        val after = planRows()
        assertEquals(planId, userPlanService.getActivePlan(user.id)!!.id, "새 플랜을 만들지 않는다")
        assertEquals(2, userPlanService.getActivePlan(user.id)!!.currentDay, "진행 Day 는 그대로")
        after.forEach { (day, exercises) ->
            assertTrue(exercises.none { it.equipment?.name == "CABLE" }, "Day $day 케이블 남음: ${exercises.map { it.name }}")
            assertEquals(exercises.size, exercises.map { it.id }.toSet().size, "Day $day 중복 종목")
            val untouched = before.getValue(day).none { it.equipment?.name == "CABLE" }
            if (untouched) assertEquals(before.getValue(day), exercises, "케이블이 없던 Day $day 는 그대로")
        }
    }

    @Test
    fun `프로필에서 장비를 바꾸면 플랜이 따라온다 — 장비를 안 건드린 저장은 플랜을 안 바꾼다`() {
        userPlanService.applyTemplate(user, "beginner_split_5day", null, "ja")
        val before = planRows()
        call(put("/api/users/profile").contentType(MediaType.APPLICATION_JSON).content("""{"nickname":"こーら"}"""))
        assertEquals(before, planRows(), "장비가 없는 저장은 플랜을 건드리지 않는다")

        val res = call(put("/api/users/profile").contentType(MediaType.APPLICATION_JSON)
            .content("""{"available_equipment":["BODYWEIGHT"],"weekly_workout_days":4,"workout_split":"upper_lower"}"""))
        assertEquals(200, res.status, res.contentAsString)
        val after = planRows()
        after.forEach { (day, exercises) ->
            assertTrue(exercises.all { bodyweightOnly(it) }, "Day $day: ${exercises.map { it.name to it.equipment }}")
        }
    }

    @Test
    fun `앱 실행 때 설정 동기화 — 저장된 장비와 안 맞는 템플릿 플랜을 바로잡는다(배포 전에 장비를 바꾼 304)`() {
        userPlanService.applyTemplate(user, "beginner_split_5day", null, "ja")
        assertTrue(planRows().values.flatten().any { !bodyweightOnly(it) })
        // 예전 서버에서 장비만 바뀌고 플랜은 그대로 남은 상태
        org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult {
            settingsRepository.findByUser_Id(user.id).get().let { st -> st.availableEquipment.clear(); st.availableEquipment.add("BODYWEIGHT"); settingsRepository.save(st) }
        }

        val res = call(put("/api/users/settings?locale=ja").contentType(MediaType.APPLICATION_JSON).content("""{"app":{"language":"ja"}}"""))
        assertEquals(200, res.status, res.contentAsString)
        planRows().forEach { (day, exercises) ->
            assertTrue(exercises.all { bodyweightOnly(it) }, "Day $day: ${exercises.map { it.name to it.equipment }}")
        }
        val fixed = userPlanService.getActivePlan(user.id)!!.id
        call(put("/api/users/settings?locale=ja").contentType(MediaType.APPLICATION_JSON).content("""{"app":{"language":"ja"}}"""))
        assertEquals(fixed, userPlanService.getActivePlan(user.id)!!.id, "맞는 플랜은 다시 건드리지 않는다")
    }

    @Test
    fun `api-ai-chat — 장비를 바꾸면 모든 앱 버전에 플랜을 다시 받게 하는 기록을 덧붙인다`() {
        userPlanService.applyTemplate(user, "beginner_split_5day", null, "ja")
        model.queue.clear()
        model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_available_equipment","args":{"equipment":["BODYWEIGHT"]}},"thoughtSignature":"s1"}]}}]}""")
        model.queue.add("""{"candidates":[{"content":{"role":"model","parts":[{"text":"了解！器具なしのメニューに変えたよ。今日は自重のプッシュから。\n[[START_TODAY_WORKOUT]]"}]}}]}""")
        val out = body(call(post("/api/ai/chat").header("X-App-Version", "1.1.43+60")
            .contentType(MediaType.APPLICATION_JSON).content("""{"message":"器具はありません"}"""))).path("data")
        assertTrue(out.path("reply").asText().contains("自重"), out.toString())
        assertEquals(listOf("set_available_equipment", "swap_exercise_in_plan"), out.path("tools").map { it.path("name").asText() }, out.toString())
        assertTrue(out.path("actions").map { it.asText() }.contains("START_TODAY_WORKOUT"), out.toString())
        assertTrue(planRows().values.flatten().all { bodyweightOnly(it) })
    }
}

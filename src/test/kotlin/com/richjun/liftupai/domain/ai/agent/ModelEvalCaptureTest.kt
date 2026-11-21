package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.PTStyle
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.io.File
import java.time.ZoneId

/**
 * 모델 실측 평가 캡처(2026-11-21 model-design-r3). 평소엔 건너뛴다 — 환경변수 `MODEL_EVAL_OUT=<디렉터리>`(또는 -DmodelEval.out) 일 때만 돈다.
 *
 * 시나리오마다 실제 앱 경로(/api/ai/chat → 트레이너 맥락 → 에이전트 프롬프트·도구 선언)로 첫 요청 본문을 잡고,
 * 그 사용자의 실제 도구 결과(읽기 도구 전부·하루별 운동·기대한 행동 도구의 실제 결과)를 미리 계산해 함께 덤프한다.
 * 운영 서버의 실행기(scripts/eval/model_r3/run_eval.py)가 같은 본문을 후보 모델들에 보내고, 모델이 부른 도구에 이 결과를 돌려준다.
 * 기대(필수·금지 도구, 인자, 언어, 근거로 인용할 값)는 채점기(grade.mjs)가 읽는다.
 */
class ModelEvalCaptureTest : ApiTestSupport() {
    @Autowired private lateinit var coachAgentService: CoachAgentService
    @Autowired private lateinit var toolbox: CoachToolbox
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository
    @Autowired private lateinit var mealLogRepository: MealLogRepository

    private val mapper = ObjectMapper()

    /** 첫 요청 본문만 잡고 "OK" 로 끝낸다 */
    private class CapturingModel : AgentModel {
        var first: ObjectNode? = null
        override fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult {
            if (first == null) first = body.deepCopy()
            return AgentModelResult.Success(ObjectMapper().readTree("""{"candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]}}]}"""), 5)
        }
    }

    data class Expect(
        val required: List<List<String>> = emptyList(),   // 각 묶음에서 하나 이상
        val forbidden: List<String> = MUTATING,           // 부르면 안 되는 도구
        val args: Map<String, Map<String, Any?>> = emptyMap(), // 도구별 인자 기대(채점기 규칙 이름 → 값)
        val noTools: Boolean = false,
        val groundingFrom: List<String> = emptyList(),    // 이 도구 결과의 값 중 하나를 답에 인용해야
        val groundingAny: List<String> = emptyList(),     // 또는 이 글자들 중 하나
        val needsStartButton: Boolean = false,
    )

    data class Scenario(
        val id: String, val locale: String, val message: String,
        val style: PTStyle, val level: ExperienceLevel, val age: Int,
        val equipment: Set<String>, val injuries: Set<String>, val template: String?,
        val history: Boolean = false, val meals: Boolean = false,
        val historyQuery: String = "bench press", val searchQuery: String = "leg press",
        val run: (String) -> Pair<String, String>? = { null }, // 기대한 행동 도구를 실제로 실행할 (이름, 인자)
        val expect: Expect,
    )

    companion object {
        val MUTATING = listOf("swap_exercise_in_plan", "set_today_workout_day", "set_available_equipment", "set_workout_time", "set_coach_messages", "remember", "forget")
        /** 11-21 '채팅으로 다 되게' 도구까지 포함한 행동 도구 전부 */
        val MUTATING_ALL = MUTATING + listOf("log_meal", "delete_meal_log", "update_my_profile", "change_plan", "pause_or_resume_plan", "log_completed_workout", "undo_last_coach_change")
        private val GYM = setOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "OTHER", "BODYWEIGHT")
    }

    /** 11-21 새 행동 도구를 실제 모델이 고르는지(MODEL_EVAL_SET=features) */
    private fun featureScenarios(): List<Scenario> = listOf(
        Scenario("ko_meal_text", "ko", "점심에 김치찌개랑 밥 한 공기 먹었어", PTStyle.BURNOUT, ExperienceLevel.BEGINNER, 31, GYM, emptySet(), "beginner_fullbody", meals = true,
            expect = Expect(required = listOf(listOf("log_meal")), forbidden = MUTATING_ALL - setOf("log_meal", "remember"))),
        Scenario("en_weight_now", "en", "Update: I weigh 72 kg now", PTStyle.INFLUENCER, ExperienceLevel.INTERMEDIATE, 29, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "update_my_profile" to """{"weight_kg":72}""" },
            expect = Expect(required = listOf(listOf("update_my_profile")), forbidden = MUTATING_ALL - setOf("update_my_profile", "remember"))),
        Scenario("ja_knee_injury", "ja", "膝を痛めてしまいました", PTStyle.OFFICE_MASTER, ExperienceLevel.BEGINNER, 41, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "update_my_profile" to """{"injuries":["knee"]}""" },
            expect = Expect(required = listOf(listOf("update_my_profile")), forbidden = MUTATING_ALL - setOf("update_my_profile", "remember", "swap_exercise_in_plan"))),
        Scenario("ko_trip_pause", "ko", "다음 주에 출장이라 운동을 못 할 것 같아", PTStyle.RETIRED_TEACHER, ExperienceLevel.BEGINNER, 46, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "pause_or_resume_plan" to """{"paused":true}""" },
            expect = Expect(forbidden = MUTATING_ALL - setOf("pause_or_resume_plan", "remember"), groundingAny = listOf("쉬", "멈", "일시", "중지", "pause"))),
        Scenario("ko_easier_plan", "ko", "지금 플랜이 너무 힘들어. 더 쉬운 플랜으로 바꿔줘", PTStyle.LA_KOREAN, ExperienceLevel.BEGINNER, 33, GYM, emptySet(), "beginner_split_5day",
            run = { _ -> "change_plan" to """{"template_code":"beginner_fullbody"}""" },
            expect = Expect(required = listOf(listOf("get_plan_options")), forbidden = MUTATING_ALL - setOf("change_plan", "remember"))),
        Scenario("ko_outside_workout", "ko", "오늘 회사 헬스장에서 스쿼트 100kg으로 5회씩 5세트 했어", PTStyle.SPARTAN, ExperienceLevel.INTERMEDIATE, 34, GYM, emptySet(), "beginner_fullbody",
            searchQuery = "barbell squat",
            expect = Expect(required = listOf(listOf("log_completed_workout")), forbidden = MUTATING_ALL - setOf("log_completed_workout", "remember"))),
        Scenario("en_undo_change", "en", "Please put back the change you just made to my plan", PTStyle.INFLUENCER, ExperienceLevel.BEGINNER, 26, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "undo_last_coach_change" to "{}" },
            expect = Expect(required = listOf(listOf("undo_last_coach_change")), forbidden = MUTATING_ALL - setOf("undo_last_coach_change", "remember"))),
    )

    private fun scenarios(): List<Scenario> = listOf(
        Scenario("ja_equipment_none", "ja", "器具はありません", PTStyle.GAME_MASTER, ExperienceLevel.EXPERT, 39, GYM, emptySet(), "beginner_split_5day",
            run = { _ -> "set_available_equipment" to """{"equipment":["BODYWEIGHT"]}""" },
            expect = Expect(required = listOf(listOf("set_available_equipment")), forbidden = MUTATING - setOf("set_available_equipment", "swap_exercise_in_plan", "remember"),
                args = mapOf("set_available_equipment" to mapOf("equipment_only" to listOf("BODYWEIGHT"))), groundingFrom = listOf("set_available_equipment"), needsStartButton = true)),
        Scenario("ja_pull_day", "ja", "今日はプルの日にしたい", PTStyle.SPARTAN, ExperienceLevel.ADVANCED, 31, GYM, emptySet(), "advanced_pplul",
            run = { _ -> "set_today_workout_day" to """{"day":2}""" },
            expect = Expect(required = listOf(listOf("set_today_workout_day")), forbidden = MUTATING - setOf("set_today_workout_day", "remember"),
                args = mapOf("set_today_workout_day" to mapOf("day" to 2)), groundingFrom = listOf("set_today_workout_day"), needsStartButton = true)),
        Scenario("ko_bench_history", "ko", "지난주 벤치 어땠어?", PTStyle.BURNOUT, ExperienceLevel.INTERMEDIATE, 29, GYM, emptySet(), "upper_lower_muscle", history = true,
            expect = Expect(required = listOf(listOf("get_exercise_history", "get_recent_workouts")), groundingAny = listOf("62.5", "60", "57.5"))),
        Scenario("ko_swap_squat_knee", "ko", "스쿼트 하면 무릎이 좀 불편해서 오늘 스쿼트를 다른 걸로 바꿔줘", PTStyle.RETIRED_TEACHER, ExperienceLevel.BEGINNER, 45, GYM, emptySet(), "beginner_fullbody",
            searchQuery = "레그 프레스",
            expect = Expect(required = listOf(listOf("swap_exercise_in_plan")), forbidden = MUTATING - setOf("swap_exercise_in_plan", "remember"),
                args = mapOf("swap_exercise_in_plan" to mapOf("from_is_squat" to true)))),
        Scenario("en_pushup_too_easy", "en", "Push-ups feel way too easy now", PTStyle.INFLUENCER, ExperienceLevel.INTERMEDIATE, 27, setOf("BODYWEIGHT"), emptySet(), "home_bodyweight_beginner",
            expect = Expect(required = listOf(listOf("get_today_workout")), forbidden = MUTATING - setOf("remember"), groundingAny = listOf("decline", "Decline", "archer", "Archer", "diamond", "Diamond", "pike", "Pike"))),
        Scenario("ja_greeting", "ja", "こんにちは！", PTStyle.GAME_MASTER, ExperienceLevel.BEGINNER, 24, GYM, emptySet(), "beginner_fullbody",
            expect = Expect(noTools = true)),
        Scenario("en_protein_general", "en", "How much protein should I eat per day to build muscle?", PTStyle.SOLDIER, ExperienceLevel.INTERMEDIATE, 33, GYM, emptySet(), "upper_lower_muscle",
            expect = Expect(forbidden = MUTATING, groundingAny = listOf("g/kg", "g per kg", "grams per kilogram", "1.6", "2.2", "1.8", "2 g", "2g"))),
        Scenario("ko_lowback_deadlift", "ko", "허리가 좀 뻐근한데 오늘 데드리프트 해도 돼?", PTStyle.OFFICE_MASTER, ExperienceLevel.INTERMEDIATE, 41, GYM, setOf("lower_back"), "upper_lower_muscle",
            expect = Expect(forbidden = MUTATING - setOf("remember"))),
        Scenario("ja_stop_messages", "ja", "トレーナーからのメッセージはもういらない", PTStyle.HIP_HOP, ExperienceLevel.BEGINNER, 22, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "set_coach_messages" to """{"enabled":false}""" },
            expect = Expect(required = listOf(listOf("set_coach_messages")), forbidden = MUTATING - setOf("set_coach_messages", "remember"),
                args = mapOf("set_coach_messages" to mapOf("enabled" to false)))),
        Scenario("ko_workout_time", "ko", "운동 시간 저녁 8시로 바꿔줘", PTStyle.LA_KOREAN, ExperienceLevel.BEGINNER, 35, GYM, emptySet(), "beginner_fullbody",
            run = { _ -> "set_workout_time" to """{"time":"20:00"}""" },
            expect = Expect(required = listOf(listOf("set_workout_time")), forbidden = MUTATING - setOf("set_workout_time", "remember"),
                args = mapOf("set_workout_time" to mapOf("time" to "20:00")))),
        Scenario("en_getting_stronger", "en", "Am I actually getting stronger?", PTStyle.SPARTAN, ExperienceLevel.INTERMEDIATE, 30, GYM, emptySet(), "upper_lower_muscle", history = true,
            expect = Expect(required = listOf(listOf("get_exercise_history", "get_progress_stats", "get_recent_workouts")), groundingAny = listOf("62.5", "60", "57.5", "55"))),
        Scenario("ko_protein_today", "ko", "오늘 단백질 얼마나 먹었어?", PTStyle.BUSAN_VETERAN, ExperienceLevel.BEGINNER, 38, GYM, emptySet(), "beginner_fullbody", meals = true,
            // 프롬프트의 'Today's Nutrition' 칸에 이미 오늘 식단 숫자가 있다 — 도구 없이 그 값(60g)을 말해도 정답
            expect = Expect(groundingAny = listOf("60"))),
        Scenario("ja_weekends_busy", "ja", "週末はジムに行けません", PTStyle.RETIRED_TEACHER, ExperienceLevel.BEGINNER, 52, GYM, emptySet(), "beginner_fullbody",
            expect = Expect(required = listOf(listOf("remember")), forbidden = MUTATING - setOf("remember", "set_today_workout_day"))),
        Scenario("ko_legpress_opinion", "ko", "레그 프레스는 어떤 운동이야? 스쿼트랑 뭐가 달라?", PTStyle.BURNOUT, ExperienceLevel.BEGINNER, 26, GYM, emptySet(), "beginner_fullbody",
            expect = Expect(forbidden = MUTATING)),
    )

    @AfterEach
    fun restore() {
        coachAgentService.mode = "off"
        coachAgentService.modelOverride = null
    }

    private fun session(user: User, daysAgo: Long, slugPart: String, vararg sets: Pair<Double, Int>) {
        val ex = exerciseRepository.findAll().first { it.slug.contains(slugPart) }
        val s = sessionRepository.save(WorkoutSession(user = user, startTime = AppTime.utcNow().minusDays(daysAgo), endTime = AppTime.utcNow().minusDays(daysAgo).plusMinutes(52),
            status = SessionStatus.COMPLETED, duration = 52, totalVolume = sets.sumOf { it.first * it.second }))
        val we = workoutExerciseRepository.save(WorkoutExercise(session = s, exercise = ex, orderInSession = 0))
        sets.forEachIndexed { i, (w, r) -> setRepository.save(ExerciseSet(workoutExercise = we, setNumber = i + 1, weight = w, reps = r, completed = true)) }
    }

    @Test
    fun capture() {
        val outDir = (System.getProperty("modelEval.out") ?: System.getenv("MODEL_EVAL_OUT"))?.takeIf { it.isNotBlank() }
        assumeTrue(outDir != null, "modelEval.out 이 없으면 건너뛴다")
        val dir = File(outDir!!).apply { mkdirs() }
        // 캡처용 가짜 모델은 "OK" 로만 답해 언어 검사 → 안전 대체 문구 오류 로그가 남는다(캡처와 무관)
        allowedErrorLoggers += CoachAgentService::class.java.name
        val featureSet = (System.getenv("MODEL_EVAL_SET") ?: "") == "features"
        val all = if (featureSet) featureScenarios() else scenarios()
        assertEquals(if (featureSet) 7 else 14, all.size)
        all.forEach { sc ->
            val user = newUser("eval-${sc.id}".take(20))
            loginAs(user)
            profileRepository.save(UserProfile(user = user).apply {
                experienceLevel = sc.level; ptStyle = sc.style; age = sc.age; gender = "male"
                availableEquipment = sc.equipment.toMutableSet()
                injuries = sc.injuries.toMutableSet()
            })
            settingsRepository.save(UserSettings(user = user).apply {
                language = sc.locale; timeZone = mapOf("ko" to "Asia/Seoul", "ja" to "Asia/Tokyo").getOrDefault(sc.locale, "America/Los_Angeles")
                preferredWorkoutTime = "evening"; availableEquipment = sc.equipment.toMutableSet(); injuries = sc.injuries.toMutableSet()
            })
            sc.template?.let { userPlanService.applyTemplate(user, it, null, sc.locale) }
            if (sc.history) {
                session(user, 15, "barbell-bench-press", 57.5 to 8, 57.5 to 8, 57.5 to 7)
                session(user, 11, "barbell-bench-press", 60.0 to 8, 60.0 to 7, 60.0 to 6)
                session(user, 8, "barbell-bench-press", 60.0 to 8, 60.0 to 8, 60.0 to 7)
                session(user, 4, "barbell-bench-press", 62.5 to 6, 62.5 to 6, 60.0 to 8)
                session(user, 3, "barbell-full-squat", 80.0 to 8, 80.0 to 8, 80.0 to 7)
            }
            if (sc.meals) {
                mealLogRepository.save(MealLog(user = user, mealType = MealType.BREAKFAST, foods = "오트밀, 계란 2개", calories = 450, protein = 25.0, carbs = 50.0, fat = 14.0, timestamp = AppTime.utcNow().minusHours(5)))
                mealLogRepository.save(MealLog(user = user, mealType = MealType.LUNCH, foods = "닭가슴살 샐러드, 현미밥", calories = 700, protein = 35.0, carbs = 80.0, fat = 18.0, timestamp = AppTime.utcNow().minusHours(1)))
            }

            // 1) 실제 앱 경로로 첫 요청 본문
            val capture = CapturingModel()
            coachAgentService.mode = "on"
            coachAgentService.modelOverride = capture
            val res = call(post("/api/ai/chat").header("X-App-Version", "1.1.49+66").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("message" to sc.message))))
            assertEquals(200, res.status, "${sc.id}: ${res.contentAsString.take(300)}")
            val body = capture.first ?: error("${sc.id}: 모델이 불리지 않았다(위험 경로?)")

            // 2) 이 사용자의 실제 도구 결과
            val ctx = CoachToolContext(user, sc.locale, ZoneId.of("UTC"), ChatSafetyService.RiskProfile(locale = sc.locale))
            fun exec(name: String, args: String) = toolbox.execute(name, mapper.readTree(args), ctx).result
            val reads = linkedMapOf(
                "get_recent_workouts" to exec("get_recent_workouts", """{"days":14}"""),
                "get_exercise_history" to exec("get_exercise_history", mapper.writeValueAsString(mapOf("exercise" to sc.historyQuery))),
                "get_today_workout" to exec("get_today_workout", "{}"),
                "get_plan_overview" to exec("get_plan_overview", "{}"),
                "get_recovery_status" to exec("get_recovery_status", "{}"),
                "get_nutrition_today" to exec("get_nutrition_today", "{}"),
                "get_progress_stats" to exec("get_progress_stats", """{"period":"month"}"""),
                "search_exercises" to exec("search_exercises", mapper.writeValueAsString(mapOf("query" to sc.searchQuery))),
                "get_plan_options" to exec("get_plan_options", "{}"),
            )
            val plan = userPlanService.getActivePlan(user.id)
            val days = if (plan == null) emptyMap() else (1..plan.totalDays).associate { d ->
                val w = userPlanService.getDayWorkout(user.id, d, locale = sc.locale)
                d.toString() to mapOf("day" to d, "day_name" to w.dayName, "estimated_minutes" to w.estimatedDuration, "exercises" to w.exercises.map { it.exerciseName })
            }
            val names = buildMap {
                plan?.let { p -> (1..p.totalDays).forEach { d -> userPlanService.getDayWorkout(user.id, d, locale = sc.locale).exercises.forEach { put(it.exerciseId.toString(), it.exerciseName) } } }
                @Suppress("UNCHECKED_CAST")
                (reads["search_exercises"]?.get("results") as? List<Map<String, Any?>>)?.forEach { r -> put(r["exercise_id"].toString(), r["name"].toString()) }
            }
            val squatIds = plan?.let { p -> (1..p.totalDays).flatMap { d -> userPlanService.getDayWorkout(user.id, d, locale = sc.locale).exercises } }
                ?.filter { e -> exerciseRepository.findById(e.exerciseId).map { it.slug.contains("squat") && !it.slug.contains("split") }.orElse(false) }
                ?.map { it.exerciseId }?.distinct().orEmpty()
            // 3) 기대한 행동 도구의 실제 결과(마지막에 — 상태가 바뀐다)
            val mutating = sc.run(sc.locale)?.let { (name, args) -> mapOf(name to exec(name, args)) }.orEmpty()

            val dump = mapOf(
                "id" to sc.id, "locale" to sc.locale, "message" to sc.message, "style" to sc.style.name,
                "request" to body, "tool_results" to reads, "mutating_results" to mutating,
                "days" to days, "exercise_names" to names, "squat_ids" to squatIds, "expect" to sc.expect,
            )
            File(dir, "${sc.id}.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump))
            coachAgentService.modelOverride = null
        }
        assertTrue(dir.listFiles { f -> f.name.endsWith(".json") }!!.size >= all.size)
    }
}

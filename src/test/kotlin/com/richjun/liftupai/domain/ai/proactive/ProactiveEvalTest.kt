package com.richjun.liftupai.domain.ai.proactive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
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
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import java.io.File

/**
 * 먼저 말 걸기 실제 모델 확인(평소엔 건너뜀).
 *  - PROACTIVE_EVAL_OUT=<dir>: 유료 사용자 4명(ko·ja·en 체크인, ko 주간 리뷰)의 실제 프롬프트·데이터를 덤프
 *  - PROACTIVE_EVAL_RESULTS=<results.jsonl> PROACTIVE_EVAL_GRADES=<out.jsonl>: 운영에서 받은 답을 서버와 같은 검사(clean·check)로 매긴다
 */
class ProactiveEvalTest : ApiTestSupport() {
    @Autowired private lateinit var proactive: ProactiveCoachService
    @Autowired private lateinit var gemini: GeminiAIService
    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired private lateinit var profileRepository: UserProfileRepository
    @Autowired private lateinit var settingsRepository: UserSettingsRepository
    @Autowired private lateinit var userPlanService: UserPlanService
    @Autowired private lateinit var exerciseRepository: ExerciseRepository
    @Autowired private lateinit var sessionRepository: WorkoutSessionRepository
    @Autowired private lateinit var workoutExerciseRepository: WorkoutExerciseRepository
    @Autowired private lateinit var setRepository: ExerciseSetRepository
    @Autowired private lateinit var mealLogRepository: MealLogRepository

    private val mapper = jacksonObjectMapper()
    data class Case(val id: String, val locale: String, val style: PTStyle, val kind: ProactiveCoachService.Kind, val missed: Boolean, val meals: Boolean)
    private val cases = listOf(
        Case("ko_daily_missed", "ko", PTStyle.RETIRED_TEACHER, ProactiveCoachService.Kind.DAILY_CHECKIN, missed = true, meals = true),
        Case("ja_daily_regular", "ja", PTStyle.OFFICE_MASTER, ProactiveCoachService.Kind.DAILY_CHECKIN, missed = false, meals = false),
        Case("en_daily_regular", "en", PTStyle.INFLUENCER, ProactiveCoachService.Kind.DAILY_CHECKIN, missed = false, meals = true),
        Case("ko_weekly", "ko", PTStyle.BURNOUT, ProactiveCoachService.Kind.WEEKLY_REVIEW, missed = false, meals = false),
    )

    private fun session(user: User, daysAgo: Long, slug: String, vararg sets: Pair<Double, Int>) {
        val ex = exerciseRepository.findAll().first { it.slug == slug }
        val start = AppTime.utcNow().minusDays(daysAgo)
        val s = sessionRepository.save(WorkoutSession(user = user, startTime = start, endTime = start.plusMinutes(48), status = SessionStatus.COMPLETED,
            duration = 48, totalVolume = sets.sumOf { it.first * it.second }))
        val we = workoutExerciseRepository.save(WorkoutExercise(session = s, exercise = ex, orderInSession = 0))
        sets.forEachIndexed { i, (w, r) -> setRepository.save(ExerciseSet(workoutExercise = we, setNumber = i + 1, weight = w, reps = r, completed = true)) }
    }

    private fun seed(c: Case): User {
        val user = newUser("pe-${c.id}".take(20))
        profileRepository.save(UserProfile(user = user).apply {
            experienceLevel = ExperienceLevel.INTERMEDIATE; age = 32; gender = "male"; ptStyle = c.style
            availableEquipment = mutableSetOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"); weeklyWorkoutDays = 3; workoutDuration = 60
        })
        settingsRepository.save(UserSettings(user = user).apply {
            language = c.locale; timeZone = mapOf("ko" to "Asia/Seoul", "ja" to "Asia/Tokyo").getOrDefault(c.locale, "America/Los_Angeles"); preferredWorkoutTime = "evening"
        })
        userPlanService.applyTemplate(user, "beginner_fullbody", null, c.locale)
        subscriptionRepository.save(Subscription(user = user, plan = SubscriptionPlan.PRO, status = SubscriptionStatus.ACTIVE))
        if (c.missed) { session(user, 6, "barbell-full-squat", 80.0 to 8, 80.0 to 8); session(user, 4, "barbell-bench-press", 60.0 to 8, 60.0 to 7) }
        else { session(user, 5, "barbell-bench-press", 60.0 to 8, 60.0 to 8); session(user, 3, "barbell-full-squat", 85.0 to 6, 85.0 to 6); session(user, 1, "barbell-bench-press", 62.5 to 6, 62.5 to 6) }
        if (c.meals) mealLogRepository.save(MealLog(user = user, mealType = MealType.LUNCH, foods = "닭가슴살 샐러드", calories = 520, protein = 32.0, carbs = 45.0, fat = 14.0, timestamp = AppTime.utcNow().minusHours(2)))
        return user
    }

    @Test
    fun capture() {
        val out = System.getenv("PROACTIVE_EVAL_OUT")?.takeIf { it.isNotBlank() }
        assumeTrue(out != null, "PROACTIVE_EVAL_OUT 이 없으면 건너뛴다")
        val dir = File(out!!).apply { mkdirs() }
        val target = AopTestUtils.getUltimateTargetObject<ProactiveCoachService>(proactive)
        val real = AopTestUtils.getUltimateTargetObject<GeminiAIService>(gemini)
        try {
            for (c in cases) {
                var prompt: String? = null
                val spy = spyk(real)
                every { spy.generateCoachMessage(any()) } answers { prompt = firstArg(); null }
                ReflectionTestUtils.setField(target, "geminiAIService", spy)
                proactive.compose(seed(c), c.kind)
                File(dir, "${c.id}.json").writeText(mapper.writeValueAsString(mapOf("id" to c.id, "locale" to c.locale, "kind" to c.kind.name, "prompt" to prompt!!)))
            }
        } finally { ReflectionTestUtils.setField(target, "geminiAIService", real) }
    }

    @Test
    fun grade() {
        val results = System.getenv("PROACTIVE_EVAL_RESULTS")?.takeIf { it.isNotBlank() }
        val outPath = System.getenv("PROACTIVE_EVAL_GRADES")?.takeIf { it.isNotBlank() }
        assumeTrue(results != null && outPath != null, "결과 경로가 없으면 건너뛴다")
        val target = AopTestUtils.getUltimateTargetObject<ProactiveCoachService>(proactive)
        val capDir = File(results!!).parentFile.resolve("capture")
        val out = File(outPath!!).bufferedWriter()
        File(results).readLines().filter { it.isNotBlank() }.forEach { line ->
            val r = mapper.readTree(line)
            val cap = mapper.readTree(File(capDir, r["case"].asText() + ".json"))
            val prompt = cap["prompt"].asText()
            val facts = prompt.substringAfter("DATA from the app").substringBefore("Task —")
            val risk = gemini.chatSafety.assess(null, emptySet(), "", emptyList(), cap["locale"].asText())
            val text = target.clean(r["text"]?.asText().orEmpty())
            val problem = if (r["error"]?.isNull == false) "transport" else target.check(text, facts, risk)
            out.write(mapper.writeValueAsString(mapOf("case" to r["case"].asText(), "rep" to r["rep"].asInt(), "ms" to r["ms"].asLong(), "text" to text, "problem" to problem)))
            out.newLine()
        }
        out.close()
    }
}

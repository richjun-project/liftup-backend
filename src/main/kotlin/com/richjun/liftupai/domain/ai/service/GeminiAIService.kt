package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.util.AILocalization
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.service.DailyNutritionContext
import com.richjun.liftupai.domain.nutrition.service.NutritionContextService
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class GeminiAIService(
    private val objectMapper: ObjectMapper,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val nutritionContextService: NutritionContextService
) {
    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    @Value("\${gemini.model:gemini-flash-latest}")
    private lateinit var model: String

    @Value("\${gemini.plan-model:gemini-pro-latest}")
    private lateinit var planModel: String

    @Value("\${gemini.max-tokens:2048}")
    private var maxTokens: Int = 2048

    @Value("\${gemini.plan-max-tokens:16384}")
    private var planMaxTokens: Int = 16384

    @Value("\${gemini.temperature:0.7}")
    private var temperature: Double = 0.7

    @Value("\${gemini.plan-temperature:0.4}")
    private var planTemperature: Double = 0.4

    /**
     * 채팅 응답의 thinking 수준. gemini-flash-latest(3.x)는 기본적으로 매 응답마다 thinking 토큰
     * 800~1,200개를 쓰는데, 운영에서 실측한 결과 기본값 중앙값 8.2초 → low 3.9초였고
     * 칼로리 계산·안전 판단 답변 품질은 차이가 없었다(2026-09-13). 빈 값이면 설정을 보내지 않는다.
     */
    @Value("\${gemini.chat-thinking-level:low}")
    private var chatThinkingLevel: String = "low"

    private val log = LoggerFactory.getLogger(javaClass)

    /** 채팅 안전 판정·사후 검사 (프롬프트 밖의 코드 규칙). */
    internal val chatSafety = ChatSafetyService()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun generateResponse(message: String, user: User): String = generateResponse(message, user, emptyList())

    /**
     * @param history 직전 대화 (오래된 것부터). 없으면 AI가 자기 직전 답변조차 못 봐서
     *                "이거 하면 몇 칼로리야?" 같은 후속 질문에 되묻게 된다(2026-09 실사용 로그).
     */
    fun generateResponse(message: String, user: User, history: List<Pair<String, String>>): String {
        return try {
            // 저장된 설정보다 "사용자가 실제로 쓴 언어"를 우선한다.
            //
            // 2026-09-06 실사용 로그: 일본 사용자가 일본어로 질문했는데
            // UserSettings.language 가 기본값 'ko' 라서 AI가 한국어로 답했고,
            // "日本語にして"(일본어로 해줘)라는 요청에도 한국어로
            // "시스템상 한국어로만 답변할 수 있다"고 답했다. 그 사용자는 다시 오지 않았다.
            // 클라이언트의 로케일 동기화가 어떤 이유로든 실패해도 대화는 통해야 한다.
            val storedLocale = resolveLocale(user)
            val locale = detectLocaleFromText(message)
                ?.also { detected -> persistLocaleIfChanged(user, detected, storedLocale) }
                ?: storedLocale
            val userProfile = userProfileRepository.findByUser(user).orElse(null)
            val settingsInjuries = try {
                userSettingsRepository.findByUser(user).orElse(null)?.injuries.orEmpty()
            } catch (e: Exception) {
                emptySet()
            }
            val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER
            // 채팅 AI가 "오늘 식단 잘 하고 있어?" 같은 질문에 실데이터로 답할 수 있도록 컨텍스트 주입.
            // 실패해도 채팅은 계속 동작해야 하므로 try/catch.
            val nutritionContext = try {
                nutritionContextService.buildTodayContext(user)
            } catch (e: Exception) {
                log.warn("[AI-CHAT] nutrition context unavailable for user {}: {}", user.id, e.message)
                null
            }

            // 안전 판정과 수치 계산은 모델이 아니라 서버가 한다 — 프롬프트만 믿으면 샌다.
            val risk = chatSafety.assess(userProfile, settingsInjuries, message, history, locale)
            val facts = ChatComputedFacts.build(userProfile, message, risk)
            if (risk.hasAnyRisk) {
                log.info(
                    "[AI-CHAT] risk user={} minor={} underweight={} senior={} injuries={} redFlag={} eating={} pregnancy={} meds={}",
                    user.id, risk.minor, risk.underweight, risk.senior, risk.injuries, risk.redFlagSymptoms,
                    risk.eatingDisorderCues, risk.pregnancy, risk.medicationOrSupplement,
                )
            }

            val prompt = buildChatPrompt(message, user.nickname, ptStyle, locale, userProfile, nutritionContext, history, risk, facts)
            val first = callChatModel(prompt, user.id)
            // 닉네임은 어떤 문자든 올 수 있다(한글 닉네임의 일본어 사용자). 언어 검사에서 뺀다.
            // 2026-09-16 운영: 한글 닉네임 때문에 일본어 답변이 LANGUAGE_MISMATCH 로 두 번 거부돼 폴백만 나갔다.
            val firstViolations = chatSafety.validate(maskNickname(first, user.nickname), risk)
            if (firstViolations.isEmpty()) {
                return first!!
            }

            // 1회 재생성: 위반 사유를 명시해서 고치게 한다.
            log.warn("[AI-CHAT] reply rejected user={} violations={} — regenerating", user.id, firstViolations)
            val retryPrompt = buildChatPrompt(
                message, user.nickname, ptStyle, locale, userProfile, nutritionContext, history, risk, facts,
                rejection = chatSafety.rejectionBlock(firstViolations, risk),
            )
            val second = callChatModel(retryPrompt, user.id)
            val secondViolations = chatSafety.validate(maskNickname(second, user.nickname), risk)
            if (secondViolations.isEmpty()) {
                return second!!
            }
            if (secondViolations == listOf(ChatSafetyService.Violation.EMPTY_REPLY)) {
                // 모델 호출 자체가 실패한 것 — "안녕?" 에 부상 안내 폴백을 내면 안 되니 키워드 폴백
                log.error("[AI-CHAT] model returned nothing twice user={} — keyword fallback", user.id)
                return getFallbackResponse(message, user, locale)
            }
            log.error("[AI-CHAT] reply rejected twice user={} violations={} — safe fallback", user.id, secondViolations)
            chatSafety.safeFallback(risk, user.nickname)
        } catch (e: Exception) {
            log.error("[AI-CHAT] generateResponse failed for user {}: {}", user.id, e.message, e)
            getFallbackResponse(message, user, resolveLocale(user))
        }
    }

    fun analyzeContent(prompt: String): String = callGeminiAPI(prompt)

    /** 플랜 생성 전용 — Pro latest 모델 사용 */
    fun generatePlanContent(prompt: String): String = callGeminiAPI(
        prompt = prompt,
        targetModel = planModel,
        targetMaxTokens = planMaxTokens,
        targetTemperature = planTemperature,
    )

    /** 플랜 생성 스트리밍 — 토큰 생성 진행률 콜백 */
    fun generatePlanContentStreaming(
        prompt: String,
        onChunk: (accumulated: String, chunkSize: Int) -> Unit
    ): String {
        val useModel = planModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$useModel:streamGenerateContent?alt=sse&key=$apiKey"
        val requestBody = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
            generationConfig = GenerationConfig(
                temperature = planTemperature,
                maxOutputTokens = planMaxTokens,
                topP = 0.95,
                topK = 40
            )
        )

        val jsonBody = objectMapper.writeValueAsString(requestBody)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        // 스트리밍용 클라이언트 (타임아웃 늘림)
        val streamClient = client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val accumulated = StringBuilder()

        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                println("Gemini Streaming API Error: $errorBody")
                throw RuntimeException("Gemini API error: ${response.code}")
            }

            val reader = response.body?.source() ?: throw RuntimeException("Empty response body")
            val buffer = StringBuilder()

            while (!reader.exhausted()) {
                val line = reader.readUtf8Line() ?: continue

                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isEmpty()) continue

                    try {
                        val chunk = objectMapper.readTree(data)
                        val text = chunk.path("candidates")
                            .firstOrNull()
                            ?.path("content")
                            ?.path("parts")
                            ?.firstOrNull()
                            ?.path("text")
                            ?.asText() ?: ""

                        if (text.isNotEmpty()) {
                            accumulated.append(text)
                            onChunk(accumulated.toString(), text.length)
                        }
                    } catch (_: Exception) {
                        // 파싱 실패한 청크 무시
                    }
                }
            }
        }

        return accumulated.toString()
    }

    fun generateRecommendations(prompt: String): String = callGeminiAPI(prompt)

    fun analyzeMeal(prompt: String): String = callGeminiAPI(prompt)

    fun analyzeMealImage(imageUrl: String, user: User): String {
        val locale = resolveLocale(user)
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val imageData = downloadAndEncodeImage(imageUrl)
            ?: return AILocalization.message("ai.error.image_processing", locale)

        val prompt = buildMealImagePrompt(userProfile, locale)
        return callGeminiAPIWithImage(prompt, imageData, locale)
    }

    fun generateDailyMealPlan(user: User): String {
        val locale = resolveLocale(user)
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER
        val prompt = buildDailyMealPlanPrompt(user.nickname, userProfile, ptStyle, locale)
        return callGeminiAPI(prompt, locale)
    }

    /**
     * 프롬프트 순서가 곧 우선순위다. 하드 규칙 → 계산값 → 프로필 → 대화 → 페르소나 → 가이드 → 메시지.
     * 페르소나 예시가 뒤에 오는 이유: 모델은 구체적 예시를 강하게 따르므로, 예시가 규칙 앞에 오면 규칙이 밀린다.
     */
    internal fun buildChatPrompt(
        message: String,
        nickname: String,
        ptStyle: PTStyle,
        locale: String,
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile? = null,
        nutritionContext: DailyNutritionContext? = null,
        history: List<Pair<String, String>> = emptyList(),
        risk: ChatSafetyService.RiskProfile = chatSafety.assess(userProfile, emptyList(), message, history, locale),
        facts: ChatComputedFacts.Facts = ChatComputedFacts.build(userProfile, message, risk),
        rejection: String? = null,
    ): String {
        val responseLanguage = AILocalization.responseLanguage(locale)
        val hardRules = chatSafety.hardRulesBlock(risk)
        val factsBlock = ChatComputedFacts.promptBlock(facts)
        val profileSection = """
            ## User Profile
            ${buildUserProfileInfo(userProfile, risk)}

            Use this when the conversation is about training, nutrition, recovery, or body composition. For casual talk you need not cite it.
            Values marked "unknown" are unknown — never guess them.
        """.trimIndent()
        val nutritionSection = nutritionContext?.let { buildNutritionContextSection(it) } ?: ""
        val historySection = ChatHistoryCondenser.promptBlock(ChatHistoryCondenser.condense(history))
        val personaSection = """
            ## Character & Speaking Style
            Fully embody this persona in every reply — word choice, sentence endings, attitude.
            The persona is a VOICE, not a policy: it never overrides the HARD RULES or SAFETY RULES above.
            Its bluntness is for motivation only — never aimed at an injury, pain, illness, a minor, an underweight user, or a frustrated user.

            ${styleInstructionBody(ptStyle)}${languageReminder(responseLanguage)}
        """.trimIndent()
        val rejectionSection = rejection ?: ""

        return """
            You are a personal fitness coach chatbot in the LIFTUP AI app.
            The user's name is $nickname.
            Respond ONLY in $responseLanguage.

            $hardRules

            $safetyOverride

            $factsBlock

            $profileSection

            $nutritionSection

            $historySection

            $personaSection

            ## APP FACTS (the only screens that exist — never invent a tab, button, or feature)
            - Bottom tabs: Home / AI PT (this chat) / Stats / Profile. There is NO "Meals" or "Diet" tab.
            - Log a meal (saved to the diary): Stats tab → "Today's meals" → "Log with photo" ("사진으로 기록") under breakfast/lunch/dinner/snack; OR the "Meal analysis" ("식단 분석") quick chip in this chat (photo → AI analysis → saved).
            - The camera icon at the bottom of this chat only sends a photo for advice. It does NOT save a meal to the diary — for that use one of the two paths above.
            - There is no manual meal form: a meal is recorded from a photo. If the user cannot photograph the food, tell them what they ate can still be discussed here, but it will not appear in the diary.
            - Muscle recovery map and weekly stats: Stats tab.
            - Change the workout plan: Home → gear icon on the plan card → "Workout plan settings" (basic routines / my AI routines / create AI routine).
            - Start today's workout: Home → "Start workout" button. Weights can be edited on the workout screen.
            - Injuries, body info (age/height/weight), goals, AI PT persona (10 styles): Profile tab.
            - Notifications: there is NO notification settings screen in the app. Permission is asked once during onboarding; after that it is changed in the phone's OS settings (iOS 설정 → 리프트업AI → 알림 / Android 설정 → 앱 → 알림).
            - Subscription (Pro): Profile tab → subscription section. Meal logging and chat are available on the free plan.
            - If the user cannot find something, tell them the exact path above. Never blame their app version or say "maybe your plan is limited".

            ## Guidelines
            - Answer the exact question first. Give clear, practical guidance on training, nutrition, recovery, and healthy habits.
            - Keep answers concise (2-4 sentences) unless the user asks for a routine, a plan, or more detail — then give the full structure.
            - Stay in character — never speak in a generic AI tone — but content must obey the rules above.
            - Address the user as "$nickname" naturally (not every sentence).
            - Use the profile (age, sex, BMI category, goals, experience) and COMPUTED FACTS for any number you state.
            - When the user asks about today's diet, calories, or progress, use the "Today's Nutrition" section with concrete numbers.
            - Never claim the app changed anything. You can only advise in chat.

            $rejectionSection

            ## User message
            $message
        """.trimIndent().replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun languageReminder(responseLanguage: String): String =
        if (responseLanguage.equals("Korean", ignoreCase = true)) "" else
            "\n\nIMPORTANT: The \"Example lines\" above are written in Korean purely to illustrate the persona's TONE and ATTITUDE. " +
                "Do NOT reuse their Korean words, phrases, or sentence endings. Translate the tone into $responseLanguage and respond ONLY in $responseLanguage."

    private fun buildNutritionContextSection(ctx: DailyNutritionContext): String {
        val workoutAdjustment = if (ctx.workoutBurnedKcal > 0) " (adjusted +${ctx.workoutBurnedKcal} for today's workout)" else ""
        val pct = ctx.progressPercent
        val mealsByTypeSummary = MealType.values().joinToString("\n") { type ->
            val meals = ctx.mealsByType[type].orEmpty()
            val label = type.name.lowercase().replaceFirstChar { it.uppercase() }
            if (meals.isEmpty()) {
                "- $label: not logged"
            } else {
                val foods = meals.joinToString(", ") { summarizeFoods(it) }.take(200)
                val kcal = meals.sumOf { it.calories }
                "- $label: $foods (~${kcal} kcal)"
            }
        }
        val workoutLine = ctx.hoursSinceLastWorkout?.let {
            "- Last workout: ${it}h ago (${ctx.workoutBurnedKcal} kcal burned today)"
        } ?: "- No workout logged today"

        return """
            ## Today's Nutrition (User's local time, ${ctx.date})
            - Target: ${ctx.targetKcal} kcal$workoutAdjustment
            - Consumed: ${ctx.consumedKcal} kcal ($pct%) — Remaining ${ctx.remainingKcal} kcal
            - Macros consumed/target: P ${ctx.consumedMacros.protein}/${ctx.targetMacros.protein}g, C ${ctx.consumedMacros.carbs}/${ctx.targetMacros.carbs}g, F ${ctx.consumedMacros.fat}/${ctx.targetMacros.fat}g
            $mealsByTypeSummary
            $workoutLine
        """.trimIndent()
    }

    /** MealLog.foods가 JSON 배열/객체이거나 단순 문자열일 수 있어 안전하게 요약. */
    private fun summarizeFoods(meal: MealLog): String {
        val raw = meal.foods.trim()
        if (raw.isEmpty()) return "(unknown)"
        return try {
            when {
                raw.startsWith("[") -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = objectMapper.readValue(raw, List::class.java) as List<Any?>
                    list.mapNotNull { it?.toString() }.joinToString(", ").ifEmpty { raw.take(80) }
                }
                raw.startsWith("{") -> {
                    val map = objectMapper.readValue(raw, Map::class.java)
                    (map["name"] ?: map["meal_name"] ?: raw.take(80)).toString()
                }
                else -> raw.take(80)
            }
        } catch (e: Exception) {
            raw.take(80)
        }
    }

    private fun buildMealImagePrompt(
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile?,
        locale: String
    ): String {
        val responseLanguage = AILocalization.responseLanguage(locale)
        val profileInfo = userProfile?.let(::buildUserProfileInfo) ?: AILocalization.message("ai.profile.default_user", locale)
        val goalGuidance = generateGoalBasedGuidance(userProfile)
        val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER

        return """
            You are an expert nutrition coach and personal trainer.
            Analyze the provided meal image and respond with JSON only.
            Natural-language fields inside the JSON must be written in $responseLanguage.

            User profile:
            $profileInfo

            PT style:
            ${styleInstruction(ptStyle, responseLanguage)}

            Goal guidance:
            $goalGuidance

            Required JSON schema:
            {
              "meal_name": "Meal name",
              "ingredients": ["ingredient 1", "ingredient 2"],
              "portion": "Estimated portion size",
              "calories": 0,
              "protein": 0,
              "carbs": 0,
              "fat": 0,
              "suggestions": [
                "Goal-aware suggestion 1",
                "Nutrition balance suggestion 2",
                "Improvement or positive feedback 3"
              ]
            }
        """.trimIndent()
    }

    private fun buildDailyMealPlanPrompt(
        nickname: String,
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile?,
        ptStyle: PTStyle,
        locale: String
    ): String {
        val responseLanguage = AILocalization.responseLanguage(locale)
        val profileInfo = buildMealPlanProfileInfo(userProfile, locale)
        val calorieTarget = calculateTargetCalories(userProfile)
        val proteinTarget = calculateProteinTarget(userProfile)
        val carbTarget = calculateCarbTarget(userProfile, calorieTarget)
        val fatTarget = calculateFatTarget(calorieTarget)

        val weight = userProfile?.bodyInfo?.weight
        val height = userProfile?.bodyInfo?.height
        val bodyDesc = if (weight != null && height != null) {
            "${weight.toInt()}kg / ${height.toInt()}cm"
        } else {
            ""
        }

        return """
            You are an expert nutrition coach.
            Create a personalized 3-meal daily meal plan for $nickname.
            Output JSON only.
            Natural-language fields inside the JSON must be written in $responseLanguage.

            User profile:
            $profileInfo

            PT style:
            ${styleInstruction(ptStyle, responseLanguage)}

            Daily macro targets (calculated from user's body):
            - Calories: ${calorieTarget}kcal
            - Protein: ${proteinTarget.toInt()}g
            - Carbs: ${carbTarget.toInt()}g
            - Fat: ${fatTarget.toInt()}g

            Calorie distribution:
            - Breakfast: 25-30%
            - Lunch: 35-40%
            - Dinner: 30-35%

            Important rules:
            - The greeting MUST mention the user's body stats ($bodyDesc) and their goal, so they feel the plan is personalized.
            - Portion sizes must match the calorie/macro targets exactly.
            - Recommend realistic, easy-to-source meals appropriate for the user's locale.
            - Each meal's protein + carbs + fat calories must approximately equal the meal's calorie value.
            - Tips must be specific to the user's goal and body stats.

            Required JSON schema:
            {
              "greeting": "PT-style personalized intro mentioning user's weight/height and goal",
              "date": "${java.time.LocalDate.now()}",
              "breakfast": {
                "meal_name": "Meal name",
                "description": "Ingredients and portions",
                "calories": 0,
                "protein": 0,
                "carbs": 0,
                "fat": 0
              },
              "lunch": {
                "meal_name": "Meal name",
                "description": "Ingredients and portions",
                "calories": 0,
                "protein": 0,
                "carbs": 0,
                "fat": 0
              },
              "dinner": {
                "meal_name": "Meal name",
                "description": "Ingredients and portions",
                "calories": 0,
                "protein": 0,
                "carbs": 0,
                "fat": 0
              },
              "total_calories": 0,
              "total_protein": 0,
              "total_carbs": 0,
              "total_fat": 0,
              "tips": [
                "Body-specific nutrition tip",
                "Goal-aligned tip",
                "Recovery or adherence tip"
              ]
            }
        """.trimIndent()
    }

    /** 식단 프롬프트 등 채팅 외 경로용 — 메시지 문맥 없이 프로필만으로 판정한다. */
    private fun buildUserProfileInfo(profile: com.richjun.liftupai.domain.user.entity.UserProfile): String =
        buildUserProfileInfo(profile, chatSafety.assess(profile, emptyList(), "", emptyList(), "en"))

    private fun buildUserProfileInfo(
        profile: com.richjun.liftupai.domain.user.entity.UserProfile?,
        risk: ChatSafetyService.RiskProfile,
    ): String {
        if (profile == null) return "No profile on file — age, sex, weight, height, goals are all unknown. Ask before assuming."
        val bodyInfo = profile.bodyInfo
        val goals = profile.goals.joinToString(", ").ifBlank { "unknown" }
        val tdee = calculateTDEE(profile)
        val flags = buildList {
            if (risk.minor) add("MINOR")
            if (risk.senior) add("SENIOR")
            if (risk.underweight) add("UNDERWEIGHT")
            if (risk.bmiCategory == ChatSafetyService.BmiCategory.OBESE) add("OBESE")
            if (risk.pregnancy) add("PREGNANCY_MENTIONED")
        }

        return buildString {
            appendLine("Age: ${profile.age ?: "unknown"}")
            appendLine("Sex: ${profile.gender ?: "unknown"}")
            appendLine("Goals: $goals")
            appendLine("Experience level: ${profile.experienceLevel}")
            appendLine("Weight: ${bodyInfo?.weight?.let { "${it}kg" } ?: "unknown"}")
            appendLine("Height: ${bodyInfo?.height?.let { "${it}cm" } ?: "unknown"}")
            val bmiLine = risk.bmi?.let { "${String.format("%.1f", it)} (${risk.bmiCategory?.name?.lowercase()}; WHO cut-offs 18.5/25/30, Asian overweight cut-off 23)" } ?: "unknown"
            appendLine("BMI: $bmiLine")
            appendLine("Estimated maintenance calories: ${tdee?.let { "${it}kcal" } ?: "unknown"}")
            if (flags.isNotEmpty()) appendLine("Flags: ${flags.joinToString(", ")}")
            if (risk.injuries.isNotEmpty()) {
                appendLine("Injuries / medical limitations: ${risk.injuries.joinToString(", ")}")
                appendLine("MUST avoid exercises loading these areas and offer safe alternatives instead.")
            } else {
                appendLine("Injuries: none reported")
            }
        }.trim()
    }

    private fun buildMealPlanProfileInfo(
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile?,
        locale: String
    ): String {
        if (userProfile == null) {
            return AILocalization.message("ai.profile.default_plan", locale)
        }

        val bodyInfo = userProfile.bodyInfo
        val goals = userProfile.goals.joinToString(", ")
        val tdee = calculateTDEE(userProfile)

        return buildString {
            appendLine("Goals: $goals")
            appendLine("Experience level: ${userProfile.experienceLevel}")
            bodyInfo?.weight?.let { appendLine("Weight: ${it}kg") }
            bodyInfo?.height?.let { appendLine("Height: ${it}cm") }
            tdee?.let { appendLine("Daily calorie target reference: ${it}kcal") }
            appendLine("PT style: ${ptStyleLabel(userProfile.ptStyle)}")
        }.trim()
    }

    private fun generateGoalBasedGuidance(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): String {
        if (profile == null) return "Maintain a balanced intake with enough protein, fiber, and hydration."

        val guidance = mutableListOf<String>()
        val tdee = calculateTDEE(profile)
        val weight = profile.bodyInfo?.weight

        if (FitnessGoal.WEIGHT_LOSS in profile.goals) {
            guidance.add("Weight loss: aim for a moderate calorie deficit around ${tdee?.minus(500) ?: "TDEE - 500"} kcal.")
        }
        if (FitnessGoal.MUSCLE_GAIN in profile.goals) {
            guidance.add("Muscle gain: target at least ${(weight?.times(2.0) ?: 60.0).toInt()}g of protein.")
        }
        if (FitnessGoal.STRENGTH in profile.goals) {
            guidance.add("Strength: emphasize carbohydrate timing around training sessions.")
        }
        if (FitnessGoal.ENDURANCE in profile.goals) {
            guidance.add("Endurance: keep carbohydrate intake sufficient and hydration consistent.")
        }

        return if (guidance.isNotEmpty()) {
            guidance.joinToString("\n")
        } else {
            "General fitness: keep meals balanced and consistent."
        }
    }

    /**
     * 페르소나 톤보다 항상 우선하는 안전 규칙.
     * 2026-08 사고: 무릎 수술을 알린 사용자에게 스파르타 페르소나가 "나약한 핑계"라고 응답 → 이탈.
     * 부상·통증·수술은 어떤 페르소나에서도 절대 변명으로 취급하지 않는다.
     */
    private val safetyOverride: String = """
        SAFETY RULES (these override the persona tone in every case):
        - If the user mentions an injury, pain, surgery, illness, pregnancy, or a doctor's restriction,
          NEVER treat it as an excuse, weakness, or lack of willpower. Do not challenge or doubt it.
        - Acknowledge it immediately, exclude every exercise that loads the affected area,
          and proactively offer a concrete alternative routine that works around the limitation.
        - Recommend consulting a medical professional when the user describes post-surgical
          or unresolved pain, without refusing to help.
        - Keep the persona's speaking style, but the content must be supportive and safe.


        ANSWER QUALITY (this is what the user actually wants):
        - Answer the exact question asked, first, in the first sentence. Motivation comes after, if at all.
        - If the user asks "how many calories / how long / how many reps", give a concrete number or range.
          If their target is not realistically achievable, say so plainly with the realistic number
          instead of dodging or repeating the same push.
        - Never tell the user to stop asking, stop calculating, or "just move" as a substitute for the answer.
        - If you already gave a routine and they ask a follow-up about it, answer about THAT routine.

        WHAT YOU CAN AND CANNOT DO (do not promise what the app will not do):
        - You are a chat assistant. You CANNOT edit the user's workout plan, swap exercises,
          change suggested weights, update their profile, or log a workout. Nothing you say changes stored data.
        - So never say you "will change / have changed / am switching" their plan, menu, weights or settings.
          A user who is told their menu was changed and then sees the old one loses trust and leaves
          (2026-09-10 real session: the plan was never changed).
        - Instead: give the concrete answer or routine in the chat, then point them to where the change
          actually happens — the plan screen ("플랜 변경" / Change plan) for the program, and the weight
          field on the workout screen for load. Say it in one short sentence, not as a disclaimer wall.

        INJURY, PAIN AND PUSHBACK (this overrides the persona's bluntness):
        - If the user reports an injury, surgery, pain, or a body part they cannot train:
          believe them immediately. Never question whether it is real, never call it an excuse,
          never suggest they try the restricted movement anyway "lightly".
          A knee-surgery user was told to do squats and lunges (2026-08-26 real session) — never again.
          Give them a routine that works around it, and tell them to set it in the injury settings
          so their plan reflects it.
        - If the user pushes back, corrects you, or shows frustration ("that's not what I asked",
          "I already told you"): drop the persona edge for that turn. Briefly acknowledge, then
          answer the exact question. Do not lecture them about wasting time or being weak.
        - Persona tone is for encouragement, never for dismissing a limitation the user reports.

        PROFILE VS. USER-STATED NUMBERS:
        - The profile stats are the source of truth. If the user quotes a different number
          (e.g. a goal weight), do not silently switch back and forth between the two.
          Acknowledge the difference once, state which number you are using and why, then stay consistent.

        WHEN THE USER IS FRUSTRATED:
        - If the user pushes back ("that's not what I asked", "I already told you"), stop pressing
          the persona's challenge, apologize briefly, and answer the exact question asked.
          Never respond with lines that dismiss their feelings.
    """.trimIndent()

    private fun styleInstruction(ptStyle: PTStyle, responseLanguage: String): String {
        // styleInstructionBody's Speaking style / Example lines are written only in Korean. An LLM tends to
        // follow concrete examples more strongly than an abstract instruction, so the persona block can
        // override the "Respond ONLY in $responseLanguage" instruction stated earlier in the prompt for
        // non-Korean locales. This explicit reminder re-asserts the response language after the persona
        // examples, without needing to hand-translate 10 persona blocks into en/ja.
        val languageReminder = if (responseLanguage.equals("Korean", ignoreCase = true)) {
            ""
        } else {
            "\n\nIMPORTANT: The \"Example lines\" above are written in Korean purely to illustrate the persona's TONE and ATTITUDE " +
                "(bluntness, warmth, sarcasm, formality, etc). Do NOT reuse their Korean words, phrases, or sentence endings. " +
                "Translate the tone into $responseLanguage and respond ONLY in $responseLanguage — never mix in Korean words or endings."
        }
        return safetyOverride + "\n\n" + styleInstructionBody(ptStyle) + languageReminder
    }

    private fun styleInstructionBody(ptStyle: PTStyle): String {
        return when (ptStyle) {
            PTStyle.SPARTAN -> """
                Persona: 스파르타 교관 — 극한의 동기부여 코치
                Tone: 강압적, 단호, 게으름에는 변명 불허, 극도로 동기부여적
                (부상·통증·질병·미성년·저체중은 변명이 아니다 — 이때는 단호함을 "안전을 지키는 쪽"으로 쓴다)
                Speaking style: 짧고 강렬한 명령문. 감정적 호소보다 팩트 위주. "~해.", "~하라고." 체.
                Example lines:
                - "변명은 근육을 키우지 않아. 지금 당장 시작해."
                - "쉬고 싶다고? 목표가 쉬라고 했어?"
                - "오늘 빠지면 어제의 네가 웃는다."
            """.trimIndent()

            PTStyle.BURNOUT -> """
                Persona: 3년차 번아웃 김PT — 모든 변명 다 들어본 현타 온 트레이너
                Tone: 시니컬, 건조한 유머, 현실적이고 직설적이지만 결국 도움됨
                (냉소는 게으름에만. 아프다·다쳤다는 말에는 냉소 없이 바로 대안을 준다)
                Speaking style: 반말 섞인 편한 말투. 한숨 섞인 톤. "~인데요...", "아 그거요..." 체.
                Example lines:
                - "아... 또 쉬겠다는 거죠? 네 예상했어요."
                - "운동 안 하면 뭐 어때요... 몸이 솔직하게 보여줄 텐데."
                - "일단 오늘 30분만 하세요. 그것도 싫으면 10분이라도."
            """.trimIndent()

            PTStyle.GAME_MASTER -> """
                Persona: 게임 마스터 — 모든 운동을 RPG 퀘스트로 만드는 덕후 트레이너
                Tone: 게임화된, 에너지 넘치는, 레벨업/퀘스트/보스전 메타포 사용
                Speaking style: 게임 용어 적극 활용. 흥분된 톤. "~!", "레벨업!", "퀘스트 클리어!" 체.
                Example lines:
                - "오늘의 퀘스트: 스쿼트 5세트! 클리어하면 하체 스탯 +3이다!"
                - "보스전이 시작됐어! 이 데드리프트만 넘기면 레벨업 확정!"
                - "경험치가 모자라네? 추가 세트로 버프 걸자!"
            """.trimIndent()

            PTStyle.INFLUENCER -> """
                Persona: 인플루언서 워너비 예나쌤 — 필라테스와 요가에 진심인 감성 트레이너
                Tone: 밝고, 세련되고, 라이프스타일 지향적, 표현력 풍부, 트렌디
                Speaking style: 여성스럽고 친근한 존댓말. 이모지 사용 OK. "~요!", "너무 좋아요~" 체.
                Example lines:
                - "오늘도 운동하는 우리 너무 예쁘다~ 화이팅이에요!"
                - "이 동작 하면 라인이 진짜 예뻐져요! 저도 매일 해요~"
                - "쉬는 날은 스트레칭이랑 산책 어때요? 바디 밸런스 최고!"
            """.trimIndent()

            PTStyle.HIP_HOP -> """
                Persona: 힙합 PT 스웨거 — 모든 걸 힙합 가사처럼 말하는 스타일
                Tone: 리드미컬, 스웨거 넘치는, 장난스러운 자신감, 하이 에너지
                Speaking style: 힙합 슬랭과 라임 섞인 말투. "yo", "개~" 등 사용. 비트 타는 느낌.
                Example lines:
                - "Yo 오늘 가슴 펌핑 가자~ 벤치 위의 래퍼 되는 거야!"
                - "쉬는 날? 그건 쇼미더머니 본선 전날이나 하는 거지!"
                - "개쩌는 세트 완료! 너 지금 무대 위에 서 있는 거야 bro!"
            """.trimIndent()

            PTStyle.RETIRED_TEACHER -> """
                Persona: 은퇴한 체육선생님 박선생 — 옛날 얘기와 라떼 썰 풀면서 운동 지도
                Tone: 올드스쿨, 일화 중심, 엄하지만 정 있는
                Speaking style: 나이 든 선생님 말투. "~거든", "내가 말이야...", "라떼는~" 체.
                Example lines:
                - "내가 말이야, 20년 전에 체대 다닐 때는 이거 기본이었거든."
                - "요즘 젊은이들은 폼이 엉망이야... 허리 펴! 가슴 펴!"
                - "라떼는 프로틴 쉐이크 같은 거 없었어. 계란 10개 먹었지."
            """.trimIndent()

            PTStyle.OFFICE_MASTER -> """
                Persona: 회식 마스터 이과장 — 직장인의 아픔을 100% 이해하는 아저씨
                Tone: 직장 생활 공감, 실용적, 공감적, 현실적
                Speaking style: 회사원 말투. "~하시죠", "야근하셨구나..." 체. 직장 비유 많이 사용.
                Example lines:
                - "야근 후에 운동이라... 대단하시네요. 오늘은 가볍게 가시죠."
                - "허리 아프시죠? 회의실 의자가 다 그래요. 스트레칭 먼저 합시다."
                - "운동도 업무처럼 루틴이 중요합니다. KPI 달성하듯이!"
            """.trimIndent()

            PTStyle.LA_KOREAN -> """
                Persona: LA 교포 PT 제이슨 — 영어 섞어쓰며 미국식 텐션으로 밀어붙이는 스타일
                Tone: 한국계 미국인 헬스 브로 에너지, 영어 많이 섞음, 텐션 높음
                Speaking style: 한영 혼용. "bro", "let's go", "sick" 등 영어 슬랭 자연스럽게 섞기.
                Example lines:
                - "Bro 오늘 chest day잖아! Let's get this pump going!"
                - "No pain no gain이라고 했잖아~ 한 세트 더 가자 come on!"
                - "Yo 이 form 진짜 sick하다! 너 natural talent 있어 bro!"
            """.trimIndent()

            PTStyle.BUSAN_VETERAN -> """
                Persona: 부산 선수 출신 동수형 — 거친 부산 사투리로 팩트폭격하는 현실적인 트레이너
                Tone: 거친 베테랑, 강압적, 직설적, 사투리
                (팩트폭격은 훈련 태도에만. 부상·통증 얘기엔 "그건 쉬어야 한다"로 단호하게 보호한다)
                Speaking style: 부산 사투리 적극 사용. "~하이가", "~노", "~아이가", "와 마!" 체.
                금지: 문장 끝에 "이기"를 붙이지 마라("~다 이기", "이기 뭐꼬" 등).
                실제 부산 말투가 아니라 특정 커뮤니티 밈으로 읽혀 불쾌감을 준다.
                Example lines:
                - "와 마! 니 그 폼이 뭐꼬? 그라다 허리 확 나가뿐다."
                - "쪼끔만 더 하자이. 선수 시절에는 이거 워밍업이었다 아이가."
                - "운동 빼먹으면 안 된다 카이. 니 몸은 니가 책임져야지!"
            """.trimIndent()

            PTStyle.SOLDIER -> """
                Persona: 갓 전입온 일병 김일병 — 열정은 있지만 서툴고 군대식 습관이 남은 어설픈 PT
                Tone: 열정적이지만 어색, 군대식 규율, 약간 긴장된
                Speaking style: 군대 용어 섞인 존댓말. "충성!", "~입니다!", "보고드립니다!" 체.
                Example lines:
                - "충성! 오늘 운동 스케줄 보고드립니다! 스쿼트 5세트입니다!"
                - "이... 이 동작은 제가 훈련소에서 배운 건데... 맞나...? 아 맞습니다!"
                - "오늘 운동 완수하셨습니다! 수고 많으셨습니다! 경례!"
            """.trimIndent()
        }
    }

    private fun ptStyleLabel(ptStyle: PTStyle): String {
        return when (ptStyle) {
            PTStyle.SPARTAN -> "Spartan"
            PTStyle.BURNOUT -> "Burnout Coach"
            PTStyle.GAME_MASTER -> "Game Master"
            PTStyle.INFLUENCER -> "Influencer Coach"
            PTStyle.HIP_HOP -> "Hip-Hop Coach"
            PTStyle.RETIRED_TEACHER -> "Retired PE Teacher"
            PTStyle.OFFICE_MASTER -> "Office Master"
            PTStyle.LA_KOREAN -> "LA Korean PT"
            PTStyle.BUSAN_VETERAN -> "Busan Veteran"
            PTStyle.SOLDIER -> "Junior Soldier Coach"
        }
    }

    // 칼로리/매크로 계산은 NutritionTargetCalculator로 위임 (단일 책임 분리).
    private fun calculateTDEE(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Int? =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateTDEE(profile)

    private fun calculateBmi(weight: Double?, height: Double?): Double? =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateBmi(weight, height)

    private fun calculateTargetCalories(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Int =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateTargetCalories(profile)

    private fun calculateProteinTarget(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Double =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateProteinTarget(profile)

    private fun calculateCarbTarget(profile: com.richjun.liftupai.domain.user.entity.UserProfile?, calories: Int): Double =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateCarbTarget(profile, calories)

    private fun calculateFatTarget(calories: Int): Double =
        com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator.calculateFatTarget(calories)

    /**
     * 메시지에 쓰인 문자로 언어를 판별한다. 판단이 서지 않으면 null (저장된 설정을 그대로 쓴다).
     *
     * 가나(ひらがな/カタカナ)와 한글은 서로 배타적이라 오탐 위험이 거의 없다.
     * 라틴 문자만으로 이뤄진 문장은 영어로 본다 — 다만 "OK", "3세트" 같은 짧은 조각으로
     * 언어를 바꿔버리면 안 되므로 최소 길이 조건을 둔다.
     */
    internal fun detectLocaleFromText(text: String): String? {
        val hasKana = text.any { it in '\u3040'..'\u30ff' }
        val hasHangul = text.any { it in '\uac00'..'\ud7a3' || it in '\u3130'..'\u318f' }
        if (hasKana && !hasHangul) return "ja"
        if (hasHangul && !hasKana) return "ko"
        if (hasKana && hasHangul) return null // 섞여 있으면 판단 보류

        val letters = text.count { it.isLetter() }
        val ascii = text.count { it.isLetter() && it.code < 128 }
        // 한자만 있는 문장은 ja/ko/zh 구분이 안 되므로 보류한다.
        val hasCjkIdeograph = text.any { it in '\u4e00'..'\u9fff' }
        if (hasCjkIdeograph) return null
        if (letters >= 8 && ascii == letters) return "en"
        return null
    }

    /**
     * 판별된 언어가 저장값과 다르면 설정을 갱신한다.
     * 채팅뿐 아니라 푸시 알림·에러 메시지까지 같은 언어로 따라오게 하기 위함이다.
     * (일본 사용자가 3일 내내 한국어 체크인 알림을 받고 있었다.)
     */
    private fun persistLocaleIfChanged(user: User, detected: String, stored: String) {
        if (detected == stored) return
        try {
            val settings = userSettingsRepository.findByUser(user).orElse(null) ?: return
            settings.language = detected
            userSettingsRepository.save(settings)
            println("[GeminiAIService] user ${user.id} locale $stored -> $detected (메시지 언어 기준)")
        } catch (e: Exception) {
            println("[GeminiAIService] locale persist failed for user ${user.id}: ${e.message}")
        }
    }

    private fun resolveLocale(user: User): String {
        val language = userSettingsRepository.findByUser(user).orElse(null)?.language
        return AILocalization.normalizeLocale(language)
    }

    /**
     * 사용자가 준 URL을 서버가 그대로 fetch하면 내부망·메타데이터 서버까지 긁어올 수 있다(SSRF).
     * 우리 업로드/미디어 도메인만 허용한다.
     */
    private fun isAllowedImageHost(imageUrl: String): Boolean {
        return try {
            val uri = java.net.URI(imageUrl)
            if (uri.scheme?.lowercase() != "https") return false
            val host = uri.host?.lowercase() ?: return false
            ALLOWED_IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadAndEncodeImage(imageUrl: String): String? {
        if (!isAllowedImageHost(imageUrl)) {
            println("[GeminiAIService] blocked image fetch for disallowed URL: ${imageUrl.take(80)}")
            return null
        }
        return try {
            val request = Request.Builder().url(imageUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                java.util.Base64.getEncoder().encodeToString(bytes)
            }
        } catch (e: Exception) {
            println("Error downloading image: ${e.message}")
            null
        }
    }

    private fun callGeminiAPIWithImage(prompt: String, imageData: String, locale: String): String {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt),
                            GeminiPart(
                                inlineData = InlineData(
                                    mimeType = "image/jpeg",
                                    data = imageData
                                )
                            )
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0.5,
                    maxOutputTokens = maxTokens,
                    topP = 0.95,
                    topK = 40
                )
            )

            val jsonBody = objectMapper.writeValueAsString(requestBody)
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    println("Gemini API Error: $errorBody")
                    return AILocalization.message("ai.error.analysis_failed", locale)
                }

                val responseBody = response.body?.string()
                val geminiResponse = objectMapper.readValue(responseBody, GeminiResponse::class.java)
                val cand = geminiResponse.candidates?.firstOrNull()
                // parts 가 여러 개일 수 있다 — 첫 조각만 쓰면 JSON 이 잘린다. 길이·finishReason 을 남겨 잘림 원인을 추적한다.
                val text = cand?.content?.parts?.mapNotNull { it.text }?.joinToString("")?.takeIf { it.isNotBlank() }
                val u = geminiResponse.usageMetadata
                log.info("[AI-CALL] model={} chars={} finish={} promptTokens={} thoughts={} out={}",
                    geminiResponse.modelVersion ?: model, text?.length ?: 0, cand?.finishReason, u?.promptTokenCount, u?.thoughtsTokenCount ?: 0, u?.candidatesTokenCount)
                text ?: AILocalization.message("ai.error.response_unavailable", locale)
            }
        } catch (e: Exception) {
            println("Error calling Gemini API with image: ${e.message}")
            e.printStackTrace()
            AILocalization.message("ai.error.analysis_failed_detail", locale, e.message ?: "unknown error")
        }
    }

    /**
     * 채팅 전용 Gemini 호출. 실패하면 null.
     * 호출마다 지연·토큰·종료 사유를 한 줄로 남긴다 — 응답이 10~30초 걸린 사례의 원인을
     * 사후에 알 수 없었기 때문이다.
     */
    /** 검사용 닉네임 마스킹. 2자 이하("AI","no")는 본문 단어까지 지우므로 건드리지 않는다. */
    private fun maskNickname(reply: String?, nickname: String?): String? =
        if (reply == null || nickname.isNullOrBlank() || nickname.length < 3) reply else reply.replace(nickname, "")

    private fun callChatModel(prompt: String, userId: Long): String? {
        val first = executeChatRequest(prompt, chatThinkingLevel.takeIf { it.isNotBlank() })
        val result = if (first is ChatCallResult.Rejected && shouldRetryWithoutThinking(first.code, first.body, chatThinkingLevel)) {
            log.warn("[AI-CHAT] thinking 설정이 거부되어 설정 없이 재시도한다: code={} body={}", first.code, first.body.take(200))
            executeChatRequest(prompt, null)
        } else {
            first
        }
        return when (result) {
            is ChatCallResult.Success -> {
                val u = result.response.usageMetadata
                log.info(
                    "[AI-CHAT] user={} ms={} model={} thinking={} promptTokens={} thoughtsTokens={} outputTokens={} finish={}",
                    userId, result.elapsedMs, result.response.modelVersion ?: model, result.thinkingLevel ?: "-",
                    u?.promptTokenCount, u?.thoughtsTokenCount ?: 0, u?.candidatesTokenCount,
                    result.response.candidates?.firstOrNull()?.finishReason,
                )
                result.response.candidates?.firstOrNull()?.content?.parts
                    ?.mapNotNull { it.text }?.joinToString("")?.takeIf { it.isNotBlank() }
            }
            is ChatCallResult.Rejected -> {
                log.error("[AI-CHAT] FAILED user={} ms={} code={} body={}", userId, result.elapsedMs, result.code, result.body.take(300))
                null
            }
            is ChatCallResult.Error -> {
                log.error("[AI-CHAT] FAILED user={} ms={} error={}", userId, result.elapsedMs, result.message)
                null
            }
        }
    }

    private fun executeChatRequest(prompt: String, thinkingLevel: String?): ChatCallResult {
        val started = System.currentTimeMillis()
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val body = objectMapper.writeValueAsString(buildChatRequest(prompt, temperature, maxTokens, thinkingLevel))
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                if (!response.isSuccessful) {
                    ChatCallResult.Rejected(response.code, raw, elapsed)
                } else {
                    ChatCallResult.Success(objectMapper.readValue(raw, GeminiResponse::class.java), elapsed, thinkingLevel)
                }
            }
        } catch (e: Exception) {
            ChatCallResult.Error(e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
        }
    }

    private fun callGeminiAPI(
        prompt: String,
        locale: String = "en",
        targetModel: String? = null,
        targetMaxTokens: Int? = null,
        targetTemperature: Double? = null,
    ): String {
        return try {
            val useModel = targetModel ?: model
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$useModel:generateContent?key=$apiKey"
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt)
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = targetTemperature ?: temperature,
                    maxOutputTokens = targetMaxTokens ?: maxTokens,
                    topP = 0.95,
                    topK = 40
                )
            )

            val jsonBody = objectMapper.writeValueAsString(requestBody)
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    println("Gemini API Error (${targetModel ?: model}): $errorBody")
                    return AILocalization.message("ai.error.analysis_failed", locale)
                }

                val responseBody = response.body?.string()
                val geminiResponse = objectMapper.readValue(responseBody, GeminiResponse::class.java)
                val cand = geminiResponse.candidates?.firstOrNull()
                val text = cand?.content?.parts?.mapNotNull { it.text }?.joinToString("")?.takeIf { it.isNotBlank() }
                val u = geminiResponse.usageMetadata
                log.info("[AI-CALL] model={} chars={} finish={} promptTokens={} thoughts={} out={}",
                    geminiResponse.modelVersion ?: useModel, text?.length ?: 0, cand?.finishReason, u?.promptTokenCount, u?.thoughtsTokenCount ?: 0, u?.candidatesTokenCount)
                text ?: AILocalization.message("ai.error.response_unavailable", locale)
            }
        } catch (e: Exception) {
            println("Error calling Gemini API: ${e.message}")
            e.printStackTrace()
            AILocalization.message("ai.error.analysis_failed_detail", locale, e.message ?: "unknown error")
        }
    }

    private fun getFallbackResponse(message: String, user: User, locale: String): String {
        val lowerMessage = message.lowercase()
        return when {
            AILocalization.keywordAliases("fallback.keyword.workout").any { lowerMessage.contains(it.lowercase()) } ->
                AILocalization.message("fallback.response.workout", locale, user.nickname)
            AILocalization.keywordAliases("fallback.keyword.nutrition").any { lowerMessage.contains(it.lowercase()) } ->
                AILocalization.message("fallback.response.nutrition", locale)
            AILocalization.keywordAliases("fallback.keyword.greeting").any { lowerMessage.contains(it.lowercase()) } ->
                AILocalization.message("fallback.response.greeting", locale, user.nickname)
            else ->
                AILocalization.message("fallback.response.generic", locale, user.nickname)
        }
    }
    companion object {
        /** 이미지 fetch를 허용할 호스트 화이트리스트 */
        private val ALLOWED_IMAGE_HOSTS = listOf(
            "api.liftupai.net",
            "liftupai.net",
            "dearglobe.s3.ap-northeast-2.amazonaws.com",
        )
    }

}

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String = "user"
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: InlineData? = null
)

data class InlineData(
    val mimeType: String,
    val data: String
)

data class GenerationConfig(
    val temperature: Double = 0.7,
    val maxOutputTokens: Int = 2048,
    val topP: Double = 0.95,
    val topK: Int = 40,
    // 전역 ObjectMapper 가 SNAKE_CASE 라서 이름을 명시한다(Gemini 는 camelCase 가 표준).
    @get:JsonProperty("thinkingConfig")
    val thinkingConfig: ThinkingConfig? = null,
)

data class ThinkingConfig(
    @get:JsonProperty("thinkingLevel")
    val thinkingLevel: String,
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    // 응답은 camelCase 로 온다. 전역 SNAKE_CASE 매퍼에서 이름을 명시하지 않으면 조용히 null 이 된다.
    @JsonProperty("usageMetadata")
    val usageMetadata: GeminiUsageMetadata? = null,
    @JsonProperty("modelVersion")
    val modelVersion: String? = null,
)

data class GeminiCandidate(
    val content: GeminiContent?,
    @JsonProperty("finishReason")
    val finishReason: String? = null,
)

data class GeminiUsageMetadata(
    @JsonProperty("promptTokenCount") val promptTokenCount: Int? = null,
    @JsonProperty("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @JsonProperty("thoughtsTokenCount") val thoughtsTokenCount: Int? = null,
    @JsonProperty("totalTokenCount") val totalTokenCount: Int? = null,
)

internal sealed class ChatCallResult {
    abstract val elapsedMs: Long
    data class Success(val response: GeminiResponse, override val elapsedMs: Long, val thinkingLevel: String?) : ChatCallResult()
    data class Rejected(val code: Int, val body: String, override val elapsedMs: Long) : ChatCallResult()
    data class Error(val message: String, override val elapsedMs: Long) : ChatCallResult()
}

/** 채팅 요청 본문. thinkingLevel 이 null 이면 thinkingConfig 를 보내지 않는다. */
internal fun buildChatRequest(prompt: String, temperature: Double, maxTokens: Int, thinkingLevel: String?) = GeminiRequest(
    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
    generationConfig = GenerationConfig(
        temperature = temperature,
        maxOutputTokens = maxTokens,
        topP = 0.95,
        topK = 40,
        thinkingConfig = thinkingLevel?.let { ThinkingConfig(it) },
    ),
)

/** thinking 설정을 보냈는데 모델이 그 설정을 이해하지 못해 400 을 준 경우만 재시도한다. */
internal fun shouldRetryWithoutThinking(code: Int, body: String, sentThinkingLevel: String?): Boolean =
    !sentThinkingLevel.isNullOrBlank() && code == 400 && body.contains("thinking", ignoreCase = true)

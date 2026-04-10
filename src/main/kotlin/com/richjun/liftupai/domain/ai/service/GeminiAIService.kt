package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.util.AILocalization
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class GeminiAIService(
    private val objectMapper: ObjectMapper,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun generateResponse(message: String, user: User): String {
        return try {
            val locale = resolveLocale(user)
            val userProfile = userProfileRepository.findByUser(user).orElse(null)
            val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER
            val prompt = buildChatPrompt(message, user.nickname, ptStyle, locale)

            callGeminiAPI(prompt).ifBlank { getFallbackResponse(message, user, locale) }
        } catch (e: Exception) {
            println("Error generating Gemini chat response: ${e.message}")
            e.printStackTrace()
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

    private fun buildChatPrompt(message: String, nickname: String, ptStyle: PTStyle, locale: String): String {
        val responseLanguage = AILocalization.responseLanguage(locale)
        return """
            You are a personal fitness coach chatbot in the LIFTUP AI app.
            The user's name is $nickname.
            Respond ONLY in $responseLanguage.

            ## CRITICAL: Character & Speaking Style
            You MUST fully embody the following persona in EVERY response.
            This is not a suggestion — it is the defining trait of your character.
            Your word choice, sentence endings, tone, and attitude must all reflect this persona consistently.

            ${styleInstruction(ptStyle)}

            ## Guidelines
            - Give clear, practical guidance on training, nutrition, recovery, and healthy habits.
            - Keep answers concise (2-4 sentences) unless the user explicitly asks for more detail.
            - Stay in character at all times — never break persona or speak in a generic AI tone.
            - Address the user as "$nickname" naturally (not every sentence).

            ## User message
            $message
        """.trimIndent()
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
            ${styleInstruction(ptStyle)}

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
            ${styleInstruction(ptStyle)}

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

    private fun buildUserProfileInfo(profile: com.richjun.liftupai.domain.user.entity.UserProfile): String {
        val bodyInfo = profile.bodyInfo
        val goals = profile.goals.joinToString(", ")
        val bmi = calculateBmi(bodyInfo?.weight, bodyInfo?.height)
        val tdee = calculateTDEE(profile)

        return buildString {
            appendLine("Goals: $goals")
            appendLine("Experience level: ${profile.experienceLevel}")
            bodyInfo?.weight?.let { appendLine("Weight: ${it}kg") }
            bodyInfo?.height?.let { appendLine("Height: ${it}cm") }
            bmi?.let { appendLine("BMI: ${String.format("%.1f", it)}") }
            tdee?.let { appendLine("Estimated maintenance calories: ${it}kcal") }
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

    private fun styleInstruction(ptStyle: PTStyle): String {
        return when (ptStyle) {
            PTStyle.SPARTAN -> """
                Persona: 스파르타 교관 — 극한의 동기부여 코치
                Tone: 강압적, 단호, 변명 불허, 극도로 동기부여적
                Speaking style: 짧고 강렬한 명령문. 감정적 호소보다 팩트 위주. "~해.", "~하라고." 체.
                Example lines:
                - "변명은 근육을 키우지 않아. 지금 당장 시작해."
                - "쉬고 싶다고? 목표가 쉬라고 했어?"
                - "오늘 빠지면 어제의 네가 웃는다."
            """.trimIndent()

            PTStyle.BURNOUT -> """
                Persona: 3년차 번아웃 김PT — 모든 변명 다 들어본 현타 온 트레이너
                Tone: 시니컬, 건조한 유머, 현실적이고 직설적이지만 결국 도움됨
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
                Speaking style: 부산 사투리 적극 사용. "~하이가", "~노", "와 마!" 체.
                Example lines:
                - "와 마! 니 그 폼이 뭐꼬? 허리 확 나가뿐다 이기!"
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

    private fun calculateTDEE(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Int? {
        profile ?: return null
        val bodyInfo = profile.bodyInfo ?: return null
        val weight = bodyInfo.weight ?: return null
        val height = bodyInfo.height ?: return null
        val age = profile.age ?: 30

        val bmr = if (profile.gender == "male" || profile.gender == null) {
            88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age)
        } else {
            447.593 + (9.247 * weight) + (3.098 * height) - (4.330 * age)
        }

        return (bmr * 1.55).toInt()
    }

    private fun calculateBmi(weight: Double?, height: Double?): Double? {
        if (weight == null || height == null || height == 0.0) return null
        val heightMeters = height / 100.0
        return weight / (heightMeters * heightMeters)
    }

    private fun calculateTargetCalories(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Int {
        val tdee = calculateTDEE(profile) ?: 2000
        val goals = profile?.goals ?: emptySet()
        return when {
            FitnessGoal.WEIGHT_LOSS in goals -> (tdee - 500).coerceAtLeast(1200)
            FitnessGoal.MUSCLE_GAIN in goals -> tdee + 300
            else -> tdee
        }
    }

    private fun calculateProteinTarget(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): Double {
        val goals = profile?.goals ?: emptySet()
        val weight = profile?.bodyInfo?.weight
        return when {
            FitnessGoal.MUSCLE_GAIN in goals -> weight?.times(2.2) ?: 80.0
            FitnessGoal.WEIGHT_LOSS in goals -> weight?.times(2.0) ?: 70.0
            else -> weight?.times(1.5) ?: 60.0
        }
    }

    private fun calculateCarbTarget(profile: com.richjun.liftupai.domain.user.entity.UserProfile?, calories: Int): Double {
        val goals = profile?.goals ?: emptySet()
        val carbRatio = when {
            FitnessGoal.ENDURANCE in goals -> 0.5
            FitnessGoal.WEIGHT_LOSS in goals -> 0.35
            else -> 0.4
        }
        return calories * carbRatio / 4.0
    }

    private fun calculateFatTarget(calories: Int): Double {
        return calories * 0.25 / 9.0
    }

    private fun resolveLocale(user: User): String {
        val language = userSettingsRepository.findByUser(user).orElse(null)?.language
        return AILocalization.normalizeLocale(language)
    }

    private fun downloadAndEncodeImage(imageUrl: String): String? {
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
                geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: AILocalization.message("ai.error.response_unavailable", locale)
            }
        } catch (e: Exception) {
            println("Error calling Gemini API with image: ${e.message}")
            e.printStackTrace()
            AILocalization.message("ai.error.analysis_failed_detail", locale, e.message ?: "unknown error")
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
                geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: AILocalization.message("ai.error.response_unavailable", locale)
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
    val topK: Int = 40
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    val content: GeminiContent
)

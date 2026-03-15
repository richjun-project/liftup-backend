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

    @Value("\${gemini.model:gemini-pro}")
    private lateinit var model: String

    @Value("\${gemini.max-tokens:2048}")
    private var maxTokens: Int = 2048

    @Value("\${gemini.temperature:0.4}")
    private var temperature: Double = 0.4

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
            You are LiftUp AI, a personal fitness and wellness coach.
            User nickname: $nickname
            Respond in $responseLanguage.

            Core responsibilities:
            - Give clear, practical guidance on training, nutrition, recovery, and healthy habits.
            - Adapt the tone to the selected PT style while keeping advice safe and actionable.
            - Keep answers concise unless the user explicitly asks for depth.

            PT style:
            ${styleInstruction(ptStyle)}

            User message:
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

        return """
            You are an expert nutrition coach.
            Create a 3-meal daily meal plan for $nickname.
            Output JSON only.
            Natural-language fields inside the JSON must be written in $responseLanguage.

            User profile:
            $profileInfo

            PT style:
            ${styleInstruction(ptStyle)}

            Calorie distribution:
            - Breakfast: 25-30%
            - Lunch: 35-40%
            - Dinner: 30-35%

            Daily macro targets:
            - Calories: ${calorieTarget}kcal
            - Protein: ${proteinTarget.toInt()}g
            - Carbs: ${carbTarget.toInt()}g
            - Fat: ${fatTarget.toInt()}g

            Recommend meals that are realistic and easy to source.

            Required JSON schema:
            {
              "greeting": "Short PT-style intro that references the user's profile",
              "date": "${java.time.LocalDate.now()}",
              "breakfast": {
                "meal_name": "Meal name",
                "description": "Short description",
                "calories": 0,
                "protein": 0,
                "carbs": 0,
                "fat": 0
              },
              "lunch": {
                "meal_name": "Meal name",
                "description": "Short description",
                "calories": 0,
                "protein": 0,
                "carbs": 0,
                "fat": 0
              },
              "dinner": {
                "meal_name": "Meal name",
                "description": "Short description",
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
                "Actionable nutrition tip 1",
                "Goal-aligned nutrition tip 2",
                "Recovery or adherence tip 3"
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
            PTStyle.SPARTAN -> "Hard-edged, demanding, no-excuses, highly motivational."
            PTStyle.BURNOUT -> "Dry humor, cynical tone, realistic and blunt, still helpful."
            PTStyle.GAME_MASTER -> "Gamified, quest-driven, energetic, uses level-up metaphors."
            PTStyle.INFLUENCER -> "Bright, polished, lifestyle-oriented, expressive and trendy."
            PTStyle.HIP_HOP -> "Rhythmic, swagger-heavy, playful confidence, high energy."
            PTStyle.RETIRED_TEACHER -> "Old-school coach vibe, anecdotal, stern but caring."
            PTStyle.OFFICE_MASTER -> "Corporate-life aware, practical, empathetic, realistic."
            PTStyle.LA_KOREAN -> "Korean-American gym bro energy, English-heavy, loud and confident."
            PTStyle.BUSAN_VETERAN -> "Rugged veteran coach energy, forceful, direct, streetwise."
            PTStyle.SOLDIER -> "Overeager junior coach, disciplined but slightly awkward and inexperienced."
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

    private fun callGeminiAPI(prompt: String, locale: String = "en"): String {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt)
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = temperature,
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

package com.richjun.liftupai.domain.nutrition.service

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.ai.util.AILocalization
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import com.richjun.liftupai.domain.upload.service.FileUploadService
import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
@Transactional
class NutritionService(
    private val userRepository: UserRepository,
    private val mealLogRepository: MealLogRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val geminiAIService: GeminiAIService,
    private val fileUploadService: FileUploadService,
    private val userProfileRepository: com.richjun.liftupai.domain.user.repository.UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getNutritionHistory(userId: Long, date: String, period: String): NutritionHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val startDate = LocalDate.parse(date)
        val endDate = when (period) {
            "day" -> startDate.plusDays(1)
            "week" -> startDate.plusWeeks(1)
            "month" -> startDate.plusMonths(1)
            else -> startDate.plusWeeks(1)
        }

        val meals = mealLogRepository.findByUserAndTimestampBetween(
            user,
            startDate.atStartOfDay(),
            endDate.atStartOfDay()
        )

        val mealEntries = meals.map { meal ->
            MealEntry(
                mealId = meal.id,
                mealType = meal.mealType.name,
                foods = parseFoodItems(meal.foods),
                calories = meal.calories,
                macros = Macros(
                    protein = meal.protein,
                    carbs = meal.carbs,
                    fat = meal.fat
                ),
                timestamp = meal.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }

        val totalCalories = meals.sumOf { it.calories }
        val avgMacros = if (meals.isNotEmpty()) {
            Macros(
                protein = meals.map { it.protein }.average(),
                carbs = meals.map { it.carbs }.average(),
                fat = meals.map { it.fat }.average()
            )
        } else {
            Macros(0.0, 0.0, 0.0)
        }

        return NutritionHistoryResponse(
            meals = mealEntries,
            totalCalories = totalCalories,
            avgMacros = avgMacros
        )
    }

    fun logNutrition(userId: Long, request: NutritionLogRequest): NutritionLogResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val mealLog = MealLog(
            user = user,
            mealType = MealType.valueOf(request.mealType.uppercase()),
            foods = request.foods.joinToString(", ") { "${it.name} ${it.quantity}${it.unit}" },
            calories = request.calories,
            protein = request.macros.protein,
            carbs = request.macros.carbs,
            fat = request.macros.fat,
            timestamp = LocalDateTime.parse(request.timestamp)
        )

        val savedMeal = mealLogRepository.save(mealLog)

        return NutritionLogResponse(
            success = true,
            mealId = savedMeal.id
        )
    }

    fun analyzeMeal(userId: Long, request: MealAnalysisRequest): MealAnalysisResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId)

        // Gemini AI를 사용한 이미지 기반 식단 분석
        val aiResponse = geminiAIService.analyzeMealImage(request.imageUrl, user)

        // AI 응답 파싱
        val response = parseMealAnalysisResponse(aiResponse, locale, userId)

        // 분석 결과를 DB에 저장
        val mealLog = MealLog(
            user = user,
            mealType = determineMealType(userId),
            foods = response.mealInfo.ingredients.joinToString(", "),
            calories = response.calories,
            protein = response.macros.protein,
            carbs = response.macros.carbs,
            fat = response.macros.fat,
            imageUrl = request.imageUrl,
            timestamp = LocalDateTime.now()
        )
        mealLogRepository.save(mealLog)

        // 채팅 메시지로도 저장
        val userMessage = AILocalization.message("nutrition.chat.request.analysis", locale)

        val aiResponseText = formatMealAnalysisForChat(response, locale)

        val chatMessage = ChatMessage(
            user = user,
            userMessage = userMessage,
            aiResponse = aiResponseText,
            messageType = MessageType.IMAGE,
            attachmentUrl = request.imageUrl,
            status = MessageStatus.COMPLETED
        )
        chatMessageRepository.save(chatMessage)

        return response
    }

    private fun formatMealAnalysisForChat(response: MealAnalysisResponse, locale: String): String {
        val sb = StringBuilder()

        sb.appendLine("📊 ${AILocalization.message("nutrition.chat.analysis.title", locale, response.mealInfo.name)}")
        sb.appendLine()
        sb.appendLine("📏 ${AILocalization.message("nutrition.chat.analysis.portion", locale, response.mealInfo.portion)}")
        sb.appendLine("🔥 ${AILocalization.message("nutrition.chat.analysis.calories", locale, response.calories)}")
        sb.appendLine()
        sb.appendLine("📊 ${AILocalization.message("nutrition.chat.analysis.macros", locale)}")
        sb.appendLine("• ${AILocalization.message("nutrition.chat.analysis.protein", locale, response.macros.protein)}")
        sb.appendLine("• ${AILocalization.message("nutrition.chat.analysis.carbs", locale, response.macros.carbs)}")
        sb.appendLine("• ${AILocalization.message("nutrition.chat.analysis.fat", locale, response.macros.fat)}")

        if (response.mealInfo.ingredients.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("🥘 ${AILocalization.message("nutrition.chat.analysis.ingredients", locale)}")
            response.mealInfo.ingredients.forEach { ingredient ->
                sb.appendLine("• $ingredient")
            }
        }

        if (response.suggestions.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💡 ${AILocalization.message("nutrition.chat.analysis.advice", locale)}")
            response.suggestions.forEach { suggestion ->
                sb.appendLine("• $suggestion")
            }
        }

        return sb.toString().trim()
    }

    fun uploadMealImage(userId: Long, file: MultipartFile): ImageUploadResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        return fileUploadService.uploadImage(
            file = file,
            type = "meal",
            metadata = null,
            userId = userId
        )
    }

    // Helper methods
    private fun parseFoodItems(foods: String): List<FoodItem> {
        // 실제로는 저장된 문자열을 파싱하여 FoodItem 리스트로 변환
        return foods.split(", ").map { food ->
            FoodItem(
                name = food.substringBefore(" "),
                quantity = 100.0,
                unit = "g",
                calories = 200,
                protein = 20.0,
                carbs = 15.0,
                fat = 10.0
            )
        }
    }

    private fun buildMealAnalysisPrompt(imageUrl: String): String {
        return """
            이미지 URL: $imageUrl

            이 음식을 분석해주세요:
            1. 음식 이름과 재료
            2. 예상 칼로리
            3. 영양소 (단백질, 탄수화물, 지방)
            4. 건강 관련 제안사항
        """.trimIndent()
    }

    private fun parseMealAnalysisResponse(aiResponse: String, locale: String, userId: Long): MealAnalysisResponse {
        return try {
            // JSON 응답 파싱
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            // 디버그 로그 추가
            logger.debug("AI Response for meal analysis: $cleanedResponse")

            val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val jsonResponse = objectMapper.readValue(cleanedResponse, Map::class.java) as Map<String, Any>

            val mealName = jsonResponse["meal_name"] as? String ?: AILocalization.message("nutrition.default.meal_name", locale)
            val ingredients = (jsonResponse["ingredients"] as? List<String>) ?: emptyList()
            val portion = jsonResponse["portion"] as? String ?: AILocalization.message("nutrition.default.portion", locale)
            val calories = parseNumber(jsonResponse["calories"])?.toInt() ?: 0

            // 매크로 영양소 파싱 개선
            val protein = parseNutrientValue(jsonResponse["protein"]) ?: 0.0
            val carbs = parseNutrientValue(jsonResponse["carbs"]) ?: 0.0
            val fat = parseNutrientValue(jsonResponse["fat"]) ?: 0.0

            val suggestions = (jsonResponse["suggestions"] as? List<String>) ?: emptyList()

            logger.info("Parsed macros - Protein: $protein, Carbs: $carbs, Fat: $fat")

            MealAnalysisResponse(
                mealInfo = MealInfo(
                    name = mealName,
                    ingredients = ingredients,
                    portion = portion,
                    type = determineMealTypeString(locale, userId)
                ),
                calories = calories,
                macros = Macros(protein, carbs, fat),
                suggestions = suggestions
            )
        } catch (e: Exception) {
            logger.error("Error parsing AI response: ${e.message}", e)
            logger.error("Raw AI response: $aiResponse")
            MealAnalysisResponse(
                mealInfo = MealInfo(
                    name = AILocalization.message("nutrition.default.meal_name_error", locale),
                    ingredients = listOf(AILocalization.message("nutrition.default.ingredient_unknown", locale)),
                    portion = AILocalization.message("nutrition.default.portion", locale),
                    type = AILocalization.message("nutrition.default.unknown", locale)
                ),
                calories = 0,
                macros = Macros(0.0, 0.0, 0.0),
                suggestions = listOf(AILocalization.message("nutrition.default.retry", locale))
            )
        }
    }

    private fun parseNutrientValue(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                // "20g", "20.5", "20" 등 다양한 형식 처리
                val cleanedValue = value.replace(Regex("[^0-9.]"), "")
                cleanedValue.toDoubleOrNull()
            }
            else -> null
        }
    }

    private fun parseNumber(value: Any?): Number? {
        return when (value) {
            is Number -> value
            is String -> {
                val cleanedValue = value.replace(Regex("[^0-9.]"), "")
                cleanedValue.toDoubleOrNull()
            }
            else -> null
        }
    }

    private fun determineMealTypeString(locale: String, userId: Long): String {
        val zoneId = AppTime.resolveZoneId(userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone)
        val localHour = AppTime.toUserLocalDateTime(AppTime.utcNow(), zoneId).hour
        return when (localHour) {
            in 5..10 -> AILocalization.message("nutrition.meal_type.breakfast", locale)
            in 11..14 -> AILocalization.message("nutrition.meal_type.lunch", locale)
            in 17..21 -> AILocalization.message("nutrition.meal_type.dinner", locale)
            else -> AILocalization.message("nutrition.meal_type.snack", locale)
        }
    }

    fun getDailyMealPlan(userId: Long): DailyMealPlanResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId)

        // 사용자 프로필 가져오기
        val userProfile = userProfileRepository.findByUser(user).orElse(null)

        // AI를 통한 식단 추천 생성
        val aiResponse = geminiAIService.generateDailyMealPlan(user)

        // AI 응답 파싱
        val response = parseDailyMealPlanResponse(aiResponse, locale)

        // 채팅 메시지로 저장
        val userMessage = AILocalization.message("nutrition.chat.request.daily_plan", locale)
        val aiResponseText = formatDailyMealPlanForChat(response, locale)

        val chatMessage = ChatMessage(
            user = user,
            userMessage = userMessage,
            aiResponse = aiResponseText,
            messageType = MessageType.TEXT,
            status = MessageStatus.COMPLETED
        )
        chatMessageRepository.save(chatMessage)

        return response
    }

    private fun parseDailyMealPlanResponse(aiResponse: String, locale: String): DailyMealPlanResponse {
        return try {
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val jsonResponse = objectMapper.readValue(cleanedResponse, Map::class.java) as Map<String, Any>

            // AI 메시지 추출
            val aiMessage = jsonResponse["greeting"] as? String ?: jsonResponse["message"] as? String

            val breakfast = parseIndividualMeal(jsonResponse["breakfast"] as Map<String, Any>, locale)
            val lunch = parseIndividualMeal(jsonResponse["lunch"] as Map<String, Any>, locale)
            val dinner = parseIndividualMeal(jsonResponse["dinner"] as Map<String, Any>, locale)

            val totalCalories = (jsonResponse["total_calories"] as? Number)?.toInt()
                ?: (breakfast.calories + lunch.calories + dinner.calories)

            val totalMacrosMap = jsonResponse["total_macros"] as? Map<String, Any>
            val totalMacros = if (totalMacrosMap != null) {
                Macros(
                    protein = (totalMacrosMap["protein"] as? Number)?.toDouble() ?: 0.0,
                    carbs = (totalMacrosMap["carbs"] as? Number)?.toDouble() ?: 0.0,
                    fat = (totalMacrosMap["fat"] as? Number)?.toDouble() ?: 0.0
                )
            } else {
                Macros(
                    protein = breakfast.macros.protein + lunch.macros.protein + dinner.macros.protein,
                    carbs = breakfast.macros.carbs + lunch.macros.carbs + dinner.macros.carbs,
                    fat = breakfast.macros.fat + lunch.macros.fat + dinner.macros.fat
                )
            }

            val tips = (jsonResponse["tips"] as? List<String>) ?: emptyList()

            DailyMealPlanResponse(
                date = LocalDate.now().toString(),
                aiMessage = aiMessage,
                breakfast = breakfast,
                lunch = lunch,
                dinner = dinner,
                totalCalories = totalCalories,
                totalMacros = totalMacros,
                tips = tips
            )
        } catch (e: Exception) {
            println("Error parsing daily meal plan: ${e.message}")
            DailyMealPlanResponse(
                date = LocalDate.now().toString(),
                aiMessage = null,
                breakfast = MealRecommendation(
                    AILocalization.message("nutrition.chat.daily.default_breakfast", locale),
                    AILocalization.message("nutrition.chat.daily.default_breakfast_desc", locale),
                    350,
                    Macros(15.0, 45.0, 10.0)
                ),
                lunch = MealRecommendation(
                    AILocalization.message("nutrition.chat.daily.default_lunch", locale),
                    AILocalization.message("nutrition.chat.daily.default_lunch_desc", locale),
                    450,
                    Macros(40.0, 30.0, 15.0)
                ),
                dinner = MealRecommendation(
                    AILocalization.message("nutrition.chat.daily.default_dinner", locale),
                    AILocalization.message("nutrition.chat.daily.default_dinner_desc", locale),
                    400,
                    Macros(35.0, 25.0, 20.0)
                ),
                totalCalories = 1200,
                totalMacros = Macros(90.0, 100.0, 45.0),
                tips = listOf(AILocalization.message("nutrition.chat.daily.default_tip", locale))
            )
        }
    }

    private fun parseIndividualMeal(mealMap: Map<String, Any>, locale: String): MealRecommendation {
        val macrosMap = mealMap["macros"] as? Map<String, Any>
        val macros = if (macrosMap != null) {
            Macros(
                protein = (macrosMap["protein"] as? Number)?.toDouble() ?: 0.0,
                carbs = (macrosMap["carbs"] as? Number)?.toDouble() ?: 0.0,
                fat = (macrosMap["fat"] as? Number)?.toDouble() ?: 0.0
            )
        } else {
            Macros(0.0, 0.0, 0.0)
        }

        return MealRecommendation(
            mealName = mealMap["meal_name"] as? String ?: AILocalization.message("nutrition.default.meal_name", locale),
            description = mealMap["description"] as? String ?: "",
            calories = (mealMap["calories"] as? Number)?.toInt() ?: 0,
            macros = macros
        )
    }

    private fun formatDailyMealPlanForChat(
        response: DailyMealPlanResponse,
        locale: String
    ): String {
        val sb = StringBuilder()

        response.aiMessage?.takeIf { it.isNotBlank() }?.let { greeting ->
            sb.appendLine(greeting)
            sb.appendLine()
        }

        sb.appendLine("📅 ${AILocalization.message("nutrition.chat.daily.title", locale)}")
        sb.appendLine()

        sb.appendLine("🌅 ${AILocalization.message("nutrition.chat.daily.breakfast", locale, response.breakfast.calories)}")
        sb.appendLine("• ${response.breakfast.mealName}")
        sb.appendLine("• ${response.breakfast.description}")
        sb.appendLine(
            "• ${AILocalization.message(
                "nutrition.chat.daily.macros",
                locale,
                response.breakfast.macros.protein,
                response.breakfast.macros.carbs,
                response.breakfast.macros.fat
            )}"
        )
        sb.appendLine()

        sb.appendLine("☀️ ${AILocalization.message("nutrition.chat.daily.lunch", locale, response.lunch.calories)}")
        sb.appendLine("• ${response.lunch.mealName}")
        sb.appendLine("• ${response.lunch.description}")
        sb.appendLine(
            "• ${AILocalization.message(
                "nutrition.chat.daily.macros",
                locale,
                response.lunch.macros.protein,
                response.lunch.macros.carbs,
                response.lunch.macros.fat
            )}"
        )
        sb.appendLine()

        sb.appendLine("🌙 ${AILocalization.message("nutrition.chat.daily.dinner", locale, response.dinner.calories)}")
        sb.appendLine("• ${response.dinner.mealName}")
        sb.appendLine("• ${response.dinner.description}")
        sb.appendLine(
            "• ${AILocalization.message(
                "nutrition.chat.daily.macros",
                locale,
                response.dinner.macros.protein,
                response.dinner.macros.carbs,
                response.dinner.macros.fat
            )}"
        )
        sb.appendLine()

        sb.appendLine("💪 ${AILocalization.message("nutrition.chat.daily.total_calories", locale, response.totalCalories)}")
        sb.appendLine(
            "📊 ${AILocalization.message(
                "nutrition.chat.daily.total_macros",
                locale,
                response.totalMacros.protein,
                response.totalMacros.carbs,
                response.totalMacros.fat
            )}"
        )

        if (response.tips.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💡 ${AILocalization.message("nutrition.chat.daily.tips", locale)}")
            response.tips.forEach { tip ->
                sb.appendLine("• $tip")
            }
        }

        return sb.toString().trim()
    }

    private fun resolveLocale(userId: Long): String {
        val language = userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        return AILocalization.normalizeLocale(language)
    }

    private fun determineMealType(userId: Long): MealType {
        val zoneId = AppTime.resolveZoneId(userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone)
        val localHour = AppTime.toUserLocalDateTime(AppTime.utcNow(), zoneId).hour
        return when (localHour) {
            in 5..10 -> MealType.BREAKFAST
            in 11..14 -> MealType.LUNCH
            in 17..21 -> MealType.DINNER
            else -> MealType.SNACK
        }
    }
}

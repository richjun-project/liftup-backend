package com.richjun.liftupai.domain.nutrition.service

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.repository.MealLogRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.upload.service.FileUploadService
import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
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
    private val userProfileRepository: com.richjun.liftupai.domain.user.repository.UserProfileRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getNutritionHistory(userId: Long, date: String, period: String): NutritionHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

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
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

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
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Gemini AI를 사용한 이미지 기반 식단 분석
        val aiResponse = geminiAIService.analyzeMealImage(request.imageUrl, user)

        // AI 응답 파싱
        val response = parseMealAnalysisResponse(aiResponse)

        // 분석 결과를 DB에 저장
        val mealLog = MealLog(
            user = user,
            mealType = determineMealType(),
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
        val userMessage = "🍽️ 식단 분석 요청"

        val aiResponseText = formatMealAnalysisForChat(response)

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

    private fun formatMealAnalysisForChat(response: MealAnalysisResponse): String {
        val sb = StringBuilder()

        sb.appendLine("📊 ${response.mealInfo.name} 분석 결과")
        sb.appendLine()
        sb.appendLine("📏 분량: ${response.mealInfo.portion}")
        sb.appendLine("🔥 칼로리: ${response.calories}kcal")
        sb.appendLine()
        sb.appendLine("📊 영양성분:")
        sb.appendLine("• 단백질: ${response.macros.protein}g")
        sb.appendLine("• 탄수화물: ${response.macros.carbs}g")
        sb.appendLine("• 지방: ${response.macros.fat}g")

        if (response.mealInfo.ingredients.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("🥘 재료:")
            response.mealInfo.ingredients.forEach { ingredient ->
                sb.appendLine("• $ingredient")
            }
        }

        if (response.suggestions.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💡 AI 영양사 조언:")
            response.suggestions.forEach { suggestion ->
                sb.appendLine("• $suggestion")
            }
        }

        return sb.toString().trim()
    }

    fun uploadMealImage(userId: Long, file: MultipartFile): ImageUploadResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

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

    private fun parseMealAnalysisResponse(aiResponse: String): MealAnalysisResponse {
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

            val mealName = jsonResponse["meal_name"] as? String ?: "분석된 음식"
            val ingredients = (jsonResponse["ingredients"] as? List<String>) ?: emptyList()
            val portion = jsonResponse["portion"] as? String ?: "1인분"
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
                    type = determineMealTypeString()
                ),
                calories = calories,
                macros = Macros(protein, carbs, fat),
                suggestions = suggestions
            )
        } catch (e: Exception) {
            logger.error("Error parsing AI response: ${e.message}", e)
            logger.error("Raw AI response: $aiResponse")
            // 파싱 실패 시 기본값 반환
            MealAnalysisResponse(
                mealInfo = MealInfo(
                    name = "음식 분석 실패",
                    ingredients = listOf("분석할 수 없음"),
                    portion = "알 수 없음",
                    type = "알 수 없음"
                ),
                calories = 0,
                macros = Macros(0.0, 0.0, 0.0),
                suggestions = listOf("이미지를 다시 업로드해주세요")
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

    private fun determineMealTypeString(): String {
        val hour = LocalDateTime.now().hour
        return when (hour) {
            in 5..10 -> "아침"
            in 11..14 -> "점심"
            in 17..21 -> "저녁"
            else -> "간식"
        }
    }

    fun getDailyMealPlan(userId: Long): DailyMealPlanResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 사용자 프로필 가져오기
        val userProfile = userProfileRepository.findByUser(user).orElse(null)

        // AI를 통한 식단 추천 생성
        val aiResponse = geminiAIService.generateDailyMealPlan(user)

        // AI 응답 파싱
        val response = parseDailyMealPlanResponse(aiResponse)

        // 채팅 메시지로 저장
        val userMessage = "🍱 오늘의 3끼 식단 추천 요청"
        val aiResponseText = formatDailyMealPlanForChat(response, user, userProfile)

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

    private fun parseDailyMealPlanResponse(aiResponse: String): DailyMealPlanResponse {
        return try {
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val jsonResponse = objectMapper.readValue(cleanedResponse, Map::class.java) as Map<String, Any>

            // AI 메시지 추출
            val aiMessage = jsonResponse["greeting"] as? String ?: jsonResponse["message"] as? String

            val breakfast = parseIndividualMeal(jsonResponse["breakfast"] as Map<String, Any>)
            val lunch = parseIndividualMeal(jsonResponse["lunch"] as Map<String, Any>)
            val dinner = parseIndividualMeal(jsonResponse["dinner"] as Map<String, Any>)

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
            // 파싱 실패 시 기본값 반환
            DailyMealPlanResponse(
                date = LocalDate.now().toString(),
                aiMessage = null,
                breakfast = MealRecommendation("오트밀", "건강한 아침 식사", 350, Macros(15.0, 45.0, 10.0)),
                lunch = MealRecommendation("닭가슴살 샐러드", "단백질 풍부", 450, Macros(40.0, 30.0, 15.0)),
                dinner = MealRecommendation("연어 구이", "오메가3 풍부", 400, Macros(35.0, 25.0, 20.0)),
                totalCalories = 1200,
                totalMacros = Macros(90.0, 100.0, 45.0),
                tips = listOf("충분한 수분 섭취를 잊지 마세요")
            )
        }
    }

    private fun parseIndividualMeal(mealMap: Map<String, Any>): MealRecommendation {
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
            mealName = mealMap["meal_name"] as? String ?: "식사",
            description = mealMap["description"] as? String ?: "",
            calories = (mealMap["calories"] as? Number)?.toInt() ?: 0,
            macros = macros
        )
    }

    private fun generateProfileBasedGreeting(
        profile: com.richjun.liftupai.domain.user.entity.UserProfile,
        nickname: String
    ): String {
        val bodyInfo = profile.bodyInfo
        val goals = profile.goals.map { it.name }.joinToString(", ")
        val ptStyle = profile.ptStyle

        // BMI 계산
        val bmi = if (bodyInfo?.weight != null && bodyInfo.height != null) {
            val weight = bodyInfo.weight!!
            val height = bodyInfo.height!!
            val heightM = height / 100.0
            String.format("%.1f", weight / (heightM * heightM))
        } else null

        // TDEE 계산
        val tdee = calculateTDEE(profile)

        // 기본 프로필 정보
        val profileInfo = buildString {
            if (bodyInfo?.weight != null) append("체중 ${bodyInfo.weight}kg")
            if (bodyInfo?.height != null) append(", 키 ${bodyInfo.height}cm")
            if (bmi != null) append(", BMI $bmi")
            if (goals.isNotEmpty()) append(", 목표: $goals")
        }

        return when (ptStyle) {
            com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN -> {
                "💪 전사여! 너의 프로필($profileInfo)을 분석했다.\n" +
                "오늘의 전투를 위한 연료를 공급하겠다! ${tdee?.let { "일일 목표: ${it}kcal" } ?: ""}"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.BURNOUT -> {
                "😑 아... ${nickname}, 또 왔네.\n" +
                "프로필 확인했어 ($profileInfo)\n" +
                "${tdee?.let { "하루 ${it}kcal인데" } ?: "뭐"} 어차피 치킨 먹을거잖아? 그래도 일단 추천은 해줄게..."
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER -> {
                "🎮 플레이어 ${nickname} 로그인!\n" +
                "[스탯 확인: $profileInfo]\n" +
                "${tdee?.let { "일일 에너지 ${it}EP 필요!" } ?: ""} 오늘의 식단 퀘스트를 시작하자! ⚔️"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.INFLUENCER -> {
                "✨ ${nickname}님~ 오늘도 빛나네요!\n" +
                "프로필 체크했어요 ($profileInfo)\n" +
                "${tdee?.let { "일일 ${it}kcal" } ?: ""} 맞춤 클린 식단! 인스타에 올리기 좋은 메뉴들로 준비했어요 📸"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.HIP_HOP -> {
                "Yo! ${nickname} what's up!\n" +
                "너의 스탯 체크 완료 ($profileInfo)\n" +
                "${tdee?.let { "${it}kcal" } ?: ""} that's your daily goal, 오늘의 meal plan let's go! 🎤🔥"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.RETIRED_TEACHER -> {
                "${nickname} 학생, 왔구나.\n" +
                "자네 신체 정보 봤네 ($profileInfo)\n" +
                "${tdee?.let { "하루 ${it}kcal" } ?: ""} 우리 때는 이런 계산도 없었는데... 라떼는 말이야... 🏫"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.OFFICE_MASTER -> {
                "${nickname} 대리님, 수고 많으십니다.\n" +
                "프로필 확인했습니다 ($profileInfo)\n" +
                "${tdee?.let { "일일 ${it}kcal" } ?: ""} 회식 고려한 현실적인 식단 짜드렸습니다... 🍻"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.LA_KOREAN -> {
                "Hey ${nickname}! What's up bro!\n" +
                "너의 stats 체크했어 ($profileInfo)\n" +
                "${tdee?.let { "Daily ${it}kcal" } ?: ""} perfect meal plan ready! Let's get it! 🇺🇸🔥"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.BUSAN_VETERAN -> {
                "야 ${nickname}! 왔나.\n" +
                "니 몸 상태 봤다 ($profileInfo)\n" +
                "${tdee?.let { "하루 ${it}kcal" } ?: ""} 마 제대로 묵을 거 추천해주께. 징징대지 말고 묵어라! 💪"
            }
            com.richjun.liftupai.domain.user.entity.PTStyle.SOLDIER -> {
                "안녕하십니... 아, 안녕하세요 ${nickname}님!\n" +
                "신상명세... 아니 프로필 확인했습니다! ($profileInfo)\n" +
                "${tdee?.let { "일일 보급량... 아니 ${it}kcal" } ?: ""} 식단 브리핑... 아니 추천 시작하겠습니다! 🫡"
            }
        }
    }

    private fun calculateTDEE(profile: com.richjun.liftupai.domain.user.entity.UserProfile): Int? {
        val bodyInfo = profile.bodyInfo ?: return null
        val weight = bodyInfo.weight ?: return null
        val height = bodyInfo.height ?: return null
        val age = profile.age ?: 30 // 기본값

        // Harris-Benedict 공식
        val bmr = if (profile.gender == "male" || profile.gender == null) {
            88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age)
        } else {
            447.593 + (9.247 * weight) + (3.098 * height) - (4.330 * age)
        }

        // 활동 계수 (중간 활동 기준)
        val activityFactor = 1.55

        return (bmr * activityFactor).toInt()
    }

    private fun formatDailyMealPlanForChat(
        response: DailyMealPlanResponse,
        user: com.richjun.liftupai.domain.auth.entity.User,
        userProfile: com.richjun.liftupai.domain.user.entity.UserProfile?
    ): String {
        val sb = StringBuilder()

        // PT 스타일에 따른 맞춤형 인사말 추가
        if (userProfile != null) {
            sb.appendLine(generateProfileBasedGreeting(userProfile, user.nickname))
            sb.appendLine()
        }

        sb.appendLine("📅 오늘의 식단 추천")
        sb.appendLine()

        sb.appendLine("🌅 아침 (${response.breakfast.calories}kcal)")
        sb.appendLine("• ${response.breakfast.mealName}")
        sb.appendLine("• ${response.breakfast.description}")
        sb.appendLine("• 단백질: ${response.breakfast.macros.protein}g | 탄수화물: ${response.breakfast.macros.carbs}g | 지방: ${response.breakfast.macros.fat}g")
        sb.appendLine()

        sb.appendLine("☀️ 점심 (${response.lunch.calories}kcal)")
        sb.appendLine("• ${response.lunch.mealName}")
        sb.appendLine("• ${response.lunch.description}")
        sb.appendLine("• 단백질: ${response.lunch.macros.protein}g | 탄수화물: ${response.lunch.macros.carbs}g | 지방: ${response.lunch.macros.fat}g")
        sb.appendLine()

        sb.appendLine("🌙 저녁 (${response.dinner.calories}kcal)")
        sb.appendLine("• ${response.dinner.mealName}")
        sb.appendLine("• ${response.dinner.description}")
        sb.appendLine("• 단백질: ${response.dinner.macros.protein}g | 탄수화물: ${response.dinner.macros.carbs}g | 지방: ${response.dinner.macros.fat}g")
        sb.appendLine()

        sb.appendLine("💪 총 칼로리: ${response.totalCalories}kcal")
        sb.appendLine("📊 총 영양소: 단백질 ${response.totalMacros.protein}g | 탄수화물 ${response.totalMacros.carbs}g | 지방 ${response.totalMacros.fat}g")

        if (response.tips.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💡 오늘의 팁:")
            response.tips.forEach { tip ->
                sb.appendLine("• $tip")
            }
        }

        return sb.toString().trim()
    }

    private fun determineMealType(): MealType {
        val hour = LocalDateTime.now().hour
        return when (hour) {
            in 5..10 -> MealType.BREAKFAST
            in 11..14 -> MealType.LUNCH
            in 17..21 -> MealType.DINNER
            else -> MealType.SNACK
        }
    }
}
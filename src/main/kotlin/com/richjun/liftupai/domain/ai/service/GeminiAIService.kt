package com.richjun.liftupai.domain.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.concurrent.TimeUnit

@Service
class GeminiAIService(
    private val objectMapper: ObjectMapper,
    private val userProfileRepository: UserProfileRepository
) {
    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    @Value("\${gemini.model:gemini-pro}")
    private lateinit var model: String

    @Value("\${gemini.max-tokens:2048}")
    private var maxTokens: Int = 2048

    @Value("\${gemini.temperature:0.7}")
    private var temperature: Double = 0.7

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun generateResponse(message: String, user: User): String {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            // 사용자의 PT 스타일 가져오기
            val userProfile = userProfileRepository.findByUser(user).orElse(null)
            val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER

            val systemPrompt = generateSystemPromptByStyle(ptStyle, user.nickname)

            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = "$systemPrompt\n\n사용자 질문: $message")
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
                    return getFallbackResponse(message, user)
                }

                val responseBody = response.body?.string()
                val geminiResponse = objectMapper.readValue(responseBody, GeminiResponse::class.java)

                return geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: getFallbackResponse(message, user)
            }
        } catch (e: Exception) {
            println("Error calling Gemini API: ${e.message}")
            e.printStackTrace()
            return getFallbackResponse(message, user)
        }
    }

    fun analyzeContent(prompt: String): String {
        return callGeminiAPI(prompt)
    }

    fun generateRecommendations(prompt: String): String {
        return callGeminiAPI(prompt)
    }

    fun analyzeMeal(prompt: String): String {
        return callGeminiAPI(prompt)
    }

    fun analyzeMealImage(imageUrl: String, user: User): String {
        val userProfile = userProfileRepository.findByUser(user).orElse(null)

        // 이미지 다운로드 및 Base64 인코딩
        val imageData = downloadAndEncodeImage(imageUrl)
        if (imageData == null) {
            return "이미지를 처리할 수 없습니다."
        }

        // 사용자 프로필 정보 구성
        val profileInfo = if (userProfile != null) {
            buildUserProfileInfo(userProfile)
        } else {
            "일반 사용자"
        }

        // PT 스타일 가져오기
        val ptStyle = userProfile?.ptStyle ?: com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER

        val prompt = """
            당신은 전문 영양사이자 PT 트레이너입니다. 이미지의 음식을 분석하고 JSON 형식으로 응답해주세요.

            $profileInfo

            PT 스타일: ${getPTStyleDescription(ptStyle)}

            사용자의 목표와 프로필을 고려하여 개인 맞춤 피드백을 제공해주세요:
            ${generateGoalBasedGuidance(userProfile)}

            다음 형식으로 응답해주세요:
            {
                "meal_name": "음식 이름",
                "ingredients": ["재료1", "재료2", ...],
                "portion": "예상 분량 (예: 1인분, 300g)",
                "calories": 예상 칼로리 (숫자),
                "protein": 단백질(g),
                "carbs": 탄수화물(g),
                "fat": 지방(g),
                "suggestions": [
                    "사용자의 목표에 맞는 구체적 제안 1",
                    "영양 균형 관련 제안 2",
                    "개선점 또는 칭찬 3"
                ]
            }

            suggestions는 ${ptStyle} 스타일로 작성하고, 사용자의 목표(${userProfile?.goals?.joinToString() ?: "일반 건강"})를 반영해주세요.

            JSON만 응답하고 다른 텍스트는 포함하지 마세요.
        """.trimIndent()

        return callGeminiAPIWithImage(prompt, imageData)
    }

    private fun downloadAndEncodeImage(imageUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(imageUrl)
                .build()

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

    private fun buildUserProfileInfo(profile: com.richjun.liftupai.domain.user.entity.UserProfile): String {
        val bodyInfo = profile.bodyInfo
        val goals = profile.goals.map { it.name }.joinToString(", ")

        val bmi = if (bodyInfo?.weight != null && bodyInfo.height != null) {
            val weight = bodyInfo.weight!!
            val height = bodyInfo.height!!
            val heightM = height / 100.0
            weight / (heightM * heightM)
        } else null

        val tdee = calculateTDEE(profile)

        return """
            사용자 정보:
            - 목표: $goals
            - 경험 수준: ${profile.experienceLevel}
            ${bodyInfo?.let { "- 체중: ${it.weight}kg, 키: ${it.height}cm" } ?: ""}
            ${bmi?.let { "- BMI: ${String.format("%.1f", it)}" } ?: ""}
            ${tdee?.let { "- 일일 권장 칼로리: ${it}kcal" } ?: ""}

            이 정보를 바탕으로 개인 맞춤 영양 평가를 제공해주세요.
        """.trimIndent()
    }

    private fun calculateTDEE(profile: com.richjun.liftupai.domain.user.entity.UserProfile): Int? {
        val bodyInfo = profile.bodyInfo ?: return null
        val weight = bodyInfo.weight ?: return null
        val height = bodyInfo.height ?: return null
        val age = profile.age ?: 30 // 기본값

        // Harris-Benedict 공식 (남성 기준, 추후 성별 구분 필요)
        val bmr = if (profile.gender == "male" || profile.gender == null) {
            88.362 + (13.397 * weight) + (4.799 * height) - (5.677 * age)
        } else {
            447.593 + (9.247 * weight) + (3.098 * height) - (4.330 * age)
        }

        // 활동 계수 (중간 활동 기준)
        val activityFactor = 1.55

        return (bmr * activityFactor).toInt()
    }

    private fun getPTStyleDescription(ptStyle: com.richjun.liftupai.domain.user.entity.PTStyle): String {
        return when (ptStyle) {
            com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN ->
                "강하고 단호한 스파르타식 - 엄격한 기준, 변명 불허"
            com.richjun.liftupai.domain.user.entity.PTStyle.BURNOUT ->
                "3년차 번아웃 김PT - 모든 변명 다 들어본, 현실적이고 직설적인"
            com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER ->
                "게임 마스터 레벨업 - 운동을 RPG로, 경험치와 퀘스트로 동기부여"
            com.richjun.liftupai.domain.user.entity.PTStyle.INFLUENCER ->
                "인플루언서 워너비 예나쌤 - 필라테스와 요가 감성, 인스타 스타일"
            com.richjun.liftupai.domain.user.entity.PTStyle.HIP_HOP ->
                "힙합 PT 스웨거 - 모든 걸 힙합 가사처럼, MZ 스타일"
            com.richjun.liftupai.domain.user.entity.PTStyle.RETIRED_TEACHER ->
                "은퇴한 체육선생님 박선생 - 옛날 얘기와 라떼 썰, 꼰대미 충만"
            com.richjun.liftupai.domain.user.entity.PTStyle.OFFICE_MASTER ->
                "회식 마스터 이과장 - 직장인의 아픔 이해, 현실적인 조언"
            com.richjun.liftupai.domain.user.entity.PTStyle.LA_KOREAN ->
                "LA 교포 PT 제이슨 - 영어 섹어쓰며 미국식 텐션, 한국식 영어"
            com.richjun.liftupai.domain.user.entity.PTStyle.BUSAN_VETERAN ->
                "부산 선수 출신 동수형 - 거친 부산 사투리로 팩트폭격"
            com.richjun.liftupai.domain.user.entity.PTStyle.SOLDIER ->
                "갓 전입온 일병 김일병 - 열정은 있지만 서툴고 군대식 습관"
        }
    }

    private fun generateGoalBasedGuidance(profile: com.richjun.liftupai.domain.user.entity.UserProfile?): String {
        if (profile == null) return "일반적인 건강 유지를 위한 균형잡힌 식사"

        val goals = profile.goals
        val bodyInfo = profile.bodyInfo
        val tdee = calculateTDEE(profile)

        val guidance = mutableListOf<String>()

        if (goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS)) {
            guidance.add("체중 감량: 칼로리 적자 필요 (일일 ${tdee?.let { it - 500 } ?: "TDEE-500"}kcal 목표)")
        }
        if (goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.MUSCLE_GAIN)) {
            val proteinGoal = bodyInfo?.weight?.times(2.0) ?: 60.0
            guidance.add("근육 증가: 단백질 ${proteinGoal}g 이상 섭취 권장")
        }
        if (goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.STRENGTH)) {
            guidance.add("근력 향상: 운동 전후 탄수화물 섭취 중요")
        }
        if (goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.ENDURANCE)) {
            guidance.add("지구력 향상: 탄수화물 비중 높이고 수분 섭취 충분히")
        }

        return if (guidance.isNotEmpty()) {
            guidance.joinToString("\n")
        } else {
            "일반 건강 유지: 균형잡힌 영양소 섭취"
        }
    }

    fun generateDailyMealPlan(user: User): String {
        val userProfile = userProfileRepository.findByUser(user).orElse(null)
        val ptStyle = userProfile?.ptStyle ?: PTStyle.GAME_MASTER

        // Calculate TDEE and macro distribution
        val tdee = calculateTDEE(userProfile) ?: 2000
        val goals = userProfile?.goals ?: emptyList()
        val bodyInfo = userProfile?.bodyInfo

        // Calculate target calories based on goals
        val targetCalories = when {
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS) ->
                (tdee - 500).coerceAtLeast(1200)
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.MUSCLE_GAIN) ->
                tdee + 300
            else -> tdee
        }

        // Calculate macro distribution
        val proteinTarget = when {
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.MUSCLE_GAIN) ->
                bodyInfo?.weight?.times(2.2) ?: 80.0
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS) ->
                bodyInfo?.weight?.times(2.0) ?: 70.0
            else -> bodyInfo?.weight?.times(1.5) ?: 60.0
        }

        val carbRatio = when {
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.ENDURANCE) -> 0.5
            goals.contains(com.richjun.liftupai.domain.user.entity.FitnessGoal.WEIGHT_LOSS) -> 0.35
            else -> 0.4
        }

        val profileInfo = if (userProfile != null) {
            """
            사용자 프로필:
            - 목표: ${goals.map { it.name }.joinToString(", ")}
            - 경험 수준: ${userProfile.experienceLevel}
            ${bodyInfo?.let { "- 체중: ${it.weight}kg, 키: ${it.height}cm" } ?: ""}
            - 일일 목표 칼로리: ${targetCalories}kcal
            - 목표 단백질: ${proteinTarget.toInt()}g
            - PT 스타일: ${getPTStyleDescription(ptStyle)}
            """.trimIndent()
        } else {
            "일반 사용자 (목표 칼로리: 2000kcal)"
        }

        val prompt = """
            당신은 전문 영양사입니다. 사용자에게 하루 3끼 식사를 추천해주세요.

            $profileInfo

            칼로리 배분:
            - 아침: 전체의 25-30%
            - 점심: 전체의 35-40%
            - 저녁: 전체의 30-35%

            매크로 목표:
            - 단백질: ${proteinTarget.toInt()}g
            - 탄수화물: ${(targetCalories * carbRatio / 4).toInt()}g
            - 지방: ${(targetCalories * 0.25 / 9).toInt()}g

            한국인이 쉽게 구할 수 있는 식사로 추천해주세요.

            다음 JSON 형식으로만 응답하세요:
            {
                "greeting": "${ptStyle} 스타일로 사용자의 프로필을 보고 인사하며 분석 내용을 한두 문장으로 언급 (예: '야 할롱이! 니 몸 상태 봤다. 체중 69kg, BMI 22.5 적정체중이네. 오늘 칼로리는 2400kcal로 짜줄게.')",
                "date": "${java.time.LocalDate.now()}",
                "breakfast": {
                    "meal_name": "식사명",
                    "description": "간단한 설명 (조리법 불필요)",
                    "calories": 칼로리(숫자),
                    "protein": 단백질g,
                    "carbs": 탄수화물g,
                    "fat": 지방g
                },
                "lunch": {
                    "meal_name": "식사명",
                    "description": "간단한 설명",
                    "calories": 칼로리,
                    "protein": 단백질g,
                    "carbs": 탄수화물g,
                    "fat": 지방g
                },
                "dinner": {
                    "meal_name": "식사명",
                    "description": "간단한 설명",
                    "calories": 칼로리,
                    "protein": 단백질g,
                    "carbs": 탄수화물g,
                    "fat": 지방g
                },
                "total_calories": 총칼로리,
                "total_protein": 총단백질g,
                "total_carbs": 총탄수화물g,
                "total_fat": 총지방g,
                "tips": [
                    "${ptStyle} 스타일의 식단 관련 팁 1",
                    "목표 달성을 위한 조언 2",
                    "영양 균형 관련 팁 3"
                ]
            }

            tips는 ${ptStyle} 스타일로 작성하세요.
            JSON만 응답하고 다른 텍스트는 포함하지 마세요.
        """.trimIndent()

        return callGeminiAPI(prompt)
    }

    private fun callGeminiAPIWithImage(prompt: String, imageData: String): String {
        try {
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
                    temperature = 0.5,  // 더 정확한 분석을 위해 낮춤
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
                    return "AI 분석 중 오류가 발생했습니다."
                }

                val responseBody = response.body?.string()
                val geminiResponse = objectMapper.readValue(responseBody, GeminiResponse::class.java)

                return geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: "AI 응답을 받을 수 없습니다."
            }
        } catch (e: Exception) {
            println("Error calling Gemini API with image: ${e.message}")
            e.printStackTrace()
            return "AI 분석 중 오류가 발생했습니다: ${e.message}"
        }
    }

    private fun callGeminiAPI(prompt: String): String {
        try {
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
                    return "AI 분석 중 오류가 발생했습니다."
                }

                val responseBody = response.body?.string()
                val geminiResponse = objectMapper.readValue(responseBody, GeminiResponse::class.java)

                return geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: "AI 응답을 받을 수 없습니다."
            }
        } catch (e: Exception) {
            println("Error calling Gemini API: ${e.message}")
            e.printStackTrace()
            return "AI 분석 중 오류가 발생했습니다: ${e.message}"
        }
    }

    private fun generateSystemPromptByStyle(ptStyle: PTStyle, nickname: String): String {
        val basePrompt = """
            당신은 LiftUp AI의 개인 AI 트레이너입니다.
            사용자: $nickname

            역할:
            - 운동, 영양, 건강에 대한 전문적인 조언 제공
            - 사용자의 목표와 수준을 고려한 맞춤형 답변
        """.trimIndent()

        val stylePrompt = when (ptStyle) {
            PTStyle.SPARTAN -> """

                대화 스타일:
                - 매우 강하고 단호한 스파르타식 톤
                - 군대식 트레이너처럼 엄격한 지시
                - "한계는 없다!", "더 강해져라!" 같은 강한 동기부여
                - 변명은 받아들이지 않음
                - 극한의 도전을 요구
                - 💪🔥 강렬한 이모티콘 사용
            """.trimIndent()

            PTStyle.BURNOUT -> """

                대화 스타일:
                - 3년차 번아웃 김PT - 모든 변명을 다 들어본 현타 온 트레이너
                - "아 그래... 오늘도 다이어트 내일부터지? 나도 안다"
                - "치킨? 먹어. 대신 내일 죽을 각오하고 와"
                - "폼 망가졌는데... 아 몰라 다치지만 마"
                - "월요일마다 새로 시작하면 1년에 52번 시작하는 거야"
                - 지쳤지만 그래도 프로답게 운동은 시킴
                - 현실적이고 직설적인 조언
            """.trimIndent()

            PTStyle.GAME_MASTER -> """

                대화 스타일:
                - 게임 마스터 PT '레벨업' - 모든 운동을 RPG로 변환
                - "축하합니다! 스쿼트 50개 달성! 하체 근력 +3, 정신력 +2 획득!"
                - "보스전(바디프로필)까지 D-30! 지금 레벨이 너무 낮아!"
                - "경험치 2배 이벤트! 지금 한 세트 더!"
                - "치킨 먹으면 디버프 걸립니다. -민첩 -5, 지방 +10"
                - "헬스장 입장! 오늘의 일일 퀘스트를 확인하세요"
                - 🎮⚔️🛡️ 게임 관련 이모티콘 활용
            """.trimIndent()

            PTStyle.INFLUENCER -> """

                대화 스타일:
                - 인플루언서 워너비 예나쌤 - 필라테스와 요가에 진심인 감성 트레이너
                - "오늘 운동룩 너무 예뻐요! 애슬레저룩 완벽!"
                - "이 동작 릴스 찍기 딱 좋은데... 같이 찍을래요?"
                - "여기 코어 잡고... 숨 쉬어요... 마인드풀하게..."
                - "오늘 초승달이래요. 하체 운동하기 좋은 날!"
                - "글루트 활성화시키고... 힙딥 채워봐요 언니"
                - "매트 색깔이 차크라랑 안 맞는 것 같은데..."
                - 🧘‍♀️✨🌙 감성적인 이모티콘 사용
            """.trimIndent()

            PTStyle.HIP_HOP -> """

                대화 스타일:
                - 힙합 PT 스웨거 - 모든 걸 힙합 가사처럼 말하는 스타일
                - "Yo! 오늘도 Iron 들어 올려, no pain no gain that's my story"
                - "벤치에 누워 바벨 밀어, 내 가슴은 getting bigger"
                - "싫어하는 유산소 but 복근 위해 I gotta go"
                - "프로틴 shake it shake it 근육 make it make it"
                - "헬스장이 내 studio, 덤벨이 내 microphone"
                - "약한 모습 kill that 거울 속 날 feel that"
                - 🎤🔥💯 힙합 감성 이모티콘
            """.trimIndent()

            PTStyle.RETIRED_TEACHER -> """

                대화 스타일:
                - 은퇴한 체육선생님 박선생 - 옛날 얘기와 라떼 썰 풀면서 운동
                - "내가 88올림픽 때는 말이야... 아 그때 넌 태어나지도 않았구나"
                - "요즘 애들은 근성이 없어. 우리 때는 토끼뜀으로 운동장 10바퀴..."
                - "이게 운동이야? 우리 때는 이거 준비운동이었어"
                - "스마트워치? 우리는 맥박 직접 재면서 했다고"
                - "BTS? 우리 때 서태지가 진짜지. 그 복근 봤어?"
                - 라떼는 말이야... 스타일의 꼰대 어투
            """.trimIndent()

            PTStyle.OFFICE_MASTER -> """

                대화 스타일:
                - 회식 마스터 이과장 - 직장인의 아픔을 100% 이해하는 아저씨 트레이너
                - "어제 회식했다고? 그래... 2차 갔지? 3차도? ...오늘은 가볍게 하자"
                - "금요일에 운동을 왜 해? 어차피 밤에 다 먹을건데"
                - "부장이 치킨 사준다는데 어떻게 안 먹어? 먹고 와"
                - "스트레스 받으면 살 더 쪄. 차라리 맛있게 먹고 운동해"
                - "월요병으로 운동하면 부상만 입어. 수요일에 보자"
                - 직장인 공감 100% 현실적인 조언
            """.trimIndent()

            PTStyle.LA_KOREAN -> """

                대화 스타일:
                - LA 교포 PT 제이슨 - 영어 섹어쓰며 미국식 텐션으로 밀어붙이는 스타일
                - "오케이 guys, 오늘 렉 데이인데 why are you walking? 크롤링해야지!"
                - "No no no! Form이 완전 엉망이야! 다시, from the beginning!"
                - "브로, 치킨? Seriously? 너 cutting 중이라며? Come on man!"
                - "한국 헬스장은 너무 조용해. 미국은 다 소리 지르는데... LIGHT WEIGHT BABY!"
                - "Cardio 30분? That's nothing bro. 미국 고등학교 풋볼 연습이 더 빡세"
                - "김치? Good good, probiotics 최고지. But 라면? Hell no!"
                - "Let's get it! 화이팅 아니고 Let's go! 자 갑니다!"
                - 한글리시 섮어 쓰기
            """.trimIndent()

            PTStyle.BUSAN_VETERAN -> """

                대화 스타일:
                - 부산 선수 출신 동수형 - 거친 부산 사투리로 팩트폭격하는 현실적인 트레이너
                - "아이고 마! 그기 무슨 운동이고? 니 장난하나? 확 마 패뿌릴라"
                - "와이라노? 힘들다고? 아직 10개밖에 안했는데 벌써 징징대나"
                - "치킨 묵고 왔제? 냄새가 여까지 나는데... 마 오늘 뒤질 각오해라"
                - "끝이가? 끝이 뭔 끝이고! 우야노 벌써 헐떡거리노"
                - "니가 그카고 앉아있으이께 배가 나오는기라. 마 일나라!"
                - "아따 마! 폼이 와이라노? 허리 나가뿌겠다 그카다가는"
                - "프로틴? 그거 묵으모 근육 생기나? 운동을 해야 생기지 마"
                - 부산 사투리 팩트 폭격
            """.trimIndent()

            PTStyle.SOLDIER -> """

                대화 스타일:
                - 갓 전입온 일병 PT 김일병 - 열정은 있지만 서툴고 실수도 많이 함
                - "안녕하십니까! 아, 아니다... 안녕하세요! 헬스장 회원님!"
                - "하나, 둘, 셋... 어? 몇 개까지 세었지? 아 잠깐만... 다시! 하나, 둘..."
                - "오늘 PT 시작... 아 정렬! 아니, 차려! 아니... 그냥 운동 시작할게요"
                - "저희 부대에서는... 아, 여기 민간이구나. 그냥 편하게 하세요"
                - "선임 PT님이 이렇게 하라고... 아니 제가 배운 거로는..."
                - "휴식 시간 10초! 아니 30초! 아니 1분... 얼마가 좋을까요?"
                - "관등성명 아니... 이름이 뭐였죠? 아, 회원님!"
                - "이 운동 부상 위험도 3... 아니 민간에서는 안전하다고 해야하나?"
                - 열정은 있지만 경험 부족으로 허둥대는 모습
                - 서툴지만 열심히 하려고 노력함

                활용 프롬프트:
                "너는 이제 갓 전입온 일병 PT 김일병이야.
                군대 일병처럼 열정은 있지만 서툴고 실수도 많이 해.
                '안녕하십니까!' 같은 군대식 인사를 하다가
                민간이라는 걸 깨닫고 당황하는 식으로 말해.
                선임 PT 눈치도 보고, 카운팅도 까먹고,
                열심히 하려고 하지만 어설픈 느낌으로."
            """.trimIndent()
        }

        return basePrompt + stylePrompt
    }

    private fun getFallbackResponse(message: String, user: User): String {
        return when {
            message.contains("운동", ignoreCase = true) ->
                "${user.nickname}님, 운동에 관심이 있으시군요! 어떤 부위 운동을 원하시나요? 가슴, 등, 하체 중 선택해주세요! 💪"

            message.contains("식단", ignoreCase = true) || message.contains("음식", ignoreCase = true) ->
                "균형 잡힌 식단이 중요해요! 단백질, 탄수화물, 지방을 적절히 섭취하시고, 충분한 수분 섭취를 잊지 마세요! 🥗"

            message.contains("안녕", ignoreCase = true) || message.contains("하이", ignoreCase = true) ->
                "안녕하세요 ${user.nickname}님! 오늘도 건강한 하루 되세요! 운동이나 식단에 대해 궁금한 점이 있으시면 언제든 물어보세요! 😊"

            else ->
                "${user.nickname}님의 질문을 이해했어요! 더 자세한 정보를 주시면 더 정확한 답변을 드릴 수 있을 것 같아요. 운동, 식단, 건강 관련 질문을 해주세요! 💭"
        }
    }
}

// Gemini API Request/Response 데이터 클래스들
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
    val data: String  // Base64 encoded
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
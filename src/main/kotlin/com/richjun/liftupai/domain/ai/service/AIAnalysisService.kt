package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.util.ExerciseNameNormalizer
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.workout.service.WorkoutProgressTracker
import com.richjun.liftupai.domain.workout.service.WorkoutProgramPosition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
@Transactional
class AIAnalysisService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val geminiAIService: GeminiAIService,
    private val objectMapper: ObjectMapper,
    private val workoutProgressTracker: WorkoutProgressTracker,
    private val workoutServiceV2: com.richjun.liftupai.domain.workout.service.WorkoutServiceV2,
    private val workoutSessionRepository: com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val exerciseNameNormalizer: ExerciseNameNormalizer
) {

    fun analyzeForm(userId: Long, request: FormAnalysisRequest): FormAnalysisResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val exercise = exerciseRepository.findById(request.exerciseId)
            .orElseThrow { ResourceNotFoundException("운동을 찾을 수 없습니다") }

        // Gemini AI를 사용한 자세 분석
        val analysisPrompt = buildFormAnalysisPrompt(exercise.name, request.videoUrl ?: request.imageUrl)
        val aiResponse = geminiAIService.analyzeContent(analysisPrompt)

        // AI 응답 파싱 (실제로는 더 정교한 파싱 로직 필요)
        return parseFormAnalysisResponse(aiResponse)
    }

    fun getRecommendations(userId: Long, type: String, muscleGroups: List<String>): RecommendationsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)

        // Gemini AI를 사용한 추천 생성
        val recommendationPrompt = buildRecommendationPrompt(type, muscleGroups, profile)
        val aiResponse = geminiAIService.generateRecommendations(recommendationPrompt)

        // AI 응답 파싱
        return parseRecommendationsResponse(aiResponse, type)
    }

    // Helper methods
    private fun buildFormAnalysisPrompt(exerciseName: String, mediaUrl: String?): String {
        return """
            운동: $exerciseName
            미디어 URL: $mediaUrl

            이 운동 자세를 분석해주세요:
            1. 자세의 정확도 점수 (0-100)
            2. 개선이 필요한 부분
            3. 교정 방법
            4. 전반적인 분석
        """.trimIndent()
    }

    private fun buildRecommendationPrompt(type: String, muscleGroups: List<String>, profile: Any?): String {
        val profileInfo = if (profile is UserProfile) {
            """
            경험 수준: ${profile.experienceLevel}
            목표: ${profile.goals.joinToString()}
            주간 운동일: ${profile.weeklyWorkoutDays}
            선호 운동 시간: ${profile.preferredWorkoutTime}
            운동 분할: ${profile.workoutSplit}
            운동 시간: ${profile.workoutDuration}분
            """.trimIndent()
        } else {
            "프로필 정보 없음"
        }

        return """
            추천 유형: $type
            근육 그룹: ${muscleGroups.joinToString(", ")}
            사용자 프로필:
            $profileInfo

            다음을 추천해주세요:
            1. 운동 프로그램
            2. 영양 섭취
            3. 회복 방법
        """.trimIndent()
    }

    private fun parseFormAnalysisResponse(aiResponse: String): FormAnalysisResponse {
        // 실제로는 AI 응답을 정교하게 파싱
        // 여기서는 간단한 예시
        return FormAnalysisResponse(
            analysis = "자세가 전반적으로 양호합니다.",
            score = 85,
            improvements = listOf(
                "무릎을 조금 더 굽히세요",
                "허리를 곧게 펴세요"
            ),
            corrections = listOf(
                "발 간격을 어깨너비로 조정",
                "시선을 정면으로 유지"
            )
        )
    }

    private fun parseRecommendationsResponse(aiResponse: String, type: String): RecommendationsResponse {
        // 실제로는 AI 응답을 정교하게 파싱
        return RecommendationsResponse(
            workouts = listOf(
                WorkoutRecommendation(
                    exerciseId = 1,
                    name = "벤치프레스",
                    sets = 4,
                    reps = "8-10",
                    reason = "가슴 근육 발달에 효과적",
                    difficulty = "중간"
                )
            ),
            nutrition = listOf(
                NutritionRecommendation(
                    food = "닭가슴살",
                    calories = 165,
                    macros = Macros(31.0, 0.0, 3.6),
                    timing = "운동 후 30분 이내",
                    reason = "근육 회복을 위한 단백질 공급"
                )
            ),
            recovery = listOf(
                RecoveryRecommendation(
                    activity = "스트레칭",
                    duration = 15,
                    intensity = "낮음",
                    benefits = listOf("유연성 향상", "근육 이완")
                )
            )
        )
    }

    fun chat(userId: Long, request: ChatRequest): ChatResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 컨텍스트 정보를 포함한 프롬프트 생성
        val enhancedMessage = if (request.context != null) {
            buildContextualMessage(request.message, request.context)
        } else {
            request.message
        }

        // Gemini AI에게 응답 요청
        val startTime = System.currentTimeMillis()
        val aiReply = try {
            geminiAIService.generateResponse(enhancedMessage, user)
        } catch (e: Exception) {
            // 에러 발생 시 실패 메시지 저장
            val errorMessage = ChatMessage(
                user = user,
                userMessage = request.message,
                aiResponse = "AI 응답 생성 중 오류가 발생했습니다.",
                messageType = MessageType.TEXT,
                status = MessageStatus.FAILED,
                error = e.message
            )
            chatMessageRepository.save(errorMessage)
            throw e
        }

        val responseTime = System.currentTimeMillis() - startTime

        // 성공한 경우 ChatMessage 저장
        val chatMessage = ChatMessage(
            user = user,
            userMessage = request.message,
            aiResponse = aiReply,
            messageType = MessageType.TEXT,
            status = MessageStatus.COMPLETED,
            responseTime = responseTime
        )

        val savedMessage = chatMessageRepository.save(chatMessage)

        // 응답에서 추천 키워드 추출 (선택사항)
        val suggestions = extractSuggestions(aiReply)

        return ChatResponse(
            reply = aiReply,
            timestamp = savedMessage.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            messageId = savedMessage.id.toString(),
            suggestions = suggestions
        )
    }

    private fun buildContextualMessage(message: String, context: ChatContext): String {
        val contextInfo = StringBuilder()

        context.workoutType?.let {
            contextInfo.append("현재 운동 종류: $it\n")
        }
        context.currentExercise?.let {
            contextInfo.append("현재 운동: $it\n")
        }
        context.userGoal?.let {
            contextInfo.append("사용자 목표: $it\n")
        }

        return if (contextInfo.isNotEmpty()) {
            """
            [컨텍스트]
            $contextInfo

            [사용자 질문]
            $message
            """.trimIndent()
        } else {
            message
        }
    }

    private fun extractSuggestions(aiReply: String): List<String> {
        val suggestions = mutableListOf<String>()

        // AI 응답에서 운동 관련 키워드 추출
        when {
            aiReply.contains("가슴", ignoreCase = true) -> suggestions.add("가슴 운동 보기")
            aiReply.contains("등", ignoreCase = true) -> suggestions.add("등 운동 보기")
            aiReply.contains("하체", ignoreCase = true) -> suggestions.add("하체 운동 보기")
            aiReply.contains("식단", ignoreCase = true) -> suggestions.add("식단 추천 받기")
            aiReply.contains("프로그램", ignoreCase = true) -> suggestions.add("운동 프로그램 생성")
        }

        // 최대 3개까지만 제안
        return suggestions.take(3)
    }

    fun getAIWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        difficulty: String? = null
    ): AIWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)

        // Check if user has started a workout today
        val hasStartedToday = workoutProgressTracker.hasStartedWorkoutToday(user)

        // Get user's program position
        val programDays = (profile as? UserProfile)?.weeklyWorkoutDays ?: 3
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)

        // Determine target muscle based on program position if not specified
        val adjustedTargetMuscle = if (!hasStartedToday && targetMuscle == null) {
            val programType = (profile as? UserProfile)?.workoutSplit ?: "PPL"
            val sequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
            val workoutType = sequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY

            when (workoutType) {
                WorkoutType.PUSH -> "가슴"
                WorkoutType.PULL -> "등"
                WorkoutType.LEGS -> "하체"
                WorkoutType.UPPER -> "상체"
                WorkoutType.LOWER -> "하체"
                WorkoutType.CHEST -> "가슴"
                WorkoutType.BACK -> "등"
                WorkoutType.ARMS -> "팔"
                WorkoutType.SHOULDERS -> "어깨"
                else -> "전신"
            }
        } else {
            targetMuscle
        }

        // 구조화된 프롬프트 생성 with program context
        val prompt = buildStructuredWorkoutPrompt(
            user,
            profile,
            duration,
            equipment,
            adjustedTargetMuscle,
            difficulty,
            programPosition,
            hasStartedToday
        )

        // AI 응답 받기
        val aiResponse = geminiAIService.generateRecommendations(prompt)

        // AI 응답을 WorkoutRecommendationDetail로 파싱
        val recommendation = parseAIWorkoutResponse(user, aiResponse, duration, equipment, adjustedTargetMuscle)

        // 대체 운동 생성
        val alternatives = generateAIAlternatives(duration, equipment, adjustedTargetMuscle)

        // AI 인사이트 생성 (AI 응답에서 파싱한 데이터 사용)
        val insights = parseAIInsights(aiResponse)

        return AIWorkoutRecommendationResponse(
            recommendation = recommendation,
            alternatives = alternatives,
            aiInsights = insights
        )
    }

    private fun buildStructuredWorkoutPrompt(
        user: com.richjun.liftupai.domain.auth.entity.User,
        profile: Any?,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?,
        programPosition: WorkoutProgramPosition? = null,
        hasStartedToday: Boolean = false
    ): String {
        val workoutDuration = duration ?: 30
        val targetDifficulty = difficulty ?: "intermediate"

        // PT 스타일 정보 추가
        val userProfile = profile as? UserProfile
        val ptStyle = userProfile?.ptStyle ?: com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER

        val profileInfo = if (profile is UserProfile) {
            """
            사용자 정보:
            - 이름: ${user.nickname}
            - PT 스타일: ${ptStyle}
            - 경험 수준: ${profile.experienceLevel}
            - 목표: ${profile.goals.joinToString()}
            - 주간 운동일: ${profile.weeklyWorkoutDays}
            - 선호 운동 시간: ${profile.preferredWorkoutTime}
            - 운동 분할: ${profile.workoutSplit}
            """.trimIndent()
        } else {
            "사용자 정보: ${user.nickname}, 초급자 수준"
        }

        // 최근 운동 이력 분석 추가
        val recentWorkouts = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        val workoutHistoryInfo = if (recentWorkouts.isNotEmpty()) {
            val muscleFrequency = analyzeMuscleFrequency(recentWorkouts)
            val daysSinceLastWorkout = if (recentWorkouts.isNotEmpty()) {
                java.time.Duration.between(recentWorkouts.first().startTime, LocalDateTime.now()).toDays()
            } else 7

            """

            최근 운동 패턴:
            - 마지막 운동: ${daysSinceLastWorkout}일 전
            - 이번 주 운동 횟수: ${recentWorkouts.size}회
            - 최근 주로 한 부위: ${muscleFrequency.entries.sortedByDescending { it.value }.take(2).joinToString { "${it.key}(${it.value}회)" }}
            - 부족한 부위: ${identifyUndertrainedMuscles(muscleFrequency)}
            """.trimIndent()
        } else {
            ""
        }

        val equipmentText = equipment?.let { "장비: $it" } ?: "장비: 모든 장비 사용 가능"
        val muscleText = targetMuscle?.let { "목표 근육: $it" } ?: "목표 근육: 전신"

        // PT 스타일별 지침 추가
        val styleGuidance = when (ptStyle) {
            com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN -> """
                코칭 스타일 지침:
                - 매우 강렬하고 도전적인 메시지 작성
                - "한계는 없다!", "더 강해져라!" 같은 강한 동기부여
                - 높은 강도와 볼륨의 운동 구성
                - 휴식 시간 최소화, 슈퍼세트 활용
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.BURNOUT -> """
                코칭 스타일 지침:
                - 3년차 번아웃 김PT - 모든 변명 다 들어본 현타 온 트레이너
                - "하... 또 그 핑계야?", "어차피 안 할거면서..." 같은 냉소적 표현
                - "뭐 어때, 살쪄도 행복하면 되지", "치킨 먹었구나? 티 나더라"
                - 현실적이고 직설적이지만 은근히 챙겨주는 스타일
                - 변명 다 들어봐서 예측하고 차단하는 멘트
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER -> """
                코칭 스타일 지침:
                - 게임 마스터 레벨업 - 모든 운동을 RPG로 변환
                - "오늘의 퀘스트!", "경험치 +100!", "보스전 돌입!" 같은 게임 용어
                - "스쿼트 던전 클리어", "벤치프레스로 레벨업", "근육 스탯 상승!"
                - 세트를 스테이지로, 운동을 퀘스트로 표현
                - 🎮⚔️🛡️ 게임 관련 이모티콘 사용
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.INFLUENCER -> """
                코칭 스타일 지침:
                - 인플루언서 워너비 예나쌤 - 필라테스와 요가 감성
                - "언니~ 오늘 바디 프로필 찍을 것처럼!", "글루트 활성화 시켜봐요"
                - "인스타에 올릴만한 폼이에요", "매트 색깔이 차크라랑..."
                - 필라테스, 요가 용어 많이 사용
                - ✨🧘‍♀️💕 감성적인 이모티콘 사용
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.HIP_HOP -> """
                코칭 스타일 지침:
                - 힙합 PT 스웨거 - 모든 걸 힙합 가사처럼
                - "Yo! 오늘도 Iron 들어 올려", "no pain no gain that's my story"
                - "벤치에 누워 바벨 밀어, 내 가슴은 getting bigger"
                - 라임 맞추고 플로우 있게 말하기
                - 🎤🔥💯 힙합 감성 이모티콘
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.RETIRED_TEACHER -> """
                코칭 스타일 지침:
                - 은퇴한 체육선생님 박선생 - 옛날 얘기와 라떼 썰
                - "우리 때는 말이야...", "요즘 애들은 근성이 없어"
                - "88올림픽 때는...", "이게 운동이야? 우리 때는 준비운동"
                - "스마트워치? 우리는 맥박 직접 재면서..."
                - 라떼는 말이야 스타일의 꼰대 어투
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.OFFICE_MASTER -> """
                코칭 스타일 지침:
                - 회식 마스터 이과장 - 직장인의 아픔을 100% 이해
                - "어제 회식했지? 2차 갔지? 3차도?", "금요일에 운동을 왜 해?"
                - "부장이 치킨 사준다는데 어떻게 안 먹어?"
                - "스트레스 받으면 살 더 쪄", "월요병으로 운동하면 부상"
                - 직장인 현실 공감 100%
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.LA_KOREAN -> """
                코칭 스타일 지침:
                - LA 교포 PT 제이슨 - 영어 섞어쓰며 미국식 텐션
                - "오케이 guys, 렉 데이인데 why are you walking?"
                - "No no no! Form이 완전 엉망", "브로, 치킨? Seriously?"
                - "한국 헬스장은 너무 조용해. LIGHT WEIGHT BABY!"
                - "Let's get it! 화이팅 아니고 Let's go!"
                - 한글리시 섞어 쓰기
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.BUSAN_VETERAN -> """
                코칭 스타일 지침:
                - 부산 선수 출신 동수형 - 거친 부산 사투리로 팩트폭격
                - "아이고 마! 그기 무슨 운동이고? 니 장난하나?"
                - "와이라노? 힘들다고? 아직 10개밖에 안했는데"
                - "치킨 묵고 왔제? 냄새가 여까지 나는데..."
                - "니가 그카고 앉아있으이께 배가 나오는기라"
                - 부산 사투리 팩트 폭격
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.SOLDIER -> """
                코칭 스타일 지침:
                - 갓 전입온 일병 김일병 - 열정은 있지만 서툴고 실수 많음
                - "안녕하십니까! 아, 아니다... 안녕하세요!"
                - "하나, 둘, 셋... 어? 몇 개까지 세었지? 다시!"
                - "오늘 PT 시작... 아 정렬! 아니, 차려! 아니..."
                - "선임 PT님이 이렇게 하라고... 아니 제가 배운 거로는..."
                - 군대식 습관과 민간 혼동, 서툴지만 열심히
            """.trimIndent()

            else -> """
                코칭 스타일 지침:
                - 일반적인 전문가 스타일
                - 친절하고 명확한 설명
                - 적절한 난이도와 휴식
            """.trimIndent()
        }

        // 최근 운동 성과 정보 추가
        val recentAchievements = if (recentWorkouts.isNotEmpty()) {
            """
            최근 성과:
            - 이번 주 운동 ${recentWorkouts.size}회 완료
            - 마지막 운동: ${recentWorkouts.first().workoutType}
            - 연속 운동일: ${calculateStreak(recentWorkouts)}일
            """.trimIndent()
        } else {
            "첫 운동을 시작합니다!"
        }

        return """
            운동 프로그램을 JSON 형식으로 추천해주세요.

            $profileInfo
            $workoutHistoryInfo
            $recentAchievements

            $styleGuidance

            요구사항:
            - 운동 시간: ${workoutDuration}분
            - $equipmentText
            - $muscleText
            - 난이도: $targetDifficulty

            다음 JSON 형식으로만 응답해주세요:
            {
              "workout_name": "맞춤 운동 프로그램",
              "exercises": [
                {
                  "name": "한글 운동명 (예: 벤치프레스, 스쿼트, 데드리프트)",
                  "sets": 세트 수(숫자),
                  "reps": "반복 횟수(예: 8-12)",
                  "rest_seconds": 휴식 시간(초),
                  "order": 순서(숫자)
                }
              ],
              "estimated_calories": 예상 칼로리,
              "difficulty": "초급/중급/고급 중 하나",
              "tips": ["구체적이고 실행 가능한 ${ptStyle} 스타일 팁 2-3개"],
              "progression_note": "다음 단계 목표와 달성 방법 - ${ptStyle} 스타일로",
              "coaching_message": "${ptStyle}에 맞는 개인화된 코칭 메시지 (운동 구성의 이유 포함)",
              "workout_focus": "오늘 운동의 핵심 포인트와 왜 중요한지",
              "ai_insights": {
                "workout_rationale": "[필수] 이 운동 구성의 핵심 이유 - ${ptStyle} 스타일로",
                "key_point": "[필수] 오늘 운동에서 가장 중요한 한 가지와 그 이유 - ${ptStyle} 스타일로",
                "next_step": "[필수] 다음 운동에서 도전할 구체적 목표와 그 근거 (예: 스쿼트 무게 5kg 증량 - 현재 3주 연속 같은 무게 유지중이라 점진적 과부하 필요) - ${ptStyle} 스타일로"
              }
            }

            주의사항:
            1. 모든 텍스트는 한글로 작성
            2. ${workoutDuration}분에 맞는 운동 개수 (4-8개)
            3. 같은 운동 중복 금지
            4. 복합관절 운동을 먼저, 단일관절 운동을 나중에
            5. 개인화된 메시지 (일반론 금지!)
            6. ${ptStyle} 스타일에 맞는 톤과 메시지
            7. workout_name에는 사용자 이름이나 일차를 포함하지 않음
            8. 모든 추천과 조언에는 구체적인 이유와 근거를 포함
            9. 사용자의 현재 상태, 목표, 최근 운동 패턴을 고려한 맞춤형 분석
            10. ai_insights의 3개 필드 (workout_rationale, key_point, next_step) 모두 필수로 작성
            11. JSON만 응답 (다른 텍스트 없이)
        """.trimIndent()
    }

    private fun parseAIWorkoutResponse(
        user: com.richjun.liftupai.domain.auth.entity.User,
        aiResponse: String,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?
    ): AIWorkoutDetail {
        return try {
            // JSON 응답 파싱 시도
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonResponse = objectMapper.readValue<Map<String, Any>>(cleanedResponse)

            val workoutName = jsonResponse["workout_name"] as? String ?: generateWorkoutName(duration, targetMuscle)
            val exercisesList = jsonResponse["exercises"] as? List<Map<String, Any>> ?: emptyList()
            val estimatedCalories = (jsonResponse["estimated_calories"] as? Number)?.toInt() ?: calculateCalories(duration ?: 30)
            val difficulty = jsonResponse["difficulty"] as? String ?: "중급"

            // AI가 생성한 추가 정보 추출
            val tips = (jsonResponse["tips"] as? List<String>) ?: emptyList()
            val progressionNote = jsonResponse["progression_note"] as? String
            val coachingMessage = jsonResponse["coaching_message"] as? String
            val workoutFocus = jsonResponse["workout_focus"] as? String

            // AI insights 파싱
            val aiInsightsMap = jsonResponse["ai_insights"] as? Map<String, Any>

            // 중복 제거를 위한 Set
            val usedExerciseIds = mutableSetOf<String>()
            val usedExerciseNames = mutableSetOf<String>()

            val exercises = exercisesList.mapIndexedNotNull { index, exerciseMap ->
                val exerciseName = exerciseMap["name"] as? String ?: "운동 ${index + 1}"
                val targetMuscle = (jsonResponse["target_muscles"] as? List<String>)?.firstOrNull()

                // 실제 운동 DB에서 매칭 시도
                var matchedExercise = findMatchingExerciseByName(exerciseName)
                var isAlternative = false

                // 매칭 실패 시 대체 운동 찾기
                if (matchedExercise == null) {
                    println("운동 '$exerciseName' 매칭 실패, 대체 운동 검색 중...")
                    matchedExercise = findAlternativeExercise(
                        exerciseName,
                        targetMuscle,
                        equipment
                    )
                    isAlternative = true
                }

                // 여전히 매칭에 실패한 경우 로깅하고 제외
                if (matchedExercise == null) {
                    println("경고: 운동 '$exerciseName'을 찾을 수 없고 대체 운동도 찾을 수 없어 제외합니다.")
                    return@mapIndexedNotNull null
                }

                val exerciseId = matchedExercise.id.toString()
                val finalExerciseName = if (isAlternative) {
                    "${matchedExercise.name} (대체: $exerciseName)"
                } else {
                    matchedExercise.name
                }

                // 중복 체크
                if (exerciseId in usedExerciseIds || matchedExercise.name.lowercase() in usedExerciseNames) {
                    // 대체 운동 찾기 시도
                    val alternativeExercise = if (matchedExercise.muscleGroups.isNotEmpty()) {
                        exerciseRepository.findAlternativeExercises(
                            matchedExercise.id,
                            matchedExercise.category,
                            matchedExercise.muscleGroups.toList()
                        ).firstOrNull { alt ->
                            alt.id.toString() !in usedExerciseIds &&
                            alt.name.lowercase() !in usedExerciseNames
                        }
                    } else null

                    if (alternativeExercise != null) {
                        matchedExercise = alternativeExercise
                        println("중복 운동 '$finalExerciseName' 대신 '${alternativeExercise.name}' 사용")
                    } else {
                        return@mapIndexedNotNull null // 대체 운동도 없으면 제외
                    }
                }

                usedExerciseIds.add(matchedExercise.id.toString())
                usedExerciseNames.add(matchedExercise.name.lowercase())

                // DB에서 운동 정보 가져오기
                val targetMuscles = getExerciseTargetMuscles(matchedExercise)
                val equipmentNeeded = matchedExercise.equipment?.name
                val difficultyLevel: String? = null

                // 무게 계산
                val suggestedWeight = try {
                    workoutServiceV2.calculateSuggestedWeight(user, matchedExercise)
                } catch (e: Exception) {
                    calculateWeightByExerciseName(user, matchedExercise.name)
                }

                AIExerciseDetail(
                    exerciseId = matchedExercise.id.toString(),
                    name = if (isAlternative) {
                        "${matchedExercise.name} (대체)"
                    } else {
                        matchedExercise.name
                    },
                    sets = (exerciseMap["sets"] as? Number)?.toInt() ?: 3,
                    reps = exerciseMap["reps"] as? String ?: "10-12",
                    rest = (exerciseMap["rest_seconds"] as? Number)?.toInt() ?: 60,
                    order = (exerciseMap["order"] as? Number)?.toInt() ?: (index + 1),
                    suggestedWeight = suggestedWeight,
                    targetMuscles = targetMuscles,
                    equipmentNeeded = equipmentNeeded,
                    difficultyLevel = difficultyLevel
                )
            }.sortedBy { it.order }

            // 전체 운동 정보는 상위 레벨에서 가져옴
            val musclesList = (jsonResponse["target_muscles"] as? List<String>) ?: emptyList()
            val equipmentList = (jsonResponse["equipment"] as? List<String>) ?: emptyList()

            AIWorkoutDetail(
                workoutId = "ai_${System.currentTimeMillis()}",
                name = workoutName,
                duration = duration ?: 30,
                difficulty = difficulty,
                exercises = exercises,
                estimatedCalories = estimatedCalories,
                targetMuscles = musclesList,
                equipment = equipmentList,
                tips = tips,
                progressionNote = progressionNote,
                coachingMessage = coachingMessage,  // AI가 생성한 메시지 사용
                workoutFocus = workoutFocus
            )

        } catch (e: Exception) {
            // JSON 파싱 실패 시 폴백 응답
            println("AI 응답 파싱 실패: ${e.message}")
            generateFallbackRecommendation(duration, equipment, targetMuscle)
        }
    }

    private fun findMatchingExerciseByName(exerciseName: String): Exercise? {
        // 1. ExerciseNameNormalizer를 사용한 정규화
        val normalizedName = exerciseNameNormalizer.normalize(exerciseName)

        // 2. 정확한 이름 매칭 (정규화된 이름으로)
        exerciseRepository.findByNameIgnoreCase(normalizedName)?.let {
            println("운동 매칭 성공 (정확한 매칭): AI 입력='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 3. Repository의 정규화 쿼리 사용
        exerciseRepository.findByNormalizedName(normalizedName).firstOrNull()?.let {
            println("운동 매칭 성공 (정규화 쿼리): AI 입력='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 4. 변형 생성 및 검색
        val variations = exerciseNameNormalizer.generateVariations(exerciseName)
        exerciseRepository.findByNameIn(variations.map { variation -> variation.lowercase() }).firstOrNull()?.let {
            println("운동 매칭 성공 (변형 매칭): AI 입력='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 5. 정확한/압축 이름 매칭
        exerciseRepository.findByExactOrCompactName(normalizedName).firstOrNull()?.let {
            println("운동 매칭 성공 (정확한/압축 매칭): AI 입력='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 6. 부분 매칭 시도 (폴백)
        val exercises = exerciseRepository.findAll()

        // 양방향 부분 매칭
        exercises.find { exercise ->
            normalizedName.contains(exerciseNameNormalizer.normalize(exercise.name), ignoreCase = true) ||
            exerciseNameNormalizer.normalize(exercise.name).contains(normalizedName, ignoreCase = true)
        }?.let { foundExercise ->
            println("운동 매칭 성공 (부분 매칭): AI 입력='$exerciseName' -> DB='${foundExercise.name}'")
            return foundExercise
        }

        // 7. 핵심 키워드 매칭
        val keywords = extractKeywords(normalizedName)
        if (keywords.isNotEmpty()) {
            exercises.find { exercise ->
                val normalizedDbName = exerciseNameNormalizer.normalize(exercise.name)
                keywords.any { keyword ->
                    normalizedDbName.contains(keyword, ignoreCase = true)
                }
            }?.let { foundExercise ->
                println("운동 매칭 성공 (키워드 매칭): AI 입력='$exerciseName' -> DB='${foundExercise.name}'")
                return foundExercise
            }
        }

        // 8. 최종 실패 로깅
        println("운동 매칭 실패: AI 입력='$exerciseName' (정규화='$normalizedName')를 DB에서 찾을 수 없음")
        return null
    }

    private fun extractKeywords(exerciseName: String): List<String> {
        // 핵심 운동 키워드 추출
        val coreExercises = listOf(
            "스쿼트", "프레스", "데드리프트", "런지", "로우", "컬",
            "플라이", "레이즈", "푸시다운", "익스텐션", "풀다운", "딥스",
            "푸시업", "풀업", "크런치", "플랭크", "레그레이즈"
        )
        return coreExercises.filter { exerciseName.contains(it, ignoreCase = true) }
    }

    private fun calculateWeightByExerciseName(user: com.richjun.liftupai.domain.auth.entity.User, exerciseName: String): Double? {
        // 운동명만으로는 정확한 무게 계산이 어려우므로 null 반환
        // 실제 DB에 있는 운동을 찾아서 계산하는 것이 정확함
        return null
    }

    private fun getExerciseTargetMuscles(exercise: Exercise): List<String> {
        // Exercise 엔티티에서 타겟 근육 정보 가져오기
        val muscles = mutableListOf<String>()

        // muscleGroups에서 타겟 근육 추출
        exercise.muscleGroups.forEach { muscleGroup ->
            muscles.add(translateMuscleGroupToKorean(muscleGroup))
        }

        // 카테고리 기반 폴백
        if (muscles.isEmpty()) {
            muscles.add(when (exercise.category) {
                ExerciseCategory.CHEST -> "가슴"
                ExerciseCategory.BACK -> "등"
                ExerciseCategory.LEGS -> "하체"
                ExerciseCategory.SHOULDERS -> "어깨"
                ExerciseCategory.ARMS -> "팔"
                ExerciseCategory.CORE -> "코어"
                else -> "전신"
            })
        }

        return muscles.distinct()
    }

    private fun translateMuscleGroupToKorean(muscleGroup: MuscleGroup): String {
        // Flutter 프론트엔드와 일치하는 16개 근육 그룹
        return when (muscleGroup) {
            MuscleGroup.CHEST -> "가슴"
            MuscleGroup.BACK -> "등"
            MuscleGroup.SHOULDERS -> "어깨"
            MuscleGroup.BICEPS -> "이두"
            MuscleGroup.TRICEPS -> "삼두"
            MuscleGroup.LEGS -> "다리"
            MuscleGroup.CORE -> "코어"
            MuscleGroup.ABS -> "복근"
            MuscleGroup.GLUTES -> "둔근"
            MuscleGroup.CALVES -> "종아리"
            MuscleGroup.FOREARMS -> "전완"
            MuscleGroup.NECK -> "목"
            MuscleGroup.QUADRICEPS -> "대퇴사두"
            MuscleGroup.HAMSTRINGS -> "햄스트링"
            MuscleGroup.LATS -> "광배근"
            MuscleGroup.TRAPS -> "승모근"
        }
    }

    private fun translateMuscleToKorean(muscle: String): String {
        return when (muscle.lowercase()) {
            "chest", "pectorals" -> "가슴"
            "back", "lats", "latissimus" -> "등"
            "legs", "quadriceps", "hamstrings", "glutes" -> "하체"
            "shoulders", "deltoids" -> "어깨"
            "arms", "biceps", "triceps" -> "팔"
            "core", "abs", "abdominals" -> "코어"
            else -> muscle
        }
    }

    private fun generateFallbackRecommendation(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?
    ): AIWorkoutDetail {
        val workoutDuration = duration ?: 30
        val exercises = getDefaultExercises(equipment, targetMuscle, workoutDuration)

        return AIWorkoutDetail(
            workoutId = "fallback_${System.currentTimeMillis()}",
            name = generateWorkoutName(duration, targetMuscle),
            duration = workoutDuration,
            difficulty = "중급",
            exercises = exercises,
            estimatedCalories = calculateCalories(workoutDuration),
            targetMuscles = getTargetMuscles(targetMuscle),
            equipment = listOfNotNull(equipment)
            // AI가 생성하지 못한 경우 null로 처리
        )
    }

    private fun getDefaultExercises(
        equipment: String?,
        targetMuscle: String?,
        duration: Int
    ): List<AIExerciseDetail> {
        val exerciseCount = when {
            duration <= 20 -> 3
            duration <= 30 -> 5
            duration <= 45 -> 6
            else -> 8
        }

        // 중복 제거를 위해 distinct 처리
        val allExercises = exerciseRepository.findAll()
        val selectedExercises = mutableListOf<Exercise>()
        val usedCategories = mutableSetOf<ExerciseCategory>()

        // 카테고리별로 하나씩만 선택하여 다양성 확보
        for (exercise in allExercises) {
            if (selectedExercises.size >= exerciseCount) break

            // 같은 카테고리에서 2개 이상 선택하지 않도록 제한
            val categoryCount = selectedExercises.count { it.category == exercise.category }
            if (categoryCount < 2) {
                selectedExercises.add(exercise)
            }
        }

        // 부족한 경우 추가 선택
        if (selectedExercises.size < exerciseCount) {
            val remaining = allExercises.filter { it !in selectedExercises }
            selectedExercises.addAll(remaining.take(exerciseCount - selectedExercises.size))
        }

        return selectedExercises.take(exerciseCount).mapIndexed { index, exercise ->
            AIExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = exercise.name,
                sets = when (exercise.category) {
                    ExerciseCategory.LEGS -> 4
                    ExerciseCategory.CHEST, ExerciseCategory.BACK -> 3
                    else -> 3
                },
                reps = when (exercise.category) {
                    ExerciseCategory.LEGS -> "12-15"
                    ExerciseCategory.CHEST, ExerciseCategory.BACK -> "8-12"
                    else -> "10-12"
                },
                rest = when (exercise.category) {
                    ExerciseCategory.LEGS, ExerciseCategory.CHEST, ExerciseCategory.BACK -> 90
                    else -> 60
                },
                order = index + 1
                // AI가 생성하지 못한 경우 null로 처리
            )
        }
    }

    private fun generateWorkoutName(duration: Int?, targetMuscle: String?): String {
        val durationText = duration?.let { "${it}분" } ?: "30분"
        val muscleText = when (targetMuscle?.lowercase()) {
            "chest" -> "가슴"
            "back" -> "등"
            "legs" -> "하체"
            "shoulders" -> "어깨"
            "arms" -> "팔"
            "core" -> "코어"
            else -> "전신"
        }
        return "AI 추천 $durationText $muscleText 운동"
    }

    private fun calculateCalories(duration: Int): Int {
        return duration * 7 // 분당 약 7칼로리
    }

    private fun getTargetMuscles(targetMuscle: String?): List<String> {
        return when (targetMuscle?.lowercase()) {
            "chest" -> listOf("chest", "triceps", "shoulders")
            "back" -> listOf("back", "biceps", "rear_delts")
            "legs" -> listOf("quadriceps", "hamstrings", "glutes", "calves")
            "shoulders" -> listOf("shoulders", "traps")
            "arms" -> listOf("biceps", "triceps", "forearms")
            "core" -> listOf("abs", "obliques", "lower_back")
            else -> listOf("full_body")
        }
    }

    private fun generateAIAlternatives(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?
    ): List<AIAlternativeWorkout> {
        val alternatives = mutableListOf<AIAlternativeWorkout>()
        val baseDuration = duration ?: 30

        // 시간 대체 옵션
        if (baseDuration > 20) {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_short_${System.currentTimeMillis()}",
                name = "AI 추천 ${baseDuration - 10}분 단축 운동",
                duration = baseDuration - 10
            ))
        }

        if (baseDuration < 45) {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_long_${System.currentTimeMillis()}",
                name = "AI 추천 ${baseDuration + 15}분 집중 운동",
                duration = baseDuration + 15
            ))
        }

        // 다른 근육 그룹 옵션
        if (targetMuscle != "full_body") {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_fullbody_${System.currentTimeMillis()}",
                name = "AI 추천 ${baseDuration}분 전신 운동",
                duration = baseDuration
            ))
        }

        return alternatives.take(2)
    }

    /**
     * 기본 인사이트 생성 (AI가 제공하지 않은 경우)
     */
    private fun generateDefaultInsights(jsonResponse: Map<String, Any>): AIWorkoutInsights {
        val coachingMessage = jsonResponse["coaching_message"] as? String
        val workoutFocus = jsonResponse["workout_focus"] as? String
        val progressionNote = jsonResponse["progression_note"] as? String
        val tips = (jsonResponse["tips"] as? List<String>)?.firstOrNull()

        return AIWorkoutInsights(
            workoutRationale = coachingMessage ?: "오늘의 운동은 균형잡힌 근육 발달과 체력 향상을 목표로 구성되었습니다.",
            keyPoint = workoutFocus ?: tips ?: "정확한 자세와 적절한 휴식 시간을 유지하는 것이 중요합니다.",
            nextStep = progressionNote ?: "다음 운동에서는 무게를 5% 증량하거나 반복 횟수를 2회 늘려보세요."
        )
    }

    /**
     * AI 응답에서 인사이트 파싱
     */
    private fun parseAIInsights(aiResponse: String): AIWorkoutInsights? {
        return try {
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonResponse = objectMapper.readValue<Map<String, Any>>(cleanedResponse)
            val aiInsightsMap = jsonResponse["ai_insights"] as? Map<String, Any>

            if (aiInsightsMap != null) {
                // 디버그 로깅
                println("AI Insights Map: $aiInsightsMap")

                val insights = AIWorkoutInsights(
                    workoutRationale = aiInsightsMap["workout_rationale"] as? String,
                    keyPoint = aiInsightsMap["key_point"] as? String,
                    nextStep = aiInsightsMap["next_step"] as? String
                )

                // 값이 하나라도 있으면 반환
                if (insights.workoutRationale != null || insights.keyPoint != null || insights.nextStep != null) {
                    return insights
                }
            }

            // AI가 insights를 제공하지 않은 경우 기본값 생성
            println("WARNING: ai_insights is missing or empty in AI response, generating defaults")
            return generateDefaultInsights(jsonResponse)
        } catch (e: Exception) {
            println("ERROR parsing AI insights: ${e.message}")
            // AI 응답 파싱 실패 시 null 반환
            null
        }
    }

    // 근육 부위별 운동 빈도 분석
    private fun analyzeMuscleFrequency(workouts: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): Map<String, Int> {
        val frequency = mutableMapOf<String, Int>()
        workouts.forEach { session ->
            when (session.workoutType) {
                WorkoutType.PUSH -> {
                    frequency["가슴"] = frequency.getOrDefault("가슴", 0) + 1
                    frequency["어깨"] = frequency.getOrDefault("어깨", 0) + 1
                    frequency["삼두"] = frequency.getOrDefault("삼두", 0) + 1
                }
                WorkoutType.PULL -> {
                    frequency["등"] = frequency.getOrDefault("등", 0) + 1
                    frequency["이두"] = frequency.getOrDefault("이두", 0) + 1
                }
                WorkoutType.LEGS -> {
                    frequency["하체"] = frequency.getOrDefault("하체", 0) + 1
                }
                WorkoutType.UPPER -> {
                    frequency["상체"] = frequency.getOrDefault("상체", 0) + 1
                }
                WorkoutType.LOWER -> {
                    frequency["하체"] = frequency.getOrDefault("하체", 0) + 1
                }
                WorkoutType.CHEST -> {
                    frequency["가슴"] = frequency.getOrDefault("가슴", 0) + 1
                }
                WorkoutType.BACK -> {
                    frequency["등"] = frequency.getOrDefault("등", 0) + 1
                }
                WorkoutType.SHOULDERS -> {
                    frequency["어깨"] = frequency.getOrDefault("어깨", 0) + 1
                }
                WorkoutType.ARMS -> {
                    frequency["팔"] = frequency.getOrDefault("팔", 0) + 1
                }
                else -> {
                    frequency["전신"] = frequency.getOrDefault("전신", 0) + 1
                }
            }
        }
        return frequency
    }

    // 부족한 근육 부위 파악
    private fun identifyUndertrainedMuscles(frequency: Map<String, Int>): String {
        val mainMuscles = listOf("가슴", "등", "하체", "어깨")
        val underTrained = mainMuscles.filter {
            frequency.getOrDefault(it, 0) < 1
        }

        return if (underTrained.isNotEmpty()) {
            underTrained.joinToString(", ")
        } else {
            "균형잡힌 운동 중"
        }
    }

    // 연속 운동일 계산
    private fun calculateStreak(workouts: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): Int {
        if (workouts.isEmpty()) return 0

        // 날짜별로 그룹화 (같은 날 여러 운동은 하나의 날로 처리)
        val workoutDates = workouts
            .map { it.startTime.toLocalDate() }
            .distinct()
            .sortedDescending()  // 최근 날짜부터

        // 가장 최근 운동일이 오늘 또는 어제가 아니면 연속 끊김
        val today = java.time.LocalDate.now()
        val lastWorkoutDate = workoutDates.first()
        val daysSinceLastWorkout = java.time.temporal.ChronoUnit.DAYS.between(lastWorkoutDate, today)

        if (daysSinceLastWorkout > 1) {
            return 0  // 연속 운동이 끊김
        }

        var streak = 1

        // 연속된 날짜 카운트
        for (i in 1 until workoutDates.size) {
            val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                workoutDates[i],
                workoutDates[i - 1]
            )

            if (daysBetween == 1L) {  // 정확히 하루 차이
                streak++
            } else {
                break
            }
        }

        return streak
    }

    /**
     * 대체 운동을 찾는 메서드
     * 운동 매칭에 실패했을 때 유사한 운동을 찾아 추천
     */
    private fun findAlternativeExercise(
        exerciseName: String,
        targetMuscle: String? = null,
        equipment: String? = null
    ): Exercise? {
        println("대체 운동 검색 시작: 원본='$exerciseName', 타겟근육='$targetMuscle', 장비='$equipment'")

        // 1. 운동명에서 근육 그룹 힌트 추출
        val muscleGroups = extractMuscleGroupsFromName(exerciseName, targetMuscle)
        val category = extractCategoryFromName(exerciseName, targetMuscle)

        // 2. 카테고리와 근육 그룹으로 대체 운동 검색
        if (category != null && muscleGroups.isNotEmpty()) {
            val alternatives = exerciseRepository.findByCategoryAndMuscleGroups(category, muscleGroups)
            if (alternatives.isNotEmpty()) {
                val selected = alternatives.first()
                println("대체 운동 찾음 (카테고리+근육): '$exerciseName' -> '${selected.name}'")
                return selected
            }
        }

        // 3. 근육 그룹만으로 검색
        if (muscleGroups.isNotEmpty()) {
            val alternatives = exerciseRepository.findByMuscleGroupsIn(muscleGroups)
            if (alternatives.isNotEmpty()) {
                // 가장 기본적인 운동 우선 선택 (이름이 짧은 것)
                val selected = alternatives.minByOrNull { it.name.length } ?: alternatives.first()
                println("대체 운동 찾음 (근육그룹): '$exerciseName' -> '${selected.name}'")
                return selected
            }
        }

        // 4. 카테고리만으로 검색 (폴백)
        if (category != null) {
            val alternatives = exerciseRepository.findByCategory(category)
            if (alternatives.isNotEmpty()) {
                val selected = alternatives.first()
                println("대체 운동 찾음 (카테고리): '$exerciseName' -> '${selected.name}'")
                return selected
            }
        }

        // 5. 최종 폴백: 가장 기본적인 운동들
        val fallbackExercises = mapOf(
            "가슴" to "푸시업",
            "등" to "풀업",
            "하체" to "스쿼트",
            "어깨" to "숄더프레스",
            "팔" to "덤벨컬",
            "코어" to "플랭크"
        )

        val fallbackName = fallbackExercises[targetMuscle] ?: "푸시업"
        val fallbackExercise = exerciseRepository.findByNameIgnoreCase(fallbackName)

        if (fallbackExercise != null) {
            println("폴백 운동 사용: '$exerciseName' -> '${fallbackExercise.name}'")
            return fallbackExercise
        }

        println("대체 운동 찾기 실패: '$exerciseName'")
        return null
    }

    /**
     * 운동명에서 근육 그룹 추출
     */
    private fun extractMuscleGroupsFromName(exerciseName: String, targetMuscle: String?): List<MuscleGroup> {
        val groups = mutableListOf<MuscleGroup>()
        val lowerName = exerciseName.lowercase()

        // 운동명에서 힌트 찾기
        when {
            lowerName.contains("푸시") || lowerName.contains("푸쉬") ||
            lowerName.contains("벤치") || lowerName.contains("플라이") -> {
                groups.add(MuscleGroup.CHEST)
                groups.add(MuscleGroup.TRICEPS)
            }
            lowerName.contains("풀") || lowerName.contains("로우") ||
            lowerName.contains("데드") -> {
                groups.add(MuscleGroup.BACK)
                groups.add(MuscleGroup.BICEPS)
            }
            lowerName.contains("스쿼트") || lowerName.contains("런지") ||
            lowerName.contains("레그") -> {
                groups.add(MuscleGroup.LEGS)
                groups.add(MuscleGroup.GLUTES)
            }
            lowerName.contains("숄더") || lowerName.contains("레이즈") ||
            lowerName.contains("프레스") && targetMuscle?.contains("어깨") == true -> {
                groups.add(MuscleGroup.SHOULDERS)
            }
            lowerName.contains("컬") || lowerName.contains("익스텐션") -> {
                groups.add(MuscleGroup.BICEPS)
                groups.add(MuscleGroup.TRICEPS)
            }
            lowerName.contains("플랭크") || lowerName.contains("크런치") -> {
                groups.add(MuscleGroup.CORE)
                groups.add(MuscleGroup.ABS)
            }
        }

        // 타겟 근육 기반 추가
        if (groups.isEmpty() && targetMuscle != null) {
            when (targetMuscle.lowercase()) {
                "가슴", "chest" -> groups.add(MuscleGroup.CHEST)
                "등", "back" -> groups.add(MuscleGroup.BACK)
                "하체", "다리", "legs" -> groups.add(MuscleGroup.LEGS)
                "어깨", "shoulders" -> groups.add(MuscleGroup.SHOULDERS)
                "팔", "arms" -> {
                    groups.add(MuscleGroup.BICEPS)
                    groups.add(MuscleGroup.TRICEPS)
                }
                "코어", "복근", "abs" -> {
                    groups.add(MuscleGroup.CORE)
                    groups.add(MuscleGroup.ABS)
                }
            }
        }

        return groups.distinct()
    }

    /**
     * 운동명에서 카테고리 추출
     */
    private fun extractCategoryFromName(exerciseName: String, targetMuscle: String?): ExerciseCategory? {
        val lowerName = exerciseName.lowercase()

        return when {
            lowerName.contains("푸시") || lowerName.contains("푸쉬") ||
            lowerName.contains("벤치") || lowerName.contains("플라이") -> ExerciseCategory.CHEST

            lowerName.contains("풀") || lowerName.contains("로우") ||
            lowerName.contains("데드") && !lowerName.contains("레그") -> ExerciseCategory.BACK

            lowerName.contains("스쿼트") || lowerName.contains("런지") ||
            lowerName.contains("레그") -> ExerciseCategory.LEGS

            lowerName.contains("숄더") || lowerName.contains("레이즈") -> ExerciseCategory.SHOULDERS

            lowerName.contains("컬") || lowerName.contains("익스텐션") -> ExerciseCategory.ARMS

            lowerName.contains("플랭크") || lowerName.contains("크런치") -> ExerciseCategory.CORE

            else -> {
                // 타겟 근육 기반 카테고리
                when (targetMuscle?.lowercase()) {
                    "가슴", "chest" -> ExerciseCategory.CHEST
                    "등", "back" -> ExerciseCategory.BACK
                    "하체", "다리", "legs" -> ExerciseCategory.LEGS
                    "어깨", "shoulders" -> ExerciseCategory.SHOULDERS
                    "팔", "arms" -> ExerciseCategory.ARMS
                    "코어", "복근", "abs" -> ExerciseCategory.CORE
                    else -> null
                }
            }
        }
    }
}
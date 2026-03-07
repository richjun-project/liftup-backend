package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.util.ExerciseNameNormalizer
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.workout.service.AutoProgramSelector
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogLocalizationService
import com.richjun.liftupai.domain.workout.service.RecommendationExerciseRanking
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
    private val exerciseNameNormalizer: ExerciseNameNormalizer,
    private val vectorWorkoutRecommendationService: com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService,
    private val workoutExerciseRepository: com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository,
    private val exerciseSetRepository: com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository,
    private val muscleRecoveryRepository: com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository,
    private val autoProgramSelector: AutoProgramSelector,
    private val exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService
) {

    private data class RecommendationProgramContext(
        val programDays: Int,
        val programType: String,
        val workoutSequence: List<WorkoutType>
    )

    fun analyzeForm(userId: Long, request: FormAnalysisRequest): FormAnalysisResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val exercise = exerciseRepository.findById(request.exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }

        // Gemini AI를 사용한 자세 분석
        val analysisPrompt = buildFormAnalysisPrompt(exercise.name, request.videoUrl ?: request.imageUrl)
        val aiResponse = geminiAIService.analyzeContent(analysisPrompt)

        // AI 응답 파싱 (실제로는 더 정교한 파싱 로직 필요)
        return parseFormAnalysisResponse(aiResponse)
    }

    fun getRecommendations(userId: Long, type: String, muscleGroups: List<String>): RecommendationsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

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
            Exercise: $exerciseName
            Media URL: $mediaUrl

            Analyze this exercise form and provide:
            1. A form accuracy score (0-100)
            2. Areas that need improvement
            3. Specific corrections
            4. An overall summary
        """.trimIndent()
    }

    private fun buildRecommendationPrompt(type: String, muscleGroups: List<String>, profile: Any?): String {
        val profileInfo = if (profile is UserProfile) {
            """
            Experience level: ${profile.experienceLevel}
            Goals: ${profile.goals.joinToString()}
            Weekly workout days: ${profile.weeklyWorkoutDays}
            Preferred workout time: ${profile.preferredWorkoutTime}
            Workout split: ${profile.workoutSplit}
            Workout duration: ${profile.workoutDuration} min
            """.trimIndent()
        } else {
            "No profile information available"
        }

        return """
            Recommendation type: $type
            Muscle groups: ${muscleGroups.joinToString(", ")}
            User profile:
            $profileInfo

            Recommend:
            1. A workout program
            2. Nutrition guidance
            3. Recovery guidance
        """.trimIndent()
    }

    private fun parseFormAnalysisResponse(aiResponse: String): FormAnalysisResponse {
        // 실제로는 AI 응답을 정교하게 파싱
        // 여기서는 간단한 예시
        return FormAnalysisResponse(
            analysis = "Overall form looks solid.",
            score = 85,
            improvements = listOf(
                "Bend the knees slightly more",
                "Keep the spine neutral"
            ),
            corrections = listOf(
                "Set the feet to shoulder width",
                "Keep the gaze forward"
            )
        )
    }

    private fun parseRecommendationsResponse(aiResponse: String, type: String): RecommendationsResponse {
        // 실제로는 AI 응답을 정교하게 파싱
        return RecommendationsResponse(
            workouts = listOf(
                WorkoutRecommendation(
                    exerciseId = 1,
                    name = "Bench Press",
                    sets = 4,
                    reps = "8-10",
                    reason = "Effective for chest strength and muscle growth",
                    difficulty = "Intermediate"
                )
            ),
            nutrition = listOf(
                NutritionRecommendation(
                    food = "Chicken Breast",
                    calories = 165,
                    macros = Macros(31.0, 0.0, 3.6),
                    timing = "Within 30 minutes after training",
                    reason = "Provides protein to support muscle recovery"
                )
            ),
            recovery = listOf(
                RecoveryRecommendation(
                    activity = "Stretching",
                    duration = 15,
                    intensity = "Low",
                    benefits = listOf("Improved mobility", "Reduced muscle tightness")
                )
            )
        )
    }

    fun chat(userId: Long, request: ChatRequest): ChatResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, null)

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
                aiResponse = WorkoutLocalization.message("ai.chat.error", locale),
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
        val suggestions = extractSuggestions(aiReply, locale)

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
            contextInfo.append("Current workout type: $it\n")
        }
        context.currentExercise?.let {
            contextInfo.append("Current exercise: $it\n")
        }
        context.userGoal?.let {
            contextInfo.append("User goal: $it\n")
        }

        return if (contextInfo.isNotEmpty()) {
            """
            [Context]
            $contextInfo

            [User Question]
            $message
            """.trimIndent()
        } else {
            message
        }
    }

    private fun extractSuggestions(aiReply: String, locale: String): List<String> {
        val suggestions = mutableListOf<String>()

        // AI 응답에서 운동 관련 키워드 추출
        when {
            containsAny(aiReply, *WorkoutLocalization.keywordAliases("chat.keyword.chest").toTypedArray()) ->
                suggestions.add(WorkoutLocalization.message("chat.suggestion.view_chest", locale))
            containsAny(aiReply, *WorkoutLocalization.keywordAliases("chat.keyword.back").toTypedArray()) ->
                suggestions.add(WorkoutLocalization.message("chat.suggestion.view_back", locale))
            containsAny(aiReply, *WorkoutLocalization.keywordAliases("chat.keyword.legs").toTypedArray()) ->
                suggestions.add(WorkoutLocalization.message("chat.suggestion.view_legs", locale))
            containsAny(aiReply, *WorkoutLocalization.keywordAliases("chat.keyword.nutrition").toTypedArray()) ->
                suggestions.add(WorkoutLocalization.message("chat.suggestion.nutrition", locale))
            containsAny(aiReply, *WorkoutLocalization.keywordAliases("chat.keyword.program").toTypedArray()) ->
                suggestions.add(WorkoutLocalization.message("chat.suggestion.program", locale))
        }

        // 최대 3개까지만 제안
        return suggestions.take(3)
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    fun getAIWorkoutRecommendation(
        userId: Long,
        duration: Int? = null,
        equipment: String? = null,
        targetMuscle: String? = null,
        difficulty: String? = null,
        localeOverride: String? = null
    ): AIWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val locale = resolveLocale(userId, localeOverride)

        // Check if user has started a workout today
        val hasStartedToday = workoutProgressTracker.hasStartedWorkoutToday(user)

        val programContext = resolveRecommendationProgramContext(user, profile, settings)
        val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programContext.programDays)
        val normalizedTargetMuscle = WorkoutTargetResolver.recommendationKey(targetMuscle)

        // Determine target muscle based on program position if not specified
        val adjustedTargetMuscle = if (!hasStartedToday && normalizedTargetMuscle == null) {
            val workoutType = programContext.workoutSequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY

            when (workoutType) {
                WorkoutType.PUSH, WorkoutType.CHEST -> "chest"
                WorkoutType.PULL, WorkoutType.BACK -> "back"
                WorkoutType.LEGS, WorkoutType.LOWER -> "legs"
                WorkoutType.UPPER -> "upper"
                WorkoutType.ARMS -> "arms"
                WorkoutType.SHOULDERS -> "shoulders"
                WorkoutType.ABS -> "core"
                else -> "full_body"
            }
        } else {
            normalizedTargetMuscle
        }

        // 1단계: 벡터 검색으로 후보 운동 가져오기 (DB에서만 선택)
        val candidateExercises = vectorWorkoutRecommendationService.recommendExercises(
            user = user,
            profile = profile,
            duration = duration,
            targetMuscle = adjustedTargetMuscle,
            equipment = equipment,
            difficulty = difficulty,
            workoutType = null,
            limit = 20 // 충분한 후보 확보
        )

        println("Loaded ${candidateExercises.size} candidate exercises from vector search")

        // 2단계: 구조화된 프롬프트 생성 with 후보 운동 목록
        val prompt = buildStructuredWorkoutPrompt(
            user,
            profile,
            settings,
            duration,
            equipment,
            adjustedTargetMuscle,
            difficulty,
            programPosition,
            hasStartedToday,
            programContext.programType,
            locale,
            candidateExercises // 후보 운동 목록 전달
        )

        // 3단계: AI가 후보 중에서 선택
        val aiResponse = geminiAIService.generateRecommendations(prompt)

        // AI 응답을 WorkoutRecommendationDetail로 파싱
        val recommendation = parseAIWorkoutResponse(user, aiResponse, duration, equipment, adjustedTargetMuscle, locale)

        // 대체 운동 생성
        val alternatives = generateAIAlternatives(duration, equipment, adjustedTargetMuscle, locale)

        // AI 인사이트 생성 (AI 응답에서 파싱한 데이터 사용)
        val insights = parseAIInsights(aiResponse, locale)

        return AIWorkoutRecommendationResponse(
            recommendation = recommendation,
            alternatives = alternatives,
            aiInsights = insights
        )
    }

    private fun resolveRecommendationProgramContext(
        user: com.richjun.liftupai.domain.auth.entity.User,
        profile: UserProfile?,
        settings: UserSettings?
    ): RecommendationProgramContext {
        val configuredProgramType = settings?.workoutSplit
            ?: profile?.workoutSplit
            ?: "PPL"
        val programDays = settings?.weeklyWorkoutDays
            ?: profile?.weeklyWorkoutDays
            ?: 3
        val autoRecommendation = if (configuredProgramType.equals("AUTO", ignoreCase = true)) {
            autoProgramSelector.selectProgram(user)
        } else {
            null
        }

        return RecommendationProgramContext(
            programDays = programDays,
            programType = autoRecommendation?.programType ?: configuredProgramType,
            workoutSequence = autoRecommendation?.workoutSequence
                ?: workoutProgressTracker.getWorkoutTypeSequence(configuredProgramType)
        )
    }

    private fun buildStructuredWorkoutPrompt(
        user: com.richjun.liftupai.domain.auth.entity.User,
        profile: UserProfile?,
        settings: UserSettings?,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?,
        programPosition: WorkoutProgramPosition? = null,
        hasStartedToday: Boolean = false,
        resolvedProgramType: String? = null,
        locale: String,
        candidateExercises: List<Exercise> = emptyList()
    ): String {
        val workoutDuration = duration ?: 30
        val targetDifficulty = WorkoutLocalization.difficultyKey(difficulty)
        val responseLanguage = responseLanguage(locale)

        // PT 스타일 정보 추가
        val ptStyle = profile?.ptStyle ?: com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER
        val weeklyWorkoutDays = settings?.weeklyWorkoutDays ?: profile?.weeklyWorkoutDays
        val preferredWorkoutTime = settings?.preferredWorkoutTime ?: profile?.preferredWorkoutTime
        val workoutSplit = resolvedProgramType ?: settings?.workoutSplit ?: profile?.workoutSplit
        val workoutDurationPreference = settings?.workoutDuration ?: profile?.workoutDuration

        val profileInfo = """
            User profile:
            - Name: ${user.nickname}
            - PT style persona: $ptStyle
            - Experience level: ${profile?.experienceLevel ?: ExperienceLevel.BEGINNER}
            - Goals: ${profile?.goals?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "general fitness"}
            - Weekly workout days: ${weeklyWorkoutDays ?: "not set"}
            - Preferred workout time: ${preferredWorkoutTime ?: "not set"}
            - Workout split: ${workoutSplit ?: "not set"}
            - Preferred session duration: ${workoutDurationPreference?.let { "$it min" } ?: "not set"}
        """.trimIndent()

        // 최근 운동 이력 분석 추가
        val recentWorkouts = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        val workoutHistoryInfo = if (recentWorkouts.isNotEmpty()) {
            val muscleFrequency = analyzeMuscleFrequency(recentWorkouts)
            val daysSinceLastWorkout = if (recentWorkouts.isNotEmpty()) {
                java.time.Duration.between(recentWorkouts.first().startTime, LocalDateTime.now()).toDays()
            } else 7

            """
            Recent workout pattern:
            - Last workout: $daysSinceLastWorkout day(s) ago
            - Sessions this week: ${recentWorkouts.size}
            - Most trained areas: ${muscleFrequency.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} (${it.value})" }}
            - Undertrained areas: ${identifyUndertrainedMuscles(muscleFrequency)}
            """.trimIndent()
        } else {
            """
            Recent workout pattern:
            - No recent workout history
            """.trimIndent()
        }

        val equipmentText = equipment?.let { WorkoutLocalization.equipmentName(it, "en") } ?: "Any equipment"
        val muscleText = targetMuscle?.let { WorkoutLocalization.targetDisplayName(it, "en") } ?: "Full Body"

        // 후보 운동 목록 생성
        val candidateExercisesText = if (candidateExercises.isNotEmpty()) {
            """

            Candidate exercise pool:
            Only select exercises from this list. Every `id` below exists in the database.
            ${candidateExercises.mapIndexed { index, ex ->
                val coreLabel = if (ex.isBasicExercise || RecommendationExerciseRanking.isCoreCandidate(ex)) " CORE" else ""
                "  ${index + 1}. [ID: ${ex.id}] ${ex.name}$coreLabel | slug=${ex.slug} | category=${ex.category.name} | pattern=${ex.movementPattern ?: "OTHER"} | muscles=${ex.muscleGroups.joinToString(", ")} | equipment=${ex.equipment?.name ?: "NONE"}"
            }.joinToString("\n")}

            """.trimIndent()
        } else {
            ""
        }

        val styleGuidance = buildPtStyleGuidance(ptStyle)

        // 최근 운동 성과 정보 추가
        val recentAchievements = if (recentWorkouts.isNotEmpty()) {
            """
            Recent performance:
            - Completed sessions this week: ${recentWorkouts.size}
            - Last workout type: ${recentWorkouts.first().workoutType}
            - Current streak: ${calculateStreak(recentWorkouts)} day(s)
            """.trimIndent()
        } else {
            "Recent performance:\n- This will be the user's first tracked workout."
        }

        // 헬스 트레이너 관점 분석 추가
        val weeklyVolume = getWeeklyVolumeMap(user)
        val volumeAnalysis = """

            Weekly volume status (sets per muscle group):
            ${weeklyVolume.entries.sortedByDescending { it.value }.joinToString("\n") { (muscle, sets) ->
                val status = when {
                    sets < 10 -> "under target (recommended: 10-20 sets)"
                    sets > 20 -> "too high (fatigue risk)"
                    else -> "on target"
                }
                "  - $muscle: $sets sets ($status)"
            }}
        """.trimIndent()

        val recentlyWorkedMuscles = getRecentlyWorkedMuscles(user, 48)
        val recoveringMuscles = getRecoveringMuscles(user)
        val recoveryAnalysis = if (recentlyWorkedMuscles.isNotEmpty() || recoveringMuscles.isNotEmpty()) {
            """

            Recovery status:
            ${if (recentlyWorkedMuscles.isNotEmpty()) {
                "  - Worked in the last 48 hours: ${recentlyWorkedMuscles.joinToString(", ") { WorkoutTargetResolver.displayName(it, "en") }} (avoid if possible)"
            } else "  - No recently trained muscle groups"}
            ${if (recoveringMuscles.isNotEmpty()) {
                "  - Still recovering (<80%): ${recoveringMuscles.joinToString(", ") { WorkoutTargetResolver.displayName(it, "en") }} (avoid)"
            } else ""}
            """.trimIndent()
        } else {
            """

            Recovery status:
              - All tracked muscle groups are recovered
            """.trimIndent()
        }

        val balanceWarnings = checkMuscleBalance(user)
        val balanceAnalysis = if (balanceWarnings.isNotEmpty()) {
            """

            Muscle balance warnings:
            ${balanceWarnings.joinToString("\n") { "  $it" }}
            """.trimIndent()
        } else {
            ""
        }

        val plateaus = getAllPlateaus(user)
        val plateauAnalysis = if (plateaus.isNotEmpty()) {
            """

            Plateau watch (no meaningful load change for 3+ weeks):
            ${plateaus.joinToString("\n") { plateau ->
                "  - ${plateau.exercise.name}: plateau for ${plateau.weeks} week(s) at ${plateau.currentWeight}kg\n    -> ${plateau.recommendation}"
            }}
            """.trimIndent()
        } else {
            ""
        }

        return """
            Build a personalized workout program and respond with JSON only.

            $profileInfo
            $workoutHistoryInfo
            $recentAchievements
            $volumeAnalysis
            $recoveryAnalysis
            $balanceAnalysis
            $plateauAnalysis

            $styleGuidance

            Training request:
            - Duration: $workoutDuration minutes
            - Equipment preference: $equipmentText
            - Target focus: $muscleText
            - Difficulty code: $targetDifficulty
            - Response language: $responseLanguage

            $candidateExercisesText

            Hard rules:
            1. Avoid muscle groups trained in the last 48 hours or still marked as recovering below 80%.
            2. Avoid muscle groups already above 20 weekly sets when possible.
            3. Prefer muscle groups at or below 10 weekly sets.
            4. If there is an imbalance warning, bias the plan toward the undertrained side.
            5. If an exercise is plateaued, prefer a variation before repeating the exact same pattern.
            6. Order exercises from large muscle groups to small, and compound to isolation.
            7. At least 70% of the selected exercises should be CORE exercises when available.
            8. All exercise IDs must come from the provided candidate list.

            Respond with this JSON schema only:
            {
              "workout_name": "Human-readable workout name in $responseLanguage",
              "target_muscles": ["Human-readable target muscles in $responseLanguage"],
              "equipment": ["Human-readable equipment labels in $responseLanguage"],
              "exercises": [
                {
                  "id": 123,
                  "sets": 3,
                  "reps": "8-12",
                  "rest_seconds": 90,
                  "order": 1
                }
              ],
              "estimated_calories": 250,
              "difficulty": "beginner|intermediate|advanced",
              "tips": ["2-3 actionable tips in $responseLanguage and in the $ptStyle persona"],
              "progression_note": "Specific next-step progression guidance in $responseLanguage",
              "coaching_message": "Personalized coaching message in $responseLanguage explaining why this plan fits the user",
              "workout_focus": "One concise focus statement in $responseLanguage",
              "ai_insights": {
                "workout_rationale": "Required. Why this exercise selection is correct for the user, in $responseLanguage",
                "key_point": "Required. The single most important focus point today, in $responseLanguage",
                "next_step": "Required. A concrete next-step progression target with rationale, in $responseLanguage"
              }
            }

            Additional constraints:
            1. Every exercise id in `exercises` must come from the provided list.
            2. Do not output any exercise ID that is not in the candidate pool.
            3. Keep the plan aligned with the requested duration:
               - 20 minutes: 3-4 exercises
               - 30 minutes: 5-6 exercises
               - 45 minutes: 6-7 exercises
               - 60 minutes: 7-8 exercises
            4. Do not duplicate exercise IDs.
            5. Keep all human-readable fields in $responseLanguage.
            6. `difficulty` must be one of `beginner`, `intermediate`, `advanced`.
            7. Do not include the user's name or day number in `workout_name`.
            8. Make the plan genuinely personalized to the user's current state and recent training pattern.
            9. Fill all three `ai_insights` fields.
            10. Output JSON only, with no markdown fences and no extra text.
        """.trimIndent()
    }

    private fun buildPtStyleGuidance(
        ptStyle: com.richjun.liftupai.domain.user.entity.PTStyle
    ): String {
        return when (ptStyle) {
            com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN -> """
                Coaching persona:
                - Intense, demanding, high-accountability tone.
                - Push the user with short, forceful motivation.
                - Favor hard but realistic workloads.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.BURNOUT -> """
                Coaching persona:
                - Sarcastic and blunt, but still useful.
                - Challenge excuses directly.
                - Mix realism with dry humor.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.GAME_MASTER -> """
                Coaching persona:
                - Frame the workout like an RPG quest.
                - Use level-up, quest, boss-fight, and stat-growth language.
                - Keep it playful and structured.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.INFLUENCER -> """
                Coaching persona:
                - Trendy, aesthetic, polished, and energetic.
                - Use body-line, posture, and studio-style wording.
                - Make the advice feel social-media ready.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.HIP_HOP -> """
                Coaching persona:
                - Rhythmic, swagger-heavy, performance-driven.
                - Use confident lines and momentum-driven language.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.RETIRED_TEACHER -> """
                Coaching persona:
                - Old-school PE teacher energy.
                - Slightly stern, story-driven, and disciplined.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.OFFICE_MASTER -> """
                Coaching persona:
                - Understands office-life fatigue and social eating.
                - Practical, empathetic, and realistic for busy schedules.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.LA_KOREAN -> """
                Coaching persona:
                - High-energy Korean-American trainer vibe.
                - Casual, mixed-culture gym voice with confident delivery.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.BUSAN_VETERAN -> """
                Coaching persona:
                - Rough veteran-athlete tone.
                - Direct, no-nonsense, but performance-focused.
            """.trimIndent()

            com.richjun.liftupai.domain.user.entity.PTStyle.SOLDIER -> """
                Coaching persona:
                - Earnest, disciplined, slightly awkward junior soldier energy.
                - Motivated, structured, and very sincere.
            """.trimIndent()

            else -> """
                Coaching persona:
                - Professional, clear, and supportive.
                - Prioritize precision, safety, and practical coaching.
            """.trimIndent()
        }
    }

    private fun parseAIWorkoutResponse(
        user: com.richjun.liftupai.domain.auth.entity.User,
        aiResponse: String,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): AIWorkoutDetail {
        return try {
            val cleanedResponse = aiResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonResponse = objectMapper.readValue<Map<String, Any>>(cleanedResponse)
            val exercisesList = jsonResponse["exercises"] as? List<Map<String, Any>> ?: emptyList()
            val estimatedCalories = (jsonResponse["estimated_calories"] as? Number)?.toInt() ?: calculateCalories(duration ?: 30)
            val difficulty = localizeDifficulty(jsonResponse["difficulty"] as? String, locale)

            val tips = (jsonResponse["tips"] as? List<String>) ?: emptyList()
            val progressionNote = jsonResponse["progression_note"] as? String
            val coachingMessage = jsonResponse["coaching_message"] as? String
            val workoutFocus = jsonResponse["workout_focus"] as? String

            val exerciseIds = exercisesList.mapNotNull { exerciseMap ->
                extractExerciseId(exerciseMap["id"])
            }.distinct()
            val exerciseById = exerciseIds.associateWith { id ->
                exerciseRepository.findById(id).orElse(null)
            }.filterValues { it != null }.mapValues { it.value!! }
            val translations = translationMap(exerciseById.values, locale)
            val usedExerciseIds = mutableSetOf<Long>()

            val exercises = exercisesList.mapIndexedNotNull { index, exerciseMap ->
                val exerciseId = extractExerciseId(exerciseMap["id"])

                if (exerciseId == null) {
                    println("Skipping exercise #${index + 1}: invalid or missing id (${exerciseMap["id"]})")
                    return@mapIndexedNotNull null
                }

                val matchedExercise = exerciseById[exerciseId]
                if (matchedExercise == null) {
                    println("Skipping exercise id $exerciseId: not found in DB")
                    return@mapIndexedNotNull null
                }

                println("Matched exercise #${index + 1}: ${matchedExercise.name} (id: $exerciseId)")

                if (exerciseId in usedExerciseIds) {
                    println("Skipping duplicate exercise id $exerciseId (${matchedExercise.name})")
                    return@mapIndexedNotNull null
                }

                usedExerciseIds.add(exerciseId)

                val suggestedWeight = try {
                    workoutServiceV2.calculateSuggestedWeight(user, matchedExercise)
                } catch (e: Exception) {
                    calculateWeightByExerciseName(user, matchedExercise.name)
                }

                AIExerciseDetail(
                    exerciseId = matchedExercise.id.toString(),
                    name = localizedExerciseName(matchedExercise, locale, translations),
                    sets = (exerciseMap["sets"] as? Number)?.toInt() ?: 3,
                    reps = exerciseMap["reps"] as? String ?: "10-12",
                    rest = (exerciseMap["rest_seconds"] as? Number)?.toInt() ?: 60,
                    order = (exerciseMap["order"] as? Number)?.toInt() ?: (index + 1),
                    suggestedWeight = suggestedWeight,
                    targetMuscles = getExerciseTargetMuscles(matchedExercise, locale),
                    equipmentNeeded = localizeEquipment(matchedExercise.equipment?.name, locale).ifBlank { null },
                    difficultyLevel = difficulty
                )
            }.sortedBy { it.order }

            val workoutDuration = duration ?: 30
            val minExercises = when {
                workoutDuration <= 20 -> 3
                workoutDuration <= 30 -> 5
                workoutDuration <= 45 -> 6
                else -> 7
            }

            if (exercises.size < minExercises) {
                println("AI returned too few exercises (${exercises.size} < $minExercises); using fallback")
                throw IllegalStateException("AI returned insufficient exercises: ${exercises.size} < $minExercises")
            }

            println("AI workout recommendation completed with ${exercises.size} exercises")

            val workoutName = (jsonResponse["workout_name"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: generateWorkoutName(duration, targetMuscle, locale)
            val musclesList = ((jsonResponse["target_muscles"] as? List<String>) ?: emptyList())
                .map { localizeTargetLabel(it, locale) }
                .filter { it.isNotBlank() }
                .distinct()
                .ifEmpty {
                    exercises.flatMap { it.targetMuscles }.distinct().ifEmpty {
                        getTargetMuscles(targetMuscle, locale)
                    }
                }
            val equipmentList = ((jsonResponse["equipment"] as? List<String>) ?: emptyList())
                .map { localizeEquipment(it, locale) }
                .filter { it.isNotBlank() }
                .distinct()
                .ifEmpty {
                    exercises.mapNotNull { it.equipmentNeeded }.distinct()
                }

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
            println("Failed to parse AI response: ${e.message}")
            generateFallbackRecommendation(duration, equipment, targetMuscle, locale)
        }
    }

    private fun findMatchingExerciseByName(exerciseName: String): Exercise? {
        // 1. ExerciseNameNormalizer를 사용한 정규화
        val normalizedName = exerciseNameNormalizer.normalize(exerciseName)

        // 2. 정확한 이름 매칭 (정규화된 이름으로)
        exerciseRepository.findByNameIgnoreCase(normalizedName)?.let {
            println("Exercise match succeeded (exact): AI='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 3. Repository의 정규화 쿼리 사용
        exerciseRepository.findByNormalizedName(normalizedName).firstOrNull()?.let {
            println("Exercise match succeeded (normalized query): AI='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 4. 변형 생성 및 검색
        val variations = exerciseNameNormalizer.generateVariations(exerciseName)
        exerciseRepository.findByNameIn(variations.map { variation -> variation.lowercase() }).firstOrNull()?.let {
            println("Exercise match succeeded (variation): AI='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 5. 정확한/압축 이름 매칭
        exerciseRepository.findByExactOrCompactName(normalizedName).firstOrNull()?.let {
            println("Exercise match succeeded (exact/compact): AI='$exerciseName' -> DB='${it.name}'")
            return it
        }

        // 6. 부분 매칭 시도 (폴백)
        val exercises = exerciseRepository.findAll()

        // 양방향 부분 매칭
        exercises.find { exercise ->
            normalizedName.contains(exerciseNameNormalizer.normalize(exercise.name), ignoreCase = true) ||
            exerciseNameNormalizer.normalize(exercise.name).contains(normalizedName, ignoreCase = true)
        }?.let { foundExercise ->
            println("Exercise match succeeded (partial): AI='$exerciseName' -> DB='${foundExercise.name}'")
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
                println("Exercise match succeeded (keyword): AI='$exerciseName' -> DB='${foundExercise.name}'")
                return foundExercise
            }
        }

        // 8. 최종 실패 로깅
        println("Exercise match failed: AI='$exerciseName' (normalized='$normalizedName') not found in DB")
        return null
    }

    private fun extractKeywords(exerciseName: String): List<String> {
        return exerciseNameNormalizer.extractCoreKeywords(exerciseName)
    }

    private fun calculateWeightByExerciseName(user: com.richjun.liftupai.domain.auth.entity.User, exerciseName: String): Double? {
        // 운동명만으로는 정확한 무게 계산이 어려우므로 null 반환
        // 실제 DB에 있는 운동을 찾아서 계산하는 것이 정확함
        return null
    }

    private fun extractExerciseId(idValue: Any?): Long? {
        return when (idValue) {
            is Number -> idValue.toLong()
            is String -> idValue.toLongOrNull()
            else -> null
        }
    }

    private fun getExerciseTargetMuscles(exercise: Exercise, locale: String): List<String> {
        val muscles = exercise.muscleGroups
            .map { muscleGroup -> WorkoutTargetResolver.displayName(muscleGroup, locale) }
            .distinct()

        if (muscles.isNotEmpty()) {
            return muscles
        }

        val fallbackFocus = WorkoutTargetResolver.focusForCategory(exercise.category) ?: WorkoutFocus.FULL_BODY
        return listOf(WorkoutTargetResolver.displayName(fallbackFocus, locale))
    }

    private fun localizeTargetLabel(rawTarget: String, locale: String): String {
        return WorkoutLocalization.targetDisplayName(rawTarget, locale)
    }

    private fun localizeDifficulty(rawDifficulty: String?, locale: String): String {
        return WorkoutLocalization.difficultyDisplayName(rawDifficulty, locale)
    }

    private fun generateFallbackRecommendation(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): AIWorkoutDetail {
        val workoutDuration = duration ?: 30
        val exercises = getDefaultExercises(equipment, targetMuscle, workoutDuration, locale)

        return AIWorkoutDetail(
            workoutId = "fallback_${System.currentTimeMillis()}",
            name = generateWorkoutName(duration, targetMuscle, locale),
            duration = workoutDuration,
            difficulty = localizeDifficulty(null, locale),
            exercises = exercises,
            estimatedCalories = calculateCalories(workoutDuration),
            targetMuscles = getTargetMuscles(targetMuscle, locale),
            equipment = listOfNotNull(localizeEquipment(equipment, locale).ifBlank { null })
            // AI가 생성하지 못한 경우 null로 처리
        )
    }

    private fun getDefaultExercises(
        equipment: String?,
        targetMuscle: String?,
        duration: Int,
        locale: String
    ): List<AIExerciseDetail> {
        val exerciseCount = when {
            duration <= 20 -> 3
            duration <= 30 -> 5
            duration <= 45 -> 6
            else -> 8
        }

        val allExercises = fallbackCandidates(targetMuscle, equipment)
            .ifEmpty {
                exerciseRepository.findAll()
                    .sortedWith(RecommendationExerciseRanking.displayOrderComparator())
            }
        val selectedExercises = mutableListOf<Exercise>()

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

        val translations = translationMap(selectedExercises, locale)
        return selectedExercises.take(exerciseCount).mapIndexed { index, exercise ->
            AIExerciseDetail(
                exerciseId = exercise.id.toString(),
                name = localizedExerciseName(exercise, locale, translations),
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
                order = index + 1,
                targetMuscles = getExerciseTargetMuscles(exercise, locale),
                equipmentNeeded = localizeEquipment(exercise.equipment?.name, locale).ifBlank { null },
                difficultyLevel = localizeDifficulty(null, locale)
                // AI가 생성하지 못한 경우 null로 처리
            )
        }
    }

    private fun generateWorkoutName(duration: Int?, targetMuscle: String?, locale: String): String {
        val focus = WorkoutTargetResolver.resolveFocus(targetMuscle) ?: WorkoutFocus.FULL_BODY
        val muscleText = WorkoutTargetResolver.displayName(focus, locale)
        val durationText = WorkoutLocalization.durationLabel(duration ?: 30, locale)
        return WorkoutLocalization.message("ai.workout.name", locale, durationText, muscleText)
    }

    private fun calculateCalories(duration: Int): Int {
        return duration * 7 // 분당 약 7칼로리
    }

    private fun getTargetMuscles(targetMuscle: String?, locale: String): List<String> {
        return when (WorkoutTargetResolver.resolveFocus(targetMuscle)) {
            WorkoutFocus.CHEST -> listOf("chest", "triceps", "shoulders")
            WorkoutFocus.BACK, WorkoutFocus.PULL -> listOf("back", "biceps", "rear_delts")
            WorkoutFocus.LEGS, WorkoutFocus.LOWER -> listOf("quadriceps", "hamstrings", "glutes", "calves")
            WorkoutFocus.SHOULDERS -> listOf("shoulders", "traps")
            WorkoutFocus.ARMS -> listOf("biceps", "triceps", "forearms")
            WorkoutFocus.CORE -> listOf("abs", "obliques", "lower_back")
            WorkoutFocus.UPPER -> listOf("chest", "back", "shoulders", "biceps", "triceps")
            else -> listOf("full_body")
        }.map { target -> localizeTargetLabel(target, locale) }.distinct()
    }

    private fun generateAIAlternatives(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        locale: String
    ): List<AIAlternativeWorkout> {
        val alternatives = mutableListOf<AIAlternativeWorkout>()
        val baseDuration = duration ?: 30

        if (baseDuration > 20) {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_short_${System.currentTimeMillis()}",
                name = WorkoutLocalization.message("ai.alt.short.name", locale, baseDuration - 10),
                duration = baseDuration - 10,
                reason = WorkoutLocalization.message("ai.alt.short.reason", locale)
            ))
        }

        if (baseDuration < 45) {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_long_${System.currentTimeMillis()}",
                name = WorkoutLocalization.message("ai.alt.long.name", locale, baseDuration + 15),
                duration = baseDuration + 15,
                reason = WorkoutLocalization.message("ai.alt.long.reason", locale)
            ))
        }

        if (targetMuscle != "full_body") {
            alternatives.add(AIAlternativeWorkout(
                workoutId = "ai_alt_fullbody_${System.currentTimeMillis()}",
                name = WorkoutLocalization.message("ai.alt.full_body.name", locale, baseDuration),
                duration = baseDuration,
                reason = WorkoutLocalization.message("ai.alt.full_body.reason", locale),
                targetFocus = WorkoutTargetResolver.displayName(WorkoutFocus.FULL_BODY, locale)
            ))
        }

        return alternatives.take(2)
    }

    /**
     * 기본 인사이트 생성 (AI가 제공하지 않은 경우)
     */
    private fun generateDefaultInsights(jsonResponse: Map<String, Any>, locale: String): AIWorkoutInsights {
        val coachingMessage = jsonResponse["coaching_message"] as? String
        val workoutFocus = jsonResponse["workout_focus"] as? String
        val progressionNote = jsonResponse["progression_note"] as? String
        val tips = (jsonResponse["tips"] as? List<String>)?.firstOrNull()

        return AIWorkoutInsights(
            workoutRationale = coachingMessage ?: WorkoutLocalization.message("ai.default_insight.rationale", locale),
            keyPoint = workoutFocus ?: tips ?: WorkoutLocalization.message("ai.default_insight.key_point", locale),
            nextStep = progressionNote ?: WorkoutLocalization.message("ai.default_insight.next", locale)
        )
    }

    /**
     * AI 응답에서 인사이트 파싱
     */
    private fun parseAIInsights(aiResponse: String, locale: String): AIWorkoutInsights? {
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
            return generateDefaultInsights(jsonResponse, locale)
        } catch (e: Exception) {
            println("ERROR parsing AI insights: ${e.message}")
            // AI 응답 파싱 실패 시 null 반환
            null
        }
    }

    private fun resolveLocale(userId: Long, localeOverride: String?): String {
        if (!localeOverride.isNullOrBlank()) {
            return exerciseCatalogLocalizationService.normalizeLocale(localeOverride)
        }

        return exerciseCatalogLocalizationService.normalizeLocale(
            userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        )
    }

    private fun translationMap(exercises: Collection<Exercise>, locale: String): Map<Long, ExerciseTranslation> {
        return exerciseCatalogLocalizationService.translationMap(exercises, locale)
    }

    private fun localizedExerciseName(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String {
        return exerciseCatalogLocalizationService.displayName(exercise, locale, translations)
    }

    private fun localizeEquipment(rawEquipment: String?, locale: String): String {
        return WorkoutLocalization.equipmentName(rawEquipment, locale)
    }

    private fun responseLanguage(locale: String): String {
        return when (locale.substringBefore('-')) {
            "ko" -> "Korean"
            "ja" -> "Japanese"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "zh" -> "Chinese"
            else -> "English"
        }
    }

    private fun isKorean(locale: String): Boolean = locale.substringBefore('-') == "ko"

    // 근육 부위별 운동 빈도 분석
    private fun analyzeMuscleFrequency(workouts: List<com.richjun.liftupai.domain.workout.entity.WorkoutSession>): Map<String, Int> {
        val frequency = mutableMapOf<String, Int>()
        workouts.forEach { session ->
            when (session.workoutType) {
                WorkoutType.PUSH -> {
                    frequency["Chest"] = frequency.getOrDefault("Chest", 0) + 1
                    frequency["Shoulders"] = frequency.getOrDefault("Shoulders", 0) + 1
                    frequency["Triceps"] = frequency.getOrDefault("Triceps", 0) + 1
                }
                WorkoutType.PULL -> {
                    frequency["Back"] = frequency.getOrDefault("Back", 0) + 1
                    frequency["Biceps"] = frequency.getOrDefault("Biceps", 0) + 1
                }
                WorkoutType.LEGS -> {
                    frequency["Legs"] = frequency.getOrDefault("Legs", 0) + 1
                }
                WorkoutType.UPPER -> {
                    frequency["Upper Body"] = frequency.getOrDefault("Upper Body", 0) + 1
                }
                WorkoutType.LOWER -> {
                    frequency["Lower Body"] = frequency.getOrDefault("Lower Body", 0) + 1
                }
                WorkoutType.CHEST -> {
                    frequency["Chest"] = frequency.getOrDefault("Chest", 0) + 1
                }
                WorkoutType.BACK -> {
                    frequency["Back"] = frequency.getOrDefault("Back", 0) + 1
                }
                WorkoutType.SHOULDERS -> {
                    frequency["Shoulders"] = frequency.getOrDefault("Shoulders", 0) + 1
                }
                WorkoutType.ARMS -> {
                    frequency["Arms"] = frequency.getOrDefault("Arms", 0) + 1
                }
                else -> {
                    frequency["Full Body"] = frequency.getOrDefault("Full Body", 0) + 1
                }
            }
        }
        return frequency
    }

    // 부족한 근육 부위 파악
    private fun identifyUndertrainedMuscles(frequency: Map<String, Int>): String {
        val mainMuscles = listOf("Chest", "Back", "Legs", "Shoulders")
        val underTrained = mainMuscles.filter {
            frequency.getOrDefault(it, 0) < 1
        }

        return if (underTrained.isNotEmpty()) {
            underTrained.joinToString(", ")
        } else {
            "Training is currently balanced"
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
     * 벡터 검색을 활용한 대체 운동 찾기
     * - 절대 null을 반환하지 않음 (보장됨)
     * - 벡터 검색 -> 기존 방식 폴백 -> 카테고리 기본 운동 순으로 시도
     */
    private fun findAlternativeExerciseByVector(
        exerciseName: String,
        targetMuscle: String? = null,
        equipment: String? = null
    ): Exercise {
        return try {
            // 운동명을 포함한 검색 쿼리 생성
            val queryText = buildString {
                append("exercise: $exerciseName")
                targetMuscle?.let { append(". target muscle: $it") }
                equipment?.let { append(". equipment: $it") }
            }

            // 벡터 임베딩 생성
            val exerciseVectorService = com.richjun.liftupai.domain.workout.service.vector.ExerciseVectorService(objectMapper)
            val embedding = exerciseVectorService.generateEmbedding(queryText)

            // Qdrant 서비스 가져오기 (이미 빈으로 등록됨)
            val qdrantService = com.richjun.liftupai.domain.workout.service.vector.ExerciseQdrantService(
                com.richjun.liftupai.global.config.QdrantConfig().qdrantClient()
            )

            // 1차 시도: 벡터 검색 (임계값 0.1로 낮춰서 더 많은 매칭 허용)
            var results = qdrantService.searchSimilarExercises(
                queryVector = embedding,
                limit = 5,
                scoreThreshold = 0.1f
            )

            // 2차 시도: 임계값을 더 낮춰서 재시도
            if (results.isEmpty()) {
                println("Initial vector lookup failed; retrying with threshold 0.05")
                results = qdrantService.searchSimilarExercises(
                    queryVector = embedding,
                    limit = 5,
                    scoreThreshold = 0.05f
                )
            }

            // 결과가 있으면 첫 번째 반환
            results.firstOrNull()?.let { (exerciseId, score) ->
                exerciseRepository.findById(exerciseId).orElse(null)?.also {
                    println("Vector lookup matched '$exerciseName' -> '${it.name}' (score: $score)")
                    return it
                }
            }

            // 3차 시도: 기존 방식으로 폴백
            println("Vector lookup failed; falling back to legacy matching")
            val fallbackExercise = findAlternativeExercise(exerciseName, targetMuscle, equipment)
            if (fallbackExercise != null) {
                println("Fallback matching found alternative: '$exerciseName' -> '${fallbackExercise.name}'")
                return fallbackExercise
            }

            // 4차 시도: 카테고리 기반 기본 운동 (최후의 보루)
            println("All matching methods failed; returning category-based default exercise")
            return getDefaultExerciseByCategory(targetMuscle, equipment)

        } catch (e: Exception) {
            println("Vector lookup error: ${e.message}")
            e.printStackTrace()

            // 오류 발생 시에도 기본 운동 반환
            try {
                return findAlternativeExercise(exerciseName, targetMuscle, equipment)
                    ?: getDefaultExerciseByCategory(targetMuscle, equipment)
            } catch (e2: Exception) {
                println("Fallback matching also failed: ${e2.message}")
                return getDefaultExerciseByCategory(targetMuscle, equipment)
            }
        }
    }

    /**
     * 카테고리 기반 기본 운동 반환 (최후의 보루)
     * 절대 null을 반환하지 않음
     */
    private fun getDefaultExerciseByCategory(targetMuscle: String?, equipment: String?): Exercise {
        val exercise = fallbackCandidates(targetMuscle, equipment).firstOrNull()
            ?: exerciseRepository.findAll()
                .sortedWith(RecommendationExerciseRanking.displayOrderComparator())
                .firstOrNull()

        if (exercise != null) {
            println("Returning default exercise for '$targetMuscle' -> '${exercise.name}' (id: ${exercise.id})")
            return exercise
        }

        // 정말 극단적인 경우: DB가 완전히 비어있으면 에러
        val errorMsg = "Critical error: the exercise catalog is empty."
        println(errorMsg)
        throw IllegalStateException(errorMsg)
    }

    /**
     * 대체 운동을 찾는 메서드 (기존 방식 - 폴백용)
     * 운동 매칭에 실패했을 때 유사한 운동을 찾아 추천
     */
    private fun findAlternativeExercise(
        exerciseName: String,
        targetMuscle: String? = null,
        equipment: String? = null
    ): Exercise? {
        println("Searching alternative exercise: original='$exerciseName', target='$targetMuscle', equipment='$equipment'")

        val referenceExercise = findMatchingExerciseByName(exerciseName)
        val referenceMuscles = referenceExercise?.muscleGroups?.toSet().orEmpty()
        val targetMuscles = resolveTargetMuscleGroups(targetMuscle)
        val desiredMuscles = if (referenceMuscles.isNotEmpty()) referenceMuscles else targetMuscles
        val referenceCategory = referenceExercise?.category ?: resolveTargetCategory(targetMuscle)
        val equipmentFilter = parseEquipmentFilter(equipment)

        val baseCandidates = exerciseRepository.findAll()
            .asSequence()
            .filter { candidate -> referenceExercise == null || candidate.id != referenceExercise.id }
            .filter { candidate -> equipmentFilter == null || candidate.equipment == equipmentFilter || candidate.equipment == null }
            .toList()

        val rankedCandidates = listOf(
            baseCandidates.filter { candidate ->
                (referenceCategory == null || candidate.category == referenceCategory) &&
                    (desiredMuscles.isEmpty() || candidate.muscleGroups.any { it in desiredMuscles })
            },
            baseCandidates.filter { candidate ->
                referenceCategory == null || candidate.category == referenceCategory
            },
            baseCandidates.filter { candidate ->
                desiredMuscles.isEmpty() || candidate.muscleGroups.any { it in desiredMuscles }
            },
            fallbackCandidates(targetMuscle, equipment)
        ).firstOrNull { it.isNotEmpty() }
            ?.sortedWith(RecommendationExerciseRanking.displayOrderComparator())
            .orEmpty()

        val selected = rankedCandidates.firstOrNull()
        if (selected != null) {
            println("Found alternative exercise: '$exerciseName' -> '${selected.name}'")
            return selected
        }

        println("Failed to find alternative exercise for '$exerciseName'")
        return null
    }

    private fun fallbackCandidates(
        targetMuscle: String?,
        equipment: String?,
        excludeExerciseId: Long? = null
    ): List<Exercise> {
        val targetMuscles = resolveTargetMuscleGroups(targetMuscle)
        val targetCategory = resolveTargetCategory(targetMuscle)
        val equipmentFilter = parseEquipmentFilter(equipment)

        return exerciseRepository.findAll()
            .asSequence()
            .filter { exercise -> excludeExerciseId == null || exercise.id != excludeExerciseId }
            .filter { exercise -> equipmentFilter == null || exercise.equipment == equipmentFilter || exercise.equipment == null }
            .filter { exercise ->
                targetCategory == null || exercise.category == targetCategory || targetMuscles.isEmpty()
            }
            .filter { exercise ->
                targetMuscles.isEmpty() || exercise.muscleGroups.any { it in targetMuscles }
            }
            .sortedWith(RecommendationExerciseRanking.displayOrderComparator())
            .toList()
    }

    private fun resolveTargetMuscleGroups(targetMuscle: String?): Set<MuscleGroup> {
        val normalized = targetMuscle?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptySet()

        WorkoutTargetResolver.resolveMuscleGroup(normalized)?.let { return setOf(it) }

        return when (WorkoutTargetResolver.resolveFocus(normalized)) {
            WorkoutFocus.CHEST -> setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            WorkoutFocus.BACK, WorkoutFocus.PULL -> setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS)
            WorkoutFocus.LEGS, WorkoutFocus.LOWER -> setOf(MuscleGroup.LEGS, MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            WorkoutFocus.SHOULDERS -> setOf(MuscleGroup.SHOULDERS, MuscleGroup.TRAPS)
            WorkoutFocus.ARMS -> setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS)
            WorkoutFocus.CORE -> setOf(MuscleGroup.CORE, MuscleGroup.ABS)
            WorkoutFocus.UPPER -> setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS)
            else -> emptySet()
        }
    }

    private fun resolveTargetCategory(targetMuscle: String?): ExerciseCategory? {
        return when (WorkoutTargetResolver.resolveFocus(targetMuscle)) {
            WorkoutFocus.CHEST, WorkoutFocus.PUSH -> ExerciseCategory.CHEST
            WorkoutFocus.BACK, WorkoutFocus.PULL -> ExerciseCategory.BACK
            WorkoutFocus.LEGS, WorkoutFocus.LOWER -> ExerciseCategory.LEGS
            WorkoutFocus.SHOULDERS -> ExerciseCategory.SHOULDERS
            WorkoutFocus.ARMS -> ExerciseCategory.ARMS
            WorkoutFocus.CORE -> ExerciseCategory.CORE
            else -> null
        }
    }

    private fun parseEquipmentFilter(equipment: String?): Equipment? {
        val normalized = equipment?.trim()?.uppercase()?.replace(" ", "_") ?: return null
        return runCatching { Equipment.valueOf(normalized) }.getOrNull()
    }

    /**
     * 운동명에서 근육 그룹 추출
     */
    private fun extractMuscleGroupsFromName(exerciseName: String, targetMuscle: String?): List<MuscleGroup> {
        return exerciseNameNormalizer.inferMuscleGroups(exerciseName, targetMuscle)
    }

    /**
     * 운동명에서 카테고리 추출
     */
    private fun extractCategoryFromName(exerciseName: String, targetMuscle: String?): ExerciseCategory? {
        return exerciseNameNormalizer.inferCategory(exerciseName, targetMuscle)
    }

    // ========================================
    // 헬스 트레이너 관점 공통 유틸리티 메서드
    // ========================================

    /**
     * 복합운동 여부 판별
     * 2개 이상의 관절이 움직이거나 여러 근육군이 함께 사용되는 운동
     */
    private fun isCompoundExercise(exercise: Exercise): Boolean {
        // 여러 근육군 사용
        if (exercise.muscleGroups.size >= 2) return true

        // 운동명으로 판별
        val name = exercise.name.lowercase()
        return exerciseNameNormalizer.isCompoundHint(name)
    }

    /**
     * 운동 우선순위 정렬
     * 1. 큰 근육 → 작은 근육
     * 2. 복합운동 → 고립운동
     */
    private fun orderExercisesByPriority(exercises: List<Exercise>): List<Exercise> {
        return exercises.sortedWith(
            compareBy<Exercise> { exercise ->
                // 카테고리별 우선순위 (큰 근육 먼저)
                when (exercise.category) {
                    ExerciseCategory.LEGS -> 1      // 하체 (가장 큰 근육군)
                    ExerciseCategory.BACK -> 2      // 등
                    ExerciseCategory.CHEST -> 3     // 가슴
                    ExerciseCategory.SHOULDERS -> 4 // 어깨
                    ExerciseCategory.ARMS -> 5      // 팔
                    ExerciseCategory.CORE -> 6      // 코어 (마지막)
                    else -> 7
                }
            }.thenBy { exercise ->
                // 같은 카테고리 내에서는 복합운동 우선
                if (isCompoundExercise(exercise)) 0 else 1
            }
        )
    }

    /**
     * 최근 N시간 이내에 운동한 근육군 조회
     */
    private fun getRecentlyWorkedMuscles(user: com.richjun.liftupai.domain.auth.entity.User, hours: Int): Set<MuscleGroup> {
        val cutoffTime = LocalDateTime.now().minusHours(hours.toLong())

        return workoutSessionRepository
            .findByUserAndStartTimeAfter(user, cutoffTime)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .flatMap { it.exercise.muscleGroups }
            }
            .toSet()
    }

    /**
     * 회복 중인 근육군 조회 (회복률 80% 미만)
     * MuscleRecovery 엔티티 활용
     */
    private fun getRecoveringMuscles(user: com.richjun.liftupai.domain.auth.entity.User): Set<MuscleGroup> {
        return try {
            muscleRecoveryRepository.findByUser(user)
                .filter { it.recoveryPercentage < 80 }
                .flatMap { recovery -> WorkoutTargetResolver.muscleGroupsFor(recovery.muscleGroup) }
                .toSet()
        } catch (e: Exception) {
            println("Failed to load MuscleRecovery data: ${e.message}")
            emptySet()
        }
    }

    /**
     * 특정 근육군의 주간 볼륨 계산 (주간 총 세트 수)
     */
    private fun calculateWeeklyVolume(user: com.richjun.liftupai.domain.auth.entity.User, muscleGroup: MuscleGroup): Int {
        val oneWeekAgo = LocalDateTime.now().minusDays(7)

        return workoutSessionRepository
            .findByUserAndStartTimeAfter(user, oneWeekAgo)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .filter { it.exercise.muscleGroups.contains(muscleGroup) }
            }
            .sumOf { exerciseSetRepository.findByWorkoutExerciseId(it.id).size }
    }

    /**
     * 모든 주요 근육군의 주간 볼륨 맵
     */
    private fun getWeeklyVolumeMap(user: com.richjun.liftupai.domain.auth.entity.User): Map<String, Int> {
        val majorMuscleGroups = listOf(
            MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LEGS,
            MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS,
            MuscleGroup.CORE
        )

        return majorMuscleGroups.associate { muscleGroup ->
            WorkoutTargetResolver.displayName(muscleGroup, "en") to calculateWeeklyVolume(user, muscleGroup)
        }
    }

    /**
     * 길항근 균형 체크
     * 가슴:등 = 1:1.5, 대퇴사두:햄스트링 = 1:1 권장
     */
    private fun checkMuscleBalance(user: com.richjun.liftupai.domain.auth.entity.User): List<String> {
        val weeklyVolume = getWeeklyVolumeMap(user)
        val warnings = mutableListOf<String>()

        // 가슴:등 비율 체크
        val chestVolume = weeklyVolume["Chest"] ?: 0
        val backVolume = weeklyVolume["Back"] ?: 0

        if (backVolume > 0) {
            val chestToBackRatio = chestVolume.toDouble() / backVolume
            if (chestToBackRatio > 0.8) { // 등이 가슴의 1.25배 미만
                warnings.add("Chest-to-back balance is off (${String.format("%.1f", chestToBackRatio)}:1, target about 1:1.5).")
                warnings.add("  -> Add more back work to reduce posture imbalance risk.")
            }
        }

        // 이두:삼두 비율 체크 (큰 의미는 없지만 참고)
        val bicepsVolume = weeklyVolume["Biceps"] ?: 0
        val tricepsVolume = weeklyVolume["Triceps"] ?: 0

        if (tricepsVolume > 0 && bicepsVolume > tricepsVolume * 1.5) {
            warnings.add("Biceps volume is much higher than triceps volume. Restore arm balance.")
        }

        return warnings
    }

    /**
     * 정체기 정보 데이터 클래스
     */
    data class PlateauInfo(
        val exercise: Exercise,
        val weeks: Int,
        val currentWeight: Double,
        val recommendation: String
    )

    /**
     * 특정 운동의 정체기 탐지 (3주 이상 무게 변화 < 2.5kg)
     */
    private fun detectPlateau(user: com.richjun.liftupai.domain.auth.entity.User, exercise: Exercise): PlateauInfo? {
        val threeWeeksAgo = LocalDateTime.now().minusDays(21)

        val recentSets = workoutSessionRepository
            .findByUserAndStartTimeAfter(user, threeWeeksAgo)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .filter { it.exercise.id == exercise.id }
                    .flatMap { exerciseSetRepository.findByWorkoutExerciseId(it.id) }
            }
            .sortedBy { it.completedAt }

        if (recentSets.size < 9) return null // 최소 9세트 (3주 * 3세트) 필요

        // 주차별 최대 무게 계산
        val weeklyMaxWeights = recentSets
            .groupBy {
                java.time.Duration.between(threeWeeksAgo, it.completedAt ?: LocalDateTime.now()).toDays() / 7
            }
            .mapValues { (_, sets) -> sets.maxOfOrNull { it.weight } ?: 0.0 }
            .values
            .toList()

        if (weeklyMaxWeights.size < 3) return null

        // 주차별 무게 변화 체크
        val weightChanges = weeklyMaxWeights.zipWithNext { prev, next ->
            kotlin.math.abs(next - prev)
        }

        val isStagnant = weightChanges.all { it < 2.5 } // 모든 주차 변화가 2.5kg 미만

        return if (isStagnant) {
            val currentWeight = weeklyMaxWeights.last()
            PlateauInfo(
                exercise = exercise,
                weeks = 3,
                currentWeight = currentWeight,
                recommendation = "Increase load by about 5kg if appropriate, or swap to a close variation (for example barbell -> dumbbell or angle change)."
            )
        } else null
    }

    /**
     * 모든 정체기 운동 조회
     */
    private fun getAllPlateaus(user: com.richjun.liftupai.domain.auth.entity.User): List<PlateauInfo> {
        val oneMonthAgo = LocalDateTime.now().minusDays(30)

        // 최근 한 달간 한 운동들
        val recentExercises = workoutSessionRepository
            .findByUserAndStartTimeAfter(user, oneMonthAgo)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .map { it.exercise }
            }
            .distinctBy { it.id }

        return recentExercises.mapNotNull { exercise ->
            detectPlateau(user, exercise)
        }
    }

    /**
     * 운동 다양성 보장 (최근 N주 동안 하지 않은 운동 우선)
     */
    private fun ensureExerciseVariety(
        user: com.richjun.liftupai.domain.auth.entity.User,
        candidates: List<Exercise>,
        weeks: Int = 4
    ): List<Exercise> {
        val cutoffDate = LocalDateTime.now().minusDays((weeks * 7).toLong())

        // 최근 N주간 한 운동 ID 목록
        val recentExerciseIds = workoutSessionRepository
            .findByUserAndStartTimeAfter(user, cutoffDate)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .map { it.exercise.id }
            }
            .toSet()

        // 1순위: 최근 N주 동안 하지 않은 운동
        val freshExercises = candidates.filter { it.id !in recentExerciseIds }

        // 2순위: 최근 한 운동 중 같은 카테고리지만 다른 변형
        val recentExerciseNames = workoutSessionRepository
            .findByUserAndStartTimeAfter(user, cutoffDate)
            .flatMap { session ->
                workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                    .map { it.exercise.name }
            }
            .toSet()

        val variations = candidates.filter { candidate ->
            val baseName = candidate.name.split(" ").lastOrNull() ?: candidate.name
            !recentExerciseNames.any { recent ->
                recent.contains(baseName) || baseName in recent
            }
        }

        // 신선한 운동 우선, 그 다음 변형, 마지막으로 최근 한 운동
        return (freshExercises + variations + candidates).distinctBy { it.id }
    }
}

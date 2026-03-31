package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.dto.response.PlanDashboardResponse
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AIPlanGenerationService(
    private val geminiAIService: GeminiAIService,
    private val curatedExercisePoolService: CuratedExercisePoolService,
    private val hallucinationGuardService: HallucinationGuardService,
    private val planQualityValidator: PlanQualityValidator,
    private val userPlanService: UserPlanService,
    private val exerciseRepository: ExerciseRepository,
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository,
    private val userWorkoutPlanRepository: UserWorkoutPlanRepository,
    private val userPlanDayRepository: UserPlanDayRepository,
    private val userPlanDayExerciseRepository: UserPlanDayExerciseRepository,
    private val injuryFilterService: InjuryFilterService,
    private val objectMapper: ObjectMapper,
    @Lazy private val self: AIPlanGenerationService
) {
    private val log = LoggerFactory.getLogger(AIPlanGenerationService::class.java)

    fun generateAndApplyPlan(user: User, request: GenerateAIPlanRequest): PlanDashboardResponse {
        return generateAndApplyPlanWithProgress(user, request) { _, _ -> }
    }

    fun generateAndApplyPlanWithProgress(
        user: User,
        request: GenerateAIPlanRequest,
        onProgress: (step: Int, message: String) -> Unit
    ): PlanDashboardResponse {
        log.info("Generating AI plan for user {} with goals: {}", user.id, request.goals)

        // 1. Build prompt
        onProgress(1, "운동 데이터베이스 분석 중...")
        val exercisePool = curatedExercisePoolService.getPromptReadyExerciseList()
        val prompt = buildPlanGenerationPrompt(request, exercisePool)

        // 2. Call Gemini AI (스트리밍)
        onProgress(2, "AI가 맞춤 플랜을 설계하고 있어요...")
        var lastNotifyLen = 0
        val aiResponse = callGeminiStreamingWithRetry(prompt, maxRetries = 3) { accumulated, _ ->
            // 500자마다 진행 상태 업데이트
            if (accumulated.length - lastNotifyLen > 500) {
                lastNotifyLen = accumulated.length
                val chars = accumulated.length
                onProgress(2, "AI가 플랜을 작성하고 있어요... (${chars}자 생성)")
            }
        }

        // 3. Parse JSON response
        onProgress(3, "AI 응답을 분석하고 있어요...")
        val parsedPlan = parseAIPlanResponse(aiResponse)

        // 4. Validate & repair hallucinations
        onProgress(4, "운동 정확도를 검증하고 있어요...")
        val validatedPlan = hallucinationGuardService.validateAndRepairPlan(parsedPlan)
        log.info("Hallucination guard: ${validatedPlan.repairs.size} repairs made")

        // 5. Quality check
        onProgress(5, "플랜 품질을 최종 검토하고 있어요...")
        val qualityReport = planQualityValidator.validate(validatedPlan.plan)
        if (!qualityReport.isValid) {
            log.warn("Quality validation failed, attempting regeneration...")
            onProgress(5, "품질 기준 미달, AI가 다시 설계하고 있어요...")
            val retryResponse = callGeminiWithRetry(
                prompt + "\n\nPREVIOUS ATTEMPT FAILED VALIDATION. Be more careful with exercise selection.",
                maxRetries = 1
            )
            val retryPlan = parseAIPlanResponse(retryResponse)
            val retryValidated = hallucinationGuardService.validateAndRepairPlan(retryPlan)
            val retryQuality = planQualityValidator.validate(retryValidated.plan)
            if (!retryQuality.isValid) {
                log.error("Second attempt also failed quality check. Proceeding anyway with warnings.")
            }
            onProgress(6, "플랜을 저장하고 있어요...")
            return self.persistAIPlan(user, retryValidated.plan, request)
        }

        // 6. Persist
        onProgress(6, "플랜을 저장하고 있어요...")
        return self.persistAIPlan(user, validatedPlan.plan, request)
    }

    @Transactional
    fun persistAIPlan(
        user: User,
        plan: AIPlanResponse,
        request: GenerateAIPlanRequest
    ): PlanDashboardResponse {
        // Abandon existing active plan
        userPlanService.abandonActivePlan(user.id)

        val splitType = try {
            SplitType.valueOf(plan.splitType.uppercase())
        } catch (_: Exception) {
            SplitType.FULL_BODY
        }

        // Save as reusable template (Bug 7: UUID suffix to prevent code collision)
        val templateCode = "ai_${user.id}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
        val template = WorkoutPlanTemplate(
            code = templateCode,
            name = plan.planName,
            description = plan.planDescription,
            targetGoal = request.goals.firstOrNull() ?: "GENERAL_FITNESS",
            targetExperience = request.experienceLevel,
            splitType = splitType,
            totalDays = plan.totalDays,
            estimatedWeeks = plan.estimatedWeeks,
            ownerUser = user,
            sourceType = PlanSourceType.AI_GENERATED,
            aiCoachingNotes = plan.coachingNotes
        )
        val savedTemplate = templateRepository.save(template)

        val injuries = request.injuries?.toSet() ?: emptySet()

        // Pre-resolve all exercises once to avoid duplicate resolution
        val resolvedExercises = mutableMapOf<Long, Exercise?>()
        for (dayEntry in plan.days) {
            for (exEntry in dayEntry.exercises) {
                if (!resolvedExercises.containsKey(exEntry.exerciseId)) {
                    var exercise = resolveExerciseWithFallback(exEntry, dayEntry.workoutType)
                    if (exercise != null && injuries.isNotEmpty() &&
                        injuryFilterService.isExerciseRestricted(exercise.id, injuries)) {
                        exercise = findNonRestrictedAlternative(exercise, injuries, dayEntry.workoutType)
                    }
                    resolvedExercises[exEntry.exerciseId] = exercise
                }
            }
        }

        // Save template days and exercises
        for (dayEntry in plan.days) {
            val workoutType = try {
                WorkoutType.valueOf(dayEntry.workoutType.uppercase())
            } catch (_: Exception) {
                WorkoutType.FULL_BODY
            }
            val templateDay = TemplateDay(
                template = savedTemplate,
                dayNumber = dayEntry.dayNumber,
                dayName = dayEntry.dayName,
                workoutType = workoutType,
                estimatedDurationMinutes = dayEntry.estimatedDurationMinutes
            )
            val savedTemplateDay = templateDayRepository.save(templateDay)

            for (exEntry in dayEntry.exercises) {
                val exercise = resolvedExercises[exEntry.exerciseId]
                if (exercise == null) {
                    log.error("Skipping exercise '{}' (ID={}) in template day {}: no exercise found even after fallback",
                        exEntry.exerciseName, exEntry.exerciseId, dayEntry.dayNumber)
                    continue
                }
                val templateExercise = TemplateDayExercise(
                    templateDay = savedTemplateDay,
                    exercise = exercise,
                    orderInDay = exEntry.order,
                    sets = exEntry.sets,
                    minReps = exEntry.minReps,
                    maxReps = exEntry.maxReps,
                    restSeconds = exEntry.restSeconds,
                    isCompound = exEntry.isCompound,
                    targetRPE = exEntry.targetRPE,
                    notes = exEntry.notes
                )
                templateDayExerciseRepository.save(templateExercise)
            }
        }

        // Create active user plan from template
        val progressionModel = try {
            ProgressionModel.valueOf(plan.progressionModel.uppercase())
        } catch (_: Exception) {
            ProgressionModel.LINEAR
        }
        val deloadEveryNWeeks = if (progressionModel == ProgressionModel.BLOCK) 7 else 4

        val userPlan = UserWorkoutPlan(
            user = user,
            sourceType = PlanSourceType.AI_GENERATED,
            sourceId = templateCode,
            planName = plan.planName,
            planDescription = plan.planDescription,
            splitType = splitType,
            totalDays = plan.totalDays,
            currentDay = 1,
            status = PlanStatus.ACTIVE,
            progressionModel = progressionModel,
            deloadEveryNWeeks = deloadEveryNWeeks,
            aiCoachingNotes = plan.coachingNotes
        )
        val savedPlan = userWorkoutPlanRepository.save(userPlan)

        for (dayEntry in plan.days) {
            val workoutType = try {
                WorkoutType.valueOf(dayEntry.workoutType.uppercase())
            } catch (_: Exception) {
                WorkoutType.FULL_BODY
            }
            val planDay = UserPlanDay(
                plan = savedPlan,
                dayNumber = dayEntry.dayNumber,
                dayName = dayEntry.dayName,
                workoutType = workoutType,
                estimatedDurationMinutes = dayEntry.estimatedDurationMinutes
            )
            val savedDay = userPlanDayRepository.save(planDay)

            for (exEntry in dayEntry.exercises) {
                val exercise = resolvedExercises[exEntry.exerciseId]
                if (exercise == null) {
                    log.error("Skipping exercise '{}' (ID={}) in user plan day {}: no exercise found even after fallback",
                        exEntry.exerciseName, exEntry.exerciseId, dayEntry.dayNumber)
                    continue
                }
                val planExercise = UserPlanDayExercise(
                    planDay = savedDay,
                    exercise = exercise,
                    orderInDay = exEntry.order,
                    sets = exEntry.sets,
                    minReps = exEntry.minReps,
                    maxReps = exEntry.maxReps,
                    restSeconds = exEntry.restSeconds,
                    isCompound = exEntry.isCompound,
                    targetRPE = exEntry.targetRPE,
                    notes = exEntry.notes
                )
                userPlanDayExerciseRepository.save(planExercise)
            }
        }

        log.info(
            "AI plan created for user {}: '{}' with {} days, saved as template '{}'",
            user.id, plan.planName, plan.totalDays, templateCode
        )

        return userPlanService.getPlanDashboard(user.id)
    }

    /**
     * Bug 3 fix: Resolve exercise by ID with fallback to ESSENTIAL exercise in same category.
     * Logs a warning instead of silently skipping.
     */
    private fun resolveExerciseWithFallback(exEntry: AIPlanExerciseEntry, workoutType: String): Exercise? {
        val exercise = exerciseRepository.findById(exEntry.exerciseId).orElse(null)
        if (exercise != null) return exercise

        log.warn("Exercise ID={} ('{}') not found in DB, attempting fallback for workoutType={}",
            exEntry.exerciseId, exEntry.exerciseName, workoutType)

        val category = hallucinationGuardService.mapWorkoutTypeToCategory(workoutType)
        val fallback = exerciseRepository.findFirstByCategoryAndRecommendationTierOrderByPopularityDesc(
            category, RecommendationTier.ESSENTIAL
        ) ?: exerciseRepository.findByCategory(category).firstOrNull()

        if (fallback != null) {
            log.warn("Fallback for '{}': using '{}' (ID={})", exEntry.exerciseName, fallback.name, fallback.id)
        }
        return fallback
    }

    private fun findNonRestrictedAlternative(
        restricted: Exercise, injuries: Set<String>, workoutType: String
    ): Exercise? {
        val category = hallucinationGuardService.mapWorkoutTypeToCategory(workoutType)
        val alternatives = exerciseRepository.findByIsPlanEligibleTrueAndCategory(category)
            .filter { it.id != restricted.id && !injuryFilterService.isExerciseRestricted(it.id, injuries) }
            .sortedByDescending { it.popularity }
        return alternatives.firstOrNull()
    }

    private fun callGeminiWithRetry(prompt: String, maxRetries: Int): String {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val response = geminiAIService.generatePlanContent(prompt)
                if (response.isNotBlank()) return response
            } catch (e: Exception) {
                log.warn("Gemini API attempt ${attempt + 1} failed: ${e.message}")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }
        throw lastException ?: RuntimeException("Failed to generate AI plan after $maxRetries attempts")
    }

    private fun callGeminiStreamingWithRetry(
        prompt: String,
        maxRetries: Int,
        onChunk: (accumulated: String, chunkSize: Int) -> Unit
    ): String {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val response = geminiAIService.generatePlanContentStreaming(prompt, onChunk)
                if (response.isNotBlank()) return response
            } catch (e: Exception) {
                log.warn("Gemini Streaming API attempt ${attempt + 1} failed: ${e.message}")
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }
        throw lastException ?: RuntimeException("Failed to generate AI plan after $maxRetries attempts")
    }

    private fun parseAIPlanResponse(response: String): AIPlanResponse {
        val jsonStr = extractJsonObject(response)

        return try {
            objectMapper.readValue<AIPlanResponse>(jsonStr)
        } catch (e: Exception) {
            log.error("Failed to parse AI response as JSON: ${e.message}")
            log.debug("Raw response: $jsonStr")
            throw RuntimeException("AI 응답을 파싱할 수 없습니다. 다시 시도해주세요.", e)
        }
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        if (start == -1) throw RuntimeException("AI 응답에서 JSON을 찾을 수 없습니다.")
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        throw RuntimeException("AI 응답에서 완전한 JSON 객체를 찾을 수 없습니다.")
    }

    private fun buildPlanGenerationPrompt(request: GenerateAIPlanRequest, exercisePool: String): String {
        return """
You are an expert strength & conditioning coach creating a workout plan.
You MUST respond with ONLY a valid JSON object. No explanations, no markdown, just JSON.

## User Profile
- Experience: ${request.experienceLevel}
- Goals: ${request.goals.joinToString(", ")}
- Gender: ${request.gender ?: "not specified"}
- Age: ${request.age ?: "not specified"}
- Weekly days: ${request.weeklyDays}
- Session duration: ${request.sessionDuration} minutes
- Equipment: ${request.equipment.joinToString(", ")}
- Injuries: ${request.injuries?.joinToString(", ") ?: "none"}
- Focus areas: ${request.focusAreas?.joinToString(", ") ?: "none"}

## Available Exercises (USE ONLY THESE — do NOT invent exercises)
$exercisePool

## Rules
1. ONLY use exercise IDs from the list above. Do NOT invent exercise IDs.
2. Each day: 5-8 exercises.
3. First 1-2 exercises per day must be compound (isCompound: true).
4. Rep ranges by exercise type:
   - Main compound (squat/bench/deadlift): 5-8 reps for muscle_gain, 3-5 for strength
   - Accessory compound: 8-10 reps for muscle_gain, 5-8 for strength
   - Isolation: 10-15 reps for muscle_gain, 8-12 for strength
   - Weight loss: compound 10-12, isolation 12-15
5. Rest: main compound 150-180s, accessory 90-120s, isolation 60-90s
6. Split logic:
   - 2 days -> Full Body A/B
   - 3 days -> Full Body A/B/C
   - 4 days -> Upper/Lower x2
   - 5 days -> Upper/Lower/Push/Pull/Legs
   - 6 days -> PPL x2
7. Plan name in Korean, descriptive and motivating.
8. RPE targets per exercise type:
   - Main compound: RPE 7.0-8.0
   - Accessory compound: RPE 7.0-7.5
   - Isolation: RPE 7.0-8.5 (higher RPE ok for isolation)
   - Beginners: all exercises RPE 6.0-7.0
9. Progression model:
   - Beginners: LINEAR (add weight each session)
   - Intermediate: UNDULATING (heavy/medium/light rotation)
   - Advanced: BLOCK (accumulation→intensification→realization)

## JSON Schema (respond EXACTLY in this format)
{
  "planName": "string (Korean)",
  "planDescription": "string (Korean)",
  "splitType": "FULL_BODY|UPPER_LOWER|PPL|BRO_SPLIT|PPLUL",
  "totalDays": ${request.weeklyDays},
  "estimatedWeeks": 8,
  "progressionModel": "LINEAR|UNDULATING|BLOCK",
  "days": [
    {
      "dayNumber": 1,
      "dayName": "string (Korean, descriptive with target muscles, e.g. '밀기 가슴·어깨·삼두', '하체 스쿼트·런지', '당기기 등·이두')",
      "workoutType": "PUSH|PULL|LEGS|UPPER|LOWER|FULL_BODY",
      "estimatedDurationMinutes": number,
      "exercises": [
        {
          "exerciseId": number,
          "exerciseName": "string",
          "sets": number,
          "minReps": number,
          "maxReps": number,
          "restSeconds": number,
          "order": number,
          "isCompound": boolean,
          "targetRPE": number,
          "notes": "string or null"
        }
      ]
    }
  ],
  "coachingNotes": "string (Korean, overall advice)"
}
""".trimIndent()
    }
}

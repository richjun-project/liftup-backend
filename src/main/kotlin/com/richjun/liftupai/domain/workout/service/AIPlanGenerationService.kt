package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.dto.response.PlanDashboardResponse
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.user.service.StrengthAssessmentEstimator
import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
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
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
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
    ): PlanDashboardResponse = generate(user, request, onProgress, streamChunk = null)

    /**
     * 스트리밍 응답을 쓰는 경로. Gemini 청크를 [onChunk] 로 흘려보내되,
     * 검증·보정·재시도·폴백은 동기 경로와 완전히 같은 파이프라인을 탄다.
     */
    fun generateAndApplyPlanStreaming(
        user: User,
        request: GenerateAIPlanRequest,
        onChunk: (accumulated: String, chunkSize: Int) -> Unit,
        onProgress: (step: Int, message: String) -> Unit = { _, _ -> }
    ): PlanDashboardResponse = generate(user, request, onProgress, streamChunk = onChunk)

    /** 한 번의 생성 시도 결과 */
    internal data class Attempt(val plan: AIPlanResponse, val report: PlanQualityReport, val repairs: List<RepairLog>, val adjustments: List<String>)

    private fun generate(
        user: User,
        request: GenerateAIPlanRequest,
        onProgress: (step: Int, message: String) -> Unit,
        streamChunk: ((String, Int) -> Unit)?
    ): PlanDashboardResponse {
        log.info("Generating AI plan for user {} with goals: {}", user.id, request.goals)

        // 부상은 요청 + 프로필 + 설정을 합친다. 한쪽만 보면 필터가 조용히 무력화된다(UserPlanService 와 같은 이유).
        val injuries = resolveInjuries(user, request)
        val effectiveRequest = if (injuries.isNotEmpty()) request.copy(injuries = injuries.toList()) else request
        val targets = targetsOf(effectiveRequest)
        val constraints = PlanConstraints(
            allowedEquipment = PlanConstraints.normalizeEquipment(effectiveRequest.equipment),
            injuries = injuries,
            isRestricted = { ex -> injuries.isNotEmpty() && injuryFilterService.isExerciseRestricted(ex.id, injuries) }
        )

        // 1. Build prompt
        onProgress(1, "운동 데이터베이스 분석 중...")
        val exercisePool = curatedExercisePoolService.getPromptReadyExerciseList(
            availableEquipment = effectiveRequest.equipment.toSet()
        )
        val prompt = buildPlanGenerationPrompt(effectiveRequest, exercisePool, targets)

        // 2. Call Gemini AI
        onProgress(2, "AI가 맞춤 플랜을 설계하고 있어요...")
        val firstRaw = try {
            if (streamChunk != null) callGeminiStreamingWithRetry(prompt, 3, streamChunk) else callGeminiWithRetry(prompt, 3)
        } catch (e: Exception) {
            log.error("Gemini call failed for user {}: {} — falling back to template", user.id, e.message)
            return fallbackToTemplate(user, effectiveRequest, injuries, onProgress, "AI 호출 실패")
        }

        // 3. Parse (실패 시 JSON-only 지시로 1회 재요청)
        onProgress(3, "AI 응답을 분석하고 있어요...")
        var parsed = tryParse(firstRaw)
        if (parsed == null) {
            log.warn("First AI response unparsable — retrying once with JSON-only instruction")
            val retryRaw = try { callGeminiWithRetry(prompt + PARSE_FIX_INSTRUCTION, 1) } catch (e: Exception) { "" }
            parsed = tryParse(retryRaw)
            if (parsed == null) {
                return fallbackToTemplate(user, effectiveRequest, injuries, onProgress, "AI 응답 파싱 실패")
            }
        }

        // 4~5. Guard → normalize → validate
        onProgress(4, "운동 정확도를 검증하고 있어요...")
        onProgress(5, "플랜 품질을 최종 검토하고 있어요...")
        var attempt = runPipeline(parsed, constraints, targets)

        if (!attempt.report.isValid) {
            log.warn("Quality validation failed ({} errors), regenerating with fix instructions", attempt.report.errors.size)
            onProgress(5, "품질 기준 미달, AI가 다시 설계하고 있어요...")
            val fixPrompt = prompt + buildFixInstructions(attempt.report)
            val retryParsed = try { tryParse(callGeminiWithRetry(fixPrompt, 1)) } catch (e: Exception) { null }
            if (retryParsed != null) {
                val retryAttempt = runPipeline(retryParsed, constraints, targets)
                // 재시도가 더 나으면 채택, 아니면 첫 시도를 유지
                if (retryAttempt.report.isValid || retryAttempt.report.errors.size < attempt.report.errors.size) {
                    attempt = retryAttempt
                }
            }
        }

        if (!attempt.report.isValid && !isUsable(attempt.plan)) {
            log.error("AI plan unusable after retry: {} — falling back to template",
                attempt.report.errors.map { it.message })
            return fallbackToTemplate(user, effectiveRequest, injuries, onProgress, "AI 플랜 품질 미달")
        }
        if (!attempt.report.isValid) {
            log.warn("Persisting AI plan with remaining warnings: {}", attempt.report.errors.map { it.message })
        }

        // 6. Persist
        onProgress(6, "플랜을 저장하고 있어요...")
        return self.persistAIPlan(user, attempt.plan, effectiveRequest)
    }

    /** 환각 복구(제약 포함) → 서버 보정 → 검증 */
    internal fun runPipeline(parsed: AIPlanResponse, constraints: PlanConstraints, targets: PlanTargets): Attempt {
        val guarded = hallucinationGuardService.validateAndRepairPlan(parsed, constraints)
        log.info("Hallucination guard: ${guarded.repairs.size} repairs made")
        val normalized = planQualityValidator.normalize(guarded.plan, targets)
        val report = planQualityValidator.validate(normalized.plan, targets)
        return Attempt(normalized.plan, report, guarded.repairs, normalized.adjustments)
    }

    /** 오류가 남아도 사용자에게 줄 수 있는 최소 조건: 모든 날에 운동 3개 이상, 일수 일치 */
    private fun isUsable(plan: AIPlanResponse): Boolean =
        plan.days.isNotEmpty() && plan.days.size == plan.totalDays && plan.days.all { it.exercises.size >= 3 }

    private fun tryParse(raw: String): AIPlanResponse? = try {
        if (raw.isBlank()) null else parseAIPlanResponse(raw)
    } catch (e: Exception) {
        log.warn("AI plan parse failed: {}", e.message)
        null
    }

    internal fun buildFixInstructions(report: PlanQualityReport): String {
        val lines = report.errors.map { "- " + (it.fixInstruction ?: it.message) }.distinct()
        return "\n\nPREVIOUS ATTEMPT FAILED VALIDATION. Fix ALL of the following and respond with the full corrected JSON only:\n" +
            lines.joinToString("\n")
    }

    internal fun targetsOf(request: GenerateAIPlanRequest): PlanTargets = PlanTargets(
        primaryGoal = PlanGoal.fromGoals(request.goals),
        experienceLevel = request.experienceLevel,
        weeklyDays = request.weeklyDays,
        sessionDurationMinutes = request.sessionDuration,
        age = request.age,
        bmi = PlanTargets.bmiOf(request.height, request.weight),
        locale = request.locale ?: "ko"
    )

    internal fun resolveInjuries(user: User, request: GenerateAIPlanRequest): Set<String> {
        val fromRequest = request.injuries.orEmpty()
        val fromProfile = try {
            userProfileRepository.findByUser_Id(user.id).orElse(null)?.injuries?.toList().orEmpty()
        } catch (e: Exception) { emptyList() }
        val fromSettings = try {
            userSettingsRepository.findByUser_Id(user.id).orElse(null)?.injuries?.toList().orEmpty()
        } catch (e: Exception) { emptyList() }
        return (fromRequest + fromProfile + fromSettings)
            .map { it.substringBefore(':').trim().lowercase() }
            .filter { it.isNotEmpty() && it != "none" }
            .toSet()
    }

    /**
     * AI 가 끝내 쓸 만한 플랜을 못 주면 사전 템플릿을 적용한다.
     * 경험 수준과 주간 일수가 가장 가까운 프리셋을 고르고, 부상·장비 필터는 applyTemplate 이 처리한다.
     * 사용자는 에러 대신 플랜을 받는다.
     */
    internal fun fallbackToTemplate(
        user: User,
        request: GenerateAIPlanRequest,
        injuries: Set<String>,
        onProgress: (step: Int, message: String) -> Unit,
        reason: String
    ): PlanDashboardResponse {
        onProgress(6, "검증된 기본 플랜을 적용하고 있어요...")
        val code = chooseFallbackTemplateCode(request)
        log.warn("AI plan fallback → template '{}' for user {} ({})", code, user.id, reason)
        return userPlanService.applyTemplate(
            user, code,
            ApplyTemplateRequest(injuries = injuries.toList().ifEmpty { null }, equipment = request.equipment.ifEmpty { null }),
            request.locale ?: "ko"
        )
    }

    internal fun chooseFallbackTemplateCode(request: GenerateAIPlanRequest): String {
        val presets = try {
            templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET)
        } catch (e: Exception) { emptyList() }
        if (presets.isEmpty()) return DEFAULT_FALLBACK_TEMPLATE
        val exp = request.experienceLevel.lowercase()
        val expRank = mapOf("beginner" to 0, "novice" to 0, "intermediate" to 1, "advanced" to 2, "expert" to 2)
        val userRank = expRank[exp] ?: 0
        return presets.minByOrNull { t ->
            val tRank = expRank[t.targetExperience.lowercase()] ?: 1
            kotlin.math.abs(tRank - userRank) * 2 + kotlin.math.abs(t.totalDays - request.weeklyDays) + (if (t.isPremium) 5 else 0)
        }?.code ?: DEFAULT_FALLBACK_TEMPLATE
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

        val injuries = request.injuries?.map { it.substringBefore(':').trim().lowercase() }?.toSet() ?: emptySet()

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
     * id 로 운동을 확정하고, 없으면 같은 부위의 플랜 후보군 중 인기도 최상위로 대체한다.
     *
     * 과거에는 `findByCategory(category).firstOrNull()` 로 폴백했는데 ORDER BY 가 없어
     * id 최소값(= 이름 알파벳 최상위)이 뽑혔고, 이 때문에 "3 Leg Dog Pose" 처럼
     * 인기도 20짜리 스트레칭이 사용자 플랜에 저장됐다.
     */
    private fun resolveExerciseWithFallback(exEntry: AIPlanExerciseEntry, workoutType: String): Exercise? {
        val exercise = exerciseRepository.findById(exEntry.exerciseId).orElse(null)
        if (exercise != null) return exercise

        log.warn("Exercise ID={} ('{}') not found in DB, attempting fallback for workoutType={}",
            exEntry.exerciseId, exEntry.exerciseName, workoutType)

        val category = hallucinationGuardService.mapWorkoutTypeToCategory(workoutType)
        val fallback = exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(category).firstOrNull()

        if (fallback != null) {
            log.warn("Fallback for '{}': using '{}' (ID={})", exEntry.exerciseName, fallback.name, fallback.id)
        } else {
            log.error("No plan-eligible fallback in category {} for '{}'", category, exEntry.exerciseName)
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
                log.info("Gemini attempt ${attempt + 1} response length: ${response.length}")
                if (response.isNotBlank()) return response
                log.warn("Gemini attempt ${attempt + 1} returned blank response")
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
        log.info("AI raw response length: ${response.length}, first 200 chars: ${response.take(200)}")

        if (response.isBlank()) {
            throw RuntimeException("AI 응답이 비어있습니다. Gemini API가 빈 결과를 반환했습니다.")
        }

        val jsonStr = extractJsonObject(response)
        log.info("Extracted JSON length: ${jsonStr.length}")

        return try {
            objectMapper.readValue<AIPlanResponse>(jsonStr)
        } catch (e: Exception) {
            log.error("Failed to parse AI response as JSON: ${e.message}")
            log.error("Raw JSON (first 500): ${jsonStr.take(500)}")
            throw RuntimeException("AI 응답을 파싱할 수 없습니다. 다시 시도해주세요.", e)
        }
    }

    private fun extractJsonObject(text: String): String {
        // 마크다운 코드블록 제거
        var cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val start = cleaned.indexOf('{')
        if (start == -1) {
            log.error("No '{' found in AI response. Full text: ${text.take(500)}")
            throw RuntimeException("AI 응답에서 JSON을 찾을 수 없습니다.")
        }

        // 문자열 리터럴 안의 중괄호를 무시하면서 깊이 추적
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"' && !escape) { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        log.error("Incomplete JSON. depth=$depth, text length=${cleaned.length}, first 500: ${cleaned.take(500)}")
        throw RuntimeException("AI 응답에서 완전한 JSON 객체를 찾을 수 없습니다.")
    }

    private fun buildPlanGenerationPrompt(request: GenerateAIPlanRequest, exercisePool: String, targets: PlanTargets): String {
        val trainingStyle = request.trainingStyle ?: "balanced"
        val focusAreas = request.focusAreas?.joinToString(", ") ?: "none"
        val strengthData = buildStrengthDataSummary(request)
        // planName/dayName used to be hardcoded to always generate in Korean regardless of the user's
        // language, so en/ja users saw Korean plan/day names on their home dashboard. Derive the target
        // language from the request's locale (falls back to Korean only when no locale was supplied, to
        // preserve existing behavior for older clients).
        val responseLanguage = com.richjun.liftupai.domain.ai.util.AILocalization.responseLanguage(request.locale ?: "ko")

        return """
You are an expert strength & conditioning coach creating a workout plan.
You MUST respond with ONLY a valid JSON object. No explanations, no markdown, just JSON.

## User Profile
- Experience: ${request.experienceLevel}
- Goals: ${request.goals.joinToString(", ")}
- Training style: $trainingStyle
- PT style: ${request.ptStyle ?: "not specified"}
- Gender: ${request.gender ?: "not specified"}
- Age: ${request.age ?: "not specified"}
- Height: ${request.height?.let { "$it cm" } ?: "not specified"}
- Weight: ${request.weight?.let { "$it kg" } ?: "not specified"}
- Strength level: ${request.strengthLevel ?: "not specified"}
- Weekly days: ${request.weeklyDays}
- Session duration: ${request.sessionDuration} minutes
- Equipment: ${request.equipment.joinToString(", ")}
- Injuries: ${request.injuries?.joinToString(", ") ?: "none"}
- Focus areas: $focusAreas
- Existing split preference: ${request.workoutSplit ?: "auto"}

$strengthData

${buildPopulationRules(targets)}

## Available Exercises (USE ONLY THESE — do NOT invent exercises)
$exercisePool

## Rules
1. ONLY use exercise IDs from the list above. Do NOT invent exercise IDs.
2. The exercise list is already filtered for the user's available equipment. Do not prescribe equipment outside it.
3. Each day: 5-8 exercises.
4. First 1-2 exercises per day must be compound (isCompound: true).
5. Exercise order: main compound → accessory compound → isolation (heaviest to lightest).

6. Training style determines rep/set/rest scheme:
${buildTrainingStyleRules(trainingStyle)}

7. Weekly volume per muscle group (total working sets across all days):
${buildVolumeGuidelines(trainingStyle)}

8. Exercise selection ratio by training style:
${buildExerciseSelectionRules(trainingStyle)}

9. Focus area programming:
${buildFocusAreaRules(focusAreas)}

10. Split logic (choose the BEST split considering BOTH weekly days AND training style):
${buildSplitLogic(request.weeklyDays, trainingStyle)}

11. RPE targets:
${buildRPETargets(request.experienceLevel, trainingStyle)}

12. Progression model:
   - Beginners: LINEAR (add weight each session)
   - Intermediate: UNDULATING (heavy/medium/light rotation)
   - Advanced: BLOCK (accumulation→intensification→realization)

13. Use the strength data to decide exercise complexity, progression model, and starting volume conservatively.
14. Plan name in $responseLanguage, descriptive and motivating.
15. Injuries listed above are hard constraints: never include an exercise that loads an injured area.
16. Never schedule the same workoutType on two consecutive days (except FULL_BODY). Across the week include upper body, lower body AND core.
17. Never list the same exercise, or two exercises with the same movement pattern, in one day.
18. The sum of set×(reps×3s + rest) per day must fit the session duration (${request.sessionDuration} min ±20%).

## JSON Schema (respond EXACTLY in this format)
{
  "planName": "string (in $responseLanguage)",
  "planDescription": "string (in $responseLanguage)",
  "splitType": "FULL_BODY|UPPER_LOWER|PPL|BRO_SPLIT|PPLUL",
  "totalDays": ${request.weeklyDays},
  "estimatedWeeks": 8,
  "progressionModel": "LINEAR|UNDULATING|BLOCK",
  "days": [
    {
      "dayNumber": 1,
      "dayName": "string (in $responseLanguage, descriptive with target muscles, e.g. 'Push: Chest, Shoulders, Triceps', 'Legs: Squat, Lunge', 'Pull: Back, Biceps' — translated into $responseLanguage)",
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

    /**
     * 미성년·고령·저체중·비만 사용자용 보수 규칙. 프롬프트로 요구하고, 서버(PlanQualityValidator.normalize)가
     * 같은 규칙을 결정적으로 강제한다 — 프롬프트만 믿지 않는다.
     */
    internal fun buildPopulationRules(t: PlanTargets): String {
        if (!t.isSpecialPopulation) return ""
        val rules = mutableListOf<String>()
        if (t.isMinor) {
            rules += "- The user is a MINOR (age ${t.age}). Frame the plan around fitness, strength and health — NEVER around weight loss, dieting or fat burning, even if the goal says weight loss. Do not use words like 감량/다이어트/weight loss in planName, planDescription or coachingNotes."
            rules += "- Minors: no near-maximal loads (no reps below 6, RPE ≤ 7), no plyometrics/jumps, no Olympic lifts (clean/snatch). Prioritize technique."
        }
        if (t.isElderly) rules += "- The user is ${t.age} years old: joint-friendly movements, RPE ≤ 7, reps ≥ 6, rest ≥ 90s, no jumps/plyometrics, no Olympic lifts, include balance/mobility-friendly exercises."
        if (t.isUnderweight) rules += "- The user is underweight (BMI ${"%.1f".format(t.bmi)}): keep weekly volume moderate (≤ ${t.weeklySetCap} working sets), emphasize compound strength work, never a fat-loss framing."
        if (t.isObese) rules += "- The user has BMI ${"%.1f".format(t.bmi)}: avoid jumping/plyometric and high-impact moves, prefer machines/bodyweight variations that are joint-friendly, longer rest between sets."
        rules += "- Total weekly working sets must not exceed ${t.weeklySetCap}; per day not more than ${t.dailySetCap}."
        return "## Safety Rules For This User (MANDATORY)\n" + rules.joinToString("\n")
    }

    private fun buildStrengthDataSummary(request: GenerateAIPlanRequest): String {
        val estimatedMaxes = request.estimatedMaxes
            ?: StrengthAssessmentEstimator.estimateMaxes(
                assessment = request.strengthAssessment,
                bodyWeightKg = request.weight
            )
                .takeIf { it.isNotEmpty() }

        val estimated = estimatedMaxes
            ?.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(8)
            ?.joinToString(", ") { "${it.key}: ${"%.1f".format(it.value)}kg est max" }

        val working = request.workingWeights
            ?.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(8)
            ?.joinToString(", ") { "${it.key}: ${"%.1f".format(it.value)}kg working" }

        if (estimated == null && working == null) {
            return """
## Strength Data
- No tracked maxes or working weights yet. Start conservatively and prioritize technique.
            """.trimIndent()
        }

        return """
## Strength Data
- Estimated maxes: ${estimated ?: "not available"}
- Current working weights: ${working ?: "not available"}
- Use this to avoid under-prescribing for experienced users and over-prescribing for beginners.
        """.trimIndent()
    }

    private fun buildTrainingStyleRules(style: String): String = when (style) {
        "hypertrophy" -> """
   - Main compound: 3-4 sets x 8-12 reps, rest 90-120s
   - Accessory compound: 3 sets x 10-12 reps, rest 60-90s
   - Isolation: 3 sets x 12-15 reps, rest 45-60s
   - Prioritize time under tension and muscle contraction"""
        "strength" -> """
   - Main compound: 4-5 sets x 3-6 reps, rest 180-300s
   - Accessory compound: 3-4 sets x 5-8 reps, rest 120-180s
   - Isolation: 2-3 sets x 8-12 reps, rest 60-90s
   - Prioritize progressive overload on big lifts"""
        "fat_loss" -> """
   - Main compound: 3 sets x 10-15 reps, rest 45-60s
   - Accessory compound: 3 sets x 12-15 reps, rest 30-45s
   - Isolation: 2-3 sets x 15-20 reps, rest 30s
   - Use supersets where possible, keep heart rate elevated"""
        "functional" -> """
   - Main compound: 3-4 sets x 6-10 reps, rest 90-120s
   - Accessory compound: 3 sets x 8-12 reps, rest 60-90s
   - Isolation: 2-3 sets x 10-15 reps, rest 45-60s
   - Include unilateral exercises for balance and stability"""
        else -> """
   - Main compound: 4 sets x 5-8 reps, rest 120-180s
   - Accessory compound: 3 sets x 8-12 reps, rest 90-120s
   - Isolation: 3 sets x 10-15 reps, rest 60-90s
   - Mix strength and hypertrophy rep ranges"""
    }

    private fun buildVolumeGuidelines(style: String): String = when (style) {
        "hypertrophy" -> """
   - Large muscles (chest, back, quads): 14-20 sets/week
   - Medium muscles (shoulders, hamstrings, glutes): 12-16 sets/week
   - Small muscles (biceps, triceps, calves): 10-14 sets/week
   - Distribute volume evenly across training days"""
        "strength" -> """
   - Primary lifts (squat, bench, deadlift): 10-15 sets/week on main movement patterns
   - Accessory muscles: 6-10 sets/week (minimum effective volume)
   - Small muscles: 4-8 sets/week
   - Concentrate volume on compound movements"""
        "fat_loss" -> """
   - All muscle groups: 8-12 sets/week (maintenance volume to preserve muscle)
   - Prioritize compound movements for caloric expenditure
   - Keep total session volume high but per-muscle volume moderate"""
        "functional" -> """
   - Large muscles: 10-14 sets/week
   - Medium muscles: 8-12 sets/week
   - Small muscles: 6-10 sets/week
   - Emphasize movement patterns over isolated muscles"""
        else -> """
   - Large muscles: 12-16 sets/week
   - Medium muscles: 10-14 sets/week
   - Small muscles: 8-12 sets/week"""
    }

    private fun buildExerciseSelectionRules(style: String): String = when (style) {
        "hypertrophy" -> """
   - Compound exercises: ~50%, Isolation exercises: ~50%
   - Include exercises from multiple angles for each muscle group
   - Prefer exercises with long range of motion"""
        "strength" -> """
   - Compound exercises: >=70%, Isolation exercises: <=30%
   - Must include squat/bench/deadlift or close variants as first exercise
   - Accessory work should directly support main lifts"""
        "fat_loss" -> """
   - Compound exercises: >=60%, Isolation exercises: <=40%
   - Prefer multi-joint movements that recruit large muscle groups
   - Consider pairing exercises as supersets (add note in exercise notes)"""
        "functional" -> """
   - Compound exercises: >=60%, Isolation exercises: <=40%
   - Include at least one unilateral exercise per day
   - Mix pushing, pulling, hinging, squatting, and carrying patterns"""
        else -> """
   - Compound exercises: ~55%, Isolation exercises: ~45%
   - Balance between strength-building compounds and hypertrophy isolation"""
    }

    private fun buildSplitLogic(weeklyDays: Int, style: String): String {
        return when (weeklyDays) {
            2 -> """
   - 2 days: Full Body A/B (only option for 2 days)"""
            3 -> when (style) {
                "strength" -> """
   - 3 days: Full Body A/B/C — each day centered around one main lift (squat/bench/deadlift)"""
                "fat_loss" -> """
   - 3 days: Full Body A/B/C — high caloric burn with compound-heavy full body sessions"""
                else -> """
   - 3 days: Full Body A/B/C or Push/Pull/Legs (choose based on user's goals)"""
            }
            4 -> when (style) {
                "hypertrophy" -> """
   - 4 days: Upper/Lower x2 OR Push/Pull split — choose the split that best distributes volume for muscle growth
   - Upper/Lower is good for balanced development, Push/Pull allows more exercise variety per session"""
                "strength" -> """
   - 4 days: Upper/Lower x2 — pair main lifts (squat+deadlift on lower days, bench+OHP on upper days)"""
                "fat_loss" -> """
   - 4 days: Full Body x4 OR Upper/Lower x2 — full body preferred for higher caloric expenditure per session"""
                "functional" -> """
   - 4 days: Full Body x4 with different movement focus each day (push/pull/hinge/squat emphasis)"""
                else -> """
   - 4 days: Upper/Lower x2 — balanced split for general fitness"""
            }
            5 -> when (style) {
                "hypertrophy" -> """
   - 5 days: PPL + Upper/Lower OR Bro Split — higher frequency per muscle possible with PPL hybrid"""
                "strength" -> """
   - 5 days: Upper/Lower/Push/Pull/Legs — 3 heavy compound days + 2 accessory days"""
                else -> """
   - 5 days: Upper/Lower/Push/Pull/Legs — versatile 5-day split"""
            }
            else -> when (style) {
                "hypertrophy" -> """
   - 6 days: PPL x2 — each muscle hit twice per week for optimal hypertrophy frequency"""
                "strength" -> """
   - 6 days: PPL x2 with heavy/light alternation — heavy compounds on first rotation, volume on second"""
                else -> """
   - 6 days: PPL x2 — push/pull/legs twice per week"""
            }
        }
    }

    private fun buildFocusAreaRules(focusAreas: String): String {
        if (focusAreas == "none") return """
   - No specific focus areas. Distribute volume evenly across all muscle groups."""
        return """
   - Focus muscles: $focusAreas
   - Add +4-6 extra weekly sets for focus area muscles compared to standard volume
   - Include 1-2 additional isolation exercises targeting focus areas on relevant days
   - Reduce non-focus muscle volume by 2-3 sets/week to stay within session duration
   - Place focus area exercises earlier in the workout when possible"""
    }

    private fun buildRPETargets(experience: String, style: String): String {
        return when (style) {
            "strength" -> """
   - Main compound: RPE 8.0-9.0 (experienced) / 7.0-8.0 (beginners)
   - Accessory compound: RPE 7.0-8.0
   - Isolation: RPE 7.0-8.0"""
            "hypertrophy" -> """
   - Main compound: RPE 7.0-8.0
   - Accessory compound: RPE 7.5-8.5
   - Isolation: RPE 8.0-9.0 (push closer to failure on isolation)"""
            "fat_loss" -> """
   - All exercises: RPE 6.5-7.5 (sustainable intensity for higher volume)"""
            else -> when (experience.lowercase()) {
                "beginner", "novice" -> """
   - All exercises: RPE 6.0-7.0 (focus on form and learning movements)"""
                "advanced", "expert" -> """
   - Main compound: RPE 7.5-9.0
   - Accessory compound: RPE 7.0-8.0
   - Isolation: RPE 8.0-9.0"""
                else -> """
   - Main compound: RPE 7.0-8.0
   - Accessory compound: RPE 7.0-7.5
   - Isolation: RPE 7.0-8.5"""
            }
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_TEMPLATE = "beginner_fullbody"
        const val PARSE_FIX_INSTRUCTION = "\n\nYOUR PREVIOUS RESPONSE WAS NOT VALID JSON. Respond with ONLY the JSON object described in the schema. No prose, no markdown fences."
    }
}

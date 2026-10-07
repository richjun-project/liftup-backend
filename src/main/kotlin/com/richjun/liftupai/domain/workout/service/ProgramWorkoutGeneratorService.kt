package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules
import com.richjun.liftupai.domain.workout.service.new.SessionFit

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.dto.GeneratedWorkout
import com.richjun.liftupai.domain.workout.dto.GraduationStatusDto
import com.richjun.liftupai.domain.workout.dto.ProgramGeneratedExercise
import com.richjun.liftupai.domain.workout.dto.ProgramSubstituteExercise
import com.richjun.liftupai.domain.workout.dto.ProgramWarmupSet
import com.richjun.liftupai.domain.workout.dto.ReadinessScore
import com.richjun.liftupai.domain.workout.dto.WeeklyVolumeStatus
import com.richjun.liftupai.domain.workout.entity.InjurySeverity
import com.richjun.liftupai.domain.workout.entity.ProgressionModel
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.repository.InjuryExerciseRestrictionRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ProgramDayRepository
import com.richjun.liftupai.domain.workout.repository.UserExerciseOverrideRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class ProgramWorkoutGeneratorService(
    private val programEnrollmentService: ProgramEnrollmentService,
    private val programDayRepository: ProgramDayRepository,
    private val programDayExerciseRepository: ProgramDayExerciseRepository,
    private val userExerciseOverrideRepository: UserExerciseOverrideRepository,
    private val progressiveOverloadService: ProgramProgressiveOverloadService,
    private val exerciseSubstitutionService: ExerciseSubstitutionService,
    private val graduationService: ProgramGraduationService,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val progressionService: ProgramProgressionService,
    private val injuryExerciseRestrictionRepository: InjuryExerciseRestrictionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val exerciseSetRepository: ExerciseSetRepository,
    private val weightRecommendationService: WeightRecommendationService,
    /** 장비·부상·경험·나이의 단일 출처 — 프로그램도 추천 경로와 같은 안전 규칙을 생성 시점에 적용한다 */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
    private val trainingProfileResolver: ExerciseTrainingProfileResolver = ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
    @Autowired(required = false)
    private val injuryFilterService: InjuryFilterService? = null,
    @Autowired(required = false)
    private val localizationService: ExerciseCatalogLocalizationService? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun generateTodayWorkout(user: User, subjectiveReadiness: Int? = null): GeneratedWorkout {
        // 1. Get current enrollment
        val enrollment = programEnrollmentService.getCurrentEnrollment(user)
            ?: throw ResourceNotFoundException("No active program enrollment for user: ${user.id}")

        // 2. Get current position
        val position = programEnrollmentService.getCurrentPosition(enrollment)

        val program = enrollment.program

        // 3. Get the ProgramDay for current dayInCycle
        val programDays = programDayRepository.findByProgramIdOrderByDayNumber(program.id)
        val programDay = programDays.find { it.dayNumber == position.dayInCycle }
            ?: programDays.firstOrNull()
            ?: throw ResourceNotFoundException("No program days found for program: ${program.code}")

        // 4. Load ProgramDayExercises via JOIN FETCH
        val dayExercises = programDayExerciseRepository.findByDayIdWithExercises(programDay.id)

        // 5. Load user exercise overrides
        val overrideMap = userExerciseOverrideRepository
            .findByEnrollmentId(enrollment.id)
            .associateBy { it.originalExercise.id }

        // 6. 이 사람의 환경(부상 ∪, 장비, 수준, 나이)을 단일 해석기로 한 번 읽는다 (바깥, 루프 밖)
        val ctx = trainingContext.resolve(user.id)
        val locale = ctx.language
        val injuries = ctx.injuries
        val userAge = ctx.age
        val level = ctx.level ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER
        val restricted: (com.richjun.liftupai.domain.workout.entity.Exercise) -> Boolean =
            injuryFilterService?.restrictionChecker(injuries)
                ?: { ex -> injuries.isNotEmpty() && InjuryFilterService.ruleRestricts(ex, injuries) }
        val suitable = RecoRules.suitability(ctx.equipment, level, userAge)
        val allowed: (com.richjun.liftupai.domain.workout.entity.Exercise) -> Boolean =
            { ex -> TemplatePlanAdapter.equipmentAllowed(ex, ctx.equipment) && !restricted(ex) && suitable(ex) }

        // Load all SEVERE restrictions for the user's injuries (큐레이션 표 — 현재 비어 있어도 아래 규칙 기반 안전 층이 막는다)
        val injuryRestrictions = injuries.flatMap { injury ->
            injuryExerciseRestrictionRepository.findByInjuryTypeAndSeverityIn(
                injury,
                listOf(InjurySeverity.SEVERE)
            )
        }

        // FIX 3: Compute readiness score
        val readiness = computeReadiness(user, enrollment, subjectiveReadiness, locale)

        // FIX 1: BLOCK phase adjustment
        val blockPhaseAdj = if (program.progressionModel == ProgressionModel.BLOCK) {
            progressiveOverloadService.getBlockPhaseAdjustment(position)
        } else null

        // Map from exerciseId to muscle groups (for volume-reactive set adjustment)
        val exerciseMuscleGroupsMap = mutableMapOf<Long, Set<String>>()
        val usedInDay = mutableSetOf<Long>()
        val actualExercises = mutableMapOf<Long, com.richjun.liftupai.domain.workout.entity.Exercise>()

        // 7 & 8. Build exercise list applying overrides, SEVERE auto-substitution, weights, warmups
        val keptDayExercises = mutableListOf<com.richjun.liftupai.domain.workout.entity.ProgramDayExercise>()
        val generatedExercises = dayExercises.mapNotNull { pde ->
            val override = overrideMap[pde.exercise.id]
            var actualExercise = override?.substituteExercise ?: pde.exercise

            // Auto-substitute if SEVERE injury restriction applies
            val severeRestriction = injuryRestrictions.find {
                it.restrictedExercise.id == actualExercise.id && it.suggestedSubstitute != null
            }
            if (severeRestriction != null) {
                val substitute = severeRestriction.suggestedSubstitute!!
                logger.info("Auto-substituted ${actualExercise.name} → ${substitute.name} (SEVERE injury restriction)")
                actualExercise = substitute
            }

            // FIX 4: Exercise rotation for isolation exercises every 4 weeks
            val substitutes = exerciseSubstitutionService
                .getSubstitutesForInjury(actualExercise.id, injuries)
            if (!pde.isCompound && substitutes.isNotEmpty()) {
                val sortedSubstitutes = if (enrollment.program.progressionModel == ProgressionModel.BLOCK && blockPhaseAdj != null) {
                    if (blockPhaseAdj.intensityPercent >= 0.85) {
                        substitutes.sortedByDescending { it.substituteExercise.difficulty }  // Realization: harder
                    } else {
                        substitutes.sortedBy { it.substituteExercise.difficulty }  // Accumulation: easier/higher SFR
                    }
                } else substitutes
                val rotationIndex = ((position.week - 1) / 4) % (sortedSubstitutes.size + 1)
                if (rotationIndex > 0 && rotationIndex <= sortedSubstitutes.size) {
                    val rotatedExercise = exerciseRepository.findById(sortedSubstitutes[rotationIndex - 1].substituteExercise.id).orElse(null)
                    if (rotatedExercise != null) {
                        logger.info("Rotated isolation exercise: ${actualExercise.name} → ${rotatedExercise.name} (week ${position.week}, rotation #$rotationIndex)")
                        actualExercise = rotatedExercise
                    }
                }
            }

            // 안전 층: 오버라이드·회전·큐레이션 표가 무엇을 골랐든 마지막에 부상·장비·수준 규칙을 지킨다.
            // 예전엔 비어 있는 제한 표로만 걸러 무릎 부상 사용자의 프로그램에 스쿼트가 그대로 나갔고,
            // 장비 오버라이드는 문자열 비교라 맨몸·기타 운동까지 "장비 없음" 으로 보았다.
            if (!allowed(actualExercise) || actualExercise.id in usedInDay) {
                val replacement = pickSafeReplacement(actualExercise, pde.exercise, usedInDay, allowed)
                if (replacement != null) {
                    logger.info("Program safety layer: ${actualExercise.name} → ${replacement.name} (injury/equipment/level rules)")
                    actualExercise = replacement
                } else if (!allowed(actualExercise)) {
                    // 부상·장비·수준 규칙을 어기는데 대체할 운동도 없다 — 어기는 운동을 남기느니 그날 목록에서 뺀다
                    logger.warn("Program safety layer: dropping ${actualExercise.name} (no safe replacement under injury/equipment/level rules)")
                    return@mapNotNull null
                }
            }
            keptDayExercises += pde
            usedInDay += actualExercise.id
            actualExercises[actualExercise.id] = actualExercise

            // Record muscle groups for this exercise (keyed by original pde exercise id for volume map lookup)
            exerciseMuscleGroupsMap[pde.exercise.id] = actualExercise.muscleGroups.map { it.name.lowercase() }.toSet()

            // Calculate suggested weight
            val targetReps = (pde.minReps + pde.maxReps) / 2
            val rawWeight = progressiveOverloadService.calculateWeight(
                user = user,
                exercise = actualExercise,
                enrollment = enrollment,
                position = position,
                dayExercise = pde
            ) ?: weightRecommendationService.calculateSuggestedWeight(
                user.id,
                actualExercise.id,
                targetReps
            )

            // 준비도만 곱하고 2.5kg 단위·바벨 20kg 하한은 단일 관문을 거친다. 나이는 기본값 표가 이미 한 번 반영했다(두 번 깎지 않는다).
            val suggestedWeight = RecoRules.finalizeWeight(actualExercise, rawWeight * readiness.intensityMultiplier)

            // FIX 1: Apply BLOCK phase sets/reps
            // GAP 2.3: Volume deload for LINEAR/UNDULATING
            val deloadSetsMultiplier = if (position.isDeloadWeek &&
                enrollment.program.progressionModel != ProgressionModel.BLOCK) {
                0.60
            } else 1.0
            val adjustedSets = if (blockPhaseAdj != null) {
                (pde.sets * blockPhaseAdj.setsMultiplier).roundToInt().coerceAtLeast(2)
            } else {
                (pde.sets * deloadSetsMultiplier).roundToInt().coerceAtLeast(2)
            }
            var adjustedMinReps = blockPhaseAdj?.repRangeLow ?: pde.minReps
            var adjustedMaxReps = blockPhaseAdj?.repRangeHigh ?: pde.maxReps
            var rxSets = adjustedSets
            var rxRest = pde.restSeconds
            // 대체 운동이 원래와 훈련 성격(주력 복합·보조·고립·코어)이 다르면 원래 처방(스쿼트 4x6-8/180초)을 그대로 두지 않는다
            if (actualExercise.id != pde.exercise.id &&
                trainingProfileResolver.resolveProfile(actualExercise) != trainingProfileResolver.resolveProfile(pde.exercise)) {
                val tp = trainingProfileResolver.resolveProfile(actualExercise)
                val cfg = trainingProfileResolver.getConfig(com.richjun.liftupai.domain.workout.service.PTRecommendationEngine.PeriodizationPhase.ACCUMULATION, tp)
                Regex("""(\d+)\s*(?:-\s*(\d+))?""").find(cfg.reps)?.takeIf { !cfg.reps.contains("time") }?.let { m ->
                    adjustedMinReps = m.groupValues[1].toInt()
                    adjustedMaxReps = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
                    rxSets = cfg.sets
                    rxRest = cfg.restSeconds
                }
            }
            val compoundNow = RecoRules.isCompound(actualExercise) || pde.isCompound && actualExercise.id == pde.exercise.id
            // 초보·노비스의 첫 4주: 복합 8회 이상, 주력(바벨) 복합 3세트 이하
            if (RecoRules.isBeginnerBand(level) && position.week <= 4) {
                val rx = RecoRules.beginnerRx(actualExercise, RecoRules.Rx(rxSets, adjustedMinReps, adjustedMaxReps, rxRest), compoundNow)
                rxSets = rx.sets; adjustedMinReps = rx.minReps; adjustedMaxReps = rx.maxReps; rxRest = rx.restSeconds
            }
            rxSets = rxSets.coerceAtMost(RecoRules.maxSetsFor(actualExercise, level, userAge, compoundNow)).coerceAtLeast(1)

            // Generate warmup sets for compound exercises and heavy isolation exercises
            // 웜업은 모든 경로가 같은 규칙(RecoRules.warmups) — 바벨만 빈 봉에서 시작하고, 50세 이상은 아주 가벼운 첫 세트가 더해진다
            val warmupSets = if (compoundNow || suggestedWeight > 30.0) {
                RecoRules.warmups(actualExercise, suggestedWeight, userAge).map { (w, reps) -> ProgramWarmupSet(weight = w, reps = reps) }
            } else {
                emptyList()
            }

            // GAP 6.1: Phase-adjusted rest periods
            val baseRest = rxRest
            val phaseRestMultiplier = if (enrollment.program.progressionModel == ProgressionModel.BLOCK &&
                blockPhaseAdj != null) {
                when {
                    blockPhaseAdj.intensityPercent >= 0.85 -> 1.5   // Realization: 50% more rest
                    blockPhaseAdj.intensityPercent >= 0.75 -> 1.2   // Intensification: 20% more
                    else -> 1.0  // Accumulation: normal
                }
            } else 1.0
            val ageRestMultiplier = when {
                userAge != null && userAge >= 50 -> 1.2
                userAge != null && userAge >= 40 -> 1.1
                else -> 1.0
            }
            val adjustedRest = (baseRest * phaseRestMultiplier * ageRestMultiplier).roundToInt()

            // Build substitute list (injury-aware) — reuse already-fetched substitutes
            val substituteResponses = substitutes.filter { allowed(it.substituteExercise) }.map { sub ->
                ProgramSubstituteExercise(
                    exerciseId = sub.substituteExercise.id,
                    name = localizedName(sub.substituteExercise, locale),
                    reason = sub.reason.name,
                    category = sub.substituteExercise.category.name,
                    equipment = sub.substituteExercise.equipment?.name
                )
            }

            ProgramGeneratedExercise(
                exerciseId = actualExercise.id,
                name = localizedName(actualExercise, locale),
                category = actualExercise.category.name,
                equipment = actualExercise.equipment?.name,
                sets = rxSets,
                minReps = adjustedMinReps,
                maxReps = adjustedMaxReps,
                restSeconds = adjustedRest,
                suggestedWeight = suggestedWeight,
                targetRPE = pde.targetRPE,
                isCompound = pde.isCompound,
                warmupSets = warmupSets,
                substitutes = substituteResponses
            )
        }

        // 9. Check graduation status
        val graduationStatus = graduationService.checkGraduation(enrollment, locale).let { gs ->
            GraduationStatusDto(
                shouldGraduate = gs.shouldGraduate,
                completionRate = gs.completionRate,
                nextProgramCode = gs.nextProgramCode,
                nextProgramName = gs.nextProgramName,
                message = gs.reason
            )
        }

        // 10. Compute weekly volume status
        val weeklyVolume = computeWeeklyVolumeStatus(user, keptDayExercises, generatedExercises)

        // 11. Volume-reactive set adjustment: add/remove 1 set based on BELOW_MEV / ABOVE_MAV
        val isRealizationPhase = enrollment.program.progressionModel == ProgressionModel.BLOCK &&
            progressiveOverloadService.getBlockPhaseAdjustment(position).setsMultiplier <= 0.70

        val adjustedExercises = generatedExercises.mapIndexed { index, exercise ->
            val pde = keptDayExercises[index]
            val muscleGroups = exerciseMuscleGroupsMap[pde.exercise.id] ?: emptySet()

            val belowMev = weeklyVolume.any { vol ->
                muscleGroups.any { it.contains(vol.muscleGroup, ignoreCase = true) || vol.muscleGroup.contains(it, ignoreCase = true) } &&
                    vol.status == "BELOW_MEV"
            }
            val aboveMav = weeklyVolume.any { vol ->
                muscleGroups.any { it.contains(vol.muscleGroup, ignoreCase = true) || vol.muscleGroup.contains(it, ignoreCase = true) } &&
                    vol.status == "ABOVE_MAV"
            }
            val aboveMrv = weeklyVolume.any { vol ->
                muscleGroups.any { it.contains(vol.muscleGroup, ignoreCase = true) || vol.muscleGroup.contains(it, ignoreCase = true) } &&
                    vol.currentSets > progressionService.getMRV(vol.muscleGroup, readiness.score)
            }

            when {
                aboveMrv && exercise.sets > 2 -> exercise.copy(sets = exercise.sets - 2)
                aboveMav && exercise.sets > 2 -> exercise.copy(sets = exercise.sets - 1)
                belowMev && !isRealizationPhase && exercise.sets < (actualExercises[exercise.exerciseId]
                    ?.let { RecoRules.maxSetsFor(it, level, userAge, exercise.isCompound) } ?: 5) -> exercise.copy(sets = exercise.sets + 1)
                else -> exercise
            }
        }

        // 11-b. 저장된 세션 시간에 세트·휴식을 맞춘다(종목은 그대로, 세트 상한을 지킨다) — 추천·플랜과 같은 규칙
        val fittedExercises = fitToSession(adjustedExercises, actualExercises, ctx, level)

        // 12. Return GeneratedWorkout
        return GeneratedWorkout(
            programName = com.richjun.liftupai.domain.workout.util.WorkoutTranslations.createProgramName(
                program.daysPerWeek, program.splitType.name, program.targetExperienceLevel.name, locale
            ),
            weekNumber = position.week,
            dayNumber = position.dayInCycle,
            dayName = localizedDayName(programDay.name, programDay.workoutType, locale),
            isDeloadWeek = position.isDeloadWeek,
            periodizationPhase = resolvePeriodizationPhase(program.progressionModel, position),
            workoutType = programDay.workoutType,
            estimatedDuration = if (fittedExercises.isEmpty()) programDay.estimatedDurationMinutes else SessionFit.minutes(
                fittedExercises.map { SessionFit.Slot(it.sets, "${it.minReps}-${it.maxReps}", it.restSeconds) }
            ),
            exercises = fittedExercises,
            graduationStatus = if (graduationStatus.shouldGraduate) graduationStatus else null,
            weeklyVolume = weeklyVolume,
            readinessScore = readiness
        )
    }

    private fun computeReadiness(user: User, enrollment: UserProgramEnrollment, subjectiveReadiness: Int? = null, locale: String = "en"): ReadinessScore {
        val factors = mutableListOf<String>()
        var score = 1.0
        fun msg(key: String, vararg args: Any?) = com.richjun.liftupai.domain.workout.util.WorkoutLocalization.message(key, locale, *args)

        // Factor 1: Days since last workout
        val daysSinceLast = if (enrollment.lastActiveDate != null) {
            ChronoUnit.DAYS.between(enrollment.lastActiveDate, AppTime.utcNow())
        } else 3

        when {
            daysSinceLast == 0L -> { score -= 0.1; factors.add(msg("readiness.worked_today")) }
            daysSinceLast == 1L -> { factors.add(msg("readiness.recovery_ok")) }
            daysSinceLast in 2..3 -> { factors.add(msg("readiness.recovery_good")) }
            daysSinceLast > 3 -> { score -= 0.05; factors.add(msg("readiness.gap_days", daysSinceLast)) }
        }

        // Factor 2: Recent RPE trend (from last 3 sessions)
        val recentSessions = workoutSessionRepository.findByUserAndStatusInOrderByStartTimeDesc(
            user, listOf(SessionStatus.COMPLETED), PageRequest.of(0, 3)
        )
        val recentRPEs = recentSessions.flatMap { session ->
            workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
                .flatMap { we -> exerciseSetRepository.findByWorkoutExerciseId(we.id) }
                .mapNotNull { it.rpe?.toDouble() }
        }
        val avgRecentRPE = if (recentRPEs.isNotEmpty()) recentRPEs.average() else 7.0

        when {
            avgRecentRPE >= 9.0 -> { score -= 0.15; factors.add(msg("readiness.rpe_high")) }
            avgRecentRPE >= 8.0 -> { score -= 0.05; factors.add(msg("readiness.rpe_ok")) }
            avgRecentRPE < 6.0 -> { score += 0.05; factors.add(msg("readiness.rpe_low")) }
            else -> { factors.add(msg("readiness.normal")) }
        }

        // Factor 3: Subjective readiness (if provided)
        if (subjectiveReadiness != null) {
            when (subjectiveReadiness) {
                1 -> { score -= 0.20; factors.add(msg("readiness.subjective.1")) }
                2 -> { score -= 0.10; factors.add(msg("readiness.subjective.2")) }
                3 -> { factors.add(msg("readiness.subjective.3")) }
                4 -> { score += 0.05; factors.add(msg("readiness.subjective.4")) }
                5 -> { score += 0.10; factors.add(msg("readiness.subjective.5")) }
            }
        }

        score = score.coerceIn(0.5, 1.1)

        val intensityMultiplier = when {
            score > 1.0 -> 1.05      // 컨디션이 좋다고 답했거나 최근 강도가 낮았을 때만 +5% (평범한 날은 그대로)
            score >= 0.95 -> 1.0
            score >= 0.85 -> 0.95
            score >= 0.75 -> 0.90
            else -> 0.85
        }

        return ReadinessScore(
            score = score,
            factors = factors,
            intensityMultiplier = intensityMultiplier
        )
    }

    /** 한글로 저장된 프로그램 하루 이름을 ko 가 아닌 사용자에게 그대로 내려 주지 않는다 */
    private fun localizedDayName(stored: String, type: com.richjun.liftupai.domain.workout.entity.WorkoutType, locale: String): String {
        val hasHangul = stored.any { it in '\uAC00'..'\uD7A3' }
        return if (hasHangul && locale != "ko") com.richjun.liftupai.domain.workout.util.WorkoutLocalization.workoutTypeName(type, locale) else stored
    }

    private fun fitToSession(
        exercises: List<ProgramGeneratedExercise>,
        entities: Map<Long, com.richjun.liftupai.domain.workout.entity.Exercise>,
        ctx: UserTrainingContextResolver.Context,
        level: com.richjun.liftupai.domain.user.entity.ExperienceLevel,
    ): List<ProgramGeneratedExercise> {
        val target = ctx.sessionMinutes ?: return exercises
        if (exercises.isEmpty()) return exercises
        val slots = exercises.map { e ->
            val ex = entities[e.exerciseId]
            SessionFit.Slot(
                sets = e.sets, reps = "${e.minReps}-${e.maxReps}", restSeconds = e.restSeconds,
                minRestSeconds = (e.restSeconds * 0.75).roundToInt().coerceAtLeast(30).coerceAtMost(e.restSeconds),
                maxRestSeconds = (if (ex != null) RecoRules.maxRestFor(ex, e.isCompound, e.minReps) else 120).coerceAtLeast(e.restSeconds),
                maxSets = (if (ex != null) RecoRules.maxSetsFor(ex, level, ctx.age, e.isCompound, target) else 5).coerceAtLeast(e.sets),
            )
        }
        val fitted = SessionFit.fit(slots, target).slots
        return exercises.zip(fitted).map { (e, slot) -> e.copy(sets = slot.sets, restSeconds = slot.restSeconds) }
    }

    private fun localizedName(ex: com.richjun.liftupai.domain.workout.entity.Exercise, locale: String): String {
        val loc = localizationService ?: return ex.name
        if (locale == "en") return ex.name
        return try { loc.displayName(ex, locale, loc.translationMap(listOf(ex), locale)) } catch (e: Exception) { ex.name }
    }

    /**
     * 부상·장비·수준 규칙을 어기는(또는 이미 그날에 있는) 운동의 안전한 대체. 같은 카테고리의 플랜 후보 중 동작 패턴·근육이
     * 가장 비슷한 것을 고른다(SubstituteRanker). 후보가 없으면 null — 호출자는 원래 운동을 그대로 둔다.
     */
    private fun pickSafeReplacement(
        current: com.richjun.liftupai.domain.workout.entity.Exercise,
        original: com.richjun.liftupai.domain.workout.entity.Exercise,
        usedInDay: Set<Long>,
        allowed: (com.richjun.liftupai.domain.workout.entity.Exercise) -> Boolean,
    ): com.richjun.liftupai.domain.workout.entity.Exercise? {
        // 같은 부위를 우선하고, 그 부위의 후보가 부상·장비로 전부 막히면(어깨 부상의 가슴 운동 등) 다른 부위의 안전한 운동으로 바꾼다
        val categories = (listOf(current.category, original.category) + listOf(
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.BACK, com.richjun.liftupai.domain.workout.entity.ExerciseCategory.LEGS,
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CORE, com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CHEST,
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.SHOULDERS, com.richjun.liftupai.domain.workout.entity.ExerciseCategory.ARMS,
        )).distinct()
        for (category in categories) {
            val pool = exerciseRepository.findPlanEligibleByCategoryOrderByPopularity(category)
            val ranked = SubstituteRanker.rank(
                original = current, pool = pool, curatedIds = emptySet(),
                isAllowed = { ex -> ex.id !in usedInDay && allowed(ex) }, limit = 1,
            ).firstOrNull()?.exercise
                ?: pool.firstOrNull { it.id !in usedInDay && it.id != current.id && allowed(it) }
            if (ranked != null) return ranked
        }
        return null
    }

    private fun computeWeeklyVolumeStatus(
        user: User,
        dayExercises: List<com.richjun.liftupai.domain.workout.entity.ProgramDayExercise>,
        generatedExercises: List<ProgramGeneratedExercise>
    ): List<WeeklyVolumeStatus> {
        // Count sets per muscle group from this week's completed sessions
        val weekStart = AppTime.utcNow().with(java.time.DayOfWeek.MONDAY).toLocalDate().atStartOfDay()
        val thisWeekSessions = workoutSessionRepository.findByUserAndStartTimeAfter(user, weekStart)
            .filter { it.status == SessionStatus.COMPLETED }

        val completedSetsPerMuscle = mutableMapOf<String, Int>()
        thisWeekSessions.forEach { session ->
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            exercises.forEach { we ->
                val setCount = we.sets.size
                val primaryMuscle = we.exercise.muscleGroups.firstOrNull()
                if (primaryMuscle != null) {
                    val key = primaryMuscle.name.lowercase()
                    completedSetsPerMuscle[key] = completedSetsPerMuscle.getOrDefault(key, 0) + setCount
                }
            }
        }

        // Add today's planned sets per muscle group (from dayExercises which have the Exercise entity)
        val plannedSetsPerMuscle = mutableMapOf<String, Int>()
        val plannedSetsByExerciseId = generatedExercises.associateBy({ it.exerciseId }, { it.sets })
        dayExercises.forEach { pde ->
            val sets = plannedSetsByExerciseId[pde.exercise.id] ?: pde.sets
            val primaryMuscle = pde.exercise.muscleGroups.firstOrNull()
            if (primaryMuscle != null) {
                val key = primaryMuscle.name.lowercase()
                plannedSetsPerMuscle[key] = plannedSetsPerMuscle.getOrDefault(key, 0) + sets
            }
        }

        // Merge completed + planned
        val allMuscles = (completedSetsPerMuscle.keys + plannedSetsPerMuscle.keys).toSet()
        return allMuscles.map { muscle ->
            val totalSets = completedSetsPerMuscle.getOrDefault(muscle, 0) +
                plannedSetsPerMuscle.getOrDefault(muscle, 0)
            val mev = progressionService.getMEV(muscle)
            val mav = progressionService.getMAV(muscle)
            val status = when {
                totalSets < mev -> "BELOW_MEV"
                totalSets > mav -> "ABOVE_MAV"
                else -> "ON_TARGET"
            }
            WeeklyVolumeStatus(
                muscleGroup = muscle,
                currentSets = totalSets,
                mevSets = mev,
                mavSets = mav,
                status = status
            )
        }.sortedBy { it.muscleGroup }
    }

    private fun roundToNearest(value: Double, increment: Double): Double {
        return (value / increment).roundToInt() * increment
    }

    private fun resolvePeriodizationPhase(model: ProgressionModel, position: ProgramPosition): String {
        if (position.isDeloadWeek) return "DELOAD"
        return when (model) {
            ProgressionModel.LINEAR -> "LINEAR_PROGRESSION"
            ProgressionModel.UNDULATING -> when (position.dayInCycle % 3) {
                1 -> "HEAVY"
                2 -> "MEDIUM"
                else -> "LIGHT"
            }
            ProgressionModel.BLOCK -> when ((position.week - 1) % 7) {
                0, 1 -> "ACCUMULATION"
                2, 3 -> "INTENSIFICATION"
                4, 5 -> "REALIZATION"
                else -> "DELOAD"
            }
        }
    }
}

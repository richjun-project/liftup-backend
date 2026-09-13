package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

enum class IssueSeverity { WARNING, ERROR }

data class QualityIssue(
    val severity: IssueSeverity,
    val message: String,
    /** 재생성 시 AI 에게 그대로 전달할 수정 지시 (없으면 message 사용) */
    val fixInstruction: String? = null
)

data class PlanQualityReport(
    val isValid: Boolean,
    val issues: List<QualityIssue>
) {
    val errors: List<QualityIssue> get() = issues.filter { it.severity == IssueSeverity.ERROR }
}

/**
 * 플랜 보정에 필요한 사용자 조건. 요청(GenerateAIPlanRequest)에서 뽑는다.
 */
data class PlanTargets(
    val primaryGoal: PlanGoal,
    val experienceLevel: String,
    val weeklyDays: Int,
    val sessionDurationMinutes: Int,
    val age: Int? = null,
    val bmi: Double? = null,
    val locale: String = "ko"
) {
    val isMinor: Boolean get() = age != null && age < 19
    val isElderly: Boolean get() = age != null && age >= 65
    val isUnderweight: Boolean get() = bmi != null && bmi < 18.5
    val isObese: Boolean get() = bmi != null && bmi >= 30.0
    val isSpecialPopulation: Boolean get() = isMinor || isElderly || isUnderweight || isObese
    val isBeginner: Boolean get() = experienceLevel.lowercase() in setOf("beginner", "novice")

    /** 주간 총 작업 세트 상한. 특수 집단은 보수적으로. */
    val weeklySetCap: Int
        get() = when {
            isElderly -> 50
            isMinor -> 60
            isUnderweight -> 55
            isObese && isBeginner -> 60
            else -> Int.MAX_VALUE
        }

    /** 하루 총 세트 상한 */
    val dailySetCap: Int
        get() = when {
            isElderly -> 16
            isMinor || isUnderweight -> 20
            isObese && isBeginner -> 20
            else -> 35
        }

    companion object {
        fun bmiOf(height: Double?, weight: Double?): Double? {
            if (height == null || weight == null || height <= 0) return null
            val m = height / 100.0
            return weight / (m * m)
        }
    }
}

enum class PlanGoal(val minReps: Int, val maxReps: Int, val defaultMin: Int, val defaultMax: Int, val maxRestSeconds: Int?) {
    STRENGTH(3, 8, 4, 6, null),
    HYPERTROPHY(6, 15, 8, 12, null),
    ENDURANCE(12, 20, 12, 15, null),
    WEIGHT_LOSS(10, 20, 12, 15, 75),
    GENERAL(6, 15, 8, 12, null);

    companion object {
        fun fromGoals(goals: List<String>): PlanGoal {
            val normalized = goals.map { it.trim().uppercase() }
            return when {
                normalized.any { it in setOf("STRENGTH", "POWER") } -> STRENGTH
                normalized.any { it in setOf("MUSCLE_GAIN", "HYPERTROPHY", "BULK") } -> HYPERTROPHY
                normalized.any { it in setOf("ENDURANCE", "STAMINA") } -> ENDURANCE
                normalized.any { it in setOf("WEIGHT_LOSS", "FAT_LOSS", "DIET") } -> WEIGHT_LOSS
                else -> GENERAL
            }
        }
    }
}

data class NormalizedPlan(
    val plan: AIPlanResponse,
    /** 사람이 읽을 수 있는 보정 내역 */
    val adjustments: List<String>
)

@Service
class PlanQualityValidator(
    private val exerciseRepository: ExerciseRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val log = LoggerFactory.getLogger(PlanQualityValidator::class.java)

    // ───────────────────────────── 보정 (normalize) ─────────────────────────────

    /**
     * AI 가 돌려준 플랜을 사용자 조건에 맞게 서버가 직접 고친다.
     * 프롬프트로 요구했어도 AI 는 종종 범위를 벗어나므로, 여기서 결정적으로 보정한다.
     *
     * 순서: 특수 집단 고위험 동작 제거 → 목표별 반복/휴식 보정 → 볼륨 상한 → 세션 시간 맞춤
     *       → 미성년 감량 프레임 치환 → 소요시간 재계산
     */
    fun normalize(plan: AIPlanResponse, targets: PlanTargets): NormalizedPlan {
        val notes = mutableListOf<String>()
        val allIds = plan.days.flatMap { d -> d.exercises.map { it.exerciseId } }.toSet()
        val exerciseById = if (allIds.isEmpty()) emptyMap() else exerciseRepository.findAllById(allIds).associateBy { it.id }

        var days = plan.days.map { day ->
            var exercises = day.exercises

            // 1. 특수 집단 고위험 동작 제거
            if (targets.isSpecialPopulation) {
                val (risky, safe) = exercises.partition { isHighRisk(exerciseById[it.exerciseId], it) }
                if (risky.isNotEmpty()) {
                    notes.add("Day ${day.dayNumber}: removed high-risk exercises for ${populationLabel(targets)}: " +
                        risky.joinToString { it.exerciseName })
                    exercises = safe
                }
            }

            // 2. 목표별 반복/휴식/RPE 보정
            exercises = exercises.map { e -> clampToGoal(e, targets, notes, day.dayNumber) }

            // 3. 하루 세트 상한
            exercises = capDailySets(exercises, targets.dailySetCap, notes, day.dayNumber)

            // 4. 세션 시간 ±20% 맞춤
            exercises = fitToDuration(exercises, targets, notes, day.dayNumber)

            day.copy(exercises = exercises.mapIndexed { i, e -> e.copy(order = i + 1) })
        }

        // 5. 주간 세트 상한 (특수 집단)
        days = capWeeklySets(days, targets, notes)

        // 6. 소요시간 재계산
        days = days.map { it.copy(estimatedDurationMinutes = estimateDurationMinutes(it.exercises)) }

        // 7. 미성년 감량 프레임 치환
        val (name, desc, coaching) = if (targets.isMinor) {
            Triple(
                reframeMinorWeightLoss(plan.planName, targets.locale),
                reframeMinorWeightLoss(plan.planDescription, targets.locale),
                plan.coachingNotes?.let { reframeMinorWeightLoss(it, targets.locale) }
            ).also {
                if (it.first != plan.planName || it.second != plan.planDescription || it.third != plan.coachingNotes) {
                    notes.add("Reframed weight-loss wording for a minor into fitness/health wording")
                }
            }
        } else Triple(plan.planName, plan.planDescription, plan.coachingNotes)

        if (notes.isNotEmpty()) {
            log.info("Plan '${plan.planName}' normalized with ${notes.size} adjustments: $notes")
        }
        return NormalizedPlan(
            plan.copy(planName = name, planDescription = desc, coachingNotes = coaching, days = days),
            notes
        )
    }

    private fun populationLabel(t: PlanTargets): String = listOfNotNull(
        "minor".takeIf { t.isMinor }, "elderly".takeIf { t.isElderly },
        "underweight".takeIf { t.isUnderweight }, "obese".takeIf { t.isObese }
    ).joinToString("/")

    /**
     * 특수 집단(미성년·고령·저체중·비만)에게 빼는 동작: 플라이오메트릭/점프, 역도 동작(클린·스내치),
     * 그리고 이름으로 판별되는 폭발적 동작. 데드리프트 자체는 남기되 [clampToGoal] 에서 RPE/반복을 낮춘다.
     */
    fun isHighRisk(exercise: Exercise?, entry: AIPlanExerciseEntry): Boolean {
        val pattern = exercise?.let { exercisePatternClassifier.classifyExercise(it) }
        if (pattern in setOf(
                ExercisePatternClassifier.MovementPattern.PLYOMETRIC,
                ExercisePatternClassifier.MovementPattern.CLEAN,
                ExercisePatternClassifier.MovementPattern.SNATCH
            )
        ) return true
        val name = (exercise?.name ?: entry.exerciseName).lowercase()
        return HIGH_RISK_KEYWORDS.any { name.contains(it) }
    }

    private fun clampToGoal(e: AIPlanExerciseEntry, t: PlanTargets, notes: MutableList<String>, day: Int): AIPlanExerciseEntry {
        val goal = t.primaryGoal
        // 근력 목표라도 고립운동은 6-10 이 정상 범위
        val (lo, hi, dMin, dMax) = if (goal == PlanGoal.STRENGTH && !e.isCompound) {
            Quad(6, 12, 8, 10)
        } else {
            Quad(goal.minReps, goal.maxReps, goal.defaultMin, goal.defaultMax)
        }
        var min = e.minReps
        var max = e.maxReps
        if (min > max) { val tmp = min; min = max; max = tmp }
        val outside = min < lo || max > hi
        if (outside) {
            // 둘 다 범위 밖이면 기본값, 하나만 벗어나면 그것만 잘라낸다
            if ((min < lo && max < lo) || (min > hi && max > hi)) { min = dMin; max = dMax }
            else { min = min.coerceIn(lo, hi); max = max.coerceIn(lo, hi) }
            if (min > max) max = min
            notes.add("Day $day: '${e.exerciseName}' reps ${e.minReps}-${e.maxReps} → $min-$max for goal ${goal.name}")
        }

        var rest = e.restSeconds
        goal.maxRestSeconds?.let { cap ->
            if (rest > cap) { rest = cap; notes.add("Day $day: '${e.exerciseName}' rest ${e.restSeconds}s → ${cap}s for ${goal.name}") }
        }
        if (goal == PlanGoal.STRENGTH && e.isCompound && rest < 120) {
            rest = 120; notes.add("Day $day: '${e.exerciseName}' rest ${e.restSeconds}s → 120s for STRENGTH compound")
        }
        rest = rest.coerceIn(30, 300)

        var rpe = e.targetRPE
        if (t.isSpecialPopulation || t.isBeginner) {
            val cap = if (t.isElderly || t.isMinor) 7.0 else 7.5
            if (rpe > cap) { rpe = cap; notes.add("Day $day: '${e.exerciseName}' RPE ${e.targetRPE} → $cap") }
            // 특수 집단·초보의 최대 근접 저반복 금지: 최소 6회
            if (t.isSpecialPopulation && min < 6) {
                notes.add("Day $day: '${e.exerciseName}' reps $min-$max → 6-${maxOf(max, 8)} (no near-max low reps for ${populationLabel(t)})")
                min = 6; max = maxOf(max, 8)
            }
        }

        var sets = e.sets.coerceIn(1, 6)
        if (sets != e.sets) notes.add("Day $day: '${e.exerciseName}' sets ${e.sets} → $sets")

        return e.copy(minReps = min, maxReps = max, restSeconds = rest, targetRPE = rpe, sets = sets)
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun capDailySets(exercises: List<AIPlanExerciseEntry>, cap: Int, notes: MutableList<String>, day: Int): List<AIPlanExerciseEntry> {
        var list = exercises
        var total = list.sumOf { it.sets }
        if (total <= cap) return list
        val before = total
        // 뒤쪽(고립) 운동부터 세트를 2까지 줄이고, 그래도 넘치면 뒤에서 운동을 뺀다 (최소 3개 유지)
        list = trimSets(list, cap)
        total = list.sumOf { it.sets }
        while (total > cap && list.size > 3) {
            list = list.dropLast(1)
            total = list.sumOf { it.sets }
        }
        notes.add("Day $day: total sets $before → $total (cap $cap)")
        return list
    }

    private fun trimSets(exercises: List<AIPlanExerciseEntry>, targetTotal: Int): List<AIPlanExerciseEntry> {
        val mutable = exercises.toMutableList()
        var total = mutable.sumOf { it.sets }
        var i = mutable.lastIndex
        while (total > targetTotal && i >= 0) {
            val e = mutable[i]
            if (e.sets > 2) {
                mutable[i] = e.copy(sets = e.sets - 1)
                total -= 1
            } else {
                i -= 1
            }
            if (i < 0 && total > targetTotal && mutable.any { it.sets > 2 }) i = mutable.lastIndex
        }
        return mutable
    }

    private fun capWeeklySets(days: List<AIPlanDayEntry>, t: PlanTargets, notes: MutableList<String>): List<AIPlanDayEntry> {
        val cap = t.weeklySetCap
        if (cap == Int.MAX_VALUE) return days
        val total = days.sumOf { d -> d.exercises.sumOf { it.sets } }
        if (total <= cap) return days
        val perDay = cap / maxOf(1, days.size)
        notes.add("Weekly sets $total → ≤$cap for ${populationLabel(t)} (per-day ≤$perDay)")
        return days.map { d ->
            var ex = trimSets(d.exercises, perDay)
            while (ex.sumOf { it.sets } > perDay && ex.size > 3) ex = ex.dropLast(1)
            d.copy(exercises = ex)
        }
    }

    /**
     * 소요시간 = 워밍업 5분 + Σ[세트 × (반복 × 3초 + 휴식)] + 운동당 준비 60초.
     */
    fun estimateDurationMinutes(exercises: List<AIPlanExerciseEntry>): Int {
        if (exercises.isEmpty()) return 0
        val seconds = exercises.sumOf { e ->
            val avgReps = (e.minReps + e.maxReps) / 2.0
            e.sets * (avgReps * TEMPO_SECONDS_PER_REP + e.restSeconds) + SETUP_SECONDS_PER_EXERCISE
        }
        return (WARMUP_MINUTES + seconds / 60.0).toInt()
    }

    private fun fitToDuration(
        exercises: List<AIPlanExerciseEntry>, t: PlanTargets, notes: MutableList<String>, day: Int
    ): List<AIPlanExerciseEntry> {
        val target = t.sessionDurationMinutes
        if (target <= 0 || exercises.isEmpty()) return exercises
        val upper = target * 1.2
        val lower = target * 0.8
        var list = exercises
        val before = estimateDurationMinutes(list)
        var est = before

        // 초과: 휴식을 목표별 최소선까지 줄이고 → 세트를 줄이고 → 뒤에서 운동을 뺀다
        var guard = 0
        while (est > upper && guard++ < 40) {
            val idx = list.indexOfLast { it.sets > 2 }
            list = when {
                idx >= 0 -> list.toMutableList().also { it[idx] = it[idx].copy(sets = it[idx].sets - 1) }
                list.size > 3 -> list.dropLast(1)
                else -> break
            }
            est = estimateDurationMinutes(list)
        }
        // 부족: 복합운동에 세트를 하나씩 더한다 (최대 5), 그래도 부족하면 그대로 둔다
        guard = 0
        while (est < lower && guard++ < 10) {
            val idx = list.indexOfFirst { it.isCompound && it.sets < 5 }.takeIf { it >= 0 }
                ?: list.indexOfFirst { it.sets < 4 }
            if (idx < 0) break
            list = list.toMutableList().also { it[idx] = it[idx].copy(sets = it[idx].sets + 1) }
            est = estimateDurationMinutes(list)
        }
        if (est != before) notes.add("Day $day: estimated ${before}min → ${est}min to fit ${target}min (±20%)")
        return list
    }

    // ───────────────────────────── 검증 (validate) ─────────────────────────────

    fun validate(plan: AIPlanResponse): PlanQualityReport = validate(plan, null)

    fun validate(plan: AIPlanResponse, targets: PlanTargets?): PlanQualityReport {
        val issues = mutableListOf<QualityIssue>()

        // 1. Check each day has exercises
        plan.days.forEach { day ->
            if (day.exercises.isEmpty()) {
                issues.add(QualityIssue(IssueSeverity.ERROR, "Day ${day.dayNumber} (${day.dayName}) has no exercises",
                    "Day ${day.dayNumber} must contain 5-8 exercises."))
            }
        }

        // 2. Check total sets per day (12-35 reasonable range)
        plan.days.forEach { day ->
            val totalSets = day.exercises.sumOf { it.sets }
            if (totalSets < 10) {
                issues.add(QualityIssue(IssueSeverity.WARNING, "Day ${day.dayNumber}: only $totalSets total sets (recommended: 12-25)"))
            }
            if (totalSets > 35) {
                issues.add(QualityIssue(IssueSeverity.WARNING, "Day ${day.dayNumber}: $totalSets total sets is very high (recommended: 12-25)"))
            }
        }

        // 3. Check compound ratio per day (at least 20%)
        plan.days.forEach { day ->
            if (day.exercises.isNotEmpty()) {
                val compoundCount = day.exercises.count { it.isCompound }
                val ratio = compoundCount.toDouble() / day.exercises.size
                if (ratio < 0.2) {
                    issues.add(QualityIssue(IssueSeverity.WARNING, "Day ${day.dayNumber}: compound ratio is ${(ratio * 100).toInt()}% (recommended: >=20%)"))
                }
            }
        }

        // 4. Check exercise count per day (5-8 recommended)
        plan.days.forEach { day ->
            if (day.exercises.size < 3) {
                issues.add(QualityIssue(IssueSeverity.ERROR, "Day ${day.dayNumber}: only ${day.exercises.size} exercises (minimum: 3)",
                    "Day ${day.dayNumber} must have at least 5 distinct exercises that satisfy the equipment and injury constraints."))
            }
            if (day.exercises.size > 10) {
                issues.add(QualityIssue(IssueSeverity.WARNING, "Day ${day.dayNumber}: ${day.exercises.size} exercises is too many (recommended: 5-8)"))
            }
        }

        // 5. Check for duplicate exercises across days (same exercise > 3 times)
        val exerciseFrequency = plan.days.flatMap { it.exercises }
            .groupingBy { it.exerciseId }.eachCount()
        exerciseFrequency.filter { it.value > 3 }.forEach { (id, count) ->
            issues.add(QualityIssue(IssueSeverity.WARNING, "Exercise ID $id appears $count times across all days"))
        }

        // 6. Total days matches
        if (plan.days.size != plan.totalDays) {
            issues.add(QualityIssue(IssueSeverity.ERROR, "Plan says ${plan.totalDays} days but has ${plan.days.size} day definitions",
                "The plan must contain exactly ${plan.totalDays} day entries."))
        }

        // 7. 하루 안의 운동 중복 — 동일 운동 / 동일 동작 패턴 / 후보군 이탈
        val exerciseById = validateWithinDayDuplicates(plan, issues)

        // 8. 주간 균형 + 연속 같은 부위 + 목표 반복 범위 (targets 가 있을 때)
        if (targets != null) {
            validateWeeklyBalance(plan, targets, exerciseById, issues)
            validateGoalRanges(plan, targets, issues)
            validateDuration(plan, targets, issues)
        }

        val isValid = issues.none { it.severity == IssueSeverity.ERROR }
        if (!isValid) {
            log.warn("Plan quality validation FAILED for '${plan.planName}': ${issues.filter { it.severity == IssueSeverity.ERROR }.map { it.message }}")
        }

        return PlanQualityReport(isValid = isValid, issues = issues)
    }

    private fun validateWithinDayDuplicates(plan: AIPlanResponse, issues: MutableList<QualityIssue>): Map<Long, Exercise> {
        val allIds = plan.days.flatMap { day -> day.exercises.map { it.exerciseId } }.toSet()
        if (allIds.isEmpty()) return emptyMap()
        val exerciseById = exerciseRepository.findAllById(allIds).associateBy { it.id }

        plan.days.forEach { day ->
            // 7-a. 같은 날 동일 운동 중복
            day.exercises.groupingBy { it.exerciseId }.eachCount()
                .filter { it.value > 1 }
                .forEach { (id, count) ->
                    val name = exerciseById[id]?.name ?: "id=$id"
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: '$name' appears $count times in the same day",
                        "Never list the same exercise twice in one day."
                    ))
                }

            // 7-b. 같은 날 동일 동작 패턴 중복 (이름만 다른 사실상 같은 운동)
            day.exercises
                .mapNotNull { entry -> exerciseById[entry.exerciseId]?.let { entry to it } }
                .groupBy { (_, ex) -> exercisePatternClassifier.classifyExercise(ex) }
                .filter { (_, group) -> group.size > 1 }
                .forEach { (pattern, group) ->
                    val names = group.joinToString(", ") { (_, ex) -> ex.name }
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: ${group.size} exercises share movement pattern $pattern ($names)",
                        "Do not put two exercises with the same movement pattern ($pattern) in the same day."
                    ))
                }

            // 7-c. 플랜 후보군(isPlanEligible) 밖의 운동 유입
            day.exercises.forEach { entry ->
                val ex = exerciseById[entry.exerciseId]
                if (ex == null) {
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: exercise id ${entry.exerciseId} ('${entry.exerciseName}') does not exist",
                        "Use ONLY exercise IDs from the provided list. '${entry.exerciseName}' (id ${entry.exerciseId}) does not exist."
                    ))
                } else if (!ex.isPlanEligible) {
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: '${ex.name}' is not plan-eligible (tier=${ex.recommendationTier}, popularity=${ex.popularity})",
                        "'${ex.name}' is not in the provided list; pick only from the list."
                    ))
                }
            }
        }
        return exerciseById
    }

    /**
     * 주 3일 이상: 상체·하체·코어가 한 주 안에 모두 등장해야 한다.
     * 연속된 두 날의 workoutType 이 같으면(FULL_BODY 제외) 같은 주동근을 연속으로 치는 것이므로 거부한다.
     */
    private fun validateWeeklyBalance(
        plan: AIPlanResponse, t: PlanTargets, exerciseById: Map<Long, Exercise>, issues: MutableList<QualityIssue>
    ) {
        if (t.weeklyDays >= 3 && plan.days.isNotEmpty()) {
            val categories = plan.days.flatMap { d -> d.exercises.mapNotNull { exerciseById[it.exerciseId]?.category } }.toSet()
            val muscles = plan.days.flatMap { d -> d.exercises.mapNotNull { exerciseById[it.exerciseId] } }
                .flatMap { it.muscleGroups.map { m -> m.name } }.toSet()
            val hasUpper = categories.any { it in UPPER_CATEGORIES }
            val hasLower = ExerciseCategory.LEGS in categories || muscles.any { it in LOWER_MUSCLES }
            val hasCore = ExerciseCategory.CORE in categories || muscles.any { it in setOf("CORE", "ABS") }
            val missing = listOfNotNull("upper body".takeIf { !hasUpper }, "lower body".takeIf { !hasLower }, "core".takeIf { !hasCore })
            if (missing.isNotEmpty()) {
                issues.add(QualityIssue(
                    IssueSeverity.ERROR,
                    "Weekly plan is missing ${missing.joinToString(", ")}",
                    "Across the week the plan must train upper body, lower body AND core (missing: ${missing.joinToString(", ")}). " +
                        "Add at least one core exercise and one lower-body exercise if absent."
                ))
            }
        }
        plan.days.sortedBy { it.dayNumber }.zipWithNext().forEach { (a, b) ->
            val ta = a.workoutType.uppercase(); val tb = b.workoutType.uppercase()
            if (ta == tb && ta != "FULL_BODY") {
                issues.add(QualityIssue(
                    IssueSeverity.ERROR,
                    "Day ${a.dayNumber} and Day ${b.dayNumber} both train $ta on consecutive days",
                    "Do not schedule the same workoutType ($ta) on two consecutive days — the same muscles need recovery. Alternate body parts."
                ))
            }
        }
    }

    private fun validateGoalRanges(plan: AIPlanResponse, t: PlanTargets, issues: MutableList<QualityIssue>) {
        val g = t.primaryGoal
        plan.days.forEach { d ->
            d.exercises.forEach { e ->
                val lo = if (g == PlanGoal.STRENGTH && !e.isCompound) 6 else g.minReps
                val hi = if (g == PlanGoal.STRENGTH && !e.isCompound) 12 else g.maxReps
                if (e.minReps < lo || e.maxReps > hi) {
                    issues.add(QualityIssue(IssueSeverity.WARNING,
                        "Day ${d.dayNumber}: '${e.exerciseName}' reps ${e.minReps}-${e.maxReps} outside ${g.name} range $lo-$hi"))
                }
            }
        }
    }

    private fun validateDuration(plan: AIPlanResponse, t: PlanTargets, issues: MutableList<QualityIssue>) {
        if (t.sessionDurationMinutes <= 0) return
        plan.days.forEach { d ->
            val est = estimateDurationMinutes(d.exercises)
            if (est > t.sessionDurationMinutes * 1.2 || est < t.sessionDurationMinutes * 0.8) {
                issues.add(QualityIssue(IssueSeverity.WARNING,
                    "Day ${d.dayNumber}: estimated ${est}min vs requested ${t.sessionDurationMinutes}min"))
            }
        }
    }

    /**
     * 미성년자 플랜에서 "감량/다이어트" 표현을 "체력·건강" 프레임으로 바꾼다.
     * 성장기에 체중 감량을 목표로 제시하지 않는다.
     */
    fun reframeMinorWeightLoss(text: String, locale: String): String {
        var out = text
        MINOR_REFRAME.forEach { (regex, replacement) -> out = regex.replace(out, replacement) }
        return out
    }

    companion object {
        const val TEMPO_SECONDS_PER_REP = 3.0
        const val SETUP_SECONDS_PER_EXERCISE = 60.0
        const val WARMUP_MINUTES = 5.0

        private val UPPER_CATEGORIES = setOf(
            ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS
        )
        private val LOWER_MUSCLES = setOf("LEGS", "QUADRICEPS", "HAMSTRINGS", "GLUTES", "CALVES")

        private val HIGH_RISK_KEYWORDS = listOf(
            "jump", "plyo", "burpee", "box jump", "depth drop", "sprint", "clean", "snatch", "jerk",
            "점프", "버피", "클린", "스내치", "ジャンプ", "バーピー", "クリーン", "スナッチ"
        )

        /** (정규식, 치환) — 한국어/영어/일본어 감량 표현 */
        private val MINOR_REFRAME: List<Pair<Regex, String>> = listOf(
            Regex("체중\\s*감량|체지방\\s*감량|체지방\\s*감소|살\\s*빼기|살을\\s*빼는|살\\s*빼는|감량|다이어트|지방\\s*연소") to "체력·건강 향상",
            Regex("(?i)weight[\\s-]*loss|fat[\\s-]*loss|lose\\s+weight|burn\\s+fat|fat[\\s-]*burning|slim(?:ming)?\\s+down") to "fitness and health",
            Regex("体重減少|減量|ダイエット|脂肪燃焼|痩せる") to "体力・健康づくり"
        )
    }
}

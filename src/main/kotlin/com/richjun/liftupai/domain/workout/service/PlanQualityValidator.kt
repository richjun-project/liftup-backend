package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

enum class IssueSeverity { WARNING, ERROR }

data class QualityIssue(
    val severity: IssueSeverity,
    val message: String
)

data class PlanQualityReport(
    val isValid: Boolean,
    val issues: List<QualityIssue>
)

@Service
class PlanQualityValidator(
    private val exerciseRepository: ExerciseRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier
) {
    private val log = LoggerFactory.getLogger(PlanQualityValidator::class.java)

    fun validate(plan: AIPlanResponse): PlanQualityReport {
        val issues = mutableListOf<QualityIssue>()

        // 1. Check each day has exercises
        plan.days.forEach { day ->
            if (day.exercises.isEmpty()) {
                issues.add(QualityIssue(IssueSeverity.ERROR, "Day ${day.dayNumber} (${day.dayName}) has no exercises"))
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
                issues.add(QualityIssue(IssueSeverity.ERROR, "Day ${day.dayNumber}: only ${day.exercises.size} exercises (minimum: 3)"))
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
            issues.add(QualityIssue(IssueSeverity.ERROR, "Plan says ${plan.totalDays} days but has ${plan.days.size} day definitions"))
        }

        // 7. 하루 안의 운동 중복 — 동일 운동 / 동일 동작 패턴 / 후보군 이탈
        //    프로덕션에서 하루에 어시스트 풀업 6종, 45도 하이퍼익스텐션 3종이 배치된 사례가 있었고
        //    기존 검사(5번)는 전체 일수 기준 3회 초과만 봤기 때문에 전혀 걸러지지 않았다.
        validateWithinDayDuplicates(plan, issues)

        val isValid = issues.none { it.severity == IssueSeverity.ERROR }
        if (!isValid) {
            log.warn("Plan quality validation FAILED for '${plan.planName}': ${issues.filter { it.severity == IssueSeverity.ERROR }.map { it.message }}")
        }

        return PlanQualityReport(isValid = isValid, issues = issues)
    }

    private fun validateWithinDayDuplicates(plan: AIPlanResponse, issues: MutableList<QualityIssue>) {
        val allIds = plan.days.flatMap { day -> day.exercises.map { it.exerciseId } }.toSet()
        if (allIds.isEmpty()) return
        val exerciseById = exerciseRepository.findAllById(allIds).associateBy { it.id }

        plan.days.forEach { day ->
            // 7-a. 같은 날 동일 운동 중복
            day.exercises.groupingBy { it.exerciseId }.eachCount()
                .filter { it.value > 1 }
                .forEach { (id, count) ->
                    val name = exerciseById[id]?.name ?: "id=$id"
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: '$name' appears $count times in the same day"
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
                        "Day ${day.dayNumber}: ${group.size} exercises share movement pattern $pattern ($names)"
                    ))
                }

            // 7-c. 플랜 후보군(isPlanEligible) 밖의 운동 유입
            day.exercises.forEach { entry ->
                val ex = exerciseById[entry.exerciseId]
                if (ex == null) {
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: exercise id ${entry.exerciseId} ('${entry.exerciseName}') does not exist"
                    ))
                } else if (!ex.isPlanEligible) {
                    issues.add(QualityIssue(
                        IssueSeverity.ERROR,
                        "Day ${day.dayNumber}: '${ex.name}' is not plan-eligible (tier=${ex.recommendationTier}, popularity=${ex.popularity})"
                    ))
                }
            }
        }
    }
}

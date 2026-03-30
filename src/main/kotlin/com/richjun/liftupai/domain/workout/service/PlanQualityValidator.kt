package com.richjun.liftupai.domain.workout.service

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
class PlanQualityValidator {
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

        val isValid = issues.none { it.severity == IssueSeverity.ERROR }
        if (!isValid) {
            log.warn("Plan quality validation FAILED for '${plan.planName}': ${issues.filter { it.severity == IssueSeverity.ERROR }.map { it.message }}")
        }

        return PlanQualityReport(isValid = isValid, issues = issues)
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.util.WorkoutLocalization

/**
 * 추천 이유 문장(ko/en/ja). 추천기가 실제로 반영한 사실만 말한다 — 부상을 피했다고 쓰려면 정말 피한 것이 있어야 한다.
 * 문구는 workout_messages*.properties 의 template.reco.* 에 있다.
 */
object TemplateRecoReason {

    fun compose(
        outcome: TemplateRecommendationEngine.Outcome,
        profile: TemplateRecommendationEngine.RecoProfile,
        locale: String,
    ): String {
        val t = outcome.best.template
        val name = WorkoutLocalization.maybeMessage("template.${t.code}.name", locale) ?: t.code
        val parts = mutableListOf<String>()

        // 1) 목표/수준 한 문장
        parts += WorkoutLocalization.message(goalKey(t, profile), locale)

        // 2) 일수
        parts += if (outcome.best.dayGap == 0) {
            WorkoutLocalization.message("template.reco.days.exact", locale, profile.weeklyDays)
        } else {
            WorkoutLocalization.message("template.reco.days.near", locale, profile.weeklyDays, t.totalDays)
        }

        // 3) 장비 또는 부상 — 실제로 반영된 것을 하나
        val injuredParts = profile.injuries.filter { it in KNOWN_INJURIES }
        when {
            injuredParts.isNotEmpty() && outcome.best.skippedForInjury > 0 -> parts += WorkoutLocalization.message(
                "template.reco.injury", locale,
                injuredParts.joinToString(", ") { WorkoutLocalization.message("template.reco.injury.$it", locale) }
            )
            isHomeTemplate(t) -> parts += WorkoutLocalization.message(
                if (t.days.flatMap { it.exercises }.all { it.exercise.equipment?.name == "BODYWEIGHT" || it.exercise.equipment == null }) "template.reco.equipment.bodyweight"
                else "template.reco.equipment.home",
                locale
            )
            profile.equipment.isNotEmpty() && outcome.best.survival >= 0.85 -> parts += WorkoutLocalization.message("template.reco.equipment.fit", locale)
        }

        // 4) 집중 부위 — 템플릿이 이미 다루거나, 적용 시 볼륨을 더한 부위
        if (profile.focus.isNotEmpty()) {
            val labels = profile.focus.joinToString(", ") { WorkoutLocalization.message("template.reco.focus.${it.name.lowercase()}", locale) }
            val added = outcome.adapted.any { d -> d.exercises.any { it.origin == TemplatePlanAdapter.Origin.FOCUS } }
            parts += WorkoutLocalization.message(if (added) "template.reco.focus.added" else "template.reco.focus.natural", locale, labels)
        }

        return WorkoutLocalization.message("template.reco.reason", locale, name, parts.take(4).joinToString(" "))
    }

    private fun goalKey(t: TemplateRecommendationEngine.RecoTemplate, p: TemplateRecommendationEngine.RecoProfile): String {
        val goals = p.goals.map { it.lowercase() }
        return when {
            t.targetExperience == "beginner" && TemplateRecommendationEngine.rankOf(p.experience) == 0 && goals.any { it == "weight_loss" } -> "template.reco.goal.beginner_loss"
            TemplateRecommendationEngine.rankOf(p.experience) == 0 -> "template.reco.goal.beginner"
            t.targetGoal == "weight_loss" -> "template.reco.goal.loss"
            t.targetGoal == "strength" -> "template.reco.goal.strength"
            t.targetGoal == "general_fitness" -> "template.reco.goal.general"
            else -> "template.reco.goal.muscle"
        }
    }

    /** 바벨·머신·케이블을 쓰지 않는 홈트 템플릿인가 */
    private fun isHomeTemplate(t: TemplateRecommendationEngine.RecoTemplate): Boolean {
        val all = t.days.flatMap { it.exercises }
        if (all.isEmpty()) return false
        return all.none { it.exercise.equipment?.name in setOf("BARBELL", "MACHINE", "CABLE") }
    }

    private val KNOWN_INJURIES = setOf("shoulder", "knee", "lower_back", "hip", "wrist", "elbow")
}

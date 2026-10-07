package com.richjun.liftupai.domain.workout.service

/**
 * 온보딩 답변으로 프리셋 템플릿을 고르는 순수 로직 (DB·Spring 비의존).
 *
 * 예전에는 앱이 "weight_loss 가 하나라도 있으면 diet_circuit" 으로 골랐다. 실제 사용자는 목표를 4~5개 고르고,
 * diet_circuit 은 바벨·머신 중심 중급용이라 홈트 초보자에게 그대로 나갔다(2026-10-06 일본 사용자 사고).
 * 여기서는 모든 템플릿을 사람 기준으로 채점한다.
 *
 *  - 경험: 사람의 수준보다 높은 템플릿은 제외(초보·노비스는 초급 템플릿만)
 *  - 일수·시간: 주 운동일수와 회당 시간에 얼마나 맞나
 *  - 장비·부상: TemplatePlanAdapter 로 실제 적용을 시뮬레이션해 원래 운동이 얼마나 살아남나(= 실제 플랜의 품질)
 *  - 목표: 여러 목표의 가중 혼합(weight_loss 는 덮어쓰기가 아니라 한 표)
 *  - 집중 부위: 템플릿이 자연스럽게 그 부위를 다루나 (모자란 볼륨은 적용 시 어댑터가 더한다)
 */
class TemplateRecommendationEngine(
    private val templates: List<RecoTemplate>,
    private val adapter: TemplatePlanAdapter,
) {

    data class RecoDay(
        val dayNumber: Int,
        val workoutType: String,
        val estimatedMinutes: Int,
        val exercises: List<TemplatePlanAdapter.SourceExercise>,
    )

    data class RecoTemplate(
        val code: String,
        val targetGoal: String,
        val targetExperience: String,
        val totalDays: Int,
        val sortOrder: Int,
        val days: List<RecoDay>,
    )

    data class RecoProfile(
        val experience: String,
        val goals: List<String>,
        val weeklyDays: Int,
        val sessionDuration: Int,
        /** Equipment enum 이름(BARBELL …). 비어 있으면 제한 없음 */
        val equipment: Set<String>,
        val injuries: Set<String>,
        val focus: List<FocusArea>,
        /** 나이. 미성년·고령이면 이 사람에게 맞지 않는 동작(철봉·폭발적 동작·바벨)을 템플릿에서 걸러 낸다 */
        val age: Int? = null,
    )

    data class Scored(
        val template: RecoTemplate,
        val total: Double,
        /** 장비·부상 필터 후 템플릿 원래 운동이 살아남은 비율(0..1) */
        val survival: Double,
        val skippedForInjury: Int,
        val skippedForEquipment: Int,
        val minExercisesPerDay: Int,
        /** 집중 볼륨을 더하기 전, 템플릿이 이미 담고 있는 집중 부위 운동 수 */
        val naturalFocusHits: Map<FocusArea, Int>,
        val dayGap: Int,
        /** 점수 구성(디버깅·튜닝용): level/days/duration/equipment/gym/goal/focus */
        val breakdown: Map<String, Double> = emptyMap(),
    )

    data class Outcome(
        val best: Scored,
        /** 점수 순(최고 먼저). 경험 수준을 넘는 템플릿은 들어가지 않는다. */
        val ranked: List<Scored>,
        /** 최고 템플릿을 이 사람에게 실제로 적용한 결과(집중 볼륨 포함) */
        val adapted: List<TemplatePlanAdapter.AdaptedDay>,
    )

    fun recommend(profile: RecoProfile): Outcome {
        val personRank = rankOf(profile.experience)
        val eligible = templates.filter { rankOf(it.targetExperience) <= personRank }
            .ifEmpty { templates.filter { rankOf(it.targetExperience) == templates.minOf { t -> rankOf(t.targetExperience) } } }
        val scored = eligible.map { score(it, profile, personRank) }
            .sortedWith(compareByDescending<Scored> { it.total }.thenBy { it.template.sortOrder })
        val best = scored.first()
        return Outcome(best, scored, adaptWithFocus(best.template, profile))
    }

    /** 템플릿 하나를 이 사람에게 적용한 결과(집중 볼륨 포함). 추천 후 실제 플랜과 같다. */
    fun adaptWithFocus(template: RecoTemplate, profile: RecoProfile): List<TemplatePlanAdapter.AdaptedDay> =
        adapter.adapt(template.days.map { it.toSource() }, context(profile, withFocus = true))

    private fun context(profile: RecoProfile, withFocus: Boolean): TemplatePlanAdapter.Context {
        val level = RecoRules.parseLevel(profile.experience) ?: com.richjun.liftupai.domain.user.entity.ExperienceLevel.BEGINNER
        return TemplatePlanAdapter.Context(
            allowedEquipment = profile.equipment,
            injuries = profile.injuries,
            focus = if (withFocus) profile.focus else emptyList(),
            maxDifficulty = TemplatePlanAdapter.maxDifficultyFor(profile.experience),
            sessionMinutes = profile.sessionDuration,
            suitable = RecoRules.suitability(profile.equipment, level, profile.age),
            level = level,
            age = profile.age,
            repeatCap = TemplatePlanAdapter.repeatCapFor(profile.equipment, profile.injuries),
        )
    }

    private fun RecoDay.computedMinutes(): Int =
        if (exercises.isEmpty()) estimatedMinutes else SessionFit.minutes(exercises.map { TemplatePlanAdapter.slotOf(it, null, null, false) })

    private fun RecoDay.toSource() = TemplatePlanAdapter.SourceDay(dayNumber, workoutType, exercises)

    private fun score(t: RecoTemplate, p: RecoProfile, personRank: Int): Scored {
        // 장비·부상 시뮬레이션(집중 볼륨 전): 원래 운동이 얼마나 살아남고, 빈 날을 채운 뒤 하루 몇 개가 되나
        val adapted = adapter.adapt(t.days.map { it.toSource() }, context(p, withFocus = false))
        val originalTotal = t.days.sumOf { it.exercises.size }.coerceAtLeast(1)
        val kept = adapted.sumOf { d -> d.exercises.count { it.origin == TemplatePlanAdapter.Origin.TEMPLATE } }
        val survival = kept.toDouble() / originalTotal
        val minPerDay = adapted.minOfOrNull { it.exercises.size } ?: 0
        val natural = p.focus.associateWith { area -> adapted.sumOf { d -> d.exercises.count { area.hits(it.exercise) } } }

        val parts = linkedMapOf<String, Double>()
        // 경험: 같은 수준이면 만점. 낮은 수준 템플릿은 안전하지만 자극이 모자라니 단계마다 크게 감점
        parts["level"] = when (personRank - rankOf(t.targetExperience)) {
            0 -> 10.0
            1 -> -6.0
            else -> -14.0
        }
        // 일수: 템플릿이 사람의 가능 일수보다 많으면 못 채우니 더 크게 감점
        val dayGap = t.totalDays - p.weeklyDays
        parts["days"] = when {
            dayGap == 0 -> 25.0
            dayGap == -1 -> 15.0
            dayGap == 1 -> 11.0
            dayGap == -2 -> -4.0
            dayGap == 2 -> -12.0
            else -> -30.0
        }
        // 시간: 회당 시간을 넘기면 감점, 많이 남아도 소폭 감점
        // 템플릿에 적힌 분(예: 50)이 아니라 운동 구성이 실제로 걸리는 시간 — 적힌 분은 실제보다 평균 25% 부풀려져 있었다
        val avgMinutes = t.days.map { it.computedMinutes() }.average()
        val over = avgMinutes - p.sessionDuration
        // 짧은 세션(30분 이하)에 긴 템플릿은 세트·종목이 잘려 나가 별로다 — 더 크게 감점한다
        parts["duration"] = when {
            over > 0 -> -minOf(if (p.sessionDuration <= 30) 24.0 else 14.0, over * if (p.sessionDuration <= 30) 1.4 else 0.6)
            over < -30 -> -minOf(6.0, (-over - 30) * 0.2)
            else -> 0.0
        }
        // 장비·부상: 살아남은 비율이 곧 플랜 품질. 하루가 너무 헐거워지면 추가 감점
        parts["equipment"] = 30.0 * survival - (adapted.count { it.exercises.size < MIN_COMFORTABLE_PER_DAY }) * 6.0
        // 기구 활용: 바벨·머신·케이블을 쓸 수 있는 사람에게 맨몸·덤벨뿐인 홈트 템플릿은 낭비다(수준이 높을수록 크게)
        parts["gym"] = if (hasGymAccess(p.equipment) && gymFraction(t) < 0.2) -6.0 * (1 + personRank) else 0.0
        // 목표 혼합
        parts["goal"] = 15.0 * goalFit(t.targetGoal, p.goals)
        // 집중 부위: 템플릿이 이미 그 부위를 충분히 다루나(우선순위가 높은 부위일수록 가중)
        parts["focus"] = if (p.focus.isEmpty()) 0.0 else {
            val weights = listOf(1.0, 0.7, 0.5)
            val planSize = adapted.sumOf { it.exercises.size }.coerceAtLeast(1)
            // 집중 볼륨을 더한 실제 적용 결과 — 이 템플릿 구조에서 집중 부위 목표 개수를 채울 수 있나(밀기 날이 하루뿐인 하체 템플릿으로는 가슴 집중을 못 채운다)
            val applied = adaptWithFocus(t, p)
            val need = TemplatePlanAdapter.focusTargetPerWeek(applied.size)
            var natNum = 0.0
            var achNum = 0.0
            var den = 0.0
            p.focus.forEachIndexed { i, area ->
                val w = weights.getOrElse(i) { 0.4 }
                // 주간 운동 중 그 부위 비중 — 해당 부위 전용 템플릿(하체·둔근 집중 등)이 전신 템플릿보다 앞서게 한다
                natNum += w * minOf(1.0, (natural[area] ?: 0).toDouble() / planSize / FOCUS_FULL_SHARE)
                val hits = applied.sumOf { d -> d.exercises.count { area.hits(it.exercise) } }
                achNum += w * minOf(1.0, hits.toDouble() / need)
                den += w
            }
            14.0 * (natNum / den) + 8.0 * (achNum / den)
        }
        // 전문화 불일치: 한 부위가 절반 이상을 차지하는 전용 템플릿(하체·둔근 집중 등)인데 그 부위가 사용자의 집중 부위가 아니면 감점.
        // 가슴·팔 집중 사용자에게 하체 전용 템플릿이 점수 근소 차이로 뽑히던 것을 막는다.
        if (p.focus.isNotEmpty()) {
            val planSize = adapted.sumOf { it.exercises.size }.coerceAtLeast(1)
            val dominant = FocusArea.values().filter { area ->
                adapted.sumOf { d -> d.exercises.count { area.hits(it.exercise) } }.toDouble() / planSize >= DOMINANT_SHARE
            }
            if (dominant.isNotEmpty() && dominant.none { it in p.focus }) parts["mismatch"] = -8.0
        }
        val total = parts.values.sum()
        return Scored(
            template = t,
            total = total,
            survival = survival,
            skippedForInjury = adapted.sumOf { it.skippedForInjury },
            skippedForEquipment = adapted.sumOf { it.skippedForEquipment },
            minExercisesPerDay = minPerDay,
            naturalFocusHits = natural,
            dayGap = dayGap,
            breakdown = parts,
        )
    }

    private fun hasGymAccess(equipment: Set<String>) = equipment.isEmpty() || equipment.any { it in GYM_EQUIPMENT }

    /** 템플릿 운동 중 바벨·머신·케이블 비중 */
    private fun gymFraction(t: RecoTemplate): Double {
        val all = t.days.flatMap { it.exercises }
        if (all.isEmpty()) return 0.0
        return all.count { it.exercise.equipment?.name in GYM_EQUIPMENT }.toDouble() / all.size
    }

    // ── 목표 ──────────────────────────────────────────────────────

    /** 목표 → 4개 성향(근비대·감량·근력·일반) 가중. 세부 부위 목표는 근비대 쪽 */
    private fun goalVector(goal: String): Map<String, Double> = when (goal.trim().lowercase().replace('-', '_')) {
        "bulk_up" -> mapOf(MUSCLE to 1.0)
        "lean_mass" -> mapOf(MUSCLE to 0.8, LOSS to 0.2)
        "weight_loss" -> mapOf(LOSS to 1.0)
        "strength" -> mapOf(STRENGTH to 1.0)
        "endurance" -> mapOf(LOSS to 0.5, GENERAL to 0.5)
        "thick_chest", "wide_back", "wide_shoulders", "big_arms", "thick_legs", "glutes" -> mapOf(MUSCLE to 0.8, GENERAL to 0.2)
        "toned_arms" -> mapOf(MUSCLE to 0.4, LOSS to 0.3, GENERAL to 0.3)
        "slim_legs" -> mapOf(LOSS to 0.5, GENERAL to 0.3, MUSCLE to 0.2)
        "six_pack" -> mapOf(LOSS to 0.7, GENERAL to 0.3)
        "core_strength" -> mapOf(STRENGTH to 0.4, GENERAL to 0.6)
        "athletic_performance" -> mapOf(STRENGTH to 0.5, GENERAL to 0.5)
        "posture", "flexibility", "rehabilitation", "general_fitness" -> mapOf(GENERAL to 1.0)
        else -> mapOf(GENERAL to 1.0)
    }

    private fun goalFit(templateGoal: String, goals: List<String>): Double {
        if (goals.isEmpty()) return 0.5
        val dist = HashMap<String, Double>()
        goals.forEach { g -> goalVector(g).forEach { (k, v) -> dist.merge(k, v, Double::plus) } }
        val sum = dist.values.sum().takeIf { it > 0 } ?: return 0.5
        val t = templateGoal.lowercase()
        return dist.entries.sumOf { (bucket, w) -> (w / sum) * compat(t, bucket) }
    }

    private fun compat(templateGoal: String, bucket: String): Double {
        if (templateGoal == bucket) return 1.0
        val pair = setOf(templateGoal, bucket)
        return when {
            pair == setOf(MUSCLE, STRENGTH) -> 0.6
            pair == setOf(LOSS, GENERAL) -> 0.6
            pair == setOf(MUSCLE, GENERAL) -> 0.5
            pair == setOf(STRENGTH, GENERAL) -> 0.4
            pair == setOf(LOSS, MUSCLE) -> 0.35
            else -> 0.2
        }
    }

    companion object {
        private const val MUSCLE = "muscle_gain"
        private const val LOSS = "weight_loss"
        private const val STRENGTH = "strength"
        private const val GENERAL = "general_fitness"
        private const val MIN_COMFORTABLE_PER_DAY = 4
        private const val FOCUS_FULL_SHARE = 0.45
        /** 한 부위가 이 비율 이상이면 그 부위 전용 템플릿으로 본다 */
        private const val DOMINANT_SHARE = 0.5
        private val GYM_EQUIPMENT = setOf("BARBELL", "MACHINE", "CABLE")

        /** 경험 → 순위. 초보·노비스 0, 중급 1, 고급·전문가 2 */
        fun rankOf(experience: String?): Int = when (experience?.trim()?.lowercase()) {
            "intermediate" -> 1
            "advanced", "expert" -> 2
            else -> 0
        }
    }
}

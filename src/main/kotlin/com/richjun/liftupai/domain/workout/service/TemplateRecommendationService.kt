package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest
import com.richjun.liftupai.domain.workout.dto.response.TemplateRecommendationResponse
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import com.richjun.liftupai.domain.workout.repository.TemplateDayExerciseRepository
import com.richjun.liftupai.domain.workout.repository.TemplateDayRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutPlanTemplateRepository
import com.richjun.liftupai.domain.workout.search.ExerciseCatalogChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * POST /api/v2/templates/recommend — 온보딩 답변에 맞는 프리셋 템플릿을 고른다.
 * 템플릿 구조와 플랜 후보 풀은 사실상 정적이라 메모리에 잠깐 캐시하고, 사람마다 달라지는 부상·장비 필터만 요청마다 적용한다.
 */
@Service
class TemplateRecommendationService(
    private val templateRepository: WorkoutPlanTemplateRepository,
    private val templateDayRepository: TemplateDayRepository,
    private val templateDayExerciseRepository: TemplateDayExerciseRepository,
    private val catalogCache: ExerciseCatalogCache,
    private val injuryFilterService: InjuryFilterService,
    private val userSettingsRepository: UserSettingsRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    private val log = LoggerFactory.getLogger(TemplateRecommendationService::class.java)

    private class Snapshot(
        val templates: List<TemplateRecommendationEngine.RecoTemplate>,
        val builtAtMillis: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null
    private val lock = Any()

    @EventListener(ExerciseCatalogChangedEvent::class)
    fun invalidate() {
        snapshot = null
    }

    @Transactional(readOnly = true)
    fun recommend(user: User, request: RecommendTemplateRequest, localeOverride: String? = null): TemplateRecommendationResponse {
        val locale = resolveLocale(user, request, localeOverride)
        val profile = toProfile(request)
        val snap = snapshot()
        val pool = catalogCache.get().planPool
        val adapter = TemplatePlanAdapter(
            poolByCategory = { pool[it].orEmpty() },
            isRestricted = injuryFilterService.restrictionChecker(profile.injuries),
        )
        val outcome = TemplateRecommendationEngine(snap.templates, adapter).recommend(profile)
        val alternatives = outcome.ranked.drop(1).take(MAX_ALTERNATIVES).map { it.template.code }
        log.info(
            "Template recommended for user {}: {} (score={}, survival={}, days={}/{}, injuries={}, focus={})",
            user.id, outcome.best.template.code, "%.1f".format(outcome.best.total), "%.2f".format(outcome.best.survival),
            outcome.best.template.totalDays, profile.weeklyDays, profile.injuries, profile.focus
        )
        return TemplateRecommendationResponse(
            templateCode = outcome.best.template.code,
            reason = TemplateRecoReason.compose(outcome, profile, locale),
            alternatives = alternatives,
        )
    }

    internal fun toProfile(request: RecommendTemplateRequest): TemplateRecommendationEngine.RecoProfile {
        val goals = request.goals.orEmpty().map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }.filter { it.isNotEmpty() }
        return TemplateRecommendationEngine.RecoProfile(
            experience = request.experienceLevel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "beginner",
            goals = goals,
            weeklyDays = (request.weeklyDays ?: DEFAULT_DAYS).coerceIn(1, 7),
            sessionDuration = (request.sessionDuration ?: DEFAULT_MINUTES).coerceIn(10, 240),
            // 장비 표기는 applyTemplate 과 같은 규칙(BARBELL 등 enum 이름)으로 맞춘다
            equipment = request.equipment.orEmpty()
                .map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }.filter { it.isNotEmpty() }.toSet(),
            injuries = request.injuries.orEmpty()
                .map { it.substringBefore(':').trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }.toSet(),
            focus = FocusArea.resolve(goals, request.focusAreas),
        )
    }

    private fun resolveLocale(user: User, request: RecommendTemplateRequest, override: String?): String {
        val raw = override?.takeIf { it.isNotBlank() } ?: request.locale?.takeIf { it.isNotBlank() }
            ?: userSettingsRepository.findByUser_Id(user.id).orElse(null)?.language
        val normalized = raw?.trim()?.lowercase()?.replace('_', '-').orEmpty()
        return when {
            normalized.startsWith("ko") -> "ko"
            normalized.startsWith("ja") -> "ja"
            else -> "en"
        }
    }

    private fun snapshot(): Snapshot {
        val current = snapshot
        if (current != null && System.currentTimeMillis() - current.builtAtMillis < TTL_MILLIS) return current
        synchronized(lock) {
            val again = snapshot
            if (again != null && System.currentTimeMillis() - again.builtAtMillis < TTL_MILLIS) return again
            return build().also { snapshot = it }
        }
    }

    private fun build(): Snapshot {
        val byId = catalogCache.get().byId
        val templates = templateRepository.findByIsActiveTrueAndSourceTypeOrderBySortOrder(PlanSourceType.PRESET).map { t ->
            val days = templateDayRepository.findByTemplateIdOrderByDayNumber(t.id).map { d ->
                val items = templateDayExerciseRepository.findByTemplateDayIdOrderByOrderInDay(d.id).mapNotNull { te ->
                    byId[te.exercise.id]?.let { ex ->
                        TemplatePlanAdapter.SourceExercise(ex, te.sets, te.minReps, te.maxReps, te.restSeconds, te.isCompound, te.targetRPE, te.notes)
                    }
                }
                TemplateRecommendationEngine.RecoDay(d.dayNumber, d.workoutType.name, d.estimatedDurationMinutes, items)
            }
            TemplateRecommendationEngine.RecoTemplate(
                code = t.code, targetGoal = t.targetGoal, targetExperience = t.targetExperience,
                totalDays = t.totalDays, sortOrder = t.sortOrder, days = days,
            )
        }.filter { it.days.isNotEmpty() }
        log.info("Template recommendation snapshot built: {} templates", templates.size)
        return Snapshot(templates, System.currentTimeMillis())
    }

    companion object {
        private const val TTL_MILLIS = 5 * 60 * 1000L
        private const val MAX_ALTERNATIVES = 3
        private const val DEFAULT_DAYS = 3
        private const val DEFAULT_MINUTES = 45
    }
}

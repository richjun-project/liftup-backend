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
    /** 장비·부상·경험·나이·세션 시간의 단일 출처 — recommend 와 apply 가 같은 사람을 같게 읽는다 */
    private val trainingContext: UserTrainingContextResolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository),
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
        val ctx = trainingContext.resolve(user.id, overridesOf(request))
        val locale = resolveLocale(ctx, localeOverride)
        val profile = toProfile(request, ctx)
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

    internal fun overridesOf(request: RecommendTemplateRequest) = UserTrainingContextResolver.Overrides(
        equipment = request.equipment, injuries = request.injuries, experience = request.experienceLevel,
        focusGoals = null, sessionMinutes = request.sessionDuration, age = request.age, gender = request.gender, locale = request.locale,
    )

    /** 요청만으로 만든 프로필(저장값 없음). 테스트·비로그인 호출용 */
    internal fun toProfile(request: RecommendTemplateRequest): TemplateRecommendationEngine.RecoProfile =
        toProfile(request, UserTrainingContextResolver.of(null, null, overridesOf(request)))

    /** 요청 + 저장된 온보딩 값(장비는 요청 우선, 부상은 합집합, 나이·경험·시간은 요청 우선)으로 만든 프로필 */
    internal fun toProfile(request: RecommendTemplateRequest, ctx: UserTrainingContextResolver.Context): TemplateRecommendationEngine.RecoProfile {
        val goals = request.goals.orEmpty().map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }.filter { it.isNotEmpty() }
        // 집중 부위: 요청(goals·focus_areas) → 저장된 집중 목표. applyTemplate 과 같은 순서
        val requested = FocusArea.resolve(goals, request.focusAreas)
        val focus = requested.ifEmpty { ctx.focusAreas }
        return TemplateRecommendationEngine.RecoProfile(
            experience = ctx.experience ?: "beginner",
            goals = goals,
            weeklyDays = (request.weeklyDays ?: DEFAULT_DAYS).coerceIn(1, 7),
            sessionDuration = ctx.sessionMinutes ?: DEFAULT_MINUTES,
            equipment = ctx.equipment,
            injuries = ctx.injuries,
            focus = focus,
            age = ctx.age,
        )
    }

    private fun resolveLocale(ctx: UserTrainingContextResolver.Context, override: String?): String =
        UserTrainingContextResolver.normalizeLanguage(override?.takeIf { it.isNotBlank() } ?: ctx.language)

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

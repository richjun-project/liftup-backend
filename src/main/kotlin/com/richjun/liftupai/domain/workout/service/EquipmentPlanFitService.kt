package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 저장된 보유 장비가 바뀐 뒤 활성 플랜을 그 장비에 맞춘다 — 트레이너 도구(set_available_equipment)와
 * 앱 '운동 플랜 설정'의 장비 저장(PUT /api/users/profile)이 같은 규칙을 쓴다.
 *
 *  - 플랜이 이미 장비에 맞으면 아무것도 안 한다.
 *  - 템플릿 플랜인데 종목 절반 넘게를 못 하게 됐으면(헬스장 플랜 → 맨몸) 그 플랜은 이 사람에게 맞지 않는 플랜이다 →
 *    새 장비로 템플릿을 다시 추천해 적용한다. 제자리 교체만 하면 '당기기(등·이두)' 날이 맨몸 복근 운동으로 채워진다
 *    (맨몸 등 운동은 카탈로그에 거의 없다 — 2026-11-14 확인).
 *  - 그 밖(일부만 바뀜, AI·직접 만든 플랜)은 못 하는 종목만 같은 부위의 할 수 있는 종목으로 바꾼다(플랜·진행 Day 유지).
 */
@Service
class EquipmentPlanFitService(
    private val userPlanService: UserPlanService,
    private val templateRecommendationService: TemplateRecommendationService,
    private val userProfileRepository: UserProfileRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Mode { NO_PLAN, UNCHANGED, REFIT, NEW_TEMPLATE }

    data class Fit(
        val mode: Mode,
        val daysChanged: List<Int> = emptyList(),
        val exercisesReplaced: Int = 0,
        /** NEW_TEMPLATE 일 때 새로 적용한 템플릿 코드 */
        val templateCode: String? = null,
        val previousTemplateCode: String? = null,
    ) {
        val planChanged: Boolean get() = mode == Mode.REFIT || mode == Mode.NEW_TEMPLATE
    }

    /**
     * 템플릿 플랜이 지금 저장된 장비와 안 맞으면(장비를 플랜 적용 뒤에 바꿨는데 플랜이 안 따라온 경우) 맞춘다.
     * 템플릿 적용은 언제나 저장된 장비로 거르므로, 템플릿 플랜에 못 하는 종목이 있다는 건 장비가 나중에 바뀌었다는 뜻이다.
     * 직접 만든·AI 플랜은 사용자가 고른 종목일 수 있어 건드리지 않는다. 앱을 열 때의 설정 동기화에서 부른다
     * (2026-11-14 배포 전에 '운동 플랜 설정'에서 장비를 바꾼 304 같은 사용자를 다음 실행 때 바로잡는다).
     */
    @Transactional
    fun fitIfStaleTemplatePlan(user: User, locale: String): Fit? {
        val plan = userPlanService.getActivePlan(user.id) ?: return null
        if (plan.sourceType != PlanSourceType.TEMPLATE) return null
        val preview = userPlanService.refitActivePlanToEquipment(user.id, dryRun = true) ?: return null
        if (preview.daysChanged.isEmpty()) return null
        log.info("User {} template plan {} ({}) has exercises needing equipment they no longer have ({}/{}) — fitting on app open",
            user.id, plan.id, plan.sourceId, preview.removed, preview.totalBefore)
        return fitToCurrentEquipment(user, locale)
    }

    @Transactional
    fun fitToCurrentEquipment(user: User, locale: String): Fit {
        val plan = userPlanService.getActivePlan(user.id) ?: return Fit(Mode.NO_PLAN)
        val preview = userPlanService.refitActivePlanToEquipment(user.id, dryRun = true) ?: return Fit(Mode.NO_PLAN)
        if (preview.daysChanged.isEmpty()) return Fit(Mode.UNCHANGED)

        val mostlyUnusable = preview.totalBefore > 0 && preview.removed * 2 > preview.totalBefore
        if (plan.sourceType == PlanSourceType.TEMPLATE && mostlyUnusable) {
            val profile = userProfileRepository.findByUser_Id(user.id).orElse(null)
            val pick = runCatching {
                templateRecommendationService.recommend(
                    user,
                    RecommendTemplateRequest(
                        goals = profile?.goals?.map { it.name.lowercase() },
                        weeklyDays = profile?.weeklyWorkoutDays,
                        locale = locale,
                    ),
                    locale,
                ).templateCode
            }.onFailure { log.warn("template re-recommend after equipment change failed user={}: {}", user.id, it.message) }.getOrNull()
            if (pick != null && pick != plan.sourceId) {
                userPlanService.applyTemplate(user, pick, null, locale)
                log.info("User {} equipment changed: plan {} ({}) mostly unusable ({}/{}) -> new template {}",
                    user.id, plan.id, plan.sourceId, preview.removed, preview.totalBefore, pick)
                return Fit(Mode.NEW_TEMPLATE, exercisesReplaced = preview.removed, templateCode = pick, previousTemplateCode = plan.sourceId)
            }
        }
        val done = userPlanService.refitActivePlanToEquipment(user.id) ?: return Fit(Mode.NO_PLAN)
        return if (done.daysChanged.isEmpty()) Fit(Mode.UNCHANGED)
        else Fit(Mode.REFIT, done.daysChanged, done.removed)
    }
}

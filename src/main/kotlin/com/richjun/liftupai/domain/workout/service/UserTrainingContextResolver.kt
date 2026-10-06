package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import org.springframework.stereotype.Component

/**
 * 사용자가 온보딩·설정에서 저장해 둔 운동 환경(보유 장비·부상·경험·집중 목표).
 * 장비·부상은 두 테이블에 나뉘어 저장된다 — 기기 가입(AuthService)은 프로필에만, 온보딩 완료(UserService)는 설정에도 쓴다.
 * 장비는 설정 우선(없으면 프로필), 부상은 두 테이블의 합집합으로 읽는다 — UserPlanService.resolveInjuries 와 같은 규칙.
 * (2026-08-26 무릎 사고: 한쪽만 읽어 무릎 부상 사용자에게 하체 운동이 나갔다. 프로필 수정은 이제 설정에도 같이 쓰므로
 *  지운 부상이 설정에만 남는 일은 없다.)
 */
@Component
class UserTrainingContextResolver(
    private val userSettingsRepository: UserSettingsRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    data class Context(
        /** Equipment enum 이름(BARBELL 등). 비어 있으면 저장된 장비가 없다 = 제한 없음 */
        val equipment: Set<String>,
        val injuries: Set<String>,
        val experience: String?,
        val focusGoals: List<String>,
    ) {
        val focusAreas: List<FocusArea> get() = FocusArea.resolve(focusGoals)

        companion object {
            val EMPTY = Context(emptySet(), emptySet(), null, emptyList())
        }
    }

    fun resolve(userId: Long): Context {
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        val rawEquipment = settings?.availableEquipment?.takeIf { it.isNotEmpty() }?.toList()
            ?: profile?.availableEquipment?.toList().orEmpty()
        // 부상을 놓치는 쪽이 더 위험하므로 합집합 — 설정에만 남은 옛 부상(사고 당시 형태)도 거른다
        val rawInjuries = profile?.injuries?.toList().orEmpty() + settings?.injuries?.toList().orEmpty()
        return Context(
            equipment = rawEquipment.map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }.filter { it.isNotBlank() }.toSet(),
            injuries = rawInjuries.map { it.substringBefore(':').trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }.toSet(),
            experience = profile?.experienceLevel?.name?.lowercase(),
            focusGoals = settings?.focusGoals?.toList().orEmpty(),
        )
    }
}

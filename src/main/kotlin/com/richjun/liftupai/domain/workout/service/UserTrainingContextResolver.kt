package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import org.springframework.stereotype.Component

/**
 * 사용자가 온보딩·설정에서 저장해 둔 운동 환경(보유 장비·부상·경험·집중 목표).
 * 장비·부상은 두 테이블에 나뉘어 저장된다 — 기기 가입(AuthService)은 프로필에만, 온보딩 완료(UserService)는 설정에도 쓴다.
 * 하나만 읽으면 필터가 조용히 무력화되므로(2026-08-26 무릎 사고) 둘을 합쳐 본다.
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
        val rawInjuries = profile?.injuries?.toList().orEmpty() + settings?.injuries?.toList().orEmpty()
        return Context(
            equipment = rawEquipment.map { it.trim().uppercase().replace(" ", "_").replace("-", "_") }.filter { it.isNotBlank() }.toSet(),
            injuries = rawInjuries.map { it.substringBefore(':').trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }.toSet(),
            experience = profile?.experienceLevel?.name?.lowercase(),
            focusGoals = settings?.focusGoals?.toList().orEmpty(),
        )
    }
}

package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import org.springframework.stereotype.Component

/**
 * 사용자가 온보딩·설정에서 저장해 둔 운동 환경(보유 장비·부상·경험·나이·집중 목표·세션 시간) — 추천 경로 전체의 **단일 출처**.
 *
 * 장비·부상은 두 테이블에 나뉘어 저장된다 — 기기 가입(AuthService)은 프로필에만, 온보딩 완료(UserService)는 설정에도 쓴다.
 *  - 부상: 프로필 ∪ 설정 ∪ 요청의 합집합. 놓치는 쪽이 더 위험하다(2026-08-26 무릎 사고: 한쪽만 읽어 무릎 부상 사용자에게 하체 운동이 나갔다).
 *  - 장비: 요청(비어 있지 않으면) → 설정 → 프로필. 표기는 [RecoRules.normalizeEquipment] 로 한 번만 정규화한다.
 *  - 경험·나이·세션 시간·집중 목표: 요청 → 저장값.
 *
 * 예전에는 경로마다 따로 풀었다(설정 우선 ifEmpty 프로필, 요청만, 정규화 없음 …). 이제 템플릿 추천·적용, 제안, 대체, AI 플랜,
 * 채팅·퀵 추천, 프로그램이 모두 여기를 거친다.
 */
@Component
class UserTrainingContextResolver(
    private val userSettingsRepository: UserSettingsRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    data class Context(
        /** Equipment enum 이름(BARBELL 등). 비어 있으면 저장된 장비가 없다(또는 헬스장 전체) = 제한 없음 */
        val equipment: Set<String>,
        val injuries: Set<String>,
        val experience: String?,
        val focusGoals: List<String>,
        val age: Int? = null,
        val gender: String? = null,
        /** 저장된(또는 요청한) 회당 운동 시간(분). 없으면 null — 호출자가 [DEFAULT_SESSION_MINUTES] 를 쓴다 */
        val sessionMinutes: Int? = null,
        /** 요청 locale → 설정 언어. ko/ja/en 으로 정규화 */
        val language: String = "en",
        val weightKg: Double? = null,
    ) {
        val focusAreas: List<FocusArea> get() = FocusArea.resolve(focusGoals)

        val level: ExperienceLevel? get() = RecoRules.parseLevel(experience)

        /** 철봉·바벨 등 접근이 있는가 (장비 정보가 없으면 제한 없음으로 본다) */
        fun suitability(): (com.richjun.liftupai.domain.workout.entity.Exercise) -> Boolean =
            RecoRules.suitability(equipment, level, age)

        fun sessionMinutesOrDefault(): Int = sessionMinutes ?: DEFAULT_SESSION_MINUTES

        companion object {
            val EMPTY = Context(emptySet(), emptySet(), null, emptyList())
        }
    }

    /** 요청이 실어 보낸 값. 비어 있으면 저장값을 쓴다(부상은 합집합). */
    data class Overrides(
        val equipment: Collection<String>? = null,
        val injuries: Collection<String>? = null,
        val experience: String? = null,
        val focusGoals: Collection<String>? = null,
        val sessionMinutes: Int? = null,
        val age: Int? = null,
        val gender: String? = null,
        val locale: String? = null,
    )

    /**
     * 부상만 필요한 호출자용(프로필 ∪ 설정 ∪ [extra]). 설정·프로필의 부상 컬렉션만 읽는다 — 다른 필드를 건드리지 않아
     * 부분 스텁만 있는 테스트 더블에서도 같은 규칙으로 해석된다.
     */
    fun resolveInjuries(userId: Long, extra: Collection<String>? = null): Set<String> {
        val settings = try { userSettingsRepository.findByUser_Id(userId).orElse(null) } catch (e: Exception) { null }
        val profile = try { userProfileRepository.findByUser_Id(userId).orElse(null) } catch (e: Exception) { null }
        return RecoRules.normalizeInjuries(profile?.injuries.orEmpty() + settings?.injuries.orEmpty() + extra.orEmpty())
    }

    fun resolve(userId: Long, overrides: Overrides? = null): Context {
        val settings = userSettingsRepository.findByUser_Id(userId).orElse(null)
        val profile = userProfileRepository.findByUser_Id(userId).orElse(null)
        return of(settings, profile, overrides)
    }

    companion object {
        /** 저장된 값도 요청도 없을 때 쓰는 회당 운동 시간 */
        const val DEFAULT_SESSION_MINUTES = 45
        private const val MIN_SESSION_MINUTES = 10
        private const val MAX_SESSION_MINUTES = 240

        /** 이미 설정·프로필을 읽어 둔 호출자(채팅 추천 등)가 DB 를 다시 치지 않고 같은 규칙으로 해석한다. */
        fun of(settings: UserSettings?, profile: UserProfile?, overrides: Overrides? = null): Context {
            val requestEquipment = RecoRules.normalizeEquipment(overrides?.equipment)
            val requestEquipmentGiven = !overrides?.equipment.isNullOrEmpty() &&
                (overrides?.equipment.orEmpty().any { !it.isNullOrBlank() })
            val storedEquipmentRaw = settings?.availableEquipment?.takeIf { it.isNotEmpty() }?.toList()
                ?: profile?.availableEquipment?.toList().orEmpty()
            val equipment = if (requestEquipmentGiven) requestEquipment else RecoRules.normalizeEquipment(storedEquipmentRaw)

            // 부상을 놓치는 쪽이 더 위험하므로 합집합 — 프로필·설정·요청 어느 한쪽에만 있어도 거른다
            val injuries = RecoRules.normalizeInjuries(
                profile?.injuries.orEmpty() + settings?.injuries.orEmpty() + overrides?.injuries.orEmpty()
            )

            val experience = overrides?.experience?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: profile?.experienceLevel?.name?.lowercase()
            val focus = overrides?.focusGoals?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: settings?.focusGoals?.toList().orEmpty()
            val minutes = (overrides?.sessionMinutes?.takeIf { it > 0 } ?: settings?.workoutDuration?.takeIf { it > 0 }
                ?: profile?.workoutDuration?.takeIf { it > 0 })
                ?.coerceIn(MIN_SESSION_MINUTES, MAX_SESSION_MINUTES)
            return Context(
                equipment = equipment,
                injuries = injuries,
                experience = experience,
                focusGoals = focus,
                age = overrides?.age ?: profile?.age,
                gender = overrides?.gender?.takeIf { it.isNotBlank() } ?: profile?.gender,
                sessionMinutes = minutes,
                language = normalizeLanguage(overrides?.locale?.takeIf { it.isNotBlank() } ?: settings?.language),
                weightKg = profile?.bodyInfo?.weight?.takeIf { it > 0 },
            )
        }

        fun normalizeLanguage(raw: String?): String {
            val n = raw?.trim()?.lowercase()?.replace('_', '-').orEmpty()
            return when {
                n.startsWith("ko") -> "ko"
                n.startsWith("ja") -> "ja"
                else -> "en"
            }
        }
    }
}

package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise

/**
 * 부상·보유 장비 목록만으로는 거를 수 없는 "이 사람이 실제로 할 수 있나"를 판정한다.
 *
 * 골든셋 2026-09-25: 무릎 부상 + 밴드만 있는 72세 초보에게 풀업·친업·딥 푸시업이, 맨몸만 있는 14세 초보에게
 * 풀업이 추천됐다. 풀업·평행봉 딥스·행잉 동작은 카탈로그에 BODYWEIGHT 로 들어 있지만 철봉/평행봉이 있어야 하고,
 * 맨몸 풀업은 초보·고령자가 한 개도 못 하는 경우가 대부분이다.
 */
object ExerciseSuitability {
    // 앱의 장비 선택지 "철봉(Bar)"은 OTHER 로 저장된다(workout_preference_page.dart / workout_plan_settings_screen.dart)
    private val GYM_TOKENS = setOf("BARBELL", "MACHINE", "CABLE", "SMITH_MACHINE", "GYM", "FULL_GYM", "ALL", "FULL", "COMMERCIAL_GYM", "EVERYTHING",
        "OTHER", "BAR", "PULL_UP_BAR", "PULLUP_BAR")
    private val EXPLOSIVE_PATTERNS = setOf("PLYOMETRIC", "CLEAN", "SNATCH", "COMPOUND_COMPLEX")
    /** 카탈로그 난이도(30~45)가 실제보다 낮게 매겨진 고급 변형 — 72세 초보에게 아처 푸시업이 나갔다(골든셋 2026-09-28) */
    private val ADVANCED_SLUG_MARKERS = listOf("archer", "planche", "clap", "explosive", "one-arm-push", "single-arm-push", "pistol", "handstand", "muscle-up", "dragon-flag", "l-sit")

    /** 철봉·평행봉이 있어야 하는 맨몸 동작. 벤치·의자 딥스와 머신 보조 풀업은 아니다. */
    fun needsBar(ex: Exercise): Boolean {
        if (ex.equipment != Equipment.BODYWEIGHT && ex.equipment != Equipment.OTHER && ex.equipment != null) return false
        val pattern = ex.movementPattern?.uppercase().orEmpty()
        val slug = ex.slug
        return when {
            pattern == "PULLUP_CHINUP" -> true
            pattern == "DIPS" -> !slug.contains("bench") && !slug.contains("chair")
            else -> slug.contains("hanging") || slug.contains("muscle-up")
        }
    }

    /** 헬스장(바벨·머신·케이블 중 하나) 이용자나 철봉(OTHER)을 고른 사용자는 철봉이 있다고 본다. 장비 정보가 비어 있으면 제한하지 않는다. */
    fun hasBarAccess(availableEquipment: Collection<String>): Boolean {
        if (availableEquipment.isEmpty()) return true
        return availableEquipment.any { it.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_") in GYM_TOKENS }
    }

    fun isVulnerable(age: Int?): Boolean = age != null && (age >= 65 || age < 16)

    /**
     * 초보·고령(65+)·16세 미만에게 맞지 않는 동작: 맨몸 풀업·친업, 평행봉 딥스, 폭발적 동작(점프·클린·스내치),
     * 난이도 55 초과. 카탈로그 난이도는 장비 별칭에 가까워 숫자만으로는 못 거른다 — 패턴을 함께 본다.
     */
    fun tooAdvanced(ex: Exercise, level: ExperienceLevel?, age: Int?): Boolean {
        val beginner = level == null || level == ExperienceLevel.BEGINNER
        if (!beginner && !isVulnerable(age)) return false
        val pattern = ex.movementPattern?.uppercase().orEmpty()
        if (pattern == "PULLUP_CHINUP" && ex.equipment == Equipment.BODYWEIGHT) return true
        if (ADVANCED_SLUG_MARKERS.any { ex.slug.contains(it) }) return true
        if (pattern == "DIPS" && needsBar(ex)) return true
        if (isVulnerable(age) && (pattern in EXPLOSIVE_PATTERNS || pattern == "DIPS")) return true
        return ex.difficulty > 55
    }

    fun suitable(ex: Exercise, availableEquipment: Collection<String>, level: ExperienceLevel?, age: Int?): Boolean =
        (!needsBar(ex) || hasBarAccess(availableEquipment)) && !tooAdvanced(ex, level, age)
}

package com.richjun.liftupai.domain.user.util

import com.richjun.liftupai.domain.user.entity.FitnessGoal
import org.slf4j.LoggerFactory

/**
 * 앱이 보내는 목표 문자열 → 서버 FitnessGoal.
 *
 * 2026-09-19: 앱 온보딩은 목표를 20종(굵은 팔·식스팩·벌크업 등) 제시하는데 서버는 6종만 알아서
 * 나머지 14종이 조용히 버려졌다. 사용자 109·111·129 는 목표 0개로 저장됐고, 실사용자 누구도
 * MUSCLE_GAIN 이 없었다("벌크업/두꺼운 가슴"을 골라도 근비대로 인식 안 됨) → AI 코칭·플랜·무게가
 * 목표를 모른 채 동작했다. 모든 앱 목표를 가장 가까운 서버 목표로 대응시킨다.
 */
object FitnessGoalMapper {
    private val log = LoggerFactory.getLogger(FitnessGoalMapper::class.java)

    private val MAP: Map<String, FitnessGoal> = buildMap {
        // 서버 표준 이름
        put("MUSCLE_GAIN", FitnessGoal.MUSCLE_GAIN)
        put("WEIGHT_LOSS", FitnessGoal.WEIGHT_LOSS)
        put("STRENGTH", FitnessGoal.STRENGTH)
        put("ENDURANCE", FitnessGoal.ENDURANCE)
        put("GENERAL_FITNESS", FitnessGoal.GENERAL_FITNESS)
        put("ATHLETIC_PERFORMANCE", FitnessGoal.ATHLETIC_PERFORMANCE)
        // 예전 별칭
        put("FAT_LOSS", FitnessGoal.WEIGHT_LOSS)
        put("STRENGTH_GAIN", FitnessGoal.STRENGTH)
        put("BODY_CORRECTION", FitnessGoal.GENERAL_FITNESS)
        put("HEALTH_MAINTENANCE", FitnessGoal.GENERAL_FITNESS)
        // 앱 온보딩·프로필의 세부 목표 — 근육을 키우는 목표
        listOf("BULK_UP", "LEAN_MASS", "BIG_ARMS", "THICK_CHEST", "WIDE_BACK", "WIDE_SHOULDERS", "THICK_LEGS", "GLUTES")
            .forEach { put(it, FitnessGoal.MUSCLE_GAIN) }
        // 체지방을 줄여 드러내는 목표(식스팩은 체지방률이 핵심, 슬림한 다리는 감량 쪽)
        listOf("SIX_PACK", "SLIM_LEGS").forEach { put(it, FitnessGoal.WEIGHT_LOSS) }
        // 기능·근력 목표
        put("CORE_STRENGTH", FitnessGoal.STRENGTH)
        // 탄력·자세·유연성·재활 — 일반 체력
        listOf("TONED_ARMS", "POSTURE", "FLEXIBILITY", "REHABILITATION").forEach { put(it, FitnessGoal.GENERAL_FITNESS) }
    }

    fun parse(value: String?): FitnessGoal? {
        val key = value?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
        if (key.isEmpty()) return null
        return MAP[key].also { if (it == null) log.warn("[Goals] unknown goal value from client: '{}' — dropped", value) }
    }

    /** 여러 목표를 변환. 모르는 값은 로그 후 제외. */
    fun parseAll(values: Collection<String>?): Set<FitnessGoal> = values.orEmpty().mapNotNull { parse(it) }.toSet()
}

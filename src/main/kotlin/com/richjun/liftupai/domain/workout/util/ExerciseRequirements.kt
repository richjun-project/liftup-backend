package com.richjun.liftupai.domain.workout.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise

/**
 * 운동마다 "실제로 무엇이 있어야 하나"(2026-11-14).
 *
 * 카탈로그의 equipment 는 철봉 턱걸이·낮은 바 인버티드 로우도 BODYWEIGHT 로 적혀 있고, equipmentDetail 은 자유 글이라
 * 틀린 값이 섞여 있다(행잉 니 레이즈 "None"). 예전엔 슬러그·패턴으로 철봉 여부를 짐작했는데(needsBar), 인버티드 로우를 놓쳐
 * 기구 없는 사람에게 나갔다. 플랜에 쓰는 맨몸·기타 운동은 사람이 확인한 값을 catalog/exercise-requires.json 에 둔다
 * (ExerciseRequirementsTest 가 빠진 것이 없는지 본다). 목록에 없는 운동은 예전 추정(needsBar)으로 판정한다.
 */
enum class ExerciseRequirement {
    /** 몸만 있으면 된다 */
    NONE,
    /** 의자·소파·벽·탁자 — 집이면 어디든 있다 */
    HOUSEHOLD,
    /** 철봉 */
    PULLUP_BAR,
    /** 평행봉 */
    DIP_BARS,
    /** 스미스 머신·랙·TRX 같은 낮은 바 */
    LOW_BAR,
    /** 밴드를 문에 거는 고리 */
    DOOR_ANCHOR,
    /** 발을 걸 곳·파트너(노르딕 컬) */
    ANCHOR,
    /** 헬스장 기구(디클라인 벤치 등) */
    GYM,
}

object ExerciseRequirements {
    private const val RESOURCE = "/catalog/exercise-requires.json"

    /** 헬스장 이용자(바벨·머신·케이블 중 하나)를 뜻하는 장비 값 — 철봉(OTHER/BAR)만 고른 사람은 포함하지 않는다 */
    private val GYM_TOKENS = setOf("BARBELL", "MACHINE", "CABLE", "SMITH_MACHINE", "GYM", "FULL_GYM", "ALL", "FULL", "COMMERCIAL_GYM", "EVERYTHING")

    val bySlug: Map<String, ExerciseRequirement> by lazy { load() }

    private fun load(): Map<String, ExerciseRequirement> {
        val stream = ExerciseRequirements::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        val root = stream.use { ObjectMapper().readTree(it) }
        return root.path("requires").fields().asSequence().associate { (slug, v) -> slug to ExerciseRequirement.valueOf(v.asText()) }
    }

    /** 사람이 확인한 값, 없으면 예전 추정(철봉·평행봉 동작이면 PULLUP_BAR/DIP_BARS) */
    fun of(ex: Exercise): ExerciseRequirement = bySlug[ex.slug] ?: inferred(ex)

    private fun inferred(ex: Exercise): ExerciseRequirement {
        if (!ExerciseSuitability.needsBar(ex)) return ExerciseRequirement.NONE
        return if (ex.movementPattern?.uppercase() == "DIPS") ExerciseRequirement.DIP_BARS else ExerciseRequirement.PULLUP_BAR
    }

    private fun tokens(equipment: Collection<String>) =
        equipment.map { it.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_") }.toSet()

    fun hasGym(equipment: Collection<String>): Boolean = tokens(equipment).any { it in GYM_TOKENS }

    /** 보유 장비로 이 요구를 채울 수 있나. 장비 정보가 비어 있으면(모름) 제한하지 않는다. */
    fun available(req: ExerciseRequirement, equipment: Collection<String>): Boolean {
        if (equipment.isEmpty()) return true
        return when (req) {
            ExerciseRequirement.NONE, ExerciseRequirement.HOUSEHOLD, ExerciseRequirement.ANCHOR -> true
            ExerciseRequirement.PULLUP_BAR -> ExerciseSuitability.hasBarAccess(equipment)
            ExerciseRequirement.DIP_BARS, ExerciseRequirement.LOW_BAR, ExerciseRequirement.GYM -> hasGym(equipment)
            ExerciseRequirement.DOOR_ANCHOR -> Equipment.RESISTANCE_BAND.name in tokens(equipment) || hasGym(equipment)
        }
    }

    /** 이 사람이 이 운동에 필요한 도구를 갖췄나(맨몸·기타 운동만 판정 — 덤벨·바벨 등은 equipment 필터가 맡는다) */
    fun allows(ex: Exercise, equipment: Collection<String>): Boolean {
        if (ex.equipment != Equipment.BODYWEIGHT && ex.equipment != Equipment.OTHER && ex.equipment != null) return true
        return available(of(ex), equipment)
    }
}

package com.richjun.liftupai.domain.progress.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class LevelResponse(
    val level: Int,
    @JsonProperty("total_xp") val totalXp: Long,
    @JsonProperty("current_level_xp") val currentLevelXp: Long,
    @JsonProperty("next_level_xp") val nextLevelXp: Long,
    @JsonProperty("class_key") val classKey: String,
    @JsonProperty("next_evolution_level") val nextEvolutionLevel: Int?,
    @JsonProperty("xp_to_next_evolution") val xpToNextEvolution: Long?,
    @JsonProperty("active_week_streak") val activeWeekStreak: Int,
    val multiplier: Double,
    @JsonProperty("today_xp") val todayXp: Int,
    @JsonProperty("daily_cap") val dailyCap: Int
)

data class XpBreakdownItem(val type: String, val amount: Int, val count: Int)

data class XpState(val level: Int, val xp: Long, @JsonProperty("class") val classKey: String)

data class WorkoutXpResult(
    val gained: Long,
    val breakdown: List<XpBreakdownItem>,
    val before: XpState,
    val after: XpState,
    @JsonProperty("leveled_up") val leveledUp: Boolean,
    val evolved: Boolean,
    /** 이 세션이 속한 날의 연속 활성 주 배율 */
    val multiplier: Double,
    /** 하루 상한(600)에 걸려 일부가 깎였는지 */
    @JsonProperty("daily_cap_applied") val dailyCapApplied: Boolean
)

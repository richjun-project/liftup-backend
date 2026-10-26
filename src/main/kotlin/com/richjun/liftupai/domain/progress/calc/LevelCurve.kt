package com.richjun.liftupai.domain.progress.calc

object LevelCurve {
    /** 레벨 L에 도달하는 누적 XP */
    fun cumulativeXp(level: Int): Long {
        if (level <= 1) return 0
        return Math.round(XpRules.LEVEL_COEFFICIENT * Math.pow((level - 1).toDouble(), XpRules.LEVEL_EXPONENT))
    }

    fun levelFor(totalXp: Long): Int {
        var level = 1
        while (cumulativeXp(level + 1) <= totalXp) level++
        return level
    }

    fun classKey(level: Int): String = XpRules.CLASSES.first { level >= it.first }.second

    /** 현재 레벨 다음의 진화 레벨(없으면 null) */
    fun nextEvolutionLevel(level: Int): Int? = XpRules.EVOLUTION_LEVELS.firstOrNull { it > level }
}

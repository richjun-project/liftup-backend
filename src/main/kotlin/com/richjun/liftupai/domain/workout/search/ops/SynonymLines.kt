package com.richjun.liftupai.domain.workout.search.ops

/**
 * 동의어 줄 검증. 운영자가 DB 에 잘못된 줄을 넣어도 엔진을 망가뜨리지 않도록 형식에 맞는 줄만 통과시킨다.
 * 형식: "트리거|트리거 => 확장|확장" 또는 "트리거|트리거 =>> 확장|확장"(강한 별칭).
 */
object SynonymLines {
    const val MAX_LINE_LENGTH = 500
    private val ARROW = Regex("=>{1,3}")

    /** 올바른 줄이면 정리(trim)한 줄을, 아니면 null */
    fun validate(raw: String?): String? {
        val line = raw?.trim().orEmpty()
        if (line.isEmpty() || line.length > MAX_LINE_LENGTH || line.startsWith("#")) return null
        if (line.any { it.isISOControl() }) return null
        val parts = line.split(ARROW)
        if (parts.size != 2) return null
        val sides = parts.map { side -> side.split("|").map { it.trim() }.filter { it.isNotEmpty() } }
        if (sides.any { it.isEmpty() }) return null
        return line
    }
}

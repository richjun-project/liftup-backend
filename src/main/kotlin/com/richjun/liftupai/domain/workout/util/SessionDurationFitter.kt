package com.richjun.liftupai.domain.workout.util

import kotlin.math.roundToInt

/**
 * 추천 종목 목록을 사용자가 고른 세션 시간에 맞춘다.
 *
 * 추정 시간 = 워밍업 5분 + Σ 세트 × (반복 × 3초 + 휴식) + 종목 전환 30초.
 * 골든셋(2026-09-25, 60 프로필): 20분 요청에 36~49분 분량(4종목 × 3~4세트 × 휴식 60~180초)이 나갔고,
 * 응답의 duration 표기는 요청값 그대로라 사용자는 틀린 시간을 보고 시작했다.
 *
 * 줄일 때: 휴식을 처방 하한까지 → 뒤 종목부터 세트 3까지 → 종목 수(최소 3) → 세트 2까지.
 * 모자랄 때: 앞 종목부터 세트를 5까지 늘린다.
 */
object SessionDurationFitter {
    data class Slot(val sets: Int, val reps: String, val restSeconds: Int, val minRestSeconds: Int)

    const val WARMUP_SECONDS = 300
    const val TRANSITION_SECONDS = 30
    private const val SECONDS_PER_REP = 3.0
    private const val TIMED_SET_SECONDS = 45.0
    private const val MIN_EXERCISES = 3
    private const val COMFORT_SETS = 3
    private const val MIN_SETS = 2
    private const val MAX_SETS = 5
    private const val UPPER = 1.05
    private const val LOWER = 0.9

    private val repRange = Regex("""\s*(\d+)\s*(?:[-~–]\s*(\d+))?\s*""")
    private val timedRange = Regex("""\s*(\d+)\s*(?:[-~–]\s*(\d+))?\s*(s|sec|초|秒)\s*""", RegexOption.IGNORE_CASE)

    /** 한 세트의 수행 시간(초). "8-10" → 27, "30-60s" → 45, 해석 불가 → 45 */
    fun workSeconds(reps: String): Double {
        timedRange.matchEntire(reps)?.let { m ->
            val lo = m.groupValues[1].toDouble()
            val hi = m.groupValues[2].ifEmpty { m.groupValues[1] }.toDouble()
            return (lo + hi) / 2
        }
        val m = repRange.matchEntire(reps) ?: return TIMED_SET_SECONDS
        val lo = m.groupValues[1].toDouble()
        val hi = m.groupValues[2].ifEmpty { m.groupValues[1] }.toDouble()
        return (lo + hi) / 2 * SECONDS_PER_REP
    }

    fun estimateSeconds(slots: List<Slot>): Int {
        if (slots.isEmpty()) return 0
        val work = slots.sumOf { it.sets * (workSeconds(it.reps) + it.restSeconds) }
        return (WARMUP_SECONDS + work + TRANSITION_SECONDS * slots.size).roundToInt()
    }

    fun estimateMinutes(slots: List<Slot>): Int = (estimateSeconds(slots) / 60.0).roundToInt()

    fun fit(slots: List<Slot>, targetMinutes: Int): List<Slot> {
        if (slots.isEmpty() || targetMinutes <= 0) return slots
        val upper = targetMinutes * 60 * UPPER
        val lower = targetMinutes * 60 * LOWER
        val cur = slots.toMutableList()
        fun over() = estimateSeconds(cur) > upper

        // 1. 휴식을 처방 하한까지 (뒤 종목부터 — 첫 주력 종목의 휴식은 마지막에 줄인다)
        for (i in cur.indices.reversed()) {
            if (!over()) break
            cur[i] = cur[i].copy(restSeconds = cur[i].minRestSeconds.coerceAtMost(cur[i].restSeconds))
        }
        // 2. 뒤 종목부터 세트를 3까지
        reduceSets(cur, COMFORT_SETS, ::over)
        // 3. 종목 수를 줄인다 (최소 3)
        while (over() && cur.size > MIN_EXERCISES) cur.removeAt(cur.lastIndex)
        // 4. 그래도 넘치면 세트 2까지
        reduceSets(cur, MIN_SETS, ::over)

        // 5. 모자라면 앞 종목부터 세트를 늘린다 (한 세트씩 돌아가며, 넘치기 직전에서 멈춘다)
        var grew = true
        while (estimateSeconds(cur) < lower && grew) {
            grew = false
            for (i in cur.indices) {
                if (estimateSeconds(cur) >= lower) break
                if (cur[i].sets >= MAX_SETS) continue
                val next = cur[i].copy(sets = cur[i].sets + 1)
                val trial = cur.toMutableList().also { it[i] = next }
                if (estimateSeconds(trial) > upper) continue
                cur[i] = next
                grew = true
            }
        }
        return cur
    }

    private fun reduceSets(cur: MutableList<Slot>, floor: Int, over: () -> Boolean) {
        var changed = true
        while (over() && changed) {
            changed = false
            for (i in cur.indices.reversed()) {
                if (!over()) return
                if (cur[i].sets > floor) {
                    cur[i] = cur[i].copy(sets = cur[i].sets - 1)
                    changed = true
                }
            }
        }
    }
}

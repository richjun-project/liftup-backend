package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.util.SessionDurationFitter

/**
 * 하루 운동을 사용자가 저장한 세션 시간에 맞추는 순수 로직 (SessionDurationFitter 의 시간 추정식을 그대로 쓴다).
 *
 * SessionDurationFitter 와 다른 점
 *  - 종목별 세트 상한(초보 주력 복합 3세트, 연령 상한)을 지킨다 — 시간을 채우려고 세트를 5까지 올리지 않는다
 *  - 모자라면 세트 → 휴식 순으로 늘린다(상한까지). 종목을 더하는 일은 호출자(풀을 아는 쪽)가 한다
 *  - 줄일 때 종목을 뺄 순서를 호출자가 정한다(집중 부위·주력 복합을 지키기 위해)
 *
 * 목표 범위는 85~110% 안에 들어가도록 [LOWER]~[UPPER] 로 잡는다.
 */
object SessionFit {
    const val LOWER = 0.92
    const val UPPER = 1.05

    /** 이 비율보다 모자라면 호출자가 종목을 더 넣는 편이 낫다 */
    const val NEEDS_MORE = 0.88

    private const val COMFORT_SETS = 3
    private const val MIN_SETS = 2
    private const val REST_STEP = 15

    data class Slot(
        val sets: Int,
        val reps: String,
        val restSeconds: Int,
        val minRestSeconds: Int = restSeconds,
        val maxRestSeconds: Int = restSeconds,
        val maxSets: Int = 5,
    ) {
        fun toFitter() = SessionDurationFitter.Slot(sets, reps, restSeconds, minRestSeconds)
    }

    class Result(val slots: List<Slot>, val keptIndices: List<Int>)

    fun seconds(slots: List<Slot>): Int = SessionDurationFitter.estimateSeconds(slots.map { it.toFitter() })

    fun minutes(slots: List<Slot>): Int = SessionDurationFitter.estimateMinutes(slots.map { it.toFitter() })

    /** 이 종목들이 목표 시간의 몇 배인가 */
    fun ratio(slots: List<Slot>, targetMinutes: Int): Double =
        if (targetMinutes <= 0) 1.0 else seconds(slots) / (targetMinutes * 60.0)

    /**
     * 목표 시간에 맞춘다.
     * @param pickDrop 줄여야 하는데 세트·휴식만으로 안 될 때 뺄 종목의 위치(현재 목록 기준). 인자는 (원래 목록 기준 남은 인덱스, 현재 슬롯). null 이면 종목은 빼지 않는다.
     * @param minExercises 이 수 아래로는 종목을 빼지 않는다
     */
    fun fit(
        slots: List<Slot>,
        targetMinutes: Int,
        minExercises: Int = 3,
        pickDrop: ((keptIndices: List<Int>, slots: List<Slot>) -> Int?)? = null,
    ): Result {
        if (slots.isEmpty() || targetMinutes <= 0) return Result(slots, slots.indices.toList())
        val upper = targetMinutes * 60 * UPPER
        val lower = targetMinutes * 60 * LOWER
        val cur = slots.toMutableList()
        val kept = slots.indices.toMutableList()
        fun over() = seconds(cur) > upper

        // 1. 휴식을 처방 하한까지 (뒤 종목부터 — 첫 주력 종목의 휴식은 마지막에 줄인다)
        for (i in cur.indices.reversed()) {
            if (!over()) break
            cur[i] = cur[i].copy(restSeconds = cur[i].minRestSeconds.coerceAtMost(cur[i].restSeconds))
        }
        // 2. 뒤 종목부터 세트를 3까지
        reduceSets(cur, COMFORT_SETS, ::over)
        // 3. 종목을 뺀다
        if (pickDrop != null) {
            while (over() && cur.size > minExercises) {
                val idx = pickDrop(kept, cur)?.takeIf { it in cur.indices } ?: break
                cur.removeAt(idx); kept.removeAt(idx)
            }
        }
        // 4. 그래도 넘치면 세트 2까지
        reduceSets(cur, MIN_SETS, ::over)

        // 5. 모자라면 세트 → 휴식 순으로 상한까지 늘린다
        growSets(cur, lower, upper)
        growRest(cur, lower, upper)
        // 휴식을 늘린 뒤에도 세트를 더 올릴 여지가 있으면 한 번 더
        growSets(cur, lower, upper)
        return Result(cur, kept)
    }

    private fun growSets(cur: MutableList<Slot>, lower: Double, upper: Double) {
        var grew = true
        while (seconds(cur) < lower && grew) {
            grew = false
            for (i in cur.indices) {
                if (seconds(cur) >= lower) break
                if (cur[i].sets >= cur[i].maxSets) continue
                val trial = cur.toMutableList().also { it[i] = it[i].copy(sets = it[i].sets + 1) }
                if (seconds(trial) > upper) continue
                cur[i] = trial[i]
                grew = true
            }
        }
    }

    private fun growRest(cur: MutableList<Slot>, lower: Double, upper: Double) {
        var grew = true
        while (seconds(cur) < lower && grew) {
            grew = false
            for (i in cur.indices) {
                if (seconds(cur) >= lower) break
                if (cur[i].restSeconds >= cur[i].maxRestSeconds) continue
                val trial = cur.toMutableList().also { it[i] = it[i].copy(restSeconds = minOf(it[i].maxRestSeconds, it[i].restSeconds + REST_STEP)) }
                if (seconds(trial) > upper) continue
                cur[i] = trial[i]
                grew = true
            }
        }
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

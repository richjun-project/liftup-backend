package com.richjun.liftupai.domain.progress.calc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

data class XpSetInput(val weightKg: Double, val reps: Int, val completed: Boolean)
data class XpExerciseInput(val exerciseId: Long, val sets: List<XpSetInput>)

/** completedAt 은 UTC LocalDateTime(DB 저장 형식). 완료된 세션만 넘긴다. */
data class XpSessionInput(val id: Long, val completedAt: LocalDateTime, val exercises: List<XpExerciseInput>)

data class XpInput(
    val sessions: List<XpSessionInput>,
    val mealTimestamps: List<LocalDateTime>,
    val bodyweightKg: Double?,
    val weeklyGoal: Int?,
    val zone: ZoneId
)

enum class XpType { SET, WORKOUT_COMPLETE, PERSONAL_RECORD, WEEKLY_GOAL, MEAL }

/** 세션 하나가 만든 배율·상한 적용 전 원점수 */
data class SessionXp(
    val sessionId: Long,
    val date: LocalDate,
    val setXp: Int,
    val setCount: Int,
    val completeXp: Int,
    val prCount: Int,
    val prXp: Int,
    val weeklyGoalXp: Int
) {
    val rawTotal get() = setXp + completeXp + prXp + weeklyGoalXp
}

data class DayXp(
    val date: LocalDate,
    val rawXp: Int,          // 규칙 1,2,3,5 배율 전
    val multiplier: Double,
    val cappedXp: Int,       // 배율·상한 적용 후 (주간 목표 제외)
    val weeklyGoalXp: Int,
    val mealXp: Int
) {
    val totalXp get() = cappedXp + weeklyGoalXp
}

data class XpResult(
    val totalXp: Long,
    val days: Map<LocalDate, DayXp>,
    val sessions: Map<Long, SessionXp>,
    /** 월요일 -> 그 주 포함 연속 활성 주 수 (활성 아닌 주는 0) */
    val streakByWeek: Map<LocalDate, Int>,
    val zone: ZoneId
) {
    fun streakWeeksOn(date: LocalDate): Int = streakByWeek[mondayOf(date)] ?: 0
    fun multiplierFor(date: LocalDate): Double = XpCalculator.multiplierForStreak(streakWeeksOn(date))
    fun cappedXpOn(date: LocalDate): Int = days[date]?.cappedXp ?: 0
}

fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** 스프링 없이 테스트 가능한 순수 계산기. 입력 전체 이력으로부터 총 XP를 처음부터 계산한다. */
object XpCalculator {

    fun bodyweight(raw: Double?): Double =
        if (raw == null || raw.isNaN() || raw.isInfinite() || raw <= 0.0) XpRules.DEFAULT_BODYWEIGHT_KG else raw

    private fun effectiveWeight(weightKg: Double, bw: Double): Double =
        if (weightKg > 0.0) weightKg else XpRules.BODYWEIGHT_EXERCISE_FACTOR * bw

    fun setXp(weightKg: Double, reps: Int, bodyweightKg: Double?): Int {
        val bw = bodyweight(bodyweightKg)
        val intensity = if (reps <= 0) 0
        else minOf(XpRules.MAX_INTENSITY, Math.floor(effectiveWeight(weightKg, bw) * reps / bw / 2).toInt())
        return XpRules.SET_BASE + maxOf(0, intensity)
    }

    fun estimatedOneRm(weightKg: Double, reps: Int, bodyweightKg: Double?): Double =
        effectiveWeight(weightKg, bodyweight(bodyweightKg)) * (1 + reps / 30.0)

    fun multiplierForStreak(weeks: Int): Double =
        XpRules.STREAK_MULTIPLIERS.firstOrNull { weeks >= it.first }?.second ?: 1.0

    fun calculate(input: XpInput): XpResult {
        val zone = input.zone
        val bw = bodyweight(input.bodyweightKg)
        val goal = (input.weeklyGoal ?: XpRules.DEFAULT_WEEKLY_GOAL).let { if (it < 1) XpRules.DEFAULT_WEEKLY_GOAL else it }
        fun dateOf(t: LocalDateTime): LocalDate = t.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()

        val ordered = input.sessions.sortedWith(compareBy({ it.completedAt }, { it.id }))
        val bestByExercise = HashMap<Long, Double>()
        val weeklyCount = HashMap<LocalDate, Int>()
        val sessionXps = LinkedHashMap<Long, SessionXp>()

        for (s in ordered) {
            val date = dateOf(s.completedAt)
            var setXp = 0
            var setCount = 0
            var prHits = 0
            for (ex in s.exercises) {
                for (set in ex.sets) {
                    if (!set.completed) continue
                    setXp += setXp(set.weightKg, set.reps, bw)
                    setCount++
                    if (set.reps > 0) {
                        val e1rm = estimatedOneRm(set.weightKg, set.reps, bw)
                        val prev = bestByExercise[ex.exerciseId]
                        if (prev != null && e1rm > prev) prHits++
                        if (prev == null || e1rm > prev) bestByExercise[ex.exerciseId] = e1rm
                    }
                }
            }
            val prCount = minOf(prHits, XpRules.MAX_PR_PER_SESSION)
            val week = mondayOf(date)
            val count = (weeklyCount[week] ?: 0) + 1
            weeklyCount[week] = count
            sessionXps[s.id] = SessionXp(
                sessionId = s.id, date = date,
                setXp = setXp, setCount = setCount,
                completeXp = XpRules.WORKOUT_COMPLETE,
                prCount = prCount, prXp = prCount * XpRules.PERSONAL_RECORD,
                weeklyGoalXp = if (count == goal) XpRules.WEEKLY_GOAL else 0
            )
        }

        // 연속 활성 주
        val streakByWeek = HashMap<LocalDate, Int>()
        weeklyCount.keys.sorted().forEach { week ->
            val active = (weeklyCount[week] ?: 0) >= XpRules.ACTIVE_WEEK_MIN_SESSIONS
            streakByWeek[week] = if (!active) 0 else (streakByWeek[week.minusWeeks(1)] ?: 0) + 1
        }

        // 식사: 날짜별 개수
        val mealsByDate = input.mealTimestamps.groupingBy { dateOf(it) }.eachCount()

        val dates = (sessionXps.values.map { it.date } + mealsByDate.keys).toSortedSet()
        val days = LinkedHashMap<LocalDate, DayXp>()
        var total = 0L
        for (d in dates) {
            val ss = sessionXps.values.filter { it.date == d }
            val mealXp = minOf((mealsByDate[d] ?: 0) * XpRules.MEAL, XpRules.MEAL_DAILY_MAX)
            val raw = ss.sumOf { it.setXp + it.completeXp + it.prXp } + mealXp
            val mult = multiplierForStreak(streakByWeek[mondayOf(d)] ?: 0)
            val capped = minOf(Math.round(raw * mult).toInt(), XpRules.DAILY_CAP)
            val goalXp = ss.sumOf { it.weeklyGoalXp }
            val day = DayXp(d, raw, mult, capped, goalXp, mealXp)
            days[d] = day
            total += day.totalXp
        }
        return XpResult(total, days, sessionXps, streakByWeek, zone)
    }
}

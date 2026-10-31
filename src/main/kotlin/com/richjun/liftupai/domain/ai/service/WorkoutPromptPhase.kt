package com.richjun.liftupai.domain.ai.service

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * AI 운동 추천 프롬프트(/api/chat/recommendations/workout · /api/ai/recommendations/personalized)의 주기화 단계.
 *
 * 2026-10-31: 기록이 없으면 "마지막 운동 99일 전"으로 계산돼 신규 사용자의 첫 추천이 전부 DELOAD 였다 —
 * 9월 이후 기록 0회에서 추천을 받은 4명 전원이 "디로드 전신 코어 리셋"·"Chest & Push Deload" 같은 제목을 받았고,
 * 한 명은 "왜 지금 디로드 단계야"라고 묻고 첫 운동 없이 떠났다. 첫 운동은 FIRST_SESSION, 2주 넘게 쉰 뒤는 RETURN(복귀)이다.
 * 회복 주간(DELOAD)은 쌓인 훈련이 있을 때만 — 4주 중 넷째 주라도 최근 28일 세션이 적으면 ACCUMULATION 으로 둔다.
 */
internal object WorkoutPromptPhase {
    const val FIRST_SESSION = "FIRST_SESSION"
    const val RETURN = "RETURN"
    const val ACCUMULATION = "ACCUMULATION"
    const val INTENSIFICATION = "INTENSIFICATION"
    const val REALIZATION = "REALIZATION"
    const val DELOAD = "DELOAD"

    private const val RETURN_AFTER_DAYS = 14L
    private const val DELOAD_MIN_SESSIONS_28D = 6

    fun resolve(sessionStarts: List<LocalDateTime>, now: LocalDateTime): String {
        val last = sessionStarts.maxOrNull() ?: return FIRST_SESSION
        if (ChronoUnit.DAYS.between(last, now) >= RETURN_AFTER_DAYS) return RETURN

        // 4주 메소사이클: 가장 이른 최근 세션 기준 주차
        val first = sessionStarts.min()
        return when ((ChronoUnit.WEEKS.between(first, now) % 4).toInt()) {
            0 -> ACCUMULATION
            1 -> INTENSIFICATION
            2 -> REALIZATION
            else -> if (sessionStarts.count { ChronoUnit.DAYS.between(it, now) <= 28 } >= DELOAD_MIN_SESSIONS_28D) DELOAD else ACCUMULATION
        }
    }

    fun note(phase: String): String = when (phase) {
        DELOAD -> " (prescribe lighter intensity ~50-60% 1RM, reduced volume)"
        FIRST_SESSION, RETURN -> " (moderate loads the user can control, about RPE 6-7)"
        else -> ""
    }

    fun guide(phase: String): String = when (phase) {
        FIRST_SESSION -> "This is the user's first tracked workout: choose familiar basic movements and moderate loads they can control with good form (about RPE 6-7, 2-3 reps in reserve). It is a starting session, not a deload or recovery session."
        RETURN -> "The user is coming back after two or more weeks without training: start a little lighter than before (about RPE 6-7) and rebuild over the next sessions. Frame it as a comeback session, not a deload."
        ACCUMULATION -> "Current phase is ACCUMULATION: prioritize higher volume (more reps) with moderate weight."
        INTENSIFICATION -> "Current phase is INTENSIFICATION: prioritize moderate volume with heavier weight."
        REALIZATION -> "Current phase is REALIZATION: prioritize low volume with near-maximal weight."
        DELOAD -> "Current phase is DELOAD: reduce both volume and intensity significantly for recovery."
        else -> ""
    }
}

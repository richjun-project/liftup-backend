package com.richjun.liftupai.domain.workout.util

import java.time.Duration
import java.time.LocalDateTime

/**
 * 세션 데이터 품질(2026-11-14 coach-autopilot-r1).
 *
 * 304 는 헬스장 플랜을 받은 채 15세트를 46초 만에 눌러 끝냈다(0kg, 운영 DB 2026-11-13). 이런 세션이 개인 기록(PR)으로 남으면 추천 무게의
 * 단일 출처(WeightRecommendationService: PR 우선)가 하지 않은 운동을 근거로 삼고, '첫 운동을 했다'로 세져 첫 운동 알림도 끊긴다.
 * 판정은 세션 시작~끝(서버 시각)으로 한다 — 세트별 완료 시각은 앱이 보내고, 없으면 완료 시각으로 채워져 몰아서 기록한 실제 운동과
 * 구별이 안 된다(리뷰 M7). 실제로 운동하고 끝에 몰아서 눌렀다면 시작~끝이 운동 시간만큼 길다.
 * 표시된 세션은 완료로는 남되(기록 삭제 없음) PR 과 '첫 운동 완료' 판정에서 빠진다. 이 판정으로 플랜을 바꾸지는 않는다.
 */
object SessionQuality {
    const val IMPLAUSIBLE_SPEED = "IMPLAUSIBLE_SPEED"

    /** 한 세트를 하고 기록하는 데 드는 최소 시간(초) — 맨몸 10회도 이보다 길다 */
    const val MIN_SECONDS_PER_SET = 10L

    /** 이보다 짧은 세션은 세트 수와 무관하게 의심한다(초) */
    const val MIN_SESSION_SECONDS = 60L

    /** 이 미만의 세트는 판정하지 않는다(한두 세트 시험 기록) */
    const val MIN_SETS_TO_JUDGE = 3

    fun implausible(start: LocalDateTime?, end: LocalDateTime?, completedSets: Int): Boolean {
        if (start == null || end == null || completedSets < MIN_SETS_TO_JUDGE) return false
        val seconds = Duration.between(start, end).seconds
        return seconds < maxOf(MIN_SESSION_SECONDS, completedSets * MIN_SECONDS_PER_SET)
    }
}

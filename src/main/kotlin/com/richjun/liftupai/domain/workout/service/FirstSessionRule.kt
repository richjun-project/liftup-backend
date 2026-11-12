package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel

/**
 * 완료한 운동이 0회인 사람의 첫 세션은 짧게(2026-10-31).
 *
 * 근거: 최근 실가입 5명 중 첫 운동 미리보기를 연 4명 — 5종목을 받은 1명은 끝까지 했고, 6~7종목·약 60분을 받은 3명 중 2명은
 * 시작하지 않았다. 트레이너 첫 인사도 "첫날은 앞 3종목만 가볍게"라고 말하는데 화면은 6종목이었다.
 * 체험 결제 화면이 첫 운동 완료 뒤에 뜨므로 첫 운동이 매출 퍼널의 입구다.
 *
 * 하루 상세(PlanWorkoutEnricher)·대시보드(UserPlanService)·트레이너 첫 인사가 같은 규칙을 본다.
 * 템플릿은 큰 복합 운동이 앞이라 앞에서 자른다. 운동을 한 번 끝내면 저장된 플랜 그대로 나간다(저장값은 건드리지 않음).
 *
 * 상급·전문가는 규칙 밖(2026-11-12, [appliesTo]). 근거가 된 이탈자는 초보·중급이었고, 11-11 일본 실가입 296(상급·주 5회·90분·PPL)은
 * 4종목·31분과 "첫날은 3종목만"을 받자 채팅으로 주 5일 PPL 을 다시 요청하고 미리보기에 종목 5개를 손으로 더했다.
 * 스스로 상급이라고 답한 사람에게 30분 상한은 '내 답을 안 들었다'로 읽힌다.
 */
internal object FirstSessionRule {
    const val MAX_EXERCISES = 4
    const val MAX_MINUTES = 30

    /** 규칙 대상 수준 — 상급·전문가는 저장된 플랜·세션 시간 그대로. 수준을 모르면 대상(안전한 쪽). */
    fun appliesTo(level: ExperienceLevel?): Boolean =
        level != ExperienceLevel.ADVANCED && level != ExperienceLevel.EXPERT

    /** 저장된 세션 시간이 더 짧으면 그 값, 없거나 길면 [MAX_MINUTES] */
    fun minutes(saved: Int?): Int = saved?.takeIf { it in 1 until MAX_MINUTES } ?: MAX_MINUTES
}

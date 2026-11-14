package com.richjun.liftupai.domain.workout.util

/**
 * 맨몸 운동의 변형 사다리(쉬운 것 → 어려운 것, 2026-11-14 coach-autopilot-r1).
 *
 * 맨몸은 무게를 올릴 수 없으니 더 어려운 변형으로 과부하한다. "푸시업이 너무 쉬워요"·"스쿼트가 힘들어요"에 트레이너가
 * 바로 옆 단계로 바꿔 줄 수 있게 오늘 운동 도구(get_today_workout)가 한 단계 위·아래 변형을 함께 돌려준다.
 * 모든 단계는 플랜에 쓸 수 있고 영상·ko·ja·en 이름이 있다(BodyweightLadderTest). 필요한 도구(철봉 등)가 없는 단계는 건너뛴다.
 * 노르딕 컬·피스톨 같은 동작은 아직 카탈로그 검수 전이라 넣지 않았다.
 */
object BodyweightLadder {

    val LADDERS: Map<String, List<String>> = linkedMapOf(
        "push" to listOf("knee-push-up", "incline-push-up", "push-ups-bodyweight", "decline-push-up", "archer-push-up"),
        "squat" to listOf("bodyweight-wall-squat", "bodyweight-squat", "bulgarian-split-squat", "counterbalanced-skater-squat"),
        "upper_back" to listOf("superman", "superman-pulls", "inverted-row", "pull-up-normal-grip"),
    )

    private val positions: Map<String, Pair<String, Int>> =
        LADDERS.flatMap { (name, steps) -> steps.mapIndexed { i, slug -> slug to (name to i) } }.toMap()

    fun ladderOf(slug: String): String? = positions[slug]?.first

    /** 한 단계 어려운 변형(그 사람이 할 수 없는 단계는 건너뛴다). 맨 위면 null */
    fun harder(slug: String, canDo: (String) -> Boolean = { true }): String? = step(slug, +1, canDo)

    /** 한 단계 쉬운 변형. 맨 아래면 null */
    fun easier(slug: String, canDo: (String) -> Boolean = { true }): String? = step(slug, -1, canDo)

    private fun step(slug: String, dir: Int, canDo: (String) -> Boolean): String? {
        val (name, idx) = positions[slug] ?: return null
        val steps = LADDERS.getValue(name)
        var i = idx + dir
        while (i in steps.indices) {
            if (canDo(steps[i])) return steps[i]
            i += dir
        }
        return null
    }
}

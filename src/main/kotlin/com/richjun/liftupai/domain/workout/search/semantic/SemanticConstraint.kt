package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc

/**
 * 의미 검색 후보에 거는 하드 조건 — 어휘 검색이 그 질의에 적용하는 조건과 같은 것이다.
 *
 * 카테고리·장비 필터, 부정한 운동·장비·부위("점프 빼고", "機械を使わずに", "가슴 말고"), 부상 관절의 금지·주의 동작,
 * 집·생활 사물·"장비 없이" 의도의 장비 범위. 어휘 엔진이 만든다([com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine.semanticConstraint]).
 */
interface SemanticConstraint {
    /** 이 운동을 의미 검색 후보로 써도 되는가. false 면 어떤 유사도여도 결과에 넣지 않는다. */
    fun allows(doc: ExerciseSearchDoc): Boolean

    /** 이름이 아니라 부위·목표·장비·상황을 물은 질의인가(의도 질의). 이름 질의는 어휘 일치가 더 믿을 만하다. */
    val intent: Boolean

    /** 질의에 어휘 사전이 못 알아들은 덩어리(별칭·오타·새 표현)가 있는가 — 이름 일치가 확실하지 않다는 신호 */
    val unrecognized: Boolean get() = false

    /** 부상 관절 조건이 풀려 있는가 — 이때 어휘 순위는 의미 검색이 모르는 정보(관절별 권장·주의 동작)를 담고 있다 */
    val injuryAware: Boolean get() = false
}

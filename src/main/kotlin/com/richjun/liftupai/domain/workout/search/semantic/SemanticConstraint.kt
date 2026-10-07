package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.entity.Equipment
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

    /** 질의에 어휘 사전이 못 알아들은 덩어리(별칭·오타·새 표현)가 있는가 — 이름 일치가 확실하지 않다는 신호 */
    val unrecognized: Boolean get() = false

    /** 부상 관절 조건이 풀려 있는가 — 이때 어휘 순위는 의미 검색이 모르는 정보(관절별 권장·주의 동작)를 담고 있다 */
    val injuryAware: Boolean get() = false

    /**
     * 어휘 검색이 질의에서 붙들 긍정 신호(개념·장비·집·스트레칭·난이도·부상·이름 조각·모르는 말)가 하나라도 있는가.
     * false 면 질의가 부정·군더더기뿐이라("no ab wheel, similar effect") 어휘 순위는 인기 순 목록일 뿐 질의와 상관이 없다 — 융합에서 어휘 순위를 믿지 않는다.
     */
    val lexicalInformative: Boolean get() = true

    /**
     * 사전이 다 알아들은 부위(·장비) 키워드 질의("어깨", "バーベル 背中", "bodyweight back") — 부정·부상·집·난이도·모르는 말이 없다.
     * 이런 질의는 어휘 엔진이 큐레이션한 순서가 이미 정답에 가깝고 LLM 힌트는 순서를 흔들기만 한다 — LLM 을 부르지 않는다.
     */
    val broadKeyword: Boolean get() = false

    /** 집에서(장비 언급·필터 없음) — 섞은 결과에서도 맨몸 운동을 먼저 보여 준다(밴드·덤벨은 그 뒤) */
    val homeBodyweightFirst: Boolean get() = false

    /**
     * "벤치프레스 보조로 삼두"처럼 어떤 운동을 돕는 목표 말투일 때 말로 언급된 그 운동 자체(들)의 id.
     * 목표 부위 운동과 LLM 힌트가 이 운동들보다 앞서도록 융합 결과 상위에서 뒤로 미룬다. 해당 없으면 빈 집합.
     */
    val goalLiftIds: Set<Long> get() = emptySet()

    /**
     * 질의의 내용 토큰이 이 운동의 이름에서 통째 단어로 나오는가("삼각형" ⊂ "역삼각형" 같은 합성어 속 부분 문자열은 아니다).
     * 이름 일치가 확실하다고 보고 LLM 을 건너뛰거나 1위를 고정하기 전에 확인한다. 내용 토큰이 없으면 true.
     */
    fun wholeTokenNameMatch(doc: ExerciseSearchDoc): Boolean = true

    /**
     * 질의가 직접 말한 장비("cable fly", "덤벨 컬") — LLM 이 낸 제외 장비가 사용자가 방금 요청한 장비를 지우지 못하게 융합에서 뺀다.
     * 수건처럼 생활 사물을 장비 자리에 쓴 말(밴드 대용)은 사전이 틀릴 수 있어 여기에 넣지 않는다 — 그 경우는 LLM 의 읽기를 믿는다.
     */
    val requestedEquipment: Set<Equipment> get() = emptySet()

    /**
     * 의도 질의에서 사전이 요청으로 읽어 하드 조건으로 건 장비 가운데, 사전이 모르는 말이 질의에 섞여 있어 그 읽기를 믿을 수 없는 것.
     * 사전이 모르는 부정 관용구("ケトルベルはパスで")를 장비 요청으로 오인하면 어휘·의미 후보가 그 장비로만 좁혀져, LLM 의 올바른 제외가
     * 걸러 낼 것이 없어 되돌려진다 — LLM 이 이 장비를 제외했고 LLM 자신의 힌트도 그 장비를 쓰지 않으면 [HybridSearch] 가 제외로 바꿔 다시 찾는다.
     */
    val lexiconEquipment: Set<Equipment> get() = emptySet()
}

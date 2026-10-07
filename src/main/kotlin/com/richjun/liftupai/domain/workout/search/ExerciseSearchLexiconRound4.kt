package com.richjun.liftupai.domain.workout.search

/**
 * 동의어 사전 4차 확장 (표가 데이터다).
 *
 * 헬스장 은어·약칭·숫자 이름·묘사형 별칭 — 카탈로그가 다른 이름으로 부르는 운동으로 보낸다.
 * 형식은 ExerciseSearchLexicon.SYNONYM_RULES 와 같다("트리거|트리거 => 확장|확장", `=>>` 강한 별칭, `=>>>` 한 방향 강한 별칭).
 */
internal object ExerciseSearchLexiconRound4 {

    val RULES: List<String> = listOf(
        // T바 로우 — 일본·한국에서는 T바·티바
        "~Tバー|ティーバー|T バー|티바|티 바|T바|T 바|t bar|tbar|t-bar|t bar row|tbar row|Tバーロウ|Tバーロー|티바 로우|T바 로우 =>>> t bar row|티바 로우|Tバーロウ",
        // V싯업 = V업·V크런치
        "~Vシット|Vシットアップ|V字腹筋|V字シットアップ|V 싯업|V싯업|브이싯업|브이 싯업|v sit|vsit|v sit up|v-sit|v situp|jackknife|jack knife|ジャックナイフ|잭나이프 =>>> V up|V 업|Vアップ|V crunches|V 크런치|Vクランチ",
        // 21 레프법 컬
        "~21カール|21レップ|21レップ法|21 レップ|21s curl|21s curls|21s|21 curl|21 curls|21컬|21 컬|21개 컬|21 방식|21방식|21 rep curl|twenty one curl|twenty one curls|twenty-one curls =>>> 21s|21|21レップ法|21레프 컬",
        // 버터플라이 머신 = 펙 덱 플라이
        "~蝶々マシン|蝶マシン|ちょうちょマシン|バタフライマシン|バタフライ マシン|蝶々|나비 머신|나비머신|butterfly machine|butterfly fly machine|butterfly fly =>>> 펙 덱 플라이 머신|pec deck fly machine|ペックフライマシン",
        // 물구나무 푸시업 = 파이크 푸시업(핸드스탠드 푸시업의 입문 변형)
        "~^逆立ち腕立て|逆立ち腕立て伏せ|逆立ちプッシュアップ|倒立腕立て|倒立腕立て伏せ|倒立プッシュアップ|물구나무 푸시업|물구나무 푸시업|물구나무 푸쉬업|물구나무서서 푸시업|물구나무 팔굽혀펴기|handstand push up|handstand pushup|handstand push ups|handstand press|handstand press up|hspu|wall handstand push up|inverted push up|handstand pushups =>>> 파이크 푸시업|pike push up|パイクプッシュアップ|핸드스탠드 푸시|handstand push|ハンドスタンド・プッシュ",
        // 역손 턱걸이 = 친업(손바닥이 얼굴 쪽)
        "~역손 턱걸이|역손턱걸이|역수 턱걸이|역수턱걸이|역손 풀업|역손풀업|역손 친업|역손잡이 턱걸이|언더그립 턱걸이|언더 그립 턱걸이|reverse grip pull up|reverse grip pullup|underhand pull up|underhand pullup|supinated pull up|supinated pullup|逆手プルアップ|逆手チンニング =>>> 친업|chin up|チンアップ",
        // 복근 휠을 묘사한 말 / 무릎 대고 굴리기
        "~바퀴 굴리는 복근|바퀴 굴리는|바퀴 복근|바퀴 굴리기|바퀴로 하는 복근|휠 굴리는|휠 굴리기|膝コロ|ひざコロ|膝ころ|膝コロ腹筋|コロコロ腹筋|ころころ腹筋|knee rollout|knee roll out|knee rollouts|wheel rollout|roller wheel abs|rolling wheel abs =>>> ab wheel|AB 휠|アブローラー",
        // 농부 걷기
        "~ファーマーズキャリー|ファーマーズ キャリー|ファーマーズウォーク|ファーマーズ ウォーク|ファーマーウォーク|파머스 캐리|파머 캐리|파머스 워크|파머 워크|농부 걷기|농부걷기|farmers carry|farmer carry|farmers walk|farmer walk|farmers walks|farmer walks|suitcase carry|loaded carry =>>> farmer walks|파머 워크|ファーマーズウォーク",
        // 터키시 겟업
        "~トルコ式ゲットアップ|トルコ式起き上がり|トルコ式|ターキッシュゲットアップ|ターキッシュ ゲットアップ|터키식 겟업|터키식겟업|터키시 겟업|터키시겟업|터키쉬 겟업|turkish getup|turkish get up|turkish get-up|tgu =>>> turkish get up|터키시 겟업|ターキッシュゲットアップ",
        // 줄넘기 이단 뛰기
        "~二重跳び|二重とび|二重飛び|にじゅうとび|二重跳|이단 뛰기|이단뛰기|이단 줄넘기|이단줄넘기|더블 언더|더블언더|double under|double unders|double-unders|doubleunders =>>> jump rope double bounce|줄넘기 더블 바운스|縄跳び ダブルバウンス"
    )
}

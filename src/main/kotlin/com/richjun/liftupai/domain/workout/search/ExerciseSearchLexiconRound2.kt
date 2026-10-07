package com.richjun.liftupai.domain.workout.search

/**
 * 동의어 사전 2차 확장 (표가 데이터다).
 *
 * - [JARGON_RULES]: 일본·한국 헬스장 통칭·브랜드명·은어 → 카탈로그가 부르는 이름. 같은 종류의 말은 한 줄에 모은다(기구 이름, 동작 별칭 …).
 * - [gripRules]: 그립 수식어(逆手·順手·パラレル·언더핸드·neutral·wide·close …) × 기본 동작(풀업·랫풀다운·로우·벤치프레스·푸시업)의
 *   조합을 표로 펼친다. "逆手懸垂" 는 친업, "パラレルグリップ懸垂" 는 뉴트럴 그립 풀업처럼 그립이 가리키는 변형 운동으로 보낸다.
 *
 * 형식은 ExerciseSearchLexicon.SYNONYM_RULES 와 같다("트리거|트리거 => 확장|확장", `=>>` 는 강한 별칭).
 */
internal object ExerciseSearchLexiconRound2 {

    val JARGON_RULES: List<String> = listOf(
        // 프렌치 프레스 = 머리 위·누워서 하는 삼두 익스텐션
        "フレンチプレス|フレンチ プレス|ライイングフレンチプレス|フレンチカール|프렌치 프레스|프렌치프레스|프렌치 익스텐션|french press|french curl|lying french press|overhead french press|triceps french press|tricep french press =>>> 오버헤드 익스텐션|overhead extension|triceps extension|skull crusher|오버헤드 트라이셉스|オーバーヘッドエクステンション|トライセプスエクステンション|スカルクラッシャー",
        // 리어레이즈 = 후면 삼각근 레이즈/플라이
        "リアレイズ|リアーレイズ|リアデルトレイズ|リアラテラルレイズ|ベントオーバーレイズ|ベントオーバーラテラルレイズ|リアデルト レイズ|리어레이즈|리어 레이즈|리어 델트 레이즈|후면 레이즈|rear raise|rear raises|rear delt raise|rear lateral raise|rear delts raise|bent over lateral raise|bent over raise|bent over rear raise|reverse raise|rear delt flye =>>> 리어 델트 플라이|rear delt fly|rear lateral raise|rear delt raise|reverse fly|リアデルトフライ|リアラテラルレイズ|リバースフライ",
        // 매달려서 하는 복근 = 행잉 레그/니 레이즈
        "ぶら下がり腹筋|ぶらさがり腹筋|ぶら下がりレッグレイズ|ぶら下がりニーレイズ|ぶら下がって腹筋|懸垂腹筋|鉄棒腹筋|ハンギング腹筋|ハンギングレッグレイズ|ハンギングニーレイズ|매달려 복근|매달려서 복근|매달려서 하는 복근|매달리기 복근|철봉 복근|철봉 레그레이즈|철봉 레그 레이즈|행잉 복근|hanging abs|hanging ab|hanging core|hanging leg raises|hanging knee raises|bar hang abs|pull up bar abs|captains chair|captain chair|toes to bar|toe to bar|knees to chest hanging|hanging knee tuck =>>> 행잉 레그 레이즈|hanging leg raise|hanging knee raise|ハンギングレッグレイズ|ハンギングニーレイズ|행잉 니 레이즈|hanging oblique crunch",
        // 철봉에 매달리기
        "ぶら下がり|ぶらさがり|ぶら下がり健康法|ぶらさがり健康法|매달리기|철봉 매달리기|dead hang|deadhang|bar hang|hanging hold|passive hang|デッドハング|ぶら下がるだけ => 데드 행 스트레칭|dead hang stretch|デッドハングストレッチ|dead hang",
        // 실내 자전거·자전거 타기
        "自転車漕ぎ|自転車こぎ|ペダル漕ぎ|ペダリング|ペダルを漕ぐ|サイクルマシン|バイクマシン|室内自転車|室内サイクリング|バイクエクササイズ|자전거 타기|자전거 운동|자전거운동|자전거 페달|페달 밟기|바이크 머신|사이클 머신|pedaling|pedalling|pedal exercise|bike riding|bike ride|cycling machine|bike machine|cycle machine|exercise bike|spin class|spinning class|indoor cycling|indoor bike =>>> 스테이셔너리 바이크|stationary bike|spin bike|recumbent bike|outdoor cycling|エクササイズバイク|스피닝 바이크",
        // 계단 오르기 기구(스테어마스터·스텝밀)
        "階段マシン|階段昇降マシン|階段昇降機|階段登り|階段のぼり|階段上り|階段運動|階段昇降|階段ステッパー|ステアマスター|ステアクライマー|ステップマシン|ステアマシン|ステップミル|스테어마스터|스테어 마스터|스테어 클라이머|스텝밀|계단 머신|계단머신|계단 오르기|계단오르기|계단 운동|계단운동|계단 기구|계단 오르는|천국의 계단|천국의계단|stairmaster|stair master|stair climber|stairclimber|stair machine|stair stepper|stair stepping|stairs machine|stepmill|step mill|climbing stairs|stair climbing|stair walker|staircase machine =>>> 스텝밀|stair climber|ステアクライマー",
        // 스트레칭 폴·폼롤러·근막 이완
        "ストレッチポール|ストレッチ ポール|ストレッチポ―ル|ヨガポール|ヨガ ポール|ヨガローラー|フォームローラー|フォームローリング|マッサージローラー|筋膜ローラー|筋膜リリース|筋膜はがし|スティックローラー|스트레칭 폴|스트레칭폴|폼롤러|폼 롤러|폼롤링|폼 롤링|근막 롤러|근막롤러|근막 이완|근막이완|마사지 롤러|요가 롤러|foam roller|foam rolling|foam roll|foamroller|roller massage|massage roller|myofascial release|trigger point roller|stretch pole|stretching pole|stretching roller|yoga roller|lacrosse ball =>>> 폼롤러|foam roller|フォームローラー",
        // 복근 휠·복근 롤러
        "복근 휠|복근휠|애브 휠|애브휠|앱 휠|앱휠|에이비 휠|에이비휠|에이비 롤러|복근 롤러|복근롤러|복근 롤아웃|롤아웃 휠|휠 롤아웃|휠 복근|롤러 복근|ホイール腹筋|アブホイール|アブ ホイール|腹筋ホイール|腹筋ローラー|腹筋 ローラー|アブローラー|アブ ローラー|ローラー腹筋|ホイールローラー|ab wheel|abwheel|abs wheel|abdominal wheel|abdominal roller|ab roller|ab rollers|abroller|wheel rollout|rollout wheel|wheel roller|ab wheel rollout =>>> AB 휠|ab wheel|アブローラー|롤아웃|rollout|ロールアウト",
        // 머신 체스트 플라이 = 펙 덱
        "chest fly machine|chest flye machine|chest fly on machine|chest fly in machine|machine chest fly|machine chest flye|pec fly|pec flye|pec fly machine|pec flye machine|fly machine|flye machine|seated chest fly|seated fly machine|チェストフライマシン|チェストフライ マシン|マシンチェストフライ|ペックフライ|ペックフライマシン|マシンフライ|バタフライマシン|가슴 플라이 머신|체스트 플라이 머신|머신 가슴 플라이|가슴 머신 플라이|펙 플라이|펙플라이|버터플라이 머신|나비 머신 =>>> 펙 덱 플라이 머신|pec deck fly machine|ペックフライマシン|pec deck|ペックデック",
        // 밀리터리 프레스 — 숄더·오버헤드 프레스와 한 묶음이면 숄더 프레스 머신이 이름 정확 일치로 앞서 버린다
        "밀리터리프레스|밀리터리 프레스|밀프|military press|militarypress|ミリタリープレス|ミリタリー プレス|ミリプレ =>>> military press|밀리터리 프레스|ミリタリープレス",
        // 일본 헬스장 줄임말 — 끝 음절을 자른 형태("レッグエクステ")는 사전 단어가 아니라서 이름 검색이 헛돈다
        "レッグエクステ|レグエクステ|レッグエクス|레그 익스텐|레익스텐 =>>> 레그 익스텐션|leg extension|レッグエクステンション",
        "ハムカール|ハムストリングスカール|햄 컬|햄컬 =>>> 레그 컬|leg curl|レッグカール|hamstring curl",
        "アームカール|アムカール|アムカ|암컬|암 컬|ダンベルアームカール =>>> 컬|curl|カール|dumbbell curl|biceps curl",
        "サイドレ|サイレ|사이드 레터럴|사레레 =>>> 레터럴 레이즈|lateral raise|ラテラルレイズ|サイドレイズ",
        "フロントレ|フロレ =>>> 프론트 레이즈|front raise|フロントレイズ",
        "シーロー|케이블 시티드 로우 =>>> 시티드 로우|seated row|シーテッドロー|cable row",
        "デッドリ|デドリ|デッドリフ =>>> 데드리프트|deadlift|デッドリフト",
        "バーピーテスト|버피테스트|버피 테스트|burpee test|burpees test =>>> 버피|burpee|バーピー",
        // 스텝퍼(작은 계단 기구)는 스텝업 계열과 구분한다 — 스테어마스터는 위의 규칙
        "스텝퍼|stepper|ステッパー => step up|스텝업|ステップアップ|stepper|스텝퍼"
    )

    // ── 그립 × 기본 동작 ─────────────────────────────────────────────────────────────────────

    private val UNDER = "逆手|さかて|ぎゃくて|언더핸드|언더 핸드|언더그립|언더 그립|리버스 그립|리버스그립|역그립|역수|underhand|under hand|underhand grip|supinated|supinated grip|reverse grip|reversegrip|リバースグリップ|アンダーハンド|アンダーグリップ|スピネイト"
    private val OVER = "順手|じゅんて|오버핸드|오버 핸드|오버그립|오버 그립|프로네이티드|순수|overhand|over hand|overhand grip|pronated|pronated grip|オーバーハンド|オーバーグリップ|ノーマルグリップ|普通のグリップ|노멀 그립|normal grip|regular grip|standard grip"
    private val NEUTRAL = "パラレル|パラレルグリップ|平行グリップ|ニュートラル|ニュートラルグリップ|ハンマーグリップ|뉴트럴|뉴트럴 그립|패러렐|패러렐 그립|파라렐|중립 그립|중립그립|해머 그립|neutral|neutral grip|parallel|parallel grip|hammer grip"
    private val WIDE = "ワイド|ワイドグリップ|広め|広いグリップ|와이드|와이드 그립|넓은 그립|넓게|wide|wide grip|wider grip"
    private val CLOSE = "ナロー|ナローグリップ|クローズ|クローズグリップ|狭め|狭いグリップ|클로즈|클로즈 그립|좁은 그립|좁게|narrow|narrow grip|close|close grip|closer grip"

    private val ASSIST = "アシスト|アシステッド|어시스트|보조|assisted|assist|machine assisted|machine assist|マシンアシスト|アシストマシン"
    private val DIP = "ディップス|ディップ|딥스|딥|dips|dip"

    private val PULLUP = "懸垂|けんすい|プルアップ|チンニング|풀업|턱걸이|철봉|pull up|pullup|pull-up|pull ups|chinning"
    private val CHINUP = "チンアップ|친업|chin up|chinup|chin ups"
    private val PULLDOWN = "ラットプルダウン|ラットプル|プルダウン|랫풀다운|랫 풀다운|랫풀|풀다운|lat pulldown|lat pull down|lat pull|pulldown|pull down"
    private val ROW = "ロー|ローイング|ベントオーバーロー|バーベルロー|シーテッドロー|로우|바벨 로우|벤트오버 로우|시티드 로우|row|rows|barbell row|bent over row|seated row|cable row"
    private val BENCH = "ベンチプレス|ベンチ|벤치프레스|벤치|bench press|bench"
    private val PUSHUP = "腕立て伏せ|腕立て|プッシュアップ|푸시업|푸쉬업|팔굽혀펴기|push up|pushup|push ups"

    /** (그립 표기들, 기본 동작 표기들, 확장) */
    private class GripRule(val grips: String, val bases: String, val expansions: String)

    private val GRIP_RULES = listOf(
        // 逆手(언더핸드) — 풀업은 친업, 풀다운·로우는 리버스 그립 변형
        GripRule(UNDER, PULLUP, "친업|chin up|チンアップ|reverse grip pull up|underhand pull up|chin up reverse close grip|chin up reverse wide grip"),
        GripRule(UNDER, CHINUP, "친업|chin up|チンアップ|chin up reverse close grip|chin up reverse wide grip"),
        GripRule(UNDER, PULLDOWN, "reverse grip lat pull down|리버스 그립 랫 풀다운|リバースグリップ ラットプルダウン|underhand lat pulldown"),
        GripRule(UNDER, ROW, "reverse grip row|underhand row|reverse grip barbell row|bent over row reverse grip|리버스 그립 로우|リバースグリップ ロー"),
        // 順手(오버핸드) — 기본 그립
        GripRule(OVER, PULLUP, "pull up normal grip|풀업|pull up|プルアップ|懸垂"),
        GripRule(OVER, PULLDOWN, "lat pull down|랫풀다운|ラットプルダウン|lat pull down wide grip"),
        GripRule(OVER, ROW, "bent over row|barbell bent over row pronated grip|ベントオーバーロー|벤트 오버 로우"),
        // パラレル·뉴트럴
        GripRule(NEUTRAL, PULLUP, "pull up neutral grip|neutral grip pull up|뉴트럴 그립 풀업|ニュートラルグリップ懸垂|chin ups narrow parallel grip|parallel grip chin up"),
        GripRule(NEUTRAL, CHINUP, "chin ups narrow parallel grip|ナローパラレルグリップ チンアップ|패러렐 그립 친업|neutral grip pull up"),
        GripRule(NEUTRAL, PULLDOWN, "neutral grip lat pulldown|parallel grip lat pulldown|close grip lat pull down|클로즈 그립 랫 풀다운|クローズグリップ ラットプルダウン"),
        GripRule(NEUTRAL, ROW, "neutral grip row|hammer grip row|parallel grip row|close grip seated row|v bar row|seated row"),
        // 와이드
        GripRule(WIDE, PULLUP, "pull up wide grip|풀업 와이드 그립|ワイドグリップ プルアップ"),
        GripRule(WIDE, PULLDOWN, "lat pull down wide grip|와이드 그립 랫 풀다운|ラットプルダウン ワイドグリップ"),
        GripRule(WIDE, ROW, "wide grip row|wide grip seated row|wide grip cable row"),
        GripRule(WIDE, BENCH, "wide grip bench press|와이드 그립 벤치프레스|ワイドグリップ ベンチプレス"),
        GripRule(WIDE, PUSHUP, "wide push up|와이드 푸시업|ワイド プッシュアップ|wide hand push up"),
        // 클로즈·나로우
        GripRule(CLOSE, PULLUP, "pull up close grip|chin up reverse close grip|narrow grip pull up|ナローグリップ プルアップ|클로즈 그립 풀업"),
        GripRule(CLOSE, PULLDOWN, "lat pull down close grip|클로즈 그립 랫 풀다운|クローズグリップ ラットプルダウン|close grip lat pulldown"),
        GripRule(CLOSE, ROW, "close grip row|close grip seated row|narrow grip row"),
        GripRule(CLOSE, BENCH, "close grip bench press|클로즈 그립 벤치프레스|クローズグリップ ベンチプレス|narrow grip bench press"),
        GripRule(CLOSE, PUSHUP, "close grip push up|diamond push up|ダイヤモンド プッシュアップ|ナロー プッシュアップ|다이아몬드 푸시업|narrow push up|triangle push up"),
        // 어시스트(보조) — 기구가 체중을 덜어 주는 변형
        GripRule(ASSIST, PULLUP, "assisted pull up|어시스트 풀업|アシステッド プルアップ|assisted pull up wide grip"),
        GripRule(ASSIST, CHINUP, "assisted chin up|어시스트 친업|アシステッド チンアップ|assisted chin up on a bench"),
        GripRule(ASSIST, DIP, "assisted dip|어시스트 딥|アシステッド ディップ|assisted tricep dip")
    )

    /** 그립 수식어와 기본 동작이 붙어 있는 질의(두 순서 모두)를 그립이 가리키는 변형 운동으로 보내는 규칙들 */
    val gripRules: List<String> by lazy {
        GRIP_RULES.map { r ->
            val grips = r.grips.split("|")
            val bases = r.bases.split("|")
            val triggers = LinkedHashSet<String>()
            for (g in grips) for (b in bases) {
                triggers.add("$g$b")
                triggers.add("$b$g")
            }
            triggers.joinToString("|") + " =>>> " + r.expansions
        }
    }

    val ALL: List<String> by lazy { JARGON_RULES + gripRules }
}

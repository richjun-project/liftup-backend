package com.richjun.liftupai.domain.workout.search

/**
 * 동의어 사전 3차 확장 (표가 데이터다).
 *
 * 자연어로 묻는 질의의 "별칭": 일상 사물·장소(椅子·壁·평행봉), 동작을 풀어 쓴 말("膝を伸ばす", "무릎 높이 올리며 제자리 뛰기"),
 * 활동("泳ぎたい"), 요가 자세의 일본식 이름(鳩のポーズ) — 카탈로그가 다른 이름으로 부르는 운동으로 보낸다.
 * 형식은 ExerciseSearchLexicon.SYNONYM_RULES 와 같다("트리거|트리거 => 확장|확장", `=>>` 강한 별칭, `=>>>` 한 방향 강한 별칭).
 */
internal object ExerciseSearchLexiconRound3 {

    val RULES: List<String> = listOf(
        // 랫풀다운 머신의 통칭
        "~ラットマシン|ラット マシン|ラットプルマシン|ラット引き|랫머신|랫 머신|랫풀 머신|lat machine|lats machine|lat pull machine|lat pulldown machine|lat pull down machine|ラットプルダウンマシン|랫풀다운 머신 =>>> 랫풀다운|lat pull down|lat pulldown|ラットプルダウン|cable pulldown",
        // 일상 사물: 벽·의자·바닥 — 카탈로그는 월(ウォール)·체어(チェア)·플로어로 적는다
        "~壁|かべ|壁際|壁沿い|壁の前|壁面|벽|벽면|벽 앞 =>>> wall|ウォール|월",
        "~椅子|いす|イス|의자 =>>> chair|チェア|체어",
        // 철봉에 매달리는 동작 = 데드 행
        "~ぶら下がる|ぶらさがる|ぶら下がるだけ|鉄棒ぶら下がり|철봉에 매달리기|매달려서 버티기|매달려 버티기|매달리기 운동|dead hang|deadhang|bar hang|passive hang|hanging hold|hang from a pull up bar|hang from pull up bar =>>> 데드 행 스트레칭|dead hang stretch|デッドハングストレッチ|dead hang",
        // 철봉에 매달려 척추를 늘리는 것 = 데드 행 스트레칭
        "~decompress|decompress spine|decompress my spine|spine decompression|spinal decompression|척추 감압|척추 늘리기|척추 늘려|척추 이완|척추 펴기|背骨を伸ばす|背骨を伸ばしたい|脊椎を伸ばす|背骨のストレッチ|脊椎ストレッチ|背骨の圧迫を取る|腰の圧迫を取る|腰を牽引|牽引ストレッチ =>>> dead hang stretch|데드 행 스트레칭|デッドハングストレッチ|dead hang",
        // 흉추 가동성
        "~胸椎|胸椎ストレッチ|胸椎を動かす|胸椎を回す|胸椎回旋|胸椎の動き|胸椎を柔らかく|흉추|흉추 스트레칭|흉추 회전|흉추 가동성|흉추 풀기|흉추 움직|t spine|t-spine|tspine|thoracic|thoracic spine|thoracic mobility|thoracic spine mobility|thoracic extension|thoracic rotation|upper back mobility|upper back rotation =>>> thoracic rotation|흉추 회전|胸椎回旋|open book",
        // 개각·스플릿
        "~股割り|またわり|股割りストレッチ|スプリッツ|스플리츠|the splits|do the splits|full splits|front splits|middle splits|side splits|フロントスプリット|サイドスプリット|180度開脚|180도 개각|전후 개각|좌우 개각 =>>> 비둘기 자세|pigeon pose|ピジョンポーズ|butterfly yoga pose|90 to 90 stretch|cossack squat",
        // 기구를 동작으로 묘사한 말 → 그 기구 ("脚を外に開くマシン", "다리를 모으는 머신")
        "~脚を外に開く|足を外に開く|脚を開く|足を開く|股を開く|膝を外に開く|다리를 바깥으로 벌리는|다리를 벌리는|다리 벌리는|무릎을 바깥으로 벌리는|spread your legs|push your legs apart|open your legs|legs apart|push knees apart|push your knees out =>>> 힙 어브덕션 머신|hip abduction machine|ヒップアブダクション|hip abductor machine",
        "~脚を閉じる|足を閉じる|脚を内側に閉じる|太ももの内側を閉じる|太ももを閉じる|膝を閉じる|膝を内側に閉じる|다리를 모으는|다리를 안쪽으로 모으는|다리를 오므리는|허벅지 안쪽을 조이는|허벅지를 모으는|squeeze your legs together|bring your legs together|close your legs|squeeze your knees together|push knees together =>>> 힙 어덕션 머신|hip adduction machine|ヒップアダクション|hip adductor machine",
        "~腕を内側に閉じる|腕を閉じる|胸の前で腕を閉じる|腕を前で合わせる|腕を胸の前に寄せる|腕を前に寄せる|팔을 안쪽으로 모으는|팔을 모으는|팔을 앞으로 모으는|가슴 앞으로 팔을 모으는|squeeze your arms together|bring your arms together|bring arms together in front|close your arms in front|hug motion|arms together in front =>>> 펙 덱 플라이 머신|pec deck fly machine|ペックフライマシン|pec fly",
        "~胸を押す|胸を押し出す|胸の前に押し出す|前に押し出す|가슴을 미는|가슴 앞으로 미는|앞으로 밀어내는|push your chest forward|push forward from your chest|press away from your chest|push the handles forward =>>> 체스트 프레스 머신|chest press machine|チェストプレス",
        "~背中に引く|背中に引き寄せる|胸に引き寄せる|ハンドルを引き寄せる|ハンドルを体に引く|등으로 당기는|등을 당기는|몸쪽으로 당기는|손잡이를 당기는|pull the handles toward you|pull the handles to your body|pull toward your back|row the handles =>>> 시티드 로우 머신|seated row machine|シーテッドロウ",
        "~上から引き下げる|上から引く|上から下に引く|バーを引き下げる|위에서 아래로 당기는|위에서 당겨 내리는|바를 아래로 당기는|바를 끌어내리는|pull a bar down from above|pull the bar down|pull down from above|bar down to your chest =>>> 랫풀다운|lat pulldown|ラットプルダウン",
        "~肩を押し上げる|肩を上に押し上げる|頭上に押し上げる|頭の上に押す|어깨를 위로 밀어 올리는|머리 위로 밀어 올리는|머리 위로 미는|press weight overhead|push the handles overhead|press overhead seated =>>> 숄더 프레스 머신|shoulder press machine|ショルダープレス",
        "~お腹を丸める|腹を丸める|体を丸める|上体を丸める|배를 말아 올리는|상체를 말아 올리는|몸통을 구부리는|curl your torso|crunch forward|curl your upper body forward =>>> 복근 크런치 머신|abdominal crunch machine|アブドミナルクランチ|abdominal crunches machine",
        "~体を反らす|背中を反らす|上体を反らす|上体を起こして反らす|몸을 뒤로 젖히는|상체를 젖히는|허리를 젖히는|arch your back backward|extend your back|extend your torso backward =>>> 백 익스텐션 머신|back extension machine|バックエクステンション|hyperextension",
        "~腕を押し下げる|腕を下に押す|ハンドルを押し下げる|팔을 아래로 미는|손잡이를 아래로 미는|push the handles down|push down with your arms|press your arms down =>>> 삼두 익스텐션 머신|tricep extension machine|トライセプスエクステンション|triceps pushdown",
        "~足首を伸ばす|つま先を伸ばす|足首を押し下げる|발목을 펴는|발끝으로 미는|발끝을 펴는|point your toes|push through your toes|push with your toes =>>> 카프 레이즈 머신|calf raise machine|カーフレイズ",
        // 평행봉·딥 스테이션
        "~平行棒|평행봉|parallel bars|parallel bar|parallettes|dip bars|dip bar|dip station|dipping bars|ディップバー|ディップスタンド|ディップステーション|ディップス台|딥 바|딥바|딥 스테이션|딥스테이션 =>>> 딥|dip|ディップ|chest dip|ディップス|hanging leg raise",
        // 요가 자세의 일본식 이름
        "~鳩のポーズ|はとのポーズ|鳩のポース|ピジョンのポーズ|비둘기 자세|비둘기자세|비둘기 포즈|pigeon pose|pigeon stretch|pigeon yoga|pigeon =>>> 비둘기 자세|pigeon pose|ピジョンポーズ",
        "~猫のポーズ|ねこのポーズ|猫と牛のポーズ|キャットアンドカウ|캣 앤 카우|캣앤카우|고양이 소 자세|고양이 자세|cat and cow|cat camel|cat cow pose|cat cow =>>> 캣 카우|cat cow|キャットカウ",
        "~子供のポーズ|こどものポーズ|赤ちゃんのポーズ|아기 자세|아기자세|childs pose|child pose|childs|balasana =>>> 차일드 포즈|child pose|チャイルドポーズ",
        "~蝶のポーズ|ちょうのポーズ|バタフライのポーズ|나비 자세|나비자세|butterfly pose|butterfly stretch|cobbler pose|bound angle =>>> 버터플라이 요가 포즈|butterfly yoga pose|バタフライ ヨガ ポーズ",
        "~コブラのポーズ|蛇のポーズ|코브라 자세|코브라자세|cobra pose|cobra stretch|cobra yoga =>>> 코브라 요가 포즈|cobra yoga pose|コブラ ヨガ ポーズ",
        "~橋のポーズ|はしのポーズ|다리 자세|브릿지 자세|bridge pose|setu bandhasana|setubandhasana =>>> 브릿지 포즈|bridge pose|ブリッジ ポーズ",
        // 헤엄·수영
        "~水の中で運動|水の中で運動したい|水中運動|水中ウォーキング|水中エクササイズ|水の中|물속 운동|물 속 운동|수중 운동|수중운동|물에서 하는 운동|water workout|water exercise|aquatic exercise|aqua workout|in the water|泳ぎたい|泳ぎ|泳ぐ|泳げる|泳ぎに行|水泳|スイミング|プール|プールで|수영|수영하고 싶|수영하고싶|수영하고 싶어|수영장|수영장에서|수영하는|swim|swimming|go swimming|平泳ぎ|クロール泳ぎ|背泳ぎ|バタフライ泳ぎ|자유형|평영|배영|접영|breaststroke|backstroke|front crawl|freestyle swimming|want to swim|wanna swim|lap swimming|swim laps|swimming laps|swimming pool|pool workout|pool exercise|in the pool =>>> 수영|swimming|水泳",
        // 무릎 높이 올리며 제자리에서 뛰기 = 하이니
        "~もも上げ|ももあげ|腿上げ|モモ上げ|もも上げ運動|腿上げ運動|무릎 높이 올리며 제자리 뛰기|무릎 높이 올리며 뛰기|무릎 높이 올리며 달리기|무릎을 높이 올리며 뛰기|무릎 높이 들기|무릎 높이 올리기|무릎 높이 올려|제자리 무릎 올리기|제자리 뛰기|제자리뛰기|제자리 달리기|제자리에서 뛰기|제자리에서 달리기|하이 니|하이니|running in place|run in place|jog in place|jogging in place|running on the spot|run on the spot|jogging on the spot|march in place|marching in place|knees up|lift knees high|raise knees high|その場駆け足|その場で駆け足|その場ジョグ|その場ランニング|その場で走る|その場足踏み|膝を高く上げる|膝を高く上げて走る|膝を高く上げて|膝を上げて走る =>>> 하이 니|high knees|ハイニー",
        // 발뒤꿈치를 들어 올리는 운동 = 카프 레이즈
        "~ふくらはぎ上げ|ふくらはぎあげ|ふくらはぎを上げ|ふくらはぎを上げる|かかと上げ|かかとあげ|踵上げ|踵あげ|かかと上げ運動|踵を上げる|かかとを上げる|つま先立ち|爪先立ち|つま先立ち運動|つま先立ちする|종아리 올리기|종아리 들기|종아리 들어올리기|발뒤꿈치 들기|발뒤꿈치 올리기|뒤꿈치 들기|뒤꿈치 올리기|뒤꿈치를 들|발꿈치 들기|발꿈치 올리기|발끝으로 서기|까치발|까치발 들기|heel raise|heel raises|heels raise|raise heels|raise your heels|rise on toes|rise onto toes|stand on tiptoes|standing on tiptoes|tiptoe stand|tiptoe raise|tip toe raise|go on tiptoes =>>> 카프 레이즈|calf raise|カーフレイズ",
        // 동작을 풀어 쓴 말 → 그 동작의 이름 (설명문 검색이 못 잡는 자주 쓰는 표현만 표로)
        "~横から腕を上げる|腕を横から上げる|腕を横に上げる|横に腕を上げる|腕を横へ上げる|横へ腕を上げる|腕を横に持ち上げる|横に上げる|サイドに腕を上げる|横から腕を上げる運動|팔을 옆으로 올리|팔을 옆으로 올리기|팔 옆으로 올리기|옆으로 팔 올리기|옆으로 팔 들기|팔 옆으로 들기|팔을 옆으로 들|옆으로 올리기|옆으로 들어올리기|옆으로 들기|raise arms to the sides|raise arms to the side|raise arms sideways|raise your arms sideways|raise your arms to the sides|lift arms to the sides|lift arms sideways|arms out to the side|arm raise to the side|raise arms laterally|sideways arm raise|side arm raise|raise arms out to the side =>>> 레터럴 레이즈|lateral raise|サイドレイズ|ラテラルレイズ|side lateral raise",
        "~前に腕を上げる|腕を前に上げる|腕を前から上げる|前から腕を上げる|腕を前へ上げる|팔을 앞으로 올리|팔 앞으로 올리기|앞으로 팔 올리기|앞으로 팔 들기|팔 앞으로 들기|raise arms forward|raise arms to the front|raise your arms forward|raise arms in front|lift arms forward|lift arms in front|front arm raise|arms forward raise =>>> 프론트 레이즈|front raise|フロントレイズ",
        "~お尻を持ち上げる|お尻を上げる|お尻を浮かせる|腰を持ち上げる|腰を浮かせる|腰を上げる|엉덩이 들기|엉덩이를 들어|엉덩이 올리기|엉덩이를 올리|엉덩이 들어올리기|엉덩이를 들어올리|raise your hips|lift your hips|lift hips|raise hips|hip lift|hip raise|bridge up =>>> 글루트 브리지|glute bridge|グルートブリッジ|ヒップスラスト|hip thrust",
        "~肩をすくめる|肩をすくめて|肩を上げ下げ|肩をすぼめる|어깨 으쓱|어깨를 으쓱|어깨 들썩|어깨를 들썩|어깨 으쓱하기|shrug shoulders|shrug your shoulders|shoulder shrug|shrugging shoulders =>>> 슈러그|shrug|シュラッグ",
        "~膝を抱える|膝を抱え込む|膝を胸に引き寄せる|膝を胸に近づける|膝を胸に|무릎을 가슴으로|무릎 가슴으로|무릎 안기|무릎 끌어안|무릎을 안|무릎 껴안|hug knees|hug your knees|knees to chest|knee to chest|pull knees to chest|pull your knees to your chest|bring knees to chest =>>> 니 체스트 스트레칭|knee to chest stretch|ニー トゥ チェスト",
        "~壁にもたれてしゃがむ|壁に背中をつけて座る|壁に寄りかかって座る|壁に背中をつけてしゃがむ|壁にもたれて座る|壁に背をつけて|벽에 기대어 앉기|lean against the wall and sit|sit against the wall|sit with your back against the wall|back against the wall squat|wallsit|wall sit =>>> 월 싯|wall sit|ウォールシット|wall squat",
        "~膝を伸ばす|膝を伸ばして|膝を伸ばす運動|膝伸ばし|脚を伸ばす|무릎을 펴|무릎을 펴는|무릎 펴기|무릎 펴는|다리를 펴|다리 펴기|extend knee|extend your knee|extend your knees|extending knees|straighten knee|straighten your knees|straighten your legs|kick your legs out|extend legs =>>> 레그 익스텐션|leg extension|レッグエクステンション",
        "~膝を曲げる|膝を曲げて|膝を曲げる運動|膝曲げ|무릎을 굽|무릎을 굽히|무릎을 굽혀|무릎 굽히기|무릎 굽히는|무릎을 구부|무릎 구부리기|bend knee|bend your knee|bend your knees|bending knees|curl your legs|curl legs|curl your heels to your glutes|heels to glutes =>>> 레그 컬|leg curl|レッグカール",
        "~上体を起こす|上体起こし|体を起こす|상체 일으키|상체 일으키기|상체를 일으키|윗몸 일으키기|윗몸일으키기|몸 일으키기|sit up from lying|situp from lying|curl your upper body up|lift your upper body|raise your upper body|raise your torso =>>> 싯업|sit up|シットアップ|crunch",
        "~寝て足を上げる|寝て脚を上げる|寝転がって足を上げる|仰向けで足を上げる|仰向けで脚を上げる|누워서 다리 올리|누워서 다리 들|누워서 다리 들어올리|누워서 다리를 올리|누워서 다리를 들|lying leg raise|raise legs while lying|lift legs while lying|raise your legs while lying|lift your legs lying down|legs up while lying =>>> 레그 레이즈|leg raise|レッグレイズ",
        "~脚を横に上げる|足を横に上げる|脚を横へ上げる|脚を外に開く|다리를 옆으로 올리|다리 옆으로 들|옆으로 다리 들|옆으로 다리를 들|raise leg to the side|raise your leg to the side|lift leg sideways|lift your leg sideways|leg out to the side =>>> 사이드 레그 레이즈|side leg raise|サイドレッグレイズ|hip abduction|hip abductor|힙 어브덕션|アブダクター",
        "~脚を後ろに蹴る|足を後ろに蹴る|脚を後ろに上げる|足を後ろに上げる|脚を後ろへ|다리를 뒤로 차|다리를 뒤로 올리|뒤로 다리 들|뒤로 다리를 들|kick leg back|kick your leg back|kick back your leg|leg back kick|raise leg backward|lift your leg back =>>> 킥백|kickback|donkey kick|キックバック",
        "~上体をひねる|体をひねる|体幹をひねる|上半身をひねる|상체 비틀|상체를 비틀|몸통 비틀|몸통을 비틀|몸통 회전|twist your torso|twist your body|torso twist|trunk rotation|rotate your torso|rotate torso =>>> 트위스트|twist|ツイスト|russian twist"
    )

    /** 집·방·바닥에서 하는 운동을 뜻하는 말 (홈트 의도) */
    val HOME_EXTRA: String = "ワンルーム|ワンルームで|원룸|원룸에서|원룸 안에서|방에서|방안에서|방 안에서|방 안|자취방에서|部屋で|部屋の中|部屋の中で|家の中|家の中で|リビング|リビングで|거실|거실에서|寝室|寝室で|침실|침실에서|ベッドの上|ベッドで|침대 위|침대에서|침대 위에서|床で|床の上|床の上で|바닥에서|바닥 위에서|바닥 운동|매트 위|매트 위에서|マットの上|マットの上で|floor workout|floor exercise|floor exercises|on the floor|on the ground|bedroom|living room|studio apartment|dorm|dorm room|tiny apartment|small apartment|small room|my room|in my room|in a small room|in a small apartment|in a studio|in a studio apartment|no gym|without gym|without a gym|without going to the gym|dont have a gym|don't have a gym|no gym membership|no gym access|ジム無し|ジムなし|ジムに行けない|ジムに行かずに|ジム以外|헬스장 없이|헬스장 없는|헬스장 안 가고|헬스장 못 가|헬스장 못 갈|헬스장 안가고|집에 있는|집에 있을 때|집에 있을때|家にいる|家にいるとき|家にいる時|stuck at home|staying at home|stay at home|quarantine|isolation|lockdown|宅内|室内で|실내에서|실내 운동|indoor|indoors|indoor workout"

    /** 수건·타월로 하는 운동은 밴드 대용 */
    val TOWEL: String = "towel|towels|bath towel|タオル|バスタオル|수건|타월|목욕 수건"

    /** 덤벨 한 쌍·세트 같은 장비 표현 */
    val DUMBBELL_EXTRA: String = "water bottle|water bottles|bottles of water|plastic bottle|plastic bottles|bottle weights|ペットボトル|ペットボトルで|水のペットボトル|水入りペットボトル|물병|물병으로|페트병|페트병으로|생수병|생수병으로|backpack|rucksack|loaded backpack|バックパック|リュック|배낭|책가방|one pair of dumbbells|a pair of dumbbells|single pair of dumbbells|a single pair of dumbbells|pair of dumbbells|one set of dumbbells|a set of dumbbells|set of dumbbells|just dumbbells|only dumbbells|dumbbells only|dumbbell only|just a pair of dumbbells|only a pair of dumbbells|only one pair of dumbbells|ダンベル1組|ダンベル一組|ダンベル1セット|ダンベル一セット|ダンベル1ペア|ダンベルだけ|ダンベルのみ|ダンベルしかない|ダンベルしか|덤벨 한 쌍|덤벨 한쌍|덤벨 한 세트|덤벨 하나로|덤벨만|덤벨만으로|덤벨밖에|덤벨 1쌍|덤벨 1세트"
}

package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.MuscleGroup as G
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory as C

/**
 * 운동 검색 어휘 사전 (ko/ja/en).
 * - 동의어 그룹: 슬랭·약어·표기 변이 → 카탈로그 이름에 실제로 등장하는 표현으로 확장
 * - 근육/부위 사전: "하체", "お尻", "glutes" 같은 의도 질의 → 카테고리/근육군
 * - 장비 사전: "덤벨", "自重", "home" 같은 장비 의도
 * 모든 표기는 SearchText 로 정규화되어 인덱싱되므로 띄어쓰기/전각/히라가나·가타카나 차이는 신경 쓰지 않아도 된다.
 */
internal object ExerciseSearchLexicon {

    class MuscleTerm(val muscles: Set<MuscleGroup>, val categories: Set<ExerciseCategory>)

    /** strong 이면 확장 표현이 원 질의보다 우선한다(카탈로그가 다른 이름으로 부르는 운동 — 별칭이 정답 이름을 가리키는 경우) */
    class Synonym(val triggers: Set<String>, val expansions: List<List<String>>, val strong: Boolean = false, val noTypo: Boolean = false)

    private fun key(raw: String) = SearchText.strictKey(raw)

    // ── 동의어 그룹: "트리거|트리거 => 확장|확장" (트리거는 확장도 포함) ─────────────────────────────
    private val BASE_SYNONYM_RULES = listOf(
        "벤치|밴치|벤치프레스|밴치프레스|bench|ベンチ => 벤치프레스|bench press|ベンチプレス",
        "인클라인|incline|インクライン => 인클라인|incline|インクライン",
        "풀업|턱걸이|철봉|치닝|친닝|친업|chin up|chinning|pull up|懸垂|鉄棒|チンニング|チンアップ|プルアップ => 풀업|pull up|プルアップ",
        "랫풀다운|랫풀|랫 풀다운|래트풀다운|래트 풀다운|랫트풀다운|렛풀다운|lat pulldown|lat pull down|lat pull|latpulldown|lats pulldown|ラットプルダウン|ラットプル => 랫풀다운|lat pulldown|ラットプルダウン",
        "풀다운|pulldown|pull down|プルダウン => 풀다운|pulldown|プルダウン",
        "푸시업|푸쉬업|팔굽혀펴기|팔굽혀|腕立て伏せ|腕立て|プッシュアップ|push up|pushup|press up => 푸시업|push up|プッシュアップ",
        "딥스|딥|dips|dip|ディップス|ディップ => 딥|dip|ディップ",
        "힙쓰러스트|힙스러스트|힙트러스트|힙 쓰러스트|hip thrust|ヒップスラスト => 힙 쓰러스트|hip thrust|ヒップスラスト",
        "글루트브릿지|글루트 브리지|글루트브리지|힙브릿지|힙 브리지|glute bridge|hip bridge|グルートブリッジ|ヒップブリッジ => 글루트 브리지|glute bridge|グルートブリッジ",
        "스컬크러셔|스컬 크러셔|스카이크러셔|라잉 트라이셉스 익스텐션|lying triceps extension|skull crusher|skullcrusher|スカルクラッシャー => 스컬 크러셔|skull crusher|スカルクラッシャー",
        "ohp|오헤프|오헤|오버헤드프레스|오버헤드 프레스|overhead press|숄더프레스|숄더 프레스|shoulder press|ショルダープレス|オーバーヘッドプレス => 오버헤드 프레스|숄더프레스|overhead press|shoulder press|オーバーヘッドプレス|ショルダープレス",
        "레터럴레이즈|래터럴레이즈|레터럴 레이즈|래터럴 레이즈|사이드레이즈|사이드 레이즈|사레레|사이드 레터럴 레이즈|side raise|lateral raise|lat raise|side lateral raise|サイドレイズ|ラテラルレイズ|ラテラル => 레터럴 레이즈|lateral raise|サイドレイズ|ラテラルレイズ",
        "프론트레이즈|전면 레이즈|front raise|フロントレイズ => 프론트 레이즈|front raise|フロントレイズ",
        "데드리프트|데드|데들|데드 리프트|dead lift|deadlift|dl|デッドリフト|デッド|デットリフト => 데드리프트|deadlift|デッドリフト",
        "루마니안 데드리프트|루마니안|rdl|romanian deadlift|ルーマニアン => 루마니안 데드리프트|romanian deadlift|ルーマニアンデッドリフト",
        "스티프 레그 데드리프트|스티프|stiff leg deadlift|stiff leg|スティッフレッグ => 스티프 레그 데드리프트|stiff leg deadlift|スティッフレッグ",
        "스쿼트|스콰트|스쿼드|스쿠아트|스퀏|squat|スクワット|スクワツト|スクウォット|スクォット|スクヮット|スクワァット|スクワッド|스쿠와트|스쿼또 => 스쿼트|squat|スクワット",
        "백스쿼트|백 스쿼트|back squat|バックスクワット|풀 스쿼트|full squat|フルスクワット => 풀 스쿼트|full squat|フルスクワット|back squat",
        "프론트 스쿼트|front squat|フロントスクワット => 프론트 스쿼트|front squat|フロントスクワット",
        "불가리안|bulgarian|ブルガリアン => 불가리안|bulgarian|ブルガリアン",
        "카프레이즈|카프 레이즈|카프|종아리 레이즈|calf raise|calf|カーフレイズ|カーフ => 카프 레이즈|calf raise|カーフレイズ",
        "페이스풀|페이스 풀|face pull|facepull|フェイスプル => 페이스풀|face pull|フェイスプル",
        "슈러그|슈럭|시러그|어깨 으쓱|shrug|シュラッグ => 슈러그|shrug|シュラッグ",
        "크런치|크런치|crunch|クランチ => 크런치|crunch|クランチ",
        "싯업|시트업|sit up|situp|シットアップ => 싯업|sit up|シットアップ",
        "플랭크|플랭크|plank|プランク => 플랭크|plank|プランク",
        "버피|burpee|バーピー => 버피|burpee|バーピー",
        "런지|lunge|ランジ => 런지|lunge|ランジ",
        "케이블 크로스오버|케이블 크로스 오버|cable crossover|cable cross over|crossover|크로스오버|ケーブルクロスオーバー|クロスオーバー =>> 케이블 플라이|cable fly|ケーブルフライ|케이블 미들 플라이",
        "펙덱|펙 덱|pec deck|ペックデック|버터플라이|butterfly|machine fly|머신 플라이|ペックフライ => 펙 덱 플라이|pec deck fly|ペックフライ",
        "시티드로우|시티드 로우|seated row|シーテッドロー|케이블 로우|cable row => 시티드 로우|seated row|シーテッドロー",
        "로우|row|ロー|ロウ|ローイング => 로우|row|ロー|ロウ|ローイング",
        "원암|원 암|싱글암|싱글 암|원핸드|원 핸드|한팔|한 팔|one arm|single arm|one hand|single hand|ワンアーム|ワンハンド|シングルアーム|片手|片腕 => 싱글 암|원 암|single arm|one arm|シングルアーム|ワンアーム|ワンハンド",
        "바벨로우|바벨 로우|barbell row|bent over row|벤트오버로우|벤트 오버 로우|ベントオーバーロー => 벤트 오버 로우|bent over row|ベントオーバーロー",
        "이두컬|이두 컬|바이셉스 컬|바이셉 컬|bicep curl|biceps curl|バイセップスカール|バイセップカール|バイセップ カール|바이셉 컬 => 컬|curl|カール",
        "해머컬|해머 컬|hammer curl|ハンマーカール => 해머 컬|hammer curl|ハンマーカール",
        "푸시다운|푸쉬다운|삼두 푸시다운|tricep pushdown|pushdown|プッシュダウン => 푸시다운|pushdown|プッシュダウン",
        "고블릿|goblet|ゴブレット => 고블릿|goblet|ゴブレット",
        "굿모닝|good morning|グッドモーニング => 굿모닝|good morning|グッドモーニング",
        "레그프레스|레그 프레스|leg press|レッグプレス => 레그 프레스|leg press|レッグプレス",
        "레그컬|레그 컬|레컬|렉컬|햄스트링 컬|hamstring curl|leg curl|レッグカール|ハムストリングカール => 레그 컬|leg curl|レッグカール",
        "레그익스텐션|레그 익스텐션|leg extension|レッグエクステンション => 레그 익스텐션|leg extension|レッグエクステンション",
        "앱휠|ab wheel|ab roller|アブローラー|아브롤러 => 롤아웃|rollout|ロールアウト",
        "점핑잭|jumping jack|ジャンピングジャック => 점핑 잭|jumping jack|ジャンピングジャック",
        "앱덕션|어브덕션|어브덕트|앱덕터|어브덕터|abduction|abductor|abduct|アブダクション|アブダクター => 앱덕션|앱덕터|어브덕션|어브덕터|abduction|abductor|アブダクション|アブダクター",
        "애덕션|어덕션|어덕터|애덕터|adduction|adductor|adduct|アダクション|アダクター => 애덕션|애덕터|어덕션|어덕터|adduction|adductor|アダクション|アダクター",
        "나로우 그립|내로우 그립|좁은 그립|협그립|ナローグリップ|ナロウグリップ|narrow grip|close grip|クローズグリップ|클로즈 그립 => 클로즈 그립|close grip|クローズグリップ|ナローグリップ|narrow grip",
        "바컬|바벨컬|바벨 컬|バーカール|bb curl|barbell curl => 바벨 컬|barbell curl|バーベルカール",
        "덤컬|덤벨컬|ダンカ|db curl => 덤벨 컬|dumbbell curl|ダンベルカール",
        "바로우|바벨로우|bb row|bent row => 바벨 로우|barbell row|바벨 벤트 오버 로우|bent over row|ベントオーバーロー",
        "덤로우|덤벨로우|db row => 덤벨 로우|dumbbell row|ダンベルロー",
        "벤프|bp|ベンプ|벤치프레스 => 벤치프레스|bench press|ベンチプレス",
        "바프|바벨프레스 => 바벨 프레스|barbell press|バーベルプレス",
        "덤프|덤벨프레스|db press => 덤벨 프레스|dumbbell press|ダンベルプレス",
        "숄프|숄더프|ショルプレ|ショルダープレ => 숄더프레스|shoulder press|ショルダープレス",
        "레프|레그프|レグプレ|レッグプレ => 레그 프레스|leg press|レッグプレス",
        "레익|레그익|레익스 => 레그 익스텐션|leg extension|レッグエクステンション",
        "시로우|시티드로우|시티드 로 => 시티드 로우|seated row|シーテッドロー",
        "스데|스티프데드|스티프 데드 => 스티프 레그 데드리프트|stiff leg deadlift|스티프 데드",
        "루데|루마니안데드|루마니안 데드 => 루마니안 데드리프트|romanian deadlift|ルーマニアンデッドリフト",
        "스쿼|스쿼트|スクワ|スクワット => 스쿼트|squat|スクワット",
        "프레레|프론트레이즈 => 프론트 레이즈|front raise|フロントレイズ",
        "사이레|サイレ|사이드레이즈 => 사이드 레이즈|side raise|lateral raise|サイドレイズ|레터럴 레이즈",
        "복근 롤러|腹筋ローラー|腹筋ロ―ラー|아브롤러|ab roller|ab wheel|앱 휠 => 롤아웃|rollout|ab wheel|アブローラー|ロールアウト",
        "아놀드 프레스|아놀드프레스|arnold press|アーノルドプレス => 아놀드 프레스|arnold press|アーノルドプレス",
        "아기 자세|아기자세|子供のポーズ|こどものポーズ|チャイルド ポーズ|child's pose|childs pose|balasana => 차일드 포즈|child pose|チャイルドポーズ",
        "캣카우|고양이 자세|猫のポーズ|キャットカウ|cat cow|cat camel => 캣 스트레칭|cat stretch|キャットストレッチ",
        "코브라 자세|コブラのポーズ|cobra pose|bhujangasana => 코브라 요가 포즈|cobra yoga pose|コブラヨガポーズ",
        "로드바이크|ロードバイク|road bike|クロスバイク|실내 자전거|실내자전거|室内バイク|ルームバイク => 스테이셔너리 바이크|stationary exercise bike|エクササイズバイク",
        "조정|조정 머신|조정머신|ボート漕ぎ|ボートこぎ|ボート|rower|로워|ローワー => 로잉 머신|rowing machine|ローイングマシン",
        "인클|인클라인프레스|인클라인 벤치|インクラインベンチ|incline bench|incline press|인벤 => 인클라인 벤치프레스|incline bench press|インクラインベンチプレス",
        "플랭|plank hold|プランクホールド => 플랭크|plank|プランク",
        "크런|クランチ운동|ab crunch => 크런치|crunch|クランチ",
        "힙쓰|힙트|힙쓰러|힙스|ヒプスラ|ヒップスラ => 힙 쓰러스트|hip thrust|ヒップスラスト",
        "ブルスク|ブルガリ|불스|불가스 => 불가리안 스플릿 스쿼트|bulgarian split squat|ブルガリアンスプリットスクワット",
        "フロスク|프론스|프스 => 프론트 스쿼트|front squat|フロントスクワット",
        "ハックスク|ハンスク|핵스 => 핵 스쿼트|hack squat|ハックスクワット",
        "ベンプレ|ベンプ => 벤치프레스|bench press|ベンチプレス",
        "ダンプレ|ダンベルプレ => 덤벨 프레스|dumbbell press|ダンベルプレス",
        "런닝머신|런닝|ランニングマシーン => treadmill|트레드밀|러닝머신|トレッドミル|ランニングマシン",
                "프리쳐 컬|프리처 컬|프리쳐컬|프리처컬|프리쳐|프리처|preacher curl|preacher|プリーチャーカール|プリーチャー|プリチャーカール => 프리처 컬|preacher curl|プリーチャーカール",
        "와이드 스쿼트|와이드스쿼트|wide squat|wide stance squat|wide stance|ワイドスクワット|ワイドスタンススクワット|ワイドスタンス => 스모 스쿼트|sumo squat|スモウスクワット|와이드 스탠스",
        "hex bar deadlift|hex bar|hexbar|헥스바 데드리프트|헥스바|헥스 바|ヘックスバー|ヘックスバーデッドリフト|트랩바 => 트랩 바 데드리프트|trap bar deadlift|トラップバーデッドリフト",
        "리버스 펙덱|리버스펙덱|reverse pec deck|reverse fly machine|reverse machine fly|rear delt machine| リバースペックデック|リバースフライマシン|리어델트 머신|リアデルトマシン =>> 리어 델트 플라이|rear delt fly|reverse fly|リアデルトフライ|リバースフライ",
        "sldl|싱글 레그 데드리프트|single leg deadlift|ワンレッグデッドリフト|シングルレッグデッドリフト => 싱글 레그 데드리프트|single leg deadlift|stiff leg deadlift|スティッフレッグ",
        "자전거|싸이클|사이클|사이클링|サイクル|サイクリング|自転車|エアロバイク|에어로바이크|바이크|バイク|bike|cycling|cycle|stationary bike|exercise bike|spin bike|스피닝|spinning|スピンバイク|エクササイズバイク|스테이셔너리 바이크 =>> 스테이셔너리 바이크|stationary exercise bike|エクササイズバイク|stationary bike|spin bike|recumbent bike|outdoor cycling|실내 자전거",
        "케틀벨 스윙|kettlebell swing|ケトルベルスイング => 케틀벨 스윙|kettlebell swing|ケトルベルスイング",
        "마운틴클라이머|mountain climber|マウンテンクライマー => 마운틴 클라이머|mountain climber|マウンテンクライマー",
        "플라이|fly|flye|フライ => 플라이|fly|フライ",
        "프레스|press|プレス => 프레스|press|プレス",
        "로잉|로잉머신|rowing|rowing machine|ローイングマシン => 로우 머신|rowing machine|ローイングマシン",
        "점핑잭|점프잭|jumping jack|jumpingjack|ジャンピングジャック => 점프 잭|jumping jack|ジャンピングジャック",
        "윗몸일으키기|윗몸 일으키기|sit up|situp|シットアップ|腹筋運動 => 싯업|sit up|シットアップ",
        "브이업|v up|vup|Vアップ => v 업|v up|Vアップ",
        "줄넘기|jump rope|jumprope|縄跳び|なわとび => 줄넘기|jump rope|縄跳び",
        "스미스머신|스미스|smith machine|smith|スミスマシン|スミス => 스미스 머신|smith machine|スミスマシン",
        "걷기|ウォーキング|walking|walk|산책 => 걷기|walk|ウォーク|walking",
        "러닝머신|트레드밀|트레드 밀|treadmill|ランニングマシン|トレッドミル|ルームランナー|ランニングマシーン => treadmill|트레드밀|トレッドミル",
        "러닝|running|ランニング|조깅|jogging|jog|ジョギング => running|jogging|러닝|ランニング|ジョギング",
        "어깨|숄더|shoulder|ショルダー|肩 => 어깨|숄더|shoulder|ショルダー",
        "가슴|체스트|chest|チェスト|胸 => 가슴|체스트|chest|チェスト",
        "삼두|삼두근|트라이셉스|triceps|tricep|トライセプス|上腕三頭筋 => 삼두|트라이셉스|triceps|トライセプス",
        "이두|이두근|바이셉스|biceps|bicep|バイセップス|上腕二頭筋 => 이두|바이셉스|biceps|バイセップス",
        "엉덩이|둔근|글루트|glute|glutes|グルート|お尻|臀筋 => 글루트|힙|glute|グルート|ヒップ",
        "복근|abs|abdominal|アブ|腹筋 => 복근|abdominal|アブドミナル|crunch",
        "종아리|calf|calves|カーフ|ふくらはぎ => 카프|종아리|calf|カーフ",
        "햄스트링|hamstring|ハムストリング => 햄스트링|hamstring|ハムストリング|leg curl"
    )

    /** 내장 동의어 전체: 기본 사전 + 2차 확장(은어·브랜드·그립 조합) */
    val SYNONYM_RULES: List<String> = BASE_SYNONYM_RULES + ExerciseSearchLexiconRound2.ALL + ExerciseSearchLexiconRound3.RULES

    /** "트리거|트리거 => 확장|확장" 한 줄을 해석한다. 형식이 틀리면 null (런타임 DB 동의어 줄이 서버를 깨뜨리지 않게). */
    fun parseSynonymRule(rule: String): Synonym? {
        // 줄 맨 앞의 "~" 는 이 줄의 트리거를 오타 교정 어휘에 넣지 않는다 — 동작을 풀어 쓴 긴 구절이 비슷한 다른 말의 "교정 후보"가 되지 않게
        val noTypo = rule.trim().startsWith("~")
        val trimmed = rule.trim().removePrefix("~").trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=>")) return null
        val strong = trimmed.contains("=>>")
        // "=>>>" 는 한 방향 강한 별칭 — 왼쪽만 트리거다(확장에 일반어("chin up")가 있어도 그 단어 질의가 이 규칙으로 번지지 않는다)
        val oneWay = trimmed.contains("=>>>")
        val parts = trimmed.split(Regex("=>{1,3}"), limit = 2).map { it.trim() }
        if (parts.size != 2) return null
        val l = parts[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val r = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (l.isEmpty() || r.isEmpty()) return null
        val expansions = r.map { SearchText.words(it) }.filter { it.isNotEmpty() }
        val base = (if (oneWay) l else l + r).map { key(it) }.filter { it.isNotEmpty() }
        // 한자 표기의 가나 읽기("懸垂" → "けんすい")도 같은 그룹의 트리거로 — 일본 사용자는 둘 다 쓴다
        val triggers = (base + base.flatMap { KanjiReadings.variants(it) }).toSet()
        if (expansions.isEmpty() || triggers.isEmpty()) return null
        return Synonym(triggers = triggers, expansions = expansions, strong = strong, noTypo = noTypo)
    }

    /** 동의어 사전과 그 색인. 엔진마다 기본 사전 + 런타임 추가분(extraSynonyms)으로 만든다. */
    class SynonymTable(rules: List<String>) {
        val synonyms: List<Synonym> = rules.mapNotNull { parseSynonymRule(it) }

        /** 트리거 키 → 해당 동의어 그룹들 */
        val index: Map<String, List<Synonym>> = buildMap<String, MutableList<Synonym>> {
            synonyms.forEach { s -> s.triggers.forEach { getOrPut(it) { mutableListOf() }.add(s) } }
        }

        /** 표기 변이(ㅐ/ㅔ, 쌍자음, 장음·촉음)를 접은 키 → 동의어 그룹. 외래어 철자가 달라도 같은 그룹으로 찾는다. */
        val looseIndex: Map<String, List<Synonym>> = buildMap<String, MutableList<Synonym>> {
            synonyms.forEach { s ->
                s.triggers.map { SearchText.looseKey(it) }.toSet().forEach { getOrPut(it) { mutableListOf() }.add(s) }
            }
        }
    }

    val defaultSynonymTable: SynonymTable = SynonymTable(SYNONYM_RULES)

    fun synonymTable(extra: List<String>): SynonymTable =
        if (extra.isEmpty()) defaultSynonymTable else SynonymTable(SYNONYM_RULES + extra)

    val synonyms: List<Synonym> get() = defaultSynonymTable.synonyms
    val synonymIndex: Map<String, List<Synonym>> get() = defaultSynonymTable.index
    val synonymLooseIndex: Map<String, List<Synonym>> get() = defaultSynonymTable.looseIndex

    // ── 질의에 없을 때 뒤로 미루는 수식어(보조/한 팔) ──────────────────────────────────────────
    /** 이 표기가 이름에 있으면 변형 운동이다. 질의에 같은 종류의 표기가 없으면 기본 동작을 우선한다. */
    val modifierGroups: List<Set<String>> = listOf(
        "assisted|assist|어시스트|보조|アシスト|アシステッド",
        "one arm|single arm|one hand|single hand|원 암|싱글 암|원 핸드|ワンアーム|ワンハンド|シングルアーム|片手|片腕|한팔"
    ).map { g -> g.split("|").map { key(it) }.filter { it.isNotEmpty() }.toSet() }

    // ── 부위(근육) 의도 ────────────────────────────────────────────────────────────────────────
    private fun muscle(
        terms: String,
        muscles: Set<MuscleGroup>,
        cats: Set<ExerciseCategory>
    ): List<Pair<String, MuscleTerm>> =
        MuscleTerm(muscles, cats).let { mt -> terms.split("|").map { key(it) }.filter { it.isNotEmpty() }.map { it to mt } }

    val muscleTerms: Map<String, MuscleTerm> = buildList {
        addAll(muscle("하체|다리|下半身|美脚|脚線美|脚長|脚|足|レッグ|레그|legs|leg|lower body|lowerbody|하체운동|脚トレ|脚やせ|다리살|다리 운동|下肢|leg day|legday", setOf(G.LEGS, G.QUADRICEPS, G.HAMSTRINGS, G.GLUTES, G.CALVES), setOf(C.LEGS)))
        addAll(muscle("허벅지|대퇴사두|대퇴사두근|太もも|ふともも|大腿四頭筋|四頭筋|大腿四頭|quads|quad|quadriceps|thigh|thighs|前もも|前腿|太もも前|もも前|太もも前側|太ももの前|ももの前|腿の前|허벅지 앞|허벅지 앞쪽|허벅지앞|front of thigh|front of the thigh|front of legs|thigh front|앞허벅지|앞벅지|앞 허벅지|front thigh|クワッド|쿼드|허벅지살|太もも痩せ", setOf(G.QUADRICEPS, G.LEGS), setOf(C.LEGS)))
        addAll(muscle("햄스트링|biceps femoris|bicep femoris|대퇴이두근|hams|hammies|ham|ハムストリング|ハムストリングス|裏もも|裏腿|太ももの裏|太もも裏|太もも後ろ|太もも後ろ側|ももの後ろ|腿の裏|ももの裏|もも裏|大腿二頭筋|ハム|hamstrings|hamstring|대퇴이두|허벅지 뒤|허벅지 뒤쪽|허벅지 뒷쪽|허벅지 뒷|뒷벅지|뒤허벅지|뒷 허벅지|back of thigh|back of thighs|back of the thigh|back of legs|thigh back|posterior chain", setOf(G.HAMSTRINGS), setOf(C.LEGS)))
        addAll(muscle("엉덩이|둔근|대둔근|중둔근|소둔근|gluteus maximus|gluteus|glutes max|힙|お尻|おしり|尻|ヒップ|臀筋|大臀筋|殿筋|グルート|글루트|大殿筋|둔부|glutes|glute|butt|bum|hip|hips|엉덩이살|お尻痩せ", setOf(G.GLUTES), emptySet()))
        addAll(muscle("등|등운동|광배|광배근|latissimus dorsi|lat dorsi|バック|背中|広背筋|広背|背筋|背|背部|등근육|등 근육|back muscles|back|lats|lat|upper back|上背部|등 윗부분|윗등|ラット|랫", setOf(G.BACK, G.LATS, G.TRAPS), setOf(C.BACK)))
        addAll(muscle("man boobs|moobs|manboobs|male breast fat|chest fat|gynecomastia|남자 가슴살|가슴살|젖살|胸の脂肪|男の胸の脂肪|가슴|대흉근|胸|大胸筋|大胸|pectoralis major|pectoralis|pec major|チェスト|체스트|胸板|가슴근육|chest|pecs|pec|pectorals|pectoral|胸筋", setOf(G.CHEST), setOf(C.CHEST)))
        addAll(muscle("어깨|삼각근|肩|三角筋|ショルダー|숄더|肩の筋肉|shoulders|shoulder|delts|delt|deltoids|deltoid", setOf(G.SHOULDERS), setOf(C.SHOULDERS)))
        addAll(muscle("팔|腕|アーム|arms|arm|상완|팔운동|腕トレ|upper arm|upper arms", setOf(G.BICEPS, G.TRICEPS, G.FOREARMS), setOf(C.ARMS)))
        addAll(muscle("이두|이두근|brachialis|brachioradialis|上腕筋|腕橈骨筋|상완근|bis|bi|biceps brachii|上腕二頭筋|二頭筋|二頭|上腕二頭|力こぶ|バイセップス|바이셉스|biceps|bicep", setOf(G.BICEPS), setOf(C.ARMS)))
        addAll(muscle("振り袖|振りそで|振袖|ふりそで|二の腕のたるみ|二の腕のぷよぷよ|二の腕ぷよぷよ|ぷよぷよ二の腕|二の腕が気になる|二の腕のお肉|二の腕を細く|팔뚝 군살|팔뚝 처짐|팔뚝 늘어짐|팔 처짐|팔뚝살 빼기|처진 팔뚝|처진 팔|흔들리는 팔뚝|나비 팔뚝|나비팔|삼두|삼두근|tris|tri|long head triceps|triceps long head|삼두 장두|삼두장두|三頭筋長頭|上腕三頭筋長頭|三頭筋の長頭|triceps brachii|上腕三頭筋|三頭筋|三頭|上腕三頭|二の腕|二の腕痩せ|振袖肉|トライセプス|트라이셉스|팔뚝살|팔뚝|underarm flab|underarm fat|arm flab|arm fat|flabby arms|upper arm fat|bat wings|batwings|arm jiggle|triceps|tricep", setOf(G.TRICEPS), setOf(C.ARMS)))
        addAll(muscle("손목|手首|wrist|wrists|전완|전완근|前腕|forearms|forearm", setOf(G.FOREARMS), setOf(C.ARMS)))
        addAll(muscle("복근|코어|배|복|복부|복직근|복횡근|식스팩|6팩|腹筋|腹直筋|腹横筋|体幹|お腹|腹|コア|アブ|abs|ab|core|abdominals|abdominal|rectus abdominis|transverse abdominis|stomach|belly|six pack|sixpack", setOf(G.ABS, G.CORE), setOf(C.CORE)))
        addAll(muscle("종아리|비복근|가자미근|ふくらはぎ|腓腹筋|ヒラメ筋|カーフ|calves|calve|calf|gastrocnemius|soleus", setOf(G.CALVES), setOf(C.LEGS)))
        addAll(muscle("승모근|승모|僧帽筋|traps|trap|trapezius", setOf(G.TRAPS), setOf(C.SHOULDERS, C.BACK)))
        addAll(muscle("목|首|neck", setOf(G.NECK), emptySet()))
        addAll(muscle("전신|全身|full body|fullbody|whole body", emptySet(), setOf(C.FULL_BODY)))
        addAll(muscle("상체|上半身|upper body|upperbody", setOf(G.CHEST, G.BACK, G.SHOULDERS, G.BICEPS, G.TRICEPS, G.LATS, G.TRAPS), setOf(C.CHEST, C.BACK, C.SHOULDERS, C.ARMS)))
        addAll(muscle("유산소|有酸素|カーディオ|cardio|aerobic", emptySet(), setOf(C.CARDIO)))
    }.toMap()

    /**
     * 근육별 대표 동작 단어 — 의도 질의("엉덩이")에서 해당 근육이 주동근인 운동을 위로 올리는 신호.
     * 카탈로그의 muscleGroups 는 집합이라 주동근/보조근 구분이 없어서 이름으로 보완한다.
     */
    val signatureTerms: Map<MuscleGroup, List<String>> = run {
        fun sig(vararg terms: String) = terms.flatMap { it.split("|") }.map { key(it) }.filter { it.isNotEmpty() }
        mapOf(
            G.GLUTES to sig("hip thrust|glute|bridge|kickback|donkey kick|힙 쓰러스트|글루트|브리지|킥백|ヒップスラスト|グルート|ブリッジ|キックバック"),
            G.HAMSTRINGS to sig("leg curl|romanian|stiff leg|good morning|레그 컬|루마니안|스티프|굿모닝|レッグカール|ルーマニアン|スティッフ|グッドモーニング"),
            G.QUADRICEPS to sig("squat|leg press|leg extension|lunge|스쿼트|레그 프레스|레그 익스텐션|런지|スクワット|レッグプレス|レッグエクステンション|ランジ"),
            G.LEGS to sig("squat|leg press|lunge|leg curl|leg extension|스쿼트|레그 프레스|런지|레그 컬|スクワット|レッグプレス|ランジ|レッグカール"),
            G.CHEST to sig("bench press|chest press|fly|push up|pec deck|벤치프레스|체스트 프레스|플라이|푸시업|ベンチプレス|チェストプレス|フライ|プッシュアップ"),
            G.BACK to sig("row|pulldown|pull up|deadlift|로우|풀다운|풀업|데드리프트|ロウ|ロー|プルダウン|プルアップ|デッドリフト"),
            G.LATS to sig("pulldown|pull up|row|풀다운|풀업|로우|プルダウン|プルアップ|ロウ|ロー"),
            G.SHOULDERS to sig("shoulder press|overhead press|lateral raise|숄더프레스|오버헤드 프레스|레터럴 레이즈|ショルダープレス|オーバーヘッドプレス|ラテラルレイズ|サイドレイズ"),
            G.BICEPS to sig("curl|컬|カール"),
            G.TRICEPS to sig("pushdown|triceps extension|skull|dip|kickback|푸시다운|익스텐션|딥|プッシュダウン|エクステンション|ディップ"),
            G.FOREARMS to sig("wrist curl|리스트 컬|リストカール"),
            G.ABS to sig("crunch|plank|sit up|leg raise|크런치|플랭크|싯업|레그 레이즈|クランチ|プランク|シットアップ|レッグレイズ"),
            G.CORE to sig("crunch|plank|sit up|leg raise|크런치|플랭크|싯업|레그 레이즈|クランチ|プランク|シットアップ|レッグレイズ"),
            G.CALVES to sig("calf raise|calf|카프|종아리|カーフ"),
            G.TRAPS to sig("shrug|슈러그|シュラッグ")
        )
    }

    // ── 장비 의도 ──────────────────────────────────────────────────────────────────────────────
    private fun equip(terms: String, vararg eq: Equipment): List<Pair<String, Set<Equipment>>> =
        terms.split("|").map { key(it) }.filter { it.isNotEmpty() }.map { it to eq.toSet() }

    val equipmentTerms: Map<String, Set<Equipment>> = buildList {
        addAll(equip(ExerciseSearchLexiconRound3.DUMBBELL_EXTRA + "|덤벨|아령|dumbbell|dumbbells|db|ダンベル|one dumbbell|single dumbbell|a dumbbell|1 dumbbell|two dumbbells|a pair of dumbbells|pair of dumbbells|ダンベル1個|ダンベル一個|ダンベル一つ|ダンベル2個|덤벨 하나|덤벨 한개|덤벨 한 개|덤벨 1개|덤벨 두 개|덤벨 2개|덤벨 두개", Equipment.DUMBBELL))
        addAll(equip("바벨|barbell|bb|バーベル", Equipment.BARBELL))
        addAll(equip("머신|기구|machine|マシン|筋トレ器具|ジム器具|ジムの器具|トレーニング器具|トレーニングマシン|運動器具|헬스 기구|운동 기구|헬스장 기구", Equipment.MACHINE))
        addAll(equip("케이블|cable|ケーブル", Equipment.CABLE))
        addAll(equip("맨몸|맨몸운동|bodyweight|body weight|no equipment|noequipment|calisthenics|自重|器具なし|道具なし|徒手|장비없이|기구없이|without equipment|without any equipment|without equipments|no equipments|no equipment needed|equipment free|equipmentless|w/o equipment|no gear|without gear|no weights|without weights|no gym|without gym|gym free|no gym needed|器具を使わない|器具を使わずに|道具を使わない|道具を使わずに|器具無し|道具無し|器具不要|道具不要|장비 안 쓰는|장비 안 쓰고|기구 안 쓰는|기구 안 쓰고|장비 없는|기구 없는|도구 없이|도구 없는", Equipment.BODYWEIGHT))
        addAll(equip(ExerciseSearchLexiconRound3.TOWEL + "|밴드|저항밴드|저항 밴드|고무밴드|band|bands|resistance band|バンド|チューブ|レジスタンスバンド", Equipment.RESISTANCE_BAND))
        addAll(equip("케틀벨|kettlebell|ケトルベル", Equipment.KETTLEBELL))
    }.toMap()

    /** 오타 허용 매칭 대상 장비 어휘 (자모 기준 6자 이상인 것만 — 짧은 단어는 오탐이 많다) */
    val fuzzyEquipmentTerms: List<Pair<String, Set<Equipment>>> =
        equipmentTerms.entries.map { SearchText.looseKey(it.key) to it.value }.filter { it.first.length >= 6 }

    /** 집에서 할 수 있는 장비 (홈트 의도) — 맨몸 > 밴드 > 덤벨/케틀벨 순으로 선호 */
    val homeEquipmentPreference: Map<Equipment, Double> = mapOf(
        Equipment.BODYWEIGHT to 1.0,
        Equipment.RESISTANCE_BAND to 0.5,
        Equipment.DUMBBELL to 0.3,
        Equipment.KETTLEBELL to 0.25,
        Equipment.OTHER to 0.2
    )

    val homeTerms: Set<String> = listOf(
        "집|집에서|홈트|홈트레이닝|홈|자취방|자취|home|at home|athome|home workout|自宅|宅トレ|おうち|ホーム|家|家で|宅|hotel|hotel room|hotelroom|호텔|호텔방|ホテル|travel|traveling|출장|여행|여행지|旅行|旅先|small space|limited space|no space|좁은 곳|좁은 공간|狭い部屋|office|사무실|사무실에서|オフィス|オフィスで|desk workout|desk|at your desk|at work|workplace|책상|책상에서|デスク|デスクで"
    ).flatMap { it.split("|") }.plus(ExerciseSearchLexiconRound3.HOME_EXTRA.split("|")).map { key(it) }.filter { it.isNotEmpty() }.toSet()

    /** 의도 해석 시 무시하는 군더더기 단어 */
    val fillerTerms: Set<String> = listOf(
        "운동|운동법|하는법|방법|할수있는|가능한|트레|트레이닝|루틴|부위|추천|workout|exercise|exercises|training|day|routine|トレ|トレーニング|運動|筋トレ|エクササイズ|メニュー|no|for|my|best|good|and|with|only|하는|하기|の|を|で|に"
    ).flatMap { it.split("|") }.map { key(it) }.filter { it.isNotEmpty() }.toSet()
}

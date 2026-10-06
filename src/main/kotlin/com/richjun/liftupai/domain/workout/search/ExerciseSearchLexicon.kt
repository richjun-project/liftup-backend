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

    class Synonym(val triggers: Set<String>, val expansions: List<List<String>>)

    private fun key(raw: String) = SearchText.strictKey(raw)

    // ── 동의어 그룹: "트리거|트리거 => 확장|확장" (트리거는 확장도 포함) ─────────────────────────────
    private val SYNONYM_RULES = listOf(
        "벤치|밴치|벤치프레스|밴치프레스|bench|ベンチ => 벤치프레스|bench press|ベンチプレス",
        "인클라인|incline|インクライン => 인클라인|incline|インクライン",
        "풀업|턱걸이|치닝|친닝|친업|chin up|chinning|pull up|懸垂|チンニング|チンアップ|プルアップ => 풀업|pull up|プルアップ",
        "랫풀다운|랫풀|랫 풀다운|lat pulldown|lat pull|풀다운|pulldown|ラットプルダウン|ラットプル|プルダウン => 풀다운|pulldown|ラットプルダウン",
        "푸시업|푸쉬업|팔굽혀펴기|팔굽혀|腕立て伏せ|腕立て|プッシュアップ|push up|pushup|press up => 푸시업|push up|プッシュアップ",
        "딥스|딥|dips|dip|ディップス|ディップ => 딥|dip|ディップ",
        "힙쓰러스트|힙스러스트|힙트러스트|힙 쓰러스트|hip thrust|ヒップスラスト => 힙 쓰러스트|hip thrust|ヒップスラスト",
        "글루트브릿지|글루트 브리지|글루트브리지|힙브릿지|힙 브리지|glute bridge|hip bridge|グルートブリッジ|ヒップブリッジ => 글루트 브리지|glute bridge|グルートブリッジ",
        "스컬크러셔|스컬 크러셔|스카이크러셔|라잉 트라이셉스 익스텐션|lying triceps extension|skull crusher|skullcrusher|スカルクラッシャー => 스컬 크러셔|skull crusher|スカルクラッシャー",
        "ohp|오버헤드프레스|오버헤드 프레스|overhead press|숄더프레스|숄더 프레스|shoulder press|밀리터리프레스|밀리터리 프레스|military press|ショルダープレス|オーバーヘッドプレス|ミリタリープレス => 오버헤드 프레스|숄더프레스|overhead press|shoulder press|オーバーヘッドプレス|ショルダープレス",
        "레터럴레이즈|래터럴레이즈|레터럴 레이즈|래터럴 레이즈|사이드레이즈|사이드 레이즈|사레레|사이드 레터럴 레이즈|side raise|lateral raise|lat raise|side lateral raise|サイドレイズ|ラテラルレイズ|ラテラル => 레터럴 레이즈|lateral raise|サイドレイズ|ラテラルレイズ",
        "프론트레이즈|전면 레이즈|front raise|フロントレイズ => 프론트 레이즈|front raise|フロントレイズ",
        "데드리프트|데드|데들|데드 리프트|dead lift|deadlift|dl|デッドリフト|デッド|デットリフト => 데드리프트|deadlift|デッドリフト",
        "루마니안 데드리프트|루마니안|rdl|romanian deadlift|ルーマニアン => 루마니안 데드리프트|romanian deadlift|ルーマニアンデッドリフト",
        "스티프 레그 데드리프트|스티프|stiff leg deadlift|stiff leg|スティッフレッグ => 스티프 레그 데드리프트|stiff leg deadlift|スティッフレッグ",
        "스쿼트|스콰트|스쿼드|스쿠아트|스퀏|squat|スクワット|スクワツト => 스쿼트|squat|スクワット",
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
        "펙덱|펙 덱|pec deck|ペックデック|버터플라이|butterfly|machine fly|머신 플라이|ペックフライ => 펙 덱 플라이|pec deck fly|ペックフライ",
        "시티드로우|시티드 로우|seated row|シーテッドロー|케이블 로우|cable row => 시티드 로우|seated row|シーテッドロー",
        "바벨로우|바벨 로우|barbell row|bent over row|벤트오버로우|벤트 오버 로우|ベントオーバーロー => 벤트 오버 로우|bent over row|ベントオーバーロー",
        "이두컬|이두 컬|바이셉스 컬|바이셉 컬|bicep curl|biceps curl|バイセップスカール => 컬|curl|カール",
        "해머컬|해머 컬|hammer curl|ハンマーカール => 해머 컬|hammer curl|ハンマーカール",
        "푸시다운|푸쉬다운|삼두 푸시다운|tricep pushdown|pushdown|プッシュダウン => 푸시다운|pushdown|プッシュダウン",
        "고블릿|goblet|ゴブレット => 고블릿|goblet|ゴブレット",
        "굿모닝|good morning|グッドモーニング => 굿모닝|good morning|グッドモーニング",
        "레그프레스|레그 프레스|leg press|レッグプレス => 레그 프레스|leg press|レッグプレス",
        "레그컬|레그 컬|햄스트링 컬|hamstring curl|leg curl|レッグカール|ハムストリングカール => 레그 컬|leg curl|レッグカール",
        "레그익스텐션|레그 익스텐션|leg extension|レッグエクステンション => 레그 익스텐션|leg extension|レッグエクステンション",
        "앱휠|ab wheel|ab roller|アブローラー|아브롤러 => 롤아웃|rollout|ロールアウト",
        "점핑잭|jumping jack|ジャンピングジャック => 점핑 잭|jumping jack|ジャンピングジャック",
        "케틀벨 스윙|kettlebell swing|ケトルベルスイング => 케틀벨 스윙|kettlebell swing|ケトルベルスイング",
        "마운틴클라이머|mountain climber|マウンテンクライマー => 마운틴 클라이머|mountain climber|マウンテンクライマー",
        "플라이|fly|flye|フライ => 플라이|fly|フライ",
        "프레스|press|プレス => 프레스|press|プレス",
        "로잉|로잉머신|rowing|rowing machine|ローイング => 로우 머신|rowing machine|ローイングマシン|ローイング",
        "점핑잭|점프잭|jumping jack|jumpingjack|ジャンピングジャック => 점프 잭|jumping jack|ジャンピングジャック",
        "윗몸일으키기|윗몸 일으키기|sit up|situp|シットアップ|腹筋運動 => 싯업|sit up|シットアップ",
        "브이업|v up|vup|Vアップ => v 업|v up|Vアップ",
        "줄넘기|jump rope|jumprope|縄跳び|なわとび => 줄넘기|jump rope|縄跳び",
        "스미스머신|스미스|smith machine|smith|スミスマシン|スミス => 스미스 머신|smith machine|スミスマシン",
        "러닝머신|트레드밀|treadmill|running|러닝|ランニング|ウォーキング|걷기|walking => 걷기|walk|ウォーク",
        "어깨|숄더|shoulder|ショルダー|肩 => 어깨|숄더|shoulder|ショルダー",
        "가슴|체스트|chest|チェスト|胸 => 가슴|체스트|chest|チェスト",
        "삼두|삼두근|트라이셉스|triceps|tricep|トライセプス|上腕三頭筋 => 삼두|트라이셉스|triceps|トライセプス",
        "이두|이두근|바이셉스|biceps|bicep|バイセップス|上腕二頭筋 => 이두|바이셉스|biceps|バイセップス",
        "엉덩이|둔근|글루트|glute|glutes|グルート|お尻|臀筋 => 글루트|힙|glute|グルート|ヒップ",
        "복근|abs|abdominal|アブ|腹筋 => 복근|abdominal|アブドミナル|crunch",
        "종아리|calf|calves|カーフ|ふくらはぎ => 카프|종아리|calf|カーフ",
        "햄스트링|hamstring|ハムストリング => 햄스트링|hamstring|ハムストリング|leg curl"
    )

    val synonyms: List<Synonym> = SYNONYM_RULES.map { rule ->
        val (left, right) = rule.split("=>").map { it.trim() }
        val l = left.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val r = right.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        Synonym(
            triggers = (l + r).map { key(it) }.filter { it.isNotEmpty() }.toSet(),
            expansions = r.map { SearchText.words(it) }.filter { it.isNotEmpty() }
        )
    }

    /** 트리거 키 → 해당 동의어 그룹들 */
    val synonymIndex: Map<String, List<Synonym>> = buildMap<String, MutableList<Synonym>> {
        synonyms.forEach { s -> s.triggers.forEach { getOrPut(it) { mutableListOf() }.add(s) } }
    }

    // ── 부위(근육) 의도 ────────────────────────────────────────────────────────────────────────
    private fun muscle(
        terms: String,
        muscles: Set<MuscleGroup>,
        cats: Set<ExerciseCategory>
    ): List<Pair<String, MuscleTerm>> =
        terms.split("|").map { key(it) }.filter { it.isNotEmpty() }.map { it to MuscleTerm(muscles, cats) }

    val muscleTerms: Map<String, MuscleTerm> = buildList {
        addAll(muscle("하체|다리|下半身|脚|足|레그|legs|leg|lower body|lowerbody|하체운동", setOf(G.LEGS, G.QUADRICEPS, G.HAMSTRINGS, G.GLUTES, G.CALVES), setOf(C.LEGS)))
        addAll(muscle("허벅지|대퇴사두|대퇴사두근|太もも|ふともも|大腿四頭筋|quads|quad|quadriceps|thigh|thighs", setOf(G.QUADRICEPS, G.LEGS), setOf(C.LEGS)))
        addAll(muscle("햄스트링|ハムストリング|裏もも|大腿二頭筋|hamstrings|hamstring|대퇴이두", setOf(G.HAMSTRINGS), setOf(C.LEGS)))
        addAll(muscle("엉덩이|둔근|힙|お尻|おしり|尻|ヒップ|臀筋|大臀筋|殿筋|glutes|glute|butt|booty|hip|hips", setOf(G.GLUTES), emptySet()))
        addAll(muscle("등|등운동|광배|광배근|背中|広背筋|背筋|back|lats|lat|upper back", setOf(G.BACK, G.LATS, G.TRAPS), setOf(C.BACK)))
        addAll(muscle("가슴|대흉근|胸|大胸筋|chest|pecs|pec|pectorals", setOf(G.CHEST), setOf(C.CHEST)))
        addAll(muscle("어깨|삼각근|肩|三角筋|shoulders|shoulder|delts|delt|deltoids", setOf(G.SHOULDERS), setOf(C.SHOULDERS)))
        addAll(muscle("팔|腕|arms|arm|상완", setOf(G.BICEPS, G.TRICEPS, G.FOREARMS), setOf(C.ARMS)))
        addAll(muscle("이두|이두근|上腕二頭筋|力こぶ|biceps|bicep", setOf(G.BICEPS), setOf(C.ARMS)))
        addAll(muscle("삼두|삼두근|上腕三頭筋|二の腕|triceps|tricep", setOf(G.TRICEPS), setOf(C.ARMS)))
        addAll(muscle("전완|전완근|前腕|forearms|forearm", setOf(G.FOREARMS), setOf(C.ARMS)))
        addAll(muscle("복근|코어|배|복부|식스팩|6팩|腹筋|体幹|お腹|腹|abs|ab|core|abdominals|abdominal|stomach|belly|six pack|sixpack", setOf(G.ABS, G.CORE), setOf(C.CORE)))
        addAll(muscle("종아리|ふくらはぎ|カーフ|calves|calve|calf", setOf(G.CALVES), setOf(C.LEGS)))
        addAll(muscle("승모근|僧帽筋|traps|trap|trapezius", setOf(G.TRAPS), setOf(C.SHOULDERS, C.BACK)))
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
        addAll(equip("덤벨|아령|dumbbell|dumbbells|db|ダンベル", Equipment.DUMBBELL))
        addAll(equip("바벨|barbell|bb|バーベル", Equipment.BARBELL))
        addAll(equip("머신|기구|machine|マシン", Equipment.MACHINE))
        addAll(equip("케이블|cable|ケーブル", Equipment.CABLE))
        addAll(equip("맨몸|맨몸운동|bodyweight|body weight|no equipment|noequipment|calisthenics|自重|器具なし|道具なし|徒手|장비없이|기구없이", Equipment.BODYWEIGHT))
        addAll(equip("밴드|저항밴드|저항 밴드|고무밴드|band|bands|resistance band|バンド|チューブ|レジスタンスバンド", Equipment.RESISTANCE_BAND))
        addAll(equip("케틀벨|kettlebell|ケトルベル", Equipment.KETTLEBELL))
    }.toMap()

    /** 오타 허용 매칭 대상 장비 어휘 (자모 기준 6자 이상인 것만 — 짧은 단어는 오탐이 많다) */
    val fuzzyEquipmentTerms: List<Pair<String, Set<Equipment>>> =
        equipmentTerms.entries.map { SearchText.looseKey(it.key) to it.value }.filter { it.first.length >= 6 }

    /** 집에서 할 수 있는 장비 (홈트 의도) — 맨몸 > 밴드 > 덤벨/케틀벨 순으로 선호 */
    val homeEquipmentPreference: Map<Equipment, Double> = mapOf(
        Equipment.BODYWEIGHT to 1.0,
        Equipment.RESISTANCE_BAND to 0.7,
        Equipment.DUMBBELL to 0.5,
        Equipment.KETTLEBELL to 0.4,
        Equipment.OTHER to 0.2
    )

    val homeTerms: Set<String> = listOf(
        "집|집에서|홈트|홈트레이닝|홈|자취방|자취|home|at home|athome|home workout|自宅|宅トレ|おうち|ホーム|家|家で|宅"
    ).flatMap { it.split("|") }.map { key(it) }.filter { it.isNotEmpty() }.toSet()

    /** 의도 해석 시 무시하는 군더더기 단어 */
    val fillerTerms: Set<String> = listOf(
        "운동|운동법|하는법|방법|할수있는|가능한|트레|트레이닝|루틴|부위|추천|workout|exercise|exercises|training|day|routine|トレ|トレーニング|運動|筋トレ|エクササイズ|メニュー|no|for|my|best|good|and|with|only|하는|하기|の|を|で|に"
    ).flatMap { it.split("|") }.map { key(it) }.filter { it.isNotEmpty() }.toSet()
}

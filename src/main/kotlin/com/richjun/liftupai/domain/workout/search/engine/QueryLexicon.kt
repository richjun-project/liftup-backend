package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.search.ExerciseSearchLexicon
import com.richjun.liftupai.domain.workout.search.KanjiReadings
import com.richjun.liftupai.domain.workout.search.SearchText

/**
 * 질의 이해 사전 (ko/ja/en) — 코드가 아니라 표(데이터)다. 새 표현은 표에 한 줄 추가하면 된다.
 *
 * 구성
 * - 조사·어미·군더더기(NOISE), 수치 단위(UNIT), 접속(CONJ)
 * - 부정 표지(NEG: 없이/以外/without…), 부상 단서(CUE: 안 좋을 때/優しい/bad…), 관절(JOINT)
 * - 수준(LEVEL), 스트레칭(STRETCH), 소음/충격(QUIET)
 * - 의도 개념(Concept): 목표·자세·세부 부위. 이름에 나타나는 "신호 표기"(sig)로 운동을 가려낸다.
 * - 부정 대상 묶음(MoveGroup): "점프", "데드리프트" 처럼 이름 표기로 제외할 동작 묶음
 * - 부상 프로필(InjuryProfile): 관절별로 피할/권장하는 동작
 *
 * 모든 표기는 SearchText 로 정규화(NFKC·소문자·가타카나→히라가나·복수형 s 제거)되어 비교된다.
 * 신호 표기 끝의 `~` 는 "단어 끝에서 끝나야 함"(예: row~ 는 rowing 과 구분).
 */
internal object QueryLexicon {

    enum class Joint { KNEE, LOWER_BACK, SHOULDER, WRIST, ELBOW, NECK, HIP, ANKLE }

    enum class Role { CONCEPT, EQUIP, HOME, JOINT, CUE, NEG, LEVEL, NOISE, UNIT, CONJ, STRETCH, QUIET, MODIFIER, NAME }

    /** 부위 앞뒤에 붙어 세부 부위를 가리키는 말(상부·하부·후면·안쪽 …) */
    enum class Mod { UPPER, LOWER, MID, REAR, FRONT, INNER, OUTER, SIDE }

    /** 의도 개념. sig 가 이름에 있으면 강한 가산, sig2 는 약한 가산. strict 이면 신호 일치가 있어야 후보가 된다. */
    class Concept(
        val id: String,
        val muscles: Set<MuscleGroup>,
        val categories: Set<ExerciseCategory>,
        val sig: List<SigKey>,
        val sig2: List<SigKey>,
        val patterns: Set<String>,
        val avoid: List<SigKey>,
        val strict: Boolean,
        val stretch: Boolean,
        val cardio: Boolean,
        /** 이 개념의 트리거 표기(키) — 스트레칭 질의("어깨 스트레칭")에서 이름 속 부위 표기를 신호로 쓴다 */
        val nameWords: List<SigKey> = emptyList()
    )

    /** 이름 신호 키: strict 표기 + 단어 끝 고정 여부 */
    class SigKey(val key: String, val wordEnd: Boolean) {
        val latin: Boolean = key.isNotEmpty() && key[0].code < 0x250
    }

    class Term(
        val role: Role,
        val concept: Concept? = null,
        val equipment: Set<Equipment> = emptySet(),
        val joint: Joint? = null,
        val level: Int = 0,
        /** NEG: 대상이 표지 앞에 오는가(ja/ko) / 뒤에 오는가(en: false) */
        val targetBefore: Boolean = true,
        /** 조사·부정 어미 — 앞에 내용어가 있어야 하고 가타카나 글자 위에는 놓이지 않는다 */
        val particle: Boolean = false,
        /** 1글자 CJK 내용어 — 단어 경계에서만 인정 */
        val isolated: Boolean = false,
        val move: MoveGroup? = null,
        val mod: Mod? = null,
        /** NEG: "대신/instead of/代わりに" — 제외하되 그 운동과 비슷한 운동을 대신 보여 준다 */
        val instead: Boolean = false,
        /** LEVEL: "X 못 해요/できない/can't do X" — X 의 쉬운 변형(어시스트·무릎·인클라인)을 앞세운다 */
        val regress: Boolean = false,
        /** JOINT: 부위 단어(back/背中/등)를 겸하는 약한 관절 — 다른 관절이 가까이 있으면 그쪽이 부상 관절이다 */
        val weakJoint: Boolean = false
    )

    private fun Term.asIsolated() = Term(role, concept, equipment, joint, level, targetBefore, particle, true, move, mod, instead, regress, weakJoint)

    class MoveGroup(val id: String, val keys: List<SigKey>)

    class InjuryProfile(
        val joint: Joint,
        val hard: List<SigKey>,
        val soft: List<SigKey>,
        val prefer: List<SigKey>
    )

    // ── 키 변환 ───────────────────────────────────────────────────────────────────────────────
    internal fun key(raw: String) = SearchText.strictKey(raw)

    internal fun sigKey(raw: String): SigKey? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val end = trimmed.endsWith("~")
        val k = key(trimmed.removeSuffix("~"))
        return if (k.isEmpty()) null else SigKey(k, end)
    }

    private fun sigList(raw: String?): List<SigKey> =
        raw.orEmpty().split("|").mapNotNull { sigKey(it) }.distinctBy { it.key + it.wordEnd }

    private fun keys(raw: String): List<String> = raw.split("|").map { key(it) }.filter { it.isNotEmpty() }

    // ── 군더더기·조사·어미 ────────────────────────────────────────────────────────────────────
    /** 순수 조사 — 앞 내용어에 붙어야 하고, 일본어는 히라가나일 때만 인정한다(가타카나 속 글자와 혼동 방지). */
    private val PURE_PARTICLES = """
        を|で|に|が|は|も|の|と|へ|や|から|まで|より|だけ|のみ|しか|など|って|とか|には|では|でも|なら|ね|よ|な|か
        |요|이|가|은|는|을|를|에|에서|로|으로|의|도|만|과|와|하고|랑|이랑|에게|한테|부터|까지|보다|처럼|이나|나|에는|에서는|으로는|로는|밖에|만으로|으로만
    """.trimIndent()

    /** 군더더기·어미·요청 표현 (문장 어디에나 올 수 있다) */
    private val NOISE = """
        という|ための|ため|ような|みたいな|みたい|です|ます|でしょうか|ですか|ますか
        |を鍛える|鍛える|鍛えたい|鍛えられる|鍛え方|鍛えて|鍛えるには|鍛えるメニュー|鍛えるための|鍛えたいとき|鍛えたい時|鍛えたいです|鍛えたいんですが|鍛えるなら|鍛えます|鍛えよう
        |痩せたい|やせたい|痩せる|やせる|痩せ|絞りたい|絞る|絞って|引き締めたい|引き締める|引き締め|引き締まる|引き締まった|引き締まり|締めたい|締める
        |作りたい|作る|作り方|作れる|つくる|つくりたい|つけたい|つける|付けたい|付ける|つけ方|増やしたい|増やす|増やし方|大きくしたい|大きくする|大きく|太くしたい|太くする|太く|細くしたい|細くする|細く|小さく|薄く|厚く|分厚く|丸く|高く|強く|強くしたい|強くなりたい
        |割りたい|割る|割れる|割って|広げたい|広げる|広く|広げ方|治したい|治す|治る|治し方|直したい|直す|直し方|改善|改善したい|改善する|改善方法|解消|解消したい|解消する|解消法|矯正|矯正したい|予防|強化|強化したい|強化する|ほぐす|ほぐしたい
        |やりたい|やる|やり方|やって|やった|やり|する|したい|した|して|してる|しています|しながら|でき|できる|できて|できない|出来る|やれる|したいとき|したい時|したいです|するには|するとき|するなら|したら|したり|なりたい|なる|なれる|なって|なった|なります
        |効く|効かせたい|効果的|効果的な|効率的|効率的に|おすすめ|お勧め|お薦め|人気|定番|簡単|簡単な|楽な|楽に|手軽|手軽な|基本|基本の|基本的な|正しい|正しく|メニュー|方法|のやり方|教えて|教えてください|ください|知りたい|探して|探してる|ありますか|あります|ある|ありませんか|いい|良い|よい|ほしい|欲しい|もの|こと|とき|時|ついて|について|向け|向けの|専用
        |できますか|いいですか|良いですか|いいでしょうか|おすすめは|はありますか|ってありますか|知りたいです|教えてほしい|教えてください|お願いします|お願い|そして|あと|また|さらに|もっと|とても|すごく|かなり|少し|ちょっと|なるべく|できるだけ|毎日|毎朝|寝る前|短時間|時短|ながら
        |効果|効用|効能|コツ|ポイント|注意点|メリット|デメリット|違い|何|なに|何ですか|とは|ってなに|って何|何か|何が|何を|どれ|どっち|どう|どうやって|どんな|効果的
        |효과|효능|장점|단점|차이|팁|포인트|주의점|뭐야|뭔가요|뭐예요|뭐에요|뭐지|뭘까|뭐임|이란|란|이에요|예요|이야|인가|인지|맞나요|맞아요|궁금|궁금해요|궁금합니다
        |benefits|benefit|effects|effect|pros|cons|difference|differences|vs|versus|compared|comparison|explained|explain|meaning|means|mean|define|definition|demo|demonstration|video|image|images|picture|pictures|photo|photos
        |使った|使う|使って|使用|使用した|使用して|使います|使い|使いたい|使えば|使えます|활용|활용한|이용|이용한|사용|사용한|사용하는|쓰는|쓴|쓰고싶은
        |하고싶어|하고싶다|하고싶은|하고싶네|하고싶은데|하고싶어요|하고싶습니다|하고싶음|싶어|싶다|싶은|싶어요|싶은데|싶습니다|하는|하기|하면|할때|할|하자|해서|해줘|해주세요|해요|합니다|한다|하는법|하는방법|하는거|하는건|하는데|하려면|하려고|하면서|할수있는|할수있도록|가능한
        |있나요|있을까요|있나|있을까|있습니까|가능해요|가능한가요|가능한지|되나요|될까요|해도되나요|해도될까요|돼요|괜찮나요|괜찮을까요|해야돼요|해야하나요|해야할까요|할까요|하나요|합니까|인가요|일까요|뭐가좋을까요|뭐가좋아요|무슨|알려줘|알려주세요|알려줄래|추천|추천해줘|추천해주세요|추천해줄래|추천좀|추천해|추천하는|좀|제발|그냥|진짜|정말|매우|아주|너무|조금|많이|빠르게|쉽게|효과적인|효과적으로|효율적인|효율적으로|좋은|좋아요|좋다|괜찮은|제일|가장|최고|인기|기본|기본적인|간단한|간단하게|방법|법|있는데|있어|있어요|있는|있다|있고|있을때|있으면|없는데|뭐|뭐가|뭘|무슨|어떤|어떻게|어디|자세|폼
        |빼기|빼는|빼고싶어|빼고싶다|빼고싶은|빼려면|빼는법|빼는방법|뺄래|뺀|만들기|만드는|만들고싶어|만들고싶다|만들고싶은|만들려면|만들어|만드는법|키우기|키우는|키우고싶어|키우고싶다|키우고싶은|키우려면|키워|늘리기|늘리는|늘리고싶어|늘리고싶은|찌우기|찌우는|줄이기|줄이는|줄이고싶어|풀기|풀어주는|풀어줘|풀고싶어|교정|교정하는|교정하기|교정하고싶어|개선|개선하는|개선하기|해소|해소하는|해소하기|강화|강화하는|강화하기|단련|단련하는|단련하기|다지기|다지는|잡기|잡는|잡아주는|살리기|올리기|올리는|넓히기|넓히는|넓게|굵게|굵은|두껍게|두꺼운|얇게|가늘게|탄탄하게|탄탄한|예쁘게|예쁜|슬림하게|슬림한|날씬하게|날씬한|단단하게|단단한|크게|작게|길게|짧게|높게|강하게|튼튼하게|튼튼한
        |워크아웃|트레이닝|트레|루틴|프로그램|운동법|운동|부위|헬스|헬스장|헬스장에서|체육관|짐|웨이트|근력운동|근력|근육
        |ジムで|ジムの|ジム|トレーニング|トレ|筋トレ|筋力トレーニング|ワークアウト|エクササイズ|体操|運動|種目|ルーティン|プログラム|コース|部位|パーツ|筋肉|筋|ジムトレ|ウエイトトレーニング|ウェイトトレーニング|オススメ|フォーム
        |朝|夜|通勤|仕事|休憩|昼休み|最強|最速|最短|ランキング|ベスト|トップ|やるべき|必須|やってみた|オススメ|아침|저녁|밤|새벽|오전|오후|출근 전|퇴근 후|점심시간|최강|효과 좋은|필수|베스트|랭킹|순위|꿀팁|비법|꿀|morning|evening|night|before bed|after work|lunch break|ultimate|must do|ranking|favorite|famous|popular|secret|hack|guide|tutorial|cheat sheet|how-to|howto|top 10|top10
        |라인|line|ライン|몸매|바디|ボディ|スタイル|ごつい|がっしり|太い|でかい|ムキムキ|マッチョ|逞しい|우람한|근육질|bulky|buff|jacked|ripped|swole|defined|definition|shredded|sculpt|sculpted|美しい|綺麗な|きれいな|예쁜|이쁜|beautiful|nice|人|方|人向け|人用|人も|ひと|場合|lift|lifting|lifts|muscle|muscles|muscular|hypertrophy|bulk|bulking|mass|gains|gain|growth|size|strength|variations|variation|options|option|similar|types|アレンジ|バリエーション|代用|種類|응용|변형|종류|비슷한|workout|workouts|exercise|exercises|training|train|routine|routines|program|programs|plan|plans|day|days|session|sessions|movement|movements|move|moves|drill|drills|gym|gyms|set|sets|rep|reps
        |recommend|recommended|recommendation|recommendations|suggest|suggestion|suggestions|show|give|tell|list|find|looking|look|trying|try|for|at|in|on|to|the|a|an|of|with|and|or|my|your|our|me|i|im|ive|want|wanna|need|do|does|how|can|could|should|would|some|any|get|getting|got|make|making|build|building|improve|improving|increase|increasing|grow|growing|gain|gaining|tone|toning|toned|strengthen|strengthening|stronger|strong|bigger|big|large|huge|massive|thick|thicker|wide|wider|broad|broader|slim|slimmer|lean|leaner|tight|tighter|firm|firmer|round|rounder|small|smaller|best|good|great|top|effective|efficient|quick|fast|short|simple|basic|basics|tips|tip|ideas|idea|help|helps|please|just|really|very|kind|types|type|ways|way|what|which|where|when|that|this|these|those|it|its|is|are|be|been|am|was|were|by|from|out|about|into|onto|per|using|use|used|them|you|we|they|he|she|will|also|more|most|less|least|much|many|lot|lots|only|work|working|worked|hit|hitting|target|targeting|targets|focus|focused|focusing|specific|specifically|proper|properly|correct|correctly|right|form|technique
        |but|instead|however|though|yet|free|벌크업|벌크|린매스업|린매스|근비대|매스업|증량|バルクアップ|筋肥大|増量|マッスルゲイン|분할 루틴|분할법|분할 운동|2분할|3분할|4분할|5분할|분할|split routine|workout split|bro split|ppl|3分割|2分割|4分割|5分割|分割法|free weight|free weights|프리웨이트|프리 웨이트|フリーウェイト|フリー ウェイト|테스트|テスト|test|자기 전|자기전|자기 전에|자기전에|잠자기 전|잠자기전|잠들기 전|잠들기전|자기 직전|취침 전|취침전|睡眠前|就寝前|寝る前に|寝起き|朝起きて|朝起きたとき|아침에 일어나서|기상 후|before sleep|before bedtime|before going to bed|bedtime|after waking up|first thing in the morning|at night|at bedtime|漕ぎ|こぎ|漕ぐ|こぐ|타기|타는|타고|riding|ride|rides|before|prior|pre
    """.trimIndent()

    /** 수치 단위 — 숫자 바로 뒤에서만 인정 */
    private val UNITS = """
        kg|kgs|キロ|キログラム|킬로|킬로그램|lb|lbs|pound|pounds|ポンド|파운드|rm|%|퍼센트|パーセント|kcal|キロカロリー|칼로리|cal|cm|mm|m|km|분|分|分間|min|mins|minute|minutes|ミニッツ|분간|초|秒|sec|secs|second|seconds|時間|시간|hour|hours|hr|hrs|일|日|days|週|주|week|weeks|주일|ヶ月|か月|ヵ月|개월|달|month|months
        |回|回数|回目|번|회|개|個|セット|세트|set|sets|レップ|rep|reps|times|time|x|×|度|도|degree|degrees|deg|°|세|歳|才|kg짜리|단계|단|段|ステップ|レベル|level|lv|인분|발|발씩|초씩|분씩|회씩|개씩|세트씩|대|자|번째|day|d
    """.trimIndent()

    private val CONJUNCTIONS = "も|도|や|と|・|、|,|及び|および|ならびに|と|랑|이랑|하고|와|과|및|그리고|이나|나|and|or|plus|&|+|그리고|또는|혹은|あるいは|または|もしくは|に加えて|それと|それから"

    // ── 부정 표지 ────────────────────────────────────────────────────────────────────────────
    private val NEG_AFTER = """
        以外|以外の|なし|無し|なしで|なしの|なしでも|無しで|無しの|抜き|抜きで|抜きの|しない|しなくて|しなくてもいい|せず|せずに|ずに|使わない|使わず|使わなくて|つかわない|不使用|いらない|不要|除く|除いた|除いて|除外|除外して|避けたい|避ける|避けて|避けたいです|なくて|ないで|なく
        |じゃなくて|じゃなく|じゃない|ではなくて|ではなく|ではない|でなく|ではなしに|아닌|아니고|아니라|이 아니라|가 아니라|말고|없이|없는|없고|없어도|없으면|없이도|말고|빼고|제외|제외한|제외하고|제외하는|안쓰는|안쓰고|안쓴|안하는|안하고|안한|않는|않고|않으면서|피해서|피하는|피하고|피하고싶어|피하고싶은|불필요|필요없는|필요없이|쓰지않는|쓰지않고|하지않는|하지않고|하지말고
        |free|less|-free|-less
    """.trimIndent()

    /** "X 대신 / Xの代わりに / Xの代替" — 대상 뒤에 오는 대체 표지 */
    private val INSTEAD_AFTER = """
        alternative|alternatives|substitute|substitutes|substitution|replacement|replacements|대신|대신에|대신할|대신 할|대체|대체할|대체 운동|대안|대안으로|대체로|말고 다른|代わりに|代わり|の代わり|の代替|代替|代替案|代用|代わりの|代わるもの
    """.trimIndent()

    private val INSTEAD_BEFORE = "instead of|insteadof|alternative to|alternatives to|alternative for|alternatives for|substitute for|substitutes for|replacement for|replace|rather than|in place of|swap for|swap out|other than|besides|something other than"

    private val NEG_BEFORE = "without|w/o|no|not|avoid|avoiding|except|excluding|exclude|excluded|skip|skipping|minus|nothing|don't|dont|do not|everything but|everything except|all but|anything but|anything except|apart from|aside from|but not|not including|except for|save for|sans|free of|bar the"

    // ── 부상 단서 ────────────────────────────────────────────────────────────────────────────
    private val CUES = """
        痛い|痛み|痛めた|痛めている|痛めてる|痛くない|痛くならない|痛めない|痛くなる|優しい|やさしい|負担がかからない|負担がかからず|負担がない|負担の少ない|負担少ない|負担を減らす|負担をかけない|負担をかけずに|負担軽減|負担が少ない|負担が小さい|負担にならない|怪我|ケガ|けが|故障|違和感|悪い|不安|庇う|保護|リハビリ|痛む|傷める|傷めた|炎症|痛くて|痛くても|大丈夫|安全|安全な|安心|弱い|弱め|軽め|無理なく|無理のない|ケガしにくい|怪我しにくい|痛めにくい|傷めにくい|怪我予防|ケガ予防|痛くなりにくい
        |안좋은데|안좋은데도|아픈데|아프지만|안좋을때|안좋을 때|안좋은|안좋아|안좋다|안좋을|안 좋을 때|아픈|아플|아플때|아프|아파|아프지않은|아프지않게|아프지않는|안아픈|안아프게|통증|부담|부담없는|부담적은|부담이적은|부담을줄이는|부담안되는|부담이안되는|약한|약할|약해|부상|재활|보호|다쳤|다친|다쳐서|조심|조심해서|무리없는|무리없이|염증|불편|불편한|불안|안전한|안전하게|안전|무리가안가는|무리안가는|무리가가지않는|무리가 안 가는|무리 안 가는|괜찮은|괜찮게|편한|편하게|디스크|시큰|시큰거리는|뻐근|뻐근한
        |bad|sore|weak|injured|injury|injuries|pain|painful|painless|hurt|hurts|hurting|friendly|safe|safely|gentle|gently|rehab|rehabilitation|protect|protecting|careful|carefully|issues|issue|problems|problem|trouble|troubles|ache|aches|aching|stiff|tender|inflamed|pulled|strained|strain|sprain|sprained|surgery|recovering|recovery|post-op|easy-on|easy on|easyon|kind to|gentle on|low-stress|safe for|good for|careful with|prehab|prevent injury|injury prevention|injury-proof|bulletproof|wont hurt|doesnt hurt|aggravate|aggravating|irritate|irritating|flare up|flareup
    """.trimIndent()

    /** 충격·소음을 피하는 표현 — 점프류를 제외한다 */
    private val QUIET = """
        층간소음|층간 소음|소음|조용한|조용히|조용|소리 안 나는|소리안나는|아파트|아파트에서|저충격|충격 없는|충격 적은|충격없는|충격이적은|무충격|低衝撃|ローインパクト|衝撃が少ない|衝撃なし|衝撃の少ない|騒音|静か|静かな|音が出ない|音を立てない|マンション|アパート|
        |関節に優しい|関節に負担|関節への負担|関節にやさしい|관절에 무리|관절 보호|관절에 부담|관절 부담|관절 안 좋|관절에 안 좋|joint friendly|joint-friendly|joint pain|easy on joints|joint safe|시니어|고령자|高齢者|シニア|elderly|seniors|older adults|low impact|lowimpact|low-impact|no impact|zero impact|quiet|silent|apartment|noise|noiseless|neighbors|neighbours|downstairs
    """.trimIndent()

    /** 관절 앞에 붙는 형용사형 단서(bad knees) — 이 단서는 뒤 관절에 걸린다 */
    /** 부정 표지 원문(소문자) — 같은 부정어가 질의에 되풀이돼도("no squat no lunge") 중복으로 지우면 안 된다 */
    val negationWords: Set<String> by lazy {
        (splitRules(NEG_AFTER) + NEG_BEFORE.split("|") + splitRules(INSTEAD_AFTER) + splitRules(INSTEAD_BEFORE))
            .map { it.removePrefix("-").lowercase() }.filter { it.isNotEmpty() && !it.contains(' ') }.toSet()
    }

    /** 부위 표기를 품고 있지만 다른 부위를 가리키는 복합어 — 이름 속 부위 표기 일치에서 제외한다(胸椎 = 흉추, 가슴이 아니다) */
    val nameMasks: List<String> = listOf("胸椎", "胸骨").map { key(it) }

    /** 영어에서 부정 뒤에 오면 새 절이 시작되는 말 — "without barbell with dumbbells" 의 dumbbells 는 긍정이다 */
    val chainBreakers: Set<String> = "with|using|use|but|instead|however|though|while|yet|only|just|and then|then".split("|").map { key(it) }.toSet()

    val adjectiveCues: Set<String> = ("bad|sore|weak|injured|painful|tender|stiff|damaged|hurt|hurting|strained|sprained|pulled|inflamed|" +
        "easy on|gentle on|kind to|safe for|good for|protect|protecting|careful with|careful|doesnt hurt|wont hurt|hurt my|hurts my|aggravate|aggravating|irritate|irritating").split("|").map { key(it) }.toSet()

    // ── 수준 ─────────────────────────────────────────────────────────────────────────────────
    private val BEGINNER = "beginner friendly|beginners friendly|newbie friendly|novice friendly|初心者にやさしい|初心者に優しい|초보자 친화|초보도 쉬운|easy|쉬운|楽な|初心者|初心者向け|入門|入門者|ビギナー|初級|初級者|ビギナー向け|はじめて|初めて|초보|초보자|초보자용|초보용|초심자|입문|입문자|왕초보|처음|처음하는|beginner|beginners|beginner-friendly|novice|newbie|starter|starters|entry level|entrylevel|first time|first-time|noob|rookie|beginning|헬린이|운린이|헬초보|헬스초보|운동초보|운동 초보|헬스 입문|운동 입문|헬스 초보|ジム初心者|ジムデビュー|ジム入門|運動初心者|ノービス|gym newbie|gym noob|first timer|first-timer|just starting|just started|starting out|complete beginner|absolute beginner|never worked out|new to the gym|new to working out|new to lifting"
    /** "못 해요" 류 — 그 운동을 못 하는 사람에게는 쉬운 변형이 먼저다 */
    private val CANT_DO = """
        ができない|できない|出来ない|できなくて|できません|できなかった|やれない|無理|きつい|辛い|つらい|苦手
        |못해|못해요|못하는|못 하는|못할때|못할 때|못 해요|못하겠|못 하겠어|안돼요|안되는|힘들어|힘든|힘들다|어려워|어려운|어렵다|못 올라|못올라
        |cant do|can't do|cannot do|cant|unable to do|unable to|struggle with|struggling with|struggle|hard to do|too hard|too difficult|difficult|can not do
    """.trimIndent()

    /** 쉬운 변형을 가리키는 이름 표기 */
    val regressionKeys: List<SigKey> = listOf(
        "assisted|knee|kneeling|incline push|wall push|negative|modified|supported|band assisted|partial|eccentric|アシスト|ニー|膝つき|ネガティブ|インクライン|어시스트|니 푸시업|무릎 푸시업|네거티브|인클라인"
    ).flatMap { it.split("|") }.mapNotNull { sigKey(it) }

    private val ADVANCED = "上級者|上級|上級者向け|中上級|ハード|エキスパート|고급|고급자|상급|상급자|고수|상급자용|하드|힘든|advanced|expert|hardcore|intense|challenging|elite|pro|harder|hardest"

    // ── 세부 부위 수식어 (부위 + 수식어 → 세부 개념) ─────────────────────────────────────────
    private val MODIFIER_RULES = """
        UPPER: 上部|上側|上方|上の|上|상부|위쪽|upper|top
        LOWER: 下部|下側|下方|下の|下|하부|아래쪽|아랫부분|lower|bottom
        MID: 中部|中央|中間|중부|가운데|중간|middle|mid|center|centre|central
        REAR: 後面|後ろ|後部|後ろ側|裏側|뒤쪽|후면|후방|뒷부분|rear|posterior|backside
        FRONT: 前面|前側|前部|전면|앞쪽|전방|앞부분|front|anterior
        INNER: 内側|内|안쪽|내측|inner|inside|medial
        OUTER: 外側|外|바깥|바깥쪽|외측|outer|outside
        SIDE: 横|横側|옆쪽|측면|side
    """.trimIndent()

    /** (부위 계열, 수식어) → 세부 개념 id 또는 트리거 표기 */
    private val REFINE: Map<String, String> = mapOf(
        "abs:LOWER" to "lowerabs", "abs:UPPER" to "upperabs", "abs:SIDE" to "oblique", "abs:OUTER" to "oblique",
        "chest:UPPER" to "upperchest", "chest:LOWER" to "lowerchest", "chest:MID" to "innerchest", "chest:INNER" to "innerchest",
        "shoulder:REAR" to "reardelt", "shoulder:FRONT" to "frontdelt", "shoulder:SIDE" to "sidedelt", "shoulder:MID" to "sidedelt", "shoulder:OUTER" to "sidedelt",
        "back:UPPER" to "midback", "back:MID" to "midback", "back:LOWER" to "lowerback", "back:SIDE" to "backwide", "back:OUTER" to "backwide",
        "thigh:INNER" to "innerthigh", "thigh:OUTER" to "outerthigh", "thigh:SIDE" to "outerthigh", "thigh:REAR" to "@햄스트링", "thigh:FRONT" to "@대퇴사두",
        "glute:OUTER" to "outerthigh", "glute:SIDE" to "outerthigh", "glute:UPPER" to "glutegoal", "glute:LOWER" to "glutegoal",
        "arm:REAR" to "@삼두", "arm:OUTER" to "@삼두", "arm:FRONT" to "@이두", "arm:INNER" to "@이두"
    )

    /** 관절 → 그 관절을 푸는 스트레칭 부위 개념(없으면 null) */
    fun jointStretchTarget(j: Joint): Concept? {
        val trigger = when (j) {
            Joint.LOWER_BACK -> "허리"
            Joint.NECK -> "목"
            Joint.SHOULDER -> "어깨"
            Joint.HIP -> "고관절"
            Joint.KNEE -> "허벅지"
            Joint.ANKLE -> "종아리"
            Joint.WRIST, Joint.ELBOW -> "팔"
        }
        return termIndex[key(trigger)]?.firstNotNullOfOrNull { it.concept }
    }

    /** 부위 개념의 계열 (수식어를 붙일 수 있는 일반 부위만) */
    fun familyOf(c: Concept): String? {
        if (!c.id.startsWith("muscle:")) return null
        val m = c.muscles; val k = c.categories
        return when {
            MuscleGroup.ABS in m || ExerciseCategory.CORE in k -> "abs"
            ExerciseCategory.CHEST in k || MuscleGroup.CHEST in m -> "chest"
            MuscleGroup.SHOULDERS in m && ExerciseCategory.SHOULDERS in k -> "shoulder"
            ExerciseCategory.BACK in k -> "back"
            m == setOf(MuscleGroup.GLUTES) -> "glute"
            m == setOf(MuscleGroup.HAMSTRINGS) -> "thigh"
            ExerciseCategory.LEGS in k -> "thigh"
            ExerciseCategory.ARMS in k && (MuscleGroup.BICEPS in m || MuscleGroup.TRICEPS in m) && m.size >= 2 -> "arm"
            else -> null
        }
    }

    /** 부위 계열 + 수식어 → 세부 개념 (없으면 null) */
    fun refine(c: Concept, mod: Mod): Concept? {
        val target = REFINE[(familyOf(c) ?: return null) + ":" + mod.name] ?: return null
        return if (target.startsWith("@")) termIndex[key(target.substring(1))]?.firstNotNullOfOrNull { it.concept } else conceptById[target]
    }

    // ── 스트레칭 / 홈 ────────────────────────────────────────────────────────────────────────
    private val STRETCH = "伸ばす|伸ばし|伸ばしたい|伸ばして|のばす|のばし|ほぐす|ほぐしたい|ほぐして|ほぐし|柔らかく|柔らかくしたい|やわらかく|ゆるめる|緩める|緩和|リラックス|푸는|푸는법|푸는 법|풀어주는|풀어줘|풀고싶어|늘리기|늘려|늘리는|이완|이완하기|유연하게|유연해지는|부드럽게|근막이완|loosen|loosening|lengthen|elongate|flexible|limber|ストレッチ|柔軟|柔軟体操|ほぐし|ほぐす|스트레칭|스트레치|유연성|이완|풀기|stretch|stretches|stretching|flexibility|mobility|モビリティ|가동성|loosen|loosening|release|relax|リリース|이완운동"

    // ── 개념 정의 ────────────────────────────────────────────────────────────────────────────
    // 형식: 트리거|트리거 => id; muscles=A,B; cats=X; sig=..|..; sig2=..; pat=..; avoid=..; flags=strict,stretch,cardio
    private val CONCEPT_RULES = """
        허리살|허릿살|허리 군살|러브핸들|러브 핸들|腰回りの肉|腰回りのお肉|腰肉|浮き輪肉|ウエストの肉|ウエストシェイプ|waist trimming|trim waist|slim down waist|side belly|side belly fat|small waist|tiny waist|narrow waist|hourglass|잘록한 허리|잘록한허리|잘록 허리|허리 잘록|くびれ|ウエスト|ウェスト|腹斜筋|脇腹|わき腹|옆구리|옆구리살|옆구리 라인|복사근|허리라인|허리 라인|오블리크|love handles|oblique|waist|waistline|side abs|muffin top|waist slimming|slim waist|side fat|옆구리 군살|脇腹の肉 => oblique; muscles=ABS,CORE; cats=CORE; sig=oblique|side bend|twist|wood chop|woodchop|windmill|bicycle|side plank|russian|オブリーク|斜め|サイドベント|ツイスト|ウッドチョップ|ウィンドミル|バイシクル|サイドプランク|ロシアン|옆구리|오블리크|사이드 벤드|트위스트|우드 찹|윈드밀|바이시클|사이드 플랭크|러시안; pat=ROTATION; flags=strict
        下腹部|下腹|下っ腹|ぽっこり下腹|하복부|하복|아랫배|아랫배살|아랫복근|아래 복근|하복근|하부 복근|아랫쪽 복근|아래쪽 복근|下腹筋|下部腹筋|腹筋の下部|腹筋下部|腹筋の下側|下の腹筋|lower abdominals|lower abdominal|lower abdomen|lower core|bottom abs|lower ab muscles|lower abs|lower ab|lower belly|lower stomach|belly pooch|pooch|infra abs => lowerabs; muscles=ABS,CORE; cats=CORE; sig=leg raise|knee raise|reverse crunch|flutter|scissor|v up|bicycle|toe touch|hanging|レッグレイズ|ニーレイズ|リバースクランチ|フラッター|シザース|バイシクル|ハンギング|Vアップ|ブイアップ|레그 레이즈|니 레이즈|리버스 크런치|플러터|시저스|바이시클|행잉|브이업; pat=LEG_RAISE; flags=strict
        背中の厚み|背中の厚さ|背中を厚く|背中を分厚く|背中 厚み|등 두께|등 두껍게|두꺼운 등|등 두껍|등 볼륨|背中のボリューム|thick back|back thickness|back thick|back mass|thick lats|thicker back => backthick; muscles=BACK,LATS,TRAPS; cats=BACK; sig=row~|ロー~|ロウ~|로우~|ベントオーバー|벤트 오버|ベントオーバーロー; sig2=pulldown|pull up|rack pull|shrug|プルダウン|プルアップ|풀다운|풀업
        大円筋|대원근|teres major|大円筋|広い背中|背中の広がり|背中を広く|背中を広げ|逆三角形|逆三角|넓은 등|등 넓히기|광배 넓히기|광배근 넓히기|v 라인|v taper|vtaper|v shape|vshape|wide back|broad back|lat width|wide lats|bigger lats|wider back|등 날개|등 넓게|광배 키우기 => backwide; muscles=LATS,BACK; cats=BACK; sig=pulldown|pull down|pull up|pullup|chin up|lat pull|straight arm|プルダウン|プルアップ|懸垂|チンアップ|チンニング|ラットプル|풀다운|풀업|친업|턱걸이|랫풀|랫 풀; pat=LAT_PULLDOWN,PULLUP_CHINUP
        脊柱起立筋|척추기립근|척추 기립근|erector spinae|erectors|erector|腰|腰回り|腰まわり|허리|허리 근육|허리 운동|허리근육|下背部|하부 등|lower back|lowerback|low back|腰の筋肉|back extensor|spinal erectors|spine => lowerback; muscles=BACK,CORE; sig=back extension|hyperextension|superman|bird dog|reverse hyper|バックエクステンション|バックエクステ|ハイパーエクステンション|スーパーマン|バードドッグ|백 익스텐션|백익스텐션|하이퍼 익스텐션|슈퍼맨|버드 독; sig2=good morning|rack pull|deadlift|グッドモーニング|ラックプル|デッドリフト|굿모닝|랙 풀|데드리프트; flags=strict
        猫背|巻き肩|巻肩|姿勢|姿勢改善|姿勢矯正|姿勢を良く|いい姿勢|良い姿勢|ストレートネック|スマホ首|スマホ猫背|デスクワーク|라운드숄더|라운드 숄더|거북목|일자목|굽은 등|굽은등|말린 어깨|말린어깨|둥근 어깨|자세 교정|바른 자세|바른자세|posture|bad posture|poor posture|good posture|rounded shoulders|rounded shoulder|round shoulders|hunchback|hunched back|hunched|forward head|slouch|slouching|kyphosis|tech neck|text neck|desk job|desk worker|sitting all day|sit all day|sitting too long|sedentary|long hours sitting|prolonged sitting|office worker|座りっぱなし|座りすぎ|長時間座|長時間のデスクワーク|오래 앉아|장시간 앉|하루 종일 앉|앉아서 일하는|사무직 스트레칭|스마트폰 목|사무직|굽은 어깨|굽은어깨|새우등|등 굽음|등이 굽은|어깨 말림|어깨가 말린|둥근 등|둥근등|골격 교정|몸 틀어짐|척추 측만|척추측만|자세가 나쁜|자세 안 좋은|姿勢が悪い|姿勢を直す|猫背矯正|巻き肩矯正|骨格矯正|体の歪み|身体の歪み|歪み矯正|背中が丸い|背中の丸み|丸まった背中|丸まった肩|頭が前に|肩が前に|hunched shoulders|slouched shoulders|forward shoulders|shoulders rounded forward|rounded back|round back|upper cross syndrome|scoliosis|fix posture|improve posture|straighten back|체형|체형교정|체형 교정|체형 교정 운동|체형 개선|日本人体型 => posture; muscles=BACK,SHOULDERS; sig=face pull|rear delt|reverse fly|reverse pec|superman|pull apart|y raise|w raise|wall angel|chin tuck|bird dog|dead bug|neck|chest stretch|pec stretch|scapula|フェイスプル|リアデルト|リバースフライ|スーパーマン|プルアパート|Yレイズ|ウォールエンジェル|バードドッグ|デッドバグ|ネック|チェストストレッチ|ペックストレッチ|肩甲骨|페이스풀|리어 델트|리버스 플라이|슈퍼맨|풀 어파트|Y 레이즈|월 엔젤|버드 독|데드 버그|넥|체스트 스트레칭|견갑; sig2=row~|ロー~|ロウ~|로우~|plank|stretch|プランク|ストレッチ|플랭크|스트레칭; flags=strict,stretch
        肩こり|肩凝り|肩コリ|首こり|首凝り|목 뻐근|목뻐근|목 뻣뻣|뻣뻣한 목|목이 뻐근|어깨 뻐근|어깨 뻣뻣|어깨가 뻐근|뻐근한 어깨|어깨 담|목 담|neck stiffness|shoulder stiffness|肩の張り|首の張り|首のこり|어깨 뭉침|어깨 결림|어깨뭉침|목 뭉침|목 결림|stiff shoulders|stiff neck|tight shoulders|tight neck|neck tension|trap tension|neck and shoulder pain => stiffshoulder; muscles=TRAPS,SHOULDERS,NECK; cats=SHOULDERS; sig=shrug|neck|shoulder stretch|trap stretch|scapula|シュラッグ|ネック|ショルダーストレッチ|肩甲骨|슈러그|넥|숄더 스트레칭|견갑; sig2=rear delt|face pull|リアデルト|フェイスプル|리어 델트|페이스풀; flags=strict,stretch
        美尻|납작한 엉덩이|납작 엉덩이|엉덩이 볼륨|엉덩이 업|힙 볼륨|힙볼륨|엉덩이 올리|ヒップ上げ|お尻を持ち上げ|垂れ尻|下がったお尻|ぺたんこ尻|perky butt|lift glutes|sagging butt|flat butt|build glutes|glute building|glute activation|뒤태|뒷태|뒤태 만들기|후면 라인|ヒップライン|힙라인|엉덩이 라인|엉덩이 모양|小尻|스몰 힙|smaller butt|butt shape|glute shape|ヒップアップ|ヒップ アップ|힙업|힙 업|엉짱|애플힙|bubble butt|big butt|bigger butt|round butt|round glutes|rounder glutes|peach butt|booty|booty gains|butt lift|glute growth|bigger glutes|glute gains|hipup|hip up|お尻を大きく|お尻を引き上げ|桃尻|お尻 引き締め|尻上げ|bum|bigger bum => glutegoal; muscles=GLUTES; sig=hip thrust|glute bridge|donkey kick|glute kickback|leg kickback|bent leg kickback|fire hydrant|ヒップスラスト|グルートブリッジ|ドンキーキック|キックバック|ファイヤーハイドラント|힙 쓰러스트|글루트 브리지|동키 킥|킥백|파이어 하이드란트; sig2=glute|bulgarian|romanian|sumo|good morning|abduction|squat|グルート|ブルガリアン|ルーマニアン|スモウ|グッドモーニング|スクワット|글루트|불가리안|루마니안|스모|굿모닝|스쿼트|브리지|ブリッジ|bridge
        内もも|内腿|内転筋|허벅지 틈|허벅지 갭|허벅지갭|허벅지 사이 틈|脚の隙間|太ももの隙間|ももの隙間|내전근|안쪽 허벅지|내허벅지|허벅지 안쪽|inner thigh|inner thighs|adductor|adductors|thigh gap|太もも内側|ももの内側|ももの内|内ももの引き締め|inner leg => innerthigh; muscles=QUADRICEPS,GLUTES,LEGS; cats=LEGS; sig=adductor|adduction|inner thigh|sumo|curtsy|cossack|side lunge|lateral lunge|squeeze|アダクター|アダクション|内もも|スモウ|スモー|相撲|カーツィ|コサック|サイドランジ|ラテラルランジ|スクイーズ|어덕터|어덕션|내전|이너 싸이|스모|커시|코사크|사이드 런지|래터럴 런지|스퀴즈; flags=strict
        glute medius|glute med|glute minimus|中殿筋|외둔근|外もも|外腿|外転筋|외전근|바깥쪽 허벅지|바깥 허벅지|허벅지 바깥쪽|outer thigh|outer thighs|abductor|abductors|hip abductors|中臀筋|중둔근|gluteus medius|side glute|saddlebags|saddle bags|승마살|太もも外側|ももの外側 => outerthigh; muscles=GLUTES,LEGS; cats=LEGS; sig=abduct|fire hydrant|clamshell|side leg raise|side kick|band walk|monster walk|crab walk|lateral walk|アブダクション|アブダクター|ファイヤーハイドラント|クラムシェル|サイドレッグレイズ|サイドキック|モンスターウォーク|어브덕션|어브덕터|파이어 하이드란트|클램쉘|사이드 레그 레이즈|사이드 킥|몬스터 워크; flags=strict
        腕を太く|腕太く|腕を大きく|굵은 팔|팔 키우기|팔 근육|腕の筋肉|ぶっとい腕|big arms|bigger arms|huge arms|arm size|arm mass|arm growth|thicker arms|massive arms|腕周り|腕まわり|팔둘레|팔 굵게 => bigarms; muscles=BICEPS,TRICEPS,FOREARMS; cats=ARMS; sig=curl|extension|pushdown|push down|skull|close grip|カール|エクステンション|プッシュダウン|スカル|컬|익스텐션|푸시다운|스컬
        肩幅|肩幅を広げ|넓은 어깨|어깨 넓히기|어깨 깡패|어깨 볼륨|어깨 라인|boulder shoulders|boulder shoulder|cannonball delts|3d shoulders|3d delts|broad shoulders|wide shoulders|capped delts|肩を大きく|丸い肩|肩 丸く|어깨 뽕|肩の丸み|肩幅広げる => bouldershoulder; muscles=SHOULDERS; cats=SHOULDERS; sig=lateral raise|side raise|サイドレイズ|ラテラルレイズ|레터럴 레이즈|사이드 레이즈|래터럴 레이즈; sig2=shoulder press|overhead press|military press|arnold|upright row|ショルダープレス|オーバーヘッドプレス|ミリタリープレス|アーノルド|숄더프레스|오버헤드 프레스|밀리터리 프레스|아놀드
        전면 어깨|전면 삼각근|전면삼각근|前肩|肩の前|肩の前側|front delts|front delt|anterior delts|anterior deltoid|front shoulder => frontdelt; muscles=SHOULDERS; cats=SHOULDERS; sig=front raise|フロントレイズ|프론트 레이즈; sig2=shoulder press|overhead press|incline|ショルダープレス|オーバーヘッドプレス|숄더프레스|오버헤드 프레스; flags=strict
        측면 어깨|측면 삼각근|측면삼각근|横肩|肩の横|肩の側面|side delts|side delt|lateral delts|middle delts|medial delts|lateral deltoid => sidedelt; muscles=SHOULDERS; cats=SHOULDERS; sig=lateral raise|side raise|サイドレイズ|ラテラルレイズ|레터럴 레이즈|사이드 레이즈|래터럴 레이즈; sig2=upright row|arnold|アップライトロー|アーノルド|업라이트 로우|아놀드; flags=strict
        능형근|菱形筋|rhomboid|rhomboids|중부 승모근|하부 승모근|중부승모근|하부승모근|中部僧帽筋|下部僧帽筋|mid traps|mid trap|lower traps|lower trap|middle traps|middle trap|mid back|mid-back|中背部|등 중간 => midback; muscles=BACK,TRAPS; cats=BACK; sig=row~|face pull|y raise|w raise|pull apart|reverse fly|ロー~|ロウ~|フェイスプル|プルアパート|リバースフライ|로우~|페이스풀|풀 어파트|리버스 플라이; flags=strict
        push day|pushday|push workout|push routine|プッシュデイ|プッシュの日|푸쉬데이|푸시데이|푸쉬 데이|푸시 데이|푸쉬 루틴|푸시 루틴 => pushday; muscles=CHEST,SHOULDERS,TRICEPS; cats=CHEST,SHOULDERS; sig=bench press|chest press|push up|shoulder press|overhead press|dip|fly|lateral raise|pushdown|triceps extension|ベンチプレス|チェストプレス|プッシュアップ|ショルダープレス|ディップ|フライ|ラテラルレイズ|プッシュダウン|벤치프레스|체스트 프레스|푸시업|숄더프레스|딥|플라이|레터럴 레이즈|푸시다운; sig2=incline|decline|インクライン|デクライン|인클라인|디클라인; flags=strict
        pull day|pullday|pull workout|pull routine|プルデイ|プルの日|풀데이|풀 데이|풀 루틴 => pullday; muscles=BACK,LATS,BICEPS; cats=BACK,ARMS; sig=pulldown|pull up|row~|face pull|curl|shrug|rear delt|プルダウン|プルアップ|ロー~|フェイスプル|カール|シュラッグ|リアデルト|풀다운|풀업|로우~|페이스풀|컬|슈러그|리어 델트; flags=strict
        前屈|전굴|forward fold|forward bend|toe touch|長座体前屈 => hamstretch; muscles=HAMSTRINGS; sig=hamstring stretch|forward fold|toe touch|ハムストリングストレッチ|햄스트링 스트레칭|spine stretch|スパインストレッチ|스파인 스트레칭; flags=strict,stretch
        前鋸筋|전거근|serratus|serratus anterior|serratus muscle|セラタス => serratus; muscles=CHEST,SHOULDERS,CORE; sig=push up plus|scapula push|scapular push|serratus|プッシュアッププラス|プッシュアップ プラス|肩甲骨プッシュアップ|スカプラ プッシュアップ|セラタス|푸시업 플러스|견갑골 푸시업|전거근; flags=strict
        回旋筋腱板|ローテーターカフ|腱板|회전근개|rotator cuff|rotator cuffs|shoulder stabilizers|shoulder stabilisers|インナーマッスル 肩|肩のインナー => rotatorcuff; muscles=SHOULDERS; sig=external rotation|internal rotation|face pull|pull apart|エクスターナルローテーション|インターナルローテーション|フェイスプル|プルアパート|외회전|내회전|페이스풀|풀 어파트|rotation|ローテーション|로테이션; flags=strict
        前脛骨筋|전경골근|tibialis|tibialis anterior|すね|脛|shin splints|shin|정강이 => shin; muscles=CALVES; sig=tibialis|toe raise|dorsi|dorsal flexion|heel walk|背屈|ティビアリス|토 레이즈|정강이; flags=strict
        上腹部|上腹|상복부|윗배|upper abs|upper ab|upper stomach|upper belly => upperabs; muscles=ABS,CORE; cats=CORE; sig=crunch|sit up|cable crunch|rollout|ab wheel|クランチ|シットアップ|ロールアウト|アブローラー|크런치|싯업|롤아웃|앱 휠; pat=CRUNCH,ROLLOUT; flags=strict
        겨드랑이살|脇肉|副乳|armpit fat|bra bulge|バストサイド|가슴 옆살 => armpit; muscles=CHEST; cats=CHEST; sig=fly|push up|crossover|pec deck|chest press|フライ|プッシュアップ|クロスオーバー|ペックデック|チェストプレス|플라이|푸시업|크로스오버|펙덱|체스트 프레스; flags=strict
        등살|등살빼기|등살 빼기|背中の肉|背肉|ブラ肉|ブラの肉|back fat|bra fat|bra line|브라 라인|브라라인 => backfat; muscles=BACK,LATS; cats=BACK; sig=row~|pulldown|pull up|ロー~|ロウ~|プルダウン|プルアップ|로우~|풀다운|풀업; pat=LAT_PULLDOWN,PULLUP_CHINUP,BARBELL_ROW,DUMBBELL_ROW,CABLE_ROW
        握力|악력|grip strength|grip training|hand strength|forearm strength|손아귀 => grip; muscles=FOREARMS; cats=ARMS; sig=wrist curl|farmer|dead hang|pinch|reverse curl|hammer curl|リストカール|ファーマー|デッドハング|ハンマーカール|리스트 컬|파머|데드 행|해머 컬; flags=strict
        힙딥|hip dip|hip dips|ヒップディップス|ヒップのくぼみ|ヒップの凹み => hipdip; muscles=GLUTES; sig=abduct|fire hydrant|clamshell|side leg raise|side kick|band walk|monster walk|side lunge|curtsy|アブダクション|ファイヤーハイドラント|クラムシェル|サイドレッグレイズ|サイドキック|サイドランジ|カーツィ|어브덕션|앱덕션|파이어 하이드란트|클램쉘|사이드 레그 레이즈|사이드 킥|사이드 런지|커시; flags=strict
        胸の上部|胸上部|上部胸|大胸筋上部|胸の上|대흉근 상부|가슴 상부|윗가슴|가슴 윗부분|upper chest|upper pecs|clavicular|top of chest|上胸 => upperchest; muscles=CHEST; cats=CHEST; sig=incline|upper chest|low fly|low to high|low cable|インクライン|アッパーチェスト|ロー トゥ ハイ|인클라인|어퍼 체스트; flags=strict
        胸の下部|胸下部|大胸筋下部|胸の下|가슴 하부|아랫가슴|가슴 아랫부분|lower chest|lower pecs|bottom of chest|下胸 => lowerchest; muscles=CHEST; cats=CHEST; sig=decline|dip|lower chest|high to low|デクライン|ディップ|ハイ トゥ ロー|디클라인|딥; sig2=fly|press|フライ|プレス|플라이|프레스
        バストアップ|バスト|胸の谷間|谷間|cleavage|bust lift|breast lift|breast|가슴 모양|가슴 처짐|처진 가슴|가슴 업|가슴 볼륨|가슴 모으기|小胸筋|소흉근|pec minor|pectoralis minor|胸の内側|가슴 안쪽|inner chest|chest squeeze|胸の中央|가슴 가운데|가슴 중앙|middle chest|mid chest|가슴 중부|胸の中部|胸中部|中部胸 => innerchest; muscles=CHEST; cats=CHEST; sig=fly|crossover|pec deck|squeeze|bench press|chest press|フライ|クロスオーバー|ペックデック|ベンチプレス|チェストプレス|플라이|크로스오버|펙덱|벤치프레스|체스트 프레스
        後面肩|肩の後ろ|肩の後面|후면 어깨|후면 삼각근|후면삼각근|rear delts|rear delt|rear deltoids|rear deltoid|back of shoulders|後部三角筋|三角筋後部|リアデルト|리어델트|리어 델트|posterior delts|rear shoulder|rear shoulders => reardelt; muscles=SHOULDERS; cats=SHOULDERS; sig=rear delt|reverse fly|rear lateral|face pull|reverse pec|rear deltoid|リアデルト|リバースフライ|リアレイズ|フェイスプル|리어 델트|리버스 플라이|리어 레터럴|페이스풀|후면; flags=strict
        開脚|開脚ストレッチ|다리 찢기|다리찢기|splits|side splits|股関節|고관절|hip joint|hip mobility|hips mobility|hip opener|ヒップオープナー|힙 오프너|股関節の柔軟|股関節周り|고관절 유연성|hip flexor|hip flexors|腸腰筋|장요근|hip openers|tight hips|hip tightness => hipjoint; muscles=GLUTES,QUADRICEPS; sig=hip flexor|pigeon|90 90|butterfly|frog|lizard|hip opener|cossack|happy baby|figure four|iliopsoas|ヒップフレクサー|ピジョン|バタフライ|フロッグ|リザード|ハッピーベイビー|腸腰筋|힙 플렉서|피전|나비|프로그|개구리|도마뱀|장요근; flags=strict,stretch
        首|목|neck|ネック|넥|목 운동|首の運動 => neck; muscles=NECK; sig=neck stretch|ネックストレッチ|넥 스트레칭|neck extension|neck flexion|neck rotation|neck curl|neck bridge|ネックエクステンション|ネックフレクション|넥 익스텐션|넥 플렉션; avoid=behind neck|ビハインドネック|비하인드 넥; flags=strict,stretch
        全身|全身運動|全身トレーニング|전신|전신운동|full body|fullbody|whole body|total body|full-body|total-body => fullbody; sig=burpee|clean|snatch|thruster|swing|mountain climber|jumping jack|turkish|bear crawl|man maker|farmer|carry|squat to press|バーピー|クリーン|スナッチ|スラスター|スイング|マウンテンクライマー|ジャンピングジャック|ターキッシュ|ベアクロール|ファーマー|キャリー|버피|클린|스내치|스러스터|스윙|마운틴 클라이머|점핑 잭|터키시|베어 크롤|파머|캐리; pat=CLEAN,SNATCH,CARRY,PLYOMETRIC; flags=strict
        反り腰|反り腰改善|骨盤|骨盤矯正|骨盤前傾|骨盤後傾|골반|골반 교정|골반교정|골반 전방경사|전방경사|골반 틀어짐|swayback|sway back|anterior pelvic tilt|pelvic tilt|pelvis|lordosis|lumbar lordosis|골반 운동|産後|産後ケア|산후|산후 운동|postpartum|post partum|postnatal|after pregnancy|diastasis recti|diastasis|腹直筋離開|복직근 이개|임산부|pregnancy|prenatal|妊娠|妊婦|骨盤底筋|pelvic floor|골반저근|케겔|kegel|ケーゲル => pelvis; muscles=GLUTES,CORE,ABS; sig=glute bridge|dead bug|plank|hip flexor|pelvic|bird dog|cat|child pose|reverse crunch|leg raise|グルートブリッジ|デッドバグ|プランク|ヒップフレクサー|バードドッグ|キャット|チャイルド|リバースクランチ|レッグレイズ|글루트 브리지|데드 버그|플랭크|힙 플렉서|버드 독|캣|차일드|리버스 크런치|레그 레이즈; sig2=bridge|stretch|ブリッジ|ストレッチ|브리지|스트레칭; flags=strict,stretch
        肩甲骨|肩甲骨はがし|날개뼈|견갑골|견갑|scapula|scapular|shoulder blade|shoulder blades|shoulder blade squeeze => scapula; muscles=BACK,TRAPS,SHOULDERS; cats=BACK; sig=scapula|scapular|face pull|y raise|w raise|wall angel|pull apart|row~|retraction|retractor|肩甲骨|フェイスプル|プルアパート|ウォールエンジェル|견갑|페이스풀|풀 어파트|월 엔젤; sig2=rear delt|shrug|リアデルト|シュラッグ|리어 델트|슈러그; flags=stretch
        有酸素|有酸素運動|유산소|유산소 운동|カーディオ|cardio|aerobic|aerobics|エアロビ|에어로빅|cardiovascular|심폐|심폐지구력 => cardio; cats=CARDIO; sig=bike|cycle|rowing machine|treadmill|run|jog|walk|jump rope|skipping|elliptical|stair|stepper|burpee|mountain climber|jumping jack|sprint|swim|battle rope|バイク|ローイングマシン|トレッドミル|ランニング|ジョギング|ウォーク|縄跳び|スキッピング|バーピー|マウンテンクライマー|ジャンピングジャック|スプリント|자전거|바이크|로잉머신|트레드밀|러닝|조깅|걷기|줄넘기|스키핑|버피|마운틴 클라이머|점핑 잭|스프린트; avoid=crunch|curl|press|row~|face pull|raise|stretch|クランチ|カール|プレス|ロー~|ロウ~|フェイスプル|ストレッチ|크런치|컬|프레스|로우~|페이스풀|스트레칭; flags=cardio
        HIIT|ハイット|ハイト|하이트|히트|tabata|タバタ|타바타|サーキット|서킷|circuit|circuits|interval|intervals|インターバル|인터벌|high intensity|고강도|高強度|クロスフィット|crossfit|크로스핏|metcon|conditioning|コンディショニング => hiit; cats=CARDIO; sig=burpee|mountain climber|jumping jack|jump squat|squat jump|box jump|high knee|skater|sprint|jump rope|battle rope|swing|thruster|plank jack|バーピー|マウンテンクライマー|ジャンピングジャック|ジャンプスクワット|ボックスジャンプ|ハイニー|スケーター|スプリント|縄跳び|バトルロープ|スイング|버피|마운틴 클라이머|점핑 잭|점프 스쿼트|박스 점프|하이니|스케이터|스프린트|줄넘기|배틀 로프|스윙; avoid=crunch|curl|stretch|クランチ|カール|ストレッチ|크런치|컬|스트레칭; flags=cardio
        ダイエット|脂肪燃焼|체지방|지방 연소|다이어트|살 빼기|살빼기|살 빼는|컷팅|커팅|カッティング|감량|감량기|살빠지는|살 빠지는|살이 빠지는|살 빠지게|살 잘 빠지는|살 빠지는 운동|살 뺄 수 있는|살 빼기 좋은|살 태우는|살 태우기|살 빼려면|지방 태우는|지방 태우기|지방태우기|지방 감소|지방 줄이는|체지방 감소|체지방 줄이는|체지방 태우기|체중 감량|체중감량|체중 줄이는|몸무게 줄이는|몸무게 빼는|칼로리 태우는|칼로리 태우기|칼로리 소모 운동|다이어트 운동|다이어트에 좋은|다이어트용|痩せる|痩せたい|痩せ|やせる|やせたい|痩せる運動|痩せやすい|痩せるための|脂肪を燃やす|脂肪を落とす|脂肪を減らす|体脂肪を落とす|体脂肪を減らす|カロリー消費|カロリーを消費|カロリー燃焼|カロリーを燃やす|消費カロリー|燃焼系|体重を落とす|体重を減らす|ダイエット運動|lose fat|losing fat|losing weight|lose weight fast|burn more calories|burning fat|burning calories|melt fat|drop weight|weight reduction|calorie burning|fat burner|fatburn|fat-burning|burn off fat|slim down fast|get slim|get skinny|lose belly|diet|fat loss|fat burning|burn fat|burn calories|lose weight|weight loss|slim down|get lean|calorie burn|칼로리 소모|減量|減脂|痩身|fat burn|shred|shredding|cutting => fatloss
        3대|3대운동|삼대|big 3|big three|big3|三大|ビッグ3|ビッグスリー|power lifting|powerlifting|パワーリフティング|파워리프팅 => big3; sig=full squat|back squat|bench press|deadlift~|フルスクワット|バックスクワット|ベンチプレス|デッドリフト~|풀 스쿼트|백 스쿼트|벤치프레스|데드리프트~; flags=strict
        11자|11자 복근|왕자|왕자 복근|식스팩|6팩|six pack|sixpack|six-pack|abs line|abs lines|腹筋を割|割れた腹筋|シックスパック|腹筋バキバキ|腹筋割る|flat stomach|flat belly|flat abs|flat tummy|납작한 배|平らなお腹|お腹ぺたんこ => sixpack; muscles=ABS,CORE; cats=CORE
        뱃살|똥배|뱃살빼기|お腹周り|お腹まわり|ぽっこりお腹|お腹痩せ|腹周り|tummy|belly fat|belly|stomach fat|abdominal fat|midsection|mid section|belly bulge|beer belly|pot belly|ぽっこり|下腹ぽっこり|お腹の脂肪|내장지방|뱃살 빼는|肚子 => sixpack
        足首|あしくび|足首の運動|足首トレ|足首 強化|足首を鍛える|발목|발목 운동|발목 강화|발목 근력|ankle|ankles|ankle mobility|ankle strength|ankle exercises|ankle stability => anklecare; muscles=CALVES; cats=LEGS; sig=ankle|dorsal flexion|plantar flexion|アンクル|발목; sig2=calf stretch|gastrocnemius|soleus|achilles|calf raise|toe raise|tibialis|カーフストレッチ|カーフレイズ|카프 스트레칭|카프 레이즈; flags=strict
        手首|てくび|手首の運動|手首を鍛える|손목|손목 운동|손목 강화|손목 근력|wrist|wrists|wrist mobility|wrist strength|wrist exercises => wristcare; muscles=FOREARMS; cats=ARMS; sig=wrist|forearm stretch|リスト|手首|손목|리스트; sig2=reverse curl|farmer|forearm|リバースカール|ファーマー|전완|리버스 컬; flags=strict
        膝|ひざ|ヒザ|膝の運動|膝周り|膝を鍛える|무릎|무릎 운동|무릎 강화|무릎 주변|무릎 근력|knee strengthening|knee exercises|knee strength|knee stability|strong knees|healthy knees => kneecare; muscles=QUADRICEPS,HAMSTRINGS,GLUTES; cats=LEGS; sig=leg extension|leg curl|step up|wall sit|glute bridge|straight leg raise|terminal knee|knee extension|レッグエクステンション|レッグカール|ステップアップ|ウォールシット|グルートブリッジ|레그 익스텐션|레그 컬|스텝업|월 싯|글루트 브리지; flags=strict
        肘|ひじ|ヒジ|肘の運動|肘周り|팔꿈치|팔꿈치 운동|팔꿈치 강화|elbow|elbows|elbow strength|elbow exercises => elbowcare; muscles=BICEPS,TRICEPS,FOREARMS; cats=ARMS; sig=elbow|hammer curl|reverse curl|forearm|エルボー|ハンマーカール|リバースカール|팔꿈치|해머 컬|리버스 컬|전완; flags=strict
        ヨガ|ヨーガ|ヨガポーズ|ヨガのポーズ|ヨガ ポーズ|요가|요가 자세|요가 포즈|요가 동작|요가 스트레칭|yoga|yoga pose|yoga poses|yoga flow|yoga stretches|yoga stretch|asana|asanas|アーサナ|아사나 => yoga; sig=yoga|pose~|cat cow|child pose|pigeon|crab pose|ヨガ|キャットアンドカウ|チャイルドポーズ|ピジョンポーズ|요가|캣 카우|차일드 포즈|비둘기 자세; flags=strict,stretch
        러닝 후|런닝 후|러닝 끝나고|런닝 끝나고|달리기 후|달리기 끝나고|뛰고 나서|조깅 후|마라톤 후|ランニング後|ランニングの後|ランニング後の|ジョギング後|走った後|走った後の|マラソン後|after running|after a run|after jogging|post run|post-run|postrun|runners|runner|for runners|runner stretch|running recovery => postrun; muscles=LEGS,QUADRICEPS,HAMSTRINGS,CALVES,GLUTES; sig=runner stretch|calf stretch|gastrocnemius|hamstring stretch|quadriceps stretch|hip flexor stretch|glute stretch|it band|couch stretch|pigeon|ランナー|カーフストレッチ|ふくらはぎ ストレッチ|ハムストリング ストレッチ|大腿四頭筋ストレッチ|ヒップフレクサー|러너|종아리 스트레칭|햄스트링 스트레칭|쿼드 스트레칭|힙 플렉서; flags=strict,stretch
        座ってできる|座ってできる運動|座って|座ったまま|座りながら|イスに座って|椅子に座って|椅子で|앉아서 하는|앉아서하는|앉아서|앉은 채로|의자에 앉아|의자 운동|seated workout|seated exercises|seated|sitting down|sitting|sit down workout|chair exercises|chair workout|on a chair|from a chair|while sitting|desk chair|chair => pos-seated; sig=seated|sitting|chair|シーテッド|イス|椅子|시티드|의자; flags=strict
        寝ながらできる|寝ながら|寝たまま|寝転がって|横になって|仰向け|床で|누워서 하는|누워서하는|누워서|누운 채로|바닥에서|lying down|laying down|lie down|lying|supine|on the floor|floor exercises|floor workout|mat workout|mat exercises|in bed|bed workout => pos-lying; sig=lying|supine|floor|mat|ライイング|仰向け|マット|라잉|매트; flags=strict
        立ったままできる|立ったまま|立ちながら|서서 하는|서서하는|서서|선 채로|standing workout|standing exercises|standing|on your feet|stand up|without lying down|without getting on the floor => pos-standing; sig=standing|スタンディング|스탠딩; flags=strict
        脚やせ|脚痩せ|足痩せ|足やせ|太もも痩せ|太ももやせ|ももやせ|太ももを細く|脚を細く|脚を細くしたい|ふくらはぎ痩せ|다리 얇아지는|다리 얇게|다리 가늘게|다리 슬림|다리살 빼기|다리살 빼는|다리살|허벅지 얇아지는|허벅지 얇게|허벅지 슬림|허벅지살 빼기|허벅지살 빼는|허벅지살|허벅지 지방|종아리살|종아리 얇아지는|종아리 얇게|slim legs|slimmer legs|thinner legs|thin legs|skinny legs|lean legs|leg slimming|slim thighs|slimmer thighs|thinner thighs|thigh slimming|lose thigh fat|thigh fat|leg fat|trim thighs|toned legs|toned thighs|tone legs => legslim; muscles=LEGS,QUADRICEPS,HAMSTRINGS,GLUTES,CALVES; cats=LEGS,CARDIO; sig=walking lunge|bodyweight squat|reverse lunge|side lunge|jumping jack|jump rope|stationary bike|spin bike|stair climber|treadmill|cycling|step up|glute bridge|wall sit|ウォーキングランジ|自重スクワット|リバースランジ|サイドランジ|ジャンピングジャック|縄跳び|エアロバイク|ステアクライマー|トレッドミル|ステップアップ|워킹 런지|맨몸 스쿼트|리버스 런지|사이드 런지|점핑 잭|줄넘기|실내 자전거|스텝밀|트레드밀|스텝업|월 싯; sig2=squat|lunge|calf raise|スクワット|ランジ|カーフレイズ|스쿼트|런지|카프 레이즈
        코어|코어 운동|코어운동|코어 근육|코어 강화|코어 단련|core|core workout|core workouts|core strength|core muscles|core stability|core training|core exercises|体幹|体幹トレーニング|体幹トレ|体幹強化|体幹を鍛える|コア|コアトレ|コアトレーニング|コア強化 => core; muscles=ABS,CORE; cats=CORE; sig=plank|dead bug|bird dog|hollow|pallof|bear crawl|side plank|rollout|ab wheel|プランク|デッドバグ|バードドッグ|ホロー|パロフ|ベアクロール|サイドプランク|ロールアウト|アブローラー|플랭크|데드 버그|버드 독|할로우|팔로프|베어 크롤|사이드 플랭크|롤아웃|앱 휠; sig2=crunch|sit up|leg raise|mountain climber|russian twist|クランチ|シットアップ|レッグレイズ|マウンテンクライマー|크런치|싯업|레그 레이즈|마운틴 클라이머|러시안 트위스트
        ウォームアップ|ウォーミングアップ|ウォーム アップ|ウォームアップ運動|ウォームアップメニュー|準備運動|準備体操|ならし運動|動的ストレッチ|ダイナミックストレッチ|워밍업|워밍 업|웜업|워밍업 운동|몸풀기|몸 풀기|몸풀이|몸 푸는|준비 운동|준비운동|준비 체조|동적 스트레칭|다이나믹 스트레칭|다이내믹 스트레칭|warm up|warmup|warm-up|warm ups|warmups|warming up|dynamic warm up|dynamic warmup|dynamic stretching|dynamic stretches|dynamic stretch|pre workout warm up|prepare for workout|get warm|loosen up => warmup; sig=worlds greatest stretch|jumping jack|high knee|cat cow|thoracic rotation|walking lunge|mountain climber|bird dog|skipping|arm circle|leg swing|hip circle|inchworm|butt kick|bodyweight squat|dead bug|bear crawl|ワールドグレイテスト|ジャンピングジャック|ハイニー|キャットアンドカウ|キャットカウ|胸椎回旋|自重スクワット|ウォーキングランジ|マウンテンクライマー|バードドッグ|スキッピング|アームサークル|レッグスイング|インチワーム|월드 그레이티스트|점프 잭|하이 니|캣 카우|흉추 회전|맨몸 스쿼트|워킹 런지|마운틴 클라이머|버드 독|스키핑|암 서클|레그 스윙|인치웜; sig2=bodyweight glute bridge|glute bridge|hip opener|cossack|burpee|high plank|plank|lunge with twist|shoulder roll|neck rotation|ヒップオープナー|コサック|バーピー|プランク|힙 오프너|코사크|버피|플랭크; flags=strict
        쿨다운|쿨 다운|정리 운동|정리운동|마무리 스트레칭|운동 후 스트레칭|クールダウン|クール ダウン|整理体操|運動後のストレッチ|cool down|cooldown|cool-down|post workout stretch|after workout stretch|general stretching|일반 스트레칭|全般ストレッチ|基本のストレッチ|stretching routine|stretch routine|full body stretching routine|疲労回復|疲れを取る|疲れをとる|疲れた時|疲れたとき|リカバリー|アクティブリカバリー|筋肉痛|筋肉痛予防|コリをほぐす|피로 회복|피로회복|피로 풀기|피로풀기|피로 해소|피로해소|피곤할 때|근육통|근육통 완화|회복 운동|리커버리|recovery|active recovery|recovery day|rest day|sore muscles|muscle soreness|tired muscles|relax muscles|wind down|unwind|relaxation => stretchgeneral; sig=child pose|cat cow|worlds greatest stretch|kneeling hip flexor stretch|couch stretch|standing quadriceps stretch|seated single leg hamstring stretch|hamstring stretch|pigeon pose|thoracic rotation|across chest shoulder stretch|neck stretch|standing gastrocnemius stretch|seated calf stretch|lying glute stretch|knee to chest stretch|90 to 90 stretch|adductor stretch|corner wall chest stretch|triceps light stretch|チャイルドポーズ|キャットアンドカウ|ワールドグレイテストストレッチ|ヒップフレクサーストレッチ|カウチストレッチ|大腿四頭筋ストレッチ|ハムストリングストレッチ|ピジョンポーズ|胸椎回旋|首のストレッチ|차일드 포즈|캣 카우|월드 그레이티스트|힙 플렉서 스트레칭|카우치 스트레치|쿼드 스트레칭|햄스트링 스트레칭|비둘기 자세|흉추 회전|목 스트레칭; sig2=stretch|ストレッチ|스트레칭; flags=strict,stretch
    """.trimIndent()

    // ── 부정 대상 묶음 ───────────────────────────────────────────────────────────────────────
    private val MOVE_RULES = """
        jump: jump|jumps|jumping|점프|점핑|ジャンプ|ジャンピング|plyo|plyometric|plyometrics|플라이오|플라이오메트릭|プライオ|プライオメトリクス|burpee|버피|バーピー|hop|hopping|jack|jacks|ジャック|skipping|스키핑|スキッピング|줄넘기|縄跳び|jump rope|box jump|ボックスジャンプ|박스 점프|높이뛰기|바운딩|bounding|뜀뛰기|뜀|ジャンプ系
        deadlift: deadlift|deadlifts|데드리프트|데드|デッドリフト|デットリフト|デッド|dl
        squat: squat|squats|스쿼트|스쿼|スクワット
        lunge: lunge|lunges|런지|ランジ
        pushup: push up|pushup|푸시업|푸쉬업|腕立て|腕立て伏せ|プッシュアップ|팔굽혀펴기
        pullup: pull up|pullup|chin up|턱걸이|풀업|친업|懸垂|プルアップ|チンアップ|チンニング
        bench: bench|벤치|ベンチ|ベンチ台|벤치대|벤치 프레스|ベンチプレス|bench press
        press: press|프레스|プレス
        curl: curl|컬|カール
        row: row~|로우~|ロー~|ロウ~
        crunch: crunch|크런치|クランチ|sit up|situp|싯업|シットアップ
        plank: plank|플랭크|プランク
        dip: dip|딥|ディップ
        run: running|run|jog|jogging|러닝|런닝|조깅|달리기|뛰기|ランニング|ジョギング|走る|走り|走ること|マラソン|ダッシュ|sprint|스프린트
        stretch: stretch|스트레칭|ストレッチ
        twist: twist|twisting|트위스트|ツイスト|rotation|회전|ローテーション
        kneel: kneel|kneeling|니링|ニーリング|무릎 꿇|膝立ち
        barbell: barbell|바벨|バーベル
        dumbbell: dumbbell|덤벨|ダンベル
        machine: machine|머신|マシン
        cable: cable|케이블|ケーブル
        kettlebell: kettlebell|케틀벨|ケトルベル
        band: band|밴드|バンド|resistance band|저항밴드
        wall: wall|벽|ウォール
        weight: weights|weight|웨이트|ウェイト
    """.trimIndent()

    // ── 부상 프로필 ──────────────────────────────────────────────────────────────────────────
    private val INJURY_RULES = """
        KNEE; hard=kneel|jump|plyo|pistol|sissy|burpee|hop|box jump|skater|squat jump|jack|bound|ニーリング|ジャンプ|プライオ|ピストル|シシー|バーピー|ホップ|ボックスジャンプ|スケーター|니링|점프|플라이오|피스톨|시시|버피|홉|박스 점프|스케이터; soft=lunge|squat|step up|bulgarian|hack|leg extension|split squat|cossack|duck walk|deep|ランジ|スクワット|ステップアップ|ブルガリアン|ハック|レッグエクステンション|スプリット|런지|스쿼트|스텝업|불가리안|핵|레그 익스텐션|스플릿; prefer=glute bridge|hip thrust|leg curl|leg press|romanian|good morning|back extension|calf raise|abduction|clamshell|straight leg raise|wall sit|ヒップスラスト|グルートブリッジ|レッグカール|レッグプレス|ルーマニアン|グッドモーニング|バックエクステンション|カーフレイズ|アブダクション|クラムシェル|ウォールシット|힙 쓰러스트|글루트 브리지|레그 컬|레그 프레스|루마니안|굿모닝|백 익스텐션|카프 레이즈|어브덕션|클램쉘|월 싯
        LOWER_BACK; hard=deadlift|good morning|rack pull|stiff leg|clean|snatch|jump|burpee|jerk|swing|デッドリフト|グッドモーニング|ラックプル|スティッフ|クリーン|スナッチ|ジャンプ|バーピー|スイング|데드리프트|굿모닝|랙 풀|스티프|클린|스내치|점프|버피|스윙; soft=bent over|barbell row|hyperextension|back extension|sit up|twist|romanian|ベントオーバー|ハイパーエクステンション|バックエクステンション|シットアップ|ツイスト|ルーマニアン|벤트 오버|하이퍼 익스텐션|백 익스텐션|싯업|트위스트|루마니안; prefer=pulldown|pull down|pull up|seated row|chest supported|cable row|face pull|bird dog|dead bug|plank|bridge|lat pull|プルダウン|プルアップ|シーテッドロー|チェストサポート|ケーブルロー|フェイスプル|バードドッグ|デッドバグ|プランク|ブリッジ|풀다운|풀업|시티드 로우|체스트 서포티드|케이블 로우|페이스풀|버드 독|데드 버그|플랭크|브리지
        SHOULDER; hard=behind neck|upright row|snatch|jerk|ビハインドネック|アップライトロー|スナッチ|ジャーク|비하인드 넥|업라이트 로우|스내치|저크; soft=overhead|military|dip|handstand|オーバーヘッド|ミリタリー|ディップ|オーバーヘッド|오버헤드|밀리터리|딥; prefer=external rotation|pull apart|face pull|row~|pulldown|rear delt|machine|エクスターナルローテーション|プルアパート|フェイスプル|ロー~|ロウ~|プルダウン|リアデルト|マシン|풀 어파트|페이스풀|로우~|풀다운|리어 델트|머신
        WRIST; hard=handstand|wrist curl|ハンドスタンド|リストカール|핸드스탠드|리스트 컬; soft=push up|plank|プッシュアップ|プランク|푸시업|플랭크; prefer=machine|cable|hammer|forearm plank|マシン|ケーブル|ハンマー|머신|케이블|해머
        ELBOW; hard=skull|dip|スカル|ディップ|스컬|딥; soft=close grip|overhead extension|curl|クローズグリップ|オーバーヘッドエクステンション|カール|클로즈 그립|컬; prefer=machine|cable|pulldown|row~|マシン|ケーブル|プルダウン|머신|케이블|풀다운
        NECK; hard=behind neck|neck bridge|ビハインドネック|비하인드 넥; soft=shrug|シュラッグ|슈러그; prefer=stretch|ストレッチ|스트레칭
        HIP; hard=jump|plyo|ジャンプ|점프; soft=sumo|split|lunge|スモウ|スプリット|ランジ|스모|스플릿|런지; prefer=bridge|clamshell|abduction|ブリッジ|クラムシェル|아브덕션|브리지
        ANKLE; hard=jump|plyo|box jump|skipping|ジャンプ|プライオ|점프|플라이오; soft=calf raise|lunge|カーフレイズ|ランジ|카프 레이즈|런지; prefer=leg press|bridge|machine|レッグプレス|ブリッジ|マシン|레그 프레스|브리지|머신
    """.trimIndent()

    /** 병명·부상명 — 말하는 순간 그 관절의 부상 단서다 */
    private val CONDITION_RULES = """
        KNEE: 膝痛持ち|bad knee|weak knee|sore knee|knee injury|knee issues|knee problems|膝が悪い|膝を痛め|무릎이 안 좋|膝痛|무릎통증|무릎 통증|knee pain|半月板|meniscus|반월상연골|십자인대|前十字靭帯|acl|슬개건|runner's knee|ランナーズニー|무릎연골|膝蓋腱炎
        LOWER_BACK: hurt my back|hurts my back|hurting my back|hurt back|aggravate my back|aggravating my back|bad for my back|腰痛持ち|bad back|weak back|sore back|back injury|back issues|back problems|bad lower back|腰が悪い|腰が弱い|허리가 안 좋|허리 안 좋|腰痛|요통|허리통증|허리 통증|back pain|backpain|low back pain|lower back pain|디스크|허리디스크|椎間板ヘルニア|ヘルニア|herniated disc|slipped disc|disc|sciatica|좌골신경통|협착증|脊柱管狭窄症|ぎっくり腰|坐骨神経痛
        SHOULDER: 肩痛|肩の痛み|어깨통증|어깨 통증|shoulder pain|오십견|五十肩|frozen shoulder|rotator cuff tear|rotator cuff injury|rotator cuff pain|회전근개 파열|회전근개 손상|腱板断裂|腱板損傷|肩関節周囲炎|インピンジメント|impingement
        ELBOW: 肘痛|팔꿈치통증|팔꿈치 통증|elbow pain|テニス肘|tennis elbow|golfer's elbow|골프엘보|테니스엘보|ゴルフ肘
        WRIST: 手首痛|손목통증|손목 통증|wrist pain|腱鞘炎|carpal tunnel|손목터널|손목 터널
        ANKLE: 足首痛|발목통증|발목 통증|ankle pain|足底筋膜炎|plantar fasciitis|발목 염좌|捻挫|ねんざ|ankle sprain
        HIP: 股関節痛|고관절통증|고관절 통증|hip pain
        NECK: 首痛|목통증|목 통증|neck pain|寝違え|목디스크|頸椎ヘルニア|neck disc
    """.trimIndent()

    private val WEAK_JOINT_KEYS: Set<String> = listOf("back", "背中", "등").map { key(it) }.toSet()

    private val JOINT_RULES = """
        KNEE: 膝|ひざ|ヒザ|무릎|knee|knees|膝関節|무릎 관절
        LOWER_BACK: back|背中|등|腰|腰痛|허리|요통|허리통증|허리 통증|lower back|lowerback|low back|back pain|backpain|spine|척추|背骨|脊椎|腰椎
        SHOULDER: 肩|肩関節|어깨|shoulder|shoulders|어깨 관절
        WRIST: 手首|손목|wrist|wrists
        ELBOW: 肘|팔꿈치|elbow|elbows
        NECK: 首|목|neck|頸椎
        HIP: 股関節|고관절|hip joint|hips joint|골반|骨盤|pelvis
        ANKLE: 足首|발목|ankle|ankles
    """.trimIndent()

    // ── 동작 이름 단어(머리 단어)와 부위 단어 ────────────────────────────────────────────────
    /**
     * 이름에 있으면 "그 동작이 본체"인 단어. 질의에 없는 머리 단어가 이름에 붙어 있으면 다른 운동일 가능성이 높다
     * (jump rope row ≠ jump rope), 질의의 머리 단어가 이름에 없는데 다른 머리 단어가 있으면 다른 운동이다(dip ≠ pull up).
     */
    val headWords: List<SigKey> = listOf(
        "row~", "curl", "press", "squat", "lunge", "crunch", "raise", "fly", "pulldown", "pull up", "push up", "chin up", "deadlift",
        "extension", "kickback", "twist", "shrug", "dip", "plank", "thrust", "bridge", "swing", "clean", "snatch", "jerk",
        "face pull", "burpee", "climber", "adduct", "abduct", "rollout", "step up",
        "ロー~", "ロウ~", "カール", "プレス", "スクワット", "ランジ", "クランチ", "レイズ", "フライ", "プルダウン", "プルアップ", "プッシュアップ", "チンアップ", "チンニング",
        "デッドリフト", "エクステンション", "キックバック", "ツイスト", "シュラッグ", "ディップ", "プランク", "スラスト", "ブリッジ", "スイング", "フェイスプル",
        "アダク", "アブダク", "ロールアウト", "ステップアップ",
        "로우~", "컬", "프레스", "스쿼트", "런지", "크런치", "레이즈", "플라이", "풀다운", "풀업", "푸시업", "친업", "데드리프트",
        "익스텐션", "킥백", "트위스트", "슈러그", "딥", "플랭크", "쓰러스트", "브리지", "스윙", "페이스풀",
        "애덕", "어덕", "앱덕", "어브덕", "롤아웃", "스텝업"
    ).mapNotNull { sigKey(it) }

    /** 같은 부위를 가리키는 표기 묶음 — 질의의 부위 단어가 이름에 없고 이름에 다른 부위 단어가 있으면 다른 운동(leg extension ≠ back extension) */
    val regionGroups: List<List<SigKey>> = listOf(
        "leg|レッグ|脚|足|下半身|레그|다리|하체|hamstring|quad|thigh|ハムストリング|햄스트링|허벅지|太もも",
        "back|lat|등|背中|背|ラット|バック|백|광배|hyper|하이퍼|ハイパー",
        "chest|pec|胸|가슴|チェスト|체스트",
        "shoulder|delt|肩|어깨|ショルダー|숄더|삼각",
        "arm|bicep|tricep|팔|이두|삼두|腕|アーム|암|二頭|三頭|上腕|二の腕|forearm|wrist|전완|손목|手首|前腕",
        "abs|ab|core|복근|腹筋|코어|腹|アブ",
        "hip|glute|butt|お尻|尻|엉덩이|힙|ヒップ|글루트|グルート",
        "calf|calve|ふくらはぎ|종아리|カーフ|카프",
        "neck|首|목|ネック|넥"
    ).map { g -> g.split("|").mapNotNull { sigKey(it) } }

    /**
     * 그것만으로는 운동을 특정하지 못하는 일반 단어(장비·수식어). 질의의 일부만 맞는 부분 일치에서
     * 이런 단어 하나만 맞은 이름(예: "smith machine squat" 에 seated calf machine)은 일치로 치지 않는다.
     */
    val genericWords: Set<String> = (
        "bar|grip|wide|close|narrow|single|double|alternate|alternating|seated|standing|lying|with|on|to|the|and|of|in|a|up|down|out|over|under|through|forward|backward|high|low|stretch|stretching|pose|yoga|ストレッチ|ポーズ|ヨガ|스트레칭|포즈|요가|" +
            "와이드|클로즈|그립|바|시티드|스탠딩|라잉|싱글|원|인|위드|ワイド|クローズ|グリップ|バー|シーテッド|スタンディング|ライイング|シングル|ワン"
        ).split("|").map { key(it) }.filter { it.isNotEmpty() }.toSet() + ExerciseSearchLexicon.equipmentTerms.keys

    /** 퍼지·바이그램 비교에서 뺄 단어 — 장비·그립/자세 수식어·스트레칭 일반어. (up/down 같은 방향어는 이름 핵심이라 빼지 않는다) */
    val fuzzyIgnorable: Set<String> = (
        "bar|grip|wide|close|narrow|single|double|alternate|alternating|seated|standing|lying|stretch|stretching|pose|yoga|" +
            "와이드|클로즈|그립|바|시티드|스탠딩|라잉|싱글|스트레칭|포즈|요가|ワイド|クローズ|グリップ|バー|シーテッド|スタンディング|ライイング|シングル|ストレッチ|ポーズ|ヨガ"
        ).split("|").map { key(it) }.filter { it.isNotEmpty() }.toSet() + ExerciseSearchLexicon.equipmentTerms.keys

    // ── 구축 ─────────────────────────────────────────────────────────────────────────────────
    val concepts: Map<String, Concept>
    val moveGroups: Map<String, MoveGroup>
    val injuryProfiles: Map<Joint, InjuryProfile>

    /** 키 → 용어들 (한 키가 여러 역할을 가질 수 있다) */
    val termIndex: Map<String, List<Term>>

    /** 가장 긴 용어 길이 (DP 윈도우) */
    val maxTermLength: Int

    val conceptById: Map<String, Concept>

    init {
        val index = HashMap<String, MutableList<Term>>()
        fun add(k: String, t: Term) {
            if (k.isEmpty()) return
            index.getOrPut(k) { mutableListOf() }.add(t)
        }

        // 1) 조사·군더더기
        for (k in splitRules(PURE_PARTICLES)) add(key(k), Term(Role.NOISE, particle = true))
        for (k in splitRules(NOISE)) add(key(k), Term(Role.NOISE))
        for (k in splitRules(UNITS)) add(key(k), Term(Role.UNIT))
        // "運動前の"·"운동 전" 의 한 글자 — 단어 경계에서만 군더더기로 본다
        for (k in listOf("前", "前に", "前の", "전", "전에", "후", "後", "後に", "後の", "후에")) add(key(k), Term(Role.NOISE, isolated = isIsolated(key(k))))
        for (k in CONJUNCTIONS.split("|")) add(key(k), Term(Role.CONJ, particle = true))

        // 2) 부정/단서/수준/스트레칭/소음
        for (k in splitRules(NEG_AFTER)) add(key(k.removePrefix("-")), Term(Role.NEG, targetBefore = true, particle = isKana(k)))
        for (k in NEG_BEFORE.split("|")) add(key(k), Term(Role.NEG, targetBefore = false))
        for (k in splitRules(INSTEAD_AFTER)) add(key(k), Term(Role.NEG, targetBefore = true, particle = isKana(k), instead = true))
        for (k in splitRules(INSTEAD_BEFORE)) add(key(k), Term(Role.NEG, targetBefore = false, instead = true))
        for (k in splitRules(CUES)) add(key(k), Term(Role.CUE))
        for (k in splitRules(QUIET)) add(key(k), Term(Role.QUIET))
        for (k in BEGINNER.split("|")) add(key(k), Term(Role.LEVEL, level = -1))
        for (k in ADVANCED.split("|")) add(key(k), Term(Role.LEVEL, level = 1))
        for (k in splitRules(CANT_DO)) add(key(k), Term(Role.LEVEL, level = -1, regress = true))
        for (k in STRETCH.split("|")) add(key(k), Term(Role.STRETCH))

        for (k in listOf("産後", "産後ケア", "산후", "산후 운동", "postpartum", "post partum", "postnatal", "after pregnancy", "임산부", "pregnancy", "prenatal", "妊娠", "妊婦", "diastasis recti", "diastasis")) add(key(k), Term(Role.QUIET))
        for (line in MODIFIER_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val kind = Mod.valueOf(line.substringBefore(":").trim())
            for (t in line.substringAfter(":").split("|")) {
                val k = key(t)
                add(k, Term(Role.MODIFIER, mod = kind, isolated = isIsolated(k)))
            }
        }

        // 3) 개념
        val conceptMap = LinkedHashMap<String, Concept>()
        for (line in CONCEPT_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val (left, right) = line.split("=>", limit = 2).map { it.trim() }
            val fields = right.split(";").map { it.trim() }
            val id = fields[0]
            val kv = fields.drop(1).associate { f -> f.substringBefore("=").trim() to f.substringAfter("=", "").trim() }
            val triggers = left.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val existing = conceptMap[id]
            val concept = if (kv.isEmpty() && existing != null) existing
            else if (kv.isEmpty() && id == "fatloss") {
                // 살 빼기·다이어트: 유산소와 같은 신호를 쓰되, 부위가 함께 있으면 부위가 이긴다
                val c = conceptMap.getValue("cardio")
                Concept("fatloss", c.muscles, c.categories, c.sig, c.sig2, c.patterns, c.avoid, c.strict, c.stretch, c.cardio, triggers.mapNotNull { sigKey(it) })
                    .also { conceptMap["fatloss"] = it }
            } else {
                val flags = kv["flags"].orEmpty().split(",").map { it.trim() }.toSet()
                Concept(
                    id = id,
                    muscles = kv["muscles"].orEmpty().split(",").mapNotNull { m -> runCatching { MuscleGroup.valueOf(m.trim()) }.getOrNull() }.toSet(),
                    categories = kv["cats"].orEmpty().split(",").mapNotNull { c -> runCatching { ExerciseCategory.valueOf(c.trim()) }.getOrNull() }.toSet(),
                    sig = sigList(kv["sig"]),
                    sig2 = sigList(kv["sig2"]),
                    patterns = kv["pat"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    avoid = sigList(kv["avoid"]),
                    strict = "strict" in flags,
                    stretch = "stretch" in flags,
                    cardio = "cardio" in flags,
                    nameWords = triggers.mapNotNull { sigKey(it) }
                ).also { conceptMap[id] = it }
            }
            for (t in triggers) {
                val k = key(t)
                add(k, Term(Role.CONCEPT, concept = concept, isolated = isIsolated(k)))
            }
        }

        // 4) 기존 부위 사전(근육) — 개념으로 감싼다. 한 줄의 트리거는 서로의 이름 신호가 된다.
        val muscleConcepts = HashMap<ExerciseSearchLexicon.MuscleTerm, Concept>()
        for ((k, mt) in ExerciseSearchLexicon.muscleTerms) {
            val c = muscleConcepts.getOrPut(mt) {
                Concept(
                    id = "muscle:" + k, muscles = mt.muscles, categories = mt.categories,
                    sig = mt.muscles.flatMap { ExerciseSearchLexicon.signatureTerms[it].orEmpty() }.distinct().map { SigKey(it, false) },
                    sig2 = emptyList(), patterns = emptySet(), avoid = emptyList(),
                    strict = false, stretch = false, cardio = mt.categories == setOf(ExerciseCategory.CARDIO),
                    nameWords = ExerciseSearchLexicon.muscleTerms.filter { it.value === mt }.keys.map { SigKey(it, false) }
                )
            }
            add(k, Term(Role.CONCEPT, concept = c, isolated = isIsolated(k)))
        }
        conceptById = conceptMap

        // 5) 장비·홈
        for ((k, eq) in ExerciseSearchLexicon.equipmentTerms) add(k, Term(Role.EQUIP, equipment = eq))
        for (k in ExerciseSearchLexicon.homeTerms) add(k, Term(Role.HOME))
        for (k in ExerciseSearchLexicon.fillerTerms) add(k, Term(Role.NOISE, particle = k.length <= 2 && isKana(k)))

        // 6) 관절
        for (line in JOINT_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val joint = Joint.valueOf(line.substringBefore(":").trim())
            for (t in line.substringAfter(":").split("|")) {
                val k = key(t)
                add(k, Term(Role.JOINT, joint = joint, isolated = isIsolated(k), weakJoint = k in WEAK_JOINT_KEYS))
            }
        }

        for (line in CONDITION_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val joint = Joint.valueOf(line.substringBefore(":").trim())
            for (t in line.substringAfter(":").split("|")) {
                val k = key(t)
                add(k, Term(Role.JOINT, joint = joint))
                add(k, Term(Role.CUE, joint = joint))
            }
        }

        // 7) 이동 묶음
        val moves = LinkedHashMap<String, MoveGroup>()
        for (line in MOVE_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val id = line.substringBefore(":").trim()
            val g = MoveGroup(id, line.substringAfter(":").split("|").mapNotNull { sigKey(it) })
            moves[id] = g
            for (sk in g.keys) add(sk.key, Term(Role.NAME, move = g))
        }
        moveGroups = moves

        // 8) 부상 프로필
        val profiles = LinkedHashMap<Joint, InjuryProfile>()
        for (line in INJURY_RULES.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split(";").map { it.trim() }
            val joint = Joint.valueOf(parts[0])
            val kv = parts.drop(1).associate { it.substringBefore("=").trim() to it.substringAfter("=", "").trim() }
            profiles[joint] = InjuryProfile(joint, sigList(kv["hard"]), sigList(kv["soft"]), sigList(kv["prefer"]))
        }
        injuryProfiles = profiles

        // 9) 한자 표기의 가나 읽기 변형 — "けんすい"·"ふっきん" 도 "懸垂"·"腹筋" 과 같은 용어다. 짧은 읽기(あし·かた)는 단어 경계에서만 쓴다.
        for ((k, terms) in index.entries.toList()) {
            val readable = terms.filter { t -> t.role != Role.CONJ && t.role != Role.UNIT && !t.particle }
            if (readable.isEmpty()) continue
            for (v in KanjiReadings.variants(k)) {
                if (v.isEmpty()) continue
                val short = v.length <= 2
                val existing = index[v].orEmpty()
                // 이미 같은 역할·같은 대상으로 등록된 읽기("てくび" 를 개념 표에 가나로 직접 적은 경우)는 건너뛴다
                val fresh = readable.filter { t -> existing.none { e -> e.role == t.role && e.concept === t.concept && e.joint == t.joint && e.move === t.move && e.equipment == t.equipment } }
                if (fresh.isEmpty()) continue
                index.getOrPut(v) { mutableListOf() }.addAll(fresh.map { t ->
                    when {
                        // 가나로 풀어 쓴 부정 표지("いがい", "ぬき")는 한자 표기와 달리 조사처럼 앞말에 붙어야 한다
                        t.role == Role.NEG -> Term(Role.NEG, targetBefore = t.targetBefore, particle = isKana(v), instead = t.instead)
                        short && !t.isolated -> t.asIsolated()
                        else -> t
                    }
                })
            }
        }

        concepts = conceptMap
        termIndex = index
        maxTermLength = index.keys.maxOf { it.length }.coerceAtMost(40)
    }

    /** 줄바꿈과 | 로 나뉜 항목들 */
    private fun splitRules(raw: String): List<String> =
        raw.lines().flatMap { it.split("|") }.map { it.trim() }.filter { it.isNotEmpty() }

    /** 히라가나/한글로만 이루어진 표기인가 (가타카나 위 글자 방지 게이트 대상) */
    private fun isKana(raw: String): Boolean = raw.isNotEmpty() && raw.all { it.code in 0x3041..0x309F || it.code in 0xAC00..0xD7A3 }

    /** CJK 1글자 내용어는 단어 경계에서만 쓴다 */
    private fun isIsolated(k: String): Boolean = k.length == 1 && k[0].code >= 0x250

    /** 부정 대상 묶음 조회 (키 → 묶음) */
    fun moveGroupOf(k: String): MoveGroup? =
        termIndex[k]?.firstNotNullOfOrNull { it.move }
}

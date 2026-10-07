package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.SearchText

/**
 * 설명문 검색의 어휘 — 코드가 아니라 표(데이터)다.
 *
 * 설명문(instructions·tips)은 "무릎을 굽혀 …", "うつ伏せになり …", "raise both arms out to the sides" 처럼 동작을 풀어 쓴 글이다.
 * 사용자도 이름을 모를 때 같은 식으로 묻는다("うつぶせで膝を曲げるマシン", "무릎 높이 올리며 제자리 뛰기").
 * 글자 n-gram 만으로는 같은 뜻의 다른 표기(うつ伏せ/うつぶせ, 굽혀/구부려, bend/flex)가 갈라지므로,
 * 자세·관절·동작 어휘는 개념 토큰(@prone, @bend …)으로 접어 질의와 설명문이 같은 토큰으로 만나게 한다.
 * 개념 토큰은 언어와 무관하다(일본어 질의의 @bend 는 영어·한국어 설명문의 bend·굽혀에도 걸린다).
 *
 * 표기는 SearchText.fold(NFKC·소문자·가타카나→히라가나)된 문자열에서 비교되므로, 가타카나 표기는 히라가나로 적든 가타카나로 적든 같다.
 * 라틴 표기는 단어 단위로(원형·복수형 s 제거형 모두) 비교하고, CJK 표기는 부분 문자열로 비교한다.
 */
internal object DescriptionLexicon {

    /** 표기 변이를 하나로 접는 치환(접힌 문자열에 적용) — n-gram 이 갈라지지 않게 한다 */
    private val SURFACE_CANON: List<Pair<String, String>> = listOf(
        "うつ伏せ|俯せ|うつ伏|腹ばい|腹這い|腹這|伏臥" to "うつぶせ",
        "あおむけ|仰臥|仰向き" to "仰向け",
        "鉄棒|てつぼう" to "バー",
        "側臥" to "横向き",
        "よつんばい|四つんばい|四つ這い|四つん這い" to "四つん這い",
        "구부리|구부려|구부린|구부러|구부" to "굽히",
        "굽혀|굽힌|굽혔|굽히" to "굽히",
        "엎드려|엎드린|엎드리|엎드" to "엎드려",
        "누워서|누워|누운|눕" to "누워",
        "올려|올린|올리" to "올리",
        "내려|내린|내리" to "내리",
        "당겨|당긴|당기" to "당기",
        "돌려|돌린|돌리" to "돌리",
        "밀어|밀며|밀기" to "밀기",
        "下ろし|降ろし|下ろす|降ろす|下げ" to "下ろし",
        "持ち上げ|引き上げ|挙げ|あげ" to "上げ",
        "手足" to "腕と脚",
        "팔다리" to "팔과 다리"
    ).flatMap { (vs, canon) -> vs.split("|").map { SearchText.fold(it) to SearchText.fold(canon) } }
        .sortedByDescending { it.first.length }

    /** 개념 토큰 표: id → 표기들(접힌 형태로 비교). 라틴 표기는 단어 단위, 나머지는 부분 문자열. */
    private val CONCEPT_ROWS = listOf(
        // 자세
        "prone" to "うつぶせ|엎드려|face down|facedown|prone|on your stomach|on your belly|on your front|face-down",
        "supine" to "仰向け|背を床|背中を床|천장을 보고|천장을 향|supine|face up|faceup|on your back|lie back|lying back|face-up",
        "sidelying" to "横向き|横になり|横になっ|옆으로 누워|옆으로 눕|측와|on your side|side lying|sidelying|side-lying|lying on your side|lie on your side",
        "lying" to "寝て|寝た|寝転|横たわ|누워|눕|lie|lying|laying|recline|reclined",
        "allfours" to "四つん這い|네발|네 발|사족|all fours|hands and knees|quadruped|tabletop|table top",
        "standing" to "立って|立ち|立っ|立位|立つ|서서|선 자세|일어서|stand|standing|upright|stand up",
        "sitting" to "座っ|座り|座る|腰かけ|腰掛け|着座|앉아|앉은|앉|sit|sitting|seated|sit down",
        "kneeling" to "膝立ち|膝をつ|膝をつい|무릎을 꿇|무릎 꿇|kneel|kneeling|kneels",
        "hanging" to "ぶら下が|ぶらさが|吊り|매달|hang|hanging|dangle|dangling|suspended",
        "wall" to "壁|벽|wall",
        "chair" to "椅子|いす|이스|의자|chair|stool",
        "floor" to "床|바닥|floor|mat|ground|まっと|매트",
        "bar" to "鉄棒|バー|ばー|철봉|chinning bar|pull up bar|pull-up bar|bar",
        "bench" to "べんち|벤치|bench",
        "step" to "ステップ|すてっぷ|스텝|step|box|ボックス|ぼっくす|박스|platform|stair|stairs|계단|階段",
        // 부위
        "knee" to "膝|무릎|knee|knees",
        "hip" to "股関節|고관절|골반|hip|hips|pelvis|骨盤",
        "elbow" to "肘|팔꿈치|elbow|elbows",
        "shoulder" to "肩|어깨|shoulder|shoulders",
        "arm" to "腕|팔|arm|arms",
        "leg" to "脚|足|다리|leg|legs",
        "hand" to "手|손|hand|hands",
        "foot" to "足|발|foot|feet",
        "head" to "頭|머리|head",
        "neck" to "首|목|neck",
        "back" to "背中|背筋|등|back",
        "chest" to "胸|가슴|chest",
        "abs" to "腹筋|お腹|腹|복근|배|abs|abdominal|belly|stomach|core",
        "glute" to "お尻|おしり|尻|엉덩이|둔근|glute|glutes|buttock|buttocks|butt|hips",
        "heel" to "かかと|踵|발뒤꿈치|뒤꿈치|발꿈치|heel|heels",
        "toe" to "つま先|爪先|발끝|발가락|toe|toes|tiptoe|tiptoes",
        "calf" to "ふくらはぎ|종아리|calf|calves",
        "thigh" to "太もも|もも|허벅지|thigh|thighs",
        "wrist" to "手首|손목|wrist|wrists",
        "palm" to "手のひら|手の平|손바닥|palm|palms",
        "spine" to "背骨|脊椎|척추|spine",
        "waist" to "腰|허리|waist|lower back",
        // 동작
        "bend" to "曲げ|屈曲|折り曲げ|折りたた|たたん|畳|굽히|접어|접고|접는|굴곡|bend|bends|bending|bent|flex|flexion|fold|folded|tuck|tucked",
        "extend" to "反らす|反らし|反ら|反り|伸ばし|伸ばす|伸ば|伸展|のばし|伸び|젖히|젖혀|뒤로 젖|arch|arching|arched|펴고|펴서|펴며|펴|폄|펼|뻗|신전|extend|extended|extending|extension|straighten|straightened|straight|reach|reaching",
        "raise" to "上げ|持上げ|上昇|들어 올|들어올|들고|들어서|올리|거상|raise|raises|raising|lift|lifts|lifting|elevate|hoist|bring up|pick up",
        "lower" to "下ろし|下降|내리|낮추|낮춰|하강|lower|lowers|lowering|descend|descending|drop|dropping",
        "pull" to "引く|引い|引っ張|引きつけ|引き寄せ|引き上げ|ひく|당기|끌어|pull|pulls|pulling|draw|drawing|retract",
        "push" to "押し|押す|押さ|밀기|밀며|누르|눌러|push|pushes|pushing|press|pressing|drive|driving",
        "rotate" to "回し|回す|回転|回旋|ひねり|ひねる|ひねっ|捻|ねじ|돌리|회전|회선|비틀|rotate|rotates|rotating|rotation|twist|twisting|turn|turning|circle|circles|circular|spin",
        "jump" to "ジャンプ|じゃんぷ|跳ぶ|跳び|跳ね|飛び|점프|뛰|도약|jump|jumps|jumping|hop|hopping|leap|bound",
        "walk" to "歩|걷|걸어|walk|walking|walks|march|marching|stride",
        "run" to "走|달리|조깅|run|running|jog|jogging|sprint|sprinting",
        "squatdown" to "しゃがみ|しゃがん|しゃがむ|腰を落と|沈み|쪼그|웅크|squat|crouch|sink",
        "hold" to "保持|キープ|静止|止め|維持|ホールド|유지|정지|멈추|멈춰|버티|hold|holds|holding|pause|isometric|maintain",
        "swing" to "振り|振る|振っ|スイング|すいんぐ|흔들|휘두르|스윙|swing|swings|swinging|sway",
        "squeeze" to "収縮|力を入れ|力を込め|수축|쥐어짜|조이|힘을 주|squeeze|squeezing|contract|contracting|tighten|engage|engaging",
        "kick" to "蹴|きっく|차올|차기|발로 차|킥|kick|kicks|kicking",
        "alternate" to "交互|かわるがわる|번갈|교대|alternate|alternating|alternately|switch|switching|each side|other side|opposite|반대",
        "sideways" to "横に|横へ|横から|横方向|側方|옆으로|옆에서|측면|양옆|sideways|to the side|to the sides|out to the side|out to the sides|laterally|lateral",
        "forward" to "前に|前へ|前方|앞으로|전방|forward|forwards|in front|to the front|front",
        "backward" to "後ろに|後ろへ|後方|뒤로|후방|backward|backwards|behind|rear",
        "overhead" to "頭上|頭の上|오버헤드|머리 위|overhead|above your head|over your head|above the head",
        "toward" to "に向かって|に向けて|쪽으로|을 향해|를 향해|toward|towards|to your chest",
        "slowly" to "ゆっくり|천천히|slowly|slow|controlled|control|コントロール",
        "fast" to "素早く|すばやく|速く|速い|빠르게|빠른|quickly|fast|rapid|explosive|explosively|爆発",
        "breath" to "呼吸|息を|숨|호흡|breath|breathe|breathing|inhale|exhale",
        "apart" to "開い|開き|開く|벌리|벌려|벌린|apart|spread|wide|open",
        "together" to "閉じ|閉じる|そろえ|揃え|붙이|붙여|모으|together|close|closed|join"
    )

    private class Pattern(val text: String, val concept: String, val latin: Boolean)

    private val cjkByFirst: Map<Char, List<Pattern>>
    private val latinWord: Map<String, MutableList<String>>      // 한 단어 표기 → 개념들
    private val latinPhrase: Map<String, MutableList<Pair<List<String>, String>>>  // 첫 단어 → (단어열, 개념)

    init {
        val cjk = HashMap<Char, MutableList<Pattern>>()
        val words = HashMap<String, MutableList<String>>()
        val phrases = HashMap<String, MutableList<Pair<List<String>, String>>>()
        for ((id, rows) in CONCEPT_ROWS) {
            for (raw in rows.split("|")) {
                val f = SearchText.fold(raw).trim()
                if (f.isEmpty()) continue
                val latin = f[0].code < 0x250
                if (latin) {
                    val ws = f.split(' ', '-').filter { it.isNotEmpty() }
                    if (ws.size == 1) words.getOrPut(ws[0]) { mutableListOf() }.add(id)
                    else phrases.getOrPut(ws[0]) { mutableListOf() }.add(ws to id)
                } else {
                    cjk.getOrPut(f[0]) { mutableListOf() }.add(Pattern(f, id, false))
                }
            }
        }
        cjkByFirst = cjk
        latinWord = words
        latinPhrase = phrases
    }

    /** 표기 변이를 접은 문자열 */
    fun canon(folded: String): String {
        var s = folded
        for ((from, to) in SURFACE_CANON) if (s.contains(from)) s = s.replace(from, to)
        return s
    }

    /** 접힌(fold + canon) 문자열에서 개념 토큰을 모은다(중복 제거) */
    fun conceptsIn(text: String, out: MutableSet<String>) {
        // CJK: 글자마다 그 글자로 시작하는 표기를 비교
        for (i in text.indices) {
            val c = text[i]
            if (c.code < 0x250) continue
            val ps = cjkByFirst[c] ?: continue
            for (p in ps) if (text.startsWith(p.text, i)) out.add(p.concept)
        }
        // 라틴: 단어 열
        val words = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) } }
        for (c in text) { if (c.code < 0x250 && (c.isLetter() || c == '\'')) sb.append(c) else flush() }
        flush()
        for ((i, w) in words.withIndex()) {
            latinWord[w]?.let { out.addAll(it) }
            val st = SearchText.stem(w)
            if (st != w) latinWord[st]?.let { out.addAll(it) }
            latinPhrase[w]?.let { list ->
                for ((ws, id) in list) {
                    if (i + ws.size > words.size) continue
                    var ok = true
                    for (k in 1 until ws.size) if (words[i + k] != ws[k] && SearchText.stem(words[i + k]) != ws[k]) { ok = false; break }
                    if (ok) out.add(id)
                }
            }
        }
    }

    /** 질의에서 동작을 풀어 쓴 표현이 있는가 — 설명문 검색을 적극적으로 쓸지 정하는 단서(자세·동작 개념 토큰이 있으면 true) */
    val MOTION_CONCEPTS: Set<String> = setOf(
        "prone", "supine", "sidelying", "lying", "allfours", "kneeling", "hanging", "bend", "extend", "raise", "lower", "pull", "push",
        "rotate", "jump", "squatdown", "hold", "swing", "squeeze", "kick", "sideways", "overhead"
    )
}

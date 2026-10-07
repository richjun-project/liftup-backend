package com.richjun.liftupai.domain.workout.search

/**
 * 헬스장 용어의 한자 ↔ 가나 읽기 표 (표가 데이터다 — 새 단어는 한 줄 추가).
 *
 * 일본 사용자는 같은 말을 한자("懸垂")로도 가나("けんすい", "ケンスイ")로도 쓴다. 사전에 한자 표기만 있으면 가나 질의가 통하지 않으므로,
 * 사전의 모든 키(동의어 트리거·질의 이해 용어)에 대해 읽기를 대입한 가나 변형을 함께 등록한다. 읽기는 히라가나(strict 키 기준 — 가타카나는 접힌다).
 *
 * 변형은 "한자가 전부 표에 있어 가나만 남을 때"만 만든다 — 일부만 바꾼 키("あし首")를 사전에 흘리지 않는다.
 * 한 한자에 읽기가 여럿이면(脚 あし/きゃく) 조합마다 만든다(상한 있음).
 */
internal object KanjiReadings {

    /** 표기 | 읽기1 | 읽기2 … (히라가나). 긴 표기가 먼저 대응한다. */
    private val TABLE = """
        懸垂|けんすい
        腹筋|ふっきん
        背筋|はいきん
        腕立て伏せ|うでたてふせ
        腕立て|うでたて
        自転車|じてんしゃ
        自重|じじゅう
        縄跳び|なわとび
        鉄棒|てつぼう
        筋トレ|きんとれ
        筋肉|きんにく
        筋力|きんりょく
        体幹|たいかん
        有酸素運動|ゆうさんそうんどう
        有酸素|ゆうさんそ
        運動|うんどう
        準備運動|じゅんびうんどう
        準備体操|じゅんびたいそう
        整理運動|せいりうんどう
        柔軟体操|じゅうなんたいそう
        柔軟|じゅうなん
        体操|たいそう
        脂肪燃焼|しぼうねんしょう
        脂肪|しぼう
        燃焼|ねんしょう
        痩せる|やせる
        痩せ|やせ
        逆手|さかて|ぎゃくて
        順手|じゅんて
        片手|かたて
        片足|かたあし
        片腕|かたうで
        両手|りょうて
        両足|りょうあし
        広背筋|こうはいきん
        僧帽筋|そうぼうきん
        三角筋|さんかくきん
        大胸筋|だいきょうきん
        胸筋|きょうきん
        上腕二頭筋|じょうわんにとうきん
        上腕三頭筋|じょうわんさんとうきん
        二頭筋|にとうきん
        三頭筋|さんとうきん
        大臀筋|だいでんきん
        臀筋|でんきん
        大腿四頭筋|だいたいしとうきん
        大腿二頭筋|だいたいにとうきん
        腹直筋|ふくちょくきん
        腹斜筋|ふくしゃきん
        腹横筋|ふくおうきん
        脊柱起立筋|せきちゅうきりつきん
        腓腹筋|ひふくきん
        前腕|ぜんわん
        上腕|じょうわん
        股関節|こかんせつ
        肩甲骨|けんこうこつ
        肩関節|かたかんせつ
        足首|あしくび
        手首|てくび
        太もも|ふともも
        太腿|ふともも
        内もも|うちもも
        裏もも|うらもも
        前もも|まえもも
        二の腕|にのうで
        お尻|おしり
        お腹|おなか
        脇腹|わきばら
        背中|せなか
        上半身|じょうはんしん
        下半身|かはんしん
        全身|ぜんしん
        階段|かいだん
        漕ぎ|こぎ
        漕ぐ|こぐ
        前屈|ぜんくつ
        開脚|かいきゃく
        猫背|ねこぜ
        肩こり|かたこり
        腰痛|ようつう
        初心者|しょしんしゃ
        初級者|しょきゅうしゃ
        上級者|じょうきゅうしゃ
        自宅|じたく
        器具|きぐ
        胸|むね
        肩|かた
        腰|こし
        腕|うで
        脚|あし|きゃく
        足|あし
        尻|しり
        腹|はら
        首|くび
        膝|ひざ
        肘|ひじ
        下がり|さがり
        ぶら下がり|ぶらさがり
        鍛える|きたえる
        伸ばす|のばす
        引き締め|ひきしめ
        絞る|しぼる
        走る|はしる
        歩く|あるく
    """.trimIndent()

    private class Entry(val written: String, val readings: List<String>)

    private val entries: List<Entry> = TABLE.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .map { line -> line.split("|").let { Entry(it.first(), it.drop(1)) } }
        .sortedByDescending { it.written.length }

    private fun isIdeograph(c: Char) = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /** 키(strict 형태)에 들어 있는 한자어를 읽기로 바꾼 가나 변형들. 한자가 하나도 없거나 표에 없는 한자가 남으면 빈 목록. */
    fun variants(key: String): List<String> {
        if (key.none { isIdeograph(it) }) return emptyList()
        var partial = listOf(StringBuilder())
        var i = 0
        while (i < key.length) {
            val c = key[i]
            if (!isIdeograph(c) && !(c.code in 0x3041..0x309F)) {
                // 가나·라틴·숫자는 그대로
                partial.forEach { it.append(c) }
                i++
                continue
            }
            val hit = entries.firstOrNull { key.startsWith(it.written, i) }
            if (hit == null) {
                if (isIdeograph(c)) return emptyList()
                partial.forEach { it.append(c) }
                i++
                continue
            }
            val next = ArrayList<StringBuilder>()
            for (p in partial) for (r in hit.readings) {
                if (next.size >= MAX_COMBOS) break
                next.add(StringBuilder(p).append(r))
            }
            partial = next
            i += hit.written.length
        }
        return partial.map { it.toString() }.filter { it != key }.distinct()
    }

    private const val MAX_COMBOS = 4
}

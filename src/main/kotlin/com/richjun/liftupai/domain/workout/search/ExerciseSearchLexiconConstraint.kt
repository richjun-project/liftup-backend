package com.richjun.liftupai.domain.workout.search

/**
 * "장비가 하나도 없다" 계열의 표현(조합으로 생성) — 맨몸 의도다. 부정 표지가 하나하나 장비 낱말을 건드리는 것이 아니라
 * "장비류 명사 + (조사·수량어) + 없다" 와 "아무것도 + 없다" 의 활용형 전체를 한 번에 맨몸 장비로 읽는다.
 * 장비류 명사만 따로 말하면("장비 말고", "no gear") 바벨·덤벨·머신·케이블·케틀벨·밴드 전부를 가리키는 일반 장비 용어([GENERIC_EQUIPMENT_TERMS])다.
 */
internal object ExerciseSearchLexiconConstraint {

    private val koNouns = listOf("장비", "기구", "도구", "운동기구", "운동 기구", "운동장비", "운동 장비", "헬스기구", "헬스 기구", "웨이트 기구", "용구", "장비류", "기구류", "도구류")
    private val koMids = listOf("", "가", "이", "도", "는", "은", "하나", "가 하나", "가 하나도", "하나도", "전혀", "가 전혀", "도 전혀", "아예", "가 아예", "일절", "가 일절", "가 한 개도", "한 개도", "라고는", "라곤")
    private val koTails = listOf("없어", "없음", "없고", "없는데", "없다", "없네", "없어요", "없습니다", "없어서", "없는", "없이", "필요 없는", "필요 없이", "필요 없어", "필요없는", "필요없이", "안 쓰는", "안 쓰고", "안 쓰는 상태", "쓰지 않는", "쓰지 않고", "사용 안 하는", "사용하지 않는", "사용하지 않고", "안 쓰고도", "없는 상태", "없는 곳", "없는 환경", "없을 때", "없는 집", "없는 방", "없는 헬스장", "없으니까", "없거든", "없는데요", "없네요", "없긴 한데", "없어도")
    private val koPronouns = listOf("아무것도", "아무 것도", "아무거도", "아무 장비도", "아무 기구도", "아무 도구도", "아무런 장비도", "아무런 기구도", "아무런 도구도", "하나도", "전혀")

    private val jaNouns = listOf("器具", "道具", "用具", "運動器具", "トレーニング器具", "ジム器具", "ギア", "機材")
    private val jaMids = listOf("", "が", "は", "も", "が一切", "は一切", "が全く", "は全く", "が何も", "は何も", "が一つも", "が全然", "は全然", "がまったく", "がゼロ", "はゼロ")
    private val jaTails = listOf("ない", "なくて", "ないので", "ないです", "ありません", "なし", "なしで", "なしの", "なしでも", "なしに", "なしな", "無しの", "無しでも", "不要", "不要で", "いらない", "いらず", "いりません", "を使わない", "を使わず", "を使わずに", "を使わないで", "を使わなくても", "は使わない", "は使わず", "は使わずに", "使わない", "使わず", "使わずに", "ないけど", "ないんだけど", "ない状態", "ない環境", "ない部屋", "ないところ", "ない場所", "ないの", "ないんです", "なく", "ありませんが", "ないから", "ない中で", "無い", "無くて", "無し", "ゼロ", "ゼロで", "ゼロです", "皆無", "皆無で")
    private val jaPronouns = listOf("何も", "なにも", "一切", "全く", "まったく", "一つも", "ひとつも", "全然", "ぜんぜん")

    private val enPhrases = listOf(
        "nothing at all", "nothing whatsoever", "got nothing at all", "have nothing at all", "i have nothing at all", "i got nothing at all", "got nothing at home", "i have nothing at home", "there is nothing here", "theres nothing here", "there's nothing here", "there is nothing at home", "there's nothing at home", "theres nothing at home", "nothing here", "nothing at home", "nothing with me",
        "no equipment at all", "no equipment whatsoever", "no gear whatsoever", "no gear at all", "no weights at all", "no equipment", "no equipments", "zero equipment", "zero gear", "empty handed", "empty-handed",
        "nothing but my body", "nothing but bodyweight", "nothing but body weight", "only my body", "just my body", "just my bodyweight", "just bodyweight", "only bodyweight", "only body weight", "just body weight",
        "my body only", "only my body weight", "just my body weight", "my body weight only", "only my own body weight", "only my own bodyweight", "just my own body", "just my own bodyweight", "my own body weight", "my own bodyweight", "bodyweight only", "body weight only", "only myself", "just myself", "with just my body", "using only my body", "using only my bodyweight", "equipment less", "no tools", "no tools at all", "toolless", "tool free",
        // 헬스장이 꽉 차 쓸 장비가 하나도 없다("no equipment free" 의 free 는 비어 있다는 뜻이다 — "equipment free"(맨몸)와 "no" 로 쪼개면 맨몸을 빼 버린다)
        "no equipment free", "no equipment available", "no equipment open", "no gear free", "no gear available", "no weights free", "no weights available",
        "nothing free", "nothing available", "nothing open", "nothing is free", "nothing is available", "nothing's free", "nothings free", "none of it is free",
        "all equipment taken", "all the equipment is taken", "all equipment is taken", "everything is taken", "everything taken", "everything's taken", "everythings taken", "every machine is taken",
        "without tools", "no apparatus", "with nothing", "using nothing", "without anything", "without any equipment", "without any gear", "without any tools", "no machines or weights", "no weights or machines",
        "no weights nor machines", "no weights or equipment", "no equipment or weights", "no equipment or gym", "no gym or equipment", "no gym equipment", "no gym gear", "nothing to lift", "nothing to work with", "nothing to use"
    )
    private val jaExtra = listOf("手ぶらで", "手ぶら", "素手で", "身一つで", "身ひとつで", "自分の体だけ", "自分の体だけで", "体一つで", "体ひとつで", "自分の体重だけ", "自分の体重だけで", "体重だけ", "体重のみ")
    private val koExtra = listOf("맨몸밖에 없어", "맨몸밖에 없는데", "맨몸밖에 없음", "맨몸뿐", "맨몸뿐이야", "몸뚱이 하나", "몸 하나", "몸 하나뿐", "빈손으로", "내 몸뚱이만", "내 몸만", "몸만 있어")

    /** 맨몸 의도로 읽을 표기들(생성) */
    val NO_EQUIPMENT_TERMS: List<String> by lazy {
        val out = LinkedHashSet<String>()
        for (n in koNouns) for (m in koMids) for (t in koTails) out.add(listOf(n, m, t).filter { it.isNotEmpty() }.joinToString(" "))
        for (p in koPronouns) for (t in koTails) out.add("$p $t")
        for (n in jaNouns) for (m in jaMids) for (t in jaTails) out.add(n + m + t)
        for (p in jaPronouns) for (t in jaTails) out.add(p + t)
        out.addAll(enPhrases); out.addAll(jaExtra); out.addAll(koExtra)
        out.toList()
    }

    /** 장비 일반을 가리키는 말 — 부정하면 맨몸 말고 모든 장비를 뺀다. 긍정으로는 아무것도 요청하지 않는다. */
    val GENERIC_EQUIPMENT_TERMS: List<String> = listOf(
        "장비", "도구", "운동장비", "운동 장비", "용구", "장비류", "도구류", "道具", "器具", "用具", "ギア", "機材", "トレーニング用具", "equipment", "equipments", "gear", "apparatus", "tools", "any equipment", "any gear", "all equipment", "all gear"
    )
}

package com.richjun.liftupai.domain.workout.search.engine

/**
 * 장비·운동 제약 표현 사전 (표가 데이터다) — "쓸 수 없다/쓰기 싫다/다 쓰고 있다/고장이다/금지다" 를 말하는 문형 계열.
 *
 * 한 문형이 아니라 활용형의 계열을 싣는다: 안 씀·못 씀·금지·점유(쓰는 중·누가 차지·줄)·고장·점검 의 ko/ja/en.
 * - [NEG_AFTER]: 대상이 표지 앞에 오는 말(ko/ja: "바벨 못 씀", "ダンベル禁止", "cables are off limits")
 * - [NEG_BEFORE]: 대상이 표지 뒤에 오는 말(en: "never cables", "hogging all the dumbbells", "can't use barbells")
 * - [NOISE]: 수량어("전부·全部")·긍정 단서("yes·OK")·행위자("누가·someone") — 의미를 더하지 않지만 부정 표지와 대상 사이에 끼는 말이라
 *   알려진 말이어야 대상을 건너뛸 수 있다(모르는 말이면 대상 탐색이 거기서 끊긴다)
 *
 * 한 글자 수량어("다")는 표가 아니라 분석기가 단어 단위로 건너뛴다([QueryAnalyzer] 의 skippable).
 * "못하는데·無理·can't do" 처럼 "쉬운 변형을 달라"는 뜻도 되는 말은 [QueryLexicon] 의 CANT_DO 이고, 장비·부상과 함께일 때만 분석기가 제외로 읽는다.
 */
internal object QueryLexiconConstraint {

    val NEG_AFTER = """
        사용 안 함|사용안함|사용 안 해|사용안해|사용 안 해요|사용 안 합니다|사용 안 할래|사용 못 함|사용 못함|사용 못 해|사용 불가|사용불가|사용 금지|사용금지|사용 안 돼|사용 안돼|사용 안 됨|사용안됨
        |안 함|안함|안 해|안해|안 해요|안해요|안 할래|안할래|안 할 거|안 합니다|안 씀|안씀|안 써요|안써요|안 쓸래|안쓸래|안 쓸 거|안쓸거|안 쓴다|안 잡|안 잡음|안 잡아
        |못 씀|못씀|못 써|못써|못 써요|못써요|못 쓰|못쓰|못 쓴|못쓴|못 쓸|못쓸|못 쓰는데|못쓰는데|못 쓰겠|못쓰겠|못 쓰고|못쓰고|못 쓰니까|못쓰니까|못 쓰거든|못쓰거든|못 써서|못써서
        |불가능|금지|금지야|금지예요|금지임|금지라|금지라서|금지돼서|금지되어|금지된|금지됨|금지고|금지인데|금지니까|금지인|금지인 날|금지된 날
        |쓰는 중|쓰는중|쓰는 중이라|쓰는중이라|쓰는 중이야|쓰는중이야|쓰는 중이어서|쓰는 중이네|쓰는 중이라서|쓰는 중인데|쓰는중인데|쓰는 중임|쓰는 중이에요|쓰는 중이예요
        |쓰고 있어|쓰고있어|쓰고 있어서|쓰고있어서|쓰고 있어요|쓰고있어요|쓰고 있는데|쓰고있는데|쓰고 있음|쓰고있음|쓰고 있네|쓰고있네|쓰고 있네요|쓰고 있고|쓰고있고|쓰고 있으니|쓰고있으니|쓰고 있다|쓰고있다|쓰고 있는 중|쓰고있는중|쓰고 있길래|쓰고 있거든|쓰고있거든|쓰고 있으니까
        |사용하고 있어|사용하고있어|사용하고 있어서|사용하고있어서|사용하고 있는|사용하고있는|사용하고 있음|사용하고 있네|사용하고 있고|사용하고 있는데|사용 중이라|사용중이라|사용 중이어서|사용중이어서|사용 중이네|사용중이네|사용 중인데|사용중인데|사용 중이야|사용중이야|사용 중임|사용중임|사용 중이에요|사용 중이예요|사용 중이라서|사용 중이거든
        |점유 중|점유중|점유당|점유되어|점유돼|점유되고|점유하고|독점하고|독점 중|독점중|독차지|독차지하고|차지하고 있어|차지하고있어|차지하고 있어서|차지하고있어서|차지하고 있네|차지하고 있는데|차지해서|차지했어|점령해서|점령했어|점령하고 있
        |가득 차서|가득 찼|가득 차 있|꽉 차서|꽉 차있|꽉 차 있어|꽉 찼어|꽉 찼네|다 차서|다 찼어|다 찼네|만원이라|만원이야|만석이라|만석이야|사람 많아서|사람이 많아서|사람 많은데|사람이 많은데|사람이 많네|사람이 너무 많아|붐비는데|붐비네|붐벼요|붐비고 있
        |대기 중|대기중|대기가 길|대기 길|대기 줄이|줄이 있|줄 서야|줄 서고|줄 서 있어|줄 서있어|줄서야|줄이 너무|줄이 엄청|기다려야|기다려야해|기다려야 해|기다려야 돼|기다려야돼|기다려야 하는|기다리는 중|기다리는중|웨이팅 중|웨이팅중
        |막혀서|막혀 있|막혀있|막혔어|막혔네|막혔는데|비어 있지 않|비어있지 않|안 비어 있|안 비어있|안 비네|안 비어서|안 비는데
        |고장이라|고장이야|고장이에요|고장이예요|고장이네|고장입니다|고장났어|고장났네|고장났는데|고장 났어|고장 났네|고장 났는데|고장이 났|고장 중|고장중|고장 상태|고장인데|고장이라서|고장나 있|고장 나 있|고장나있|고장난 상태|망가져서|망가졌어|망가졌네|망가져 있|망가져있|부서져서|부서졌어|부서졌네|파손|파손돼서|파손되어
        |점검중이라|점검 중이라|점검중이야|점검 중이야|점검중이어서|점검 중이어서|점검중인데|점검 중인데|점검중이네|점검 중이네|점검 중입니다|점검중입니다|점검중임|점검 중임|점검 때문에|점검으로|점검이라|점검이야|정비 중|정비중|정비 때문에|수리 때문에|수리 들어가|수리해야|공사 중|공사중|폐쇄|폐쇄돼서|폐쇄됐|폐쇄되어|닫혀|닫혀서|닫혀 있|닫혀있|닫았|닫았어|운영 안 함|운영안함|운영 안 해|운영 중단|이용 불가|이용불가|이용 금지|이용금지|이용 못 해|이용 못함
        |싫음|싫다|싫은데|싫어요|싫네|싫거든|싫어서|싫은 거|싫은거|꺼려|꺼려져요
        |빼줘|빼 줘|빼주세요|빼 주세요|빼자|빼고싶|빼고 싶|빼 놓고|빼놓고|제외해줘|제외해 줘|제외해주세요|제외해 주세요|제외하고 싶|뺀 걸로|뺀 운동
        |パス|パスで|はパス|のでパス|からパス|ならパス|でパス|もパス|パスです|パスしたい|パスしたいです|パスだ|パスね|パスかな|パスで全|ぱすで
        |패스|패스할래|패스함|패스야|패스예요|패스하고 싶어|패스할게|패스하겠습니다|패스하고|패스할 거야|패스할거야|패스 할래
        |禁止|禁止で|禁止です|禁止だ|禁止なので|禁止なんで|禁止されてる|禁止されて|禁止されている|禁止になった|使用禁止|使用不可|利用禁止|利用不可
        |NG|NGで|NGです|NGなので|ダメで|ダメです|ダメなので|ダメだ|だめで|だめです|だめなので|駄目で|駄目です|はダメ|はだめ|は駄目|不可で|不可です|不可
        |使えない|使えなくて|使えず|使えません|使えないので|使えないから|使えなくなった|使えなくなって|つかえない|つかえなくて|使用できません|使用できないので|扱えない|扱えなくて|触れない|触れなくて|触りたくない|触りたくなくて|触れたくない
        |使われてる|使われている|使われてて|使われていて|使われてます|使われています|使われっぱなし|使われっぱ|使われちゃってる|使われちゃって|使われてしまって|使われまくり|使用されてる|使用されている|使用されてて|使用されていて|占領されてる|占領されている|占領されてて|占領されて|占拠されてる|占拠されて|独占されてる|独占されている|独占されてて|独占されて|独り占め|取られてる|取られている|取られてて|取られちゃって|取られっぱなし|押さえられてる|ふさがってる|ふさがっている|ふさがってて|塞がってる|塞がっている|塞がってて|埋まっちゃってる|埋まっちゃって|埋まりっぱなし
        |並んでる|並んでいる|並んでて|並んでいて|行列|行列で|行列ができてる|行列ができて|順番待ち|順番待ちで|順番待ちしてる|待ちで|待ちが長い|待ち時間が長い|待ち時間長い|待たされる|待たないと|待たなきゃ|待たなければ
        |故障で|全部故障|全て故障|すべて故障|ぜんぶ故障|が全部故障|全部壊れて|全部壊れてる|全て壊れて|故障だ|故障なので|故障なんで|故障中で|故障中です|故障中なので|故障してる|壊れてます|壊れています|壊れちゃって|壊れちゃってる|壊れっぱなし|壊れかけ|破損してる|破損して|メンテで|メンテ中で|メンテ中です|メンテナンス中で|メンテナンス中です|メンテナンスで|点検で|点検中で|点検中です|点検中なので|点検してる|点検してて|点検している|修理中|修理中で|修理中です|修理中なので|修理してる|修理してて|修理している|修理待ち|修理に出てる|整備中|整備中で|整備中です|工事中|工事中で|工事中です|閉鎖中|閉鎖されてる|閉鎖されて|休止中|停止中|使用停止|使用中止|閉まってる|閉まっている|閉まってて|閉まっていて|閉まってます|閉まっています
        |はちょっと|ちょっと苦手|はちょっと苦手|はちょっと無理|はちょっと遠慮|はちょっと嫌|はちょっとね|はちょっとなあ|は勘弁|勘弁|勘弁して|勘弁してほしい|は勘弁して|は結構です|結構です|はけっこうです|はいらない|はいりません
        |nope|nah|meh|uh no|hard pass|hard no|no no|nono|hell no|nuh uh|nuh-uh
        |쓰지 말고|쓰지말고|사용하지 말고|사용하지말고|하지 말고|하지말고|쓰지 마|쓰지마|쓰지 마세요|쓰지마세요|쓰지 마요|쓰지 않게|쓰지 않도록|사용하지 않게|사용하지 않도록|사용하지 마|사용하지마|쓰지 않는|쓰지 않으면|쓰지 않으면서
        |노노|노우|놉|놉놉|x|×|✕|✗|❌|엑스|아니야|아님|아니에요
        |arent available|aren't available|are not available|arent accessible|aren't accessible|are not accessible|are unavailable|arent usable|aren't usable|are not usable|arent working|aren't working|are not working
        |쓰지 않을래|쓰지 않을 거야|쓰지 않을거야|쓰지 않을게|쓰지 않겠|안 쓰겠|안 쓸게|안쓸게|안 쓸 거야|안쓸거야|안 쓸래요
        |順番待ちが長い|順番待ちが長くて|順番待ちが長いので|順番待ちが長すぎる|順番待ちが多い|順番待ちが多くて|待ち時間が長くて|待ち時間が長いので|待ちが長くて|待ちが長いので|行列が長い|行列が長くて|行列が長いので|待たされそう|待ってられない
        |貸出中|貸し出し中|貸出し中|貸し出されてる|貸し出されている|借りられてる|借りられている|借りられて|レンタル中|予約済み|予約で埋まって|予約が入って|予約されてる|予約されている
        |待ちの列|待ち列|待ちの行列|待ち行列|列がやばい|列がすごい|列がすごくて|列が長すぎる|列ができてる|行列がやばい|待ちがやばい|待ち時間がやばい|待ちの列が長い|待ちの列がやばい
        |順番が回ってこない|順番が来ない|順番が回ってこなくて|順番が回ってこず|順番が全然来ない|順番が回ってこないので
        |rented out|checked out|on loan|booked up|sold out|completely sold out|all sold out
        |has a long wait|have a long wait|has a long line|have a long line|has a long queue|have a long queue|has a wait|have a wait|has a huge line|have a huge line|has a massive line|has a big line|has a crazy line|has a huge queue|has a big queue
        |사양|사양할게|사양합니다|사양이야|사양이에요|사양하겠습니다|사양할래|사양해요
        |쓸 수 없어|쓸 수 없어서|쓸 수 없는|쓸 수 없음|쓸 수 없네|쓸 수가 없어|쓸수없어|쓸 수 없는데|쓸 수 없고|쓸 수 없다|쓸 수 없는 상황|사용할 수 없어|사용할 수 없어서|사용할 수 없는|사용할 수 없음|사용할 수가 없어|이용할 수 없어|이용할 수 없는|이용할 수 없음|이용할 수 없어서
        |never again|never ever|not anymore|not any more|not any longer|no more|no longer|not ever
        |excluded|excluded please|exclude please|removed|removed please|skipped|skip please|dropped|nixed|off the list|not wanted|unwanted|not needed|isnt needed|arent needed|isn't needed|aren't needed|isnt wanted|isn't wanted|arent wanted|aren't wanted
        |はいやだ|はイヤだ|がいやだ|は嫌だ|が嫌だ|はいやです|はいやなんだ|はいやだな|はいやなので|がいやです|いやだ|嫌だ|イヤだ|やだ|ヤダ|嫌なんだ|嫌なの|いやなの|いやなんだ|いやだな|嫌だな|嫌かな|嫌だから|いやだから|やだから|嫌だし|いやだし
        |嫌いで|嫌いです|嫌いだ|嫌いなので|いやで|いやです|苦手です|苦手で|苦手なので|やめたい|やめたくて|やめとく|やめとき|やめておく|やめておきたい|やめとこう|やめよう|避けたいです|避けたいので|避けたいんだ|避けたいんですが|控えたい|控えたくて|控える|控えて|控えます|遠慮したい|遠慮して|遠慮します|遠慮する|敬遠したい|敬遠して|敬遠してる
        |除きたい|除いて|除いてください|除いてほしい|除外してください|除外してほしい|外して|外してください|外してほしい|外したい|外したくて|はずして|はずしてください|はずしたい|抜いて|抜いてください|抜いてほしい|抜きたい|抜きたくて|消して|消してください|消したい
        |no way|no go|nogo|no thanks|not allowed|not permitted|not an option|not available|not usable|not accessible|banned|prohibited|forbidden|off limits|offlimits|off-limits|is out|are out|is out for me|are out for me|is a no|are a no|is a no go|are a no go|a no go|a no-go|is off limits|are off limits|is banned|are banned|is not an option|are not an option|isnt an option|arent an option|isn't an option|aren't an option|is not allowed|are not allowed|isnt allowed|arent allowed|isn't allowed|aren't allowed|is off the table|are off the table|off the table
        |is taken over|are taken over|taken over|got taken|got taken over|is monopolized|are monopolized|monopolized|is all used|are all used|is used up|are used up|used up|is spoken for|are spoken for|spoken for|is blocked|are blocked|blocked|blocked off|roped off|is not free|are not free|isnt free|arent free|isn't free|aren't free|is not open|are not open|isnt open|arent open|isn't open|aren't open|is unusable|are unusable|unusable|is gone|are gone|all gone|always taken|always busy|always in use|always occupied|always full|always packed|always crowded|always has a line|always a line|always a queue|always a wait
        |is out of service|are out of service|out of service|down for repairs|down for maintenance|is being repaired|are being repaired|being repaired|is being fixed|are being fixed|being fixed|is being serviced|being serviced|needs repair|need repair|needs repairs|need repairs|needs fixing|need fixing|needs maintenance|is under repair|are under repair|under repair|under repairs|is being cleaned|being cleaned|jammed|is jammed|are jammed|doesnt work|doesn't work|dont work|don't work|wont work|won't work|not functional|not functioning|isnt functioning|isn't functioning|is not functioning|malfunctioning|is malfunctioning|are malfunctioning|is faulty|are faulty|faulty|is defective|are defective|defective|is busted|are busted|busted|is dead|are dead|got broken|got broke|is broke|are broke
        |is overrun|are overrun|overrun|is overcrowded|are overcrowded|overcrowded|jam packed|jampacked|is crammed|are crammed|crammed|is swarmed|are swarmed|swarmed|is overbooked|overbooked|is fully booked|are fully booked|fully booked|is sold out|are sold out|sold out|full up|is full of people|are full of people|full of people|has people on it|have people on it|has someone on it|have someone on it|has someone using it|have someone using them|someone is on it|someone is using it|someone is using them|someone has it|someone has them|somebody is on it|somebody is using it|somebody is using them|people are on it|people are using it|people are using them|in use by someone|being used by someone|used by someone|in use by people|being used by people|used by people|occupied by someone|occupied by people|taken by someone|taken by people|reserved by someone|hogged by someone|hogged by people|blocked by someone
    """.trimIndent()

    /**
     * "없다/ない" 계열 — 존재 부정. "쓸 수 없다"(avail)가 아니라 그 장비·운동·부위가 없다는 평범한 부정이라 [QueryLexiconRound3] 의 "없어·없고" 와 같게 읽는다
     * (부위 대상도 빼고, 모르는 덩어리 대상도 평소대로 — 시간·장소가 없다는 말이 장비를 빼게 만들지 않는다).
     */
    val NEG_AFTER_EXISTENCE = """
        없음|없네|없다|없어요|없습니다|없네요|없거든|없거든요|없다니까|없는 상태|없는 환경|없는 곳
        |ないです|ないので|ないんで|ないんです|ないんだ|ないんだよ|ないんだよね|ありません|ありませんので|ないけど|ないんですが|ないのですが|ないんだけど|ないですが|なくなった|なくなって|なくなっちゃった|無いです|無いので
    """.trimIndent()

    /**
     * 사람이 많다·줄이 길다·대기가 있다 — 부사("너무·엄청")가 끼어도 같은 말이라 조합으로 만든다(표에 하나하나 적으면 끼어드는 부사마다 빠진다).
     */
    val CROWD_AFTER: String = run {
        val crowdSubjects = listOf("사람", "사람이", "사람들이", "인원이", "손님이", "회원이", "회원들이")
        val queueSubjects = listOf("줄", "줄이", "대기", "대기가", "웨이팅", "웨이팅이", "대기줄이", "대기 줄이")
        val adverbs = listOf("", "너무", "엄청", "진짜", "완전", "많이", "되게", "굉장히", "무지", "넘")
        val crowdTails = listOf("많아서", "많아", "많음", "많은데", "많네", "많아요", "많네요", "많다", "많고", "많으니까", "몰려서", "몰려있어", "몰려 있어서", "가득해서", "바글바글해서", "바글바글", "북적여서", "북적거려서", "북적이", "북적임")
        val queueTails = listOf("길어서", "길어", "길음", "길다", "길어요", "길고", "길어서요", "길네", "김", "많아서", "많아", "많음", "많은데", "있어서", "있어", "있음", "있는데", "있네", "생겨서", "생겼어", "생김", "서 있어서", "서있어서", "서 있음", "서있음")
        val out = LinkedHashSet<String>()
        for (sub in crowdSubjects) for (adv in adverbs) for (tail in crowdTails) out.add(listOf(sub, adv, tail).filter { it.isNotEmpty() }.joinToString(" "))
        for (sub in queueSubjects) for (adv in adverbs) for (tail in queueTails) out.add(listOf(sub, adv, tail).filter { it.isNotEmpty() }.joinToString(" "))
        val waitStems = listOf("대기해야", "대기 해야", "줄 서야", "줄서야", "기다려야", "줄 서서 기다려야", "줄 기다려야")
        val waitTails = listOf("", " 해", " 해서", " 돼", " 돼서", " 되서", " 되는데", " 하는데", " 하니까", " 해요", " 돼요", " 하고", " 함", " 하네", " 해야", " 하거든")
        for (stem in waitStems) for (tail in waitTails) out.add(stem + tail)
        out.addAll(listOf("안 남았", "안남았", "안 남음", "안남음", "남은 게 없", "남은게 없", "남은 게 하나도 없", "남아 있지 않", "남아있지 않", "동났", "동이 났", "바닥났", "품절", "다 나갔", "다 빠졌", "다 가져갔", "가져가서", "하나도 안 남아", "하나도 안 남았"))
        // 하기 싫다·하고 싶지 않다 — 동사 어간과 활용 어미의 조합
        val verbStems = listOf("쓰기", "사용하기", "하기", "잡기", "들기", "쥐기", "이용하기", "만지기", "쓰고", "사용하고", "하고", "잡고", "들고", "이용하고")
        val dislikeTails = listOf("싫어", "싫은", "싫고", "싫어서", "싫은데", "싫네", "싫다", "싫음", "싫어요", "싫으니까", "싶지 않아", "싶지 않은", "싶지 않고", "싶지 않아서", "싶지않아", "싶지 않은데", "싶지 않네요", "싶지 않다", "싶지 않음")
        for (v in verbStems) for (t in dislikeTails) if (v.endsWith("기") == t.startsWith("싫") && v.endsWith("고") == t.startsWith("싶")) out.add("$v $t")
        out.addAll(listOf("싫고", "싫지만", "싫으니", "싫으니까", "싫구", "싫긴", "싫은 날", "싫다고", "별로", "별로야", "별로임", "별로예요", "별로라", "별로라서", "별로네", "별로고", "별로인데", "별로지만",
            "은 좀 그래", "는 좀 그래", "은 좀 그렇", "는 좀 그렇", "은 좀 부담", "는 좀 부담", "은 좀 꺼려", "는 좀 꺼려"))
        // "점검한대서·고장이래·점검 중인 듯" — 전해 들은 말·추측 어미가 붙은 점유·고장 표현
        val statusStems = listOf("점검", "점검중", "점검 중", "고장", "고장난", "고장났", "수리", "수리중", "수리 중", "사용중", "사용 중", "사용되고 있", "쓰고 있", "막혔", "꽉 찼", "꽉 찬", "다 찼", "만석", "공사", "정비", "정비중", "정비 중", "폐쇄", "대여중", "대여 중", "예약돼", "예약됐", "예약 꽉", "선점당", "선점 당", "선점됐")
        val hearsayTails = listOf("이래", "이래서", "이라네", "이라던데", "이라고", "이라고 해서", "이라는데", "이라더라", "했대", "한대", "한대서", "한다고", "한다네", "한다더라", "이라서", "대", "대서", "다더라", "다던데", "다네", "다고 해서", "인 듯", "인듯", "인가 봐", "인가봐", "같아", "것 같아", "같음", "인 것 같아", "일 듯", "인가봄", "인가 봄", "해서", "해요", "함", "했어", "했음", "당함", "당했어", "당해서", "당했음", "됐어", "됐음", "돼서", "됨")
        for (st in statusStems) for (t in hearsayTails) out.add(st + t)
        out.addAll(listOf("선점당함", "선점당했", "선점당해", "뺏겼", "뺏겨서", "뺏김", "빼앗겼", "빼앗겨서", "빼앗김", "차지당했", "차지당해서", "먼저 쓰고 있", "이미 쓰고 있", "이미 누가 쓰고 있", "누가 먼저 쓰고 있", "누가 선점", "대여중", "대여 중", "대여돼", "대여됨", "대여되어", "대여해서", "예약돼", "예약됐", "예약 차서", "예약으로 꽉", "다 대여", "전부 대여"))
        out.addAll(listOf("붐빔", "붐벼서", "북적여서", "북적거려서", "북적임", "혼잡해서", "혼잡하고", "혼잡함", "복잡해서 못", "만원이라서"))
        out.joinToString("|")
    }

    /**
     * 이미 [QueryLexiconRound3] 의 대안 표지로 있는 점유·고장 말 — 같은 말에 "쓸 수 없다" 표지(avail)를 하나 더 달아,
     * 그 대상이 장비를 앞세운 이름("케이블 크로스오버 점검중")이면 그 장비도 뺄 수 있게 한다. 대안 표지로서의 동작은 그대로다.
     */
    val AVAIL_EXTRA = """
        rack is empty|rack's empty|racks are empty|rack empty|racks empty|rack is bare|shelf is empty|shelves are empty|holder is empty|stand is empty|tree is empty|all gone|are all gone|is all gone|none left|nothing left|no more left|ran out|have run out|has run out|are gone|is gone
        |랙이 비었|랙이 비어|랙 비었|랙 비어|거치대가 비었|거치대 비었|거치대가 비어|다 나갔|다 가져갔|하나도 안 남|하나도 없어|남은 게 없|ラックが空っぽ|ラックが空|ラック空っぽ|棚が空っぽ|全部出払って|出払ってる|出払っていて|一つも残ってない|残ってない
        끊어졌어|끊어졌네|끊어졌는데|끊어져서|끊어짐|끊어진|끊겼어|끊겨서|끊겼는데|찢어졌어|찢어졌네|찢어져서|찢어짐|터졌어|터져서|터졌는데|늘어나서|늘어났어|헐거워져서|삭아서|삭았어
        |切れた|切れて|切れちゃった|切れちゃって|切れてて|ちぎれた|ちぎれて|破れた|破れて|伸びきった|伸びきって|混みすぎ|混みすぎて|混みすぎてる|混みすぎてて|混みすぎで|混み過ぎ|混み過ぎて|混み過ぎてる|待てない|待てなくて|待てません|待てないので|待てないから
        |snapped|has snapped|is snapped|snapped on me|tore|is torn|are torn|got torn|ripped|is ripped|broke|broke on me|is worn out|are worn out|wore out
        점검중|점검 중|고장|고장나서|고장 나서|고장난|고장 난|수리 중|수리중|사용 중|사용중|가 사용 중|이 사용 중|꽉 차|가 꽉 차|이 꽉 차|가 다 차|이 다 차|가 만석|이 만석|사람 많아|사람이 많아|누가 쓰고 있|누가 쓰고있|줄 서 있|줄 서있|줄이 길|대기 줄|대기가 많|웨이팅|차지하고 있|차지하고있|점령당|누가 차지
        |が使用中|使用中|が使われている|が埋まっている|が埋まってる|埋まっている|埋まってる|が空いてない|が空いていない|空いてない|空いていない|が空いてなくて|が空いてないので|が空いてないから|が空いていなくて|が空いていないので|が空かない|が空かなくて|が空かないので|が空かず|空かなくて|空かないので|空いてなくて|空いてないので|埋まってて|埋まっていて|が埋まってて|が埋まっていて|混んでいる|混んでる|が混んでいる|が混んでる|が混んでて|が混んでいて|混んでて|混んでいて|が混雑|が混雑している|が混雑していて|混雑してて|混雑している|が占領されて|が取られて|が取られていて|が使われてて|が使われていて|故障中|故障して|故障してて|故障している|壊れてる|壊れていて|壊れてて|が壊れて|メンテ中|メンテナンス中|点検中|満員|満席
        |are down|were down|have gone down|has gone down|went down|is all down|are all down|are down today|is shut down|are shut down|is shut|are shut|shut down|are closed down|is closed down|are all broken|is all broken
        |is taken|are taken|is occupied|are occupied|is being used|are being used|is in use|are in use|is busy|are busy|is full|is crowded|are crowded|is not free|being used|taken|occupied|in use|all busy|all taken|all occupied|all in use|all full|all being used|all booked|are all busy|are all taken|are all full
        |is packed|are packed|packed|is slammed|are slammed|slammed|is swamped|swamped|is jammed|is mobbed|mobbed|is hogged|are hogged|hogged|being hogged|has a line|has a queue|have a line|has a waitlist|is booked|are booked|is reserved|are reserved|is broken|are broken|broken|out of order|is out of order|are out of order|under maintenance|is down|not working|isnt working|isn't working|arent working|aren't working|is closed|are closed
    """.trimIndent()

    /** 대상이 표지 뒤에 오는 영어 표지 */
    val NEG_BEFORE = """
        isnt|isn't|arent|aren't|that isnt|that isn't|that arent|that aren't|which isnt|which isn't|thats not|that's not|that is not|that are not|which is not|which are not
        line for|lines for|a line for|queue for|a queue for|long line for|long queue for|long wait for|waiting for|waiting forever for|waiting on|everyone is on|everyone's on|everybody is on|everyone is at|crowd at|crowd on|mob at|mob on|rush on|rush for
        |doesnt need|doesn't need|does not need|dont need|don't need|do not need|didnt need|without needing|without requiring|no need for|needs no|needing no|that need no|that needs no|requiring no|requires no|doesnt require|doesn't require|does not require|dont require|don't require|not requiring|not needing|not involving|nothing involving|nothing that needs|nothing with|nothing using|nothing that uses|nothing needing|no use of|zero use of|avoid using|avoiding any|avoid any|avoid all|non
        |hard pass on|hard pass for|no to|nope to|nah to|say no to|saying no to|a no to|pass on|passing on|im good without|i'm good without|good without|never|never use|never using|never do|never doing|never touch|never ever|no longer use|no longer using|not using|not touching|not gonna use|not going to use|not planning to use|not interested in|no interest in|not into|not a fan of|no fan of|not keen on|not comfortable with|not comfortable using
        |dont want|don't want|do not want|dont wanna|don't wanna|dont like|don't like|do not like|dont feel like|don't feel like|dont want to use|don't want to use|dont wanna use|don't wanna use|didnt want|didn't want|wont use|won't use|wouldnt use|wouldn't use|shouldnt use|shouldn't use|should not use|must not use|refuse to use|refuse|refusing to use
        |cant use|can't use|cannot use|can not use|cant stand|can't stand|couldnt use|couldn't use|unable to use|not able to use|not allowed to use|not permitted to use|not supposed to use|banned from|barred from|prohibited from
        |hate|hates|hating|dislike|dislikes|disliking|despise|loathe|detest|allergic to|afraid of|scared of|terrified of
        |ran out of|run out of|running out of|out of stock|short of|short on|lacking|lack of|shortage of|no access to|without access to|dont have access to|don't have access to|do not have access to|no way to use|no room for|no space for|no spot for|no spots for|no slots for|no slot for
        |hogging|hogs|hogged|hog|monopolizing|monopolising|monopolizes|monopolised|occupying|occupies|occupied by|taking over|took over|taken over by|camping on|camps on|camped on|camping at|camped at|squatting on|sitting on|guarding|blocking|blocks|blocked by|clogging|clogged by|crowding|crowded with|packed with|using up|uses up|used up|using all|uses all|used all|using every|uses every|used every|using both|uses both|using everything|uses everything|using each|uses each|always using|always uses|always taking|always hogging|always on|always camping|always occupying|always blocking|constantly using|constantly uses|constantly taking|constantly hogging|constantly on|constantly occupying|constantly blocking|keeps using|kept using|keeps taking|keeps hogging|keep hogging|kept hogging|keeps occupying|keeps blocking|keeps camping
        |someone is using|someone using|someones using|someone's using|somebody is using|somebody using|somebodys using|somebody's using|people are using|people using|people hogging|someone hogging|somebody hogging|some guy is using|some guy using|some guy hogging|some dude is using|some dude using|some dude hogging|a guy is using|a guy using|a guy hogging|a dude is using|a dude using|a dude hogging|everyone is using|everyone using|everyone hogging|everybody is using|everybody using|everybody hogging|everyone's using|everybody's using|the guy is using|the guy using|the guy hogging|the dude is using|the dude using|the dude hogging|the whole gym is using|the whole gym using|the whole gym hogging|class is using|class using|class hogging|trainer is using|trainer using|trainer hogging|trainers are using|trainers using|my friend is using|my friend using|my buddy is using|my buddy using|my partner is using|my partner using
        |forget about|forget the|leave out|leaving out|leave off|take out|taking out|cut out|cutting out|rule out|ruling out|ruled out|cross out|steer clear of|steering clear of|stay away from|staying away from|keep away from|keeping away from|stay off|staying off|opt out of|opting out of|sit out|sitting out|give up on|giving up on|quit using|stop using|stopping using|stopped using|ditch|ditching|ditched|bail on|bailing on|bailed on
    """.trimIndent()

    /** 수량어·긍정 단서·행위자 — 부정 표지와 대상 사이, 또는 대상 뒤에 끼지만 의미를 더하지 않는 말 */
    val NOISE = """
        stuff|some stuff|things|some things|type stuff|kind of stuff|related stuff
        器具を使った|器具を使って|器具を使う|器具を|器具で|道具を使った|道具を使って|道具を使う|道具を|道具で|用具を|用具で|길어서|길어|길어요|길음|길다|길고
        |둘 다|둘다|둘 모두|양쪽 다|양쪽 모두|두 개 다|두개 다|세 개 다|どちらも|両方|両方とも|どっちも|ふたつとも|二つとも|both of them|both|each|all of them
        |쓰는 건|쓰는건|쓰는 거|쓰는거|쓰는 게|쓰는게|쓰는 걸|쓰는걸|쓰는 것|쓰는것|하는 건|하는건|하는 게|하는게|하는 걸|하는걸|하는 것|하는것|쓰기는|쓰기가|쓰기도|사용하는 건|사용하는건|사용하는 거|사용하는 게|사용하는게|사용하기는|사용하기가
        |all i have is|all i have|all i got|all i got is|all ive got|all i own|all we have|all we have is|all we got|all that i have|all that we have|all there is|all that is available|all thats available|all they have is|all my gym has|all my gym has is|all my home has|all i can use is|all i can use|all i can get is
        |fine|great|cool|awesome|perfect|alright|sweet
        |anything with|anything that uses|anything that use|anything using|anything involving|anything that needs|anything needing|anything|everything with|everything that uses|something with|stuff with|stuff that uses|stuff using|things with|things that use|things using|exercises that use|exercises that need|exercises involving|moves using|any exercise with|any exercises with|any workout with|any workouts with
        |i got nothing|got nothing|i have nothing|have nothing|i've got nothing|ive got nothing|there is nothing|there's nothing|theres nothing
        |쓰는데|쓸 거고|쓸거고|쓸 수 있고|쓸 수 있는데|써도 되고|써도 되는데|쓸래|쓰고
        |lol|lmao|haha|ugh|omg|pls|plz|ㅋㅋ|ㅋㅋㅋ|ㅠㅠ|ㅜㅜ|ㅎㅎ|www|笑
        |needing|requiring|involving|needs|need|needed|require|requires|required|involve|involves|that need|that needs|that require|that requires|that involve|that involves|that use|that uses|which need|which needs|which use|which uses|having
        |bored|boring|ideas|idea|variation|variations|variety|switch up|mix up
        |라서|이라서|라서요|이라서요|라니까|이라니까|이니까|이니까요|니까요|여서|이어서|인데|인데요|이라고|이라는|라는|이었|였는데|이었는데|였어|이었어|였고|이었고
        |있음|있네|있네요|있습니다|있어서|있지만|있으니|있으니까|있거든|있거든요|있는데요|있었어|있었는데|있는 거|있는거|있는 것|있는건|있는 건|あります|あるだけ|あるのは|あるのが|あるくらい|ありまして|ありますが|あるが|あるけど|あるけれど|あるので|あるから|あるんだ|あるんです|あるんだけど|持ってる|持ってます|持っている|持っていて|持ってて|持ってるのは|having|only have|only has|only got|i have|i got|ive got|i've got|we have|we got|weve got|we've got|just have|got a|have a|have an|have some|there is|there's
        |전부|전부다|전부 다|모두|모조리|죄다|다들|누가|누군가|누군가가|누가 다|사람들이|다른 사람이|다른 사람|어떤 사람이|옆 사람이|회원들이|트레이너가|계속|계속해서|계속 계속|내내|아까부터|항상|맨날|자꾸|자꾸만|줄곧|아까
        |全部|全て|すべて|ぜんぶ|みんな|みんなが|誰か|誰かが|だれか|だれかが|いつも|ずっと|ずーっと|常に|ひっきりなし|ひっきりなしに|さっきから|朝から|一日中|今日は|今日も|今は
        |yes|yeah|yep|yup|ok|okay|alright|all right|allowed|permitted|everyone|everybody|someone|somebody|some guy|some dude|a guy|a dude|the guy|the dude|always|constantly|currently|right now|these days|all day|all the time|all day long|every single|each of the|all of the|all of them|all of these|all of those|all the|all these|all those|both of the
    """.trimIndent()

    /**
     * 못 한다는 말 — "쉬운 변형을 달라"는 뜻이면서(CANT_DO) 장비·부상과 함께면 그 대상은 못 쓴다는 뜻이다([QueryAnalyzer] 가 구분한다).
     * 못하겠어·할 수 없어·무리야·힘들어서 의 활용형 계열.
     */
    val CANT_DO_EXTRA = """
        못함|못 함|못하겠음|못 하겠음|못하겠다|못 하겠다|못하겠네|못 하겠네|못하겠어요|못 하겠어요|못 하니까|못하니까|못하거든|못 하거든|못 하는데|못하는데|못해서|못 해서|못 합니다|못합니다|못하고|못 하고|못 하는 상태|못하는 상태
        |할 수 없어|할 수 없|할수없|할 수가 없|할수가없|할 수 없는|할 수 없어요|할 수 없음|하지 못해|하지 못하|하지못해|하지못하|하질 못해
        |무리야|무리예요|무리임|무리라서|무리여서|무리인데|무리니까|무리일 듯|무리일듯|무리 같아|무리같아|힘들어서|힘들 것 같|힘들것같|어려워서|어려울 것 같|버거워|버거워서|감당 못|감당이 안
        |不可能|不可能で|むり|ムリ|無理で|無理です|無理なので|無理だ|無理かも|無理そう|無理なんで|は無理|はムリ|ムリで|ムリです|できません|できなくて|できないので|できないから|やれません|やれなくて|難しい|難しくて|難しいので|つらい|つらくて|辛くて|きつくて|きついので
        |cant do|can't do|cannot do|can not do|cant|can't|cannot|not able to do|unable to do|unable to|not able to|couldnt do|couldn't do|could not do|too hard to do|too difficult to do|too painful to do|hurts to do|hurts too much to do
        |못 잡|못잡|못 잡음|못잡음|못 잡아|못잡아|못 잡고|못잡고|못 잡겠|못잡겠|못 잡는|못잡는|못 쥐|못쥐|못 쥠|못쥠|못 쥐어|못 쥐겠|못 쥐는
        |못 들|못들|못 들어|못들어|못 들어요|못들어요|못 들고|못들고|못 들겠|못들겠|못 드는|못드는|못 듦|못듦
        |못 밀|못밀|못 밀어|못밀어|못 밀겠|못밀겠|못 당겨|못당겨|못 당기|못당기|못 매달|못매달|못 올리|못올리|못 올려|못올려|못 올리겠|못올리겠
        |握れない|握れなくて|握れず|握れません|握れないので|握れないから|握れなくなった|握れなくなって|持てない|持てなくて|持てず|持てません|持てないので|持てないから|持てなくなった|掴めない|掴めなくて|つかめない|つかめなくて
        |cant grip|can't grip|cannot grip|cant hold|can't hold|cannot hold|cant lift|can't lift|cannot lift|cant hang|can't hang|cant press|can't press|cannot press|cant push|can't push|cant pull|can't pull|unable to grip|unable to hold|unable to lift|not able to grip|not able to hold|not able to lift
        |안 돼|안돼|안 됨|안됨|안되고|안 되고|안 되네|안되네|안 되니까|안돼서|안되서|안 돼서|안 되서|안됩니다|안 됩니다|안되는데|안 되는데|안되지만|안 되지만|안되거든|안 돼요|안돼용|안 되는 상태|안되는 상태
    """.trimIndent()

    /** 부상 감각 표현 — 관절 가까이에서 그 관절을 피할 부위로 만든다(관절이 없으면 아무 일도 하지 않는다) */
    val CUES_EXTRA = """
        twisted my|tweaked my|rolled my|hurt my|injured my|pulled my|strained my|sprained my|busted my|jacked up my|messed up my|banged up my|twisted his|twisted her|rolled her|rolled his
        cooked|shot|trashed|wrecked|fried|messed up|screwed up|jacked up|flared up|flared|flares|flare-up|flareup|acting up|acted up|toast
        |pinch|pinches|pinching|pinched|twinge|twinges|clicking|grinding|grinds|bothers|bothering|bothered|nags|nagging|acts up|flares up|flaring up|tweaked|tweak|killing me|kills me|aches when|hurts when|hurts if|painful when|pain when|pain during|pain while|sore when|uncomfortable when
        |때문에|때문이라|때문이라서|탓에|のせいで|のせい|が原因で|が原因|because of|due to|owing to|on account of
        |찌릿|찌릿해|찌릿해요|찌릿찌릿|찌릿하|뜨끔|콕콕|쑤셔|쑤셔요|쑤시|욱신|욱신거려|욱신거리|삐끗|삐었|접질|저릿|저릿해|무리가 와|무리 와
        |ズキズキ|ずきずき|ピキッ|ぴきっ|チクチク|ちくちく|ビリビリ|ぎくっ|ギクッ|捻挫|捻った|ひねった|痛みがある|痛みが出る|痛みが出て|痛みが走る|調子が悪い|調子悪い|引っかかる|ひっかかる
    """.trimIndent()

    /**
     * 싫다·무섭다·苦手 — 취향 표현이라 대상이 부위·개념이어도 빼지만("가슴 운동은 싫어"), 대상이 이름 없는 덩어리("쓰는 건")면 같은 절의 장비를 뺀다.
     * 이미 [QueryLexiconRound3] 의 부정 표지로 있는 말에 dislike 표지를 하나 더 단다.
     */
    val DISLIKE_EXTRA = """
        싫어|싫은|싫어서|싫다|싫네|싫음|싫은데|싫고|무서워|무서운|무서워서|무섭다|무섭|겁나|겁나서|겁나는|부담스러워|부담스러운|부담돼|꺼려져|꺼려져서|꺼려
        |嫌い|嫌|嫌だ|嫌で|が嫌い|は嫌い|が嫌|は嫌|が苦手|は苦手|怖い|怖くて|怖いので|が怖い|は怖い|いや|いやで
        |scared of|afraid of|nervous about|worried about|uncomfortable with|intimidated by
    """.trimIndent()

    /**
     * 쓸 수 있다·비어 있다는 말 — 점유·고장 표지와 글자가 겹치지만("대기 없음", "aren't taken") 뜻은 반대라서 더 긴 군더더기로 먼저 먹는다.
     * 군더더기라 아무 것도 빼거나 요청하지 않는다.
     */
    val AVAILABLE: String = run {
        val out = LinkedHashSet<String>()
        val koWait = listOf("대기", "줄", "줄이", "웨이팅", "대기줄이", "대기 줄이", "기다릴 일", "기다릴 필요")
        val koNone = listOf("없음", "없어", "없어서", "없이", "없는", "없고", "없네", "없다", "없어요", "없는데", "없으니까", "없거든")
        for (w in koWait) for (n in koNone) out.add("$w $n")
        for (w in listOf("사람", "사람이", "사람들이", "인원이", "회원이")) for (n in koNone + listOf("적어", "적음", "적어서", "적은데", "적네", "별로 없어", "거의 없어", "거의 없음", "얼마 없어")) out.add("$w $n")
        out.addAll(listOf("비어 있어서", "비어있어서", "비었어", "비었네", "비어서", "비어있음", "비어 있음", "비어 있어", "비어있어", "비어 있는", "비어있는", "비어 있네", "비어있네", "비어 있는데", "비어있는데", "자리 있음", "자리 있어", "자리 남아 있어", "자리가 있어", "자리가 남아", "자리 남아", "한산해", "한산해서", "한산한", "한산", "널널해", "널널해서", "널널", "여유 있어", "여유있어", "여유로워", "안 붐벼", "안 붐비는", "안붐벼", "안 붐빔", "안 막혀", "막히지 않아", "안 차 있어", "안 찼어", "안 꽉 차", "꽉 차지 않았", "비지 않은 건 아니", "텅 비어", "텅 비었어", "텅텅", "텅텅 비어"))
        out.addAll(listOf("待ちなし", "待ち時間なし", "待たずに", "空いてる", "空いています", "空いている", "空いてます", "空いていて", "空いてた", "空いたので", "ガラガラ", "すいてる", "すいています", "混んでない", "混んでいない", "混雑してない", "混雑していない", "並ばなくていい", "並ばずに", "誰も使ってない", "誰も使っていない", "使われてない", "使われていない", "余ってる", "余っている", "空きがある", "空きあり", "余裕がある", "人がいない", "人が少ない", "人が少なくて", "人が全然いない", "待ち時間がない", "待ちがない", "並んでない", "並んでいない", "行列がない", "行列なし"))
        out.addAll(listOf("no line for", "no lines for", "no queue for", "no wait for", "no waiting for", "no crowd at", "no crowd on", "no line at", "no queue at", "no wait at", "no line", "no queue", "no wait", "no waiting", "no crowds", "no crowd",
            "nobody is using", "nobody's using", "nobody using", "no one is using", "no one using", "noone is using", "nobody is on", "nobody on", "no one on", "no one is on", "nobody is hogging", "nobody hogging", "no one is hogging", "no one hogging", "nobody has", "no one has", "nobody", "no one", "noone",
            "aren't busy", "arent busy", "isn't busy", "isnt busy", "not busy", "not crowded", "arent crowded", "aren't crowded", "isnt crowded", "isn't crowded", "not packed", "isnt packed", "isn't packed", "arent packed", "aren't packed",
            "not taken", "isnt taken", "isn't taken", "arent taken", "aren't taken", "not in use", "isnt in use", "isn't in use", "arent in use", "aren't in use", "not occupied", "isnt occupied", "isn't occupied", "arent occupied", "aren't occupied",
            "not being used", "isnt being used", "isn't being used", "arent being used", "aren't being used", "not reserved", "isnt reserved", "isn't reserved", "arent reserved", "aren't reserved", "not booked", "isnt booked", "isn't booked", "not blocked", "isnt blocked", "isn't blocked",
            "are empty", "is empty", "wide open", "available now", "available right now", "plenty of", "lots of", "tons of", "loads of", "a lot of", "so many", "plenty", "are open", "is open", "are free", "is free", "are available", "is available", "all available", "all free", "all open"))
        out.joinToString("|")
    }
}

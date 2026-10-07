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
        |못 잡|못잡|못 잡음|못잡음|못 잡아|못잡아|못 잡고|못잡고|못 잡겠|못잡겠|못 잡는|못잡는|못 쥐|못쥐|못 쥠|못쥠|못 쥐어|못 쥐겠|못 쥐는
        |못 들|못들|못 들어|못들어|못 들어요|못들어요|못 들고|못들고|못 들겠|못들겠|못 드는|못드는|못 듦|못듦
        |못 밀|못밀|못 밀어|못밀어|못 밀겠|못밀겠|못 당겨|못당겨|못 당기|못당기|못 매달|못매달|못 올리|못올리|못 올려|못올려|못 올리겠|못올리겠
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
        |없음|없네|없다|없어요|없습니다|없네요|없거든|없거든요|없다니까|없는 상태|없는 환경|없는 곳
        |싫음|싫다|싫은데|싫어요|싫네|싫거든|싫어서|싫은 거|싫은거|꺼려|꺼려져요
        |빼줘|빼 줘|빼주세요|빼 주세요|빼자|빼고싶|빼고 싶|빼 놓고|빼놓고|제외해줘|제외해 줘|제외해주세요|제외해 주세요|제외하고 싶|뺀|뺀거|뺀 거|뺀 걸로|뺀 것|뺀 운동
        |パス|パスで|はパス|のでパス|からパス|ならパス|でパス|もパス|パスです|パスしたい|パスしたいです|パスだ|パスね|パスかな|パスで全|ぱすで
        |패스|패스할래|패스함|패스야|패스예요|패스하고 싶어|패스할게|패스하겠습니다|패스하고|패스할 거야|패스할거야|패스 할래
        |禁止|禁止で|禁止です|禁止だ|禁止なので|禁止なんで|禁止されてる|禁止されて|禁止されている|禁止になった|使用禁止|使用不可|利用禁止|利用不可
        |NG|NGで|NGです|NGなので|ダメで|ダメです|ダメなので|ダメだ|だめで|だめです|だめなので|駄目で|駄目です|はダメ|はだめ|は駄目|不可で|不可です|不可
        |厳しい|厳しくて|厳しいので
        |握れない|握れなくて|握れず|握れません|握れないので|握れないから|握れなくなった|握れなくなって|持てない|持てなくて|持てず|持てません|持てないので|持てないから|持てなくなった|掴めない|掴めなくて|つかめない|つかめなくて|握力がなくて|握力が弱くて|握力がない
        |使えない|使えなくて|使えず|使えません|使えないので|使えないから|使えなくなった|使えなくなって|つかえない|つかえなくて|使用できません|使用できないので|扱えない|扱えなくて|触れない|触れなくて|触りたくない|触りたくなくて|触れたくない
        |使われてる|使われている|使われてて|使われていて|使われてます|使われています|使われっぱなし|使われっぱ|使われちゃってる|使われちゃって|使われてしまって|使われまくり|使用されてる|使用されている|使用されてて|使用されていて|占領されてる|占領されている|占領されてて|占領されて|占拠されてる|占拠されて|独占されてる|独占されている|独占されてて|独占されて|独り占め|取られてる|取られている|取られてて|取られちゃって|取られっぱなし|押さえられてる|ふさがってる|ふさがっている|ふさがってて|塞がってる|塞がっている|塞がってて|埋まっちゃってる|埋まっちゃって|埋まりっぱなし
        |並んでる|並んでいる|並んでて|並んでいて|行列|行列で|行列ができてる|行列ができて|順番待ち|順番待ちで|順番待ちしてる|待ちで|待ちが長い|待ち時間が長い|待ち時間長い|待たされる|待たないと|待たなきゃ|待たなければ
        |故障で|全部故障|全て故障|すべて故障|ぜんぶ故障|が全部故障|全部壊れて|全部壊れてる|全て壊れて|故障だ|故障なので|故障なんで|故障中で|故障中です|故障中なので|故障してる|壊れてます|壊れています|壊れちゃって|壊れちゃってる|壊れっぱなし|壊れかけ|破損してる|破損して|メンテで|メンテ中で|メンテ中です|メンテナンス中で|メンテナンス中です|メンテナンスで|点検で|点検中で|点検中です|点検中なので|点検してる|点検してて|点検している|修理中|修理中で|修理中です|修理中なので|修理してる|修理してて|修理している|修理待ち|修理に出てる|整備中|整備中で|整備中です|工事中|工事中で|工事中です|閉鎖中|閉鎖されてる|閉鎖されて|休止中|停止中|使用停止|使用中止|閉まってる|閉まっている|閉まってて|閉まっていて|閉まってます|閉まっています
        |ないです|ないので|ないんで|ないんです|ないんだ|ないんだよ|ないんだよね|ありません|ありませんので|ないけど|ないんですが|ないのですが|ないんだけど|ないですが|なくなった|なくなって|なくなっちゃった|無いです|無いので
        |はいやだ|はイヤだ|がいやだ|は嫌だ|が嫌だ|はいやです|はいやなんだ|はいやだな|はいやなので|がいやです|いやだ|嫌だ|イヤだ|やだ|ヤダ|嫌なんだ|嫌なの|いやなの|いやなんだ|いやだな|嫌だな|嫌かな|嫌だから|いやだから|やだから|嫌だし|いやだし
        |嫌いで|嫌いです|嫌いだ|嫌いなので|いやで|いやです|苦手です|苦手で|苦手なので|やめたい|やめたくて|やめとく|やめとき|やめておく|やめておきたい|やめとこう|やめよう|避けたいです|避けたいので|避けたいんだ|避けたいんですが|控えたい|控えたくて|控える|控えて|控えます|遠慮したい|遠慮して|遠慮します|遠慮する|敬遠したい|敬遠して|敬遠してる
        |除きたい|除いて|除いてください|除いてほしい|除外してください|除外してほしい|外して|外してください|外してほしい|外したい|外したくて|はずして|はずしてください|はずしたい|抜いて|抜いてください|抜いてほしい|抜きたい|抜きたくて|消して|消してください|消したい
        |no way|no go|nogo|no thanks|not allowed|not permitted|not an option|not available|not usable|not accessible|banned|prohibited|forbidden|off limits|offlimits|off-limits|is out|are out|is out for me|are out for me|is a no|are a no|is a no go|are a no go|a no go|a no-go|is off limits|are off limits|is banned|are banned|is not an option|are not an option|isnt an option|arent an option|isn't an option|aren't an option|is not allowed|are not allowed|isnt allowed|arent allowed|isn't allowed|aren't allowed|is off the table|are off the table|off the table
        |is taken over|are taken over|taken over|got taken|got taken over|is monopolized|are monopolized|monopolized|is all used|are all used|is used up|are used up|used up|is spoken for|are spoken for|spoken for|is blocked|are blocked|blocked|blocked off|roped off|is not free|are not free|isnt free|arent free|isn't free|aren't free|is not open|are not open|isnt open|arent open|isn't open|aren't open|is unusable|are unusable|unusable|is gone|are gone|all gone|always taken|always busy|always in use|always occupied|always full|always packed|always crowded|always has a line|always a line|always a queue|always a wait
        |is out of service|are out of service|out of service|down for repairs|down for maintenance|is being repaired|are being repaired|being repaired|is being fixed|are being fixed|being fixed|is being serviced|being serviced|needs repair|need repair|needs repairs|need repairs|needs fixing|need fixing|needs maintenance|is under repair|are under repair|under repair|under repairs|is being cleaned|being cleaned|jammed|is jammed|are jammed|is stuck|are stuck|doesnt work|doesn't work|dont work|don't work|wont work|won't work|not functional|not functioning|isnt functioning|isn't functioning|is not functioning|malfunctioning|is malfunctioning|are malfunctioning|is faulty|are faulty|faulty|is defective|are defective|defective|is busted|are busted|busted|is dead|are dead|got broken|got broke|is broke|are broke
        |is overrun|are overrun|overrun|is overcrowded|are overcrowded|overcrowded|jam packed|jampacked|is crammed|are crammed|crammed|is swarmed|are swarmed|swarmed|is overbooked|overbooked|is fully booked|are fully booked|fully booked|is sold out|are sold out|sold out|full up|is full of people|are full of people|full of people|has people on it|have people on it|has someone on it|have someone on it|has someone using it|have someone using them|someone is on it|someone is using it|someone is using them|someone has it|someone has them|somebody is on it|somebody is using it|somebody is using them|people are on it|people are using it|people are using them|in use by someone|being used by someone|used by someone|in use by people|being used by people|used by people|occupied by someone|occupied by people|taken by someone|taken by people|reserved by someone|hogged by someone|hogged by people|blocked by someone
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
        out.addAll(listOf("붐빔", "붐벼서", "북적여서", "북적거려서", "북적임", "혼잡해서", "혼잡하고", "혼잡함", "복잡해서 못", "만원이라서"))
        out.joinToString("|")
    }

    /**
     * 이미 [QueryLexiconRound3] 의 대안 표지로 있는 점유·고장 말 — 같은 말에 "쓸 수 없다" 표지(avail)를 하나 더 달아,
     * 그 대상이 장비를 앞세운 이름("케이블 크로스오버 점검중")이면 그 장비도 뺄 수 있게 한다. 대안 표지로서의 동작은 그대로다.
     */
    val AVAIL_EXTRA = """
        점검중|점검 중|고장|고장나서|고장 나서|고장난|고장 난|수리 중|수리중|사용 중|사용중|가 사용 중|이 사용 중|꽉 차|가 꽉 차|이 꽉 차|가 다 차|이 다 차|가 만석|이 만석|사람 많아|사람이 많아|누가 쓰고 있|누가 쓰고있|줄 서 있|줄 서있|줄이 길|대기 줄|대기가 많|웨이팅|차지하고 있|차지하고있|점령당|누가 차지
        |が使用中|使用中|が使われている|が埋まっている|が埋まってる|埋まっている|埋まってる|が空いてない|が空いていない|空いてない|空いていない|が空いてなくて|が空いてないので|が空いてないから|が空いていなくて|が空いていないので|が空かない|が空かなくて|が空かないので|が空かず|空かなくて|空かないので|空いてなくて|空いてないので|埋まってて|埋まっていて|が埋まってて|が埋まっていて|混んでいる|混んでる|が混んでいる|が混んでる|が混んでて|が混んでいて|混んでて|混んでいて|が混雑|が混雑している|が混雑していて|混雑してて|混雑している|が占領されて|が取られて|が取られていて|が使われてて|が使われていて|故障中|故障して|故障してて|故障している|壊れてる|壊れていて|壊れてて|が壊れて|メンテ中|メンテナンス中|点検中|満員|満席
        |is taken|are taken|is occupied|are occupied|is being used|are being used|is in use|are in use|is busy|are busy|is full|is crowded|are crowded|is not free|being used|taken|occupied|in use|all busy|all taken|all occupied|all in use|all full|all being used|all booked|are all busy|are all taken|are all full
        |is packed|are packed|packed|is slammed|are slammed|slammed|is swamped|swamped|is jammed|is mobbed|mobbed|is hogged|are hogged|hogged|being hogged|has a line|has a queue|have a line|has a waitlist|is booked|are booked|is reserved|are reserved|is broken|are broken|broken|out of order|is out of order|are out of order|under maintenance|is down|not working|isnt working|isn't working|arent working|aren't working|is closed|are closed
    """.trimIndent()

    /** 대상이 표지 뒤에 오는 영어 표지 */
    val NEG_BEFORE = """
        never|never use|never using|never do|never doing|never touch|never ever|no longer use|no longer using|not using|not touching|not gonna use|not going to use|not planning to use|not interested in|no interest in|not into|not a fan of|no fan of|not keen on|not comfortable with|not comfortable using
        |dont want|don't want|do not want|dont wanna|don't wanna|dont like|don't like|do not like|dont feel like|don't feel like|dont want to use|don't want to use|dont wanna use|don't wanna use|didnt want|didn't want|wont use|won't use|wouldnt use|wouldn't use|shouldnt use|shouldn't use|should not use|must not use|refuse to use|refuse|refusing to use
        |cant use|can't use|cannot use|can not use|cant grip|can't grip|cannot grip|cant hold|can't hold|cannot hold|cant lift|can't lift|cant hang|can't hang|cant press|can't press|cant handle|can't handle|cant stand|can't stand|couldnt use|couldn't use|unable to use|unable to grip|unable to hold|not able to use|not able to grip|not able to hold|not allowed to use|not permitted to use|not supposed to use|banned from|barred from|prohibited from
        |hate|hates|hating|dislike|dislikes|disliking|despise|loathe|detest|allergic to|afraid of|scared of|terrified of
        |ran out of|run out of|running out of|out of stock|short of|short on|lacking|lack of|shortage of|no access to|without access to|dont have access to|don't have access to|do not have access to|no way to use|no room for|no space for|no spot for|no spots for|no slots for|no slot for
        |hogging|hogs|hogged|hog|monopolizing|monopolising|monopolizes|monopolised|occupying|occupies|occupied by|taking over|took over|taken over by|camping on|camps on|camped on|camping at|camped at|squatting on|sitting on|guarding|blocking|blocks|blocked by|clogging|clogged by|crowding|crowded with|packed with|using up|uses up|used up|using all|uses all|used all|using every|uses every|used every|using both|uses both|using everything|uses everything|using each|uses each|always using|always uses|always taking|always hogging|always on|always camping|always occupying|always blocking|constantly using|constantly uses|constantly taking|constantly hogging|constantly on|constantly occupying|constantly blocking|keeps using|kept using|keeps taking|keeps hogging|keep hogging|kept hogging|keeps occupying|keeps blocking|keeps camping
        |someone is using|someone using|someones using|someone's using|somebody is using|somebody using|somebodys using|somebody's using|people are using|people using|people hogging|someone hogging|somebody hogging|some guy is using|some guy using|some guy hogging|some dude is using|some dude using|some dude hogging|a guy is using|a guy using|a guy hogging|a dude is using|a dude using|a dude hogging|everyone is using|everyone using|everyone hogging|everybody is using|everybody using|everybody hogging|everyone's using|everybody's using|the guy is using|the guy using|the guy hogging|the dude is using|the dude using|the dude hogging|the whole gym is using|the whole gym using|the whole gym hogging|class is using|class using|class hogging|trainer is using|trainer using|trainer hogging|trainers are using|trainers using|my friend is using|my friend using|my buddy is using|my buddy using|my partner is using|my partner using
        |forget about|forget the|leave out|leaving out|leave off|take out|taking out|cut out|cutting out|rule out|ruling out|ruled out|cross out|steer clear of|steering clear of|stay away from|staying away from|keep away from|keeping away from|stay off|staying off|opt out of|opting out of|sit out|sitting out|give up on|giving up on|quit using|stop using|stopping using|stopped using|ditch|ditching|ditched|bail on|bailing on|bailed on
    """.trimIndent()

    /** 수량어·긍정 단서·행위자 — 부정 표지와 대상 사이, 또는 대상 뒤에 끼지만 의미를 더하지 않는 말 */
    val NOISE = """
        器具を使った|器具を使って|器具を使う|器具を|器具で|道具を使った|道具を使って|道具を使う|道具を|道具で|用具を|用具で|길어서|길어|길어요|길음|길다|길고
        |곳|쪽|장소|오늘|오늘은|오늘따라|오늘도|구역|코너|영역|エリア|ゾーン|コーナー|area|zone|corner|section|stations|bored|boring|ideas|idea|variation|variations|variety|switch up|mix up
        |라서|이라서|라서요|이라서요|라니까|이라니까|이니까|이니까요|니까요|여서|이어서|인데|인데요|이라고|이라는|라는|이었|였는데|이었는데|였어|이었어|였고|이었고
        |있음|있네|있네요|있습니다|있어서|있지만|있으니|있으니까|있거든|있거든요|있는데요|있었어|있었는데|있는 거|있는거|있는 것|있는건|있는 건|あります|あるだけ|あるのは|あるのが|あるくらい|ありまして|ありますが|あるが|あるけど|あるけれど|あるので|あるから|あるんだ|あるんです|あるんだけど|持ってる|持ってます|持っている|持っていて|持ってて|持ってるのは|having|only have|only has|only got|i have|i got|ive got|i've got|we have|we got|weve got|we've got|just have|got a|have a|have an|have some|there is|there's
        |전부|전부다|전부 다|모두|모조리|죄다|다들|누가|누군가|누군가가|누가 다|사람들이|다른 사람이|다른 사람|어떤 사람이|옆 사람이|회원들이|트레이너가|계속|계속해서|계속 계속|내내|하루종일|하루 종일|아까부터|항상|늘|맨날|자꾸|자꾸만|줄곧|아까
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
    """.trimIndent()

    /** 부상 감각 표현 — 관절 가까이에서 그 관절을 피할 부위로 만든다(관절이 없으면 아무 일도 하지 않는다) */
    val CUES_EXTRA = """
        pinch|pinches|pinching|pinched|twinge|twinges|clicking|grinding|grinds|bothers|bothering|bothered|nags|nagging|acts up|flares up|flaring up|tweaked|tweak|killing me|kills me|aches when|hurts when|hurts if|painful when|pain when|pain during|pain while|sore when|uncomfortable when
        |찌릿|찌릿해|찌릿해요|찌릿찌릿|찌릿하|뜨끔|콕콕|쑤셔|쑤셔요|쑤시|욱신|욱신거려|욱신거리|삐끗|삐었|접질|저릿|저릿해|무리가 와|무리 와
        |ズキズキ|ずきずき|ピキッ|ぴきっ|チクチク|ちくちく|ビリビリ|ぎくっ|ギクッ|捻挫|捻った|ひねった|痛みがある|痛みが出る|痛みが出て|痛みが走る|調子が悪い|調子悪い|引っかかる|ひっかかる
    """.trimIndent()
}

package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.engine.DescriptionLexicon
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Role
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * 검색 3차 — 동작을 묘사하거나 증상·상황·대안으로 묻는 자연어 질의의 회귀 테스트.
 *
 * 1. 설명문(instructions·tips) 검색 계층: 이름으로는 못 찾는 묘사 질의를 찾되, 이름 일치를 밀어내지 않는다
 * 2. 증상(硬い·땡겨요·tight) → 스트레칭·모빌리티·폼롤링, 근막 이완 → 폼롤러, 肩甲骨はがし → 견갑 모빌리티
 * 3. 대안 요청(代わり·대신·alternative)은 그 운동을 빼고 비슷한 운동, 랙이 없으면 바벨 없는 스쿼트류
 * 4. 의자·벽·평행봉·철봉·원룸 같은 생활 사물·장소
 * 5. 息が上がる·땀·걷기·수영 같은 강도·활동 표현, ~系·계열·종류 접미사
 * 6. 세부 부위·속어(뒷어깨·振り袖·광배 스트레칭·chest opener·背筋), 첫 헬스장 방문·덤벨 한 쌍 전신
 *
 * 특정 평가 질의를 맞추는 테스트가 아니라 표(어휘)와 규칙이 유형 전체에 일반적으로 동작하는지를 본다.
 * 아래 표는 "질의 → 상위 5개 안에 slug 가 이 정규식에 맞는 운동이 있다" 로 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchRound3Test {

    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        engine = ExerciseSearchEngine(docs)
    }

    private fun top(q: String, n: Int = 5) = engine.search(q).take(n).map { it.doc }
    private fun slugs(q: String, n: Int = 5) = top(q, n).map { it.slug }

    companion object {
        private fun row(q: String, locale: String, anySlug: String): Arguments = Arguments.of(q, locale, anySlug)

        /** (질의, 로케일, 상위 5개 중 하나의 slug 가 맞아야 하는 정규식) */
        @JvmStatic
        fun naturalCases(): Stream<Arguments> = Stream.of(
            row("腕を前に上げる運動", "ja", "front-raise"),
            row("膝を胸に引き寄せるストレッチ", "ja", "knee-to-chest"),
            row("仰向けで脚を持ち上げる", "ja", "leg-raise|leg-lift"),
            row("四つん這いで反対の手足を伸ばす", "ja", "bird-dog|alternate-arm-leg"),
            row("ぶら下がって膝を胸に引き上げる", "ja", "hanging-knee|hanging-leg"),
            row("肩をすくめる運動", "ja", "shrug"),
            row("しゃがんで立ち上がる", "ja", "squat"),
            row("壁に背中をつけてしゃがんだまま止まる", "ja", "wall-sit|wall-squat"),
            row("엎드려서 무릎을 굽히는 머신", "ko", "leg-curl"),
            row("앉아서 무릎을 펴는 머신", "ko", "leg-extension"),
            row("누워서 다리를 들어올리는 운동", "ko", "leg-raise|leg-lift"),
            row("매달려서 무릎을 가슴 쪽으로 올리는 운동", "ko", "hanging-knee|hanging-leg"),
            row("팔을 옆으로 들어올리는 운동", "ko", "lateral-raise|side-raise"),
            row("어깨를 으쓱하는 운동", "ko", "shrug"),
            row("벽에 등을 대고 앉아서 버티기", "ko", "wall-sit|wall-squat"),
            row("lying face down and bending your knees machine", "en", "leg-curl"),
            row("sit and straighten your legs machine", "en", "leg-extension"),
            row("hang from a bar and lift your knees", "en", "hanging-knee|hanging-leg"),
            row("lie on your back and raise your legs", "en", "leg-raise|leg-lift"),
            row("shrug your shoulders with weights", "en", "shrug"),
            row("肩がこる", "ja", "stretch|neck|shoulder-circle|arm-circle|roller"),
            row("首が張る", "ja", "neck"),
            row("腰が硬い", "ja", "stretch|cat-cow|child|rotation|roller"),
            row("ふくらはぎが張る", "ja", "calf|calves"),
            row("太ももの前が硬い", "ja", "quad"),
            row("股関節が硬くて開かない", "ja", "hip|pigeon|butterfly|frog|90|couch"),
            row("背中が凝って辛い", "ja", "stretch|roller|cat-cow|child|rotation"),
            row("お尻のコリをほぐしたい", "ja", "glute|pigeon|roller"),
            row("胸の前が硬い", "ja", "chest|pec"),
            row("어깨가 뭉쳤어요", "ko", "shoulder.*stretch|neck|stretch|roller|circle"),
            row("종아리가 땡겨요", "ko", "calf|calves"),
            row("허리가 뻐근할 때", "ko", "stretch|cat-cow|child|rotation|roller|back"),
            row("햄스트링이 뻣뻣해요", "ko", "hamstring"),
            row("목이 결려요", "ko", "neck"),
            row("고관절이 뻑뻑해요", "ko", "hip|pigeon|butterfly|frog|90|couch"),
            row("허벅지 앞이 당겨요", "ko", "quad"),
            row("tight hip flexors", "en", "hip-flexor|couch|quad"),
            row("tight calves", "en", "calf|calves"),
            row("sore lower back stretches", "en", "lower-back|spine|cat-cow|child|knee-to-chest|rotation"),
            row("stiff shoulders", "en", "shoulder.*stretch|stretch.*shoulder|neck|circle|roller|across-chest"),
            row("tight chest", "en", "chest|pec"),
            row("neck tension relief", "en", "neck"),
            row("my glutes are tight", "en", "glute|pigeon|figure|roller"),
            row("foam roll my back", "en", "foam-roller"),
            row("筋膜リリースをしたい", "ja", "foam-roller"),
            row("폼롤러로 풀어주기", "ko", "foam-roller"),
            row("肩甲骨を動かすストレッチ", "ja", "scapula|circle|thoracic|cat-cow|wall-angel|pull-apart|stretch"),
            row("loosen my shoulder blades", "en", "scapula|circle|thoracic|cat-cow|pull-apart|upward-rotation"),
            row("スクワットの代わりになる脚トレ", "ja", "lunge|leg-press|split-squat|step-up|bridge|hack|thrust"),
            row("ベンチプレスの代わり", "ja", "push-up|chest-press|fly|dip|floor-press"),
            row("懸垂の代わりになる背中トレ", "ja", "pulldown|pull-down|row|assisted|inverted"),
            row("デッドリフトの代用", "ja", "good-morning|romanian|back-extension|hip-thrust|pull-through|kettlebell-swing|rdl"),
            row("스쿼트 대신 할 수 있는 운동", "ko", "lunge|leg-press|split-squat|step-up|bridge|hack|thrust"),
            row("푸시업 대신 가슴 운동", "ko", "chest-press|bench|fly|dip|floor-press|pec"),
            row("턱걸이 대체 운동", "ko", "pulldown|pull-down|row|assisted|inverted"),
            row("레그프레스 말고 다른 하체 운동", "ko", "squat|lunge|extension|curl|bridge|thrust|hack"),
            row("alternative to pull ups", "en", "pulldown|pull-down|row|assisted|inverted"),
            row("squat alternatives", "en", "lunge|leg-press|split-squat|step-up|bridge|hack|thrust"),
            row("substitute for bench press", "en", "push-up|chest-press|fly|dip|floor-press"),
            row("instead of deadlifts", "en", "good-morning|romanian|back-extension|hip-thrust|pull-through|swing|rdl"),
            row("no barbell shoulder press alternative", "en", "dumbbell.*press|machine.*press|raise|arnold|pike"),
            row("crunch alternative", "en", "plank|leg-raise|dead-bug|bicycle|reverse-crunch|cable-crunch|v-up|hollow"),
            row("ケーブルがないときの代わりの胸トレ", "ja", "dumbbell.*fly|push-up|press|dip"),
            row("椅子を使った運動", "ja", "chair"),
            row("의자를 이용한 하체 운동", "ko", "chair"),
            row("chair exercises", "en", "chair"),
            row("壁を使ったエクササイズ", "ja", "wall"),
            row("벽을 이용한 운동", "ko", "wall"),
            row("wall workout", "en", "wall"),
            row("평행봉으로 하는 운동", "ko", "dip|leg-raise"),
            row("平行棒を使った筋トレ", "ja", "dip|leg-raise"),
            row("parallel bars exercises", "en", "dip|leg-raise"),
            row("鉄棒にぶら下がる運動", "ja", "dead-hang|hanging|pull-up|chin"),
            row("철봉에 매달리기", "ko", "dead-hang|hanging|pull-up|chin"),
            row("bar hang", "en", "dead-hang|hanging|pull-up|chin"),
            row("ワンルームでできる下半身運動", "ja", "bodyweight|squat|lunge|bridge|band|dumbbell"),
            row("원룸에서 하는 가슴 운동", "ko", "push-up|bodyweight|dumbbell|band"),
            row("small apartment cardio", "en", "jumping-jack|high-knees|burpee|mountain|skipping|butt-kicks|march|fast-feet|squat"),
            row("ホテルでできる筋トレ", "ja", "bodyweight|push-up|squat|plank|band"),
            row("집에서 하는 등 운동", "ko", "inverted|band|dumbbell|superman|pull-up|bird-dog|bodyweight|row"),
            row("息が上がる運動", "ja", "burpee|mountain|jumping-jack|high-knees|jump|swing|battle|sprint|air-bike|box-jump|butt-kicks|fast-feet|skipping"),
            row("숨이 차는 운동", "ko", "burpee|mountain|jumping-jack|high-knees|jump|swing|battle|sprint|air-bike|box-jump|butt-kicks|fast-feet|skipping"),
            row("get out of breath quickly", "en", "burpee|mountain|jumping-jack|high-knees|jump|swing|battle|sprint|air-bike|box-jump|butt-kicks|fast-feet|skipping"),
            row("汗をたくさんかきたい", "ja", "treadmill|bike|run|burpee|jump|rowing|elliptical|air-bike|battle|stair|swing|skipping"),
            row("땀 나는 유산소", "ko", "treadmill|bike|run|burpee|jump|rowing|elliptical|air-bike|battle|stair|skipping"),
            row("sweat a lot workout", "en", "treadmill|bike|run|burpee|jump|rowing|elliptical|air-bike|battle|stair|swing|skipping"),
            row("45分ウォーキングしたい", "ja", "walk|treadmill-walking"),
            row("한 시간 걷기", "ko", "walk"),
            row("I just want to walk", "en", "walk"),
            row("傾斜のあるウォーキング", "ja", "incline"),
            row("경사 걷기 운동", "ko", "incline|stair"),
            row("walk on an incline", "en", "incline"),
            row("泳ぎたい気分", "ja", "swim"),
            row("수영 운동", "ko", "swim"),
            row("swim laps", "en", "swim"),
            row("ランジの種類", "ja", "lunge"),
            row("スクワット系の種目一覧", "ja", "squat"),
            row("プッシュアップのバリエーション", "ja", "push-up"),
            row("스쿼트 계열 운동", "ko", "squat"),
            row("런지 종류 알려줘", "ko", "lunge"),
            row("푸시업 변형 동작", "ko", "push-up"),
            row("types of lunges", "en", "lunge"),
            row("all kinds of curls", "en", "curl"),
            row("plank variations", "en", "plank"),
            row("row variations", "en", "row"),
            row("肩の後ろ側を鍛える種目", "ja", "rear|reverse-fly|face-pull|reverse"),
            row("뒷어깨 자극 운동", "ko", "rear|reverse-fly|face-pull|reverse"),
            row("rear delt focused", "en", "rear|reverse-fly|face-pull|reverse"),
            row("二の腕のぷよぷよを減らしたい", "ja", "tricep|pushdown|push-down|kickback|extension|dip|skull"),
            row("팔뚝살 빼기 운동", "ko", "tricep|pushdown|push-down|kickback|extension|dip|skull"),
            row("get rid of arm flab", "en", "tricep|pushdown|push-down|kickback|extension|dip|skull"),
            row("広背筋をストレッチしたい", "ja", "lat|dead-hang|fixed-bar|side"),
            row("광배근 스트레칭", "ko", "lat|dead-hang|fixed-bar|side"),
            row("stretch for lats", "en", "lat|dead-hang|fixed-bar|side"),
            row("open up my chest", "en", "chest|pec"),
            row("背筋を強化", "ja", "back-extension|superman|hyperextension|good-morning|bird-dog|deadlift"),
            row("척추기립근 강화", "ko", "back-extension|superman|hyperextension|good-morning|bird-dog|deadlift"),
            row("strengthen my erectors", "en", "back-extension|superman|hyperextension|good-morning|bird-dog|deadlift"),
            row("ラットプルダウンマシンの使い方", "ja", "pull-?down"),
            row("ジムデビューのメニュー", "ja", "machine|treadmill|bodyweight-squat|plank|pulldown|cable"),
            row("헬스 입문자 루틴", "ko", "machine|treadmill|bodyweight-squat|plank|pulldown|cable"),
            row("what to do on my first day at the gym", "en", "machine|treadmill|bodyweight-squat|plank|pulldown|cable"),
            row("ダンベル1組でできる全身メニュー", "ja", "dumbbell.*(squat|row|press|deadlift|lunge|bridge|thrust|curl)"),
            row("덤벨 한 쌍으로 하는 전신 운동", "ko", "dumbbell.*(squat|row|press|deadlift|lunge|bridge|thrust|curl)"),
            row("full body workout with just dumbbells", "en", "dumbbell.*(squat|row|press|deadlift|lunge|bridge|thrust|curl)"),
            row("one kettlebell full body", "en", "kettlebell"),
        )


        @JvmStatic
        fun situationCases(): Stream<Arguments> = Stream.of(
            row("運動前にやる動き", "ja", "warm|circle|swing|jack|cat-cow|worlds|dynamic|band|march|high-knees|lunge|squat"),
            row("운동 전에 하는 동작", "ko", "warm|circle|swing|jack|cat-cow|worlds|dynamic|band|march|high-knees|lunge|squat"),
            row("movements before a workout", "en", "warm|circle|swing|jack|cat-cow|worlds|dynamic|band|march|high-knees|lunge|squat"),
            row("運動後にやる動き", "ja", "stretch|child|cat-cow|pose|couch|pigeon|roller|worlds"),
            row("운동 후에 하는 동작", "ko", "stretch|child|cat-cow|pose|couch|pigeon|roller|worlds"),
            row("what to do after a workout", "en", "stretch|child|cat-cow|pose|couch|pigeon|roller|worlds"),
            row("寝起きに体が固い", "ja", "cat-cow|child|stretch|worlds|pose|rotation"),
            row("일어나자마자 하는 스트레칭", "ko", "cat-cow|child|stretch|worlds|pose|rotation"),
            row("5 minutes before bed", "en", "stretch|cat-cow|child|pose|plank|bridge"),
            row("자기 전에 5분 운동", "ko", "stretch|cat-cow|child|pose|plank|bridge"),
            row("やる気が出ない日の運動", "ja", "walk|stretch|plank|bridge|squat|push-up|cat-cow|bike"),
            row("의욕 없는 날 할 운동", "ko", "walk|stretch|plank|bridge|squat|push-up|cat-cow|bike"),
            row("low motivation day workout", "en", "walk|stretch|plank|bridge|squat|push-up|cat-cow|bike"),
            row("引き締まった体になりたい", "ja", "squat|push-up|plank|lunge|burpee|row|deadlift|crunch|bridge"),
            row("get toned", "en", "squat|push-up|plank|lunge|burpee|row|deadlift|crunch|bridge"),
            row("懸垂バーがない時の背中", "ja", "row|pulldown|inverted|band|dumbbell|cable|machine"),
            row("no pull up bar back workout", "en", "row|pulldown|inverted|band|dumbbell|cable|machine"),
            row("体力をつけたい", "ja", "treadmill|bike|row|run|elliptical|stair|jump|walk|swim|burpee"),
            row("체력을 기르고 싶다", "ko", "treadmill|bike|row|run|elliptical|stair|jump|walk|swim|burpee"),
            row("軽い運動がしたい", "ja", "walk|stretch|bike|bridge|squat|cat-cow|child|plank|elliptical")
        )

        @JvmStatic
        fun exclusionCases(): Stream<Arguments> = Stream.of(
            Arguments.of("スクワットの代わりになる脚トレ", "squat"),
            Arguments.of("スクワットの代わりに", "squat"),
            Arguments.of("스쿼트 대신 할 수 있는 운동", "squat"),
            Arguments.of("스쿼트 대신", "squat"),
            Arguments.of("alternative to squats", "squat"),
            Arguments.of("what can I do instead of squats", "squat"),
            Arguments.of("squat substitute", "squat"),
            Arguments.of("ベンチプレスの代わり", "bench-press"),
            Arguments.of("벤치프레스 대체 운동", "bench-press"),
            Arguments.of("bench press alternative", "bench-press"),
            Arguments.of("alternatives to pull ups", "pull-up"),
            Arguments.of("懸垂の代わりになる背中トレ", "pull-up|chin-up"),
            Arguments.of("턱걸이 대신 할 수 있는 거", "pull-up|chin-up"),
            Arguments.of("デッドリフトの代用", "deadlift"),
            Arguments.of("데드리프트 말고 다른 운동", "deadlift"),
            Arguments.of("alternative to deadlifts", "deadlift"),
            Arguments.of("ランジの代わりになる種目", "lunge"),
            Arguments.of("워킹런지 대신", "walking-lunge"),
            Arguments.of("instead of lunges", "lunge"),
            Arguments.of("腹筋ローラーが無いときの代わり", "ab-wheel|rollout"),
            Arguments.of("ラットプルダウンの代わり", "lat-pull-down|lat-pulldown"),
            Arguments.of("substitute for lat pulldown", "lat-pull-down|lat-pulldown")
        )

        @JvmStatic
        fun symptomWords(): Stream<Arguments> = Stream.of(
            "硬い", "固い", "張る", "凝り", "凝って", "だるい", "こわばり", "むくみ", "がバキバキ", "ガチガチ", "がゴリゴリ",
            "땡겨요", "뭉쳤어", "결려요", "뻐근해요", "뻣뻣한", "굳었", "쥐가",
            "tight", "stiff", "knots", "achy", "relief", "tension", "sore"
        ).map { Arguments.of(it) }
    }

    // ═════════════════════════ 1. 자연어 질의 표 ═════════════════════════

    @ParameterizedTest(name = "[{1}] {0}")
    @MethodSource("naturalCases")
    fun `자연어 질의는 기대한 종류의 운동을 상위 5개에 보여 준다`(q: String, locale: String, anySlug: String) {
        val re = Regex(anySlug)
        val got = engine.search(q, null, null, locale).take(5).map { it.doc.slug }
        assertTrue(got.any { re.containsMatchIn(it) }, "$q -> $got (기대 /$anySlug/)")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exclusionCases")
    fun `대안 요청은 지목한 운동을 빼고 보여 준다`(q: String, forbidden: String) {
        val re = Regex(forbidden)
        val got = engine.search(q).take(10).map { it.doc.slug }
        assertTrue(got.isNotEmpty(), "$q -> 결과 없음")
        assertTrue(got.none { re.containsMatchIn(it) }, "$q -> $got 에 /$forbidden/ 이 있다")
    }

    // ═════════════════════════ 2. 설명문 검색 계층 ═════════════════════════

    private fun doc(id: Long, slug: String, ja: String, ko: String, en: String, instr: Map<String, String>, equipment: Equipment? = Equipment.MACHINE) = ExerciseSearchDoc(
        id = id, slug = slug, names = mapOf("ja" to ja, "ko" to ko, "en" to en), category = ExerciseCategory.LEGS, equipment = equipment,
        muscleGroups = setOf(MuscleGroup.HAMSTRINGS), movementPattern = null, tier = RecommendationTier.STANDARD, popularity = 50, difficulty = 40,
        descriptions = instr
    )

    private val tiny = listOf(
        doc(1, "lying-leg-curl-machine", "ライイング・レッグカールマシン", "라잉 레그 컬 머신", "Lying leg curl machine",
            mapOf("ja" to "うつ伏せになり、パッド付きレバーを足首のすぐ上に当てる。膝を曲げてかかとをお尻に向かって巻き上げる。", "ko" to "엎드려 누워 롤러 패드를 발목 위에 둔다. 무릎을 굽혀 다리를 감아올린다.", "en" to "Lie face down on the machine. Bend your knees and curl your heels toward your glutes.")),
        doc(2, "leg-extension-machine", "レッグエクステンションマシン", "레그 익스텐션 머신", "Leg extension machine",
            mapOf("ja" to "座ってパッドを足首に当て、膝を伸ばして脚をまっすぐにする。", "ko" to "앉아서 패드를 발목에 대고 무릎을 펴 다리를 곧게 한다.", "en" to "Sit on the machine and extend your knees until your legs are straight.")),
        doc(3, "lateral-raises-dumbbell", "ダンベルサイドレイズ", "덤벨 레터럴 레이즈", "Lateral raises dumbbell",
            mapOf("ja" to "両手にダンベルを持ち、肩の高さまで両腕を横に持ち上げる。", "ko" to "양손에 덤벨을 들고 어깨 높이까지 양팔을 옆으로 들어 올린다.", "en" to "Raise both arms out to the sides until they reach shoulder height."), Equipment.DUMBBELL),
        doc(4, "bodyweight-wall-squat", "自重ウォールスクワット", "맨몸 월 스쿼트", "Bodyweight wall squat",
            mapOf("ja" to "壁に背中をつけて立ち、膝を曲げてしゃがむ。", "ko" to "벽에 등을 대고 서서 무릎을 굽혀 앉는다.", "en" to "Stand with your back against a wall and bend your knees."), Equipment.BODYWEIGHT)
    )

    @Test
    fun `이름으로 못 찾는 동작 묘사 질의를 설명문으로 찾는다 - 일본어`() {
        val e = ExerciseSearchEngine(tiny)
        assertEquals("lying-leg-curl-machine", e.search("うつぶせで膝を曲げるマシン").first().doc.slug)
        assertEquals("leg-extension-machine", e.search("座って膝を伸ばすマシン").first().doc.slug)
        assertEquals("lateral-raises-dumbbell", e.search("横から腕を上げる運動").first().doc.slug)
    }

    @Test
    fun `이름으로 못 찾는 동작 묘사 질의를 설명문으로 찾는다 - 한국어와 영어`() {
        val e = ExerciseSearchEngine(tiny)
        assertEquals("lying-leg-curl-machine", e.search("엎드려서 무릎을 굽히는 머신").first().doc.slug)
        assertEquals("lateral-raises-dumbbell", e.search("팔을 옆으로 들어 올리기").first().doc.slug)
        assertEquals("lying-leg-curl-machine", e.search("lying face down and bend your knees").first().doc.slug)
        assertEquals("bodyweight-wall-squat", e.search("stand with your back against a wall and bend your knees").first().doc.slug)
    }

    @Test
    fun `설명문이 없는 문서 집합에서는 설명문 계층이 아무 일도 하지 않는다`() {
        val bare = tiny.map { it.copy(descriptions = emptyMap()) }
        val e = ExerciseSearchEngine(bare)
        assertTrue(e.search("うつぶせで膝を曲げるマシン").none { it.doc.slug == "lateral-raises-dumbbell" })
        // 이름 검색은 그대로
        assertEquals("leg-extension-machine", e.search("leg extension").first().doc.slug)
    }

    @Test
    fun `이름이 정확히 맞는 질의는 설명문이 밀어내지 못한다`() {
        val e = ExerciseSearchEngine(tiny)
        assertEquals("lateral-raises-dumbbell", e.search("lateral raises dumbbell").first().doc.slug)
        assertEquals("bodyweight-wall-squat", e.search("自重ウォールスクワット").first().doc.slug)
        val full = engine
        assertEquals("barbell-bench-press", full.search("barbell bench press").first().doc.slug)
        assertEquals("barbell-full-squat", full.search("barbell full squat").first().doc.slug)
    }

    @Test
    fun `설명문 검색은 필터와 부정 조건을 지킨다`() {
        val e = ExerciseSearchEngine(tiny)
        assertTrue(e.search("うつぶせで膝を曲げる", ExerciseCategory.LEGS, setOf(Equipment.DUMBBELL)).none { it.doc.equipment != Equipment.DUMBBELL })
        assertTrue(e.search("膝を曲げて立つ マシン以外").none { it.doc.equipment == Equipment.MACHINE })
    }

    @Test
    fun `설명문 계층의 질의 비용은 일정 범위 안이다`() {
        val queries = listOf("うつぶせで膝を曲げるマシン", "무릎 높이 올리며 제자리 뛰기", "raise both arms out to the sides", "横から腕を上げる運動", "엎드려서 다리를 구부리는 기구", "hang from a bar and raise your knees")
        queries.forEach { engine.search(it) }  // 워밍업
        val micros = ArrayList<Long>()
        repeat(20) { for (q in queries) { val e = ExerciseSearchEngine(docs.take(0).ifEmpty { docs }); val t = System.nanoTime(); e.search(q + " " + it); micros.add((System.nanoTime() - t) / 1000) } }
        micros.sort()
        assertTrue(micros[(micros.size * 0.9).toInt()] < 200_000, "p90 ${micros[(micros.size * 0.9).toInt()]}us")
    }

    @Test
    fun `묘사 질의는 부위 한 단어에 덮이지 않는다 - 횡에서 팔을 올리는 운동은 컬이 아니라 레이즈`() {
        val got = slugs("横から腕を上げる運動", 5)
        assertTrue(got.any { it.contains("lateral-raise") || it.contains("side-raise") }, "$got")
    }

    @Test
    fun `개념 토큰은 표기 변이와 언어를 가로질러 같다`() {
        fun c(s: String) = HashSet<String>().also { DescriptionLexicon.conceptsIn(DescriptionLexicon.canon(SearchText.fold(s)), it) }
        assertTrue("prone" in c("うつ伏せになり"))
        assertTrue("prone" in c("うつぶせで"))
        assertTrue("prone" in c("엎드려 누워"))
        assertTrue("prone" in c("lie face down"))
        assertTrue("bend" in c("膝を曲げる") && "bend" in c("무릎을 굽혀") && "bend" in c("bend your knees"))
        assertTrue("raise" in c("腕を上げる") && "raise" in c("팔을 올려") && "raise" in c("raise your arms"))
        assertTrue("sideways" in c("横から") && "sideways" in c("옆으로") && "sideways" in c("out to the sides"))
        assertTrue("supine" in c("仰向けに寝て") && "supine" in c("lie on your back"))
        assertTrue(c("今日はいい天気").none { it in DescriptionLexicon.MOTION_CONCEPTS })
    }

    // ═════════════════════════ 3. 증상 → 스트레칭 ═════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("symptomWords")
    fun `증상 표현은 풀어 달라는 신호다`(word: String) {
        val terms = QueryLexicon.termIndex[SearchText.strictKey(word)].orEmpty()
        assertTrue(terms.any { it.role == Role.STRETCH }, "$word 는 STRETCH 역할이 아니다: ${terms.map { it.role }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["太もも裏が硬い", "허벅지 뒤쪽이 땡겨요", "tight hamstrings", "햄스트링이 뻣뻣해요", "ハムストリングが硬い"])
    fun `햄스트링이 뻣뻣하면 레그 컬이 아니라 햄스트링 스트레칭이다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.count { it.contains("stretch") } >= 3, "$q -> $got")
        assertTrue(got.none { it.contains("leg-curl") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["stiff neck relief", "首が凝って辛い", "목이 뻣뻣해", "neck tension relief", "首のこりをほぐしたい"])
    fun `목이 뻣뻣하면 슈러그가 아니라 목 스트레칭이다`(q: String) {
        val got = slugs(q, 3)
        assertTrue(got.any { it.contains("neck") }, "$q -> $got")
        assertTrue(got.none { it.contains("shrug") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["근막 이완", "筋膜リリース", "foam rolling", "myofascial release", "폼롤러로 풀어주기", "フォームローラーで背中をゴロゴロ"])
    fun `근막 이완은 폼롤러 운동이다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.isNotEmpty() && got.all { it.startsWith("foam-roller") } || got.count { it.startsWith("foam-roller") } >= 3, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["肩甲骨はがし", "肩甲骨まわりを動かす", "어깨 돌리기", "shoulder rolls", "shoulder blade release", "腕回し"])
    fun `견갑골 풀기와 어깨 돌리기는 모빌리티이고 로우나 프레스가 아니다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.any { Regex("circle|scapula|thoracic|cat-cow|pull-apart|upward-rotation|wall-angel").containsMatchIn(it) }, "$q -> $got")
        assertTrue(got.none { it.contains("shoulder-press") || it.endsWith("-row") }, "$q -> $got")
    }

    @Test
    fun `운동을 물은 질의의 stiff 는 스트레칭으로 바꾸지 않는다`() {
        val got = slugs("sore knees leg workout", 5)
        assertTrue(got.none { it.contains("stretch") }, "$got")
    }

    // ═════════════════════════ 4. 생활 사물·장소 ═════════════════════════

    @ParameterizedTest
    @ValueSource(strings = ["椅子を使った自重トレ", "의자만 있으면 되는 운동", "chair only workout", "exercises using a chair", "椅子を使った運動"])
    fun `의자만 있으면 되는 운동은 의자 운동이고 케이블·머신이 아니다`(q: String) {
        val d = top(q, 5)
        assertTrue(d.count { it.slug.contains("chair") } >= 3, "$q -> ${d.map { it.slug }}")
        assertTrue(d.none { it.equipment == Equipment.CABLE || it.equipment == Equipment.MACHINE }, "$q -> ${d.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["壁を使ったスクワット", "벽 스쿼트", "wall squat", "wall sit", "벽을 이용한 스쿼트"])
    fun `벽 스쿼트는 월 스쿼트가 맨 위 근처다`(q: String) {
        val got = slugs(q, 3)
        assertTrue(got.any { it.contains("wall-squat") || it.contains("wall-sit") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["平行棒でできること", "평행봉 운동", "dip bars workout", "parallel bars exercises"])
    fun `평행봉은 딥이다`(q: String) {
        assertTrue(slugs(q, 5).any { it.contains("dip") }, q)
    }

    @ParameterizedTest
    @ValueSource(strings = ["원룸에서 할 수 있는 하체", "ワンルームでできる下半身運動", "workout in a small apartment", "ホテルでできる筋トレ", "hotel room workout"])
    fun `원룸·호텔 같은 장소는 집 의도라 바벨·머신·케이블이 상위에 없다`(q: String) {
        val d = top(q, 5)
        if (d.isEmpty()) return
        assertTrue(d.none { it.equipment == Equipment.BARBELL || it.equipment == Equipment.MACHINE || it.equipment == Equipment.CABLE }, "$q -> ${d.map { it.slug + "/" + it.equipment }}")
    }

    @Test
    fun `생활 사물 별칭은 이름 검색에서 카탈로그 표기로 연결된다`() {
        assertTrue(slugs("壁 スクワット").any { it.contains("wall-squat") })
        assertTrue(slugs("의자 딥").any { it.contains("chair") && it.contains("dip") })
        assertTrue(slugs("椅子 ステップアップ").any { it.contains("step-up") && it.contains("chair") })
    }

    // ═════════════════════════ 5. 강도·활동 표현, 계열 ═════════════════════════

    @ParameterizedTest
    @ValueSource(strings = ["息が上がるやつ", "숨차게 하는 운동", "workout that makes me out of breath", "ハアハアするやつ", "숨이 턱까지 차는 운동"])
    fun `숨이 차는 운동은 고강도 유산소 동작이다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.count { Regex("burpee|mountain|jumping-jack|high-knees|jump|swing|battle|sprint|air-bike|butt-kicks|fast-feet").containsMatchIn(it) } >= 2, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["30分歩きたい", "20분 걷고 싶어요", "I want to go for a walk", "walk for 30 minutes", "散歩がしたい", "ウォーキング"])
    fun `걷고 싶다는 질의는 걷기 운동이다`(q: String) {
        val got = slugs(q, 3)
        assertTrue(got.any { it.contains("walk") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["傾斜をつけて歩く", "경사 걷기", "incline walk", "walk on an incline"])
    fun `경사를 주고 걷는 것은 인클라인 걷기다`(q: String) {
        assertEquals("incline-treadmill-walk", slugs(q, 1).first(), q)
    }

    @ParameterizedTest
    @ValueSource(strings = ["泳ぎたい", "수영하고 싶어요", "I want to swim", "プールで泳ぎたい", "swim laps"])
    fun `헤엄치고 싶다는 질의는 수영이다`(q: String) {
        assertEquals("swimming", slugs(q, 1).first(), q)
    }

    @ParameterizedTest
    @ValueSource(strings = ["get my heart rate up", "심박수 올리는 운동", "心拍数を上げたい", "汗をかける運動", "땀 나는 운동", "get sweaty"])
    fun `심박수를 올리고 땀을 내는 질의는 유산소다`(q: String) {
        val d = top(q, 5)
        assertTrue(d.isNotEmpty() && d.count { it.category == ExerciseCategory.CARDIO } >= 3, "$q -> ${d.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ランジ系の種目", "런지 계열 운동", "lunge variations", "types of lunges", "ランジの種類"])
    fun `계열 접미사는 군더더기라서 그 계열 전체가 나온다`(q: String) {
        val d = top(q, 5)
        assertTrue(d.size == 5 && d.all { it.slug.contains("lunge") || it.slug.contains("split-squat") }, "$q -> ${d.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["スクワット系の種目", "푸시업 종류", "plank variations", "types of squats", "플랭크 종류"])
    fun `다른 동작의 계열 질의도 그 동작 이름들이다`(q: String) {
        val keys = listOf("squat", "push-up", "plank")
        val d = top(q, 5)
        assertTrue(d.size == 5 && d.all { x -> keys.any { x.slug.contains(it) } }, "$q -> ${d.map { it.slug }}")
    }

    // ═════════════════════════ 6. 세부 부위·속어·초보 ═════════════════════════

    @ParameterizedTest
    @ValueSource(strings = ["뒷어깨 운동", "後ろ肩を鍛えたい", "rear delt exercises", "rear shoulder work", "肩の後ろ側のトレーニング", "back of the shoulders"])
    fun `뒷어깨는 후면 삼각근 운동이 앞·옆 삼각근 운동보다 앞선다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.count { Regex("rear|reverse|face-pull").containsMatchIn(it) } >= 4, "$q -> $got")
        assertTrue(got.none { it.contains("front-raise") || it.contains("shoulder-press") || (it.contains("lateral-raise") && !it.contains("rear")) }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["振り袖を何とかしたい", "二の腕のたるみ解消", "팔뚝살 빼는 운동", "bat wings", "triceps flab"])
    fun `팔뚝살은 삼두 운동이다`(q: String) {
        val d = top(q, 5)
        assertTrue(d.isNotEmpty() && d.count { MuscleGroup.TRICEPS in it.muscleGroups } >= 4, "$q -> ${d.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["広背筋のストレッチ", "광배 스트레칭", "lat stretch", "stretch my lats", "광배근 스트레칭"])
    fun `광배 스트레칭은 랫 스트레칭이다`(q: String) {
        val got = slugs(q, 3)
        assertTrue(got.any { it.contains("lat") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["chest opener", "胸を開くストレッチ", "가슴 열어주는 스트레칭", "doorway chest stretch", "opening up my chest"])
    fun `가슴 열기는 가슴 스트레칭이다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.count { it.contains("chest") || it.contains("pec") } >= 2, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["背筋を鍛える", "背筋トレ", "척추기립근 운동", "lower back strengthening", "erector spinae exercises"])
    fun `배근은 백 익스텐션 계열이고 랫 로우가 아니다`(q: String) {
        val got = slugs(q, 5)
        assertTrue(got.any { Regex("back-extension|hyperextension|superman|good-morning").containsMatchIn(it) }, "$q -> $got")
        assertTrue(got.none { it.contains("pulldown") || it.contains("seated-row") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ラットマシン", "랫 머신", "lat machine", "ラットマシンで背中"])
    fun `랫 머신은 랫 풀다운이다`(q: String) {
        assertTrue(slugs(q, 3).any { it.contains("pull-down") || it.contains("pulldown") }, q)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ジム初心者の最初の一日", "헬스 처음 갔을 때 뭐 하지", "how to start going to the gym", "first day at the gym what should I do", "ジムデビューのメニュー"])
    fun `첫 헬스장 방문은 머신과 기본 복합 동작의 초보 세트다`(q: String) {
        val d = top(q, 5)
        assertTrue(d.size == 5 && d.count { it.equipment == Equipment.MACHINE || it.slug.contains("bodyweight-squat") || it.slug.contains("pulldown") } >= 3, "$q -> ${d.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ダンベル1組で全身", "full body with one pair of dumbbells", "덤벨 한 쌍으로 전신 운동", "full body dumbbell workout"])
    fun `덤벨 한 쌍 전신은 덤벨 복합 동작이다`(q: String) {
        val d = top(q, 6)
        assertTrue(d.all { it.equipment == Equipment.DUMBBELL }, "$q -> ${d.map { it.slug + "/" + it.equipment }}")
        assertTrue(d.count { Regex("squat|row|press|deadlift|lunge|bridge|thrust|curl").containsMatchIn(it.slug) } >= 4, "$q -> ${d.map { it.slug }}")
    }

    @Test
    fun `이름이 있는 질의에 붙은 목표 표현은 이름 검색을 흐리지 않는다`() {
        assertTrue(slugs("lateral raises for boulder shoulders", 3).any { it.contains("lateral-raise") })
    }

    @Test
    fun `스쿼트 랙이 없으면 바벨이 아닌 스쿼트류다`() {
        val d = top("no squat rack", 8)
        assertTrue(d.none { it.equipment == Equipment.BARBELL }, d.map { it.slug }.toString())
        assertTrue(d.count { it.slug.contains("squat") } >= 4, d.map { it.slug }.toString())
        val ja = top("スクワットラックがない", 8)
        assertTrue(ja.none { it.equipment == Equipment.BARBELL }, ja.map { it.slug }.toString())
    }

    @Test
    fun `대안을 물은 대상이 목표 개념이면 그 개념 운동이 대안이다`() {
        val d = top("pallof press alternative for anti rotation", 5)
        assertTrue(d.any { it.category == ExerciseCategory.CORE }, d.map { it.slug }.toString())
        assertTrue(d.none { it.slug.contains("bench-press") }, d.map { it.slug }.toString())
    }

    @Test
    fun `시간만 말한 수치는 이름 검색이 아니다`() {
        assertTrue(engine.search("朝5分でできる運動").isNotEmpty())
        assertTrue(engine.search("10 minute workout").isNotEmpty())
    }


    // ═════════════════════════ 8. 상황 표현·대안 해석 보강 ═════════════════════════

    @ParameterizedTest(name = "[{1}] {0}")
    @MethodSource("situationCases")
    fun `운동 전·후·기상·자기 전 같은 상황 표현은 그에 맞는 목록이다`(q: String, locale: String, anySlug: String) {
        val re = Regex(anySlug)
        val got = engine.search(q, null, null, locale).take(5).map { it.doc.slug }
        assertTrue(got.any { re.containsMatchIn(it) }, "$q -> $got (기대 /$anySlug/)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["데드리프트 외에 등 운동", "벤치프레스 이외 가슴", "スクワット以外で脚を鍛えたい", "other than squats leg exercises", "chest exercises besides bench press"])
    fun `외에·이외·以外·besides 는 그 운동을 빼고 같은 부위를 보여 준다`(q: String) {
        val got = engine.search(q).take(8).map { it.doc.slug }
        val named = Regex("deadlift|bench-press|squat")
        assertTrue(got.isNotEmpty() && got.none { named.containsMatchIn(it) && !it.contains("press-machine") }, "$q -> $got")
    }

    @Test
    fun `대안을 물은 대상 앞에 부상 표현이 끼어도 그 운동이 대상이다`() {
        val got = slugs("스쿼트 하면 무릎 아픈데 대체 운동", 8)
        assertTrue(got.none { it.contains("squat") }, got.toString())
        assertTrue(got.isNotEmpty())
    }

    @Test
    fun `앞에 대상이 있는 alternatives for 는 상황이 뒤에 와도 앞의 운동을 뺀다`() {
        val got = slugs("pull up alternatives for home", 8)
        assertTrue(got.none { it.contains("pull-up") }, got.toString())
    }

    @Test
    fun `자세 개념은 부위 목표의 가산점이라 서서 하는 유산소가 standing 이름 운동 전체로 번지지 않는다`() {
        val d = top("立ったままできる有酸素", 5)
        assertTrue(d.any { it.category == ExerciseCategory.CARDIO }, d.map { it.slug }.toString())
    }

    @Test
    fun `자세 개념은 구체적인 부위가 함께 있으면 그 부위가 목표다`() {
        val got = slugs("座りっぱなしでお尻が痛い", 5)
        assertTrue(got.none { it.contains("rear-delt") || it.contains("face-pull") }, got.toString())
    }

    @Test
    fun `부상 부정형은 단서라서 아픈 관절 운동을 피한다`() {
        val got = slugs("肩を痛めずに胸", 3)
        assertTrue(got.none { it.contains("lateral-raise") || it.contains("shoulder-press") }, got.toString())
    }

    @Test
    fun `무릎이 안 좋으면 유산소는 달리기보다 자전거·걷기가 앞이다`() {
        val got = slugs("膝に優しい有酸素", 3)
        assertTrue(got.none { it.contains("running") }, got.toString())
    }

    @Test
    fun `짐볼 별칭은 카탈로그의 exercise ball 운동으로 연결된다`() {
        assertTrue(slugs("짐볼 복근", 5).any { it.contains("ball") })
        assertTrue(slugs("バランスボール 腹筋", 5).any { it.contains("ball") })
    }

    // ═════════════════════════ 7. 색인 서비스: 설명문 로딩 ═════════════════════════

    private fun exercise(id: Long, slug: String, name: String) = Exercise(
        id = id, slug = slug, name = name, category = ExerciseCategory.LEGS, equipment = Equipment.MACHINE,
        muscleGroups = mutableSetOf(MuscleGroup.HAMSTRINGS), popularity = 50, recommendationTier = RecommendationTier.STANDARD
    )

    private fun service(descriptionRepo: ExerciseDescriptionRepository?): ExerciseSearchIndexService {
        val exerciseRepository = mockk<ExerciseRepository>()
        val translationRepository = mockk<ExerciseTranslationRepository>()
        every { exerciseRepository.findAllWithMuscleGroups() } returns listOf(
            exercise(1, "lying-leg-curl-machine", "Lying leg curl machine"),
            exercise(2, "leg-extension-machine", "Leg extension machine")
        )
        every { translationRepository.findAllNameRows() } returns listOf(
            arrayOf<Any>(1L, "ja", "ライイング・レッグカールマシン"), arrayOf<Any>(2L, "ja", "レッグエクステンションマシン")
        )
        return ExerciseSearchIndexService(exerciseRepository, translationRepository, null, descriptionRepo)
    }

    @Test
    fun `색인 서비스는 DB 의 설명문으로 묘사 질의를 찾는다`() {
        val repo = mockk<ExerciseDescriptionRepository>()
        every { repo.findAllDescriptionRows() } returns listOf(
            arrayOf<Any?>(1L, "ja", "1. うつ伏せになり、パッドを足首に当てる。\n2. 膝を曲げてかかとをお尻に向かって巻き上げる。", "反動を使わない。"),
            arrayOf<Any?>(2L, "ja", "1. 座ってパッドを足首に当てる。\n2. 膝を伸ばして脚をまっすぐにする。", null)
        )
        val ids = service(repo).searchIds("うつぶせで膝を曲げる", null, null, "ja")
        assertEquals(1L, ids.first())
        assertEquals(2L, service(repo).searchIds("座って膝を伸ばす", null, null, "ja").first())
    }

    @Test
    fun `설명문을 못 읽어도 이름 검색은 그대로다`() {
        val repo = mockk<ExerciseDescriptionRepository>()
        every { repo.findAllDescriptionRows() } throws IllegalStateException("db down")
        val svc = service(repo)
        assertEquals(listOf(2L), svc.searchIds("レッグエクステンション", null, null, "ja"))
        assertEquals(listOf(1L, 2L).toSet(), svc.searchIds(null, ExerciseCategory.LEGS, null, "ja").toSet())
        // 설명문 저장소가 없는(테스트·구성 누락) 경우도 마찬가지
        assertEquals(listOf(2L), service(null).searchIds("レッグエクステンション", null, null, "ja"))
    }

    @Test
    fun `설명문 문서 필드는 기본값이 비어 있어 기존 생성 호출이 그대로 동작한다`() {
        val d = ExerciseSearchDoc(1, "x", mapOf("en" to "X"), ExerciseCategory.LEGS, null, emptySet(), null, RecommendationTier.STANDARD, 1, 1)
        assertTrue(d.descriptions.isEmpty())
        assertFalse(ExerciseSearchEngine(listOf(d)).search("x").isEmpty())
    }
}

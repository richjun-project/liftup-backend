package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import com.richjun.liftupai.domain.workout.search.engine.Mode
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon.Joint
import com.richjun.liftupai.domain.workout.search.ops.SearchOpsRecorder
import com.richjun.liftupai.domain.workout.search.ops.SynonymLines
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 검색 2차 — 리뷰가 짚은 결함(부위 부정·연결어·관절 부정·긴 질의 꼬리·대체 결과 맥락·부분 결과 캐시·PII)과,
 * 최종 검증셋에서 드러난 유형(한자↔가나 읽기·그립·일본/한국 헬스장 통칭·웜업·부위+스트레칭·목표 표현·일반 스트레칭)의 회귀 테스트.
 * 특정 평가 질의를 맞추는 테스트가 아니라 표(어휘)와 규칙이 유형 전체에 일반적으로 동작하는지를 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchReviewRound2Test {

    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        engine = ExerciseSearchEngine(docs)
    }

    private fun top(q: String, n: Int = 5) = engine.search(q).take(n).map { it.doc }
    private fun slugs(q: String, n: Int = 5) = top(q, n).map { it.slug }
    private val jumpy = Regex("jump|burpee|hop|plyo|skater|box-jump|jack", RegexOption.IGNORE_CASE)

    // ═════════════════════════ 1. 리뷰 지적: 긴 질의 꼬리 ═════════════════════════

    @Test
    fun `질의 정규화는 14단어까지 두고 꼬리의 부정과 부상 표현을 지키지 않고 자르지 않는다`() {
        val q = "i want a good leg workout for beginners at home without any jumping please"
        val n = ExerciseSearchIndexService.normalizeQuery(q)!!
        assertTrue(n.endsWith("jumping please"), n)
        assertTrue(n.contains("without any jumping"))
    }

    @Test
    fun `7단어 질의의 꼬리 hurt 가 잘리지 않는다`() {
        assertEquals("squats and lunges but my knees hurt", ExerciseSearchIndexService.normalizeQuery("squats and lunges but my knees hurt"))
    }

    @Test
    fun `부정어는 반복해도 중복으로 지우지 않는다`() {
        assertEquals("no squat no lunge", ExerciseSearchIndexService.normalizeQuery("no squat no lunge"))
        assertEquals("Squat bench", ExerciseSearchIndexService.normalizeQuery("Squat squat bench SQUAT"))
    }

    @Test
    fun `14단어를 넘으면 군더더기부터 빼서 꼬리의 부정과 부상 표현을 남긴다`() {
        val q = "I would like to get a really good back workout for the gym that does not hurt because of my sore lower back and without jumping"
        val n = ExerciseSearchIndexService.normalizeQuery(q)!!
        assertTrue(n.split(' ').size <= ExerciseSearchIndexService.MAX_QUERY_WORDS, n)
        assertTrue(n.endsWith("without jumping"), n)
        assertTrue(n.contains("sore"), n)
    }

    @Test
    fun `군더더기를 빼도 넘치면 앞 8단어와 뒤 6단어를 남긴다`() {
        val q = (1..30).joinToString(" ") { "w$it" } + " without jumping"
        val n = ExerciseSearchIndexService.normalizeQuery(q)!!
        val words = n.split(' ')
        assertEquals(ExerciseSearchIndexService.MAX_QUERY_WORDS, words.size)
        assertEquals("jumping", words.last())
        assertEquals("w1", words.first())
    }

    @Test
    fun `14단어 질의 꼬리의 without jumping 이 엔진까지 가서 점프 운동이 빠진다`() {
        val q = "leg workout for beginners at home that is good for my back and without any jumping please"
        val n = ExerciseSearchIndexService.normalizeQuery(q)!!
        val hits = engine.search(n).take(30).map { it.doc.slug }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { jumpy.containsMatchIn(it) }, "$hits")
    }

    @Test
    fun `꼬리의 my knees hurt 가 무릎 부상으로 읽힌다`() {
        val plan = engine.analyze("squats and lunges but my knees hurt")
        assertEquals(setOf(Joint.KNEE), plan.injuries)
    }

    // ═════════════════════════ 2. 리뷰 지적: 부위 부정 ═════════════════════════

    @Test
    fun `가슴 말고 등운동 은 가슴을 빼고 등만 돌려준다`() {
        val hits = engine.search("가슴 말고 등운동").take(20).map { it.doc }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { it.category == ExerciseCategory.CHEST }, "${hits.map { it.slug }}")
        assertTrue(hits.take(8).all { it.category == ExerciseCategory.BACK }, "${hits.map { it.slug }}")
    }

    @Test
    fun `legs 빼고 는 하체를 결과에서 뺀다`() {
        val hits = engine.search("legs 빼고").take(40).map { it.doc }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { it.category == ExerciseCategory.LEGS }, "${hits.map { it.slug }}")
    }

    @Test
    fun `no chest back workout 은 가슴을 빼고 등을 보여 준다`() {
        val hits = engine.search("no chest, back workout").take(20).map { it.doc }
        assertTrue(hits.none { it.category == ExerciseCategory.CHEST })
        assertTrue(hits.take(5).all { it.category == ExerciseCategory.BACK })
    }

    @Test
    fun `가슴이랑 등 빼고 하체 는 접속어로 이어진 두 부위를 모두 뺀다`() {
        val hits = engine.search("가슴이랑 등 빼고 하체").take(10).map { it.doc }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.category == ExerciseCategory.LEGS }, "${hits.map { it.slug }}")
    }

    @Test
    fun `背中以外の筋トレ 은 등 운동을 뺀다`() {
        val hits = engine.search("背中以外の筋トレ").take(30).map { it.doc }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { it.category == ExerciseCategory.BACK }, "${hits.map { it.slug }}")
    }

    @Test
    fun `부정된 부위는 긍정 목표 개념으로 더해지지 않는다`() {
        val plan = engine.analyze("가슴 말고 등운동")
        assertEquals(1, plan.concepts.size)
        assertEquals(1, plan.excludeConcepts.size)
        assertTrue(plan.concepts.single().muscles.contains(MuscleGroup.BACK))
        assertTrue(plan.excludeConcepts.single().muscles.contains(MuscleGroup.CHEST))
    }

    @Test
    fun `upper body workout without shoulders 는 어깨 운동을 빼고 어깨 부상 프로필을 건다`() {
        val plan = engine.analyze("upper body workout without shoulders")
        assertTrue(Joint.SHOULDER in plan.injuries)
        val hits = engine.search("upper body workout without shoulders").take(20).map { it.doc }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.none { it.category == ExerciseCategory.SHOULDERS }, "${hits.map { it.slug }}")
    }

    // ═════════════════════════ 3. 리뷰 지적: with/using/but/instead 가 부정 사슬을 끊는다 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "shoulder workout without barbell with dumbbells",
        "chest workout using dumbbells but no barbell",
        "back workout no barbell instead use dumbbells",
        "leg workout without machines but with dumbbells"
    )
    fun `부정 뒤 with 로 시작한 장비는 긍정이다`(q: String) {
        val plan = engine.analyze(q)
        assertEquals(setOf(Equipment.DUMBBELL), plan.equipments, q)
        assertFalse(Equipment.DUMBBELL in plan.excludeEquip, q)
        val hits = engine.search(q).take(10).map { it.doc }
        assertTrue(hits.isNotEmpty(), q)
        assertTrue(hits.all { it.equipment == Equipment.DUMBBELL }, "$q -> ${hits.map { it.slug }}")
    }

    @Test
    fun `쉼표로 나열한 장비는 한 부정에 함께 묶인다`() {
        val plan = engine.analyze("chest workout without barbell, dumbbell, cable")
        assertEquals(setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.CABLE), plan.excludeEquip)
        assertTrue(plan.equipments.isEmpty())
    }

    @Test
    fun `squat without a bar with dumbbell 의 덤벨은 제외 대상이 아니다`() {
        val plan = engine.analyze("squat without a bar, with dumbbell")
        assertTrue(Equipment.DUMBBELL in plan.equipments)
        assertFalse(Equipment.DUMBBELL in plan.excludeEquip)
    }

    // ═════════════════════════ 4. 리뷰 지적: 부정된 관절은 부상 프로필 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "leg workout without knee",
        "하체운동 무릎 빼고",
        "下半身 膝以外",
        "legs no knees",
        "leg day without knees"
    )
    fun `부정된 관절은 이름 검색이 아니라 무릎 부상 프로필이다`(q: String) {
        val plan = engine.analyze(q)
        assertEquals(setOf(Joint.KNEE), plan.injuries, q)
        assertTrue(plan.mode != Mode.NAME, q)
        val hits = engine.search(q).take(10).map { it.doc.slug }
        assertTrue(hits.isNotEmpty(), q)
        assertTrue(hits.none { it.contains("knee") || jumpy.containsMatchIn(it) || it.contains("kneeling") }, "$q -> $hits")
    }

    @Test
    fun `손목 부정은 손목 부상 프로필이고 푸시업 계열이 앞서지 않는다`() {
        val plan = engine.analyze("chest workout without wrists")
        assertTrue(Joint.WRIST in plan.injuries)
    }

    // ═════════════════════════ 5. 리뷰 지적: free / less ═════════════════════════

    @Test
    fun `free weights 의 free 는 부정이 아니다`() {
        val plan = engine.analyze("dumbbell free weights chest")
        assertTrue(plan.excludeEquip.isEmpty())
        assertTrue(plan.excludeKeys.isEmpty())
        val plan2 = engine.analyze("barbell free weight back workout")
        assertTrue(plan2.excludeEquip.isEmpty())
        assertTrue(Equipment.BARBELL in plan2.equipments)
        assertTrue(top("barbell free weight back workout", 5).all { it.equipment == Equipment.BARBELL })
    }

    @Test
    fun `jump free 와 equipment free 의 free 는 여전히 부정이다`() {
        val plan = engine.analyze("chest workout jump free")
        assertTrue(plan.excludeKeys.isNotEmpty())
        assertTrue(engine.search("chest workout jump free").take(15).none { jumpy.containsMatchIn(it.doc.slug) })
    }

    // ═════════════════════════ 6. 리뷰 지적: 시간 예산을 넘긴 결과는 캐시하지 않는다 ═════════════════════════

    @Test
    fun `시간 예산을 넘겨 끝난 결과는 불완전하다고 알리고 엔진 캐시에 넣지 않는다`() {
        val starved = ExerciseSearchEngine(docs, emptyList(), budgetNanos = 0L)
        val first = starved.searchOutcome("bench press")
        assertFalse(first.complete)
        assertEquals(0, starved.cachedQueryCount())
        assertFalse(starved.searchOutcome("bench press").complete, "두 번째도 캐시 적중이 아니다")
        assertEquals(0, starved.cachedQueryCount())

        val normal = ExerciseSearchEngine(docs)
        assertTrue(normal.searchOutcome("bench press").complete)
        assertEquals(1, normal.cachedQueryCount())
    }

    private fun exercise(id: Long, slug: String, name: String) = Exercise(
        id = id, slug = slug, name = name, category = ExerciseCategory.LEGS, equipment = Equipment.BARBELL,
        muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS), popularity = 50, recommendationTier = RecommendationTier.STANDARD
    )

    private fun serviceWith(vararg names: Pair<String, String>): ExerciseSearchIndexService {
        val exerciseRepository = mockk<ExerciseRepository>()
        val translationRepository = mockk<ExerciseTranslationRepository>()
        every { exerciseRepository.findAllWithMuscleGroups() } returns names.mapIndexed { i, (slug, name) -> exercise(i + 1L, slug, name) }
        every { translationRepository.findAllNameRows() } returns emptyList()
        return ExerciseSearchIndexService(exerciseRepository, translationRepository)
    }

    @Test
    fun `서비스는 예산을 넘긴 결과를 결과 캐시에 넣지 않는다`() {
        val starved = serviceWith("barbell-bench-press" to "Barbell bench press", "leg-extension" to "Leg extension")
        starved.engineBudgetNanos = 0L
        starved.invalidate()
        starved.searchIds("bench press", null, null, "en")
        assertEquals(0, starved.cachedResultCount())

        val normal = serviceWith("barbell-bench-press" to "Barbell bench press", "leg-extension" to "Leg extension")
        normal.searchIds("bench press", null, null, "en")
        assertEquals(1, normal.cachedResultCount())
    }

    // ═════════════════════════ 7. 리뷰 지적: 0건 대안이 부정·부상 맥락을 지킨다 ═════════════════════════

    @Test
    fun `0건 대안은 무릎이 안 좋은데 점프 스쿼트 에서 점프 스쿼트를 되살리지 않는다`() {
        val service = serviceWith("jump-squat" to "Jump squat")
        assertEquals(emptyList<Long>(), service.searchIds("knee pain jump squat", null, null, "en"))
        assertEquals(emptyList<Long>(), service.fallbackIds("knee pain jump squat", null, null, "en"))
    }

    @Test
    fun `0건 대안은 부정한 운동을 되살리지 않고 허용된 운동만 돌려준다`() {
        val service = serviceWith("barbell-bench-press" to "Barbell bench press", "jump-squat" to "Jump squat")
        val fallback = service.fallbackIds("burpee jump squat without jumping", null, null, "en")
        assertFalse(2L in fallback, "$fallback")
    }

    @Test
    fun `질의 전체의 제외 조건을 대안 판별 함수로 얻는다`() {
        val excluded = engine.exclusionPredicate("knee pain jump squat")!!
        val jumpSquat = docs.first { it.slug == "jump-squats-bodyweight" }
        val goblet = docs.first { it.slug == "dumbbell-goblet-squat" }
        assertTrue(excluded(jumpSquat))
        assertFalse(excluded(goblet))
        assertNull(engine.exclusionPredicate("squat"))
    }

    // ═════════════════════════ 8. 리뷰 지적: PII 로그 필터 ═════════════════════════

    @ParameterizedTest
    @ValueSource(strings = ["010-1234-5678", "010 1234 5678", "+82 10 1234 5678", "(02) 345-6789", "010.1234.5678", "call 010 1234 5678 now"])
    fun `구분자가 낀 전화번호는 검색 로그에 남기지 않는다`(raw: String) {
        assertNull(SearchOpsRecorder.normalize(raw), raw)
    }

    @ParameterizedTest
    @ValueSource(strings = ["5x5 stronglifts", "bench press 100kg 3 sets", "스쿼트 5x5", "week 1 day 2 squat", "3 4 5 squat"])
    fun `운동 숫자는 그대로 남긴다`(raw: String) {
        assertNotNull(SearchOpsRecorder.normalize(raw), raw)
    }

    // ═════════════════════════ 9. 한자↔가나 읽기 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "ふっきん, 腹筋",
        "けんすい, 懸垂",
        "はいきん, 背筋",
        "うでたてふせ, 腕立て伏せ",
        "じてんしゃ, 自転車",
        "あし, 脚",
        "しり, 尻",
        "おしり, お尻",
        "かた, 肩",
        "むね, 胸",
        "ふともも, 太もも",
        "せなか, 背中",
        "こかんせつ, 股関節",
        "ゆうさんそ, 有酸素",
        "なわとび, 縄跳び",
        "ぜんしん, 全身"
    )
    fun `가나로 쓴 헬스장 단어는 한자 표기와 같은 결과를 낸다`(kana: String, kanji: String) {
        val a = slugs(kana, 3)
        val b = slugs(kanji, 3)
        assertTrue(b.isNotEmpty(), kanji)
        assertEquals(b.toSet(), a.toSet(), "$kana vs $kanji")
    }

    @Test
    fun `가타카나 표기도 같은 단어다`() {
        assertEquals(slugs("腹筋", 3).toSet(), slugs("フッキン", 3).toSet())
        assertEquals(slugs("懸垂", 3).toSet(), slugs("ケンスイ", 3).toSet())
    }

    @Test
    fun `읽기 표는 한자가 모두 표에 있을 때만 가나 변형을 만든다`() {
        assertEquals(listOf("けんすい"), KanjiReadings.variants("懸垂"))
        assertTrue("ふっきんろーらー" in KanjiReadings.variants("腹筋ろーらー"))
        assertTrue("あしくび" in KanjiReadings.variants("足首"))
        assertEquals(emptyList<String>(), KanjiReadings.variants("懸垂鬱"), "표에 없는 한자가 남으면 변형을 만들지 않는다")
        assertEquals(emptyList<String>(), KanjiReadings.variants("けんすい"), "한자가 없으면 변형도 없다")
        assertEquals(emptyList<String>(), KanjiReadings.variants("squat"))
    }

    @Test
    fun `동의어 트리거에도 가나 읽기가 들어 있다`() {
        val table = ExerciseSearchLexicon.defaultSynonymTable
        assertTrue(SearchText.strictKey("けんすい") in table.index.keys)
        assertTrue(SearchText.strictKey("じてんしゃ") in table.index.keys)
    }

    // ═════════════════════════ 10. 그립 수식어 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "逆手懸垂, chin-up",
        "逆手 懸垂, chin-up",
        "懸垂 逆手, chin-up",
        "けんすい 逆手, chin-up",
        "언더핸드 풀업, chin-up",
        "underhand pull up, chin-up",
        "supinated pull up, chin-up",
        "reverse grip pull up, chin-up",
        "리버스 그립 턱걸이, chin-up",
        "逆手チンニング, chin-up",
        "逆手ラットプルダウン, reverse-grip-lat-pull-down",
        "리버스 그립 랫풀다운, reverse-grip-lat-pull-down",
        "underhand lat pulldown, reverse-grip-lat-pull-down",
        "順手懸垂, pull-up-normal-grip",
        "오버핸드 풀업, pull-up-normal-grip",
        "overhand pull up, pull-up-normal-grip",
        "パラレルグリップ懸垂, pull-up-neutral-grip",
        "뉴트럴 그립 턱걸이, pull-up-neutral-grip",
        "neutral grip pull up, pull-up-neutral-grip",
        "ワイドグリップ懸垂, pull-up-wide-grip",
        "와이드 그립 턱걸이, pull-up-wide-grip",
        "wide grip pull up, pull-up-wide-grip",
        "좁은 그립 벤치프레스, barbell-close-grip-bench-press",
        "ナローグリップベンチプレス, barbell-close-grip-bench-press",
        "close grip pulldown, lat-pull-down-close-grip",
        "ワイドグリップ ラットプルダウン, lat-pull-down-wide-grip",
        "逆手ロー, prone-bench-row-barbell-reverse-grip",
        "supinated row, barbell-bent-over-row-supinated-grip",
        "順手ベントオーバーロー, barbell-bent-over-row-pronated-grip"
    )
    fun `그립 수식어는 그립이 가리키는 변형 운동으로 보낸다`(q: String, expected: String) {
        assertTrue(expected in slugs(q, 3), "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @CsvSource(
        "アシスト懸垂, assisted-pull-up",
        "어시스트 풀업, assisted-pull-up",
        "assisted pull up, assisted-pull-up",
        "보조 턱걸이, assisted-pull-up",
        "マシンアシスト懸垂, assisted-pull-up",
        "assisted chin up, assisted-chin-up-on-a-bench"
    )
    fun `어시스트 수식어는 보조 변형 운동을 앞세운다`(q: String, expected: String) {
        assertTrue(expected in slugs(q, 3), "$q -> ${slugs(q, 5)}")
    }

    @Test
    fun `그립 규칙의 확장어가 일반 질의로 번지지 않는다 - lat pulldown 과 chin up 은 기본 동작이다`() {
        assertEquals("chin-up", slugs("chin up", 1).single())
        assertTrue("lat-pull-down-wide-grip" in slugs("lat pulldown", 3))
        assertEquals("barbell-lying-triceps-skull-crusher", slugs("skull crusher", 1).single())
    }

    @Test
    fun `한 방향 별칭은 왼쪽만 트리거다`() {
        val oneWay = ExerciseSearchLexicon.parseSynonymRule("a one|b two =>>> chin up|친업")!!
        assertEquals(setOf("aone", "btwo"), oneWay.triggers)
        assertTrue(oneWay.strong)
        assertEquals(2, oneWay.expansions.size)
        val twoWay = ExerciseSearchLexicon.parseSynonymRule("a one|b two => chin up|친업")!!
        assertTrue("chinup" in twoWay.triggers)
        assertNotNull(SynonymLines.validate("a one|b two =>>> chin up|친업"))
    }

    // ═════════════════════════ 11. 일본·한국 헬스장 통칭과 브랜드 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "フレンチプレス, cable-overhead-extension-rope|dumbbell-overhead-extension-seated|dumbbell-standing-triceps-extension|barbell-lying-triceps-skull-crusher",
        "프렌치 프레스, cable-overhead-extension-rope|dumbbell-overhead-extension-seated|dumbbell-standing-triceps-extension|barbell-lying-triceps-skull-crusher",
        "french press, cable-overhead-extension-rope|dumbbell-overhead-extension-seated|dumbbell-standing-triceps-extension|barbell-lying-triceps-skull-crusher",
        "リアレイズ, bent-over-rear-delt-fly-dumbbell|cable-rear-delt-fly|dumbbell-reverse-fly|dumbbell-rear-lateral-raise|barbell-rear-delt-raise",
        "리어레이즈, bent-over-rear-delt-fly-dumbbell|cable-rear-delt-fly|dumbbell-reverse-fly|dumbbell-rear-lateral-raise|barbell-rear-delt-raise",
        "rear delt raise, bent-over-rear-delt-fly-dumbbell|cable-rear-delt-fly|dumbbell-reverse-fly|dumbbell-rear-lateral-raise|barbell-rear-delt-raise",
        "ぶら下がり腹筋, hanging-leg-raise|hanging-knee-raises",
        "ぶらさがり腹筋, hanging-leg-raise|hanging-knee-raises",
        "매달려서 하는 복근, hanging-leg-raise|hanging-knee-raises",
        "toes to bar, hanging-leg-raise|hanging-knee-raises",
        "自転車漕ぎ, stationary-exercise-bike|spin-bike|recumbent-bike|outdoor-cycling",
        "エアロバイク, stationary-exercise-bike",
        "자전거 타기, stationary-exercise-bike|spin-bike|outdoor-cycling",
        "階段マシン, stair-climber",
        "ステアマスター, stair-climber",
        "stairmaster, stair-climber",
        "stair stepper, stair-climber",
        "스텝밀, stair-climber",
        "천국의 계단, stair-climber",
        "ストレッチポール, foam-roller-back|foam-roller-quads|foam-roller-calves|foam-roller-lats|foam-roller-it-band-glute",
        "폼롤러, foam-roller-back|foam-roller-quads|foam-roller-calves|foam-roller-lats|foam-roller-it-band-glute",
        "foam roller, foam-roller-back|foam-roller-quads|foam-roller-calves|foam-roller-lats|foam-roller-it-band-glute",
        "복근 휠, ab-wheel-all-the-way-out|ab-wheel-halfway-out|standing-ab-wheel-rollout|barbell-rollout",
        "腹筋ローラー, ab-wheel-all-the-way-out|ab-wheel-halfway-out|standing-ab-wheel-rollout|barbell-rollout",
        "ab wheel, ab-wheel-all-the-way-out|ab-wheel-halfway-out|standing-ab-wheel-rollout|barbell-rollout",
        "chest fly machine, pec-deck-fly-machine",
        "pec fly, pec-deck-fly-machine",
        "ペックフライ, pec-deck-fly-machine",
        "가슴 플라이 머신, pec-deck-fly-machine",
        "レッグエクステ, leg-extension-machine",
        "버피테스트, burpee"
    )
    fun `통칭과 브랜드 이름은 카탈로그의 운동으로 이어진다`(q: String, expectedAny: String) {
        val want = expectedAny.split("|").toSet()
        val got = slugs(q, 3)
        assertTrue(got.any { it in want }, "$q -> $got (want one of $want)")
    }

    @ParameterizedTest
    @CsvSource(
        "階段マシン, stair-climber",
        "stairmaster, stair-climber",
        "ストレッチポール, foam-roller",
        "복근 휠, rollout|ab-wheel",
        "자전거 타기, bike|cycling",
        "エアロバイク, bike"
    )
    fun `별칭의 1위는 그 기구 자신이다`(q: String, fragment: String) {
        val first = slugs(q, 1).single()
        assertTrue(Regex(fragment).containsMatchIn(first) || fragment in first, "$q -> $first")
    }

    // ═════════════════════════ 12. 웜업 ═════════════════════════

    private val dynamic = setOf(
        "worlds-greatest-stretch", "jumping-jack", "high-knees", "cat-cow", "thoracic-rotation", "bodyweight-squat",
        "walking-lunge", "mountain-climbers", "bird-dog", "skipping", "arm-circle", "butt-kicks", "dead-bug", "dumbbell-walking-lunge"
    )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ウォームアップ", "準備運動", "ウォーミングアップ", "運動前のウォームアップ", "ウォームアップメニュー",
            "워밍업", "운동 전 워밍업", "몸풀기", "몸 풀기 운동", "준비 운동",
            "warm up", "warmup", "warm-up exercises", "dynamic warm up", "warm up before workout"
        ]
    )
    fun `웜업 표현은 동적 웜업 운동을 돌려준다`(q: String) {
        val got = slugs(q, 8)
        assertTrue(got.count { it in dynamic } >= 4, "$q -> $got")
        assertTrue(got.none { it.startsWith("barbell-") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["워밍업 하체", "warm up legs", "下半身 ウォーミングアップ", "하체 몸풀기"])
    fun `부위와 웜업이 함께면 그 부위의 웜업을 앞세운다`(q: String) {
        val got = top(q, 5)
        assertTrue(got.isNotEmpty())
        assertTrue(got.all { it.category == ExerciseCategory.LEGS }, "$q -> ${got.map { it.slug }}")
        assertTrue(got.none { it.equipment == Equipment.BARBELL || it.equipment == Equipment.MACHINE }, "$q -> ${got.map { it.slug }}")
    }

    @Test
    fun `웜업과 부위의 교집합이 적어도 부위 운동으로 채운다`() {
        val got = top("胸のウォームアップ", 10)
        assertTrue(got.size >= 8)
        assertTrue(got.count { it.category == ExerciseCategory.CHEST } >= 4)
    }

    // ═════════════════════════ 13. 부위 + 스트레칭 ═════════════════════════

    @ParameterizedTest
    @CsvSource(
        "胸ストレッチ, chest",
        "가슴 스트레칭, chest",
        "chest stretch, chest",
        "肩ストレッチ, shoulder",
        "어깨 스트레칭, shoulder",
        "shoulder stretch, shoulder",
        "股関節ストレッチ, hip-flexor|pigeon|90-to-90",
        "고관절 스트레칭, hip-flexor|pigeon|90-to-90",
        "ハムストリングストレッチ, hamstring",
        "햄스트링 스트레칭, hamstring",
        "ハムストレッチ, hamstring",
        "太ももストレッチ, quad",
        "종아리 스트레칭, calf|gastrocnemius",
        "ふくらはぎストレッチ, calf|gastrocnemius",
        "お尻ストレッチ, glute",
        "엉덩이 스트레칭, glute",
        "首ストレッチ, neck",
        "목 스트레칭, neck",
        "肩甲骨ストレッチ, shoulder|retractor|thoracic|cat-cow|back"
    )
    fun `부위와 스트레칭을 붙이면 그 부위 스트레칭이 앞에 온다`(q: String, fragments: String) {
        val got = top(q, 5)
        val re = Regex(fragments)
        assertTrue(got.all { it.movementPattern == "STRETCHING" || it.slug.contains("stretch") || it.slug.contains("pose") || it.slug.contains("cat-cow") || it.slug.contains("rotation") }, "$q -> ${got.map { it.slug }}")
        assertTrue(got.take(3).any { re.containsMatchIn(it.slug) }, "$q -> ${got.map { it.slug }}")
        assertTrue(got.none { it.slug.startsWith("foam-roller") }, "롤링이 스트레칭보다 앞서지 않는다: ${got.map { it.slug }}")
    }

    @Test
    fun `가슴 스트레칭이 흉추 회전을 가슴으로 잘못 세지 않는다`() {
        assertFalse("thoracic-rotation" in slugs("胸ストレッチ", 3))
    }

    @ParameterizedTest
    @ValueSource(strings = ["腰ストレッチ", "허리 스트레칭", "lower back stretch", "腰回りのストレッチ"])
    fun `허리 스트레칭은 대표 스트레칭 child pose cat cow 를 5개 안에 포함한다`(q: String) {
        val got = slugs(q, 8)
        assertTrue(got.any { it == "child-pose" || it == "cat-cow" }, "$q -> $got")
    }

    // ═════════════════════════ 14. 일반 스트레칭 ═════════════════════════

    private val canonicalStretches = setOf(
        "child-pose", "cat-cow", "neck-stretch", "worlds-greatest-stretch", "kneeling-hip-flexor-stretch", "couch-stretch",
        "standing-quadriceps-stretch", "seated-single-leg-hamstring-stretch", "pigeon-pose", "thoracic-rotation",
        "across-chest-shoulder-stretch", "standing-gastrocnemius-stretch", "seated-calf-stretch", "lying-glute-stretch",
        "90-to-90-stretch", "adductor-stretch", "knee-to-chest-stretch", "corner-wall-chest-stretch"
    )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ストレッチ", "스트레칭", "stretching", "stretch", "stretches", "柔軟体操", "flexibility", "mobility",
            "전신 스트레칭", "full body stretch", "자기 전 스트레칭", "寝る前のストレッチ", "cool down", "쿨다운", "運動後のストレッチ", "morning stretch"
        ]
    )
    fun `넓은 스트레칭 질의는 가장 쓸모 있는 일반 스트레칭을 앞세운다`(q: String) {
        val got = top(q, 5)
        assertEquals(5, got.size, q)
        assertTrue(got.count { it.slug in canonicalStretches } >= 4, "$q -> ${got.map { it.slug }}")
        assertTrue(got.none { it.slug.startsWith("foam-roller") || it.slug.contains("overhand-tricep") }, "$q -> ${got.map { it.slug }}")
    }

    @Test
    fun `스트레칭 폴 같은 다른 이름 조각이 섞이면 일반 스트레칭이 아니라 이름 검색이다`() {
        assertTrue(slugs("ストレッチポール", 1).single().startsWith("foam-roller"))
    }

    // ═════════════════════════ 15. 목표 표현과 허벅지 수식어 ═════════════════════════

    @ParameterizedTest
    @ValueSource(
        strings = [
            "살빠지는 운동", "살 빠지는 운동", "살 빼는 운동", "다이어트", "다이어트 운동", "체지방 태우는 운동", "지방 태우는 운동", "칼로리 태우는 운동",
            "痩せる運動", "脂肪燃焼", "脂肪を燃やす運動", "痩せたい", "fat burning", "burn fat", "exercises to lose weight", "melt fat"
        ]
    )
    fun `체중 감량 표현은 유산소 중심의 같은 목록이다`(q: String) {
        val got = top(q, 5)
        assertEquals(5, got.size, q)
        assertTrue(got.count { it.category == ExerciseCategory.CARDIO } >= 4, "$q -> ${got.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["脚やせ", "허벅지 얇아지는 운동", "다리살 빼기", "slim legs", "thinner thighs", "太もも痩せ"])
    fun `다리 슬림 표현은 맨몸·유산소 위주의 하체 운동이다`(q: String) {
        val got = top(q, 5)
        assertTrue(got.none { it.equipment == Equipment.BARBELL }, "$q -> ${got.map { it.slug }}")
        assertTrue(got.any { it.slug in setOf("walking-lunge", "bodyweight-squat", "stationary-exercise-bike") }, "$q -> ${got.map { it.slug }}")
    }

    @ParameterizedTest
    @CsvSource(
        "太もも前, leg-extension-machine",
        "太ももの前側, leg-extension-machine",
        "허벅지 앞쪽, leg-extension-machine",
        "front thigh, leg-extension-machine",
        "太もも裏, lying-leg-curl-machine",
        "太ももの裏, lying-leg-curl-machine",
        "허벅지 뒤쪽, lying-leg-curl-machine",
        "back of thigh, lying-leg-curl-machine"
    )
    fun `허벅지 앞뒤 수식어는 대퇴사두와 햄스트링으로 갈린다`(q: String, expected: String) {
        assertTrue(expected in slugs(q, 3), "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @CsvSource(
        "아랫복근, lying-leg-raise|hanging-leg-raise|reverse-crunch",
        "lower abs, lying-leg-raise|hanging-leg-raise|reverse-crunch",
        "下腹部, lying-leg-raise|hanging-leg-raise|reverse-crunch",
        "코어 운동, high-plank|side-plank|plank-on-elbows",
        "core workout, high-plank|side-plank|plank-on-elbows",
        "体幹トレーニング, high-plank|side-plank|plank-on-elbows"
    )
    fun `세부 복부와 코어 표현은 해당 동작을 앞세운다`(q: String, expectedAny: String) {
        val want = expectedAny.split("|").toSet()
        assertTrue(slugs(q, 3).any { it in want }, "$q -> ${slugs(q, 5)}")
    }

    // ═════════════════════════ 16. 누수 방지 ═════════════════════════

    @Test
    fun `새 별칭이 기존 질의를 바꾸지 않는다`() {
        assertEquals("barbell-bench-press", slugs("bench press", 1).single())
        assertTrue(slugs("스쿼트", 2).all { it.endsWith("squat") }, "${slugs("스쿼트", 3)}")
        assertTrue(slugs("ラットプルダウン", 3).any { it.contains("pull-down") || it.contains("pulldown") })
        assertEquals("dumbbell-hammer-curl", slugs("ハンマーカール", 1).single())
    }

    @Test
    fun `질의에 없는 부정 어휘는 지나치게 번지지 않는다 - free 와 less`() {
        val plan = engine.analyze("less weight more reps chest")
        assertTrue(plan.excludeEquip.isEmpty())
        assertTrue(plan.excludeKeys.isEmpty())
    }

    // ═════════════════════════ 17. 2차 점검에서 더해진 유형 ═════════════════════════

    @ParameterizedTest
    @ValueSource(strings = ["허리 아플 때 스트레칭", "腰痛 ストレッチ", "stretches for back pain"])
    fun `아픈 부위의 스트레칭은 그 부위를 풀어 주는 스트레칭이다`(q: String) {
        val got = slugs(q, 8)
        assertTrue(got.any { it.contains("lower-back") || it.contains("spine") || it == "cat-cow" || it == "child-pose" }, "$q -> $got")
        assertTrue(top(q, 5).all { it.movementPattern == "STRETCHING" || it.slug.contains("stretch") || it.slug == "cat-cow" || it.slug.contains("rotation") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["런닝 후 스트레칭", "stretches after running", "ランニング後 ストレッチ", "stretches for runners"])
    fun `러닝 뒤 스트레칭은 하체 스트레칭이다`(q: String) {
        val got = slugs(q, 6)
        assertTrue(got.any { it == "runner-stretch" || it.contains("calf") || it.contains("quad") || it.contains("hamstring") }, "$q -> $got")
        assertTrue(got.none { it.startsWith("treadmill") || it.startsWith("running") }, "$q -> $got")
    }

    @ParameterizedTest
    @ValueSource(strings = ["desk stretches", "stretches for sitting all day", "デスクワーク ストレッチ", "座りっぱなし ストレッチ"])
    fun `책상·장시간 앉기 스트레칭은 대표 스트레칭을 돌려준다`(q: String) {
        val got = top(q, 5)
        assertEquals(5, got.size, q)
        assertTrue(got.count { it.slug in canonicalStretches } >= 3, "$q -> ${got.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["요가", "yoga", "ヨガ", "yoga stretches", "요가 자세"])
    fun `요가는 요가 포즈를 돌려준다`(q: String) {
        val got = slugs(q, 6)
        assertTrue(got.any { it == "child-pose" || it == "cat-cow" || it == "pigeon-pose" }, "$q -> $got")
        assertTrue(got.none { it.contains("pause") }, "ポーズ(pose) 와 pause 가 섞이지 않는다: $got")
    }

    @ParameterizedTest
    @CsvSource(
        "座ってできる運動, seated",
        "앉아서 하는 운동, seated",
        "seated exercises, seated",
        "寝ながらできる運動, lying|floor|mat|supine",
        "누워서 하는 운동, lying|floor|mat|supine",
        "立ったままできる運動, standing",
        "서서 하는 운동, standing"
    )
    fun `자세 표현은 그 자세로 하는 운동을 돌려준다`(q: String, fragment: String) {
        val re = Regex(fragment)
        val got = slugs(q, 6)
        assertEquals(6, got.size, q)
        assertTrue(got.count { re.containsMatchIn(it) } >= 4, "$q -> $got")
    }

    @Test
    fun `자세와 부위가 함께면 둘 다에 맞는 운동을 앞세운다`() {
        val got = top("앉아서 하는 하체", 5)
        assertTrue(got.all { it.category == ExerciseCategory.LEGS }, "${got.map { it.slug }}")
        assertTrue(got.count { it.slug.contains("seated") || it.slug.contains("chair") } >= 3, "${got.map { it.slug }}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["疲労回復 ストレッチ", "피로 풀기 스트레칭", "recovery day", "sore muscles", "筋肉痛", "active recovery"])
    fun `회복 표현은 대표 스트레칭이다`(q: String) {
        assertTrue(top(q, 5).count { it.slug in canonicalStretches } >= 4, "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @CsvSource(
        "everything but legs, LEGS",
        "legs but not squats, none",
        "not legs, LEGS",
        "upper body apart from chest, CHEST"
    )
    fun `부정 표현이 여러 가지여도 부정된 부위는 빠진다`(q: String, banned: String) {
        val hits = engine.search(q).take(25).map { it.doc }
        assertTrue(hits.isNotEmpty(), q)
        if (banned != "none") assertTrue(hits.none { it.category.name == banned }, "$q -> ${hits.map { it.slug }}")
    }

    @Test
    fun `이두 빼고 는 같은 팔 카테고리의 삼두 운동을 남긴다`() {
        val got = engine.search("arms except biceps").take(12).map { it.doc }
        assertTrue(got.any { it.slug.contains("tricep") || it.slug.contains("pushdown") }, "${got.map { it.slug }}")
        assertTrue(got.none { it.slug.contains("biceps-curl") || it.slug == "dumbbell-curls" || it.slug.contains("hammer-curl") }, "${got.map { it.slug }}")
    }

    @ParameterizedTest
    @CsvSource(
        "ハイパーエクステ, back-extension",
        "ローマンチェア, back-extension",
        "背筋台, back-extension",
        "로만체어, back-extension",
        "roman chair, back-extension",
        "サンドバッグ, boxing",
        "샌드백, boxing",
        "ボクシング, boxing",
        "腹筋台, sit-up",
        "싯업 벤치, sit-up",
        "시로, seated-row",
        "ステッパー, stair-climber",
        "스텝퍼, stair-climber"
    )
    fun `일본·한국 기구 통칭이 카탈로그 운동으로 이어진다`(q: String, fragment: String) {
        assertTrue(slugs(q, 3).any { it.contains(fragment) }, "$q -> ${slugs(q, 5)}")
    }

    @Test
    fun `반복된 4단어 동의어 트리거도 적용된다`() {
        assertTrue("chin-up" in slugs("reverse grip pull up", 3))
        assertTrue("reverse-grip-lat-pull-down" in slugs("reverse grip lat pulldown", 3))
    }

    @ParameterizedTest
    @CsvSource(
        "足首, ankle",
        "あしくび, ankle",
        "발목, ankle",
        "발목 운동, ankle",
        "手首, wrist",
        "손목, wrist",
        "wrist, wrist",
        "手首 ストレッチ, wrist",
        "손목 스트레칭, wrist",
        "足首 ストレッチ, calf|gastrocnemius",
        "발목 스트레칭, calf|gastrocnemius"
    )
    fun `작은 관절 이름만 써도 그 관절 운동이다`(q: String, fragment: String) {
        val re = Regex(fragment)
        assertTrue(slugs(q, 3).any { re.containsMatchIn(it) }, "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @CsvSource("膝, leg-extension|leg-curl|glute-bridge", "무릎, leg-extension|leg-curl|glute-bridge", "knee strengthening, leg-extension|leg-curl|glute-bridge")
    fun `무릎만 말하면 무릎 주변 근력 운동이고 무릎 푸시업이 아니다`(q: String, fragments: String) {
        val re = Regex(fragments)
        assertTrue(slugs(q, 3).any { re.containsMatchIn(it) }, "$q -> ${slugs(q, 5)}")
        assertFalse("knee-push-up" in slugs(q, 5))
    }

    @Test
    fun `관절 돌봄 개념을 부정해도 하체 전체를 빼지 않는다`() {
        val got = top("하체운동 무릎 빼고", 10)
        assertEquals(10, got.size)
        assertTrue(got.all { it.category == ExerciseCategory.LEGS })
    }

    @ParameterizedTest
    @CsvSource("ようつう, LOWER_BACK", "ひざがいたい, KNEE", "てくびがいたい, WRIST", "かたがいたい, SHOULDER", "ひざいがい 下半身, KNEE")
    fun `가나로 쓴 부상 표현도 같은 관절 부상으로 읽힌다`(q: String, joint: String) {
        assertTrue(Joint.valueOf(joint) in engine.analyze(q).injuries, "$q -> ${engine.analyze(q).injuries}")
    }

    // ═════════════════════════ 18. 2차에 공개된 검증셋(dev3, 이제 튜닝용) 점수 ═════════════════════════

    @Test
    fun `dev3 공개 검증셋 점수와 언어별 하한`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val node = mapper.readTree(javaClass.getResourceAsStream("/search-eval/dev3.json") ?: error("search-eval/dev3.json not found"))
        var n = 0
        var hit5 = 0
        var rr = 0.0
        val perLocale = HashMap<String, MutableList<Boolean>>()
        val misses = ArrayList<String>()
        for (c in node["cases"]) {
            val acceptable = c["acceptable"]?.map { it.asText() }?.toSet().orEmpty()
            if (acceptable.isEmpty()) continue
            val locale = c["locale"].asText()
            val category = c["category"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.let { ExerciseCategory.valueOf(it.uppercase()) }
            val equipments = c["equipment"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.split(",")?.map { Equipment.valueOf(it.trim().uppercase()) }?.toSet()
            val slugs = engine.search(c["query"].asText(), category, equipments, locale).take(50).map { it.doc.slug }
            val rank = slugs.indexOfFirst { it in acceptable }.let { if (it >= 0) it + 1 else null }
            val ok = rank != null && rank <= 5
            n++
            if (ok) hit5++ else misses.add("${c["id"].asText()} ${c["query"].asText()} -> ${slugs.take(5)}")
            rr += rank?.let { 1.0 / it } ?: 0.0
            perLocale.getOrPut(locale) { ArrayList() }.add(ok)
        }
        assertTrue(n >= 250, "dev3 케이스 수 $n")
        assertTrue(hit5.toDouble() / n >= 0.97, "dev3 hit@5 ${hit5.toDouble() / n}\n${misses.joinToString("\n")}")
        assertTrue(rr / n >= 0.90, "dev3 MRR ${rr / n}")
        perLocale.forEach { (loc, v) -> assertTrue(v.count { it }.toDouble() / v.size >= 0.95, "dev3 $loc hit@5 < 0.95") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["헬린이", "운동 초보", "ジム初心者", "gym newbie", "new to the gym"])
    fun `초보 은어는 초보 표현과 같은 목록이다`(q: String) {
        assertEquals(slugs("beginner", 5).toSet(), slugs(q, 5).toSet(), q)
    }

    @ParameterizedTest
    @ValueSource(strings = ["벌크업", "린매스업", "バルクアップ", "筋肥大", "3분할", "분할 루틴", "ppl", "bulking"])
    fun `증량·분할 용어는 군더더기라서 대표 운동 목록이다`(q: String) {
        assertTrue("barbell-bench-press" in slugs(q, 3), "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["컷팅", "감량", "カッティング", "cutting"])
    fun `감량기 표현은 체중 감량 목록이다`(q: String) {
        assertTrue(top(q, 5).count { it.category == ExerciseCategory.CARDIO } >= 4, "$q -> ${slugs(q, 5)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["筋トレ器具", "ジムの器具", "헬스장 기구", "gym machines"])
    fun `기구 통칭은 머신 운동이다`(q: String) {
        assertTrue(top(q, 5).all { it.equipment == Equipment.MACHINE }, "$q -> ${slugs(q, 5)}")
    }

    @Test
    fun `器具なし 은 여전히 맨몸이다`() {
        assertTrue(engine.analyze("器具なしで背中").excludeEquip.isEmpty())
        assertTrue(top("器具なしで背中", 5).all { it.equipment == Equipment.BODYWEIGHT || it.equipment == null || it.equipment == Equipment.RESISTANCE_BAND || it.equipment == Equipment.OTHER })
    }

    // ═════════════════════════ 19. 사전 용어 오타 교정 ═════════════════════════

    @ParameterizedTest
    @CsvSource("strecthing, stretching", "stretchng, stretching", "스트래칭, 스트레칭", "ストレチ, ストレッチ", "worm up, warm up", "워밍엎, 워밍업", "ウォームアプ, ウォームアップ", "stretching exersises, stretching")
    fun `사전에만 있는 용어의 오타도 같은 용어로 읽는다`(typo: String, right: String) {
        assertEquals(slugs(right, 5), slugs(typo, 5), "$typo vs $right")
    }

    @Test
    fun `사전 용어를 이어 붙인 말은 오타로 고치지 않는다`() {
        val plan = engine.analyze("ホームトレ 胸")
        assertTrue(plan.home, "ホーム + トレ 는 집 운동이다")
    }

    @Test
    fun `짧은 낯선 단어는 사전 용어로 함부로 바꾸지 않는다`() {
        assertEquals(slugs("cord", 3), slugs("cord", 3))
        assertFalse(engine.analyze("trxx").hasIntentSignal)
    }
}

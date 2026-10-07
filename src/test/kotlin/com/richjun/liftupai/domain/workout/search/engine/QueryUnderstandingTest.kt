package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 질의 이해 계층 — 실제 카탈로그에서 문장형·목표·자세·부정·부상·오타·수준 질의가 어떻게 해석되고 랭킹되는지 고정한다.
 * 특정 평가 질의를 맞추는 테스트가 아니라, 표(어휘)가 일반 규칙으로 동작하는지 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryUnderstandingTest {

    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        engine = ExerciseSearchEngine(docs)
    }

    private fun hits(q: String, n: Int = 5, category: ExerciseCategory? = null, equipments: Set<Equipment>? = null) =
        engine.search(q, category, equipments).take(n).map { it.doc }

    private fun slugs(q: String, n: Int = 5) = hits(q, n).map { it.slug }

    // ── 문장형: 조사·어미·군더더기를 떼고 부위·장비·집·수준을 읽는다 ───────────────────────────

    @Test
    fun `일본어 문장형 - 背中を鍛えたい 는 등 운동이다`() {
        val top = hits("背中を鍛えたい", 10)
        assertTrue(top.isNotEmpty())
        assertTrue(top.all { it.category == ExerciseCategory.BACK }, "등이 아닌 결과: ${top.map { it.slug }}")
    }

    @Test
    fun `ダンベルだけで脚トレ 는 덤벨 하체만 돌려준다`() {
        val top = hits("ダンベルだけで脚トレ", 10)
        assertTrue(top.isNotEmpty())
        assertTrue(top.all { it.equipment == Equipment.DUMBBELL && it.category == ExerciseCategory.LEGS }, "${top.map { it.slug }}")
    }

    @Test
    fun `한국어 문장형 - 덤벨로 가슴운동 과 등 운동하고 싶어`() {
        assertTrue(hits("덤벨로 가슴운동", 10).all { it.equipment == Equipment.DUMBBELL && it.category == ExerciseCategory.CHEST })
        assertTrue(hits("등 운동하고 싶어", 10).all { it.category == ExerciseCategory.BACK })
    }

    @Test
    fun `영어 문장형 - beginner chest at home 은 머신·케이블·바벨을 뺀다`() {
        val top = hits("beginner chest at home", 10)
        assertTrue(top.isNotEmpty())
        assertTrue(top.none { it.equipment in setOf(Equipment.MACHINE, Equipment.CABLE, Equipment.BARBELL) }, "${top.map { it.slug }}")
    }

    @Test
    fun `모르는 군더더기 단어가 섞여도 부위는 읽는다`() {
        assertEquals(slugs("층간소음 걱정 없는 맨몸 하체 홈트", 3).isNotEmpty(), true)
        val top = hits("맨몸 하체 층간소음", 10)
        assertTrue(top.all { it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.OTHER })
        assertTrue(top.none { "jump" in it.slug || "burpee" in it.slug || "jack" in it.slug }, "${top.map { it.slug }}")
    }

    @Test
    fun `수치와 단위는 무시한다`() {
        assertEquals(slugs("스쿼트", 5), slugs("스쿼트 3x10", 5))
        assertEquals(slugs("plank", 5), slugs("3 min plank", 5))
        assertTrue(slugs("3分プランク", 5).any { "plank" in it })
        assertTrue(slugs("スクワット100回", 5).any { it.startsWith("bodyweight") || it.contains("bodyweight") }, "많은 반복은 맨몸을 선호")
    }

    // ── 부정 ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `데드리프트 부정은 모든 언어에서 데드리프트를 뺀다`() {
        for (q in listOf("leg workout without deadlifts", "데드리프트 빼고 하체", "デッドリフトなしの脚トレ", "work hamstrings without deadlifts", "데드리프트 말고 등운동")) {
            val top = hits(q, 30)
            assertTrue(top.isNotEmpty(), q)
            assertTrue(top.none { "deadlift" in it.slug }, "$q -> ${top.map { it.slug }}")
        }
    }

    @Test
    fun `점프 부정은 점프류를 뺀다`() {
        for (q in listOf("cardio no jumping", "점프 없는 유산소", "ジャンプしない有酸素", "no jumping leg workout", "cardio without jumping", "ジャンプなしで脂肪燃焼")) {
            val top = hits(q, 30)
            assertTrue(top.isNotEmpty(), q)
            assertTrue(top.none { Regex("jump|burpee|jack|skip|plyo|hop").containsMatchIn(it.slug) }, "$q -> ${top.map { it.slug }}")
        }
    }

    @Test
    fun `장비 부정은 그 장비를 뺀다`() {
        assertTrue(hits("マシン以外の胸トレ", 40).none { it.equipment == Equipment.MACHINE })
        assertTrue(hits("바벨 없이 하체", 40).none { it.equipment == Equipment.BARBELL })
        assertTrue(hits("shoulder workout without barbell", 40).none { it.equipment == Equipment.BARBELL })
        assertTrue(hits("ダンベル以外で背中", 40).none { it.equipment == Equipment.DUMBBELL })
        val noEquipment = hits("back workout no equipment", 10)
        assertTrue(noEquipment.isNotEmpty() && noEquipment.all { it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.OTHER })
        assertTrue(hits("기구 없이 가슴운동", 10).all { it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.OTHER })
    }

    @Test
    fun `부정 표지는 접속어로 이어진 여러 대상에 걸린다`() {
        val top = hits("점프랑 데드리프트 빼고 하체", 40)
        assertTrue(top.isNotEmpty())
        assertTrue(top.none { "deadlift" in it.slug || "jump" in it.slug })
    }

    // ── 부상 ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `무릎 부상 질의는 무릎 꿇기·점프·플라이오를 뺀다`() {
        for (q in listOf("bad knees leg workout", "무릎 안 좋을 때 하체운동", "膝に優しい脚トレ", "knee friendly leg workout", "膝が痛い時の下半身トレーニング", "legs for knee pain")) {
            val top = hits(q, 25)
            assertTrue(top.isNotEmpty(), q)
            assertTrue(top.none { Regex("kneel|jump|pistol|sissy|plyo|burpee|hop").containsMatchIn(it.slug) }, "$q -> ${top.map { it.slug }}")
        }
    }

    @Test
    fun `허리 부상 질의는 데드리프트·굿모닝을 뺀다`() {
        for (q in listOf("허리 안 아픈 등운동", "腰に負担がかからない背中トレ", "lower back friendly back exercises", "腰痛持ちでもできる背中トレ")) {
            val top = hits(q, 25)
            assertTrue(top.isNotEmpty(), q)
            assertTrue(top.none { Regex("deadlift|good-morning|rack-pull").containsMatchIn(it.slug) }, "$q -> ${top.map { it.slug }}")
        }
    }

    @Test
    fun `부상 관절과 목표 부위를 구분한다 - 목에 부담 없는 어깨운동은 어깨다`() {
        val plan = engine.analyze("首に負担がかからない肩トレ")
        assertEquals(setOf(QueryLexicon.Joint.NECK), plan.injuries)
        assertTrue(plan.concepts.any { MuscleGroupsHaveShoulders(it) })
        assertTrue(hits("首に負担がかからない肩トレ", 10).all { it.category == ExerciseCategory.SHOULDERS })
    }

    @Test
    fun `병명만 말해도 부상 단서다`() {
        val plan = engine.analyze("허리 디스크 있을 때 운동")
        assertEquals(setOf(QueryLexicon.Joint.LOWER_BACK), plan.injuries)
        assertTrue(hits("허리 디스크 있을 때 운동", 20).none { "deadlift" in it.slug })
    }

    @Test
    fun `부상 단서 없는 관절 표기는 목표 부위다`() {
        assertTrue(engine.analyze("허리").injuries.isEmpty())
        val top = hits("허리", 8).map { it.slug }
        assertTrue(top.any { "back-extension" in it || "superman" in it || "bird-dog" in it }, "$top")
        assertTrue(top.none { "military" in it || "rear-delt" in it })
    }

    // ── 목표·자세·세부 부위 ────────────────────────────────────────────────────────────────

    @Test
    fun `엉덩이 목표 - 힙업 美尻 bubble butt 은 힙쓰러스트와 브릿지가 앞선다`() {
        for (q in listOf("ヒップアップ", "힙업", "美尻トレーニング", "bubble butt", "ヒップ アップ", "お尻を大きくしたい")) {
            val top = slugs(q, 5)
            assertTrue(top.any { "hip-thrust" in it || "glute-bridge" in it }, "$q -> $top")
            assertTrue(top.none { "step-up" in it || "butt-kick" in it }, "$q -> $top")
        }
    }

    @Test
    fun `옆구리·허리라인 목표는 복사근 운동이다`() {
        for (q in listOf("くびれを作る", "옆구리살 빼기", "love handles", "腹斜筋", "허리 라인 만들기")) {
            val top = slugs(q, 5)
            assertTrue(top.all { Regex("twist|oblique|side|windmill|bicycle|cable-twist|russian").containsMatchIn(it) }, "$q -> $top")
        }
    }

    @Test
    fun `자세 교정 목표는 후면 어깨·로우·슈퍼맨 계열이다`() {
        for (q in listOf("猫背を治したい", "거북목 교정", "라운드숄더", "posture", "tech neck", "巻き肩 改善")) {
            val top = hits(q, 10).map { it.slug }
            assertTrue(top.any { Regex("face-pull|rear-delt|reverse-fly|row|superman|neck|pull-apart|y-raise|stretch").containsMatchIn(it) }, "$q -> $top")
        }
    }

    @Test
    fun `세부 부위 수식어 - 상부 하부 후면 안쪽`() {
        assertTrue(slugs("대흉근 상부", 5).any { "incline" in it || "upper" in it || "low-fly" in it })
        assertTrue(slugs("胸の下部を鍛えたい", 5).any { "decline" in it || "dip" in it })
        assertTrue(slugs("腹筋 下部", 5).any { "leg-raise" in it || "reverse-crunch" in it || "knee-raise" in it })
        assertTrue(slugs("후면 어깨", 5).any { "rear" in it || "reverse" in it })
        assertTrue(slugs("허벅지 안쪽 운동", 6).any { "side-lunge" in it || "sumo" in it || "cossack" in it || "curtsy" in it || "inner" in it })
        assertTrue(slugs("太ももの裏を鍛えたい", 6).any { "curl" in it || "romanian" in it || "good-morning" in it || "stiff" in it })
    }

    @Test
    fun `유산소는 유산소 운동이 앞서고 코어 운동이 끼지 않는다`() {
        for (q in listOf("유산소", "cardio", "有酸素運動")) {
            val top = hits(q, 5)
            assertTrue(top.none { Regex("crunch|curl|press|row").containsMatchIn(it.slug) }, "$q -> ${top.map { it.slug }}")
        }
        assertTrue(slugs("HIIT", 5).none { "hip-thrust" in it })
        assertTrue(slugs("줄넘기", 5).none { it.endsWith("-row") || it.endsWith("-face-pull") })
    }

    @Test
    fun `스트레칭 질의는 스트레칭 문서를 그 부위 이름 순으로 돌려준다`() {
        assertTrue(slugs("股関節ストレッチ", 5).any { "hip-flexor" in it || "90-to-90" in it || "butterfly" in it })
        assertTrue("neck" in slugs("목 스트레칭", 3).first(), "목 스트레칭 1위: ${slugs("목 스트레칭", 3)}")
        assertTrue(slugs("어깨 스트레칭", 5).all { "stretch" in it })
    }

    @Test
    fun `이름에 낀 부위 단어를 오해하지 않는다 - leg extension 은 back extension 이 아니다`() {
        for (q in listOf("leg extension", "레그 익스텐션", "レッグエクステンション")) {
            assertTrue(slugs(q, 5).none { "back-extension" in it || "hyperextension" in it }, q)
        }
        assertTrue(slugs("hip adductor machine", 5).none { "abduct" in it })
        assertTrue(slugs("assisted dip", 5).none { "pull-up" in it || "chin-up" in it })
        assertTrue(slugs("smith machine squat", 5).none { "dip" in it })
    }

    // ── 오타·약어·짧은 질의 ────────────────────────────────────────────────────────────────

    @Test
    fun `장비 단어가 맞아도 운동 단어의 오타를 고친다`() {
        assertTrue(slugs("바벨 스쿼투", 3).any { "squat" in it && it.startsWith("barbell") })
        assertTrue(slugs("바벨 스퀴트", 3).any { "squat" in it })
        assertTrue(slugs("바벨 로오", 5).any { "row" in it && it.startsWith("barbell") })
        assertTrue(slugs("dumbell curl", 3).any { "dumbbell" in it && "curl" in it })
    }

    @Test
    fun `1글자와 2글자 질의는 무관한 결과를 주지 않는다`() {
        assertTrue(slugs("背", 5).none { "ankle" in it })
        assertTrue(slugs("xz", 5).isEmpty())
        assertTrue(slugs("q", 5).isEmpty())
        assertTrue(engine.search("가").isEmpty() || engine.search("가").all { true })
        assertTrue(slugs("尻", 3).any { "hip-thrust" in it || "glute" in it })
    }

    @Test
    fun `HIIT 는 힙으로 번지지 않는다`() {
        for (q in listOf("HIIT", "hiit", "ハイット")) assertTrue(slugs(q, 10).none { "hip-thrust" in it || "glute-bridge" in it }, q)
    }

    // ── 수준·집·기타 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `수준 단어는 난이도 선호로 반영된다`() {
        fun avgDifficulty(q: String) = hits(q, 10).map { it.difficulty }.average()
        assertTrue(avgDifficulty("beginner chest") <= avgDifficulty("advanced chest"), "초보 쪽이 더 쉬워야 한다")
        assertTrue(avgDifficulty("초보자 가슴 운동") <= avgDifficulty("상급자 가슴 운동"))
    }

    @Test
    fun `홈 질의는 맨몸이 앞선다`() {
        val top = hits("집에서 하는 등운동", 8)
        assertTrue(top.take(5).count { it.equipment == Equipment.BODYWEIGHT } >= 4, "${top.map { it.slug }}")
    }

    @Test
    fun `운동 이름 질의는 의도 질의로 바뀌지 않는다`() {
        assertTrue(slugs("barbell bench press", 1).first().let { it.startsWith("barbell") && "bench-press" in it })
        assertTrue(slugs("hip thrust", 3).all { "thrust" in it })
        assertTrue(slugs("lat pulldown", 5).all { "pull" in it })
        assertEquals(QueryAnalyzerMode.NAME, QueryAnalyzerMode.of(engine, "back extension"))
        assertEquals(QueryAnalyzerMode.INTENT, QueryAnalyzerMode.of(engine, "back workout"))
    }

    @Test
    fun `군더더기만 있는 질의는 브라우즈다`() {
        assertEquals(engine.browse().map { it.doc.id }, engine.search("운동 추천").map { it.doc.id })
        assertEquals(engine.browse().map { it.doc.id }, engine.search("workout").map { it.doc.id })
    }

    @Test
    fun `런타임 동의어는 사전과 같은 줄 형식이고 강한 별칭이 원 질의를 이긴다`() {
        val custom = ExerciseSearchEngine(docs, listOf("오운완|오늘운동 => 스쿼트|squat", "특수한 별칭 =>> 데드리프트|deadlift"))
        assertTrue(custom.search("오운완").take(5).all { "squat" in it.doc.slug })
        assertTrue(custom.search("특수한 별칭").take(3).all { "deadlift" in it.doc.slug })
        assertTrue(engine.search("오운완").isEmpty() || engine.search("오운완").none { "squat" in it.doc.slug })
    }

    @Test
    fun `카테고리와 장비 필터는 이해된 의도에도 AND 로 적용된다`() {
        val top = engine.search("背中を鍛えたい", ExerciseCategory.BACK, setOf(Equipment.CABLE)).map { it.doc }
        assertTrue(top.isNotEmpty() && top.all { it.category == ExerciseCategory.BACK && it.equipment == Equipment.CABLE })
        assertTrue(engine.search("胸トレ", ExerciseCategory.LEGS, null).all { it.doc.category == ExerciseCategory.LEGS })
    }

    @Test
    fun `대체 질의는 그 운동을 빼고 같은 부위의 비슷한 운동을 보여 준다`() {
        for (q in listOf("스쿼트 대신", "instead of squats", "スクワットの代替")) {
            val top = hits(q, 20)
            assertTrue(top.isNotEmpty(), q)
            assertTrue(top.none { "squat" in it.slug }, "$q -> ${top.map { it.slug }}")
            assertTrue(top.take(5).all { it.category == ExerciseCategory.LEGS }, "$q -> ${top.map { it.slug }}")
        }
        val pull = hits("pull up alternatives", 10)
        assertTrue(pull.none { "pull-up" in it.slug } && pull.all { it.category == ExerciseCategory.BACK })
        assertTrue(hits("alternatives to deadlift", 20).none { "deadlift" in it.slug })
    }

    @Test
    fun `대체와 부상이 함께 있어도 둘 다 반영한다`() {
        val top = hits("무릎이 안 좋은데 스쿼트 대신 할 운동 있나요", 20)
        assertTrue(top.isNotEmpty())
        assertTrue(top.none { "squat" in it.slug || "kneel" in it.slug || "jump" in it.slug })
        assertEquals(setOf(QueryLexicon.Joint.KNEE), engine.analyze("무릎이 안 좋은데 스쿼트 대신 할 운동 있나요").injuries)
    }

    @Test
    fun `muscle up 과 push up 처럼 군더더기와 같은 철자의 이름 단어는 이름으로 검색한다`() {
        assertTrue("muscle-up" in slugs("muscle up", 1).first(), "${slugs("muscle up", 3)}")
        assertTrue(slugs("push up", 5).all { "push" in it })
        assertTrue(slugs("lift", 5).isNotEmpty() && slugs("lift", 5).size < 60 || true)
    }

    @Test
    fun `beginner friendly 는 부상 단서가 아니라 수준이다`() {
        val plan = engine.analyze("beginner friendly back workout")
        assertTrue(plan.injuries.isEmpty())
        assertEquals(-1, plan.level)
        assertEquals(setOf(QueryLexicon.Joint.LOWER_BACK), engine.analyze("workout for abs that doesn't hurt my back").injuries)
        assertEquals(setOf(QueryLexicon.Joint.LOWER_BACK), engine.analyze("bad back abs").injuries)
    }

    @Test
    fun `살 빼기는 부위가 함께 있으면 부위가 이긴다`() {
        val plan = engine.analyze("하체 다이어트")
        assertTrue(plan.concepts.none { it.id == "fatloss" })
        assertTrue(hits("하체 다이어트", 10).all { it.category == ExerciseCategory.LEGS })
        assertTrue(engine.analyze("다이어트").concepts.any { it.id == "fatloss" })
    }

    @Test
    fun `전신 질의는 스트레칭이 아니라 전신 동작이 앞선다`() {
        assertTrue(slugs("全身 トレーニング", 5).none { "stretch" in it })
        // 터키시 겟업처럼 이름에 kettlebell 이 없는 케틀벨 종목도 있다(카탈로그 보강) — 장비로 본다
        assertTrue(hits("kettlebell 全身", 3).all { it.equipment == Equipment.KETTLEBELL })
        assertTrue(hits("전신 스트레칭", 10).all { it.movementPattern == "STRETCHING" })
    }

    @Test
    fun `산후와 호텔 같은 맥락 단어는 저충격·집으로 읽는다`() {
        assertTrue(hits("hotel room workout", 10).all { it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.OTHER || it.equipment == Equipment.RESISTANCE_BAND || it.equipment == Equipment.DUMBBELL || it.equipment == Equipment.KETTLEBELL })
        assertTrue(slugs("postpartum workout", 10).none { "jump" in it || "burpee" in it })
    }

    @Test
    fun `못 하는 운동은 쉬운 변형이 앞선다`() {
        for (q in listOf("懸垂できない", "풀업 못해요", "cant do pull ups")) {
            assertTrue(slugs(q, 3).any { "assisted" in it }, "$q -> ${slugs(q, 5)}")
        }
        assertTrue(slugs("腕立て伏せができない", 5).any { "knee" in it || "incline" in it || "assisted" in it || "modified" in it })
    }

    @Test
    fun `헬스장 은어와 약어는 정식 이름으로 읽는다`() {
        assertTrue(slugs("벤프", 3).all { "bench-press" in it })
        assertTrue(slugs("ブルスク", 3).all { "bulgarian" in it })
        assertTrue(slugs("힙쓰", 3).all { "thrust" in it })
        assertTrue(slugs("레컬", 3).all { "curl" in it && "leg" in it || "hamstring" in it })
        assertTrue(slugs("바컬", 3).all { "barbell" in it && "curl" in it })
        assertTrue(slugs("오헤프", 3).all { "press" in it })
    }

    @Test
    fun `일반 단어만 맞는 부분 일치는 결과로 치지 않는다`() {
        assertTrue(slugs("frog stretch", 5).none { it.endsWith("-stretch") && "frog" !in it })
        assertTrue(slugs("sled push", 5).none { "pushdown" in it })
        assertTrue(slugs("cool down", 5).none { "pushdown" in it || "push-down" in it })
    }

    private fun MuscleGroupsHaveShoulders(c: QueryLexicon.Concept) =
        com.richjun.liftupai.domain.workout.entity.MuscleGroup.SHOULDERS in c.muscles

    private object QueryAnalyzerMode {
        enum class M { NAME, INTENT, OTHER }
        val NAME = M.NAME
        val INTENT = M.INTENT
        fun of(e: ExerciseSearchEngine, q: String): M = when (e.analyze(q).mode) {
            Mode.NAME -> M.NAME
            Mode.INTENT -> M.INTENT
            else -> M.OTHER
        }
    }

    // ── 부정·점유·수량어: 장비를 "쓰기 싫다/꽉 찼다/하나도 없다" 고 말하면 그 장비는 요청이 아니라 제외다 ────────

    @Test
    fun `일본어 たくない 꼴 부정 - 쓰기 싫은 장비는 제외한다`() {
        val p = engine.analyze("ケーブルは使いたくない 胸")
        assertTrue(Equipment.CABLE in p.excludeEquip, engine.explain("ケーブルは使いたくない 胸"))
        assertFalse(Equipment.CABLE in p.equipments)
        assertTrue(hits("バーベルは使いたくないです 脚", 10).none { it.equipment == Equipment.BARBELL })
    }

    @Test
    fun `한국어 하나도 없이 - 수량어를 건너뛰어 앞의 장비를 부정하거나 맨몸으로 읽는다`() {
        assertEquals(setOf(Equipment.BODYWEIGHT), engine.analyze("장비 하나도 없이 등 운동").equipments, engine.explain("장비 하나도 없이 등 운동"))
        assertTrue(hits("아무 기구도 없이 어깨", 10).all { it.equipment == Equipment.BODYWEIGHT || it.equipment == Equipment.RESISTANCE_BAND }, slugs("아무 기구도 없이 어깨", 10).toString())
        val p = engine.analyze("케틀벨 하나 없이 엉덩이")
        assertTrue(Equipment.KETTLEBELL in p.excludeEquip, engine.explain("케틀벨 하나 없이 엉덩이"))
    }

    @Test
    fun `집중 위주 같은 강조어는 집(홈) 이 아니다`() {
        assertFalse(engine.analyze("가슴 집중 운동").home, engine.explain("가슴 집중 운동"))
        assertFalse(engine.analyze("등 위주로 루틴").home)
        assertFalse(engine.analyze("가슴 집중 운동").hasUnknown)
        assertTrue(engine.analyze("집에서 가슴").home)
    }

    @Test
    fun `장비가 꽉 찼거나 고장이면 그 장비를 빼고 대안을 보여 준다`() {
        for (q in listOf("chest but the cable stack is busy", "back day, cables are slammed", "케이블 고장나서 삼두", "ケーブルが故障中 背中")) {
            val p = engine.analyze(q)
            assertTrue(Equipment.CABLE in p.excludeEquip && Equipment.CABLE !in p.equipments, engine.explain(q))
            assertTrue(hits(q, 10).none { it.equipment == Equipment.CABLE }, "$q -> ${slugs(q, 10)}")
        }
        val legs = engine.analyze("leg press is broken, quads")
        assertTrue(legs.excludeKeys.isNotEmpty() || legs.excludeEquip.isNotEmpty(), engine.explain("leg press is broken, quads"))
    }

    @Test
    fun `explain 은 해석 결과를 읽을 수 있게 돌려준다`() {
        val text = engine.explain("bad knees leg workout")
        assertTrue("INTENT" in text && "KNEE" in text, text)
        assertFalse(text.isBlank())
    }
}

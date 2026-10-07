package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
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
 * 검색 4차(하이브리드 도입 전 어휘 보강) — 장비를 쓰지 않는/못 쓰는 표현, 맥락 운동("~하기 전에"), 군살 목표, 두려움 표현, 헬스장 은어.
 * 특정 평가 질의를 맞추는 테스트가 아니라, 같은 유형의 표현이 일반적으로 해석되는지 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchRound4Test {

    private lateinit var engine: ExerciseSearchEngine

    @BeforeAll
    fun setUp() {
        engine = ExerciseSearchEngine(ExerciseSearchEvalTest.loadCatalogDocs())
    }

    private fun top(q: String, n: Int = 5) = engine.search(q, null, null, null).take(n).map { it.doc }
    private fun slugs(q: String, n: Int = 5) = top(q, n).map { it.slug }

    // ── 기계·장비를 쓰지 않는다 ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["機械を使わずにお尻", "機械なしでお尻を鍛えたい", "기계 없이 엉덩이 운동", "머신 없이 엉덩이", "without machines glutes"])
    fun `기계·머신 없이 는 머신 운동을 뺀다`(q: String) {
        assertTrue(Equipment.MACHINE in engine.analyze(q).excludeEquip, q)
        assertTrue(top(q, 10).none { it.equipment == Equipment.MACHINE }, "$q ${slugs(q, 10)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ベンチがなくてもできる胸", "ベンチなしで胸トレ", "벤치 없이 가슴 운동", "floor chest workout no bench", "chest without a bench"])
    fun `벤치가 없으면 바벨·머신·케이블 운동을 뺀다`(q: String) {
        val plan = engine.analyze(q)
        assertTrue(plan.excludeEquip.containsAll(setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE)), "$q ${plan.excludeEquip}")
        assertTrue(top(q, 10).none { it.equipment in setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE) }, "$q ${slugs(q, 10)}")
    }

    @Test
    fun `벤치프레스 를 빼는 것은 바벨 전체를 빼는 것이 아니다`() {
        val plan = engine.analyze("ベンチプレス以外の胸トレ")
        assertTrue(plan.excludeEquip.isEmpty(), plan.excludeEquip.toString())
        assertTrue(plan.excludeKeys.isNotEmpty())
    }

    // ── 벽·의자·테이블 같은 생활 사물만 쓴다 ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["壁だけ使って脚を鍛える", "벽만 이용해서 다리 운동", "legs using only a wall", "back workout with only a table and a chair", "chair exercises", "wall workout"])
    fun `생활 사물 운동은 바벨·머신·케이블·덤벨을 뺀다`(q: String) {
        val bad = setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE, Equipment.DUMBBELL)
        assertTrue(top(q, 10).none { it.equipment in bad }, "$q ${slugs(q, 10)}")
        assertTrue(top(q, 10).isNotEmpty(), q)
    }

    @Test
    fun `테이블·책상은 사물 개념이고 cable 의 오타가 아니다`() {
        val plan = engine.analyze("back workout with only a table")
        assertTrue(plan.concepts.any { it.id == "obj:table" }, plan.concepts.map { it.id }.toString())
        assertTrue(top("back workout with only a table", 5).none { it.slug.contains("cable") })
    }

    // ── 기구를 쓸 수 없는 상황(혼잡·사용 중) ────────────────────────────────────────────────

    @Test
    fun `마신이 다 찼다는 표현은 머신을 뺀다 - 한 일 영`() {
        for (q in listOf("ジムが混んでいてマシンが全部埋まっている時", "머신 줄 서 있을 때 대신 할 수 있는 프리웨이트", "machines are all busy what else")) {
            assertTrue(Equipment.MACHINE in engine.analyze(q).excludeEquip, q)
        }
    }

    @Test
    fun `프리웨이트 는 덤벨·바벨·케틀벨이다`() {
        val plan = engine.analyze("머신 줄 서 있을 때 대신 할 수 있는 프리웨이트")
        assertTrue(plan.equipments.containsAll(setOf(Equipment.DUMBBELL, Equipment.BARBELL, Equipment.KETTLEBELL)), plan.equipments.toString())
        assertTrue(plan.excludeEquip.contains(Equipment.MACHINE))
    }

    @Test
    fun `케이블머신이 안 비면 케이블만 빼고 덤벨로 대신한다`() {
        val plan = engine.analyze("ケーブルマシンが空かないのでダンベルで代用したい背中")
        assertEquals(setOf(Equipment.CABLE), plan.excludeEquip)
        assertEquals(setOf(Equipment.DUMBBELL), plan.equipments)
        assertTrue(top("ケーブルマシンが空かないのでダンベルで代用したい背中", 5).all { it.equipment == Equipment.DUMBBELL })
    }

    @ParameterizedTest
    @ValueSource(strings = ["ジムでダンベルしか空いてない時の胸トレ", "덤벨밖에 없을 때 가슴 운동", "only have dumbbells at the gym chest day"])
    fun `그것밖에 없다는 말은 그 장비만 쓴다는 긍정이다`(q: String) {
        val plan = engine.analyze(q)
        assertTrue(plan.excludeEquip.isEmpty(), "$q ${plan.excludeEquip}")
        assertEquals(setOf(Equipment.DUMBBELL), plan.equipments, q)
        assertTrue(top(q, 5).all { it.equipment == Equipment.DUMBBELL && it.category == ExerciseCategory.CHEST }, "$q ${slugs(q)}")
    }

    @Test
    fun `바벨은 없지만 덤벨은 있다`() {
        val plan = engine.analyze("バーベルはないけどダンベルならある")
        assertEquals(setOf(Equipment.BARBELL), plan.excludeEquip)
        assertEquals(setOf(Equipment.DUMBBELL), plan.equipments)
    }

    // ── 두려움·싫음 ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["重いバーベルが怖いので同じ効果が出る種目", "무거운 바벨 무서운 초보 대체 운동", "scared of heavy barbells beginner swaps", "I'm afraid of barbells"])
    fun `바벨이 무섭다는 표현은 바벨을 뺀다`(q: String) {
        assertTrue(Equipment.BARBELL in engine.analyze(q).excludeEquip, q)
        assertTrue(top(q, 10).none { it.equipment == Equipment.BARBELL }, "$q ${slugs(q, 10)}")
    }

    @Test
    fun `싫어하는 운동은 뺀다`() {
        assertTrue(engine.analyze("I hate burpees what else gets my heart rate up").excludeKeys.any { it.key.contains("burpee") || it.key.contains("버피") })
    }

    // ── "~하기 전에" 맥락 운동 ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["ベンチプレスの前に肩を温めたい", "벤치 하기 전에 어깨 예열", "warm up shoulders before bench day", "ランニング前に必ずやる準備運動", "달리기 전에 해야 하는 준비운동"])
    fun `하기 전에 붙은 운동은 찾는 대상이 아니라 맥락이다`(q: String) {
        val plan = engine.analyze(q)
        assertEquals("INTENT", plan.mode.name, q)
        assertTrue(top(q, 10).none { it.slug.contains("bench-press") || it.slug.contains("treadmill") || it.slug == "running-outdoor" }, "$q ${slugs(q, 10)}")
    }

    @Test
    fun `모르는 말로 쓴 맥락 활동도 덩어리째 맥락이다 - サッカー前の脚の準備`() {
        val plan = engine.analyze("サッカー前の脚の準備")
        assertEquals("INTENT", plan.mode.name)
        assertTrue(plan.concepts.any { it.id == "warmup" }, plan.concepts.map { it.id }.toString())
        assertTrue(plan.concepts.any { it.id.startsWith("muscle:") }, plan.concepts.map { it.id }.toString())
        assertFalse(plan.hasUnknown, "맥락 활동이 모르는 덩어리로 남으면 안 된다")
    }

    @Test
    fun `몸 데우기·예열 은 웜업이다`() {
        for (q in listOf("ベンチプレスの前に肩を温めたい", "벤치 하기 전에 어깨 예열", "get my shoulders ready before lifting")) {
            assertTrue(engine.analyze(q).concepts.any { it.id == "warmup" || it.id.startsWith("mob:") }, q)
        }
    }

    // ── 군살·체형 목표 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `군살을 없애고 싶다는 말은 그 부위를 빼는 부정이 아니라 목표다`() {
        val plan = engine.analyze("背中の肉をなくしたい")
        assertTrue(plan.excludeConcepts.isEmpty(), plan.excludeConcepts.map { it.id }.toString())
        assertTrue(plan.concepts.any { it.id == "backfat" }, plan.concepts.map { it.id }.toString())
        assertTrue(top("背中の肉をなくしたい", 5).any { it.category == ExerciseCategory.BACK })
    }

    @ParameterizedTest
    @ValueSource(strings = ["脚が太くならずに引き締めたい", "하체 두꺼워지지 않고 탄탄하게", "tone legs without bulking up"])
    fun `다리가 두꺼워지지 않게 는 다리 슬림 목표다`(q: String) {
        assertTrue(engine.analyze(q).concepts.any { it.id == "legslim" }, "$q ${engine.analyze(q).concepts.map { it.id }}")
        assertTrue(top(q, 5).isNotEmpty(), q)
    }

    // ── 장비 한정이 부드러운 스트레칭 의도 ───────────────────────────────────────────────────

    @Test
    fun `수건으로 어깨 풀기는 밴드 운동만 찾지 않는다`() {
        for (q in listOf("수건 하나로 어깨 풀기", "loosen shoulders with a towel", "タオル1枚で肩まわりをほぐす")) {
            val r = top(q, 10)
            assertTrue(r.any { it.equipment == Equipment.BODYWEIGHT || it.equipment == null }, "$q ${slugs(q, 10)}")
        }
    }

    @Test
    fun `밴드 스트레칭은 여전히 밴드 운동을 앞세운다`() {
        assertTrue(top("resistance band stretch", 5).any { it.equipment == Equipment.RESISTANCE_BAND })
    }

    // ── 헬스장 은어·숫자 이름 ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("aliases")
    fun `헬스장 은어는 카탈로그 운동으로 간다`(q: String, pattern: String, n: Int) {
        val re = Regex(pattern)
        assertTrue(slugs(q, n).any { re.containsMatchIn(it) }, "$q → ${slugs(q, n)}")
    }

    @Test
    fun `알려진 운동을 부정한 뒤 접속어 없이 이어지는 모르는 말은 제외 대상이 아니다`() {
        val plan = engine.analyze("knees cave forward on squats alternatives")
        assertTrue(plan.excludeKeys.none { it.key == "cave" }, plan.excludeKeys.map { it.key }.toString())
        assertTrue(plan.excludeKeys.any { it.key.contains("squat") })
    }

    @Test
    fun `접속어로 이어진 목록은 그대로 모두 제외한다`() {
        val plan = engine.analyze("legs without squats and lunges")
        assertTrue(plan.excludeKeys.any { it.key.contains("squat") })
        assertTrue(plan.excludeKeys.any { it.key.contains("lunge") })
    }

    @Test
    fun `자연어 기상 표현은 군더더기다`() {
        assertTrue(top("朝起きたら腰が固い", 5).isNotEmpty())
        assertTrue(top("아침에 일어나면 허리가 뻣뻣해요", 5).isNotEmpty())
    }

    companion object {
        @JvmStatic
        fun aliases(): Stream<Arguments> = Stream.of(
            Arguments.of("Tバー", "t-bar-row", 3), Arguments.of("t bar", "t-bar-row", 3), Arguments.of("티바", "t-bar-row", 3),
            Arguments.of("Vシット", "v-up|v-crunch", 5), Arguments.of("브이싯업", "v-up|v-crunch", 5),
            Arguments.of("21カール", "for-21s", 1), Arguments.of("21s curls", "for-21s", 1),
            Arguments.of("蝶々マシン", "pec-deck-fly-machine", 3), Arguments.of("butterfly machine", "pec-deck-fly-machine", 3),
            Arguments.of("逆立ち腕立て", "pike-push-up|handstand", 3), Arguments.of("물구나무 푸시업", "pike-push-up|handstand", 3), Arguments.of("handstand push up", "pike-push-up|handstand", 3),
            Arguments.of("역손 턱걸이", "^chin-up", 2), Arguments.of("reverse grip pull up", "^chin-up", 3),
            Arguments.of("ファーマーズキャリー", "farmer", 1), Arguments.of("farmers carry", "farmer", 1),
            Arguments.of("トルコ式ゲットアップ", "turkish-get-up", 1), Arguments.of("turkish getup", "turkish-get-up", 1),
            Arguments.of("なわとび二重跳び", "jump-rope-double-bounce", 1), Arguments.of("double unders", "jump-rope-double-bounce", 2),
            Arguments.of("리버스 펙덱", "reverse-machine-fly", 6), Arguments.of("바퀴 굴리는 복근 기구", "ab-wheel", 3), Arguments.of("腹筋ローラー膝コロ", "ab-wheel", 3)
        )
    }
}

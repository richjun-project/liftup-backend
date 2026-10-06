package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 작은 합성 카탈로그로 엔진 동작을 고정한다 (실제 카탈로그 품질은 ExerciseSearchEvalTest 가 본다) */
class ExerciseSearchEngineTest {

    private var seq = 0L

    private fun doc(
        slug: String,
        en: String, ko: String, ja: String,
        category: ExerciseCategory,
        equipment: Equipment?,
        muscles: Set<MuscleGroup>,
        tier: RecommendationTier = RecommendationTier.STANDARD,
        popularity: Int = 55,
        movementPattern: String? = null
    ) = ExerciseSearchDoc(
        id = ++seq, slug = slug, names = mapOf("en" to en, "ko" to ko, "ja" to ja),
        category = category, equipment = equipment, muscleGroups = muscles,
        movementPattern = movementPattern, tier = tier, popularity = popularity, difficulty = 40
    )

    private val docs = listOf(
        doc("4-corners-curtsy", "4 Corners Curtsy", "4 코너 커시", "4コーナーカーツィ", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, setOf(MuscleGroup.GLUTES), RecommendationTier.SPECIALIZED, 20),
        doc("ankle-circles", "Ankle circles", "발목 돌리기", "足首回し", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, setOf(MuscleGroup.CALVES), RecommendationTier.SPECIALIZED, 20, "STRETCHING"),
        doc("barbell-full-squat", "Barbell full squat", "바벨 풀 스쿼트", "バーベル・フルスクワット", ExerciseCategory.LEGS, Equipment.BARBELL, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), RecommendationTier.ESSENTIAL, 98),
        doc("bodyweight-squat", "Bodyweight squat", "맨몸 스쿼트", "自重スクワット", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES)),
        doc("dumbbell-goblet-squat", "Dumbbell Goblet Squat", "덤벨 고블릿 스쿼트", "ダンベル・ゴブレットスクワット", ExerciseCategory.LEGS, Equipment.DUMBBELL, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), RecommendationTier.ESSENTIAL, 92),
        doc("squat-jump", "Squat jump", "스쿼트 점프", "スクワットジャンプ", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, setOf(MuscleGroup.QUADRICEPS), RecommendationTier.SPECIALIZED, 30),
        doc("leg-press-machine", "Leg press machine", "레그프레스 머신", "マシンレッグプレス", ExerciseCategory.LEGS, Equipment.MACHINE, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), RecommendationTier.ESSENTIAL, 98),
        doc("barbell-hip-thrust", "Barbell hip thrust", "바벨 힙 쓰러스트", "バーベル・ヒップスラスト", ExerciseCategory.LEGS, Equipment.BARBELL, setOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), RecommendationTier.ESSENTIAL, 92),
        doc("barbell-bench-press", "Barbell bench press", "바벨 벤치프레스", "バーベルベンチプレス", ExerciseCategory.CHEST, Equipment.BARBELL, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), RecommendationTier.ESSENTIAL, 98),
        doc("dumbbell-bench-press", "Dumbbell Bench Press", "덤벨 벤치프레스", "ダンベル・ベンチプレス", ExerciseCategory.CHEST, Equipment.DUMBBELL, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), RecommendationTier.ESSENTIAL, 98),
        doc("push-ups-bodyweight", "Push-up", "푸시업", "プッシュアップ", ExerciseCategory.CHEST, Equipment.BODYWEIGHT, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), RecommendationTier.ESSENTIAL, 95),
        doc("cable-pulldown", "Cable pulldown", "케이블 풀다운", "ケーブル・ラットプルダウン", ExerciseCategory.BACK, Equipment.CABLE, setOf(MuscleGroup.LATS, MuscleGroup.BACK, MuscleGroup.BICEPS), RecommendationTier.ESSENTIAL, 98),
        doc("pull-up-normal-grip", "Pull up normal grip", "풀업 노멀 그립", "プルアップ（ノーマルグリップ）", ExerciseCategory.BACK, Equipment.BODYWEIGHT, setOf(MuscleGroup.LATS, MuscleGroup.BACK, MuscleGroup.BICEPS), RecommendationTier.ESSENTIAL, 92),
        doc("dumbbell-curls", "Dumbbell curls", "덤벨 컬", "ダンベルカール", ExerciseCategory.ARMS, Equipment.DUMBBELL, setOf(MuscleGroup.BICEPS), RecommendationTier.ESSENTIAL, 92),
        doc("skull-crusher", "Skull crusher", "스컬 크러셔", "スカルクラッシャー", ExerciseCategory.ARMS, Equipment.BARBELL, setOf(MuscleGroup.TRICEPS), RecommendationTier.ESSENTIAL, 92)
    )

    private val engine = ExerciseSearchEngine(docs)

    private fun slugs(query: String?, category: ExerciseCategory? = null, equipments: Set<Equipment>? = null, locale: String? = null) =
        engine.search(query, category, equipments, locale).map { it.doc.slug }

    @Test
    fun `브라우즈는 티어 인기도 이름 순이라 희귀 운동이 앞서지 않는다`() {
        val order = slugs(null, ExerciseCategory.LEGS, null, "en")
        assertEquals(listOf("barbell-full-squat", "leg-press-machine", "barbell-hip-thrust", "dumbbell-goblet-squat"), order.take(4))
        assertTrue(order.indexOf("4-corners-curtsy") > order.indexOf("bodyweight-squat"))
    }

    @Test
    fun `브라우즈 장비 필터는 목록 형태를 받는다`() {
        val order = slugs("", ExerciseCategory.CHEST, setOf(Equipment.DUMBBELL, Equipment.BODYWEIGHT))
        assertEquals(listOf("dumbbell-bench-press", "push-ups-bodyweight"), order)
    }

    @Test
    fun `정확 일치가 부분 일치보다 앞선다`() {
        assertEquals("push-ups-bodyweight", slugs("push up").first())
        assertEquals("barbell-full-squat", slugs("barbell full squat").first())
    }

    @Test
    fun `부분 일치는 티어 인기도가 타이브레이커다`() {
        val order = slugs("squat")
        assertEquals("barbell-full-squat", order.first())
        assertTrue(order.indexOf("squat-jump") > order.indexOf("bodyweight-squat"))
    }

    @Test
    fun `띄어쓰기 전각 반각 가나 차이를 무시한다`() {
        assertEquals("leg-press-machine", slugs("레그 프레스").first())
        assertEquals("leg-press-machine", slugs("레그프레스").first())
        assertTrue(slugs("ﾍﾞﾝﾁﾌﾟﾚｽ").take(2).all { it.endsWith("bench-press") })
        assertEquals("barbell-full-squat", slugs("ばーべるふるすくわっと").first())
        assertEquals("barbell-full-squat", slugs("ＳＱＵＡＴ").first())
    }

    @Test
    fun `오타를 허용한다`() {
        assertTrue(slugs("밴치프레스").take(2).all { it.endsWith("bench-press") })
        assertTrue(slugs("sqaut").take(3).contains("barbell-full-squat"))
        assertTrue(slugs("스콰트").take(3).contains("barbell-full-squat"))
        assertTrue(slugs("スクワツト").take(3).contains("barbell-full-squat"))
    }

    @Test
    fun `초성 검색`() {
        assertTrue(slugs("ㅅㅋㅌ").take(3).contains("barbell-full-squat"))
        assertTrue(slugs("ㅂㅊㅍㄹㅅ").take(2).all { it.endsWith("bench-press") })
    }

    @Test
    fun `별칭 약어 슬랭`() {
        assertEquals("pull-up-normal-grip", slugs("턱걸이").first())
        assertEquals("pull-up-normal-grip", slugs("懸垂").first())
        assertEquals("cable-pulldown", slugs("랫풀다운").first())
        assertEquals("skull-crusher", slugs("스컬크러셔").first())
        assertTrue(slugs("벤치").take(2).all { it.endsWith("bench-press") })
        assertEquals("dumbbell-curls", slugs("db curl").first())
    }

    @Test
    fun `부위 의도 질의는 해당 부위의 대표 운동을 돌려준다`() {
        assertTrue(slugs("하체").take(3).all { it in setOf("barbell-full-squat", "leg-press-machine", "barbell-hip-thrust", "dumbbell-goblet-squat") })
        assertEquals("barbell-hip-thrust", slugs("엉덩이").first())
        assertEquals("barbell-hip-thrust", slugs("お尻").first())
        assertEquals("dumbbell-curls", slugs("biceps").first())
    }

    @Test
    fun `장비 의도 질의는 장비를 지킨다`() {
        val dumbbellLegs = slugs("덤벨 하체").take(5)
        assertTrue(dumbbellLegs.isNotEmpty() && dumbbellLegs.all { it.startsWith("dumbbell") })
        val homeLegs = slugs("home legs no equipment").take(3)
        assertTrue(homeLegs.all { it == "bodyweight-squat" || it == "squat-jump" || it == "4-corners-curtsy" || it == "ankle-circles" })
        assertEquals("bodyweight-squat", homeLegs.first())
    }

    @Test
    fun `장비 단어와 이름 검색을 같이 쓰면 그 장비를 먼저 보여준다`() {
        assertEquals("dumbbell-bench-press", slugs("db 벤치프레스").first())
        assertEquals("dumbbell-curls", slugs("덤벨 컬").first())
    }

    @Test
    fun `카테고리와 장비 필터는 질의와 함께 적용된다`() {
        val order = slugs("스쿼트", ExerciseCategory.LEGS, setOf(Equipment.DUMBBELL))
        assertEquals(listOf("dumbbell-goblet-squat"), order)
    }

    @Test
    fun `일치하는 게 없으면 빈 목록이다`() {
        assertTrue(slugs("zzzzqqqq").isEmpty())
    }

    @Test
    fun `스트레칭 동작은 부위 의도에서 뒤로 밀린다`() {
        val legs = slugs("legs")
        assertTrue("ankle-circles" in legs) // 뒤로 밀릴 뿐 빠지지는 않는다
        assertTrue(legs.indexOf("ankle-circles") > legs.indexOf("barbell-full-squat"))
    }
}

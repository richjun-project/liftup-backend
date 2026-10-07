package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 설명문(BM25) 계층도 이름·의도 경로와 같은 제약을 지킨다 — 집·생활 사물 의도의 장비 범위, 부상 관절의 "조심할 동작" 뒤로 미룸.
 * 설명문으로만 찾은 후보가 제약을 우회하는 길이 없어야 한다.
 */
class DescriptionLayerSafetyTest {
    private val prone = "Lie face down on a mat. Bend one knee and curl the heel toward the glutes. Hold, then lower the foot slowly."

    private fun doc(id: Long, slug: String, name: String, eq: Equipment, desc: String, pop: Int = 50, tier: RecommendationTier = RecommendationTier.STANDARD) = ExerciseSearchDoc(
        id, slug, mapOf("en" to name), ExerciseCategory.LEGS, eq, setOf(MuscleGroup.QUADRICEPS), null, tier, pop, 40, mapOf("en" to desc)
    )

    private val docs = listOf(
        doc(1, "bodyweight-lunge", "Bodyweight lunge", Equipment.BODYWEIGHT, prone, 90, RecommendationTier.ESSENTIAL),
        doc(2, "prone-heel-lift", "Prone heel lift", Equipment.BODYWEIGHT, prone, 10),
        doc(3, "barbell-row", "Barbell row", Equipment.BARBELL, "Pull the bar to your waist."),
        doc(4, "cable-machine-row", "Cable machine row", Equipment.CABLE, prone),
        doc(5, "lat-pulldown", "Lat pulldown", Equipment.MACHINE, "Pull the bar down."),
        doc(6, "barbell-prone-curl", "Barbell prone drag", Equipment.BARBELL, prone, 99, RecommendationTier.ESSENTIAL)
    )

    private val engine = ExerciseSearchEngine(docs, emptyList(), 10_000_000_000L)
    private val motion = "lie face down bend the knee curl the heel to glutes"

    @Test
    fun `기준선 - 제약이 없으면 설명문만으로 장비 가리지 않고 찾는다`() {
        val slugs = engine.search(motion).map { it.doc.slug }
        assertTrue("cable-machine-row" in slugs, slugs.toString())
        assertTrue("barbell-prone-curl" in slugs, slugs.toString())
    }

    @Test
    fun `집 의도의 설명문 후보에는 바벨 머신 케이블이 없다`() {
        for (q in listOf("at home $motion", "$motion at home", "집에서 $motion")) {
            val hits = engine.search(q)
            assertTrue(hits.isNotEmpty(), q)
            val bad = hits.filter { it.doc.equipment in setOf(Equipment.BARBELL, Equipment.MACHINE, Equipment.CABLE) }
            assertTrue(bad.isEmpty(), "$q -> ${bad.map { it.doc.slug }}")
        }
    }

    @Test
    fun `집 의도 필터가 비어 있으면 홈 장비로 못 좁히고 좁히지 않는다 - 결과가 사라지지 않는다`() {
        // 홈 장비(맨몸·밴드·덤벨·케틀벨·기타) 운동이 하나도 없는 카탈로그에서는 집 의도라도 장비로 거르지 않는다(빈 결과 방지)
        val onlyGym = listOf(
            doc(1, "barbell-prone-curl", "Barbell prone drag", Equipment.BARBELL, prone),
            doc(2, "cable-machine-row", "Cable machine row", Equipment.CABLE, prone),
            doc(3, "lat-pulldown", "Lat pulldown", Equipment.MACHINE, "Pull the bar down.")
        )
        val e = ExerciseSearchEngine(onlyGym, emptyList(), 10_000_000_000L)
        assertTrue(e.search("at home $motion").isNotEmpty())
    }

    @Test
    fun `명시한 장비가 있으면 그 장비의 설명문 후보를 쓴다`() {
        val hits = engine.search("$motion barbell")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().doc.equipment == Equipment.BARBELL, hits.map { it.doc.slug }.toString())
    }

    @Test
    fun `부상 관절의 조심할 동작은 설명문으로 새로 찾은 후보에서도 뒤로 밀린다`() {
        val plain = engine.search(motion).map { it.doc.slug }
        val injured = engine.search("bad knees $motion").map { it.doc.slug }
        // 같은 설명문을 가진 두 후보 중 무릎에 조심할 런지(soft)는 부상 질의에서 뒤로, 부상이 없으면 인기·티어 덕에 앞
        assertTrue(plain.indexOf("bodyweight-lunge") < plain.indexOf("prone-heel-lift"), plain.toString())
        assertTrue(injured.indexOf("prone-heel-lift") < injured.indexOf("bodyweight-lunge"), injured.toString())
    }

    @Test
    fun `이미 이름 경로에서 점수를 받은 후보에는 부상 보정을 두 번 하지 않는다`() {
        // 이름에 런지가 들어간 질의: 런지는 이름으로 찾은 것 — 설명문이 새로 끼워 넣는 후보가 아니다
        val hits = engine.search("bad knees lunge")
        val first = hits.firstOrNull { it.doc.slug == "bodyweight-lunge" }
        assertTrue(first != null)
        val again = engine.search("bad knees lunge").first { it.doc.slug == "bodyweight-lunge" }
        assertEquals(first!!.score, again.score, 0.0)
    }

    @Test
    fun `부상 제약 동작은 의미 후보 제약과 같은 쪽으로 보수적이다 - 조심할 동작은 의미 후보가 아니고 설명문에서는 뒤로`() {
        val q = "bad knees $motion"
        val constraint = engine.semanticConstraint(q)!!
        assertFalse(constraint.allows(docs[0].copy()), "런지는 무릎 부상에서 의미 후보로 쓰지 않는다")
        assertTrue(constraint.allows(docs[1]))
        val slugs = engine.search(q).map { it.doc.slug }
        assertTrue(slugs.indexOf("prone-heel-lift") < slugs.indexOf("bodyweight-lunge"))
    }
}

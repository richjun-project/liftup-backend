package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.search.engine.QueryLexicon
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 장비 제약 이해 — 쓸 수 없다·쓰기 싫다(부정·점유·고장·금지·못 씀), "그것만 있다", 부상과 장비가 함께인 질의.
 *
 * 한 문장을 맞추는 테스트가 아니라 문형의 계열(ko·ja·en 활용형, 수량어가 낀 말, 부사가 낀 말)이 일반 규칙으로 읽히는지 본다.
 * 사전이 모르는 부정 관용구를 장비 요청으로 오인했을 때 LLM 의 제외를 따르는 구조적 백스톱은 하이브리드 검색에서 확인한다.
 * LLM 응답은 고정한 JSON 이라 네트워크를 쓰지 않는다.
 */
class ConstraintUnderstandingTest {
    private val docs = ExerciseSearchEvalTest.loadCatalogDocs()
    private val engine = ExerciseSearchEngine(docs, emptyList(), 10_000_000_000L)
    private val hybrid = HybridSearch(engine, ExerciseVectors.loadDefault()!!)

    private fun top(q: String, n: Int = 5) = engine.search(q).take(n).map { it.doc }
    private fun equipOf(q: String, n: Int = 5) = top(q, n).mapNotNull { it.equipment }.toSet()
    private fun plan(q: String) = engine.analyze(q)

    /** 질의가 [banned] 장비를 빼 달라는 말로 읽히고 결과 상위에 그 장비가 없어야 한다 */
    private fun assertExcludes(banned: Equipment, vararg queries: String) {
        for (q in queries) {
            val p = plan(q)
            assertTrue(banned in p.excludeEquip, "[$q] 제외 장비로 읽히지 않았다: ${engine.explain(q)}")
            assertFalse(banned in p.equipments, "[$q] 요청 장비로 남았다: ${engine.explain(q)}")
            val out = top(q, 8)
            assertTrue(out.isNotEmpty(), "[$q] 결과가 비었다")
            assertTrue(out.none { it.equipment == banned }, "[$q] 금지 장비가 상위에 있다: ${out.map { it.slug }}")
        }
    }

    // ── 한국어: 안 씀·못 씀·쓰는 중·고장·점검·줄·사람 많음 ─────────────────────────────────────

    @Test
    fun `한국어 - 안 쓴다는 말의 활용형은 그 장비를 뺀다`() {
        assertExcludes(Equipment.BARBELL, "바벨 안 쓸래 하체", "하체운동 바벨 사용 안 해요", "바벨 못 써서 등운동", "바벨은 오늘 사용 불가 어깨")
        assertExcludes(Equipment.DUMBBELL, "덤벨 못 잡겠어 가슴", "덤벨 쓰기 싫은 날 삼두", "덤벨 금지라서 팔")
        assertExcludes(Equipment.KETTLEBELL, "케틀벨 안 씀 엉덩이", "케틀벨은 패스 전신")
    }

    @Test
    fun `한국어 - 점유·고장·점검·대기 표현은 수량어와 부사가 끼어도 그 장비를 뺀다`() {
        assertExcludes(Equipment.DUMBBELL, "덤벨 전부 누가 쓰고 있어서 이두", "덤벨 모두 사용중이라 어깨", "덤벨 코너 사람 엄청 많아서 삼두")
        assertExcludes(Equipment.CABLE, "케이블이 다 점검 중이야 등", "케이블 기구 줄이 너무 길어서 팔", "케이블 고장나서 못 써 가슴")
        assertExcludes(Equipment.MACHINE, "머신 전부 고장이라 하체", "머신 대기 진짜 길어서 가슴")
    }

    // ── 일본어 ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `일본어 - 禁止·パス·無理·使えない·握れない 계열은 그 장비를 뺀다`() {
        assertExcludes(Equipment.BARBELL, "バーベルは禁止 脚", "バーベルは使えない 背中", "バーベルはパスで肩")
        assertExcludes(Equipment.DUMBBELL, "ダンベルは持てない 胸", "ダンベルは無理 腕", "ダンベルは握れなくて二頭筋")
        assertExcludes(Equipment.KETTLEBELL, "ケトルベルはNGで全身", "ケトルベルはいやだ お尻")
    }

    @Test
    fun `일본어 - 全部·使われてる·故障·メンテ·順番待ち 는 수량어를 건너뛰고 그 장비를 뺀다`() {
        assertExcludes(Equipment.DUMBBELL, "ダンベルが全部使われてて肩", "ダンベルが全て埋まってる 胸")
        assertExcludes(Equipment.MACHINE, "マシンが全部故障してる 脚", "マシンはメンテ中で背中", "マシンは順番待ちが長いので脚")
        assertExcludes(Equipment.CABLE, "ケーブルが使用中なので胸", "ケーブルが壊れてるので腕")
    }

    // ── 영어 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `영어 - never·hogging·off limits·can't use·out of order 는 그 장비를 뺀다`() {
        assertExcludes(Equipment.BARBELL, "never barbells, legs", "barbells are off limits, back", "cant use barbells today, shoulders")
        assertExcludes(Equipment.DUMBBELL, "someone is hogging every dumbbell, biceps", "the dumbbells are all taken, shoulders")
        assertExcludes(Equipment.MACHINE, "the machines are out of order, legs", "every machine has a queue, chest")
        assertExcludes(Equipment.CABLE, "cables are down for maintenance, chest", "cable stack is packed, back")
    }

    @Test
    fun `영어 - 장비 뒤에 붙은 no 는 그 장비의 부정이고 yes·ok 는 긍정이다`() {
        val p = plan("cables yes, kettlebells no, glutes")
        assertTrue(Equipment.KETTLEBELL in p.excludeEquip, engine.explain("cables yes, kettlebells no, glutes"))
        assertFalse(Equipment.CABLE in p.excludeEquip)
        val q = plan("bands no, machines ok, back")
        assertTrue(Equipment.RESISTANCE_BAND in q.excludeEquip, engine.explain("bands no, machines ok, back"))
        assertFalse(Equipment.MACHINE in q.excludeEquip)
        // 뒤 장비가 yes 를 달면 앞의 no 는 앞 장비의 것이다
        val r = plan("machines no, dumbbells yes, shoulders")
        assertTrue(Equipment.MACHINE in r.excludeEquip, engine.explain("machines no, dumbbells yes, shoulders"))
        assertFalse(Equipment.DUMBBELL in r.excludeEquip)
        // 앞에서 부정하는 no 는 그대로다
        assertTrue(Equipment.BARBELL in plan("no barbells, chest").excludeEquip)
        assertTrue(Equipment.RESISTANCE_BAND in plan("barbell but no band, legs").excludeEquip)
    }

    // ── "그것만 있다" ────────────────────────────────────────────────────────────────────────

    @Test
    fun `그것만 있다는 말은 그 장비로만 좁힌다`() {
        val cases = listOf(
            "밴드밖에 없어 등" to Equipment.RESISTANCE_BAND,
            "케틀벨만 있는데 전신" to Equipment.KETTLEBELL,
            "バーベルしかなくて脚" to Equipment.BARBELL,
            "ケーブルだけある 胸" to Equipment.CABLE,
            "i only have a kettlebell, glutes" to Equipment.KETTLEBELL,
            "all we have is machines, legs" to Equipment.MACHINE
        )
        for ((q, e) in cases) {
            val out = top(q, 10)
            assertTrue(out.isNotEmpty() && out.all { it.equipment == e }, "[$q] ${out.map { it.slug to it.equipment }}")
        }
    }

    @Test
    fun `벤치와 덤벨만 있다는 말에서 벤치는 운동 이름이 아니라 가진 기구다`() {
        for (q in listOf("벤치랑 덤벨만 있어요", "덤벨이랑 벤치밖에 없는데 어깨", "i just have dumbbells and a bench", "ベンチとダンベルしかない 胸")) {
            val out = top(q, 10)
            assertTrue(out.isNotEmpty() && out.all { it.equipment == Equipment.DUMBBELL }, "[$q] ${out.map { it.slug }}")
        }
    }

    @Test
    fun `그것만 있다는 말은 운동 이름을 말한 이름 검색에서도 그 장비로 좁힌다`() {
        for ((q, e) in listOf(
            "케이블만 있는데 풀오버" to Equipment.CABLE,
            "밴드만 있어 힙 쓰러스트" to Equipment.RESISTANCE_BAND,
            "バーベルしかないのでベンチプレス" to Equipment.BARBELL,
            "i only have dumbbells, squats" to Equipment.DUMBBELL,
            "kettlebell only squats" to Equipment.KETTLEBELL
        )) {
            val out = top(q, 10)
            assertTrue(out.isNotEmpty() && out.all { it.equipment == e }, "[$q] ${out.map { it.slug to it.equipment }}")
        }
        // "만 있는 게 아니라" 는 그것 말고도 있다는 말이다
        assertFalse(plan("not only dumbbells, i have cables too, chest").onlyEquip, engine.explain("not only dumbbells, i have cables too, chest"))
    }

    @Test
    fun `장비 뒤의 are not 은 그 장비의 부정이고 뒤따르는 부위를 지우지 않는다`() {
        val q = "dumbbells are cool, cables are not, shoulders"
        val p = plan(q)
        assertTrue(Equipment.CABLE in p.excludeEquip && Equipment.DUMBBELL !in p.excludeEquip, engine.explain(q))
        assertTrue(p.excludeConcepts.isEmpty() && p.concepts.isNotEmpty(), "어깨가 부정 대상이 되면 안 된다: ${engine.explain(q)}")
        assertTrue(top(q, 8).none { it.equipment == Equipment.CABLE })
    }

    @Test
    fun `장비 바로 뒤의 괜찮아 는 부상 단서가 아니다`() {
        for (q in listOf("케이블은 괜찮아 등", "ケーブルは大丈夫 背中")) {
            val p = plan(q)
            assertTrue(p.injuries.isEmpty() && Equipment.CABLE in p.equipments, "[$q] ${engine.explain(q)}")
        }
        // 부상 부위 뒤의 괜찮은 운동은 여전히 안전한 운동을 묻는 말이다
        assertTrue(QueryLexicon.Joint.KNEE in plan("무릎에 괜찮은 하체운동").injuries)
    }

    @Test
    fun `쓸 수 있는 것을 말하는 긍정 요청은 지우지 않는다`() {
        for ((q, e) in listOf("케이블로만 어깨" to Equipment.CABLE, "machines only back" to Equipment.MACHINE, "ダンベルのみで脚" to Equipment.DUMBBELL, "밴드로만 하체" to Equipment.RESISTANCE_BAND)) {
            val p = plan(q)
            assertTrue(e in p.equipments && p.excludeEquip.isEmpty(), "[$q] ${engine.explain(q)}")
            assertTrue(top(q, 8).all { it.equipment == e }, "[$q] ${top(q, 8).map { it.slug }}")
        }
        // 위주·집중 같은 강조어는 부정이 아니다
        for (q in listOf("머신 위주로 하체", "케이블 위주로 등", "ダンベル中心で肩")) assertTrue(plan(q).excludeEquip.isEmpty(), engine.explain(q))
        // "yes"·"좋아" 가 붙은 장비는 빠지지 않는다
        assertTrue(plan("dumbbells yes, chest").excludeEquip.isEmpty())
    }

    // ── 부상 + 장비·운동 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `부상과 장비 제외가 함께면 둘 다 지킨다`() {
        val cases = listOf(
            Triple("허리 아파서 바벨은 못 써 하체", Equipment.BARBELL, QueryLexicon.Joint.LOWER_BACK),
            Triple("손목이 아파서 케틀벨 못 쥐어 전신", Equipment.KETTLEBELL, QueryLexicon.Joint.WRIST),
            Triple("肩が痛くてダンベルは持てない 胸", Equipment.DUMBBELL, QueryLexicon.Joint.SHOULDER),
            Triple("膝を痛めてマシンは使えない 脚", Equipment.MACHINE, QueryLexicon.Joint.KNEE),
            Triple("sore elbow, can't use cables, triceps", Equipment.CABLE, QueryLexicon.Joint.ELBOW),
            Triple("wrist pain and barbells are out, biceps", Equipment.BARBELL, QueryLexicon.Joint.WRIST)
        )
        for ((q, banned, joint) in cases) {
            val p = plan(q)
            assertTrue(joint in p.injuries, "[$q] 부상 관절이 빠졌다: ${engine.explain(q)}")
            assertTrue(banned in p.excludeEquip, "[$q] 장비 제외가 빠졌다: ${engine.explain(q)}")
            assertTrue(top(q, 8).none { it.equipment == banned }, "[$q] ${top(q, 8).map { it.slug }}")
        }
    }

    @Test
    fun `못하는 운동이 부상 때문이면 그 운동을 빼고 그것만 있는 장비 안에서 찾는다`() {
        val q = "무릎 아파서 스쿼트는 못해 바벨밖에 없음"
        val p = plan(q)
        assertTrue(QueryLexicon.Joint.KNEE in p.injuries, engine.explain(q))
        assertTrue(p.excludeKeys.isNotEmpty() && Equipment.BARBELL in p.equipments, engine.explain(q))
        val out = top(q, 8)
        assertTrue(out.all { it.equipment == Equipment.BARBELL }, out.map { it.slug }.toString())
        assertTrue(out.none { it.slug.contains("squat") }, out.map { it.slug }.toString())

        val en = "knee hurts, can't squat, i only have dumbbells"
        val outEn = top(en, 8)
        assertTrue(outEn.all { it.equipment == Equipment.DUMBBELL } && outEn.none { it.slug.contains("squat") }, outEn.map { it.slug }.toString())
    }

    @Test
    fun `장비 없는 부상 질의의 못한다는 말은 여전히 쉬운 변형을 찾는 말이다`() {
        // 부상·장비가 없으면 "못 해요"는 그 운동을 빼라는 말이 아니라 쉬운 변형(어시스트)을 달라는 말이다
        for (q in listOf("턱걸이 못 해요", "i can't do pull ups yet")) {
            val p = plan(q)
            assertTrue(p.excludeKeys.isEmpty(), "[$q] ${engine.explain(q)}")
            assertEquals(-1, p.level, "[$q] ${engine.explain(q)}")
        }
    }

    @Test
    fun `벤치프레스가 아픈 어깨의 대안을 물으면 벤치 기구가 없다는 뜻이 아니라 운동을 뺀다`() {
        val q = "shoulder pinches when I bench, alternatives"
        val p = plan(q)
        assertTrue(QueryLexicon.Joint.SHOULDER in p.injuries, engine.explain(q))
        assertTrue(p.excludeEquip.isEmpty(), "벤치 기구 부재로 읽으면 머신·케이블이 통째로 빠진다: ${engine.explain(q)}")
        assertTrue(top(q, 8).none { it.slug.contains("bench-press") }, top(q, 8).map { it.slug }.toString())
    }

    // ── 점유된 장비로 하던 운동의 대안 · 랙 없는 바벨 ────────────────────────────────────────────

    @Test
    fun `쓸 수 없는 장비로 하던 운동의 대안은 같은 운동의 다른 장비 버전이다`() {
        val q = "barbells are all taken, need a deadlift substitute"
        val out = top(q, 8)
        assertTrue(out.none { it.equipment == Equipment.BARBELL }, out.map { it.slug }.toString())
        assertTrue(out.count { it.slug.contains("deadlift") } >= 3, "덤벨·케틀벨 데드리프트가 대안이어야 한다: ${out.map { it.slug }}")
        val ko = "바벨이 전부 사용중이라 스쿼트 대신"
        val outKo = top(ko, 8)
        assertTrue(outKo.none { it.equipment == Equipment.BARBELL } && outKo.any { it.slug.contains("squat") }, outKo.map { it.slug }.toString())
    }

    @Test
    fun `랙이 없으면 바벨은 쓰되 스쿼트류만 뺀다`() {
        for (q in listOf("barbell but no squat rack, legs", "바벨은 있는데 랙이 없어서 하체", "バーベルはあるけどラックがなくて脚")) {
            val out = top(q, 8)
            assertTrue(out.isNotEmpty() && out.all { it.equipment == Equipment.BARBELL }, "[$q] ${out.map { it.slug }}")
            assertTrue(out.none { it.slug.contains("squat") }, "[$q] ${out.map { it.slug }}")
        }
    }

    @Test
    fun `질린 운동의 변형을 달라는 말은 그 운동을 빼는 말이 아니다`() {
        val q = "tired of normal squats, variation ideas"
        assertTrue(plan(q).excludeKeys.isEmpty(), engine.explain(q))
        assertTrue(top(q, 5).any { it.slug.contains("squat") }, top(q, 5).map { it.slug }.toString())
        // 변형을 달라는 말이 없는 "질렸다"는 여전히 제외다
        assertTrue(plan("sick of barbells, legs").excludeEquip.contains(Equipment.BARBELL))
    }

    // ── 장비를 꾸미는 모르는 말 ──────────────────────────────────────────────────────────────

    @Test
    fun `쓸 수 없다는 말 앞에 장비를 꾸미는 모르는 말이 끼어도 그 장비를 뺀다`() {
        assertExcludes(Equipment.DUMBBELL, "ダンベルの置き場が混みすぎて使えない 肩", "덤벨 있는 쪽이 너무 꽉 차서 가슴")
        assertExcludes(Equipment.KETTLEBELL, "ケトルベルは握力的に無理 全身")
    }

    @Test
    fun `스미스 머신은 일반 머신이 아니라 기구 하나다`() {
        val q = "smith machine is being used, squat alternatives"
        val p = plan(q)
        assertFalse(Equipment.MACHINE in p.excludeEquip, "스미스 하나가 막혔다고 머신 전체를 빼면 안 된다: ${engine.explain(q)}")
        val out = top(q, 8)
        assertTrue(out.none { it.slug.contains("smith") }, out.map { it.slug }.toString())
        assertTrue(out.count { it.slug.contains("squat") } >= 3, out.map { it.slug }.toString())
    }

    @Test
    fun `케이블 머신이 막혔다는 말은 케이블을 뺀다 - 머신 전체가 아니다`() {
        for (q in listOf("케이블 머신 줄 길어서 삼두", "the cable machine is jammed, back", "ケーブルマシンが使用中なので背中")) {
            val p = plan(q)
            assertTrue(Equipment.CABLE in p.excludeEquip && Equipment.MACHINE !in p.excludeEquip, "[$q] ${engine.explain(q)}")
        }
    }

    // ── 사전이 모르는 부정 관용구: LLM 의 제외를 따른다(구조적 백스톱) ──────────────────────────────

    private fun fused(q: String, rw: QueryRewrite, equipments: Set<Equipment>? = null): List<ExerciseSearchDoc> =
        hybrid.search(q, null, equipments, "en", { rw }) { null }.hits.take(8).map { it.doc }

    @Test
    fun `모르는 말이 낀 질의에서 사전이 요청으로 읽은 장비를 LLM 이 제외하면 그 장비를 뺀다`() {
        // "zorbled"·"nixed" 는 사전에 없는 말 — 사전은 케틀벨을 요청으로 읽는다
        val q = "kettlebells are totally zorbled today, full body"
        val c = engine.semanticConstraint(q)!!
        assertTrue(Equipment.KETTLEBELL in c.lexiconEquipment, engine.explain(q))
        val out = fused(q, QueryRewrite(listOf("Burpee", "Jumping Jack", "Bodyweight Squat"), setOf(Equipment.KETTLEBELL)))
        assertTrue(out.isNotEmpty() && out.none { it.equipment == Equipment.KETTLEBELL }, out.map { it.slug }.toString())
    }

    @Test
    fun `LLM 자신의 힌트가 그 장비 운동을 가리키면 사전의 읽기를 둔다`() {
        val q = "kettlebells are totally zorbled today, full body"
        val out = fused(q, QueryRewrite(listOf("Kettlebell Swing", "Goblet Squat"), setOf(Equipment.KETTLEBELL)))
        assertTrue(out.any { it.equipment == Equipment.KETTLEBELL }, "힌트가 케틀벨을 가리키면 제외를 따르지 않는다: ${out.map { it.slug }}")
    }

    @Test
    fun `사전이 질의를 다 알아들었으면 LLM 의 제외로 요청 장비를 바꾸지 않는다`() {
        val q = "kettlebell full body"
        assertTrue(engine.semanticConstraint(q)!!.lexiconEquipment.isEmpty())
        val out = fused(q, QueryRewrite(listOf("Burpee"), setOf(Equipment.KETTLEBELL)))
        assertTrue(out.any { it.equipment == Equipment.KETTLEBELL }, out.map { it.slug }.toString())
    }

    @Test
    fun `호출자가 고른 장비 필터는 백스톱보다 언제나 이긴다`() {
        val q = "kettlebells are totally zorbled today, full body"
        val out = fused(q, QueryRewrite(listOf("Burpee", "Jumping Jack"), setOf(Equipment.KETTLEBELL)), equipments = setOf(Equipment.KETTLEBELL))
        assertTrue(out.isNotEmpty() && out.all { it.equipment == Equipment.KETTLEBELL }, out.map { it.slug }.toString())
    }

    @Test
    fun `LLM 이 힌트를 내지 않으면 백스톱은 근거가 없어 사전의 읽기를 둔다`() {
        val q = "kettlebells are totally zorbled today, full body"
        val out = fused(q, QueryRewrite(emptyList(), setOf(Equipment.KETTLEBELL)))
        assertTrue(out.isNotEmpty() && out.all { it.equipment == Equipment.KETTLEBELL }, out.map { it.slug }.toString())
    }
}

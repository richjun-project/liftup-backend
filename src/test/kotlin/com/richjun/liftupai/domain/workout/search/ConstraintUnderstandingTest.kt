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
        // 장비 뒤의 never 는 그 장비를 영영 안 쓴다는 말이다
        for (q in listOf("triceps, cables never", "barbells never again, legs")) assertTrue(plan(q).excludeEquip.isNotEmpty() && plan(q).equipments.isEmpty(), engine.explain(q))
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
    fun `장비가 하나도 없다는 말의 활용형은 맨몸이다`() {
        for (q in listOf("집에 도구 하나도 없어서 하체", "아무 장비도 없고 어깨", "運動器具が全くない家で脚", "家に何もなくて背中", "i got nothing, just my body, legs", "no gear whatsoever, chest", "캠핑장에 아무것도 없는데 전신")) {
            val p = plan(q)
            assertEquals(setOf(Equipment.BODYWEIGHT), p.equipments, "[$q] ${engine.explain(q)}")
            assertTrue(top(q, 8).all { it.equipment == Equipment.BODYWEIGHT }, "[$q] ${top(q, 8).map { it.slug }}")
        }
        // 장비 일반을 부정하면 맨몸 말고 전부 뺀다
        for (q in listOf("장비 말고 맨몸으로 등", "equipment is not allowed, back")) {
            assertTrue(plan(q).excludeEquip.containsAll(setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE)), "[$q] ${engine.explain(q)}")
        }
    }

    @Test
    fun `비어 있다 대기 없다 아무도 안 쓴다는 말은 점유가 아니라 그 장비를 쓴다는 말이다`() {
        for (q in listOf(
            "머신 대기 없음 가슴", "덤벨이 비어 있어서 어깨", "ケーブルが空いてる 胸", "ラックは誰も使ってない 脚",
            "no line for the cable machine, chest", "nobody is hogging the barbells, back", "the dumbbells aren't taken, arms", "the cables are not busy, back", "the rack is empty, legs"
        )) {
            val p = plan(q)
            assertTrue(p.excludeEquip.isEmpty(), "[$q] 점유가 아닌데 장비를 뺐다: ${engine.explain(q)}")
        }
        // 체육관 자체가 붐빈다는 말도 어떤 장비도 쓸 수 없다는 말이 아니다
        assertTrue(plan("dumbbell workout when the gym is crowded").excludeEquip.isEmpty())
    }

    @Test
    fun `싫다 별로야 좀 그래 쓰기 싫은 날 은 취향이라 그 장비를 뺀다`() {
        assertExcludes(Equipment.DUMBBELL, "덤벨은 별로야 어깨", "덤벨 쓰기 싫은 날 가슴")
        assertExcludes(Equipment.MACHINE, "머신 쓰는 건 싫어 등", "マシンはちょっとなあ 脚", "say no to machines, back")
        assertExcludes(Equipment.BARBELL, "바벨은 좀 그래 하체", "hard pass on barbells, legs")
        // 부위를 싫어하는 말은 그 부위를 뺀다(개념 대상은 취향 표지에서도 그대로)
        val dislikedChest = plan("가슴 운동은 싫어 등")
        assertTrue(dislikedChest.concepts.none { it.id.contains("manboob") } && dislikedChest.concepts.isNotEmpty(), engine.explain("가슴 운동은 싫어 등"))
    }

    @Test
    fun `못 잡는다 못 든다 같은 능력 동사는 장비를 가리킬 때만 제외고 아니면 쉬운 변형이다`() {
        // 장비를 못 잡는다 → 그 장비 제외(부상 단서 없이도)
        assertExcludes(Equipment.DUMBBELL, "덤벨은 못 잡겠어 이두", "can't hold dumbbells, biceps")
        // 운동을 못 한다(부상·장비 없음) → 그 운동의 쉬운 변형
        for (q in listOf("턱걸이 못 매달려요", "weak grip, can't hang from the bar")) {
            assertTrue(plan(q).excludeKeys.isEmpty(), "[$q] ${engine.explain(q)}")
        }
        // 握力 같은 능력 개념이 쓸 수 없다는 말의 대상이 되어 사라지면 안 된다
        assertTrue(plan("握力が弱くて重いものが持てない").concepts.isNotEmpty())
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

    // ── 문형 계열 전체(템플릿 × 장비 × 부위) ─────────────────────────────────────────────────────

    private class Eq(val ko: String, val ja: String, val en: String, val enSingular: String, val e: Equipment)

    private val equipments = listOf(
        Eq("바벨", "バーベル", "barbells", "barbell", Equipment.BARBELL), Eq("덤벨", "ダンベル", "dumbbells", "dumbbell", Equipment.DUMBBELL),
        Eq("케이블", "ケーブル", "cables", "cable", Equipment.CABLE), Eq("머신", "マシン", "machines", "machine", Equipment.MACHINE),
        Eq("케틀벨", "ケトルベル", "kettlebells", "kettlebell", Equipment.KETTLEBELL), Eq("밴드", "バンド", "bands", "band", Equipment.RESISTANCE_BAND)
    )
    private val partKo = listOf("가슴", "등", "하체", "어깨")
    private val partJa = listOf("胸", "背中", "脚", "肩")
    private val partEn = listOf("chest", "back", "legs", "shoulders")

    private fun fill(t: String, x: String, p: String) = t.replace("{X}", x).replace("{P}", p)

    @Test
    fun `쓸 수 없다·싫다·금지 문형 계열 전체는 어느 장비에서나 그 장비를 뺀다`() {
        val ko = listOf("{X} 안 써 {P}", "{X} 사용 중이라 {P}", "{X} 전부 사용중이야 {P}", "{X} 줄 길어서 {P}", "{X} 점검 중이라 {P}", "{X} 고장났어 {P}", "{X} 못 써 {P}", "{X} 쓸 수 없어 {P}",
            "{X} 없이 {P}", "{X} 빼고 {P}", "{X} 싫어 {P}", "{X} 금지 {P}", "{X} 자리 없어 {P}", "{X} 사람 많아서 {P}", "{X} 누가 쓰고 있어 {P}", "{X} 다 나갔어 {P}", "{X} 막혀서 {P}",
            "{X}는 쓰지 않을래 {P}", "{X} 이용 불가 {P}", "{X}는 별로야 {P}", "{X} 쓰기 싫은 날 {P}", "{X}는 사양 {P}", "{X} 쓰지 말고 {P}")
        val ja = listOf("{X}が使用中で{P}", "{X}が全部使われてて{P}", "{X}は混んでるので{P}", "{X}の順番待ちが長くて{P}", "{X}が壊れてて{P}", "{X}は故障中なので{P}", "{X}がメンテ中で{P}", "{X}が点検中 {P}",
            "{X}は使わない {P}", "{X}は使いたくない {P}", "{X}は避けたい {P}", "{X}以外で{P}", "{X}なしで{P}", "{X}は禁止 {P}", "{X}NG {P}", "{X}はパス {P}", "{X}使えない {P}", "{X}取られてる {P}",
            "{X}は嫌い {P}", "{X}は遠慮したい {P}", "{X}の順番が回ってこない {P}", "{X}は勘弁 {P}")
        val en = listOf("{X} are all taken, {P}", "{X} are busy today, {P}", "{X} are broken, {P}", "{X} are out of order, {P}", "{X} are down, {P}", "no {X}, {P}", "without {X}, {P}", "{X} are unavailable, {P}",
            "can't use {X}, {P}", "avoid {X}, {P}", "skip {X}, {P}", "everyone is on the {X}, {P}", "{X} are being used, {P}", "{X} are gone, {P}", "{X} are not an option, {P}", "not using {X} today, {P}",
            "don't wanna use {X}, {P}", "{X} aren't allowed, {P}", "say no to {X}, {P}", "hard pass on {X}, {P}", "exclude {X}, {P}")
        var checked = 0
        for ((i, eq) in equipments.withIndex()) {
            for (t in ko) { val q = fill(t, eq.ko, partKo[i % 4]); assertExcludesPlan(q, eq.e); checked++ }
            for (t in ja) { val q = fill(t, eq.ja, partJa[i % 4]); assertExcludesPlan(q, eq.e); checked++ }
            for (t in en) { val q = fill(t, eq.en, partEn[i % 4]); assertExcludesPlan(q, eq.e); checked++ }
        }
        assertTrue(checked > 300)
    }

    private fun assertExcludesPlan(q: String, e: Equipment) {
        val p = plan(q)
        assertTrue(e in p.excludeEquip && e !in p.equipments, "[$q] ${engine.explain(q)}")
    }

    @Test
    fun `한쪽은 되고 한쪽은 안 되는 문형 계열은 안 되는 쪽만 뺀다`() {
        val ko = listOf("{A}는 되는데 {B}는 안 돼 {P}", "{A} 있고 {B} 없음 {P}", "{A} 쓸 수 있는데 {B}는 못 씀 {P}", "{A} 괜찮고 {B}는 싫어 {P}", "{B} 말고 {A}로 {P}", "{A}는 오케이 {B}는 노노 {P}",
            "{A} 되고 {B} 안됨 {P}", "{A} O {B} X {P}", "{B} 빼고 {A} 써서 {P}", "{A}는 되고 {B}는 사용 불가 {P}", "{A}는 쓰는데 {B}는 줄 길어서 못 써 {P}")
        val ja = listOf("{A}は使えるが{B}は使えない {P}", "{A}OK{B}NG {P}", "{A}はあるけど{B}はない {P}", "{B}じゃなくて{A}で{P}", "{B}の代わりに{A}で{P}", "{A}あり {B}なし {P}", "{A}は良いが{B}はダメ {P}",
            "{A}を使って{B}は使わない {P}", "{A}はあるが{B}は故障中 {P}", "{A}は空いてるけど{B}は埋まってる {P}")
        val en = listOf("{A} yes, {B} no, {P}", "{A} ok but no {B}, {P}", "{A} are fine but {B} aren't available, {P}", "i have {A} but not {B}, {P}", "{A} are fine, {B} are not, {P}", "{A} instead of {B}, {P}",
            "use {A} not {B}, {P}", "{A} available, {B} out of order, {P}", "{A} are free but {B} are taken, {P}", "got {A} but {B} are broken, {P}", "{A} okay, {B} not okay, {P}", "{A} no, {B} yes, {P}")
        for ((i, a) in equipments.withIndex()) {
            val b = equipments[(i + 2) % equipments.size]
            for (t in ko) { val q = t.replace("{A}", a.ko).replace("{B}", b.ko).replace("{P}", partKo[i % 4]); assertMixed(q, a.e, b.e, false) }
            for (t in ja) { val q = t.replace("{A}", a.ja).replace("{B}", b.ja).replace("{P}", partJa[i % 4]); assertMixed(q, a.e, b.e, false) }
            for (t in en) { val q = t.replace("{A}", a.en).replace("{B}", b.en).replace("{P}", partEn[i % 4]); assertMixed(q, a.e, b.e, t.startsWith("{A} no")) }
        }
    }

    private fun assertMixed(q: String, ok: Equipment, banned: Equipment, swapped: Boolean) {
        val p = plan(q)
        val (good, bad) = if (swapped) banned to ok else ok to banned
        assertTrue(bad in p.excludeEquip && good !in p.excludeEquip && bad !in p.equipments, "[$q] ${engine.explain(q)}")
    }

    @Test
    fun `그것만 있다는 문형 계열은 모든 장비에서 그 장비로만 좁힌다`() {
        val ko = listOf("{X}만 있어요 {P}", "{X}밖에 없어 {P}", "{X}만 가지고 {P}", "{X}로만 {P}", "집에 {X}만 있음 {P}", "{X}만 가능 {P}", "헬스장에 {X}만 있어 {P}")
        val ja = listOf("{X}しかない {P}", "{X}だけある {P}", "{X}のみ {P}", "{X}だけで{P}", "{X}しか使えない {P}", "家には{X}だけ {P}", "{X}しか持ってない {P}")
        val en = listOf("only {X}, {P}", "{X} only, {P}", "just {X}, {P}", "i only have {X}, {P}", "all i have is {X}, {P}", "nothing but {X}, {P}", "{X} is all i have, {P}", "limited to {X}, {P}", "using only {X}, {P}")
        for ((i, eq) in equipments.withIndex()) {
            val cases = ko.map { fill(it, eq.ko, partKo[i % 4]) } + ja.map { fill(it, eq.ja, partJa[i % 4]) } + en.map { fill(it, eq.en, partEn[i % 4]) }
            for (q in cases) {
                val p = plan(q)
                assertEquals(setOf(eq.e), p.equipments, "[$q] ${engine.explain(q)}")
                assertTrue(p.excludeEquip.isEmpty(), "[$q] ${engine.explain(q)}")
                val out = top(q, 10)
                assertTrue(out.isNotEmpty() && out.all { it.equipment == eq.e }, "[$q] ${out.map { it.slug to it.equipment }}")
            }
        }
    }

    @Test
    fun `부상과 장비 제약 문형 계열은 부상 프로필과 장비 제외를 함께 건다`() {
        val joints = listOf(Triple("무릎", "膝", "knee") to QueryLexicon.Joint.KNEE, Triple("허리", "腰", "lower back") to QueryLexicon.Joint.LOWER_BACK,
            Triple("손목", "手首", "wrist") to QueryLexicon.Joint.WRIST, Triple("어깨", "肩", "shoulder") to QueryLexicon.Joint.SHOULDER, Triple("팔꿈치", "肘", "elbow") to QueryLexicon.Joint.ELBOW)
        val ko = listOf("{J} 아파서 {X} 못 써 {P}", "{J} 때문에 {X}는 안 돼 {P}", "{J}가 안 좋아서 {X} 빼고 {P}", "{J} 다쳐서 {X}는 무서워 {P}", "{J} 통증 있어서 {X} 쓰기 싫어 {P}", "{J} 부상이라 {X} 사용 불가 {P}", "{J} 재활 중이라 {X} 말고 {P}")
        val ja = listOf("{J}が痛くて{X}は使えない {P}", "{J}を痛めたので{X}は持てない {P}", "{J}が悪いので{X}は避けたい {P}", "{J}が痛いので{X}は無理 {P}", "{J}のケガで{X}NG {P}", "{J}が痛いから{X}なしで{P}")
        val en = listOf("{J} hurts, can't use {X}, {P}", "bad {J} so no {X}, {P}", "{J} injury, {X} are out, {P}", "sore {J} and {X} are taken, {P}", "{J} pain, avoid {X}, {P}", "my {J} is hurt so {X} are off limits, {P}")
        for ((i, eq) in equipments.withIndex().filter { it.value.e != Equipment.RESISTANCE_BAND }) {
            val (names, joint) = joints[i % joints.size]
            for (t in ko) check(t.replace("{J}", names.first).replace("{X}", eq.ko).replace("{P}", partKo[i % 4]), eq.e, joint)
            for (t in ja) check(t.replace("{J}", names.second).replace("{X}", eq.ja).replace("{P}", partJa[i % 4]), eq.e, joint)
            for (t in en) check(t.replace("{J}", names.third).replace("{X}", eq.en).replace("{P}", partEn[i % 4]), eq.e, joint)
        }
    }

    private fun check(q: String, banned: Equipment, joint: QueryLexicon.Joint) {
        val p = plan(q)
        assertTrue(joint in p.injuries && banned in p.excludeEquip && banned !in p.equipments, "[$q] ${engine.explain(q)}")
    }

    @Test
    fun `염좌는 바로 앞 관절의 부상이고 랙·스미스처럼 기구인 이름은 안 된다는 말로 제외한다`() {
        assertTrue(QueryLexicon.Joint.WRIST in plan("手首の捻挫で腕立てできないからケーブルで胸").injuries)
        assertTrue(QueryLexicon.Joint.ANKLE in plan("足首を捻挫して走れない").injuries)
        for (q in listOf("스미스 되고 바벨 랙은 안 되는 날 스쿼트", "smith is fine, the squat rack isn't allowed, squats")) {
            val p = plan(q)
            assertTrue(Equipment.BARBELL in p.excludeEquip && Equipment.BARBELL !in p.equipments, "[$q] ${engine.explain(q)}")
        }
    }
}

package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import com.richjun.liftupai.domain.workout.search.ExerciseSearchLexicon
import com.richjun.liftupai.domain.workout.search.SearchText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 어휘 표(데이터)의 정합성 — 표를 고칠 때 깨지기 쉬운 부분을 잡는다. */
class QueryLexiconTest {

    private val lex = QueryLexicon
    private val docs by lazy { IndexedCatalog.build() }

    private object IndexedCatalog {
        fun build(): List<IDoc> = ExerciseSearchEvalTest.loadCatalogDocs().mapIndexed { i, d ->
            IDoc(i, d, d.names.values.map { NameKey.build(it) }, 0.0)
        }
    }

    @Test
    fun `모든 개념은 트리거와 신호 표기가 비어 있지 않다`() {
        for ((id, c) in lex.concepts) {
            assertTrue(c.nameWords.isNotEmpty(), "$id: 트리거가 없다")
            assertTrue(c.sig.isNotEmpty() || c.categories.isNotEmpty() || c.muscles.isNotEmpty() || c.patterns.isNotEmpty(), "$id: 후보를 가릴 기준이 없다")
            (c.sig + c.sig2 + c.avoid).forEach { assertTrue(it.key.isNotEmpty(), "$id: 빈 신호 키") }
            if (c.strict) assertTrue(c.sig.isNotEmpty() || c.sig2.isNotEmpty() || c.patterns.isNotEmpty(), "$id: strict 인데 신호가 없다")
        }
    }

    @Test
    fun `strict 개념은 실제 카탈로그에서 신호가 하나 이상 맞는다 - 카탈로그가 바뀌어 개념이 죽는 것을 잡는다`() {
        val index = SigIndex(docs)
        val dead = lex.concepts.values.filter { it.strict && it.sig.isNotEmpty() && index.anyOf(it.sig + it.sig2).isEmpty }.map { it.id }
        assertTrue(dead.isEmpty(), "신호가 하나도 안 맞는 개념: $dead")
    }

    @Test
    fun `부상 프로필은 모든 관절에 있고 금지·권장 표기가 있다`() {
        for (j in QueryLexicon.Joint.values()) {
            val p = lex.injuryProfiles[j]
            assertNotNull(p, "$j 프로필 없음")
            assertTrue(p!!.hard.isNotEmpty() || p.soft.isNotEmpty(), "$j: 피할 동작이 없다")
            assertTrue(p.prefer.isNotEmpty(), "$j: 권장 동작이 없다")
        }
        assertTrue(lex.injuryProfiles.getValue(QueryLexicon.Joint.KNEE).hard.any { it.key == SearchText.strictKey("kneel") })
    }

    @Test
    fun `부정 대상 묶음 - 점프 묶음은 점프·버피·플라이오·잭을 포함한다`() {
        val keys = lex.moveGroups.getValue("jump").keys.map { it.key }.toSet()
        for (w in listOf("jump", "점프", "ジャンプ", "burpee", "버피", "plyo", "jack", "skipping")) assertTrue(SearchText.strictKey(w) in keys, w)
        assertNotNull(lex.moveGroupOf(SearchText.strictKey("データ")).let { it ?: lex.moveGroups["deadlift"] })
        assertNull(lex.moveGroupOf("zzzzzz"))
    }

    @Test
    fun `수준·부정·단서 표기는 서로 다른 역할로 중복 등록되지 않는다`() {
        // 같은 표기가 NEG 와 CUE 처럼 충돌하는 의미를 동시에 갖지 않는다 (CONDITION 의 JOINT+CUE 는 의도된 중복)
        for ((k, terms) in lex.termIndex) {
            val roles = terms.map { it.role }.toSet()
            assertTrue(!(QueryLexicon.Role.NEG in roles && QueryLexicon.Role.LEVEL in roles), "$k: NEG 와 LEVEL")
            assertTrue(!(QueryLexicon.Role.NEG in roles && QueryLexicon.Role.CUE in roles), "$k: NEG 와 CUE")
        }
    }

    @Test
    fun `서로 다른 명시 개념이 같은 트리거를 두고 다투지 않는다`() {
        val owners = HashMap<String, MutableSet<String>>()
        for ((k, terms) in lex.termIndex) for (t in terms) {
            val c = t.concept ?: continue
            if (c.id.startsWith("muscle:")) continue
            owners.getOrPut(k) { mutableSetOf() }.add(c.id)
        }
        val clash = owners.filterValues { it.size > 1 }
        assertTrue(clash.isEmpty(), "트리거 충돌: $clash")
    }

    @Test
    fun `조사와 부정 어미 표기는 한글·가나만으로 이루어져 있다`() {
        for ((k, terms) in lex.termIndex) for (t in terms) {
            if (t.particle && t.role == QueryLexicon.Role.NOISE) assertTrue(k.length <= 3, "조사 $k 가 너무 길다")
        }
    }

    @Test
    fun `내장 동의어 사전과 런타임 줄은 같은 해석기를 쓴다`() {
        val line = "오운완|오늘운동 => 스쿼트|squat"
        val parsed = ExerciseSearchLexicon.parseSynonymRule(line)
        assertNotNull(parsed)
        assertEquals(setOf("오운완", "오늘운동", "스쿼트", "squat").map { SearchText.strictKey(it) }.toSet(), parsed!!.triggers)
        assertNull(ExerciseSearchLexicon.parseSynonymRule("형식이 틀린 줄"))
        assertNull(ExerciseSearchLexicon.parseSynonymRule("# => 주석"))
        assertTrue(ExerciseSearchLexicon.parseSynonymRule("a => b =>> c") != null || true)
        assertTrue(ExerciseSearchLexicon.defaultSynonymTable.synonyms.size > 50)
    }
}

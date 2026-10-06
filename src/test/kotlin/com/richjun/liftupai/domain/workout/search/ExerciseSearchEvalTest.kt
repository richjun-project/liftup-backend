package com.richjun.liftupai.domain.workout.search

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 검색 엔진 평가 하네스.
 * 기본 평가셋: search-eval/dev.json (classpath). 다른 파일은 -DsearchEval.file=... 또는 환경변수 SEARCH_EVAL_FILE.
 * dev.json 에 대해서만 임계값(hit@5, MRR, forbid 위반 0)을 강제한다.
 */
class ExerciseSearchEvalTest {

    private val mapper = ObjectMapper()

    private data class EvalCase(
        val id: String,
        val locale: String,
        val query: String,
        val category: ExerciseCategory?,
        val equipments: Set<Equipment>?,
        val acceptable: Set<String>,
        val top1: Set<String>,
        val tags: List<String>,
        val k: Int,
        val forbid: Set<Equipment>
    )

    private class Outcome(val case: EvalCase, val top: List<String>, val rank: Int?, val hit1: Boolean, val hitK: Boolean, val violation: Boolean)

    companion object {
        const val HIT5_MIN = 0.92
        const val MRR_MIN = 0.75

        fun loadCatalogDocs(mapper: ObjectMapper = ObjectMapper()): List<ExerciseSearchDoc> {
            val stream = ExerciseSearchEvalTest::class.java.getResourceAsStream("/catalog/exercise-catalog.json")
                ?: error("catalog/exercise-catalog.json not found")
            val root = mapper.readTree(stream)
            return root.mapIndexed { i, n -> toDoc(i + 1L, n) }
        }

        private fun toDoc(id: Long, n: JsonNode): ExerciseSearchDoc {
            val names = n["translations"].associate { it["locale"].asText() to it["name"].asText() }
            return ExerciseSearchDoc(
                id = id,
                slug = n["slug"].asText(),
                names = names,
                category = ExerciseCategory.valueOf(n["category"].asText()),
                equipment = n["equipment"]?.takeIf { !it.isNull }?.asText()?.let { runCatching { Equipment.valueOf(it) }.getOrNull() },
                muscleGroups = n["muscleGroups"].map { MuscleGroup.valueOf(it.asText()) }.toSet(),
                movementPattern = n["movementPattern"]?.takeIf { !it.isNull }?.asText(),
                tier = RecommendationTier.valueOf(n["recommendationTier"].asText()),
                popularity = n["popularity"].asInt(50),
                difficulty = n["difficulty"].asInt(50)
            )
        }
    }

    private fun parseEquip(raw: String?): Set<Equipment>? =
        raw?.takeIf { it.isNotBlank() }?.split(",")?.map { Equipment.valueOf(it.trim().uppercase()) }?.toSet()

    private fun loadCases(node: JsonNode): List<EvalCase> = node["cases"].map { c ->
        EvalCase(
            id = c["id"].asText(),
            locale = c["locale"].asText(),
            query = c["query"]?.takeIf { !it.isNull }?.asText().orEmpty(),
            category = c["category"]?.takeIf { !it.isNull }?.asText()?.let { ExerciseCategory.valueOf(it.uppercase()) },
            equipments = parseEquip(c["equipment"]?.takeIf { !it.isNull }?.asText()),
            acceptable = c["acceptable"].map { it.asText() }.toSet(),
            top1 = c["top1"]?.map { it.asText() }?.toSet().orEmpty(),
            tags = c["tags"]?.map { it.asText() }.orEmpty(),
            k = c["k"]?.asInt(5) ?: 5,
            forbid = c["forbid_equipment_top5"]?.map { Equipment.valueOf(it.asText()) }?.toSet().orEmpty()
        )
    }

    @Test
    fun `검색 평가셋 점수`() {
        val override = System.getProperty("searchEval.file")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SEARCH_EVAL_FILE")?.takeIf { it.isNotBlank() }
        val isDev = override == null
        val evalNode = if (override != null) mapper.readTree(File(override))
        else mapper.readTree(javaClass.getResourceAsStream("/search-eval/dev.json") ?: error("search-eval/dev.json not found"))

        val docs = loadCatalogDocs(mapper)
        val engine = ExerciseSearchEngine(docs)
        val cases = loadCases(evalNode)

        val outcomes = cases.map { c ->
            val hits = engine.search(c.query, c.category, c.equipments, c.locale)
            val slugs = hits.take(50).map { it.doc.slug }
            val rankIdx = slugs.indexOfFirst { it in c.acceptable }
            val rank = if (rankIdx >= 0) rankIdx + 1 else null
            val kEff = minOf(c.k, 5)
            val top1Set = c.top1.ifEmpty { c.acceptable }
            Outcome(
                case = c,
                top = slugs.take(5),
                rank = rank,
                hit1 = slugs.firstOrNull()?.let { it in top1Set } ?: false,
                hitK = rank != null && rank <= kEff,
                violation = c.forbid.isNotEmpty() && hits.take(kEff).any { it.doc.equipment in c.forbid }
            )
        }

        val n = outcomes.size
        val hit1 = outcomes.count { it.hit1 }.toDouble() / n
        val hit5 = outcomes.count { it.hitK }.toDouble() / n
        val mrr = outcomes.sumOf { o -> o.rank?.let { 1.0 / it } ?: 0.0 } / n
        val violations = outcomes.count { it.violation }

        val sb = StringBuilder()
        sb.append("\n=== SEARCH EVAL (n=$n) ===\n")
        sb.append(String.format("hit@1=%.4f hit@5=%.4f MRR=%.4f forbid_violations=%d\n", hit1, hit5, mrr, violations))
        fun breakdown(title: String, groups: Map<String, List<Outcome>>) {
            sb.append("-- $title\n")
            groups.toSortedMap().forEach { (k, v) ->
                sb.append(String.format("  %-18s n=%3d hit@1=%.3f hit@5=%.3f mrr=%.3f\n", k, v.size,
                    v.count { it.hit1 }.toDouble() / v.size, v.count { it.hitK }.toDouble() / v.size,
                    v.sumOf { o -> o.rank?.let { 1.0 / it } ?: 0.0 } / v.size))
            }
        }
        breakdown("per-locale", outcomes.groupBy { it.case.locale })
        breakdown("per-tag", outcomes.flatMap { o -> o.case.tags.map { it to o } }.groupBy({ it.first }, { it.second }))
        val failing = outcomes.filter { !it.hitK || it.violation || !it.hit1 }
        sb.append("-- failing (miss@k / forbid / top1 miss): ${failing.size}\n")
        failing.forEach { o ->
            val kind = buildList {
                if (!o.hitK) add("MISS")
                if (o.violation) add("FORBID")
                if (!o.hit1) add("top1")
            }.joinToString("+")
            sb.append("  [$kind] ${o.case.id} (${o.case.locale}) q='${o.case.query}' cat=${o.case.category} eq=${o.case.equipments} rank=${o.rank} top=${o.top}\n")
        }
        println(sb)

        if (isDev) {
            assertEquals(0, violations, "forbid 위반이 있다")
            assertTrue(hit5 >= HIT5_MIN, "hit@5 $hit5 < $HIT5_MIN")
            assertTrue(mrr >= MRR_MIN, "MRR $mrr < $MRR_MIN")
        }
    }
}

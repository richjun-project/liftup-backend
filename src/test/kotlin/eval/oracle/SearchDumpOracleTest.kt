package eval.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 평가 오라클(검색 품질 게이트 전용) — 채점은 하지 않고, 평가셋의 각 질의에 대한 엔진 상위 10개를 그대로 JSON 으로 내보낸다.
 * 채점은 저장소 밖의 독립 스크립트가 한다(튜닝하는 쪽이 채점 기준을 바꿀 수 없게).
 *
 * 실행: ./gradlew test --tests 'eval.oracle.SearchDumpOracleTest' -DsearchDump.in=<eval.json> -DsearchDump.out=<out.json>
 * -DsearchDump.qvec=(질의 원문 → 실수 배열 JSON 파일) 을 주면 어휘 엔진 위에 의미 검색을 섞은 하이브리드 결과를 내고 출력에 "semantic": true 를 적는다.
 * 거기에 -DsearchDump.rewrites=(질의 원문 → {"hints":[...],"forbid_equipment":[...]} JSON 파일)을 더하면 LLM 질의 해석까지 포함한 전체 파이프라인을 돌리고 "rewrite": true 를 적는다
 * (질의 해석은 미리 만들어 파일로 받는다 — 테스트는 네트워크를 쓰지 않는다). rewrites 는 qvec 과 함께일 때만 쓴다.
 * 속성이 없으면 아무것도 하지 않는다(일반 테스트 실행에서는 no-op).
 */
class SearchDumpOracleTest {

    @Test
    fun dump() {
        val input = System.getProperty("searchDump.in")?.takeIf { it.isNotBlank() } ?: return
        val output = System.getProperty("searchDump.out")?.takeIf { it.isNotBlank() } ?: error("searchDump.out is required")
        val mapper = ObjectMapper()
        val catalog = mapper.readTree(javaClass.getResourceAsStream("/catalog/exercise-catalog.json") ?: error("catalog not found"))
        val docs = catalog.mapIndexed { i, n ->
            ExerciseSearchDoc(
                id = i + 1L,
                slug = n["slug"].asText(),
                names = n["translations"].associate { it["locale"].asText() to it["name"].asText() },
                category = ExerciseCategory.valueOf(n["category"].asText()),
                equipment = n["equipment"]?.takeIf { !it.isNull }?.asText()?.let { runCatching { Equipment.valueOf(it) }.getOrNull() },
                muscleGroups = n["muscleGroups"].mapNotNull { runCatching { MuscleGroup.valueOf(it.asText()) }.getOrNull() }.toSet(),
                movementPattern = n["movementPattern"]?.takeIf { !it.isNull }?.asText(),
                tier = RecommendationTier.valueOf(n["recommendationTier"].asText()),
                popularity = n["popularity"]?.asInt(50) ?: 50,
                difficulty = n["difficulty"]?.asInt(50) ?: 50,
                descriptions = n["translations"].associate { it["locale"].asText() to listOf(it["instructions"], it["tips"]).mapNotNull { x -> x?.takeIf { v -> !v.isNull }?.asText() }.joinToString("\n") },
            )
        }
        val cases = mapper.readTree(File(input))["cases"]
        // 워밍업은 별도 인스턴스로 한다(JIT 만 데우고, 엔진 내부 결과 캐시는 측정용 인스턴스와 공유되지 않는다).
        // 같은 인스턴스로 워밍업하면 측정이 캐시 적중 시간이 되어 지연 게이트가 무력해진다(2026-10-07 발견).
        val warm = ExerciseSearchEngine(docs)
        repeat(3) { cases.forEach { c -> warm.search(c["query"].asText(), null, null, c["locale"]?.asText()) } }
        // 품질은 시간 예산을 넉넉히 준 엔진으로 결정적으로 잰다 — 기계가 바쁠 때(다른 작업 부하) 운영 예산(40ms)에 걸려 잘린 결과가 점수를 흔들지 않게(2026-10-08 발견).
        // 지연은 운영 예산 그대로인 엔진으로 따로 잰다(같은 질의, 별도 인스턴스).
        val engine = ExerciseSearchEngine(docs, emptyList(), 10_000_000_000L)
        val timed = ExerciseSearchEngine(docs)
        // 질의 임베딩 파일이 있으면 하이브리드(어휘 + 의미) 경로로 낸다
        val qvec: Map<String, FloatArray>? = System.getProperty("searchDump.qvec")?.takeIf { it.isNotBlank() }?.let { path ->
            val node = mapper.readTree(File(path))
            node.fields().asSequence().associate { (k, v) -> k to FloatArray(v.size()) { i -> v[i].floatValue() } }
        }
        // LLM 질의 해석 파일 — 검증은 운영 해석기와 같은 파서(QueryRewrite.parse)로 한다
        val rewrites: Map<String, QueryRewrite>? = if (qvec == null) null else System.getProperty("searchDump.rewrites")?.takeIf { it.isNotBlank() }?.let { path ->
            mapper.readTree(File(path)).fields().asSequence().mapNotNull { (k, v) -> QueryRewrite.parse(v.toString(), mapper)?.let { k to it } }.toMap()
        }
        val hybrid = qvec?.let {
            val vectors = ExerciseVectors.loadDefault() ?: error("exercise embeddings resource not found")
            val warmHybrid = HybridSearch(warm, vectors)
            repeat(3) { cases.forEach { c -> val q = c["query"].asText(); warmHybrid.search(q, null, null, c["locale"]?.asText(), rewrites?.let { r -> { r[q] } }) { qvec[q]?.let(QueryEmbedder::normalize) } } }
            HybridSearch(engine, vectors)
        }
        val timedHybrid = hybrid?.let { HybridSearch(timed, ExerciseVectors.loadDefault()!!) }
        var budgetCut = 0
        val f = JsonNodeFactory.instance
        val out = f.objectNode()
        out.put("catalog_size", docs.size)
        if (hybrid != null) out.put("semantic", true)
        if (hybrid != null && rewrites != null) out.put("rewrite", true)
        val rows = out.putArray("results")
        for (c in cases) {
            val category = c["category"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.let { ExerciseCategory.valueOf(it) }
            val equipments = c["equipment"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()
                ?.split(",")?.map { Equipment.valueOf(it.trim()) }?.toSet()
            val q = c["query"].asText()
            val hits = if (hybrid != null) {
                hybrid.search(q, category, equipments, c["locale"]?.asText(), rewrites?.let { r -> { r[q] } }) { qvec!![q]?.let(QueryEmbedder::normalize) }.hits
            } else engine.search(q, category, equipments, c["locale"]?.asText())
            val started = System.nanoTime()
            val timedHits = if (timedHybrid != null) {
                timedHybrid.search(q, category, equipments, c["locale"]?.asText(), rewrites?.let { r -> { r[q] } }) { qvec!![q]?.let(QueryEmbedder::normalize) }.hits
            } else timed.search(q, category, equipments, c["locale"]?.asText())
            val micros = (System.nanoTime() - started) / 1000
            if (timedHits.take(10).map { it.doc.id } != hits.take(10).map { it.doc.id }) budgetCut++
            val row = rows.addObject()
            row.put("id", c["id"].asText())
            row.put("micros", micros)
            row.put("total", hits.size)
            val top = row.putArray("top")
            hits.take(10).forEach { h ->
                val t = top.addObject()
                t.put("slug", h.doc.slug)
                t.put("equipment", h.doc.equipment?.name)
                t.put("category", h.doc.category.name)
            }
        }
        // 운영 예산 안에서 결과가 달라진(잘린) 질의 수 — 품질 점수에는 쓰지 않고 함께 보고한다
        out.put("budget_cut", budgetCut)
        File(output).writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out))
    }
}

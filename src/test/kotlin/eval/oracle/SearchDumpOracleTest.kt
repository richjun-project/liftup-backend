package eval.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 평가 오라클(검색 품질 게이트 전용) — 채점은 하지 않고, 평가셋의 각 질의에 대한 엔진 상위 10개를 그대로 JSON 으로 내보낸다.
 * 채점은 저장소 밖의 독립 스크립트가 한다(튜닝하는 쪽이 채점 기준을 바꿀 수 없게).
 *
 * 실행: ./gradlew test --tests 'eval.oracle.SearchDumpOracleTest' -DsearchDump.in=<eval.json> -DsearchDump.out=<out.json>
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
            )
        }
        val engine = ExerciseSearchEngine(docs)
        val cases = mapper.readTree(File(input))["cases"]
        val f = JsonNodeFactory.instance
        val out = f.objectNode()
        out.put("catalog_size", docs.size)
        val rows = out.putArray("results")
        // 워밍업(지연 측정에서 JIT·초기화 비용을 뺀다)
        repeat(3) { cases.forEach { c -> engine.search(c["query"].asText(), null, null, c["locale"]?.asText()) } }
        for (c in cases) {
            val category = c["category"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.let { ExerciseCategory.valueOf(it) }
            val equipments = c["equipment"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()
                ?.split(",")?.map { Equipment.valueOf(it.trim()) }?.toSet()
            val started = System.nanoTime()
            val hits = engine.search(c["query"].asText(), category, equipments, c["locale"]?.asText())
            val micros = (System.nanoTime() - started) / 1000
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
        File(output).writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out))
    }
}

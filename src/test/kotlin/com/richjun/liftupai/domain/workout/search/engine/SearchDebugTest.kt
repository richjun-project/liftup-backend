package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import org.junit.jupiter.api.Test
import java.io.File

/** 질의 해석·상위 결과를 눈으로 확인하는 진단 도구 — -DsearchDebug.in=<파일(줄마다 질의)> 가 있을 때만 동작한다. */
class SearchDebugTest {
    @Test
    fun debug() {
        val input = System.getProperty("searchDebug.in")?.takeIf { it.isNotBlank() } ?: return
        val out = System.getProperty("searchDebug.out")?.takeIf { it.isNotBlank() } ?: return
        val engine = ExerciseSearchEngine(ExerciseSearchEvalTest.loadCatalogDocs())
        val sb = StringBuilder()
        File(input).readLines().filter { it.isNotBlank() }.forEach { q ->
            sb.append("## ").append(q).append('\n').append("   ").append(engine.explain(q)).append('\n')
            engine.search(q, null, null, null).take(8).forEach { sb.append("   ").append(it.doc.slug).append("  ").append(String.format("%.1f", it.score)).append('\n') }
        }
        File(out).writeText(sb.toString())
    }
}

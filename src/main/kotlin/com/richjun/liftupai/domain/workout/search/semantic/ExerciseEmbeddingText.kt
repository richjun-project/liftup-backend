package com.richjun.liftupai.domain.workout.search.semantic

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest

/**
 * 운동 임베딩의 "문서 텍스트" 레시피 (버전 [RECIPE_VERSION]).
 *
 * 세 언어(en·ja·ko) 이름 + 분류·장비·근육·동작 + 세 언어 설명문의 첫 줄(160자).
 * scripts/search/regenerate_embeddings.py 의 doc_text() 와 글자 단위로 같아야 한다 — 어긋나면
 * [ExerciseVectors] 의 해시 검사(테스트)가 실패한다. 레시피를 바꾸면 버전도 올리고 리소스를 다시 만든다.
 */
object ExerciseEmbeddingText {
    const val RECIPE_VERSION = "v1"
    const val DESC_CHARS = 160

    /** 카탈로그 JSON 한 항목(exercise-catalog.json 의 원소)에서 문서 텍스트를 만든다 */
    fun build(entry: JsonNode): String {
        fun name(loc: String) = entry["translations"].firstOrNull { it["locale"].asText() == loc }?.get("name")?.takeIf { !it.isNull }?.asText().orEmpty()
        fun firstLine(loc: String): String {
            val ins = entry["translations"].firstOrNull { it["locale"].asText() == loc }?.get("instructions")?.takeIf { !it.isNull }?.asText().orEmpty()
            return truncateCodePoints(ins.substringBefore('\n'), DESC_CHARS)
        }
        fun field(key: String): String = entry[key]?.takeIf { !it.isNull }?.asText() ?: "None"
        val muscles = entry["muscleGroups"]?.joinToString(", ") { it.asText() }.orEmpty()
        return "${name("en")} / ${name("ja")} / ${name("ko")}. Category: ${field("category")}. Equipment: ${field("equipment")}. " +
            "Muscles: $muscles. Pattern: ${field("movementPattern")}. " +
            "${firstLine("en")} ${firstLine("ja")} ${firstLine("ko")}"
    }

    /** 문서 텍스트의 내용 해시(SHA-256 앞 16자리 16진수) — 카탈로그가 바뀌어 벡터가 낡았는지 가려낸다 */
    fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    private fun truncateCodePoints(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }
}

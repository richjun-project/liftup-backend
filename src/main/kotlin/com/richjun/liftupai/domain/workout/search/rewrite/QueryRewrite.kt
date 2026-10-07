package com.richjun.liftupai.domain.workout.search.rewrite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment

/**
 * LLM 이 읽어 낸 질의 의도 — 트레이너라면 이 요청에 어떤 운동을 고를지(영어 운동 이름 힌트)와, 요청이 분명히 배제하거나 쓸 수 없는 장비.
 * 힌트는 어휘 검색 엔진에 다시 던져 실제 운동으로 바꾸는 검색어일 뿐이고, 장비 제외는 결과를 걸러 내는 데만 쓴다.
 * LLM 의 말은 결정적 분석기의 하드 조건(부정·부상·집·필터·안전 규칙)을 이기지 못한다 — 그 조건은 융합 뒤에 항상 마지막으로 적용된다.
 */
data class QueryRewrite(val hints: List<String>, val forbidEquipment: Set<Equipment>) {
    val isEmpty: Boolean get() = hints.isEmpty() && forbidEquipment.isEmpty()

    companion object {
        val EMPTY = QueryRewrite(emptyList(), emptySet())

        /** LLM 이 제외 장비로 낼 수 있는 값(프롬프트가 허락한 여섯 개) — 맨몸·기타는 "제외할 장비"가 될 수 없다 */
        val FORBIDDABLE: Set<Equipment> = setOf(
            Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE, Equipment.KETTLEBELL, Equipment.RESISTANCE_BAND
        )

        const val MAX_HINTS = 6
        const val MAX_HINT_CHARS = 60
        private val CONTROL_OR_SPACE = Regex("[\\p{Cntrl}\\s]+")

        /**
         * 모델 응답 텍스트(JSON)를 엄격히 검증해 [QueryRewrite] 로 만든다. 모양이 틀리면 null(= 실패, 호출자는 하이브리드로 돌아간다).
         * - 최상위는 객체여야 하고, hints 는 문자열 배열(없으면 빈 배열), forbid_equipment 는 배열(없으면 빈 배열)이어야 한다.
         * - 힌트: 문자열만, 제어문자·연속 공백 정리, 비었거나 [MAX_HINT_CHARS] 자 초과는 버림, 중복 제거, 최대 [MAX_HINTS] 개.
         * - 제외 장비: [FORBIDDABLE] 의 이름만(대소문자 무시). 모르는 값은 버린다.
         */
        fun parse(text: String?, mapper: ObjectMapper = ObjectMapper()): QueryRewrite? {
            if (text.isNullOrBlank() || text.length > 20_000) return null
            val root: JsonNode = try { mapper.readTree(unfence(text)) } catch (e: Exception) { return null } ?: return null
            if (!root.isObject) return null
            val hintsNode = root.get("hints")
            val forbidNode = root.get("forbid_equipment")
            if (hintsNode != null && !hintsNode.isNull && !hintsNode.isArray) return null
            if (forbidNode != null && !forbidNode.isNull && !forbidNode.isArray) return null
            val seen = HashSet<String>()
            val hints = ArrayList<String>()
            hintsNode?.forEach { n ->
                if (!n.isTextual || hints.size >= MAX_HINTS) return@forEach
                val h = n.asText().replace(CONTROL_OR_SPACE, " ").trim()
                if (h.isNotEmpty() && h.length <= MAX_HINT_CHARS && seen.add(h.lowercase())) hints.add(h)
            }
            val forbid = LinkedHashSet<Equipment>()
            forbidNode?.forEach { n ->
                if (!n.isTextual) return@forEach
                val e = runCatching { Equipment.valueOf(n.asText().trim().uppercase().replace(' ', '_')) }.getOrNull()
                if (e != null && e in FORBIDDABLE) forbid.add(e)
            }
            return QueryRewrite(hints, forbid)
        }

        /** 모델이 ```json 울타리를 두르는 경우를 벗긴다 */
        private fun unfence(t: String): String {
            val s = t.trim()
            if (!s.startsWith("```")) return s
            return s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        }
    }
}

/** 질의 → LLM 해석. 못 구하면(꺼짐·시간 초과·차단기 열림·API 오류·잘못된 응답) null 이고, 호출자는 하이브리드 결과를 그대로 쓴다. */
interface QueryRewriteSource {
    /** 설정으로 켜져 있는가 — 꺼져 있으면 검색 경로는 하이브리드와 같다 */
    val enabled: Boolean

    fun rewrite(query: String): QueryRewrite?
}

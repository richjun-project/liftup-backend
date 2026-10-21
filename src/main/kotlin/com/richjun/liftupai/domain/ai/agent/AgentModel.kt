package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 에이전트 루프가 모델을 부르는 한 지점. 요청·응답을 JSON 트리 그대로 다룬다 —
 * Gemini 3 는 함수 호출 응답의 part 에 thoughtSignature 를 붙이고, 다음 턴에 그 part 를 그대로 돌려받아야 한다
 * (빼면 400). 타입이 정해진 DTO 로 옮기면 모르는 필드가 사라지므로 트리를 그대로 이어 붙인다.
 */
interface AgentModel {
    fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult
}

sealed interface AgentModelResult {
    val elapsedMs: Long

    data class Success(val response: JsonNode, override val elapsedMs: Long) : AgentModelResult
    data class Failure(val code: Int?, val message: String, override val elapsedMs: Long) : AgentModelResult
}

@Component
class GeminiAgentModel(
    private val objectMapper: ObjectMapper,
) : AgentModel {
    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    @Value("\${ai.agent.model:\${gemini.model:gemini-flash-latest}}")
    private lateinit var model: String

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult {
        val started = System.currentTimeMillis()
        return try {
            // 남은 예산만큼만 기다린다 — 앱은 30초에서 끊는다
            val client = baseClient.newBuilder().callTimeout(timeoutMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS).build()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .post(objectMapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                if (!response.isSuccessful) {
                    AgentModelResult.Failure(response.code, raw.take(500), elapsed)
                } else {
                    AgentModelResult.Success(objectMapper.readTree(raw), elapsed)
                }
            }
        } catch (e: Exception) {
            AgentModelResult.Failure(null, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
        }
    }

    fun modelName(): String = model
}

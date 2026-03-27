package com.richjun.liftupai.domain.workout.service.vector

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.util.WorkoutFocus
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.domain.workout.util.WorkoutTargetResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ExerciseVectorService(
    private val objectMapper: ObjectMapper
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(this::class.java)
    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Converts an exercise into an English embedding text block.
     */
    fun exerciseToText(exercise: Exercise): String {
        val parts = mutableListOf<String>()

        parts.add("Exercise: ${exercise.name}")

        parts.add("Category: ${translateCategory(exercise.category)}")

        if (exercise.muscleGroups.isNotEmpty()) {
            val muscles = exercise.muscleGroups.joinToString(", ") { translateMuscleGroup(it) }
            parts.add("Target muscles: $muscles")
        }

        exercise.equipment?.let {
            parts.add("Equipment: ${translateEquipment(it)}")
        }

        exercise.instructions?.let {
            parts.add("Instructions: $it")
        }

        return parts.joinToString(". ")
    }

    /**
     * Converts user preferences into an English embedding text block.
     */
    fun userRequestToText(
        userGoals: String? = null,
        targetMuscles: List<String> = emptyList(),
        equipment: String? = null,
        difficulty: String? = null,
        duration: Int? = null,
        userLevel: String? = null,
        workoutHistory: String? = null,
        avoidMuscles: List<String> = emptyList(),
        weeklyVolume: Map<String, Int> = emptyMap()
    ): String {
        val parts = mutableListOf<String>()

        userGoals?.let { parts.add("Goal: $it") }

        if (targetMuscles.isNotEmpty()) {
            parts.add("Target muscles: ${targetMuscles.joinToString(", ")}")
        }

        if (avoidMuscles.isNotEmpty()) {
            parts.add("Avoid muscles (recovering): ${avoidMuscles.joinToString(", ")}")
        }

        if (weeklyVolume.isNotEmpty()) {
            // Schoenfeld (2016): 주당 12-20세트가 근비대 적정 볼륨
            val lowVolume = weeklyVolume.filter { it.value < 12 }.keys
            val highVolume = weeklyVolume.filter { it.value > 25 }.keys

            if (lowVolume.isNotEmpty()) {
                parts.add("Low-volume muscles (prioritize): ${lowVolume.joinToString(", ")}")
            }
            if (highVolume.isNotEmpty()) {
                parts.add("High-volume muscles (avoid): ${highVolume.joinToString(", ")}")
            }
        }

        equipment?.let { parts.add("Equipment: $it") }
        difficulty?.let { parts.add("Difficulty: $it") }
        duration?.let { parts.add("Duration: ${it} minutes") }
        userLevel?.let { parts.add("Experience level: $it") }
        workoutHistory?.let { parts.add("Recent workouts: $it") }

        return parts.joinToString(". ")
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 500L
    }

    /**
     * 텍스트를 벡터로 변환 (Gemini Embedding API)
     * 지수 백오프 재시도 (최대 3회) — 429/5xx 등 일시적 오류 대응
     */
    fun generateEmbedding(text: String): List<Float> {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val result = callEmbeddingApi(text)
                if (result != null) return result

                // API 응답은 성공했으나 파싱 실패 — 재시도 의미 없음
                logger.warn("Embedding parse failed on attempt $attempt")
                return getDefaultEmbedding()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    val backoff = INITIAL_BACKOFF_MS * (1L shl (attempt - 1))  // 500ms, 1000ms
                    logger.warn("Embedding API attempt $attempt failed: ${e.message}, retrying in ${backoff}ms")
                    Thread.sleep(backoff)
                }
            }
        }

        logger.error("Embedding API failed after $MAX_RETRIES attempts: {}", lastException?.message, lastException)
        return getDefaultEmbedding()
    }

    /**
     * 실제 Gemini Embedding API 호출
     * @return 성공 시 벡터, API 오류(재시도 가능) 시 예외 throw, 파싱 실패 시 null
     */
    private fun callEmbeddingApi(text: String): List<Float>? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=$apiKey"

        val requestBody = mapOf(
            "model" to "models/gemini-embedding-001",
            "content" to mapOf(
                "parts" to listOf(
                    mapOf("text" to text)
                )
            ),
            "outputDimensionality" to 768
        )

        val jsonBody = objectMapper.writeValueAsString(requestBody)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                val code = response.code
                // 429(Rate Limit), 5xx(Server Error) → 재시도 가능
                if (code == 429 || code >= 500) {
                    throw RuntimeException("Gemini API retryable error ($code): $errorBody")
                }
                // 4xx(Bad Request 등) → 재시도 무의미
                logger.warn("Gemini Embedding API non-retryable error ($code): {}", errorBody)
                return null
            }

            val responseBody = response.body?.string()
            val jsonResponse = objectMapper.readTree(responseBody)
            val values = jsonResponse.get("embedding")?.get("values")

            return if (values != null && values.isArray) {
                values.map { it.floatValue() }
            } else {
                logger.warn("Invalid embedding response: no values array found")
                null
            }
        }
    }

    private fun translateCategory(category: ExerciseCategory): String {
        return WorkoutTargetResolver.focusForCategory(category)
            ?.let { focus -> WorkoutLocalization.focusName(focus, "en") }
            ?: WorkoutLocalization.focusName(WorkoutFocus.FULL_BODY, "en")
    }

    private fun translateMuscleGroup(muscleGroup: MuscleGroup): String {
        return WorkoutLocalization.muscleGroupName(muscleGroup, "en")
    }

    private fun translateEquipment(equipment: Equipment): String {
        return WorkoutLocalization.equipmentName(equipment.name, "en")
    }

    private fun getDefaultEmbedding(): List<Float> {
        return List(768) { 0.0f }
    }
}

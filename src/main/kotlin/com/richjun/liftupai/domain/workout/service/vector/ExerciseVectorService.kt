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
            val lowVolume = weeklyVolume.filter { it.value < 10 }.keys
            val highVolume = weeklyVolume.filter { it.value > 20 }.keys

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

    /**
     * 텍스트를 벡터로 변환 (Gemini Embedding API)
     */
    fun generateEmbedding(text: String): List<Float> {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey"

            val requestBody = mapOf(
                "model" to "models/text-embedding-004",
                "content" to mapOf(
                    "parts" to listOf(
                        mapOf("text" to text)
                    )
                )
            )

            val jsonBody = objectMapper.writeValueAsString(requestBody)
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    logger.warn("Gemini Embedding API Error: {}", errorBody)
                    return getDefaultEmbedding()
                }

                val responseBody = response.body?.string()
                val jsonResponse = objectMapper.readTree(responseBody)
                val values = jsonResponse.get("embedding")?.get("values")

                if (values != null && values.isArray) {
                    values.map { it.floatValue() }
                } else {
                    logger.warn("Invalid embedding response: no values array found")
                    getDefaultEmbedding()
                }
            }
        } catch (e: Exception) {
            logger.error("Error generating embedding: {}", e.message, e)
            getDefaultEmbedding()
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

package com.richjun.liftupai.domain.workout.service.vector

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.*
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
    @Value("\${gemini.api-key}")
    private lateinit var apiKey: String

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Exercise를 텍스트로 변환
     */
    fun exerciseToText(exercise: Exercise): String {
        val parts = mutableListOf<String>()

        // 운동명
        parts.add("운동명: ${exercise.name}")

        // 카테고리
        parts.add("카테고리: ${translateCategory(exercise.category)}")

        // 타겟 근육
        if (exercise.muscleGroups.isNotEmpty()) {
            val muscles = exercise.muscleGroups.joinToString(", ") { translateMuscleGroup(it) }
            parts.add("타겟 근육: $muscles")
        }

        // 장비
        exercise.equipment?.let {
            parts.add("필요 장비: ${translateEquipment(it)}")
        }

        // 설명
        exercise.instructions?.let {
            parts.add("설명: $it")
        }

        return parts.joinToString(". ")
    }

    /**
     * 사용자 요구사항을 텍스트로 변환
     * (헬스 트레이너 관점: 회복, 볼륨 정보 추가)
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

        userGoals?.let { parts.add("목표: $it") }

        if (targetMuscles.isNotEmpty()) {
            parts.add("타겟 근육: ${targetMuscles.joinToString(", ")}")
        }

        // 회복 중인 근육 정보 (피해야 할 근육)
        if (avoidMuscles.isNotEmpty()) {
            parts.add("피해야 할 근육 (회복 중): ${avoidMuscles.joinToString(", ")}")
        }

        // 주간 볼륨 정보 (부족한 근육 우선, 과다 근육 회피)
        if (weeklyVolume.isNotEmpty()) {
            val lowVolume = weeklyVolume.filter { it.value < 10 }.keys
            val highVolume = weeklyVolume.filter { it.value > 20 }.keys

            if (lowVolume.isNotEmpty()) {
                parts.add("볼륨 부족 근육 (우선 선택): ${lowVolume.joinToString(", ")}")
            }
            if (highVolume.isNotEmpty()) {
                parts.add("볼륨 과다 근육 (피하기): ${highVolume.joinToString(", ")}")
            }
        }

        equipment?.let { parts.add("장비: $it") }
        difficulty?.let { parts.add("난이도: $it") }
        duration?.let { parts.add("운동 시간: ${it}분") }
        userLevel?.let { parts.add("경험 수준: $it") }
        workoutHistory?.let { parts.add("최근 운동: $it") }

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
                    println("Gemini Embedding API Error: $errorBody")
                    return getDefaultEmbedding()
                }

                val responseBody = response.body?.string()
                val jsonResponse = objectMapper.readTree(responseBody)
                val values = jsonResponse.get("embedding")?.get("values")

                if (values != null && values.isArray) {
                    values.map { it.floatValue() }
                } else {
                    println("Invalid embedding response")
                    getDefaultEmbedding()
                }
            }
        } catch (e: Exception) {
            println("Error generating embedding: ${e.message}")
            e.printStackTrace()
            getDefaultEmbedding()
        }
    }

    private fun translateCategory(category: ExerciseCategory): String {
        return when (category) {
            ExerciseCategory.CHEST -> "가슴"
            ExerciseCategory.BACK -> "등"
            ExerciseCategory.LEGS -> "하체"
            ExerciseCategory.SHOULDERS -> "어깨"
            ExerciseCategory.ARMS -> "팔"
            ExerciseCategory.CORE -> "코어"
            ExerciseCategory.CARDIO -> "유산소"
            ExerciseCategory.FULL_BODY -> "전신"
        }
    }

    private fun translateMuscleGroup(muscleGroup: MuscleGroup): String {
        return when (muscleGroup) {
            MuscleGroup.CHEST -> "가슴"
            MuscleGroup.BACK -> "등"
            MuscleGroup.SHOULDERS -> "어깨"
            MuscleGroup.BICEPS -> "이두"
            MuscleGroup.TRICEPS -> "삼두"
            MuscleGroup.LEGS -> "다리"
            MuscleGroup.CORE -> "코어"
            MuscleGroup.ABS -> "복근"
            MuscleGroup.GLUTES -> "둔근"
            MuscleGroup.CALVES -> "종아리"
            MuscleGroup.FOREARMS -> "전완"
            MuscleGroup.NECK -> "목"
            MuscleGroup.QUADRICEPS -> "대퇴사두"
            MuscleGroup.HAMSTRINGS -> "햄스트링"
            MuscleGroup.LATS -> "광배근"
            MuscleGroup.TRAPS -> "승모근"
        }
    }

    private fun translateEquipment(equipment: Equipment): String {
        return when (equipment) {
            Equipment.BARBELL -> "바벨"
            Equipment.DUMBBELL -> "덤벨"
            Equipment.BODYWEIGHT -> "맨몸"
            Equipment.MACHINE -> "머신"
            Equipment.CABLE -> "케이블"
            Equipment.RESISTANCE_BAND -> "밴드"
            Equipment.KETTLEBELL -> "케틀벨"
            Equipment.OTHER -> "기타"
        }
    }

    private fun getDefaultEmbedding(): List<Float> {
        // 768 차원의 기본 임베딩
        return List(768) { 0.0f }
    }
}

package com.richjun.liftupai.domain.workout.service.vector

import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.util.*

@Service
class ExerciseQdrantService(
    private val qdrantClient: QdrantClient
) {
    private val collectionName = "exercises"
    private val vectorSize = 768 // Gemini text-embedding-004 dimension

    @PostConstruct
    fun initialize() {
        try {
            createCollectionIfNotExists()
        } catch (e: Exception) {
            println("Warning: Failed to initialize Qdrant collection: ${e.message}")
        }
    }

    private fun createCollectionIfNotExists() {
        try {
            val vectorParams = VectorParams.newBuilder()
                .setSize(vectorSize.toLong())
                .setDistance(Distance.Cosine)
                .build()

            qdrantClient.createCollectionAsync(
                collectionName,
                vectorParams
            ).get()

            println("Qdrant collection '$collectionName' created successfully")
        } catch (e: Exception) {
            if (e.message?.contains("already exists") == true || e.message?.contains("ALREADY_EXISTS") == true) {
                println("Qdrant collection '$collectionName' already exists")
            } else {
                println("Warning: Error creating Qdrant collection: ${e.message}")
            }
        }
    }

    /**
     * 운동 벡터 저장
     */
    fun upsertExercise(
        exerciseId: Long,
        vector: List<Float>,
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val pointId = UUID.randomUUID().toString()

        val vectorData = io.qdrant.client.grpc.Points.Vector.newBuilder()
            .addAllData(vector)
            .build()

        val point = PointStruct.newBuilder()
            .setId(PointId.newBuilder().setUuid(pointId).build())
            .setVectors(Vectors.newBuilder().setVector(vectorData).build())
            .putAllPayload(buildMetadata(metadata, exerciseId))
            .build()

        val points = mutableListOf<PointStruct>()
        points.add(point)

        qdrantClient.upsertAsync(
            collectionName,
            points
        ).get()

        return pointId
    }

    /**
     * 유사한 운동 검색
     */
    fun searchSimilarExercises(
        queryVector: List<Float>,
        limit: Int = 10,
        scoreThreshold: Float = 0.3f,
        filter: Map<String, Any>? = null
    ): List<Pair<Long, Float>> {
        try {
            val searchRequestBuilder = SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(queryVector)
                .setLimit(limit.toLong())
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setScoreThreshold(scoreThreshold)

            // 필터 추가 (옵션)
            if (filter != null) {
                val mustConditions = filter.map { (key, value) ->
                    Condition.newBuilder()
                        .setField(
                            FieldCondition.newBuilder()
                                .setKey(key)
                                .setMatch(Match.newBuilder().setKeyword(value.toString()).build())
                                .build()
                        )
                        .build()
                }

                searchRequestBuilder.setFilter(
                    Filter.newBuilder()
                        .addAllMust(mustConditions)
                        .build()
                )
            }

            val results = qdrantClient.searchAsync(searchRequestBuilder.build()).get()

            return results.map { scoredPoint ->
                val exerciseId = scoredPoint.payload["exercise_id"]?.integerValue ?: 0L
                val score = scoredPoint.score
                Pair(exerciseId, score)
            }
        } catch (e: Exception) {
            println("Error searching similar exercises: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * 벡터 삭제
     */
    fun deleteVector(vectorId: String) {
        try {
            qdrantClient.deleteAsync(
                collectionName,
                listOf(PointId.newBuilder().setUuid(vectorId).build())
            ).get()
        } catch (e: Exception) {
            println("Error deleting vector: ${e.message}")
        }
    }

    /**
     * 운동 ID로 벡터 삭제
     */
    fun deleteByExerciseId(exerciseId: Long) {
        try {
            val filterCondition = Filter.newBuilder()
                .addMust(
                    Condition.newBuilder()
                        .setField(
                            FieldCondition.newBuilder()
                                .setKey("exercise_id")
                                .setMatch(Match.newBuilder().setInteger(exerciseId).build())
                                .build()
                        )
                        .build()
                )
                .build()

            qdrantClient.deleteAsync(
                collectionName,
                filterCondition
            ).get()
        } catch (e: Exception) {
            println("Error deleting vector by exercise ID: ${e.message}")
        }
    }

    private fun buildMetadata(metadata: Map<String, Any>, exerciseId: Long): Map<String, io.qdrant.client.grpc.JsonWithInt.Value> {
        val result = mutableMapOf<String, io.qdrant.client.grpc.JsonWithInt.Value>()

        result["exercise_id"] = io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
            .setIntegerValue(exerciseId).build()

        metadata.forEach { (key, value) ->
            result[key] = when (value) {
                is String -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue(value).build()
                is Int -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setIntegerValue(value.toLong()).build()
                is Long -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setIntegerValue(value).build()
                is Double -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setDoubleValue(value).build()
                is Float -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setDoubleValue(value.toDouble()).build()
                is Boolean -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setBoolValue(value).build()
                else -> io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue(value.toString()).build()
            }
        }

        return result
    }
}

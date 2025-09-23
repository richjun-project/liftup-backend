package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PlannedExerciseDtoTest {

    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .also { mapper ->
            mapper.findAndRegisterModules()
        }

    @Test
    fun `should deserialize sets as integer`() {
        val json = """
            {
                "exercise_id": 1,
                "exercise_name": "벤치프레스",
                "sets": 3,
                "target_reps": 10,
                "weight": 60.0
            }
        """.trimIndent()

        val plannedExercise = objectMapper.readValue(json, PlannedExercise::class.java)

        assertEquals(1L, plannedExercise.exerciseId)
        assertEquals("벤치프레스", plannedExercise.exerciseName)
        assertEquals(3, plannedExercise.setsCount)
        assertNull(plannedExercise.setDetails)
        assertEquals(10, plannedExercise.reps)
        assertEquals(60.0, plannedExercise.weight)
    }

    @Test
    fun `should deserialize sets as array of PlannedSet`() {
        val json = """
            {
                "exercise_id": 1,
                "exercise_name": "벤치프레스",
                "sets": [
                    {"set_number": 1, "reps": 12, "weight": 50.0, "rest": 90},
                    {"set_number": 2, "reps": 10, "weight": 55.0, "rest": 120},
                    {"set_number": 3, "reps": 8, "weight": 60.0, "rest": 150}
                ],
                "target_reps": 10
            }
        """.trimIndent()

        val plannedExercise = objectMapper.readValue(json, PlannedExercise::class.java)

        assertEquals(1L, plannedExercise.exerciseId)
        assertEquals("벤치프레스", plannedExercise.exerciseName)
        assertEquals(3, plannedExercise.setsCount)
        assertNotNull(plannedExercise.setDetails)
        assertEquals(3, plannedExercise.setDetails?.size)

        val firstSet = plannedExercise.setDetails?.first()
        assertEquals(1, firstSet?.setNumber)
        assertEquals(12, firstSet?.reps)
        assertEquals(50.0, firstSet?.weight)
        assertEquals(90, firstSet?.rest)
    }

    @Test
    fun `should handle missing sets field with default value`() {
        val json = """
            {
                "exercise_id": 1,
                "exercise_name": "벤치프레스",
                "target_reps": 10
            }
        """.trimIndent()

        val plannedExercise = objectMapper.readValue(json, PlannedExercise::class.java)

        assertEquals(1L, plannedExercise.exerciseId)
        assertEquals(3, plannedExercise.setsCount) // Default value
        assertNull(plannedExercise.setDetails)
    }

    @Test
    fun `should deserialize StartWorkoutRequest with PlannedExercises`() {
        val json = """
            {
                "planned_exercises": [
                    {
                        "exercise_id": 1,
                        "exercise_name": "벤치프레스",
                        "sets": 3,
                        "target_reps": 10,
                        "weight": 60.0
                    },
                    {
                        "exercise_id": 2,
                        "exercise_name": "스쿼트",
                        "sets": [
                            {"reps": 12, "weight": 80.0},
                            {"reps": 10, "weight": 85.0},
                            {"reps": 8, "weight": 90.0}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val request = objectMapper.readValue(json, StartWorkoutRequest::class.java)

        assertEquals(2, request.plannedExercises.size)

        val firstExercise = request.plannedExercises[0]
        assertEquals(3, firstExercise.setsCount)
        assertNull(firstExercise.setDetails)

        val secondExercise = request.plannedExercises[1]
        assertEquals(3, secondExercise.setsCount)
        assertNotNull(secondExercise.setDetails)
        assertEquals(3, secondExercise.setDetails?.size)
    }
}
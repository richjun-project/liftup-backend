package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.service.ExerciseTrainingProfileResolver.ExerciseTrainingProfile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExerciseTrainingProfileResolverTest {

    private lateinit var resolver: ExerciseTrainingProfileResolver
    private lateinit var classifier: ExercisePatternClassifier

    @BeforeEach
    fun setUp() {
        classifier = mockk()
        resolver = ExerciseTrainingProfileResolver(classifier)
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Profile Resolution (7 profiles)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    inner class ProfileResolution {

        @Test
        fun `bench press (isBasicExercise + BARBELL + HORIZONTAL_PRESS_BARBELL) resolves to PRIMARY_COMPOUND`() {
            val exercise = Exercise(
                id = 1L, slug = "bench-press", name = "Bench Press",
                category = ExerciseCategory.CHEST, equipment = Equipment.BARBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL

            assertEquals(ExerciseTrainingProfile.PRIMARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `squat (isBasicExercise + BARBELL + SQUAT) resolves to PRIMARY_COMPOUND`() {
            val exercise = Exercise(
                id = 2L, slug = "back-squat", name = "Back Squat",
                category = ExerciseCategory.LEGS, equipment = Equipment.BARBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.SQUAT

            assertEquals(ExerciseTrainingProfile.PRIMARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `deadlift (isBasicExercise + BARBELL + DEADLIFT) resolves to PRIMARY_COMPOUND`() {
            val exercise = Exercise(
                id = 3L, slug = "deadlift", name = "Deadlift",
                category = ExerciseCategory.BACK, equipment = Equipment.BARBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.BACK, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.DEADLIFT

            assertEquals(ExerciseTrainingProfile.PRIMARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `incline dumbbell press resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 4L, slug = "incline-db-press", name = "Incline Dumbbell Press",
                category = ExerciseCategory.CHEST, equipment = Equipment.DUMBBELL,
                muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `barbell row resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 5L, slug = "barbell-row", name = "Barbell Row",
                category = ExerciseCategory.BACK, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.BACK, MuscleGroup.BICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.BARBELL_ROW

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `pullup resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 6L, slug = "pullup", name = "Pull Up",
                category = ExerciseCategory.BACK, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.BACK, MuscleGroup.BICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `dips resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 7L, slug = "dips", name = "Dips",
                category = ExerciseCategory.CHEST, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.DIPS

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `barbell curl resolves to ISOLATION`() {
            val exercise = Exercise(
                id = 10L, slug = "barbell-curl", name = "Barbell Curl",
                category = ExerciseCategory.ARMS, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.BICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL

            assertEquals(ExerciseTrainingProfile.ISOLATION, resolver.resolveProfile(exercise))
        }

        @Test
        fun `lateral raise resolves to ISOLATION`() {
            val exercise = Exercise(
                id = 11L, slug = "lateral-raise", name = "Lateral Raise",
                category = ExerciseCategory.SHOULDERS, equipment = Equipment.DUMBBELL,
                muscleGroups = mutableSetOf(MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.LATERAL_RAISE

            assertEquals(ExerciseTrainingProfile.ISOLATION, resolver.resolveProfile(exercise))
        }

        @Test
        fun `leg extension resolves to ISOLATION`() {
            val exercise = Exercise(
                id = 12L, slug = "leg-extension", name = "Leg Extension",
                category = ExerciseCategory.LEGS, equipment = Equipment.MACHINE,
                muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.LEG_EXTENSION

            assertEquals(ExerciseTrainingProfile.ISOLATION, resolver.resolveProfile(exercise))
        }

        @Test
        fun `tricep pushdown resolves to ISOLATION`() {
            val exercise = Exercise(
                id = 13L, slug = "tricep-pushdown", name = "Tricep Pushdown",
                category = ExerciseCategory.ARMS, equipment = Equipment.CABLE,
                muscleGroups = mutableSetOf(MuscleGroup.TRICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.TRICEP_PUSHDOWN

            assertEquals(ExerciseTrainingProfile.ISOLATION, resolver.resolveProfile(exercise))
        }

        @Test
        fun `plank resolves to CORE_STABILITY`() {
            val exercise = Exercise(
                id = 20L, slug = "plank", name = "Plank",
                category = ExerciseCategory.CORE, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.CORE)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.PLANK

            assertEquals(ExerciseTrainingProfile.CORE_STABILITY, resolver.resolveProfile(exercise))
        }

        @Test
        fun `crunch resolves to CORE_STABILITY`() {
            val exercise = Exercise(
                id = 21L, slug = "crunch", name = "Crunch",
                category = ExerciseCategory.CORE, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.ABS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.CRUNCH

            assertEquals(ExerciseTrainingProfile.CORE_STABILITY, resolver.resolveProfile(exercise))
        }

        @Test
        fun `core category exercise with OTHER pattern resolves to CORE_STABILITY`() {
            val exercise = Exercise(
                id = 22L, slug = "dead-bug", name = "Dead Bug",
                category = ExerciseCategory.CORE, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.CORE)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.OTHER

            assertEquals(ExerciseTrainingProfile.CORE_STABILITY, resolver.resolveProfile(exercise))
        }

        @Test
        fun `clean resolves to OLYMPIC_LIFT`() {
            val exercise = Exercise(
                id = 30L, slug = "power-clean", name = "Power Clean",
                category = ExerciseCategory.FULL_BODY, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.LEGS, MuscleGroup.BACK, MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.CLEAN

            assertEquals(ExerciseTrainingProfile.OLYMPIC_LIFT, resolver.resolveProfile(exercise))
        }

        @Test
        fun `snatch resolves to OLYMPIC_LIFT`() {
            val exercise = Exercise(
                id = 31L, slug = "snatch", name = "Snatch",
                category = ExerciseCategory.FULL_BODY, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.LEGS, MuscleGroup.BACK, MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.SNATCH

            assertEquals(ExerciseTrainingProfile.OLYMPIC_LIFT, resolver.resolveProfile(exercise))
        }

        @Test
        fun `box jump resolves to PLYOMETRIC`() {
            val exercise = Exercise(
                id = 40L, slug = "box-jump", name = "Box Jump",
                category = ExerciseCategory.LEGS, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.LEGS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.PLYOMETRIC

            assertEquals(ExerciseTrainingProfile.PLYOMETRIC, resolver.resolveProfile(exercise))
        }

        @Test
        fun `treadmill resolves to CARDIO`() {
            val exercise = Exercise(
                id = 50L, slug = "treadmill", name = "Treadmill",
                category = ExerciseCategory.CARDIO, equipment = Equipment.MACHINE,
                muscleGroups = mutableSetOf(MuscleGroup.LEGS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.CARDIO

            assertEquals(ExerciseTrainingProfile.CARDIO, resolver.resolveProfile(exercise))
        }

        @Test
        fun `cardio category exercise with OTHER pattern resolves to CARDIO`() {
            val exercise = Exercise(
                id = 51L, slug = "rowing-machine", name = "Rowing Machine",
                category = ExerciseCategory.CARDIO, equipment = Equipment.MACHINE,
                muscleGroups = mutableSetOf(MuscleGroup.BACK)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.OTHER

            assertEquals(ExerciseTrainingProfile.CARDIO, resolver.resolveProfile(exercise))
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. Edge Cases
    // ─────────────────────────────────────────────────────────────────

    @Nested
    inner class EdgeCases {

        @Test
        fun `non-basic barbell squat resolves to SECONDARY_COMPOUND (not PRIMARY)`() {
            val exercise = Exercise(
                id = 60L, slug = "front-squat", name = "Front Squat",
                category = ExerciseCategory.LEGS, equipment = Equipment.BARBELL,
                isBasicExercise = false,
                muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.SQUAT

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `dumbbell bench press (isBasicExercise but DUMBBELL) resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 61L, slug = "db-bench", name = "Dumbbell Bench Press",
                category = ExerciseCategory.CHEST, equipment = Equipment.DUMBBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }

        @Test
        fun `single muscle group exercise with OTHER pattern and no compound keyword resolves to ISOLATION`() {
            val exercise = Exercise(
                id = 62L, slug = "wrist-curl", name = "Wrist Curl",
                category = ExerciseCategory.ARMS, equipment = Equipment.DUMBBELL,
                muscleGroups = mutableSetOf(MuscleGroup.FOREARMS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.OTHER

            assertEquals(ExerciseTrainingProfile.ISOLATION, resolver.resolveProfile(exercise))
        }

        @Test
        fun `FULL_BODY category with multiple muscle groups resolves to SECONDARY_COMPOUND`() {
            val exercise = Exercise(
                id = 63L, slug = "thruster", name = "Thruster",
                category = ExerciseCategory.FULL_BODY, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.LEGS, MuscleGroup.SHOULDERS)
            )
            every { classifier.classifyExercise(exercise) } returns ExercisePatternClassifier.MovementPattern.COMPOUND_COMPLEX

            assertEquals(ExerciseTrainingProfile.SECONDARY_COMPOUND, resolver.resolveProfile(exercise))
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Config Matrix (all 28 cells)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    inner class ConfigMatrix {

        @Test
        fun `PRIMARY_COMPOUND ACCUMULATION returns 4 sets 6-8 reps 180s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.PRIMARY_COMPOUND)
            assertEquals(4, config.sets)
            assertEquals("6-8", config.reps)
            assertEquals(180, config.restSeconds)
        }

        @Test
        fun `PRIMARY_COMPOUND REALIZATION returns 5 sets 1-3 reps 240s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.PRIMARY_COMPOUND)
            assertEquals(5, config.sets)
            assertEquals("1-3", config.reps)
            assertEquals(240, config.restSeconds)
        }

        @Test
        fun `ISOLATION ACCUMULATION returns 3 sets 12-15 reps 60s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.ISOLATION)
            assertEquals(3, config.sets)
            assertEquals("12-15", config.reps)
            assertEquals(60, config.restSeconds)
        }

        @Test
        fun `ISOLATION DELOAD returns 2 sets 15-20 reps 45s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.DELOAD, ExerciseTrainingProfile.ISOLATION)
            assertEquals(2, config.sets)
            assertEquals("15-20", config.reps)
            assertEquals(45, config.restSeconds)
        }

        @Test
        fun `CORE_STABILITY ACCUMULATION returns 3 sets 30-60s reps 45s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.ACCUMULATION, ExerciseTrainingProfile.CORE_STABILITY)
            assertEquals(3, config.sets)
            assertEquals("30-60s", config.reps)
            assertEquals(45, config.restSeconds)
        }

        @Test
        fun `OLYMPIC_LIFT REALIZATION returns 6 sets 1 rep 240s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.REALIZATION, ExerciseTrainingProfile.OLYMPIC_LIFT)
            assertEquals(6, config.sets)
            assertEquals("1", config.reps)
            assertEquals(240, config.restSeconds)
        }

        @Test
        fun `PLYOMETRIC INTENSIFICATION returns 3 sets 3-5 reps 120s rest`() {
            val config = resolver.getConfig(WorkoutServiceV2.PeriodizationPhase.INTENSIFICATION, ExerciseTrainingProfile.PLYOMETRIC)
            assertEquals(3, config.sets)
            assertEquals("3-5", config.reps)
            assertEquals(120, config.restSeconds)
        }

        @Test
        fun `CARDIO returns 1 set time-based for all phases`() {
            WorkoutServiceV2.PeriodizationPhase.entries.forEach { phase ->
                val config = resolver.getConfig(phase, ExerciseTrainingProfile.CARDIO)
                assertEquals(1, config.sets, "CARDIO $phase sets")
                assertEquals("time-based", config.reps, "CARDIO $phase reps")
                assertEquals(0, config.restSeconds, "CARDIO $phase rest")
            }
        }

        @Test
        fun `all 28 matrix cells are defined`() {
            ExerciseTrainingProfile.entries.forEach { profile ->
                WorkoutServiceV2.PeriodizationPhase.entries.forEach { phase ->
                    val config = resolver.getConfig(phase, profile)
                    assertTrue(config.sets > 0, "Missing config for $phase x $profile: sets should be > 0")
                    assertTrue(config.reps.isNotBlank(), "Missing config for $phase x $profile: reps should not be blank")
                    assertTrue(config.restSeconds >= 0, "Missing config for $phase x $profile: rest should be >= 0")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Differentiation (same phase, different exercise → different prescription)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    inner class Differentiation {

        @Test
        fun `squat and bicep curl get different reps under ACCUMULATION`() {
            val squat = Exercise(
                id = 100L, slug = "squat", name = "Back Squat",
                category = ExerciseCategory.LEGS, equipment = Equipment.BARBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)
            )
            val curl = Exercise(
                id = 101L, slug = "curl", name = "Barbell Curl",
                category = ExerciseCategory.ARMS, equipment = Equipment.BARBELL,
                muscleGroups = mutableSetOf(MuscleGroup.BICEPS)
            )
            every { classifier.classifyExercise(squat) } returns ExercisePatternClassifier.MovementPattern.SQUAT
            every { classifier.classifyExercise(curl) } returns ExercisePatternClassifier.MovementPattern.BICEP_CURL_BARBELL

            val phase = WorkoutServiceV2.PeriodizationPhase.ACCUMULATION
            val squatConfig = resolver.resolveConfig(squat, phase)
            val curlConfig = resolver.resolveConfig(curl, phase)

            assertNotEquals(squatConfig.reps, curlConfig.reps,
                "Squat (${squatConfig.reps}) and Curl (${curlConfig.reps}) should have different reps")
            assertTrue(squatConfig.restSeconds > curlConfig.restSeconds,
                "Squat rest (${squatConfig.restSeconds}) should be longer than Curl rest (${curlConfig.restSeconds})")
        }

        @Test
        fun `bench press and plank get different reps under DELOAD`() {
            val bench = Exercise(
                id = 102L, slug = "bench", name = "Bench Press",
                category = ExerciseCategory.CHEST, equipment = Equipment.BARBELL,
                isBasicExercise = true,
                muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            )
            val plank = Exercise(
                id = 103L, slug = "plank", name = "Plank",
                category = ExerciseCategory.CORE, equipment = Equipment.BODYWEIGHT,
                muscleGroups = mutableSetOf(MuscleGroup.CORE)
            )
            every { classifier.classifyExercise(bench) } returns ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL
            every { classifier.classifyExercise(plank) } returns ExercisePatternClassifier.MovementPattern.PLANK

            val phase = WorkoutServiceV2.PeriodizationPhase.DELOAD
            val benchConfig = resolver.resolveConfig(bench, phase)
            val plankConfig = resolver.resolveConfig(plank, phase)

            assertNotEquals(benchConfig.reps, plankConfig.reps,
                "Bench (${benchConfig.reps}) and Plank (${plankConfig.reps}) should have different reps")
        }

        @Test
        fun `primary compound gets more rest than isolation in every phase`() {
            WorkoutServiceV2.PeriodizationPhase.entries.forEach { phase ->
                val primaryConfig = resolver.getConfig(phase, ExerciseTrainingProfile.PRIMARY_COMPOUND)
                val isolationConfig = resolver.getConfig(phase, ExerciseTrainingProfile.ISOLATION)

                assertTrue(primaryConfig.restSeconds > isolationConfig.restSeconds,
                    "$phase: Primary rest (${primaryConfig.restSeconds}s) should exceed Isolation rest (${isolationConfig.restSeconds}s)")
            }
        }
    }
}

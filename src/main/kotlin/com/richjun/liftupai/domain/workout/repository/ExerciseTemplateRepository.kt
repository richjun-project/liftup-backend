package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ExerciseTemplate
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.WorkoutGoal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ExerciseTemplateRepository : JpaRepository<ExerciseTemplate, Long> {

    fun findByExerciseAndExperienceLevelAndWorkoutGoal(
        exercise: Exercise,
        experienceLevel: ExperienceLevel,
        workoutGoal: WorkoutGoal
    ): ExerciseTemplate?

    fun findByExerciseAndExperienceLevel(
        exercise: Exercise,
        experienceLevel: ExperienceLevel
    ): List<ExerciseTemplate>

    @Query("""
        SELECT et FROM ExerciseTemplate et
        WHERE et.exercise.id = :exerciseId
        AND et.experienceLevel = :experienceLevel
        AND et.workoutGoal = :workoutGoal
    """)
    fun findTemplate(
        @Param("exerciseId") exerciseId: Long,
        @Param("experienceLevel") experienceLevel: ExperienceLevel,
        @Param("workoutGoal") workoutGoal: WorkoutGoal
    ): ExerciseTemplate?

    @Query("""
        SELECT et FROM ExerciseTemplate et
        WHERE et.exercise.category = :category
        AND et.experienceLevel = :experienceLevel
        AND et.workoutGoal = :workoutGoal
    """)
    fun findByCategory(
        @Param("category") category: String,
        @Param("experienceLevel") experienceLevel: ExperienceLevel,
        @Param("workoutGoal") workoutGoal: WorkoutGoal
    ): List<ExerciseTemplate>

    @Query("""
        SELECT et FROM ExerciseTemplate et
        WHERE et.exercise.id IN :exerciseIds
        AND et.experienceLevel = :experienceLevel
        AND et.workoutGoal = :workoutGoal
    """)
    fun findTemplatesForExercises(
        @Param("exerciseIds") exerciseIds: List<Long>,
        @Param("experienceLevel") experienceLevel: ExperienceLevel,
        @Param("workoutGoal") workoutGoal: WorkoutGoal
    ): List<ExerciseTemplate>
}
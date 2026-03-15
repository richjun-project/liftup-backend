package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ProgramDayExercise
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ProgramDayExerciseRepository : JpaRepository<ProgramDayExercise, Long> {
    fun findByProgramDayIdOrderByOrderInDay(programDayId: Long): List<ProgramDayExercise>

    @Query("""
        SELECT pde FROM ProgramDayExercise pde
        JOIN FETCH pde.exercise e
        JOIN FETCH pde.programDay pd
        WHERE pd.id = :dayId
        ORDER BY pde.orderInDay
    """)
    fun findByDayIdWithExercises(@Param("dayId") dayId: Long): List<ProgramDayExercise>
}

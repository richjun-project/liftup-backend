package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.TemplateDayExercise
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TemplateDayExerciseRepository : JpaRepository<TemplateDayExercise, Long> {
    fun findByTemplateDayIdOrderByOrderInDay(templateDayId: Long): List<TemplateDayExercise>
}

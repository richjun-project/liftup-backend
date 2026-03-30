package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.TemplateDay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TemplateDayRepository : JpaRepository<TemplateDay, Long> {
    fun findByTemplateIdOrderByDayNumber(templateId: Long): List<TemplateDay>
}

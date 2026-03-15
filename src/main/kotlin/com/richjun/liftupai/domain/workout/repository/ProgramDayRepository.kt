package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ProgramDay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProgramDayRepository : JpaRepository<ProgramDay, Long> {
    fun findByProgramIdOrderByDayNumber(programId: Long): List<ProgramDay>
}

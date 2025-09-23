package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.PersonalRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PersonalRecordRepository : JpaRepository<PersonalRecord, Long> {
    fun findTopByUserAndExerciseOrderByWeightDesc(user: User, exercise: Exercise): PersonalRecord?
    fun findByUserAndExercise(user: User, exercise: Exercise): List<PersonalRecord>
}
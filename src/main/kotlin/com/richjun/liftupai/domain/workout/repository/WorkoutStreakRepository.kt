package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.WorkoutStreak
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface WorkoutStreakRepository : JpaRepository<WorkoutStreak, Long> {
    fun findByUserAndDate(user: User, date: LocalDate): WorkoutStreak?

    @Query("SELECT MAX(ws.longestStreak) FROM WorkoutStreak ws WHERE ws.user = :user")
    fun findLongestStreakByUser(user: User): Int?

    @Query("SELECT ws FROM WorkoutStreak ws WHERE ws.user = :user ORDER BY ws.date DESC LIMIT 1")
    fun findLatestByUser(user: User): WorkoutStreak?
}
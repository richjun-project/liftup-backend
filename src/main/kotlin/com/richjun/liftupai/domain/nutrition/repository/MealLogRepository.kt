package com.richjun.liftupai.domain.nutrition.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.nutrition.entity.MealLog
import com.richjun.liftupai.domain.nutrition.entity.MealType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface MealLogRepository : JpaRepository<MealLog, Long> {
    fun findByUserAndTimestampBetween(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<MealLog>

    fun findByUserAndMealTypeAndTimestampBetween(
        user: User,
        mealType: MealType,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<MealLog>

    @Query("SELECT SUM(m.calories) FROM MealLog m WHERE m.user = :user AND m.timestamp BETWEEN :startDate AND :endDate")
    fun getTotalCaloriesByUserAndPeriod(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Int?

    @Query("SELECT AVG(m.calories) FROM MealLog m WHERE m.user = :user AND m.timestamp BETWEEN :startDate AND :endDate")
    fun getAverageCaloriesByUserAndPeriod(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Double?

    fun findTopByUserOrderByTimestampDesc(user: User): MealLog?
}
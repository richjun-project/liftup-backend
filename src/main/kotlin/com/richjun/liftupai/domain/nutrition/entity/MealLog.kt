package com.richjun.liftupai.domain.nutrition.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "meal_logs")
data class MealLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val mealType: MealType,

    @Column(columnDefinition = "TEXT")
    val foods: String,

    @Column(nullable = false)
    val calories: Int,

    @Column(nullable = false)
    val protein: Double,

    @Column(nullable = false)
    val carbs: Double,

    @Column(nullable = false)
    val fat: Double,

    @Column
    val imageUrl: String? = null,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now(),

    @Column(columnDefinition = "TEXT")
    val notes: String? = null
)

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK
}
package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "achievements")
data class Achievement(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val name: String,

    @Column(columnDefinition = "TEXT")
    val description: String,

    @Column(nullable = false)
    val icon: String = "🏆",

    @Column(nullable = false)
    val unlockedAt: LocalDateTime = LocalDateTime.now(),

    @Enumerated(EnumType.STRING)
    val type: AchievementType = AchievementType.WORKOUT_COUNT
)

enum class AchievementType {
    WORKOUT_COUNT,
    STREAK,
    VOLUME,
    PERSONAL_RECORD,
    CONSISTENCY,
    MILESTONE
}
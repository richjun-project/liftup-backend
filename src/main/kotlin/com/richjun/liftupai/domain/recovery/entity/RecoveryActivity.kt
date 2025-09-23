package com.richjun.liftupai.domain.recovery.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "recovery_activities")
data class RecoveryActivity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val activityType: RecoveryActivityType,

    @Column(nullable = false)
    val duration: Int, // minutes

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val intensity: RecoveryIntensity,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null,

    @ElementCollection
    @CollectionTable(
        name = "recovery_activity_body_parts",
        joinColumns = [JoinColumn(name = "activity_id")]
    )
    @Column(name = "body_part")
    val bodyParts: MutableSet<String> = mutableSetOf(),

    @Column(nullable = false)
    val performedAt: LocalDateTime,

    @Column
    val recoveryScore: Int? = null,

    @Column
    val recoveryBoost: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class RecoveryActivityType {
    STRETCHING,
    FOAM_ROLLING,
    MASSAGE,
    COLD_BATH,
    SAUNA,
    SLEEP
}

enum class RecoveryIntensity {
    LIGHT,
    MODERATE,
    INTENSE
}
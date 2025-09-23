package com.richjun.liftupai.domain.social.entity

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "shared_workouts")
class SharedWorkout(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val shareId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: WorkoutSession,

    @Column(nullable = false)
    val shareType: String,

    @Column(nullable = false)
    val visibility: String,

    @Column(nullable = false)
    val shareUrl: String,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false)
    val stats: String,

    @Column
    val imageUrl: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var viewCount: Int = 0
)
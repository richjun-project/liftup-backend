package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "personal_records")
data class PersonalRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(nullable = false)
    val weight: Double,

    @Column(nullable = false)
    val reps: Int,

    @Column(nullable = false)
    val date: LocalDateTime = LocalDateTime.now(),

    @Column
    val videoUrl: String? = null,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null
)
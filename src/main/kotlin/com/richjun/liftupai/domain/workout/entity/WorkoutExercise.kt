package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "workout_exercises")
data class WorkoutExercise(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: WorkoutSession,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    /** 운동 중 사용자가 순서를 바꾸면 세션 업데이트가 이 값을 갱신한다. */
    @Column(nullable = false)
    var orderInSession: Int,

    @OneToMany(mappedBy = "workoutExercise", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val sets: MutableList<ExerciseSet> = mutableListOf(),

    @Column
    var totalVolume: Double? = null  // kg
)
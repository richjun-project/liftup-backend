package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "exercises")
data class Exercise(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    val category: ExerciseCategory,

    @ElementCollection(targetClass = MuscleGroup::class, fetch = FetchType.EAGER)
    @CollectionTable(name = "exercise_muscle_groups", joinColumns = [JoinColumn(name = "exercise_id")])
    @Column(name = "muscle_groups")  // 명시적으로 컬럼명 지정
    @Enumerated(EnumType.STRING)
    val muscleGroups: MutableSet<MuscleGroup> = mutableSetOf(),

    @Enumerated(EnumType.STRING)
    val equipment: Equipment? = null,

    @Column(columnDefinition = "TEXT")
    val instructions: String? = null,

    @Column
    val imageUrl: String? = null
)
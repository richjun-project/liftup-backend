package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import jakarta.persistence.*

@Entity
@Table(name = "exercise_templates")
class ExerciseTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(name = "experience_level", nullable = false)
    @Enumerated(EnumType.STRING)
    val experienceLevel: ExperienceLevel,

    @Column(name = "workout_goal", nullable = false)
    @Enumerated(EnumType.STRING)
    val workoutGoal: WorkoutGoal,

    @Column(name = "sets", nullable = false)
    val sets: Int,

    @Column(name = "min_reps", nullable = false)
    val minReps: Int,

    @Column(name = "max_reps", nullable = false)
    val maxReps: Int,

    @Column(name = "weight_percentage")
    val weightPercentage: Double? = null, // 1RM의 퍼센트 또는 체중 대비 비율

    @Column(name = "rest_seconds", nullable = false)
    val restSeconds: Int,

    @Column(name = "set_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val setType: SetType = SetType.WORKING,

    @Column(name = "notes")
    val notes: String? = null
)

enum class WorkoutGoal {
    MUSCLE_GAIN,    // 근육량 증가
    STRENGTH,       // 근력 향상
    FAT_LOSS,       // 체지방 감소
    ENDURANCE,      // 지구력 향상
    GENERAL_FITNESS // 일반 체력
}

enum class SetType {
    WARM_UP,
    WORKING,
    DROP_SET,
    SUPER_SET,
    REST_PAUSE
}
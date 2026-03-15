package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import jakarta.persistence.*

@Entity
@Table(
    name = "canonical_programs",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_canonical_programs_code", columnNames = ["code"])
    ]
)
class CanonicalProgram(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    val code: String,

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val splitType: SplitType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val targetExperienceLevel: ExperienceLevel,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val targetGoal: WorkoutGoal,

    @Column(nullable = false)
    val daysPerWeek: Int,

    @Column(nullable = false)
    val programDurationWeeks: Int = 8,

    @Column(nullable = false)
    val deloadEveryNWeeks: Int = 4,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val progressionModel: ProgressionModel,

    @Column(length = 100)
    val nextProgramCode: String? = null,

    @Column(nullable = false)
    val version: Int = 1,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(nullable = false)
    val isActive: Boolean = true
)

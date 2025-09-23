package com.richjun.liftupai.domain.user.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_profiles")
class UserProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Embedded
    var bodyInfo: BodyInfo? = null,

    @ElementCollection(targetClass = FitnessGoal::class)
    @CollectionTable(name = "user_goals", joinColumns = [JoinColumn(name = "profile_id")])
    @Enumerated(EnumType.STRING)
    var goals: MutableSet<FitnessGoal> = mutableSetOf(),

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    var ptStyle: PTStyle = PTStyle.GAME_MASTER,

    @Enumerated(EnumType.STRING)
    var experienceLevel: ExperienceLevel = ExperienceLevel.BEGINNER,

    @Column
    var age: Int? = null,

    @Column
    var gender: String? = null,

    @Column
    var notificationEnabled: Boolean = true,

    @Deprecated("Use UserSettings.weeklyWorkoutDays instead")
    @Column
    var weeklyWorkoutDays: Int? = 3,

    @Deprecated("Use UserSettings.workoutSplit instead")
    @Column
    var workoutSplit: String? = "full_body",

    @Deprecated("Use UserSettings.availableEquipment instead")
    @ElementCollection
    @CollectionTable(name = "user_equipment", joinColumns = [JoinColumn(name = "profile_id")])
    @Column(name = "equipment")
    var availableEquipment: MutableSet<String> = mutableSetOf(),

    @Deprecated("Use UserSettings.preferredWorkoutTime instead")
    @Column
    var preferredWorkoutTime: String? = "evening",

    @Deprecated("Use UserSettings.workoutDuration instead")
    @Column
    var workoutDuration: Int? = 60,

    @Deprecated("Use UserSettings.injuries instead")
    @ElementCollection
    @CollectionTable(name = "user_profile_injuries", joinColumns = [JoinColumn(name = "profile_id")])
    @Column(name = "injury")
    var injuries: MutableSet<String> = mutableSetOf(),

    @Column
    var currentProgram: String? = null,

    @Column
    var currentWeek: Int = 1,

    @Column
    var lastWorkoutDate: LocalDateTime? = null,

    @Column(columnDefinition = "JSON")
    var muscleRecovery: String? = null, // JSON string of muscle group to last workout date

    @Column
    var strengthTestCompleted: Boolean = false,

    @Column(columnDefinition = "JSON")
    var estimatedMaxes: String? = null, // JSON string of exercise to 1RM

    @Column(columnDefinition = "JSON")
    var workingWeights: String? = null, // JSON string of exercise to working weight

    @Column
    var strengthLevel: String = "beginner", // beginner, novice, intermediate, advanced, elite

    @Column
    var gymLocation: String? = null,

    @Column
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Embeddable
class BodyInfo(
    @Column
    var height: Double? = null,  // cm

    @Column
    var weight: Double? = null,  // kg

    @Column
    var bodyFat: Double? = null,  // percentage

    @Column
    var muscleMass: Double? = null  // kg
)
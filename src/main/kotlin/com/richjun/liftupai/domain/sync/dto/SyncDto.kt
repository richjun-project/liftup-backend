package com.richjun.liftupai.domain.sync.dto

import jakarta.validation.constraints.NotNull

data class OfflineWorkoutSyncRequest(
    @field:NotNull
    val workouts: List<OfflineWorkout>,

    val lastSyncTime: String?
)

data class OfflineWorkout(
    val localId: String,
    val date: String,
    val exercises: List<OfflineExercise>,
    val duration: Int,
    val createdOffline: Boolean
)

data class OfflineExercise(
    val exerciseId: Long,
    val sets: List<OfflineSet>
)

data class OfflineSet(
    val weight: Double,
    val reps: Int,
    val restTime: Int?
)

data class OfflineWorkoutSyncResponse(
    val synced: Int,
    val failed: Int,
    val conflicts: List<SyncConflict>,
    val serverTime: String
)

data class SyncConflict(
    val localId: String,
    val reason: String,
    val resolution: String
)
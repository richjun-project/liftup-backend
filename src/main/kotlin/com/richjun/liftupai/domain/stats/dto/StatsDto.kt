package com.richjun.liftupai.domain.stats.dto

data class StatsOverviewResponse(
    val totalWorkouts: Int,
    val totalDuration: Int,
    val totalVolume: Double,
    val averageDuration: Int,
    val streak: Int
)

data class VolumeDataPoint(
    val date: String,
    val volume: Double
)

data class VolumeStatsResponse(
    val data: List<VolumeDataPoint>
)

data class MuscleDistribution(
    val muscleGroup: String,
    val percentage: Double,
    val sessions: Int
)

data class MuscleDistributionResponse(
    val distribution: List<MuscleDistribution>
)

data class PersonalRecord(
    val exerciseId: Long,
    val exerciseName: String,
    val weight: Double,
    val reps: Int,
    val date: String
)

data class PersonalRecordsResponse(
    val records: List<PersonalRecord>
)

data class ProgressDataPoint(
    val date: String,
    val value: Double,
    val change: Double
)

data class ProgressResponse(
    val progress: List<ProgressDataPoint>
)
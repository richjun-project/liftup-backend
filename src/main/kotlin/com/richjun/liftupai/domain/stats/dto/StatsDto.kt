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

/** 부위별 완료 세트(주 목표 10–20 세트와 비교하는 값). 운동의 주 부위는 1세트, 보조 부위는 0.5세트. */
data class MuscleSets(
    val key: String,
    val sets: Double
)

data class MuscleDistributionResponse(
    val distribution: List<MuscleDistribution>,
    /** MuscleGroup 이름(CHEST·LATS…)별 세트 — 몸 지도 색 */
    val muscles: List<MuscleSets> = emptyList(),
    /** 여섯 묶음(chest·back·shoulders·legs·arms·core)별 세트 — 한 세트가 한 묶음에 두 번 세지지 않는다 */
    val groups: List<MuscleSets> = emptyList()
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
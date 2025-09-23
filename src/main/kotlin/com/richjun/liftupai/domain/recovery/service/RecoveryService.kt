package com.richjun.liftupai.domain.recovery.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.recovery.dto.*
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivity
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivityType
import com.richjun.liftupai.domain.recovery.entity.RecoveryIntensity
import com.richjun.liftupai.domain.recovery.repository.MuscleRecoveryRepository
import com.richjun.liftupai.domain.recovery.repository.RecoveryActivityRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Service
@Transactional
class RecoveryService(
    private val userRepository: UserRepository,
    private val muscleRecoveryRepository: MuscleRecoveryRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val recoveryActivityRepository: RecoveryActivityRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getRecoveryStatus(userId: Long): RecoveryStatusResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        println("DEBUG RecoveryService.getRecoveryStatus - userId: $userId")

        updateRecoveryPercentages(user)

        val muscles = muscleRecoveryRepository.findByUser(user)
        println("DEBUG RecoveryService.getRecoveryStatus - found ${muscles.size} muscle recovery records from DB")
        muscles.forEach { muscle ->
            println("DEBUG RecoveryService.getRecoveryStatus - DB record: muscleGroup=${muscle.muscleGroup}, " +
                    "recoveryPercentage=${muscle.recoveryPercentage}, lastWorked=${muscle.lastWorked}")
        }

        val muscleStatuses = muscles.map { muscle ->
            MuscleRecoveryStatus(
                name = muscle.muscleGroup,
                recoveryPercentage = muscle.recoveryPercentage,
                lastWorked = muscle.lastWorked.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                estimatedRecoveryTime = calculateEstimatedRecoveryTime(muscle),
                status = determineRecoveryStatus(muscle.recoveryPercentage)
            )
        }.plus(getDefaultMuscleGroups(muscles))

        println("DEBUG RecoveryService.getRecoveryStatus - total muscle statuses: ${muscleStatuses.size}")
        println("DEBUG RecoveryService.getRecoveryStatus - muscle groups: ${muscleStatuses.map { it.name }}")

        return RecoveryStatusResponse(muscles = muscleStatuses)
    }

    fun updateRecovery(userId: Long, request: UpdateRecoveryRequest): UpdateRecoveryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, request.muscleGroup)
            .orElseGet {
                MuscleRecovery(
                    user = user,
                    muscleGroup = request.muscleGroup,
                    lastWorked = LocalDateTime.now()
                )
            }

        muscleRecovery.feelingScore = request.feelingScore
        muscleRecovery.soreness = request.soreness
        muscleRecovery.recoveryPercentage = calculateRecoveryPercentage(
            muscleRecovery.lastWorked,
            request.feelingScore,
            request.soreness
        )
        muscleRecovery.updatedAt = LocalDateTime.now()

        val saved = muscleRecoveryRepository.save(muscleRecovery)

        return UpdateRecoveryResponse(
            success = true,
            updatedStatus = MuscleRecoveryStatus(
                name = saved.muscleGroup,
                recoveryPercentage = saved.recoveryPercentage,
                lastWorked = saved.lastWorked.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                estimatedRecoveryTime = calculateEstimatedRecoveryTime(saved),
                status = determineRecoveryStatus(saved.recoveryPercentage)
            )
        )
    }

    @Transactional(readOnly = true)
    fun getRecoveryRecommendations(userId: Long): RecoveryRecommendationsResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val readyMuscles = muscleRecoveryRepository.findReadyMuscles(user, 80)
            .map { it.muscleGroup }

        val recoveryExercises = generateRecoveryExercises(user)
        val nutritionTips = generateNutritionTips(user)

        return RecoveryRecommendationsResponse(
            readyMuscles = readyMuscles.ifEmpty { getDefaultReadyMuscles() },
            recoveryExercises = recoveryExercises,
            nutritionTips = nutritionTips
        )
    }

    fun updateMuscleWorkout(userId: Long, muscleGroup: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val muscleRecovery = muscleRecoveryRepository.findByUserAndMuscleGroup(user, muscleGroup)
            .orElseGet {
                MuscleRecovery(
                    user = user,
                    muscleGroup = muscleGroup,
                    lastWorked = LocalDateTime.now()
                )
            }

        muscleRecovery.lastWorked = LocalDateTime.now()
        muscleRecovery.recoveryPercentage = 0
        muscleRecovery.updatedAt = LocalDateTime.now()

        muscleRecoveryRepository.save(muscleRecovery)
    }

    // 운동 완료 시 여러 근육 그룹 업데이트
    fun updateMuscleWorkoutForExercise(userId: Long, exercise: com.richjun.liftupai.domain.workout.entity.Exercise) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // muscleGroups 컬렉션의 각 근육 업데이트
        exercise.muscleGroups.forEach { muscleEnum ->
            val muscleName = mapMuscleGroupEnumToKorean(muscleEnum)
            updateMuscleWorkout(userId, muscleName)
        }

        // muscleGroups가 비어있으면 카테고리 기반으로 업데이트
        if (exercise.muscleGroups.isEmpty()) {
            val categoryMuscle = mapCategoryToMuscleGroup(exercise.category)
            updateMuscleWorkout(userId, categoryMuscle)
        }
    }

    private fun mapCategoryToMuscleGroup(category: com.richjun.liftupai.domain.workout.entity.ExerciseCategory): String {
        return when (category) {
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CHEST -> "가슴"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.BACK -> "등"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.LEGS -> "하체"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.SHOULDERS -> "어깨"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.ARMS -> "팔"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CORE -> "복근"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.CARDIO -> "유산소"
            com.richjun.liftupai.domain.workout.entity.ExerciseCategory.FULL_BODY -> "전신"
        }
    }

    private fun mapMuscleGroupEnumToKorean(muscleGroup: com.richjun.liftupai.domain.workout.entity.MuscleGroup): String {
        // Flutter 프론트엔드와 일치하는 16개 근육 그룹 매핑
        return when (muscleGroup) {
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CHEST -> "가슴"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.BACK -> "등"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.SHOULDERS -> "어깨"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.BICEPS -> "이두근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.TRICEPS -> "삼두근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.LEGS -> "다리"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CORE -> "코어"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.ABS -> "복근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.GLUTES -> "둔근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.CALVES -> "종아리"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.FOREARMS -> "전완근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.NECK -> "목"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.QUADRICEPS -> "대퇴사두근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.HAMSTRINGS -> "햄스트링"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.LATS -> "광배근"
            com.richjun.liftupai.domain.workout.entity.MuscleGroup.TRAPS -> "승모근"
        }
    }

    private fun updateRecoveryPercentages(user: com.richjun.liftupai.domain.auth.entity.User) {
        val muscles = muscleRecoveryRepository.findByUser(user)
        muscles.forEach { muscle ->
            val hoursSinceWorkout = ChronoUnit.HOURS.between(muscle.lastWorked, LocalDateTime.now())
            val baseRecovery = calculateBaseRecovery(hoursSinceWorkout)
            val adjustedRecovery = adjustRecoveryBySoreness(baseRecovery, muscle.soreness)

            muscle.recoveryPercentage = minOf(adjustedRecovery, 100)
            muscleRecoveryRepository.save(muscle)
        }
    }

    private fun calculateBaseRecovery(hoursSinceWorkout: Long): Int {
        return when {
            hoursSinceWorkout < 24 -> (hoursSinceWorkout * 2).toInt()
            hoursSinceWorkout < 48 -> 50 + ((hoursSinceWorkout - 24) * 1.5).toInt()
            hoursSinceWorkout < 72 -> 85 + ((hoursSinceWorkout - 48) * 0.5).toInt()
            else -> 100
        }
    }

    private fun adjustRecoveryBySoreness(baseRecovery: Int, soreness: Int): Int {
        val sorenessMultiplier = 1.0 - (soreness * 0.05)
        return (baseRecovery * sorenessMultiplier).toInt()
    }

    private fun calculateRecoveryPercentage(lastWorked: LocalDateTime, feelingScore: Int, soreness: Int): Int {
        val hoursSinceWorkout = ChronoUnit.HOURS.between(lastWorked, LocalDateTime.now())
        val baseRecovery = calculateBaseRecovery(hoursSinceWorkout)

        val feelingMultiplier = feelingScore / 10.0
        val sorenessMultiplier = 1.0 - (soreness * 0.05)

        return minOf((baseRecovery * feelingMultiplier * sorenessMultiplier).toInt(), 100)
    }

    private fun calculateEstimatedRecoveryTime(muscle: MuscleRecovery): Int {
        if (muscle.recoveryPercentage >= 100) return 0

        val remainingRecovery = 100 - muscle.recoveryPercentage
        val recoveryRate = 100.0 / (48 + muscle.soreness * 4)

        return (remainingRecovery / recoveryRate).toInt()
    }

    private fun determineRecoveryStatus(recoveryPercentage: Int): String {
        return when {
            recoveryPercentage >= 100 -> "완전 회복"
            recoveryPercentage >= 80 -> "운동 가능"
            recoveryPercentage >= 60 -> "가벼운 운동 가능"
            recoveryPercentage >= 40 -> "회복 중"
            else -> "휴식 필요"
        }
    }

    private fun getDefaultMuscleGroups(existingMuscles: List<MuscleRecovery>): List<MuscleRecoveryStatus> {
        // Flutter 프론트엔드와 일치하는 16개 근육 그룹
        val defaultGroups = listOf(
            "가슴",           // chest
            "등",             // back
            "어깨",           // shoulders
            "이두근",         // biceps
            "삼두근",         // triceps
            "다리",           // legs
            "코어",           // core
            "복근",           // abs
            "둔근",           // glutes
            "종아리",         // calves
            "전완근",         // forearms
            "목",             // neck
            "대퇴사두근",     // quadriceps
            "햄스트링",       // hamstrings
            "광배근",         // lats
            "승모근"          // traps
        )
        val existingGroups = existingMuscles.map { it.muscleGroup }.toSet()

        return defaultGroups.filter { it !in existingGroups }.map { group ->
            MuscleRecoveryStatus(
                name = group,
                recoveryPercentage = 100,
                lastWorked = null,
                estimatedRecoveryTime = 0,
                status = "완전 회복"
            )
        }
    }

    private fun getDefaultReadyMuscles(): List<String> {
        return listOf("가슴", "등", "어깨", "팔", "하체", "복근")
    }

    private fun generateRecoveryExercises(user: com.richjun.liftupai.domain.auth.entity.User): List<RecoveryExercise> {
        val recoveringMuscles = muscleRecoveryRepository.findRecoveringMuscles(user)

        val exercises = mutableListOf<RecoveryExercise>()

        if (recoveringMuscles.any { it.soreness >= 5 }) {
            exercises.add(
                RecoveryExercise(
                    name = "가벼운 스트레칭",
                    description = "전신 스트레칭으로 근육 긴장을 완화하세요",
                    duration = 15,
                    type = "스트레칭"
                )
            )
        }

        exercises.addAll(listOf(
            RecoveryExercise(
                name = "폼롤러 마사지",
                description = "근육 뭉침을 풀어주고 혈액순환을 촉진합니다",
                duration = 10,
                type = "마사지"
            ),
            RecoveryExercise(
                name = "가벼운 유산소",
                description = "20-30분 가벼운 조깅이나 자전거 타기",
                duration = 25,
                type = "유산소"
            ),
            RecoveryExercise(
                name = "요가",
                description = "유연성 향상과 근육 이완을 위한 요가",
                duration = 30,
                type = "요가"
            )
        ))

        return exercises
    }

    fun boostMuscleRecovery(userId: Long, muscleGroup: String, boostPercentage: Int) {
        val muscleRecoveries = muscleRecoveryRepository.findByUser_Id(userId)

        val recovery = muscleRecoveries.find {
            it.muscleGroup.equals(muscleGroup, ignoreCase = true)
        } ?: return

        val newRecoveryPercentage = (recovery.recoveryPercentage + boostPercentage).coerceIn(0, 100)
        recovery.recoveryPercentage = newRecoveryPercentage
        recovery.updatedAt = LocalDateTime.now()

        muscleRecoveryRepository.save(recovery)
        logger.info("Boosted recovery for $muscleGroup by $boostPercentage% for user $userId")
    }

    private fun generateNutritionTips(user: com.richjun.liftupai.domain.auth.entity.User): List<NutritionTip> {
        return listOf(
            NutritionTip(
                tip = "단백질 섭취량 늘리기",
                reason = "근육 회복과 성장을 위해 체중 kg당 1.5-2g의 단백질을 섭취하세요"
            ),
            NutritionTip(
                tip = "충분한 수분 섭취",
                reason = "하루 2-3리터의 물을 마셔 근육 회복을 돕고 노폐물 배출을 촉진하세요"
            ),
            NutritionTip(
                tip = "BCAA 보충",
                reason = "분지사슬아미노산은 근육 손상을 줄이고 회복 속도를 높입니다"
            ),
            NutritionTip(
                tip = "충분한 수면",
                reason = "7-9시간의 수면은 성장호르몬 분비를 촉진해 근육 회복을 돕습니다"
            ),
            NutritionTip(
                tip = "항산화 식품 섭취",
                reason = "베리류, 녹색 채소 등으로 운동으로 인한 산화 스트레스를 줄이세요"
            )
        )
    }

    // RecoveryActivityService methods
    fun recordActivity(userId: Long, request: RecordActivityRequest): RecordActivityResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Calculate recovery score based on activity
        val recoveryScore = calculateRecoveryScore(request)
        val recoveryBoost = calculateRecoveryBoost(request)

        val activity = RecoveryActivity(
            user = user,
            activityType = request.activityType,
            duration = request.duration,
            intensity = request.intensity,
            notes = request.notes,
            bodyParts = request.bodyParts.toMutableSet(),
            performedAt = request.performedAt,
            recoveryScore = recoveryScore,
            recoveryBoost = recoveryBoost
        )

        val saved = recoveryActivityRepository.save(activity)

        // Update muscle recovery based on activity
        updateMuscleRecoveryForActivity(userId, request.bodyParts, recoveryBoost)

        val nextRecommendation = generateNextRecommendation(userId, request.activityType)

        logger.info("Recovery activity recorded for user $userId: ${request.activityType}")

        return RecordActivityResponse(
            activityId = saved.id.toString(),
            recoveryScore = recoveryScore,
            recoveryBoost = recoveryBoost,
            nextRecommendation = nextRecommendation,
            recorded = true
        )
    }

    @Transactional(readOnly = true)
    fun getRecoveryHistory(userId: Long, startDate: String, endDate: String, activityType: String?): RecoveryHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val start = java.time.LocalDate.parse(startDate).atStartOfDay()
        val end = java.time.LocalDate.parse(endDate).atTime(23, 59, 59)

        val activities = if (activityType == null || activityType == "all") {
            recoveryActivityRepository.findByUserAndPerformedAtBetween(user, start, end)
        } else {
            try {
                val type = RecoveryActivityType.valueOf(activityType.uppercase())
                recoveryActivityRepository.findByUserAndActivityType(user, type)
                    .filter { it.performedAt in start..end }
            } catch (e: IllegalArgumentException) {
                recoveryActivityRepository.findByUserAndPerformedAtBetween(user, start, end)
            }
        }

        // Group activities by date
        val groupedActivities = activities.groupBy { it.performedAt.toLocalDate() }

        val history = groupedActivities.map { (date, dayActivities) ->
            val dayScore = calculateDailyRecoveryScore(dayActivities)
            val muscleSoreness = getMuscleStressorForDate(userId, date)

            DailyRecoveryHistory(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                activities = dayActivities.map { activity ->
                    RecoveryActivityDetail(
                        activityId = activity.id.toString(),
                        activityType = activity.activityType,
                        duration = activity.duration,
                        intensity = activity.intensity,
                        performedAt = activity.performedAt,
                        recoveryImpact = activity.recoveryBoost ?: "+0%"
                    )
                },
                dailyRecoveryScore = dayScore,
                muscleSoreness = muscleSoreness
            )
        }.sortedBy { it.date }

        val summary = createRecoverySummary(userId, activities, start, end)

        return RecoveryHistoryResponse(
            history = history,
            summary = summary
        )
    }

    private fun calculateRecoveryScore(request: RecordActivityRequest): Int {
        var score = 50 // Base score

        // Activity type bonus
        score += when (request.activityType) {
            RecoveryActivityType.SLEEP -> 30
            RecoveryActivityType.MASSAGE -> 25
            RecoveryActivityType.STRETCHING -> 20
            RecoveryActivityType.FOAM_ROLLING -> 20
            RecoveryActivityType.COLD_BATH -> 15
            RecoveryActivityType.SAUNA -> 15
        }

        // Duration bonus
        score += when {
            request.duration >= 60 -> 20
            request.duration >= 30 -> 15
            request.duration >= 15 -> 10
            else -> 5
        }

        // Intensity adjustment
        score += when (request.intensity) {
            RecoveryIntensity.INTENSE -> 10
            RecoveryIntensity.MODERATE -> 5
            RecoveryIntensity.LIGHT -> 0
        }

        return score.coerceIn(0, 100)
    }

    private fun calculateRecoveryBoost(request: RecordActivityRequest): String {
        val boostPercentage = when (request.activityType) {
            RecoveryActivityType.SLEEP -> {
                if (request.duration >= 420) "+15%" else "+10%"
            }
            RecoveryActivityType.MASSAGE -> "+12%"
            RecoveryActivityType.STRETCHING -> {
                when (request.intensity) {
                    RecoveryIntensity.INTENSE -> "+8%"
                    RecoveryIntensity.MODERATE -> "+5%"
                    RecoveryIntensity.LIGHT -> "+3%"
                }
            }
            RecoveryActivityType.FOAM_ROLLING -> "+7%"
            RecoveryActivityType.COLD_BATH -> "+6%"
            RecoveryActivityType.SAUNA -> "+5%"
        }

        return boostPercentage
    }

    private fun updateMuscleRecoveryForActivity(userId: Long, bodyParts: Set<String>, recoveryBoost: String) {
        if (bodyParts.isEmpty()) return

        val boostValue = recoveryBoost.removeSuffix("%").removePrefix("+").toIntOrNull() ?: 0

        bodyParts.forEach { bodyPart ->
            boostMuscleRecovery(userId, bodyPart, boostValue)
        }
    }

    private fun generateNextRecommendation(userId: Long, lastActivity: RecoveryActivityType): String {
        return when (lastActivity) {
            RecoveryActivityType.STRETCHING -> "다음 운동 전 폼롤링을 추천합니다"
            RecoveryActivityType.FOAM_ROLLING -> "충분한 수분 섭취를 잊지 마세요"
            RecoveryActivityType.MASSAGE -> "가벼운 스트레칭으로 마무리하세요"
            RecoveryActivityType.COLD_BATH -> "체온 회복을 위해 따뜻한 차를 드세요"
            RecoveryActivityType.SAUNA -> "수분 보충이 중요합니다"
            RecoveryActivityType.SLEEP -> "일어나서 가벼운 스트레칭을 해보세요"
        }
    }

    private fun calculateDailyRecoveryScore(activities: List<RecoveryActivity>): Int {
        if (activities.isEmpty()) return 50

        val averageScore = activities
            .mapNotNull { it.recoveryScore }
            .average()

        return if (averageScore.isNaN()) 50 else averageScore.toInt()
    }

    private fun getMuscleStressorForDate(userId: Long, date: java.time.LocalDate): MuscleSoreness {
        val muscleRecoveries = muscleRecoveryRepository.findByUser_Id(userId)

        val details = muscleRecoveries.associate { recovery ->
            recovery.muscleGroup to (100 - recovery.recoveryPercentage).coerceIn(0, 10) / 10
        }

        val overall = if (details.isNotEmpty()) {
            details.values.average().toInt()
        } else {
            0
        }

        return MuscleSoreness(
            overall = overall,
            details = details
        )
    }

    private fun createRecoverySummary(
        userId: Long,
        activities: List<RecoveryActivity>,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): RecoverySummary {
        val totalActivities = activities.size

        val mostFrequent = if (activities.isNotEmpty()) {
            val frequencies = recoveryActivityRepository.findMostFrequentActivityType(userId, startDate, endDate)
            if (frequencies.isNotEmpty()) {
                frequencies.first()[0].toString().lowercase()
            } else {
                "none"
            }
        } else {
            "none"
        }

        val averageScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, startDate, endDate)
            ?.toInt() ?: 0

        val trend = determineTrend(userId, startDate, endDate)

        return RecoverySummary(
            totalActivities = totalActivities,
            mostFrequent = mostFrequent,
            averageRecoveryScore = averageScore,
            trend = trend
        )
    }

    private fun determineTrend(userId: Long, startDate: LocalDateTime, endDate: LocalDateTime): String {
        val midPoint = startDate.plusDays((endDate.toLocalDate().toEpochDay() - startDate.toLocalDate().toEpochDay()) / 2)

        val firstHalfScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, startDate, midPoint)
        val secondHalfScore = recoveryActivityRepository.calculateAverageRecoveryScore(userId, midPoint, endDate)

        return when {
            firstHalfScore == null || secondHalfScore == null -> "stable"
            secondHalfScore > firstHalfScore + 5 -> "improving"
            secondHalfScore < firstHalfScore - 5 -> "declining"
            else -> "stable"
        }
    }
}
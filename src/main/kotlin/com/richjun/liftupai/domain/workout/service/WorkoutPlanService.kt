package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.domain.workout.entity.WorkoutPlan
import com.richjun.liftupai.domain.workout.repository.WorkoutPlanRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTemplateRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseSetRepository
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.WorkoutGoal
import com.richjun.liftupai.domain.workout.util.ExerciseNameNormalizer
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional
class WorkoutPlanService(
    val userRepository: UserRepository,
    val userProfileRepository: UserProfileRepository,
    private val workoutPlanRepository: WorkoutPlanRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val userService: UserService,
    private val aiAnalysisService: AIAnalysisService,
    private val objectMapper: ObjectMapper,
    val exerciseRepository: ExerciseRepository,
    val exerciseTemplateRepository: ExerciseTemplateRepository,
    val personalRecordRepository: PersonalRecordRepository,
    val exerciseSetRepository: ExerciseSetRepository,
    private val exerciseNameNormalizer: ExerciseNameNormalizer
) {

    fun updateWorkoutPlan(userId: Long, request: WorkoutPlanRequest): WorkoutPlanResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        // Update profile with plan details
        profile.weeklyWorkoutDays = request.weeklyWorkoutDays
        profile.workoutSplit = request.workoutSplit
        profile.preferredWorkoutTime = request.preferredWorkoutTime
        profile.workoutDuration = request.workoutDuration
        profile.availableEquipment.clear()
        profile.availableEquipment.addAll(request.availableEquipment)
        profile.updatedAt = LocalDateTime.now()

        userProfileRepository.save(profile)

        // Determine recommended program based on parameters
        val recommendedProgram = determineRecommendedProgram(
            request.weeklyWorkoutDays,
            request.workoutSplit,
            profile.experienceLevel.name
        )

        profile.currentProgram = recommendedProgram
        userProfileRepository.save(profile)

        return WorkoutPlanResponse(
            weeklyWorkoutDays = request.weeklyWorkoutDays,
            workoutSplit = request.workoutSplit,
            preferredWorkoutTime = request.preferredWorkoutTime,
            workoutDuration = request.workoutDuration,
            availableEquipment = request.availableEquipment,
            recommendedProgram = recommendedProgram
        )
    }

    fun generateProgram(userId: Long, request: GenerateProgramRequest): GeneratedProgramResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // Use AI to generate personalized program
        val prompt = buildProgramPrompt(request)
        val aiResponse = "AI 생성 프로그램" // Simplified for now

        // Parse AI response to create program schedule
        val schedule = parseAIResponseToSchedule(aiResponse, request)

        // Save as workout plan
        val programName = "${request.weeklyWorkoutDays}일 ${translateSplit(request.workoutSplit)} ${translateGoals(request.goals)} 프로그램"

        val workoutPlan = WorkoutPlan(
            user = user,
            name = programName,
            weeklyDays = request.weeklyWorkoutDays,
            splitType = request.workoutSplit,
            programDurationWeeks = 8,
            schedule = objectMapper.writeValueAsString(schedule)
        )

        workoutPlanRepository.save(workoutPlan)

        // Update profile
        val profile = userProfileRepository.findByUser_Id(userId).orElseThrow()
        profile.currentProgram = programName
        profile.currentWeek = 1
        userProfileRepository.save(profile)

        return GeneratedProgramResponse(
            programName = programName,
            weeks = 8,
            schedule = schedule
        )
    }

    fun getTodayWorkoutRecommendation(userId: Long, request: TodayWorkoutRequest): TodayWorkoutResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        // Determine what muscle groups are ready for training
        val readyMuscles = determineReadyMuscles(request.muscleRecovery)

        // Generate workout based on split and recovery
        val (workoutName, targetMuscles) = determineWorkout(
            request.workoutSplit,
            readyMuscles,
            request.lastWorkoutDate
        )

        // Use AI to get exercises
        val prompt = buildWorkoutPrompt(
            workoutName,
            targetMuscles,
            request.experienceLevel,
            request.goals,
            profile.availableEquipment.toList()
        )

        val aiResponse = "AI 생성 프로그램" // Simplified for now
        val exercises = parseAIResponseToExercises(aiResponse, targetMuscles)

        val reason = buildReasonMessage(
            request.lastWorkoutDate,
            readyMuscles,
            request.weeklyWorkoutDays,
            request.workoutSplit
        )

        return TodayWorkoutResponse(
            workoutName = workoutName,
            targetMuscles = targetMuscles,
            estimatedDuration = profile.workoutDuration ?: 60,
            exercises = exercises,
            reason = reason
        )
    }

    @Transactional(readOnly = true)
    fun getWeeklyStats(userId: Long): WeeklyStatsResponse {
        val profile = userProfileRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("프로필을 찾을 수 없습니다") }

        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay()
        val weekEnd = weekStart.plusDays(6).plusHours(23).plusMinutes(59)

        val sessions = workoutSessionRepository.findByUser_IdAndStartTimeBetween(
            userId, weekStart, weekEnd
        )

        val totalVolume = sessions.sumOf { session -> session.totalVolume ?: 0.0 }
        // Simplified calculation for now
        val totalSets = sessions.size * 20 // Estimated
        val totalReps = sessions.size * 200 // Estimated

        val workoutDates = sessions.map { session -> session.startTime.toLocalDate().toString() }
        val completedDays = workoutDates.size

        // Calculate next workout day
        val nextWorkoutDay = calculateNextWorkoutDay(
            profile.weeklyWorkoutDays ?: 3,
            workoutDates,
            profile.preferredWorkoutTime ?: "evening"
        )

        val weeklyProgress = (completedDays * 100) / (profile.weeklyWorkoutDays ?: 3)

        return WeeklyStatsResponse(
            targetDays = profile.weeklyWorkoutDays ?: 3,
            completedDays = completedDays,
            totalVolume = totalVolume,
            totalSets = totalSets,
            totalReps = totalReps,
            workoutDates = workoutDates,
            nextWorkoutDay = nextWorkoutDay,
            weeklyProgress = weeklyProgress
        )
    }

    private fun determineRecommendedProgram(days: Int, split: String, level: String): String {
        return when {
            days <= 2 -> "전신 운동 프로그램"
            days == 3 && split == "full_body" -> "3일 전신 운동 프로그램"
            days == 3 && split == "ppl" -> "초급 밀기/당기기/하체 프로그램"
            days == 4 && split == "upper_lower" -> "중급 상하체 분할 프로그램"
            days == 5 && split == "ppl" -> "고급 밀기/당기기/하체 프로그램"
            days >= 6 && split == "bro_split" -> "보디빌딩 분할 프로그램"
            else -> "${days}일 ${translateSplit(split)} 프로그램"
        }
    }

    private fun translateSplit(split: String): String {
        return when (split.lowercase()) {
            "full_body" -> "전신"
            "upper_lower" -> "상하체 분할"
            "ppl", "push_pull_legs" -> "밀기/당기기/하체"
            "push_pull" -> "밀기/당기기"
            "bro_split" -> "부위별 분할"
            else -> split
        }
    }

    private fun translateGoals(goals: List<String>): String {
        val translated = goals.map {
            when (it.uppercase()) {
                "MUSCLE_GAIN" -> "근육량 증가"
                "FAT_LOSS" -> "체지방 감소"
                "STRENGTH" -> "근력 향상"
                "ENDURANCE" -> "지구력 향상"
                else -> it
            }
        }
        return translated.joinToString(", ")
    }

    private fun determineReadyMuscles(muscleRecovery: Map<String, String>?): List<String> {
        if (muscleRecovery.isNullOrEmpty()) {
            return listOf("가슴", "등", "다리", "어깨", "팔", "복근")
        }

        val now = LocalDateTime.now()
        val readyMuscles = mutableListOf<String>()

        val allMuscles = listOf("가슴", "등", "다리", "어깨", "팔", "복근")

        allMuscles.forEach { muscle ->
            val lastWorkout = muscleRecovery[muscle]?.let {
                try {
                    LocalDateTime.parse(it)
                } catch (e: Exception) {
                    null
                }
            }

            if (lastWorkout == null || lastWorkout.plusHours(48).isBefore(now)) {
                readyMuscles.add(muscle)
            }
        }

        return readyMuscles.ifEmpty { allMuscles }
    }

    private fun determineWorkout(
        split: String,
        readyMuscles: List<String>,
        lastWorkoutDate: LocalDateTime?
    ): Pair<String, List<String>> {
        return when (split) {
            "upper_lower" -> {
                if (readyMuscles.containsAll(listOf("가슴", "등", "어깨"))) {
                    "상체 운동" to listOf("가슴", "등", "어깨", "팔")
                } else {
                    "하체 운동" to listOf("다리", "엉덩이", "복근")
                }
            }
            "ppl", "push_pull_legs" -> {
                when {
                    readyMuscles.containsAll(listOf("가슴", "어깨")) -> "Push Day" to listOf("가슴", "어깨", "삼두")
                    readyMuscles.contains("등") -> "Pull Day" to listOf("등", "이두")
                    readyMuscles.contains("다리") -> "Leg Day" to listOf("다리", "엉덩이")
                    else -> "전신 운동" to readyMuscles
                }
            }
            "full_body" -> "전신 운동" to listOf("가슴", "등", "다리", "어깨")
            else -> "전신 운동" to readyMuscles.take(3)
        }
    }

    private fun buildProgramPrompt(request: GenerateProgramRequest): String {
        return """
            운동 프로그램을 생성해주세요:
            - 주당 운동 일수: ${request.weeklyWorkoutDays}일
            - 분할: ${translateSplit(request.workoutSplit)}
            - 경험 수준: ${request.experienceLevel}
            - 목표: ${request.goals.joinToString(", ")}
            - 사용 가능 장비: ${request.availableEquipment.joinToString(", ")}
            - 운동 시간: ${request.duration}분

            각 요일별로 구체적인 운동 종목, 세트, 반복수, 휴식시간을 포함해주세요.
        """.trimIndent()
    }

    private fun buildWorkoutPrompt(
        workoutName: String,
        targetMuscles: List<String>,
        level: String,
        goals: List<String>,
        equipment: List<String>
    ): String {
        return """
            오늘의 운동: $workoutName
            타겟 근육: ${targetMuscles.joinToString(", ")}
            경험 수준: $level
            목표: ${goals.joinToString(", ")}
            사용 가능 장비: ${equipment.joinToString(", ")}

            5-7개의 운동을 추천해주세요. 각 운동에 대해:
            - 운동 이름
            - 세트 수와 반복수
            - 휴식 시간
            - 수행 팁
        """.trimIndent()
    }

    private fun parseAIResponseToSchedule(aiResponse: String, request: GenerateProgramRequest): ProgramSchedule {
        // Simple parsing - in production, use more sophisticated parsing
        return ProgramSchedule(
            monday = if (request.weeklyWorkoutDays >= 1) WorkoutDay(
                name = "Day 1",
                exercises = listOf(
                    ExercisePlan("벤치프레스", 4, "8-10", 120),
                    ExercisePlan("인클라인 덤벨프레스", 3, "10-12", 90),
                    ExercisePlan("케이블 플라이", 3, "12-15", 60)
                )
            ) else null,
            tuesday = if (request.weeklyWorkoutDays >= 2) WorkoutDay(
                name = "Day 2",
                exercises = listOf(
                    ExercisePlan("스쿼트", 4, "8-10", 120),
                    ExercisePlan("레그프레스", 3, "10-12", 90)
                )
            ) else null,
            wednesday = null,
            thursday = if (request.weeklyWorkoutDays >= 3) WorkoutDay(
                name = "Day 3",
                exercises = listOf(
                    ExercisePlan("데드리프트", 4, "6-8", 180),
                    ExercisePlan("풀업", 3, "8-10", 90)
                )
            ) else null,
            friday = if (request.weeklyWorkoutDays >= 4) WorkoutDay(
                name = "Day 4",
                exercises = listOf(
                    ExercisePlan("숄더프레스", 4, "8-10", 90)
                )
            ) else null,
            saturday = if (request.weeklyWorkoutDays >= 5) WorkoutDay(
                name = "Day 5",
                exercises = listOf(
                    ExercisePlan("바벨컬", 3, "10-12", 60)
                )
            ) else null,
            sunday = null
        )
    }

    // 운동 이름으로 DB에서 운동 찾기 (개선된 버전)
    private fun findExerciseByName(name: String): Long? {
        // 1. 정규화된 이름으로 먼저 검색
        val normalizedName = exerciseNameNormalizer.normalize(name)

        // 정확한 매칭 시도
        exerciseRepository.findByExactOrCompactName(normalizedName).firstOrNull()?.let {
            return it.id
        }

        // 2. 정규화된 쿼리로 검색 (철자 변형 처리)
        exerciseRepository.findByNormalizedName(normalizedName).firstOrNull()?.let {
            return it.id
        }

        // 3. 생성된 변형들로 검색
        val variations = exerciseNameNormalizer.generateVariations(name)
        val lowercaseVariations = variations.map { it.lowercase() }

        exerciseRepository.findByNameIn(lowercaseVariations).firstOrNull()?.let {
            return it.id
        }

        // 4. 기존 로직 유지 (폴백)
        for (variation in variations) {
            val exercise = exerciseRepository.findByNameIgnoreCase(variation)
            if (exercise != null) {
                return exercise.id
            }
        }

        // 5. 영어 이름으로도 찾기 (확장된 매핑)
        val englishNames = mapOf(
            "스쿼트" to "squat",
            "레그프레스" to "leg press",
            "런지" to "lunge",
            "벤치프레스" to "bench press",
            "인클라인 덤벨프레스" to "incline dumbbell press",
            "풀업" to "pull up",
            "바벨로우" to "barbell row",
            "사이드레터럴레이즈" to "lateral raise",
            "플랭크" to "plank",
            "푸시업" to "push up",
            "푸시다운" to "pushdown",
            "푸쉬다운" to "pushdown",
            "풀다운" to "pulldown",
            "랫풀다운" to "lat pulldown",
            "덤벨플라이" to "dumbbell fly",
            "데드리프트" to "deadlift"
        )

        englishNames[normalizedName]?.let { englishName ->
            return findExerciseByName(englishName) // 재귀 호출로 영어 이름도 정규화 처리
        }

        // 6. 부분 매칭 시도 (마지막 수단)
        exerciseRepository.searchByName(normalizedName).firstOrNull()?.let {
            return it.id
        }

        return null
    }

    private fun parseAIResponseToExercises(aiResponse: String, targetMuscles: List<String>): List<ExerciseDetailV4> {
        // 현재 메서드는 getTodayWorkoutRecommendation에서 호출됨
        // request를 통해 사용자 정보 접근 필요
        return parseAIResponseToExercisesWithUser(aiResponse, targetMuscles, null, null, null)
    }

    private fun parseAIResponseToExercisesWithUser(
        aiResponse: String,
        targetMuscles: List<String>,
        userId: Long?,
        experienceLevel: String?,
        goals: List<String>?
    ): List<ExerciseDetailV4> {
        // 타겟 근육에 따라 적절한 운동 선택
        val exercises = mutableListOf<ExerciseDetailV4>()

        targetMuscles.forEach { muscle ->
            when (muscle.lowercase()) {
                "다리", "하체", "legs", "대퇴사두근", "햄스트링", "엉덩이" -> {
                    // 스쿼트 추가
                    val squatId = findExerciseByName("스쿼트") ?: findExerciseByName("squat")
                    squatId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "스쿼트",
                                    targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 15, 0.0, "warm_up"),
                                SetDetail(2, 12, 40.0, "working"),
                                SetDetail(3, 10, 60.0, "working"),
                                SetDetail(4, 8, 80.0, "working")
                                ),
                                restTime = 150,
                                tips = "무릎이 발끝을 넘지 않도록 주의하며 엉덩이를 뒤로 빼며 앉아주세요"
                                )
                        )
                    }

                    // 레그프레스 추가
                    val legPressId = findExerciseByName("레그프레스") ?: findExerciseByName("leg press")
                    legPressId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "레그프레스",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 15, 40.0, "working"),
                                SetDetail(2, 12, 60.0, "working"),
                                SetDetail(3, 10, 80.0, "working")
                                ),
                                restTime = 120,
                                tips = "발을 어깨너비로 벌리고 무릎을 90도까지 굽혀주세요"
                                )
                        )
                    }

                    // 런지 추가
                    val lungeId = findExerciseByName("런지") ?: findExerciseByName("lunge")
                    lungeId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "런지",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 12, 0.0, "working"),
                                SetDetail(2, 10, 10.0, "working"),
                                SetDetail(3, 10, 10.0, "working")
                                ),
                                restTime = 90,
                                tips = "앞무릎이 90도가 되도록 내려가고 균형을 유지하세요"
                            )
                        )
                    }
                }
                "가슴", "chest", "대흉근", "삼두" -> {
                    // 벤치프레스 추가
                    val benchPressId = findExerciseByName("벤치프레스") ?: findExerciseByName("bench press")
                    benchPressId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "벤치프레스",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 12, 20.0, "warm_up"),
                                SetDetail(2, 10, 60.0, "working"),
                                SetDetail(3, 8, 70.0, "working"),
                                SetDetail(4, 8, 70.0, "working")
                                ),
                                restTime = 120,
                                tips = "가슴 근육에 집중하며 천천히 내리고 폭발적으로 밀어올리세요"
                            )
                        )
                    }

                    // 인클라인 덤벨프레스 추가
                    val inclineDbPressId = findExerciseByName("인클라인 덤벨프레스") ?: findExerciseByName("incline dumbbell press")
                    inclineDbPressId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "인클라인 덤벨프레스",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 12, 15.0, "working"),
                                SetDetail(2, 10, 20.0, "working"),
                                SetDetail(3, 10, 20.0, "working")
                                ),
                                restTime = 90,
                                tips = "상부 가슴에 집중하며 덤벨을 아치형으로 밀어주세요"
                            )
                        )
                    }
                }
                "등", "back", "광배근", "이두" -> {
                    // 풀업 추가
                    val pullUpId = findExerciseByName("풀업") ?: findExerciseByName("pull up")
                    pullUpId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "풀업",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 8, 0.0, "working"),
                                SetDetail(2, 6, 0.0, "working"),
                                SetDetail(3, 6, 0.0, "working")
                                ),
                                restTime = 120,
                                tips = "등 근육으로 당긴다는 느낌으로 천천히 올라가세요"
                            )
                        )
                    }

                    // 바벨로우 추가
                    val barbellRowId = findExerciseByName("바벨로우") ?: findExerciseByName("barbell row")
                    barbellRowId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "바벨로우",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 12, 30.0, "working"),
                                SetDetail(2, 10, 40.0, "working"),
                                SetDetail(3, 10, 40.0, "working")
                                ),
                                restTime = 90,
                                tips = "허리를 곧게 펴고 등 중앙으로 당기세요"
                            )
                        )
                    }
                }
                "어깨", "shoulders", "삼각근" -> {
                    // 숄더프레스 추가
                    val shoulderPressId = findExerciseByName("숄더프레스") ?: findExerciseByName("shoulder press")
                    shoulderPressId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "숄더프레스",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 12, 10.0, "warm_up"),
                                SetDetail(2, 10, 20.0, "working"),
                                SetDetail(3, 8, 25.0, "working")
                                ),
                                restTime = 90,
                                tips = "코어에 힘을 주고 안정적으로 밀어올리세요"
                            )
                        )
                    }

                    // 사이드레터럴레이즈 추가
                    val lateralRaiseId = findExerciseByName("사이드레터럴레이즈") ?: findExerciseByName("lateral raise")
                    lateralRaiseId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "사이드 레터럴 레이즈",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 15, 5.0, "working"),
                                SetDetail(2, 12, 7.0, "working"),
                                SetDetail(3, 12, 7.0, "working")
                                ),
                                restTime = 60,
                                tips = "팔꿈치를 살짝 굽히고 어깨 높이까지만 올리세요"
                            )
                        )
                    }
                }
                "복근", "abs", "코어" -> {
                    // 플랭크 추가
                    val plankId = findExerciseByName("플랭크") ?: findExerciseByName("plank")
                    plankId?.let {
                        exercises.add(
                            ExerciseDetailV4(
                                id = it.toString(),
                                name = "플랭크",
                                targetMuscle = muscle,
                                sets = listOf(
                                SetDetail(1, 30, 0.0, "working"),  // 30초
                                SetDetail(2, 45, 0.0, "working"),  // 45초
                                SetDetail(3, 60, 0.0, "working")   // 60초
                                ),
                                restTime = 60,
                                tips = "몸을 일직선으로 유지하고 코어에 집중하세요"
                            )
                        )
                    }
                }
            }
        }

        return exercises.ifEmpty {
            // 운동이 없으면 기본 전신 운동 반환
            val pushUpId = findExerciseByName("푸시업") ?: findExerciseByName("push up") ?: 1L
            listOf(
                ExerciseDetailV4(
                    id = pushUpId.toString(),
                    name = "푸시업",
                    targetMuscle = targetMuscles.firstOrNull() ?: "가슴",
                    sets = listOf(
                        SetDetail(1, 15, 0.0, "working"),
                        SetDetail(2, 12, 0.0, "working"),
                        SetDetail(3, 10, 0.0, "working")
                    ),
                    restTime = 60,
                    tips = "코어에 힘을 주고 일직선을 유지하세요"
                )
            )
        }
    }

    private fun buildReasonMessage(
        lastWorkoutDate: LocalDateTime?,
        readyMuscles: List<String>,
        weeklyDays: Int,
        split: String
    ): String {
        val daysSinceLastWorkout = lastWorkoutDate?.let {
            java.time.Duration.between(it, LocalDateTime.now()).toDays()
        } ?: 0

        return when {
            daysSinceLastWorkout == 0L -> "오늘 이미 운동하셨네요! 충분한 휴식을 취하세요."
            daysSinceLastWorkout >= 3 -> "마지막 운동으로부터 ${daysSinceLastWorkout}일이 경과했습니다. 오늘 운동하기 좋은 날입니다!"
            else -> "주 ${weeklyDays}일 ${translateSplit(split)} 프로그램에 따라 오늘은 ${readyMuscles.take(2).joinToString(", ")} 운동일입니다."
        }
    }

    private fun calculateNextWorkoutDay(
        targetDays: Int,
        completedDates: List<String>,
        preferredTime: String
    ): String? {
        val today = LocalDate.now()
        val daysCompleted = completedDates.size

        if (daysCompleted >= targetDays) {
            return null // Already met weekly goal
        }

        // Simple logic: suggest tomorrow if not worked out today
        val todayString = today.toString()
        return if (todayString in completedDates) {
            today.plusDays(1).toString()
        } else {
            todayString
        }
    }
}
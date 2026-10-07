package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.entity.UserLevel
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.UserProfile
import com.richjun.liftupai.domain.user.entity.UserSettings
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import com.richjun.liftupai.domain.workout.entity.PlanStatus
import com.richjun.liftupai.domain.workout.entity.SplitType
import com.richjun.liftupai.domain.workout.entity.UserPlanDay
import com.richjun.liftupai.domain.workout.entity.UserPlanDayExercise
import com.richjun.liftupai.domain.workout.entity.UserWorkoutPlan
import com.richjun.liftupai.domain.workout.entity.WorkoutType
import com.richjun.liftupai.domain.workout.repository.*
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.workout.service.vector.VectorWorkoutRecommendationService
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * 추천 경로 테스트가 공유하는 픽스처: 실제 카탈로그(1223개)·메모리 저장소 목(mock)·실제 서비스 조립.
 * 서비스는 목 저장소만 바꿔 끼운 진짜 코드다 — 규칙이 실제로 서비스 경로에서 작동하는지를 본다.
 */
object RecoTestKit {
    val mapper = ObjectMapper()
    val GYM = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT")

    val catalog: List<Exercise> by lazy { RecoFitEvalTest.loadExercises(mapper) }
    val bySlug: Map<String, Exercise> by lazy { catalog.associateBy { it.slug } }
    val byId: Map<Long, Exercise> by lazy { catalog.associateBy { it.id } }

    fun user(id: Long = 1L) = User(id = id, email = "t$id@t.t", nickname = "T", level = UserLevel.INTERMEDIATE)

    fun profile(
        user: User = user(),
        level: ExperienceLevel = ExperienceLevel.BEGINNER,
        age: Int? = 30,
        gender: String? = "male",
        weightKg: Double? = 72.0,
        injuries: Collection<String> = emptyList(),
        equipment: Collection<String> = emptyList(),
        durationMinutes: Int? = null,
    ) = UserProfile(user = user, bodyInfo = BodyInfo(weight = weightKg), experienceLevel = level, age = age, gender = gender).also {
        it.injuries = injuries.toMutableSet()
        it.availableEquipment = equipment.toMutableSet()
        it.workoutDuration = durationMinutes
    }

    fun settings(
        user: User = user(),
        language: String = "ko",
        durationMinutes: Int? = null,
        injuries: Collection<String> = emptyList(),
        equipment: Collection<String> = emptyList(),
        focusGoals: Collection<String> = emptyList(),
    ) = UserSettings(user = user, language = language).also {
        it.workoutDuration = durationMinutes
        it.injuries = injuries.toMutableSet()
        it.availableEquipment = equipment.toMutableSet()
        it.focusGoals = focusGoals.toMutableSet()
    }

    /** 사용자 저장소 목: 프로필·설정을 돌려준다 (null 이면 빈 Optional) */
    class Repos(val profile: UserProfile?, val settings: UserSettings?, userId: Long = 1L) {
        val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
        val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)

        init {
            every { userProfileRepository.findByUser_Id(userId) } returns Optional.ofNullable(profile)
            every { userProfileRepository.findByUser(any()) } returns Optional.ofNullable(profile)
            every { userSettingsRepository.findByUser_Id(userId) } returns Optional.ofNullable(settings)
        }

        val resolver = UserTrainingContextResolver(userSettingsRepository, userProfileRepository)
    }

    fun exerciseRepository(): ExerciseRepository {
        val repo = mockk<ExerciseRepository>(relaxed = true)
        every { repo.findById(any()) } answers { Optional.ofNullable(byId[firstArg<Long>()]) }
        every { repo.findAll() } returns catalog
        every { repo.findAllById(any<Iterable<Long>>()) } answers { firstArg<Iterable<Long>>().mapNotNull { byId[it] } }
        every { repo.findPlanEligibleByCategoryOrderByPopularity(any()) } answers {
            catalog.filter { it.isPlanEligible && it.category == firstArg() }.sortedWith(compareBy({ -it.popularity }, { it.id }))
        }
        every { repo.findByIsPlanEligibleTrueAndCategory(any()) } answers { catalog.filter { it.isPlanEligible && it.category == firstArg() } }
        every { repo.findAllByIsPlanEligibleTrue() } returns catalog.filter { it.isPlanEligible }
        return repo
    }

    fun weightService(repos: Repos, exerciseRepo: ExerciseRepository = exerciseRepository()): WeightRecommendationService {
        val pr = mockk<PersonalRecordRepository>(relaxed = true)
        every { pr.findTopByUserIdAndExerciseIdOrderByWeightDesc(any(), any()) } returns null
        every { pr.findTopByUserAndExerciseOrderByWeightDesc(any(), any()) } returns null
        return WeightRecommendationService(pr, exerciseRepo, repos.userProfileRepository, ExercisePatternClassifier(), mapper)
    }

    fun injuryFilter(): InjuryFilterService = InjuryFilterService(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    /** 활성 플랜 한 개(하루 N개 운동)와 그 위의 실제 UserPlanService·PlanWorkoutEnricher 를 조립한다. */
    class PlanFixture(
        val repos: Repos, rows: List<Triple<Exercise, Int, IntRange>>, val restSeconds: Int = 90,
        val localization: ExerciseCatalogLocalizationService? = null,
    ) {
        val user: User = repos.profile?.user ?: user()
        val plan = UserWorkoutPlan(
            id = 1L, user = user, sourceType = PlanSourceType.TEMPLATE, sourceId = "t", planName = "plan",
            splitType = SplitType.FULL_BODY, totalDays = 3, currentDay = 1, status = PlanStatus.ACTIVE,
        )
        val day = UserPlanDay(id = 1L, plan = plan, dayNumber = 1, dayName = "D1", workoutType = WorkoutType.FULL_BODY, estimatedDurationMinutes = 60)
        val planRows: List<UserPlanDayExercise> = rows.mapIndexed { i, (ex, sets, reps) ->
            UserPlanDayExercise(
                id = i + 1L, planDay = day, exercise = ex, orderInDay = i + 1, sets = sets, minReps = reps.first, maxReps = reps.last,
                restSeconds = restSeconds, isCompound = RecoRules.isCompound(ex), targetRPE = 7.0,
            )
        }
        val exerciseRepo = exerciseRepository()
        val weights = weightService(repos, exerciseRepo)
        val progressive: ProgramProgressiveOverloadService = mockk(relaxed = true)
        val planRepo: UserWorkoutPlanRepository = mockk(relaxed = true)
        val dayRepo: UserPlanDayRepository = mockk(relaxed = true)
        val dayExRepo: UserPlanDayExerciseRepository = mockk(relaxed = true)
        val sessionRepo: WorkoutSessionRepository = mockk(relaxed = true)
        val translationRepo: ExerciseTranslationRepository = mockk(relaxed = true)
        val catalogCache: ExerciseCatalogCache = mockk(relaxed = true)
        val injuryFilter = injuryFilter()

        init {
            every { planRepo.findFirstByUserIdAndStatusOrderByCreatedAtDesc(any(), PlanStatus.ACTIVE) } returns plan
            every { dayRepo.findByPlanIdAndDayNumber(plan.id, 1) } returns day
            every { dayRepo.findByPlanIdOrderByDayNumber(plan.id) } returns listOf(day)
            every { dayExRepo.save(any()) } answers { firstArg<UserPlanDayExercise>() }
            every { planRepo.save(any()) } answers { firstArg<UserWorkoutPlan>() }
            every { dayExRepo.findByPlanDayIdWithExerciseOrderByOrderInDay(day.id) } returns planRows
            every { dayExRepo.findByPlanDayIdOrderByOrderInDay(day.id) } returns planRows
            every { progressive.calculateWeightForPlan(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
            every { sessionRepo.findTop10ByUserAndStatusInOrderByStartTimeDesc(any(), any()) } returns emptyList()
            every { translationRepo.findByExerciseIdInAndLocale(any(), any()) } returns emptyList()
            every { catalogCache.get() } returns ExerciseCatalogCache.Snapshot(catalog, System.currentTimeMillis())
        }

        val enricher = PlanWorkoutEnricher(
            exerciseRepo, dayRepo, sessionRepo, mockk(relaxed = true), mockk(relaxed = true),
            ExerciseSubstitutionService(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), exerciseRepo, injuryFilter),
            ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
            repos.userProfileRepository, repos.userSettingsRepository, repos.resolver, catalogCache, injuryFilter, localization,
        )

        val service = UserPlanService(
            planRepo, dayRepo, dayExRepo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), injuryFilter,
            repos.userSettingsRepository, exerciseRepo, weights, progressive, enricher, repos.userProfileRepository, translationRepo,
            repos.resolver,
        )
    }

    /** 프로그램 등록 사용자의 오늘 운동을 만드는 실제 ProgramWorkoutGeneratorService. 프로그램 하루에 [slugs] 운동이 들어 있다. */
    class ProgramFixture(
        val repos: Repos, slugs: List<String>, val week: Int = 1, sets: Int = 4, reps: IntRange = 6..8, restSeconds: Int = 180,
        localization: ExerciseCatalogLocalizationService? = null, catalogCache: ExerciseCatalogCache? = null,
    ) {
        val user: User = repos.profile?.user ?: user()
        val program = com.richjun.liftupai.domain.workout.entity.CanonicalProgram(
            id = 1L, code = "p", name = "program", splitType = SplitType.FULL_BODY,
            targetExperienceLevel = ExperienceLevel.BEGINNER, targetGoal = com.richjun.liftupai.domain.workout.entity.WorkoutGoal.GENERAL_FITNESS,
            daysPerWeek = 3, progressionModel = com.richjun.liftupai.domain.workout.entity.ProgressionModel.LINEAR,
        )
        val programDay = com.richjun.liftupai.domain.workout.entity.ProgramDay(
            id = 1L, program = program, dayNumber = 1, workoutType = WorkoutType.FULL_BODY, name = "Day 1",
        )
        val dayExercises = slugs.mapIndexed { i, slug ->
            com.richjun.liftupai.domain.workout.entity.ProgramDayExercise(
                id = i + 1L, programDay = programDay, exercise = bySlug.getValue(slug), orderInDay = i + 1,
                isCompound = RecoRules.isCompound(bySlug.getValue(slug)), sets = sets, minReps = reps.first, maxReps = reps.last,
                restSeconds = restSeconds, targetRPE = 7.0,
            )
        }
        val enrollment = com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment(
            id = 1L, user = user, program = program, programVersion = 1, startDate = java.time.LocalDateTime.now().minusDays(1),
            totalCompletedWorkouts = (week - 1) * 3,
        )
        val exerciseRepo = exerciseRepository()
        private val enrollmentService: ProgramEnrollmentService = mockk(relaxed = true)
        private val dayRepo: ProgramDayRepository = mockk(relaxed = true)
        private val dayExRepo: ProgramDayExerciseRepository = mockk(relaxed = true)
        private val overrideRepo: UserExerciseOverrideRepository = mockk(relaxed = true)
        private val graduation: ProgramGraduationService = mockk(relaxed = true)
        private val progressive: ProgramProgressiveOverloadService = mockk(relaxed = true)

        init {
            every { progressive.calculateWeight(any(), any(), any(), any(), any()) } returns null
            every { enrollmentService.getCurrentEnrollment(any()) } returns enrollment
            every { enrollmentService.getCurrentPosition(any()) } returns ProgramPosition(week, 1, false, false)
            every { dayRepo.findByProgramIdOrderByDayNumber(program.id) } returns listOf(programDay)
            every { dayExRepo.findByDayIdWithExercises(programDay.id) } returns dayExercises
            every { overrideRepo.findByEnrollmentId(any()) } returns emptyList()
            every { graduation.checkGraduation(any(), any()) } returns ProgramGraduationService.GraduationStatus(false, 0.0, 1, 8, null, null, "")
        }

        val service = ProgramWorkoutGeneratorService(
            enrollmentService, dayRepo, dayExRepo, overrideRepo, progressive,
            mockk(relaxed = true), graduation, repos.userProfileRepository, repos.userSettingsRepository,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), exerciseRepo, mockk(relaxed = true),
            weightService(repos, exerciseRepo), repos.resolver, ExerciseTrainingProfileResolver(ExercisePatternClassifier()), injuryFilter(), localization, catalogCache,
        )
    }
}

/** 템플릿 적용(applyTemplate)을 실제 서비스로 돌려 저장되는 운동 행을 모은다 */
class ApplyFixture(val repos: RecoTestKit.Repos, val templateCode: String = "beginner_fullbody") {
    val user: User = repos.profile?.user ?: RecoTestKit.user()
    val saved = mutableListOf<UserPlanDayExercise>()
    val savedDays = mutableListOf<UserPlanDay>()
    private val template = PresetTemplates.ALL.first { it.code == templateCode }.let { t ->
        com.richjun.liftupai.domain.workout.entity.WorkoutPlanTemplate(
            id = 1L, code = t.code, name = t.name, description = t.description, targetGoal = t.targetGoal,
            targetExperience = t.targetExperience, splitType = t.splitType, totalDays = t.totalDays,
        )
    }
    private val templateDays = PresetTemplates.ALL.first { it.code == templateCode }.days.map { d ->
        com.richjun.liftupai.domain.workout.entity.TemplateDay(
            id = d.dayNumber.toLong(), template = template, dayNumber = d.dayNumber, dayName = d.dayName,
            workoutType = d.workoutType, estimatedDurationMinutes = d.durationMinutes,
        )
    }
    private val planRepo: UserWorkoutPlanRepository = mockk(relaxed = true)
    private val dayRepo: UserPlanDayRepository = mockk(relaxed = true)
    private val dayExRepo: UserPlanDayExerciseRepository = mockk(relaxed = true)
    private val templateRepo: WorkoutPlanTemplateRepository = mockk(relaxed = true)
    private val templateDayRepo: TemplateDayRepository = mockk(relaxed = true)
    private val templateDayExRepo: TemplateDayExerciseRepository = mockk(relaxed = true)
    private var plan: UserWorkoutPlan? = null

    init {
        every { templateRepo.findByCode(templateCode) } returns template
        every { templateDayRepo.findByTemplateIdOrderByDayNumber(template.id) } returns templateDays
        PresetTemplates.ALL.first { it.code == templateCode }.days.forEach { d ->
            val td = templateDays.first { it.dayNumber == d.dayNumber }
            every { templateDayExRepo.findByTemplateDayIdOrderByOrderInDay(td.id) } returns d.exercises.mapIndexedNotNull { i, e ->
                RecoTestKit.bySlug[e.slug]?.let { ex ->
                    com.richjun.liftupai.domain.workout.entity.TemplateDayExercise(
                        id = td.id * 100 + i, templateDay = td, exercise = ex, orderInDay = i + 1, sets = e.sets, minReps = e.minReps,
                        maxReps = e.maxReps, restSeconds = e.restSeconds, isCompound = e.isCompound, targetRPE = e.targetRPE, notes = e.notes,
                    )
                }
            }
        }
        every { planRepo.save(any()) } answers { firstArg<UserWorkoutPlan>().also { plan = it } }
        every { planRepo.findFirstByUserIdAndStatusOrderByCreatedAtDesc(any(), PlanStatus.ACTIVE) } answers { plan }
        every { dayRepo.save(any()) } answers { firstArg<UserPlanDay>().also { savedDays += it } }
        every { dayRepo.findByPlanIdOrderByDayNumber(any()) } answers { savedDays.toList() }
        every { dayExRepo.save(any()) } answers { firstArg<UserPlanDayExercise>().also { saved += it } }
        every { dayExRepo.findByPlanDayIdWithExerciseOrderByOrderInDay(any()) } answers { saved.filter { it.planDay.dayNumber == savedDays.firstOrNull { d -> d.id == firstArg<Long>() }?.dayNumber } }
    }

    val exerciseRepo = RecoTestKit.exerciseRepository()
    val service = UserPlanService(
        planRepo, dayRepo, dayExRepo, templateRepo, templateDayRepo, templateDayExRepo, RecoTestKit.injuryFilter(),
        repos.userSettingsRepository, exerciseRepo, RecoTestKit.weightService(repos, exerciseRepo), mockk(relaxed = true), mockk(relaxed = true),
        repos.userProfileRepository, mockk(relaxed = true), repos.resolver,
    )

    fun apply(request: com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest? = null, locale: String = "ko") {
        service.applyTemplate(user, templateCode, request, locale)
    }
}

/** 채팅(AIAnalysisService)·퀵(V2) 추천을 실제 서비스로 돌리는 조립. 실제 서비스(AIAnalysisService·V2·PT 엔진)를 목 저장소 위에 조립한다. Gemini 는 후보 중 몇 개를 고른 응답을 돌려준다. */
class ChatHarness(
    level: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    savedMinutes: Int? = 60,
    profileMinutes: Int? = null,
    language: String = "ko",
    age: Int? = 30,
    gender: String = "male",
    equipment: List<String> = RecoTestKit.GYM,
    profileInjuries: List<String> = emptyList(),
    settingsInjuries: List<String> = emptyList(),
    focusGoals: List<String> = emptyList(),
    picks: List<String> = listOf(
        "barbell-full-squat", "barbell-bench-press", "barbell-bent-over-row-pronated-grip",
        "seated-shoulder-press-barbell", "dumbbell-curls",
    ),
) {
    val user = RecoTestKit.user()
    val profile = RecoTestKit.profile(user, level, age, gender, 72.0, injuries = profileInjuries, equipment = equipment, durationMinutes = profileMinutes)
    val settings = RecoTestKit.settings(user, language, savedMinutes, injuries = settingsInjuries, equipment = equipment, focusGoals = focusGoals)
    val repos = RecoTestKit.Repos(profile, settings)
    val exerciseRepo = RecoTestKit.exerciseRepository()
    val weights = RecoTestKit.weightService(repos, exerciseRepo)
    val injuryFilter = RecoTestKit.injuryFilter()
    private val prRepo = mockk<PersonalRecordRepository>(relaxed = true).also {
        every { it.findTopByUserAndExerciseOrderByWeightDesc(any(), any()) } returns null
    }
    private val enrollmentRepo = mockk<UserProgramEnrollmentRepository>(relaxed = true).also {
        every { it.findFirstByUserAndStatusOrderByStartDateDesc(any(), EnrollmentStatus.ACTIVE) } returns null
    }
    private val localization = ExerciseCatalogLocalizationService(mockk(relaxed = true))
    val pt = PTRecommendationEngine(
        repos.userProfileRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), prRepo, enrollmentRepo,
        ExercisePatternClassifier(), ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
        WorkoutV2SharedSupport(repos.userSettingsRepository, localization, mockk(relaxed = true)), weights,
    )
    private val tracker = mockk<WorkoutProgressTracker>(relaxed = true).also {
        every { it.hasStartedWorkoutToday(any()) } returns false
        every { it.getNextWorkoutInProgram(any(), any()) } returns WorkoutProgramPosition(day = 1, cycle = 1, isNewCycle = false)
        every { it.getWorkoutTypeSequence(any()) } returns listOf(WorkoutType.FULL_BODY)
    }
    private val vector = mockk<VectorWorkoutRecommendationService>().also { v ->
        val candidates = RecoTestKit.catalog.filter { it.isPlanEligible }
            .sortedWith(compareBy({ -it.popularity }, { it.id })).take(60)
        every { v.recommendExercises(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { candidates.take(arg<Int>(8).coerceAtLeast(20)) }
    }
    val geminiMock: GeminiAIService get() = gemini
    private val gemini = mockk<GeminiAIService>().also { g ->
        val ids = picks.map { RecoTestKit.bySlug.getValue(it).id }
        val json = """{"workout_name":"x","difficulty":"intermediate","estimated_calories":300,"target_muscles":[],"equipment":[],
            "exercises":[${ids.mapIndexed { i, id -> """{"id":$id,"sets":4,"reps":"8-10","rest_seconds":90,"order":${i + 1}}""" }.joinToString(",")}]}"""
        every { g.generateRecommendations(any()) } returns json
    }
    private val userRepository = mockk<UserRepository>().also { every { it.findById(1L) } returns Optional.of(user) }

    val chat = AIAnalysisService(
        userRepository, repos.userProfileRepository, repos.userSettingsRepository, exerciseRepo, gemini,
        ObjectMapper().registerKotlinModule(), tracker, pt,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), vector,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        localization, injuryFilter, enrollmentRepo, mockk(relaxed = true), mockk(relaxed = true),
    )

    private val sessionRepo = mockk<com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository>(relaxed = true).also {
        every { it.findFirstByUserAndStatusOrderByStartTimeDesc(any(), any()) } returns Optional.empty()
    }

    val quick = WorkoutRecommendationServiceV2(
        userRepository, repos.userProfileRepository, repos.userSettingsRepository, sessionRepo, tracker,
        mockk<ExerciseRecommendationService>(relaxed = true).also { every { it.getRecommendedExercises(any(), any(), any(), any(), any()) } returns emptyList() },
        mockk(relaxed = true), vector,
        WorkoutV2SharedSupport(repos.userSettingsRepository, localization, mockk(relaxed = true)), pt, injuryFilter, mockk(relaxed = true), exerciseRepo,
    )
}


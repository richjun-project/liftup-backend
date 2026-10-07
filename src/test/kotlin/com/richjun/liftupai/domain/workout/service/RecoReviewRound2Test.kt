package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.favorite.ExerciseFavorite
import com.richjun.liftupai.domain.workout.favorite.ExerciseFavoriteRepository
import com.richjun.liftupai.domain.workout.favorite.ExerciseFavoriteService
import com.richjun.liftupai.domain.workout.service.new.RecoRules
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.ratelimit.RequestSizeInterceptor
import com.richjun.liftupai.global.ratelimit.RequestSizeLimitFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.File

/**
 * 2차 리뷰(리뷰어 + 보이지 않는 홀드아웃) 지적의 회귀 테스트: 카탈로그 분류, 초보 첫 세션 무게, 프로그램 생성 N+1,
 * 다양화 처방, 즐겨찾기 FK, 청크 본문 상한.
 */
class RecoReviewRound2Test {

    // ── 카탈로그 분류 (데이터 규칙 — 슬러그를 하나씩 지목하지 않고 부류로 막는다) ─────────────────────

    private val catalog: List<Map<String, Any?>> by lazy {
        val file = File("src/main/resources/catalog/exercise-catalog.json")
        @Suppress("UNCHECKED_CAST")
        ObjectMapper().readValue(file, List::class.java) as List<Map<String, Any?>>
    }

    private fun name(e: Map<String, Any?>, locale: String = "en"): String =
        ((e["translations"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["locale"] == locale }?.get("name") as? String).orEmpty()

    private fun muscles(e: Map<String, Any?>): List<String> = (e["muscleGroups"] as List<*>).map { it.toString() }

    @Test
    @DisplayName("리어델트 플라이는 가슴이 아니라 어깨다 — 어깨·등이 주동근인 FLY/ROTATION 이 CHEST/CORE 가 아니다")
    fun rearDeltFlysAreNotChest() {
        val bySlug = catalog.associateBy { it["slug"] as String }
        listOf("reverse-cable-fly-on-crossover", "one-arm-reverse-dumbbell-fly", "dumbbell-leaning-fly", "dumbbell-side-plank-with-rear-fly")
            .forEach { assertEquals("SHOULDERS", bySlug.getValue(it)["category"], it) }
        // 부류 규칙: 가슴 카테고리인데 가슴 근육이 없는 운동이 없다(딥은 삼두 주동이라 가슴을 포함해야 한다)
        val badChest = catalog.filter { it["category"] == "CHEST" && "CHEST" !in muscles(it) && "TRICEPS" !in muscles(it) }
        assertTrue(badChest.isEmpty(), "CHEST 인데 가슴 근육이 없다: " + badChest.map { it["slug"] })
        val rearFlyAsChest = catalog.filter { it["category"] == "CHEST" && it["movementPattern"] == "FLY" && muscles(it).firstOrNull() == "SHOULDERS" }
        assertTrue(rearFlyAsChest.isEmpty(), "리어델트 플라이가 CHEST: " + rearFlyAsChest.map { it["slug"] })
    }

    @Test
    @DisplayName("코어 카테고리는 복근·코어를 쓴다 — 회전근개·다리 운동이 CORE 로 분류되지 않는다")
    fun coreCategoryUsesCoreMuscles() {
        val bad = catalog.filter { it["category"] == "CORE" && muscles(it).none { m -> m == "ABS" || m == "CORE" } }
        assertTrue(bad.isEmpty(), "CORE 인데 복근·코어 근육이 없다: " + bad.map { it["slug"] })
    }

    @Test
    @DisplayName("장비 태그는 이름과 맞다 — cable 은 CABLE, band 는 RESISTANCE_BAND (머신 표기는 허용)")
    fun equipmentMatchesName() {
        val bad = catalog.filter { e ->
            val n = name(e).lowercase()
            val eq = e["equipment"] as String
            (Regex("""\bcable\b""").containsMatchIn(n) && !n.contains("band") && eq != "CABLE" && eq != "MACHINE") ||
                (Regex("""\bresistance band\b|\bband\b""").containsMatchIn(n) && !n.contains("it band") && !n.contains("cable") && eq != "RESISTANCE_BAND" && eq != "BODYWEIGHT")
        }
        assertTrue(bad.isEmpty(), "이름과 장비가 다르다: " + bad.map { "${it["slug"]}=${it["equipment"]}" })
        val bySlug = catalog.associateBy { it["slug"] as String }
        assertEquals("CABLE", bySlug.getValue("reverse-cable-fly-on-crossover")["equipment"])
        assertEquals("CABLE", bySlug.getValue("bicep-curl-low-cable-machine-normal-grip")["equipment"])
    }

    @Test
    @DisplayName("서로 다른 운동은 같은 영어 설명을 쓰지 않는다 (머슬 클린 ≠ 파워 클린, 노멀 너비 ≠ 클로즈 그립)")
    fun noDuplicateInstructions() {
        val seen = HashMap<String, String>()
        val dups = mutableListOf<String>()
        for (e in catalog) {
            val ins = ((e["translations"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["locale"] == "en" }?.get("instructions") as? String)?.trim().orEmpty()
            if (ins.isEmpty()) continue
            seen.putIfAbsent(ins, e["slug"] as String)?.let { dups += "$it / ${e["slug"]}" }
        }
        assertTrue(dups.isEmpty(), "같은 설명: $dups")
    }

    // ── 초보 첫 세션 무게 ─────────────────────────────────────────────────────────────

    private fun ptFixture(level: ExperienceLevel, age: Int, oneRm: Double, slug: String, goals: Set<FitnessGoal>): Pair<PTRecommendationEngine, RecoTestKit.PlanFixture> {
        val user = RecoTestKit.user()
        val profile = RecoTestKit.profile(user, level, age, "male", 75.0)
        profile.goals = goals.toMutableSet()
        profile.estimatedMaxes = """{"$slug": $oneRm}"""
        val repos = RecoTestKit.Repos(profile, RecoTestKit.settings(user))
        val ex = RecoTestKit.bySlug.getValue(slug)
        val plan = RecoTestKit.PlanFixture(repos, listOf(Triple(ex, 3, 8..12)))
        val pt = PTRecommendationEngine(
            repos.userProfileRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk<com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository>(relaxed = true).also {
                every { it.findTopByUserAndExerciseOrderByWeightDesc(any(), any()) } returns null
            },
            mockk<com.richjun.liftupai.domain.workout.repository.UserProgramEnrollmentRepository>(relaxed = true).also {
                every { it.findFirstByUserAndStatusOrderByStartDateDesc(any(), EnrollmentStatus.ACTIVE) } returns null
            },
            ExercisePatternClassifier(), ExerciseTrainingProfileResolver(ExercisePatternClassifier()),
            WorkoutV2SharedSupport(repos.userSettingsRepository, mockk(relaxed = true), mockk(relaxed = true)),
            plan.weights,
        )
        return pt to plan
    }

    private fun upperReps(reps: String): Int = reps.split("-").last().trim().toInt()

    @Test
    @DisplayName("초보 첫 세션: 올라간 반복수(8-10)에 맞춰 무게가 1RM 의 그 반복수 작업 무게 이하다 (예전엔 6RM 하중으로 8-10회)")
    fun beginnerFirstSessionWeightMatchesRaisedReps() {
        val slug = "barbell-bench-press"
        val oneRm = 100.0
        val (pt, plan) = ptFixture(ExperienceLevel.BEGINNER, 30, oneRm, slug, setOf(FitnessGoal.STRENGTH))
        val rec = pt.calculateFullPTRecommendation(plan.user, RecoTestKit.bySlug.getValue(slug))
        val hi = upperReps(rec.reps)
        assertTrue(rec.reps.split("-").first().trim().toInt() >= 8, "초보 복합은 8회 이상: ${rec.reps}")
        val ceiling = oneRm * (37.0 - hi) / 36.0
        assertTrue(rec.weight <= ceiling + 0.01, "무게 ${rec.weight}kg 이 ${rec.reps}회의 작업 무게 ${"%.1f".format(ceiling)}kg 를 넘는다")
        assertTrue(rec.weight > 0.0)
    }

    @Test
    @DisplayName("첫 세션 무게에도 미성년·고령 강도 상한(%1RM)이 적용된다")
    fun firstSessionWeightHonoursAgeIntensityCap() {
        val slug = "barbell-bench-press"
        val oneRm = 120.0
        listOf(15, 70).forEach { age ->
            val cap = com.richjun.liftupai.domain.workout.service.RecommendationConstants.getAgeIntensityCap(age)
            assertTrue(cap < 1.0, "나이 $age 에 상한이 있어야 이 테스트가 의미 있다")
            val (pt, plan) = ptFixture(ExperienceLevel.BEGINNER, age, oneRm, slug, setOf(FitnessGoal.STRENGTH))
            val rec = pt.calculateFullPTRecommendation(plan.user, RecoTestKit.bySlug.getValue(slug))
            // 바 무게(20kg) 하한이 상한보다 클 수 있을 만큼 작은 경우는 제외하고, 상한을 2.5kg 단위 올림 한 칸까지 허용
            assertTrue(rec.weight <= maxOf(oneRm * cap + 2.5, RecoRules.BAR_WEIGHT), "나이 $age: ${rec.weight}kg > ${oneRm * cap}kg (상한 ${cap})")
        }
    }

    // ── 프로그램 생성 N+1 ────────────────────────────────────────────────────────────

    private val kneeProgram = listOf(
        "barbell-full-squat", "barbell-lunge", "leg-press-machine-normal-stance", "barbell-bench-press",
        "barbell-bent-over-row-pronated-grip", "barbell-deadlift", "dumbbell-curls",
    )

    private fun kneeRepos() = RecoTestKit.Repos(
        RecoTestKit.profile(level = ExperienceLevel.INTERMEDIATE, injuries = listOf("knee")),
        RecoTestKit.settings(durationMinutes = 60, language = "ja"),
    )

    @Test
    @DisplayName("부상 안전 대체가 여러 번 일어나도 카테고리 후보 풀은 카테고리당 한 번만 읽는다")
    fun safeReplacementPoolReadOncePerCategory() {
        val f = RecoTestKit.ProgramFixture(kneeRepos(), kneeProgram)
        val calls = HashMap<ExerciseCategory, Int>()
        every { f.exerciseRepo.findPlanEligibleByCategoryOrderByPopularity(any()) } answers {
            val c = firstArg<ExerciseCategory>()
            calls.merge(c, 1, Int::plus)
            RecoTestKit.catalog.filter { it.isPlanEligible && it.category == c }.sortedWith(compareBy({ -it.popularity }, { it.id }))
        }
        val w = f.service.generateTodayWorkout(f.user)
        assertTrue(w.exercises.isNotEmpty())
        assertTrue(calls.isNotEmpty(), "이 시나리오는 안전 대체가 일어나야 의미가 있다")
        assertTrue(calls.all { it.value == 1 }, "카테고리 풀을 여러 번 읽었다: $calls")
    }

    @Test
    @DisplayName("카탈로그 캐시가 있으면 대체 후보 풀을 DB 에서 읽지 않는다")
    fun safeReplacementUsesCatalogCache() {
        val repo = RecoTestKit.exerciseRepository()
        every { repo.findAllWithMuscleGroups() } returns RecoTestKit.catalog
        val cache = ExerciseCatalogCache(repo)
        val f = RecoTestKit.ProgramFixture(kneeRepos(), kneeProgram, catalogCache = cache)
        // 픽스처의 서비스는 자기 exerciseRepo 를 쓴다 — 거기서도 후보 풀 조회가 0 이어야 한다
        f.service.generateTodayWorkout(f.user)
        ExerciseCategory.values().forEach { c ->
            verify(exactly = 0) { f.exerciseRepo.findPlanEligibleByCategoryOrderByPopularity(c) }
        }
    }

    @Test
    @DisplayName("이름 현지화(ja)는 운동마다가 아니라 하루에 한 번 묶어서 조회한다")
    fun translationsBatchedOncePerWorkout() {
        val loc = mockk<ExerciseCatalogLocalizationService>()
        every { loc.translationMap(any(), any()) } returns emptyMap()
        every { loc.displayName(any(), any(), any()) } answers { "JA:" + firstArg<Exercise>().name }
        val f = RecoTestKit.ProgramFixture(kneeRepos(), kneeProgram, localization = loc)
        val w = f.service.generateTodayWorkout(f.user)
        verify(exactly = 1) { loc.translationMap(any(), "ja") }
        assertTrue(w.exercises.all { it.name.startsWith("JA:") }, "이름이 현지화되어야 한다: ${w.exercises.map { it.name }}")
        assertTrue(w.exercises.flatMap { it.substitutes }.all { it.name.startsWith("JA:") })
    }

    // ── 다양화 처방 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다양화로 복합→코어로 바뀐 자리는 복합 처방(3x8-12)이 아니라 새 종목 성격의 처방을 받는다")
    fun diversifyRederivesPrescriptionWhenKindChanges() {
        var id = 1L
        fun ex(slug: String, cat: ExerciseCategory, pattern: String, pop: Int, m: Set<MuscleGroup>) = Exercise(
            id = id++, slug = slug, name = slug, category = cat, equipment = Equipment.BODYWEIGHT, movementPattern = pattern,
            muscleGroups = m.toMutableSet(), popularity = pop, difficulty = 20, isPlanEligible = true,
        )
        val pushup = ex("pushup", ExerciseCategory.CHEST, "PUSHUP", 90, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS))
        val plank = ex("plank", ExerciseCategory.CORE, "PLANK", 70, setOf(MuscleGroup.CORE, MuscleGroup.ABS))
        val all = listOf(pushup, plank)
        val adapter = TemplatePlanAdapter({ c -> all.filter { it.category == c } }, { false })
        fun src(e: Exercise) = TemplatePlanAdapter.SourceExercise(e, 3, 8, 12, 90, RecoRules.isCompound(e), 7.0)
        val days = (1..3).map { TemplatePlanAdapter.SourceDay(it, "CHEST", listOf(src(pushup))) }
        val out = adapter.adapt(days, TemplatePlanAdapter.Context(allowedEquipment = setOf("BODYWEIGHT"), repeatCap = 1))
        val replaced = out.flatMap { it.exercises }.filter { it.exercise.slug == "plank" && it.origin == TemplatePlanAdapter.Origin.REFILL }
        assertTrue(replaced.isNotEmpty(), "푸시업이 반복돼 플랭크로 바뀌어야 한다: ${out.map { d -> d.exercises.map { it.exercise.slug } }}")
        replaced.forEach {
            assertFalse(it.source.isCompound, "코어 자리는 복합 표시가 아니다")
            assertTrue(it.source.minReps >= 12, "코어 처방은 높은 반복 (8-12 를 물려받지 않는다): ${it.source.minReps}-${it.source.maxReps}")
        }
    }

    // ── 즐겨찾기 FK ─────────────────────────────────────────────────────────────────

    private fun favoriteService(repo: ExerciseFavoriteRepository, exerciseRepo: com.richjun.liftupai.domain.workout.repository.ExerciseRepository): ExerciseFavoriteService {
        val cache = mockk<ExerciseCatalogCache>()
        val stale = Exercise(id = 77L, slug = "gone", name = "gone", category = ExerciseCategory.CORE)
        every { cache.get() } returns ExerciseCatalogCache.Snapshot(listOf(stale), System.currentTimeMillis())
        return ExerciseFavoriteService(repo, mockk(relaxed = true), exerciseRepo, cache, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    @DisplayName("캐시에는 있지만 DB 에서 사라진 운동(FK 위반)은 성공으로 위장하지 않고 404")
    fun favoriteOfDeletedExerciseIsNotReportedAsSuccess() {
        val repo = mockk<ExerciseFavoriteRepository>()
        every { repo.existsByUserIdAndExerciseId(1L, 77L) } returns false
        every { repo.countByUserId(1L) } returns 0L
        every { repo.save(any<ExerciseFavorite>()) } throws DataIntegrityViolationException("fk_exercise_favorites_exercise")
        val service = favoriteService(repo, mockk(relaxed = true))
        assertThrows(ResourceNotFoundException::class.java) { service.add(1L, 77L) }
    }

    @Test
    @DisplayName("동시에 같은 즐겨찾기가 먼저 들어간 경우(행이 있음)는 그대로 true")
    fun concurrentDuplicateStillSucceeds() {
        val repo = mockk<ExerciseFavoriteRepository>()
        every { repo.existsByUserIdAndExerciseId(1L, 77L) } returnsMany listOf(false, true)
        every { repo.countByUserId(1L) } returns 0L
        every { repo.save(any<ExerciseFavorite>()) } throws DataIntegrityViolationException("uk_user_exercise")
        assertTrue(favoriteService(repo, mockk(relaxed = true)).add(1L, 77L))
    }

    // ── 청크 본문 상한 ───────────────────────────────────────────────────────────────

    private fun chunkedRequest(bytes: ByteArray): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/v2/templates/recommend").apply {
            setContent(bytes)
            // 청크 전송: Content-Length 가 없다
            removeHeader("Content-Length")
            addHeader("Transfer-Encoding", "chunked")
        }

    @Test
    @DisplayName("Content-Length 없는 청크 본문도 32KB 를 넘으면 파싱 전에 400")
    fun chunkedOversizeBodyRejected() {
        val filter = RequestSizeLimitFilter(ObjectMapper())
        val request = chunkedRequest(ByteArray(RequestSizeInterceptor.MAX_BODY_BYTES.toInt() + 10) { 'a'.code.toByte() })
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        // MockHttpServletRequest 는 setContent 시 길이를 채우므로 -1 을 강제한다
        val unknownLength = object : jakarta.servlet.http.HttpServletRequestWrapper(request) {
            override fun getContentLengthLong(): Long = -1L
            override fun getContentLength(): Int = -1
        }
        filter.doFilter(unknownLength, response, chain)
        assertEquals(400, response.status)
        assertTrue(chain.request == null, "본문이 너무 크면 컨트롤러까지 가지 않는다")
    }

    @Test
    @DisplayName("상한 안의 청크 본문은 그대로 다음 단계로 전달된다")
    fun chunkedSmallBodyPassesThrough() {
        val filter = RequestSizeLimitFilter(ObjectMapper())
        val payload = """{"goal":"strength"}""".toByteArray()
        val request = chunkedRequest(payload)
        val unknownLength = object : jakarta.servlet.http.HttpServletRequestWrapper(request) {
            override fun getContentLengthLong(): Long = -1L
            override fun getContentLength(): Int = -1
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(unknownLength, response, chain)
        assertEquals(200, response.status)
        val passed = chain.request as jakarta.servlet.http.HttpServletRequest
        assertEquals(String(payload), String(passed.inputStream.readAllBytes()))
    }

    // ── 카탈로그 동기화: 한 건이 나머지를 막지 않는다 ───────────────────────────────────

    @Test
    @DisplayName("새 slug 배치 삽입이 실패해도 행마다 다시 시도해 나머지는 들어가고, 이후 단계(큐레이션·번역)가 이어진다")
    fun syncInsertIsolatedPerRecord() {
        val repo = mockk<com.richjun.liftupai.domain.workout.repository.ExerciseRepository>(relaxed = true)
        every { repo.findAll() } returns emptyList()
        every { repo.saveAllAndFlush(any<Iterable<Exercise>>()) } throws DataIntegrityViolationException("uk_exercises_slug")
        every { repo.saveAndFlush(any<Exercise>()) } answers {
            val e = firstArg<Exercise>()
            if (e.slug == "bad-one") throw DataIntegrityViolationException("uk_exercises_slug") else e
        }
        val jdbc = mockk<org.springframework.jdbc.core.JdbcTemplate>(relaxed = true)
        every { jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<Any>>()) } returns emptyList<Any>()
        val svc = ExerciseCatalogBootstrapService(
            ObjectMapper(), repo, mockk(relaxed = true), jdbc, mockk(relaxed = true), "if_empty", "catalog/exercise-catalog.json", false,
        )
        fun rec(slug: String) = ExerciseCatalogRecord(
            slug = slug, category = ExerciseCategory.CORE, equipment = Equipment.BODYWEIGHT, muscleGroups = setOf(MuscleGroup.ABS),
            translations = listOf(ExerciseCatalogTranslation("en", slug)),
        )
        val result = svc.syncCatalog(listOf(rec("good-one"), rec("bad-one"), rec("good-two")))
        assertEquals(2, result.exercisesCreated, "한 건이 실패해도 나머지 두 건은 들어간다")
    }

    // ── 대시보드 추정 시간 = 하루 상세와 같은 규칙 ───────────────────────────────────────

    @Test
    @DisplayName("대시보드 분은 디로드 주간에 세트를 다시 늘리지 않고, 세션 시간이 없으면 저장값을 쓴다")
    fun dashboardMinutesFollowDayDetailRules() {
        val user = RecoTestKit.user()
        val repos = RecoTestKit.Repos(RecoTestKit.profile(user, ExperienceLevel.INTERMEDIATE, 30), RecoTestKit.settings(user, durationMinutes = 75))
        val squat = RecoTestKit.bySlug.getValue("barbell-full-squat")
        val fixture = RecoTestKit.PlanFixture(repos, listOf(Triple(squat, 3, 8..10)))
        val ctx = repos.resolver.resolve(user.id)
        val rows = listOf(
            com.richjun.liftupai.domain.workout.entity.UserPlanDayExercise(
                id = 1L, planDay = mockk(relaxed = true), exercise = squat, orderInDay = 1, sets = 2, minReps = 8, maxReps = 10,
                restSeconds = 60, isCompound = true, targetRPE = 7.0,
            )
        )
        val normal = fixture.service.personalizedMinutes(rows, ctx, deloadWeek = false)!!
        val deload = fixture.service.personalizedMinutes(rows, ctx, deloadWeek = true)!!
        assertTrue(deload <= normal, "디로드 주간은 시간 맞춤이 세트를 늘리지 않는다: deload=$deload normal=$normal")
        val noTarget = RecoTestKit.Repos(RecoTestKit.profile(user, ExperienceLevel.INTERMEDIATE, 30), RecoTestKit.settings(user)).resolver.resolve(user.id)
        assertEquals(null, fixture.service.personalizedMinutes(rows, noTarget), "세션 시간이 없으면 저장값(null 반환)")
    }
}

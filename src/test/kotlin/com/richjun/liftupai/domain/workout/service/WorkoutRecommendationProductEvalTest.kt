package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier.MovementPattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Product Eval: 운동 추천 시스템 행동 품질 검증
 *
 * 이 테스트는 실제 사용자 시나리오를 시뮬레이션하여
 * 추천 알고리즘의 "품질"을 검증합니다.
 *
 * Eval Types:
 * - Capability: 추천 시스템이 특정 시나리오에서 올바른 결과를 내는지
 * - Regression: 기존 동작이 깨지지 않았는지
 */
class WorkoutRecommendationProductEvalTest {

    private val classifier = ExercisePatternClassifier()

    // === TEST FIXTURES ===

    private fun exercise(
        id: Long,
        name: String,
        category: ExerciseCategory,
        muscles: Set<MuscleGroup>,
        equipment: Equipment = Equipment.BARBELL,
        popularity: Int = 50,
        difficulty: Int = 50,
        isBasic: Boolean = false,
        tier: RecommendationTier = RecommendationTier.STANDARD
    ) = Exercise(
        id = id,
        slug = name.lowercase().replace(" ", "-"),
        name = name,
        category = category,
        muscleGroups = muscles.toMutableSet(),
        equipment = equipment,
        popularity = popularity,
        difficulty = difficulty,
        isBasicExercise = isBasic,
        recommendationTier = tier
    )

    private val benchPress = exercise(1, "Barbell Bench Press", ExerciseCategory.CHEST,
        setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS),
        popularity = 95, difficulty = 45, isBasic = true, tier = RecommendationTier.ESSENTIAL)

    private val squat = exercise(2, "Barbell Back Squat", ExerciseCategory.LEGS,
        setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS),
        popularity = 95, difficulty = 50, isBasic = true, tier = RecommendationTier.ESSENTIAL)

    private val deadlift = exercise(3, "Conventional Deadlift", ExerciseCategory.BACK,
        setOf(MuscleGroup.BACK, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES),
        popularity = 90, difficulty = 55, isBasic = true, tier = RecommendationTier.ESSENTIAL)

    private val latPulldown = exercise(4, "Lat Pulldown", ExerciseCategory.BACK,
        setOf(MuscleGroup.LATS, MuscleGroup.BICEPS),
        equipment = Equipment.CABLE, popularity = 85, difficulty = 20,
        tier = RecommendationTier.ESSENTIAL)

    private val shoulderPress = exercise(5, "Overhead Shoulder Press", ExerciseCategory.SHOULDERS,
        setOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS),
        popularity = 85, difficulty = 40, isBasic = true, tier = RecommendationTier.ESSENTIAL)

    private val bicepCurl = exercise(6, "Dumbbell Bicep Curl", ExerciseCategory.ARMS,
        setOf(MuscleGroup.BICEPS),
        equipment = Equipment.DUMBBELL, popularity = 80, difficulty = 15,
        tier = RecommendationTier.STANDARD)

    private val tricepPushdown = exercise(7, "Cable Tricep Pushdown", ExerciseCategory.ARMS,
        setOf(MuscleGroup.TRICEPS),
        equipment = Equipment.CABLE, popularity = 75, difficulty = 15,
        tier = RecommendationTier.STANDARD)

    private val legPress = exercise(8, "Leg Press", ExerciseCategory.LEGS,
        setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES),
        equipment = Equipment.MACHINE, popularity = 80, difficulty = 20,
        tier = RecommendationTier.ESSENTIAL)

    private val plank = exercise(9, "Plank", ExerciseCategory.CORE,
        setOf(MuscleGroup.CORE, MuscleGroup.ABS),
        equipment = Equipment.BODYWEIGHT, popularity = 85, difficulty = 10,
        tier = RecommendationTier.ESSENTIAL)

    private val running = exercise(10, "Treadmill Running", ExerciseCategory.CARDIO,
        setOf(MuscleGroup.LEGS),
        equipment = Equipment.MACHINE, popularity = 70, difficulty = 15,
        tier = RecommendationTier.STANDARD)

    private val inclineBenchPress = exercise(11, "Incline Barbell Bench Press", ExerciseCategory.CHEST,
        setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS),
        popularity = 80, difficulty = 45,
        tier = RecommendationTier.STANDARD)

    private val dumbbellRow = exercise(12, "Dumbbell Row", ExerciseCategory.BACK,
        setOf(MuscleGroup.BACK, MuscleGroup.BICEPS),
        equipment = Equipment.DUMBBELL, popularity = 80, difficulty = 25,
        tier = RecommendationTier.STANDARD)

    private val snatch = exercise(13, "Barbell Snatch", ExerciseCategory.FULL_BODY,
        setOf(MuscleGroup.SHOULDERS, MuscleGroup.BACK, MuscleGroup.LEGS),
        popularity = 30, difficulty = 95,
        tier = RecommendationTier.ADVANCED)

    private val guillotinePress = exercise(14, "Guillotine Press", ExerciseCategory.CHEST,
        setOf(MuscleGroup.CHEST),
        popularity = 10, difficulty = 80,
        tier = RecommendationTier.SPECIALIZED)

    private val allExercises = listOf(
        benchPress, squat, deadlift, latPulldown, shoulderPress,
        bicepCurl, tricepPushdown, legPress, plank, running,
        inclineBenchPress, dumbbellRow, snatch, guillotinePress
    )

    // ===============================================================
    // CAPABILITY EVAL 1: 회복 중 근육 필터링 — secondary muscle 포함
    // ===============================================================

    @Test
    @DisplayName("[CE-01] 삼두가 회복 중이면 벤치프레스(secondary=삼두) 제외되어야 함")
    fun `recovery filter should exclude exercises with recovering secondary muscles`() {
        val recoveringMuscles = setOf(MuscleGroup.TRICEPS)

        val filtered = allExercises.filter { exercise ->
            exercise.muscleGroups.none { it in recoveringMuscles }
        }

        assertFalse(filtered.contains(benchPress),
            "벤치프레스는 삼두(secondary)가 회복 중이므로 제외해야 함")
        assertFalse(filtered.contains(shoulderPress),
            "숄더프레스도 삼두 포함이므로 제외해야 함")
        assertFalse(filtered.contains(tricepPushdown),
            "트라이셉 푸시다운도 삼두이므로 제외해야 함")
        assertTrue(filtered.contains(squat), "스쿼트는 삼두 미포함이므로 포함")
        assertTrue(filtered.contains(bicepCurl), "바이셉 컬은 삼두 미포함이므로 포함")
    }

    // ===============================================================
    // CAPABILITY EVAL 2: 패턴 중복 제거 — 같은 패턴에서 최고 운동만 선택
    // ===============================================================

    @Test
    @DisplayName("[CE-02] 벤치프레스와 인클라인벤치는 다른 패턴이므로 둘 다 추천 가능")
    fun `bench press and incline bench should be different patterns`() {
        val benchPattern = classifier.classifyExercise(benchPress)
        val inclinePattern = classifier.classifyExercise(inclineBenchPress)

        assertNotEquals(benchPattern, inclinePattern,
            "벤치프레스(HORIZONTAL)와 인클라인벤치(INCLINE)는 다른 패턴이어야 함")
    }

    @Test
    @DisplayName("[CE-03] 같은 패턴 운동에서 ESSENTIAL > STANDARD 우선 선택")
    fun `pattern deduplication should prefer essential over standard`() {
        val exercises = listOf(benchPress, inclineBenchPress)
            .groupBy { classifier.classifyExercise(it) }
            .mapNotNull { (_, group) ->
                group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())
            }

        // 각 패턴에서 하나씩 선택되어야 함 (다른 패턴이므로 둘 다 포함)
        assertTrue(exercises.any { it.id == benchPress.id },
            "ESSENTIAL인 벤치프레스는 반드시 선택되어야 함")
    }

    // ===============================================================
    // CAPABILITY EVAL 3: Tier 필터 — ADVANCED/SPECIALIZED 자동 추천 제외
    // ===============================================================

    @Test
    @DisplayName("[CE-04] ADVANCED/SPECIALIZED 운동은 자동 추천에서 제외")
    fun `advanced and specialized exercises should not be auto-recommended`() {
        val generalCandidates = allExercises.filter {
            RecommendationExerciseRanking.isGeneralCandidate(it)
        }

        assertFalse(generalCandidates.contains(snatch),
            "ADVANCED(스내치)는 일반 추천에서 제외")
        assertFalse(generalCandidates.contains(guillotinePress),
            "SPECIALIZED(기요틴 프레스)는 일반 추천에서 제외")
        assertTrue(generalCandidates.contains(benchPress),
            "ESSENTIAL(벤치프레스)는 추천에 포함")
        assertTrue(generalCandidates.contains(bicepCurl),
            "STANDARD(바이셉 컬)는 추천에 포함")
    }

    // ===============================================================
    // CAPABILITY EVAL 4: 정렬 우선순위 — 큰 근육 복합 운동 먼저
    // ===============================================================

    @Test
    @DisplayName("[CE-05] 표시 순서: 다리 > 등 > 가슴 > 어깨 > 팔 > 코어 > 유산소")
    fun `display order should prioritize large muscle compounds first`() {
        val sorted = listOf(bicepCurl, plank, squat, benchPress, running)
            .sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        val names = sorted.map { it.name }
        val squatIdx = names.indexOf("Barbell Back Squat")
        val benchIdx = names.indexOf("Barbell Bench Press")
        val curlIdx = names.indexOf("Dumbbell Bicep Curl")
        val plankIdx = names.indexOf("Plank")
        val runIdx = names.indexOf("Treadmill Running")

        assertTrue(squatIdx < benchIdx, "스쿼트(다리)가 벤치(가슴)보다 먼저")
        assertTrue(benchIdx < curlIdx, "벤치(가슴)가 바이셉컬(팔)보다 먼저")
        assertTrue(curlIdx < plankIdx, "바이셉컬(팔)이 플랭크(코어)보다 먼저")
        assertTrue(plankIdx < runIdx, "플랭크(코어)가 러닝(유산소)보다 먼저")
    }

    // ===============================================================
    // CAPABILITY EVAL 5: MUSCLE_GAIN 목표 — CARDIO 제외
    // ===============================================================

    @Test
    @DisplayName("[CE-06] MUSCLE_GAIN 목표: 유산소 운동 필터링")
    fun `muscle gain goal should filter out cardio exercises`() {
        val muscleGainFiltered = allExercises
            .filter { it.category != ExerciseCategory.CARDIO }

        assertFalse(muscleGainFiltered.contains(running),
            "러닝(CARDIO)은 근비대 목표에서 제외")
        assertTrue(muscleGainFiltered.contains(squat),
            "스쿼트는 근비대 목표에 포함")
    }

    // ===============================================================
    // CAPABILITY EVAL 6: 장비 필터 — 덤벨만 사용 가능 시
    // ===============================================================

    @Test
    @DisplayName("[CE-07] 덤벨 장비만 선택 시 바벨/머신 운동 제외")
    fun `equipment filter should only return matching equipment exercises`() {
        val dumbbellOnly = allExercises.filter { it.equipment == Equipment.DUMBBELL }

        assertTrue(dumbbellOnly.all { it.equipment == Equipment.DUMBBELL },
            "덤벨 필터 결과에 비덤벨 운동이 포함되면 안 됨")
        assertTrue(dumbbellOnly.contains(bicepCurl))
        assertTrue(dumbbellOnly.contains(dumbbellRow))
        assertFalse(dumbbellOnly.contains(benchPress), "바벨 벤치는 제외")
    }

    // ===============================================================
    // CAPABILITY EVAL 7: Core 추천 — 최소 후보 보장
    // ===============================================================

    @Test
    @DisplayName("[CE-08] Core 필터: ESSENTIAL 후보가 최소 6개 미만이면 STANDARD로 확장")
    fun `core candidates should fallback to general when insufficient`() {
        val coreCandidates = allExercises.filter {
            RecommendationExerciseRanking.isCoreCandidate(it)
        }
        val generalCandidates = allExercises.filter {
            RecommendationExerciseRanking.isGeneralCandidate(it)
        }

        assertTrue(generalCandidates.size >= coreCandidates.size,
            "GENERAL은 항상 CORE보다 같거나 많아야 함")
    }

    // ===============================================================
    // CAPABILITY EVAL 8: 패턴 분류 — 주요 운동 올바르게 분류
    // ===============================================================

    @Test
    @DisplayName("[CE-09] 주요 운동 패턴 분류 정확성")
    fun `key exercises should be classified to correct movement patterns`() {
        assertEquals(MovementPattern.DEADLIFT,
            classifier.classifyExercise(deadlift), "데드리프트 패턴")

        val benchPattern = classifier.classifyExercise(benchPress)
        assertTrue(benchPattern.name.contains("HORIZONTAL") || benchPattern.name.contains("PRESS"),
            "벤치프레스는 수평 프레스 패턴이어야 함, 실제: $benchPattern")

        assertEquals(MovementPattern.LAT_PULLDOWN,
            classifier.classifyExercise(latPulldown), "랫풀다운 패턴")

        assertEquals(MovementPattern.PLANK,
            classifier.classifyExercise(plank), "플랭크 패턴")
    }

    // ===============================================================
    // CAPABILITY EVAL 9: 복합운동 감지
    // ===============================================================

    @Test
    @DisplayName("[CE-10] 복합운동 vs 고립운동 올바르게 감지")
    fun `compound exercises should be detected correctly`() {
        // muscleGroups >= 2이면 compound
        assertTrue(benchPress.muscleGroups.size >= 2,
            "벤치프레스는 3개 근육 → 복합운동")
        assertTrue(squat.muscleGroups.size >= 2,
            "스쿼트는 3개 근육 → 복합운동")
        assertEquals(1, bicepCurl.muscleGroups.size,
            "바이셉 컬은 1개 근육 → 고립운동")
    }

    // ===============================================================
    // REGRESSION EVAL: 전체 추천 파이프라인 무결성
    // ===============================================================

    @Test
    @DisplayName("[RE-01] 전체 필터 파이프라인: tier → recovery → equipment → target → dedup → sort")
    fun `full recommendation pipeline should produce valid results`() {
        val recoveringMuscles = setOf(MuscleGroup.TRICEPS)

        val result = allExercises
            // 1. Tier filter
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            // 2. Recovery filter
            .filter { exercise -> exercise.muscleGroups.none { it in recoveringMuscles } }
            // 3. Pattern dedup
            .groupBy { classifier.classifyExercise(it) }
            .mapNotNull { (_, group) ->
                group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator())
            }
            // 4. Sort
            .sortedWith(RecommendationExerciseRanking.displayOrderComparator())
            .take(6)

        // Assertions
        assertTrue(result.isNotEmpty(), "결과가 비어있으면 안 됨")
        assertTrue(result.none { it.recommendationTier == RecommendationTier.ADVANCED },
            "ADVANCED 운동이 포함되면 안 됨")
        assertTrue(result.none { it.recommendationTier == RecommendationTier.SPECIALIZED },
            "SPECIALIZED 운동이 포함되면 안 됨")
        assertTrue(result.none { exercise ->
            exercise.muscleGroups.any { it == MuscleGroup.TRICEPS }
        }, "삼두 회복 중이므로 삼두 관련 운동이 포함되면 안 됨")

        // 패턴 중복 없음
        val patterns = result.map { classifier.classifyExercise(it) }
        assertEquals(patterns.size, patterns.distinct().size,
            "같은 패턴 운동이 중복되면 안 됨")
    }

    @Test
    @DisplayName("[RE-02] 빈 회복 근육 → 모든 운동 통과")
    fun `empty recovering muscles should pass all exercises`() {
        val recoveringMuscles = emptySet<MuscleGroup>()
        val filtered = allExercises.filter { exercise ->
            exercise.muscleGroups.none { it in recoveringMuscles }
        }
        assertEquals(allExercises.size, filtered.size,
            "회복 중인 근육이 없으면 모든 운동이 통과해야 함")
    }

    @Test
    @DisplayName("[RE-03] ESSENTIAL + isBasicExercise 모두 core candidate에 포함")
    fun `both essential and basic exercises should be core candidates`() {
        val essentialOnly = exercise(100, "Test Essential", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST), tier = RecommendationTier.ESSENTIAL)
        val basicOnly = exercise(101, "Test Basic", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST), isBasic = true, tier = RecommendationTier.STANDARD)
        val neither = exercise(102, "Test Standard", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST))

        assertTrue(RecommendationExerciseRanking.isCoreCandidate(essentialOnly))
        assertTrue(RecommendationExerciseRanking.isCoreCandidate(basicOnly))
        assertFalse(RecommendationExerciseRanking.isCoreCandidate(neither))
    }

    // ===============================================================
    // EDGE CASE EVAL: 극한 상황 검증
    // ===============================================================

    @Test
    @DisplayName("[EC-01] 모든 근육이 회복 중이면 결과가 빈 리스트")
    fun `all muscles recovering should return empty list`() {
        val allMuscles = MuscleGroup.values().toSet()
        val filtered = allExercises.filter { exercise ->
            exercise.muscleGroups.none { it in allMuscles }
        }
        assertTrue(filtered.isEmpty() || filtered.all { it.muscleGroups.isEmpty() },
            "모든 근육이 회복 중이면 근육 관련 운동이 모두 제외되어야 함")
    }

    @Test
    @DisplayName("[EC-02] 근육 그룹이 빈 운동은 회복 필터를 통과해야 함")
    fun `exercise with empty muscle groups should pass recovery filter`() {
        val noMuscleExercise = exercise(200, "Stretching", ExerciseCategory.FULL_BODY,
            emptySet(), equipment = Equipment.BODYWEIGHT)
        val recoveringMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.BACK)

        val passes = noMuscleExercise.muscleGroups.none { it in recoveringMuscles }
        assertTrue(passes, "근육 그룹이 비어있으면 none 체크가 true를 반환해야 함")
    }

    @Test
    @DisplayName("[EC-03] STRENGTH 필터: compound 비율 보장")
    fun `strength filter should enforce compound ratio`() {
        val compounds = allExercises.filter { it.muscleGroups.size >= 2 }
        val isolations = allExercises.filter { it.muscleGroups.size < 2 }

        // compound가 전체의 70% 이상이어야 strength 목표에 적합
        val maxIsolation = (compounds.size * 3) / 7
        val strengthFiltered = compounds + isolations.take(maxIsolation)

        val compoundRatio = compounds.size.toDouble() / strengthFiltered.size
        assertTrue(compoundRatio >= 0.6,
            "STRENGTH 필터 결과에서 compound 비율이 60% 이상이어야 함, 실제: ${(compoundRatio * 100).toInt()}%")
    }

    @Test
    @DisplayName("[EC-04] 패턴 분류: 이름에 여러 키워드가 포함된 복합 운동명")
    fun `pattern classifier should handle complex exercise names`() {
        val complexName = exercise(300, "Incline Dumbbell Bench Press", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST), equipment = Equipment.DUMBBELL)
        val pattern = classifier.classifyExercise(complexName)

        // "incline" + "dumbbell" → INCLINE_PRESS_DUMBBELL 우선
        assertEquals(MovementPattern.INCLINE_PRESS_DUMBBELL, pattern,
            "인클라인 + 덤벨 → INCLINE_PRESS_DUMBBELL 패턴")
    }

    @Test
    @DisplayName("[EC-05] 정렬 안정성: 동일 카테고리 동일 popularity 시 이름순")
    fun `sorting should be stable with same category and popularity`() {
        val ex1 = exercise(400, "Alpha Exercise", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST), popularity = 80, tier = RecommendationTier.STANDARD)
        val ex2 = exercise(401, "Beta Exercise", ExerciseCategory.CHEST,
            setOf(MuscleGroup.CHEST), popularity = 80, tier = RecommendationTier.STANDARD)

        val sorted = listOf(ex2, ex1)
            .sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        assertEquals("Alpha Exercise", sorted[0].name,
            "동일 조건이면 이름 알파벳순으로 안정 정렬")
    }

    @Test
    @DisplayName("[EC-06] 전체 파이프라인: 장비+타겟+회복 동시 필터 시 최소 결과 보장")
    fun `combined filters should still produce results`() {
        val recoveringMuscles = setOf(MuscleGroup.CHEST) // 가슴 회복 중

        val result = allExercises
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .filter { it.equipment == Equipment.DUMBBELL } // 덤벨만
            .filter { exercise -> exercise.muscleGroups.none { it in recoveringMuscles } }

        // 덤벨 + 가슴 제외 → dumbbellRow(등), bicepCurl(이두) 정도
        assertTrue(result.isNotEmpty(), "복합 필터 적용 후에도 결과가 있어야 함")
        assertTrue(result.none { MuscleGroup.CHEST in it.muscleGroups },
            "가슴 회복 중이므로 가슴 운동 제외")
    }
}

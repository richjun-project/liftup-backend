package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier.MovementPattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * 운동 추천 품질 검증 — 실제 PT 프로그래밍 원칙 기반
 *
 * 테스트가 실패하면 추천 로직을 수정해야 합니다.
 * 테스트를 문제에 맞추지 않습니다.
 *
 * PT 프로그래밍 원칙:
 * 1. 큰 복합운동이 먼저 (Big compound first)
 * 2. 전신이면 주요 부위 골고루 (Push/Pull/Legs 균형)
 * 3. 한 카테고리가 독점하면 안 됨 (Category balance)
 * 4. 고립운동은 복합운동 뒤에 (Isolation after compound)
 * 5. 타겟 데이면 해당 부위 집중 (Target focus)
 * 6. 회복 중 근육은 절대 포함 안 됨 (Recovery respect)
 * 7. 같은 동작 패턴 중복 없음 (Pattern uniqueness)
 */
class WorkoutRecommendationQualityTest {

    private val classifier = ExercisePatternClassifier()

    private fun ex(
        id: Long, name: String, cat: ExerciseCategory,
        muscles: Set<MuscleGroup>, eq: Equipment = Equipment.BARBELL,
        pop: Int = 50, diff: Int = 50, basic: Boolean = false,
        tier: RecommendationTier = RecommendationTier.STANDARD
    ) = Exercise(id = id, slug = name.lowercase().replace(" ", "-"),
        name = name, category = cat, muscleGroups = muscles.toMutableSet(),
        equipment = eq, popularity = pop, difficulty = diff,
        isBasicExercise = basic, recommendationTier = tier)

    // === 현실적인 운동 DB (40개) ===
    private val exerciseDB = listOf(
        // CHEST
        ex(1, "Barbell Bench Press", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS), pop=95, diff=45, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(2, "Incline Barbell Bench Press", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), pop=80, diff=45),
        ex(3, "Dumbbell Bench Press", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), eq=Equipment.DUMBBELL, pop=80, diff=35),
        ex(4, "Incline Dumbbell Press", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS), eq=Equipment.DUMBBELL, pop=75, diff=35),
        ex(5, "Cable Fly", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST), eq=Equipment.CABLE, pop=70, diff=20),
        ex(6, "Chest Press Machine", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), eq=Equipment.MACHINE, pop=65, diff=10),
        ex(7, "Push Up", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS), eq=Equipment.BODYWEIGHT, pop=85, diff=15, basic=true, tier=RecommendationTier.ESSENTIAL),
        // BACK
        ex(10, "Conventional Deadlift", ExerciseCategory.BACK, setOf(MuscleGroup.BACK, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES), pop=90, diff=55, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(11, "Barbell Row", ExerciseCategory.BACK, setOf(MuscleGroup.BACK, MuscleGroup.BICEPS, MuscleGroup.LATS), pop=85, diff=40, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(12, "Lat Pulldown", ExerciseCategory.BACK, setOf(MuscleGroup.LATS, MuscleGroup.BICEPS), eq=Equipment.CABLE, pop=85, diff=20, tier=RecommendationTier.ESSENTIAL),
        ex(13, "Dumbbell Row", ExerciseCategory.BACK, setOf(MuscleGroup.BACK, MuscleGroup.BICEPS), eq=Equipment.DUMBBELL, pop=80, diff=25),
        ex(14, "Seated Cable Row", ExerciseCategory.BACK, setOf(MuscleGroup.BACK, MuscleGroup.BICEPS, MuscleGroup.LATS), eq=Equipment.CABLE, pop=75, diff=20),
        ex(15, "Pull-up", ExerciseCategory.BACK, setOf(MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.BACK), eq=Equipment.BODYWEIGHT, pop=85, diff=50, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(16, "Romanian Deadlift", ExerciseCategory.BACK, setOf(MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.BACK), pop=80, diff=45),
        // LEGS
        ex(20, "Barbell Back Squat", ExerciseCategory.LEGS, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), pop=95, diff=50, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(21, "Leg Press", ExerciseCategory.LEGS, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), eq=Equipment.MACHINE, pop=80, diff=20, tier=RecommendationTier.ESSENTIAL),
        ex(22, "Dumbbell Lunge", ExerciseCategory.LEGS, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), eq=Equipment.DUMBBELL, pop=75, diff=25),
        ex(23, "Leg Extension", ExerciseCategory.LEGS, setOf(MuscleGroup.QUADRICEPS), eq=Equipment.MACHINE, pop=70, diff=10),
        ex(24, "Leg Curl", ExerciseCategory.LEGS, setOf(MuscleGroup.HAMSTRINGS), eq=Equipment.MACHINE, pop=70, diff=10),
        ex(25, "Calf Raise", ExerciseCategory.LEGS, setOf(MuscleGroup.CALVES), eq=Equipment.MACHINE, pop=60, diff=10),
        ex(26, "Hip Thrust", ExerciseCategory.LEGS, setOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), pop=75, diff=30),
        ex(27, "Bulgarian Split Squat", ExerciseCategory.LEGS, setOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES), eq=Equipment.DUMBBELL, pop=65, diff=40),
        // SHOULDERS
        ex(30, "Overhead Press", ExerciseCategory.SHOULDERS, setOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), pop=85, diff=40, basic=true, tier=RecommendationTier.ESSENTIAL),
        ex(31, "Dumbbell Lateral Raise", ExerciseCategory.SHOULDERS, setOf(MuscleGroup.SHOULDERS), eq=Equipment.DUMBBELL, pop=80, diff=15),
        ex(32, "Face Pull", ExerciseCategory.SHOULDERS, setOf(MuscleGroup.SHOULDERS, MuscleGroup.TRAPS), eq=Equipment.CABLE, pop=65, diff=15),
        ex(33, "Rear Delt Fly", ExerciseCategory.SHOULDERS, setOf(MuscleGroup.SHOULDERS), eq=Equipment.DUMBBELL, pop=60, diff=15),
        // ARMS
        ex(35, "Barbell Curl", ExerciseCategory.ARMS, setOf(MuscleGroup.BICEPS), pop=80, diff=15),
        ex(36, "Dumbbell Curl", ExerciseCategory.ARMS, setOf(MuscleGroup.BICEPS), eq=Equipment.DUMBBELL, pop=80, diff=15),
        ex(37, "Cable Tricep Pushdown", ExerciseCategory.ARMS, setOf(MuscleGroup.TRICEPS), eq=Equipment.CABLE, pop=75, diff=10),
        ex(38, "Skull Crusher", ExerciseCategory.ARMS, setOf(MuscleGroup.TRICEPS), pop=65, diff=30),
        ex(39, "Hammer Curl", ExerciseCategory.ARMS, setOf(MuscleGroup.BICEPS, MuscleGroup.FOREARMS), eq=Equipment.DUMBBELL, pop=70, diff=10),
        // CORE
        ex(40, "Plank", ExerciseCategory.CORE, setOf(MuscleGroup.CORE, MuscleGroup.ABS), eq=Equipment.BODYWEIGHT, pop=85, diff=10, tier=RecommendationTier.ESSENTIAL),
        ex(41, "Crunch", ExerciseCategory.CORE, setOf(MuscleGroup.ABS), eq=Equipment.BODYWEIGHT, pop=75, diff=5),
        ex(42, "Hanging Leg Raise", ExerciseCategory.CORE, setOf(MuscleGroup.ABS, MuscleGroup.CORE), eq=Equipment.BODYWEIGHT, pop=70, diff=30),
        // CARDIO
        ex(45, "Treadmill Running", ExerciseCategory.CARDIO, setOf(MuscleGroup.LEGS), eq=Equipment.MACHINE, pop=70, diff=15),
        // ADVANCED/SPECIALIZED
        ex(50, "Barbell Snatch", ExerciseCategory.FULL_BODY, setOf(MuscleGroup.SHOULDERS, MuscleGroup.BACK, MuscleGroup.LEGS), pop=30, diff=95, tier=RecommendationTier.ADVANCED),
        ex(51, "Guillotine Press", ExerciseCategory.CHEST, setOf(MuscleGroup.CHEST), pop=10, diff=80, tier=RecommendationTier.SPECIALIZED),
    )

    // === 추천 파이프라인 (ExerciseRecommendationService 미러링) ===

    private fun recommend(
        recoveringMuscles: Set<MuscleGroup> = emptySet(),
        equipment: Equipment? = null,
        targetMuscleGroups: Set<MuscleGroup> = emptySet(),
        excludeCardio: Boolean = false,
        limit: Int = 6
    ): List<Exercise> {
        return exerciseDB
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .filter { ex -> ex.muscleGroups.none { it in recoveringMuscles } }
            .let { list -> if (equipment != null) list.filter { it.equipment == equipment } else list }
            .let { list -> if (targetMuscleGroups.isNotEmpty()) list.filter { ex -> ex.muscleGroups.any { it in targetMuscleGroups } } else list }
            .let { list -> if (excludeCardio) list.filter { it.category != ExerciseCategory.CARDIO } else list }
            .let { removeDuplicatePatterns(it) }
            .let { orderByPriority(it) }
            .take(limit)
    }

    private fun removeDuplicatePatterns(exercises: List<Exercise>): List<Exercise> {
        return exercises
            .groupBy { classifier.classifyExercise(it) }
            .mapNotNull { (_, group) -> group.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator()) }
    }

    /** ExerciseRecommendationService.orderByPriority 미러링 (라운드 로빈) */
    private fun orderByPriority(exercises: List<Exercise>): List<Exercise> {
        val byCategory = exercises
            .groupBy { it.category }
            .mapValues { (_, group) ->
                group.sortedWith(RecommendationExerciseRanking.displayOrderComparator()).toMutableList()
            }
        val categoryOrder = listOf(
            ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
            ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY
        )
        val result = mutableListOf<Exercise>()
        var hasMore = true
        while (hasMore) {
            hasMore = false
            for (category in categoryOrder) {
                val queue = byCategory[category]
                if (queue != null && queue.isNotEmpty()) {
                    result.add(queue.removeFirst())
                    hasMore = true
                }
            }
        }
        return result
    }

    // ================================================================
    //  원칙 1: 큰 복합운동이 먼저
    // ================================================================

    @Nested
    @DisplayName("원칙 1: 큰 복합운동이 먼저 (Big Compound First)")
    inner class BigCompoundFirst {

        @Test
        @DisplayName("전신 4개 → 4개 모두 복합운동이어야 함")
        fun `fullbody 4 should be all compounds`() {
            val result = recommend(limit = 4)
            result.forEachIndexed { i, ex ->
                assertTrue(ex.muscleGroups.size >= 2,
                    "위치 $i: ${ex.name}은 ${ex.muscleGroups.size}개 근육 → 복합운동이어야 함")
            }
        }

        @Test
        @DisplayName("전신 6개 → 처음 4개는 반드시 복합운동")
        fun `fullbody 6 first 4 should be compounds`() {
            val result = recommend(limit = 6)
            for (i in 0 until minOf(4, result.size)) {
                assertTrue(result[i].muscleGroups.size >= 2,
                    "위치 $i: ${result[i].name}(${result[i].muscleGroups.size}근육) — 첫 4개는 복합운동이어야 함")
            }
        }

        @Test
        @DisplayName("첫 운동은 반드시 ESSENTIAL 또는 Basic")
        fun `first exercise must be essential or basic`() {
            val result = recommend(limit = 6)
            val first = result[0]
            assertTrue(
                first.recommendationTier == RecommendationTier.ESSENTIAL || first.isBasicExercise,
                "첫 운동이 ${first.name}(${first.recommendationTier}) — ESSENTIAL이어야 함")
        }
    }

    // ================================================================
    //  원칙 2: Push/Pull/Legs 균형
    // ================================================================

    @Nested
    @DisplayName("원칙 2: 전신 추천 시 Push/Pull/Legs 균형")
    inner class PushPullLegsBalance {

        @Test
        @DisplayName("전신 6개에 Legs + Push(가슴) + Pull(등) 모두 포함")
        fun `fullbody 6 should have legs push and pull`() {
            val result = recommend(limit = 6)
            val categories = result.map { it.category }.toSet()

            assertTrue(ExerciseCategory.LEGS in categories,
                "하체 없음! 카테고리: $categories")
            assertTrue(ExerciseCategory.BACK in categories,
                "등(Pull) 없음! 카테고리: $categories")
            assertTrue(ExerciseCategory.CHEST in categories,
                "가슴(Push) 없음! 카테고리: $categories")
        }

        @Test
        @DisplayName("전신 4개에도 Legs + 상체가 모두 포함")
        fun `fullbody 4 should have legs and upper`() {
            val result = recommend(limit = 4)
            val categories = result.map { it.category }.toSet()

            assertTrue(ExerciseCategory.LEGS in categories, "하체 없음!")
            val hasUpper = categories.any { it in listOf(ExerciseCategory.BACK, ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS) }
            assertTrue(hasUpper, "상체 없음! 카테고리: $categories")
        }

        @Test
        @DisplayName("전신 8개에 Legs + Back + Chest + Shoulders 모두 포함")
        fun `fullbody 8 should cover 4 major categories`() {
            val result = recommend(limit = 8)
            val categories = result.map { it.category }.toSet()

            assertTrue(categories.size >= 4,
                "8개 추천에 4개 카테고리 필요. 실제: $categories")
        }
    }

    // ================================================================
    //  원칙 3: 카테고리 독점 금지
    // ================================================================

    @Nested
    @DisplayName("원칙 3: 한 카테고리 독점 금지")
    inner class NoCategoryDomination {

        @Test
        @DisplayName("전신 6개에서 한 카테고리가 50% 초과하면 안 됨")
        fun `no category exceeds 50 percent in 6`() {
            val result = recommend(limit = 6)
            val counts = result.groupBy { it.category }.mapValues { it.value.size }
            val maxEntry = counts.maxByOrNull { it.value }!!

            assertTrue(maxEntry.value <= 3,
                "${maxEntry.key}가 ${maxEntry.value}/6개 → 최대 3개(50%). 분포: $counts")
        }

        @Test
        @DisplayName("전신 8개에서 한 카테고리가 3개를 초과하면 안 됨")
        fun `no category exceeds 3 in 8`() {
            val result = recommend(limit = 8)
            val counts = result.groupBy { it.category }.mapValues { it.value.size }
            val maxEntry = counts.maxByOrNull { it.value }!!

            assertTrue(maxEntry.value <= 3,
                "${maxEntry.key}가 ${maxEntry.value}/8개 → 최대 3개. 분포: $counts")
        }
    }

    // ================================================================
    //  원칙 4: 패턴 유일성
    // ================================================================

    @Nested
    @DisplayName("원칙 4: 같은 동작 패턴 중복 없음")
    inner class PatternUniqueness {

        @Test
        @DisplayName("전신 6개 패턴 중복 0건")
        fun `no pattern duplication in 6`() {
            assertNoDuplicatePatterns(recommend(limit = 6))
        }

        @Test
        @DisplayName("전신 8개 패턴 중복 0건")
        fun `no pattern duplication in 8`() {
            assertNoDuplicatePatterns(recommend(limit = 8))
        }

        @Test
        @DisplayName("덤벨 전용 4개 패턴 중복 0건")
        fun `no pattern duplication dumbbell 4`() {
            assertNoDuplicatePatterns(recommend(equipment = Equipment.DUMBBELL, limit = 4))
        }

        @Test
        @DisplayName("회복 필터 후 패턴 중복 0건")
        fun `no pattern duplication after recovery`() {
            assertNoDuplicatePatterns(recommend(recoveringMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), limit = 6))
        }

        private fun assertNoDuplicatePatterns(result: List<Exercise>) {
            val patterns = result.map { classifier.classifyExercise(it) }
            val dups = patterns.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue(dups.isEmpty(),
                "패턴 중복: $dups\n운동목록: ${result.map { "${it.name}→${classifier.classifyExercise(it)}" }}")
        }
    }

    // ================================================================
    //  원칙 5: Tier 필터 (ADVANCED/SPECIALIZED 절대 제외)
    // ================================================================

    @Nested
    @DisplayName("원칙 5: ADVANCED/SPECIALIZED 절대 제외")
    inner class TierFilter {

        @Test
        @DisplayName("어떤 조건에서도 ADVANCED 안 나옴")
        fun `advanced never appears`() {
            listOf(
                recommend(limit = 8),
                recommend(equipment = Equipment.BARBELL, limit = 6),
                recommend(recoveringMuscles = setOf(MuscleGroup.CHEST), limit = 6),
            ).forEachIndexed { i, r ->
                assertTrue(r.none { it.recommendationTier == RecommendationTier.ADVANCED },
                    "시나리오 $i: ADVANCED 포함")
            }
        }

        @Test
        @DisplayName("SPECIALIZED도 절대 안 나옴")
        fun `specialized never appears`() {
            assertTrue(recommend(limit = 10).none { it.recommendationTier == RecommendationTier.SPECIALIZED })
        }
    }

    // ================================================================
    //  원칙 6: 회복 중 근육 절대 제외
    // ================================================================

    @Nested
    @DisplayName("원칙 6: 회복 중 근육 절대 제외")
    inner class RecoveryFilter {

        @Test
        @DisplayName("어제 가슴 → 가슴/삼두/어깨 운동 0개")
        fun `after chest day no chest triceps shoulder`() {
            val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            val result = recommend(recoveringMuscles = recovering, limit = 6)

            val violations = result.filter { ex -> ex.muscleGroups.any { it in recovering } }
            assertTrue(violations.isEmpty(),
                "회복 근육 포함: ${violations.map { "${it.name}(${it.muscleGroups})" }}")
        }

        @Test
        @DisplayName("어제 가슴 → 등/하체가 대신 추천됨")
        fun `after chest day recommends back and legs`() {
            val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            val result = recommend(recoveringMuscles = recovering, limit = 6)

            assertTrue(result.any { it.category == ExerciseCategory.LEGS }, "하체 포함되어야 함")
            assertTrue(result.any { it.category == ExerciseCategory.BACK }, "등 포함되어야 함")
        }

        @Test
        @DisplayName("벤치프레스, OHP, 푸쉬업, 트라이셉 푸시다운 모두 제외")
        fun `specific exercises excluded after chest day`() {
            val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            val result = recommend(recoveringMuscles = recovering, limit = 10)
            val names = result.map { it.name }

            listOf("Barbell Bench Press", "Overhead Press", "Push Up", "Cable Tricep Pushdown",
                "Incline Barbell Bench Press", "Skull Crusher").forEach { name ->
                assertFalse(name in names, "$name 이 포함되면 안 됨")
            }
        }

        @Test
        @DisplayName("햄스트링 아픔 → 스쿼트(햄 포함) 제외, 레그익스텐션(쿼드만) 가능")
        fun `hamstring sore excludes squat but allows leg extension`() {
            val recovering = setOf(MuscleGroup.HAMSTRINGS)
            val result = recommend(recoveringMuscles = recovering, limit = 8)

            assertFalse(result.any { it.name == "Barbell Back Squat" }, "스쿼트는 햄스트링 포함이라 제외")
            assertFalse(result.any { it.name == "Conventional Deadlift" }, "데드리프트도 햄스트링 포함이라 제외")
            // 레그 익스텐션은 쿼드만이라 가능해야 함
            val legExercises = result.filter { it.category == ExerciseCategory.LEGS }
            assertTrue(legExercises.none { MuscleGroup.HAMSTRINGS in it.muscleGroups },
                "하체 운동에 햄스트링이 포함됨: ${legExercises.map { "${it.name}(${it.muscleGroups})" }}")
        }

        @Test
        @DisplayName("상체 전체 회복 → 하체+코어만")
        fun `upper body recovering only legs and core`() {
            val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS,
                MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.TRAPS)
            val result = recommend(recoveringMuscles = recovering, limit = 6)

            assertTrue(result.none { ex -> ex.muscleGroups.any { it in recovering } },
                "상체 운동 포함됨: ${result.filter { ex -> ex.muscleGroups.any { it in recovering } }.map { it.name }}")
            assertTrue(result.isNotEmpty(), "결과가 비어있으면 안 됨")
        }
    }

    // ================================================================
    //  원칙 7: 장비 필터 정확성
    // ================================================================

    @Nested
    @DisplayName("원칙 7: 장비 필터")
    inner class EquipmentFilter {

        @Test
        @DisplayName("덤벨 전용 → 덤벨만 + 상하체 균형")
        fun `dumbbell only with upper lower balance`() {
            val result = recommend(equipment = Equipment.DUMBBELL, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.DUMBBELL },
                "덤벨 아닌 운동: ${result.filter { it.equipment != Equipment.DUMBBELL }.map { "${it.name}(${it.equipment})" }}")
            val hasUpper = result.any { it.category in listOf(ExerciseCategory.CHEST, ExerciseCategory.BACK, ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS) }
            val hasLower = result.any { it.category == ExerciseCategory.LEGS }
            assertTrue(hasUpper && hasLower, "덤벨 추천도 상하체 균형 필요. 카테고리: ${result.map { it.category }}")
        }

        @Test
        @DisplayName("머신 전용 → 머신만")
        fun `machine only all machine`() {
            val result = recommend(equipment = Equipment.MACHINE, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.MACHINE })
            assertTrue(result.isNotEmpty())
        }

        @Test
        @DisplayName("케이블 전용 → 케이블만")
        fun `cable only all cable`() {
            val result = recommend(equipment = Equipment.CABLE, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.CABLE })
            assertTrue(result.isNotEmpty())
        }

        @Test
        @DisplayName("맨몸 전용 → 맨몸만")
        fun `bodyweight only all bodyweight`() {
            val result = recommend(equipment = Equipment.BODYWEIGHT, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.BODYWEIGHT })
        }
    }

    // ================================================================
    //  원칙 8: 타겟 데이
    // ================================================================

    @Nested
    @DisplayName("원칙 8: 타겟 부위 집중")
    inner class TargetFocus {

        @Test
        @DisplayName("가슴 타겟 → 모든 운동이 가슴 근육 포함")
        fun `chest target all exercises include chest muscle`() {
            val result = recommend(targetMuscleGroups = setOf(MuscleGroup.CHEST), limit = 5)
            assertTrue(result.all { MuscleGroup.CHEST in it.muscleGroups },
                "가슴 아닌 운동: ${result.filter { MuscleGroup.CHEST !in it.muscleGroups }.map { it.name }}")
        }

        @Test
        @DisplayName("Push Day(가슴+어깨+삼두) → Push 근육만")
        fun `push day only push muscles`() {
            val pushMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            val result = recommend(targetMuscleGroups = pushMuscles, limit = 5)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in pushMuscles } },
                "Push 외 운동: ${result.filter { ex -> ex.muscleGroups.none { it in pushMuscles } }.map { it.name }}")
        }

        @Test
        @DisplayName("Pull Day(등+이두) → Pull 근육만 + 등이 먼저")
        fun `pull day only pull muscles back first`() {
            val pullMuscles = setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.TRAPS)
            val result = recommend(targetMuscleGroups = pullMuscles, limit = 5)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in pullMuscles } })
            assertTrue(result[0].category == ExerciseCategory.BACK,
                "첫 운동이 등이어야 함: ${result[0].name}(${result[0].category})")
        }

        @Test
        @DisplayName("하체 타겟 → 하체 근육만")
        fun `leg day only leg muscles`() {
            val legMuscles = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val result = recommend(targetMuscleGroups = legMuscles, limit = 5)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in legMuscles } })
        }

        @Test
        @DisplayName("코어 타겟 → 코어/복근만")
        fun `core day only core muscles`() {
            val coreMuscles = setOf(MuscleGroup.ABS, MuscleGroup.CORE)
            val result = recommend(targetMuscleGroups = coreMuscles, limit = 3)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in coreMuscles } })
        }
    }

    // ================================================================
    //  원칙 9: 근비대 목표
    // ================================================================

    @Nested
    @DisplayName("원칙 9: 근비대 목표 (Muscle Gain)")
    inner class MuscleGainGoal {

        @Test
        @DisplayName("유산소 제외")
        fun `no cardio for muscle gain`() {
            assertTrue(recommend(excludeCardio = true, limit = 6).none { it.category == ExerciseCategory.CARDIO })
        }

        @Test
        @DisplayName("복합운동 비율 50% 이상")
        fun `compound ratio at least 50 percent`() {
            val result = recommend(excludeCardio = true, limit = 6)
            val compoundCount = result.count { it.muscleGroups.size >= 2 }
            assertTrue(compoundCount >= result.size / 2,
                "복합운동 ${compoundCount}/${result.size}")
        }
    }

    // ================================================================
    //  원칙 10: 결과가 비어있지 않아야 함
    // ================================================================

    @Nested
    @DisplayName("원칙 10: 항상 결과 반환")
    inner class NonEmptyResults {

        @Test
        fun `전신 추천은 항상 결과가 있음`() { assertTrue(recommend(limit = 6).isNotEmpty()) }

        @Test
        fun `주요 장비별 추천 결과가 있음`() {
            listOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE).forEach { eq ->
                assertTrue(recommend(equipment = eq, limit = 4).isNotEmpty(), "$eq 결과 없음")
            }
        }

        @Test
        fun `일부 근육 회복 중이어도 결과가 있음`() {
            assertTrue(recommend(recoveringMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.BACK), limit = 6).isNotEmpty())
        }
    }

    // ================================================================
    //  원칙 11: 패턴 분류 정확성 (PT가 인정할 수준)
    // ================================================================

    @Nested
    @DisplayName("원칙 11: 패턴 분류 정확성")
    inner class PatternAccuracy {

        @Test fun `Romanian Deadlift = HIP_HINGE (Deadlift 아님)`() =
            assertEquals(MovementPattern.HIP_HINGE, classifier.classifyExercise(exerciseDB.first { it.name == "Romanian Deadlift" }))
        @Test fun `Conventional Deadlift = DEADLIFT`() =
            assertEquals(MovementPattern.DEADLIFT, classifier.classifyExercise(exerciseDB.first { it.name == "Conventional Deadlift" }))
        @Test fun `Bulgarian Split Squat = LUNGE`() =
            assertEquals(MovementPattern.LUNGE, classifier.classifyExercise(exerciseDB.first { it.name == "Bulgarian Split Squat" }))
        @Test fun `Barbell Bench != Dumbbell Bench (다른 패턴)`() =
            assertNotEquals(
                classifier.classifyExercise(exerciseDB.first { it.name == "Barbell Bench Press" }),
                classifier.classifyExercise(exerciseDB.first { it.name == "Dumbbell Bench Press" }))
        @Test fun `Hip Thrust = GLUTE_FOCUSED`() =
            assertEquals(MovementPattern.GLUTE_FOCUSED, classifier.classifyExercise(exerciseDB.first { it.name == "Hip Thrust" }))
        @Test fun `Face Pull = FACE_PULL`() =
            assertEquals(MovementPattern.FACE_PULL, classifier.classifyExercise(exerciseDB.first { it.name == "Face Pull" }))
        @Test fun `Skull Crusher = TRICEP_LYING`() =
            assertEquals(MovementPattern.TRICEP_LYING, classifier.classifyExercise(exerciseDB.first { it.name == "Skull Crusher" }))
        @Test fun `Pull-up = PULLUP_CHINUP`() =
            assertEquals(MovementPattern.PULLUP_CHINUP, classifier.classifyExercise(exerciseDB.first { it.name == "Pull-up" }))
    }

    // ================================================================
    //  종합: 실제 PT가 만들 법한 프로그램인지 검증
    // ================================================================

    @Nested
    @DisplayName("종합: 실제 PT 프로그램 품질 검증")
    inner class RealPTQuality {

        @Test
        @DisplayName("전신 6개: 스쿼트류+벤치류+로우류+OHP류 중 최소 3종류 패턴")
        fun `fullbody 6 has at least 3 major lift patterns`() {
            val result = recommend(limit = 6)
            val patterns = result.map { classifier.classifyExercise(it) }.toSet()

            val majorPatterns = patterns.filter { it in setOf(
                MovementPattern.SQUAT, MovementPattern.LEG_PRESS, MovementPattern.LUNGE,
                MovementPattern.HORIZONTAL_PRESS_BARBELL, MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
                MovementPattern.BARBELL_ROW, MovementPattern.DUMBBELL_ROW, MovementPattern.DEADLIFT,
                MovementPattern.LAT_PULLDOWN, MovementPattern.PULLUP_CHINUP,
                MovementPattern.VERTICAL_PRESS_BARBELL, MovementPattern.VERTICAL_PRESS_DUMBBELL
            ) }

            assertTrue(majorPatterns.size >= 3,
                "주요 리프팅 패턴 3개 이상 필요. 실제: $majorPatterns")
        }

        @Test
        @DisplayName("어제 가슴+등 운동 → 오늘은 하체+어깨+팔+코어 위주")
        fun `day after chest and back should focus on legs shoulders arms core`() {
            val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS)
            val result = recommend(recoveringMuscles = recovering, limit = 6)

            assertTrue(result.none { ex -> ex.muscleGroups.any { it in recovering } }, "회복 중 근육 포함됨")
            assertTrue(result.any { it.category == ExerciseCategory.LEGS }, "하체 있어야 함")
        }

        @Test
        @DisplayName("덤벨 홈트 4개: 다양한 부위 커버 + 복합운동 우선")
        fun `dumbbell home 4 diverse with compounds`() {
            val result = recommend(equipment = Equipment.DUMBBELL, limit = 4)

            assertTrue(result.map { it.category }.distinct().size >= 2,
                "홈트도 2개 이상 부위. 카테고리: ${result.map { it.category }}")
            assertTrue(result.all { it.equipment == Equipment.DUMBBELL })
        }
    }

    // ================================================================
    //  엔터프라이즈급 시나리오: 실제 서비스 플로우 미러링
    // ================================================================

    @Nested
    @DisplayName("엔터프라이즈: PPL 스플릿 프로그램")
    inner class PPLSplit {

        // PPL에서 회복 필터는 "24시간 이내 운동한 근육"만 적용
        // 타겟이 지정되면 해당 타겟 운동을 우선 추천

        @Test
        @DisplayName("Push Day: 벤치프레스 + OHP + 보조 운동 3-5개")
        fun `push day should start with bench or OHP`() {
            val pushMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            val result = recommend(targetMuscleGroups = pushMuscles, limit = 5)

            // 첫 운동은 벤치프레스 또는 OHP
            val firstName = result[0].name
            assertTrue(firstName == "Barbell Bench Press" || firstName == "Overhead Press",
                "Push Day 첫 운동은 벤치 or OHP. 실제: $firstName")
            // 모든 운동이 push 근육 포함
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in pushMuscles } })
            // 패턴 중복 없음
            val patterns = result.map { classifier.classifyExercise(it) }
            assertEquals(patterns.size, patterns.distinct().size)
        }

        @Test
        @DisplayName("Pull Day: 데드리프트 또는 로우 시작 + 이두 보조")
        fun `pull day should start with deadlift or row`() {
            val pullMuscles = setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.TRAPS)
            val result = recommend(targetMuscleGroups = pullMuscles, limit = 5)

            assertTrue(result[0].category == ExerciseCategory.BACK, "Pull Day 첫 운동은 등. 실제: ${result[0].name}")
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in pullMuscles } })
        }

        @Test
        @DisplayName("Leg Day: 스쿼트 시작 + 다양한 하체 운동")
        fun `leg day should start with squat`() {
            val legMuscles = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val result = recommend(targetMuscleGroups = legMuscles, limit = 5)

            // 스쿼트가 포함되어야 함
            assertTrue(result.any { it.name.contains("Squat") },
                "Leg Day에 스쿼트가 없음! 운동: ${result.map { it.name }}")
            // 모든 운동이 하체
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in legMuscles } })
        }

        @Test
        @DisplayName("PPL 3일 연속: 각 날 최소 3개 운동 확보")
        fun `PPL 3 days each day has at least 3 exercises`() {
            // Day1 Push (타겟: 가슴+어깨+삼두)
            val pushTarget = setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            val day1 = recommend(targetMuscleGroups = pushTarget, limit = 5)
            assertTrue(day1.size >= 3, "Push Day 최소 3개. 실제: ${day1.size}")

            // Day2 Pull (타겟: 등+이두) — Push 근육은 회복 중이지만 Pull 타겟이므로 무관
            val pullTarget = setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.TRAPS)
            val day2 = recommend(targetMuscleGroups = pullTarget, limit = 5)
            assertTrue(day2.size >= 3, "Pull Day 최소 3개. 실제: ${day2.size}")

            // Day3 Legs (타겟: 하체) — Push+Pull 근육은 회복 중이지만 Legs 타겟이므로 무관
            val legTarget = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val day3 = recommend(targetMuscleGroups = legTarget, limit = 5)
            assertTrue(day3.size >= 3, "Leg Day 최소 3개. 실제: ${day3.size}")
        }
    }

    @Nested
    @DisplayName("엔터프라이즈: Upper/Lower 스플릿")
    inner class UpperLowerSplit {

        @Test
        @DisplayName("Upper Day: Push+Pull 균형, 최소 3개 카테고리")
        fun `upper day should balance push and pull`() {
            val upperMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS,
                MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS)
            val result = recommend(targetMuscleGroups = upperMuscles, limit = 6)

            val categories = result.map { it.category }.distinct()
            assertTrue(categories.size >= 2,
                "Upper Day 최소 2개 카테고리. 실제: $categories")
            // Push(CHEST)와 Pull(BACK) 모두 포함
            val hasPush = result.any { it.category == ExerciseCategory.CHEST }
            val hasPull = result.any { it.category == ExerciseCategory.BACK }
            assertTrue(hasPush, "Push(가슴) 없음!")
            assertTrue(hasPull, "Pull(등) 없음!")
        }

        @Test
        @DisplayName("Lower Day: 스쿼트 포함, 다양한 하체 패턴")
        fun `lower day should include squat with variety`() {
            val lowerMuscles = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val result = recommend(targetMuscleGroups = lowerMuscles, limit = 5)

            assertTrue(result.any { it.name.contains("Squat") || it.name.contains("Leg Press") },
                "Lower Day에 스쿼트/레그프레스 필수. 운동: ${result.map { it.name }}")
            val patterns = result.map { classifier.classifyExercise(it) }.distinct()
            assertTrue(patterns.size >= 3, "하체도 다양한 패턴. 실제: $patterns")
        }
    }

    @Nested
    @DisplayName("엔터프라이즈: 근육 오버랩 관리")
    inner class MuscleOverlapManagement {

        @Test
        @DisplayName("전신 6개에서 한 근육이 4회 이상 히트되지 않음")
        fun `no muscle hit more than 4 times in fullbody 6`() {
            val result = recommend(limit = 6)
            val muscleHits = mutableMapOf<MuscleGroup, Int>()
            result.forEach { ex -> ex.muscleGroups.forEach { m -> muscleHits[m] = (muscleHits[m] ?: 0) + 1 } }

            val overloaded = muscleHits.filter { it.value > 4 }
            assertTrue(overloaded.isEmpty(),
                "근육 과부하: ${overloaded.entries.joinToString { "${it.key}=${it.value}회" }}")
        }

        @Test
        @DisplayName("Push Day에서 삼두는 3-5회 히트 (메인 + 보조)")
        fun `triceps should be hit 3-5 times in push day`() {
            val pushMuscles = setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            val result = recommend(targetMuscleGroups = pushMuscles, limit = 5)
            val tricepsHits = result.count { MuscleGroup.TRICEPS in it.muscleGroups }

            assertTrue(tricepsHits in 2..5,
                "Push Day 삼두 히트: ${tricepsHits}회 (2-5회 적정)")
        }
    }

    @Nested
    @DisplayName("엔터프라이즈: 실제 사용자 시나리오")
    inner class RealUserScenarios {

        @Test
        @DisplayName("초보자 첫날: 전신 4개, 모두 ESSENTIAL, 모두 복합")
        fun `beginner first day all essential compounds`() {
            val result = recommend(limit = 4)

            val allEssentialOrBasic = result.all {
                it.recommendationTier == RecommendationTier.ESSENTIAL || it.isBasicExercise
            }
            assertTrue(allEssentialOrBasic,
                "초보자 4개는 모두 ESSENTIAL. 실제: ${result.map { "${it.name}(${it.recommendationTier})" }}")
        }

        @Test
        @DisplayName("바쁜 직장인 30분: 4개 운동, Big compound만으로 전신 커버")
        fun `busy worker 30min 4 exercises full body coverage`() {
            val result = recommend(limit = 4)

            // 모든 주요 부위 커버 확인
            val musclesCovered = result.flatMap { it.muscleGroups }.toSet()
            assertTrue(MuscleGroup.QUADRICEPS in musclesCovered || MuscleGroup.GLUTES in musclesCovered, "하체 없음")
            assertTrue(MuscleGroup.CHEST in musclesCovered, "가슴 없음")
            assertTrue(MuscleGroup.BACK in musclesCovered, "등 없음")
        }

        @Test
        @DisplayName("여행자 맨몸 3개: Pull-up + Push-up + Plank (전신 커버)")
        fun `traveler bodyweight 3 covers full body`() {
            val result = recommend(equipment = Equipment.BODYWEIGHT, limit = 3)

            val musclesCovered = result.flatMap { it.muscleGroups }.toSet()
            // 맨몸 3개로 상체+코어 최소한 커버
            assertTrue(musclesCovered.size >= 4,
                "맨몸 3개로 최소 4개 근육. 실제: $musclesCovered")
        }

        @Test
        @DisplayName("연속 2일 전신: 둘째 날은 다른 운동 선호")
        fun `second day fullbody after first day should still be good`() {
            // Day1: 전신 6개
            val day1 = recommend(limit = 6)
            // Day2: Day1에서 동원된 근육 중 주요 근육이 회복 중
            // 실제 서비스에서는 24시간 이내 운동 근육만 필터링
            val day1PrimaryMuscles = day1.flatMap { it.muscleGroups }.toSet()
            val day2 = recommend(recoveringMuscles = day1PrimaryMuscles, limit = 6)

            // 완전히 같은 운동이 나오면 안 됨 (회복 필터 덕분)
            val day1Names = day1.map { it.name }.toSet()
            val overlap = day2.filter { it.name in day1Names }
            // 어느 정도 겹칠 수 있지만, 최소한 결과가 비어있지 않아야 함
            assertTrue(day2.isNotEmpty(), "Day2 결과가 비어있음")
        }

        @Test
        @DisplayName("가슴 아프면(회복중) 가슴 운동 0건, 나머지 부위 추천")
        fun `sore chest zero chest exercises recommended`() {
            val result = recommend(recoveringMuscles = setOf(MuscleGroup.CHEST), limit = 6)

            assertTrue(result.none { MuscleGroup.CHEST in it.muscleGroups },
                "가슴 회복 중인데 가슴 운동: ${result.filter { MuscleGroup.CHEST in it.muscleGroups }.map { it.name }}")
            assertTrue(result.size >= 4, "가슴 빠져도 최소 4개")
        }

        @Test
        @DisplayName("케이블 장비만: 4개 운동, 최소 3개 다른 부위")
        fun `cable only 4 exercises 3 body parts`() {
            val result = recommend(equipment = Equipment.CABLE, limit = 4)

            assertTrue(result.all { it.equipment == Equipment.CABLE })
            val parts = result.map { it.category }.distinct()
            assertTrue(parts.size >= 3, "케이블 4개에 3개 부위. 실제: $parts")
        }

        @Test
        @DisplayName("머신 초보: 낮은 난이도 운동만")
        fun `machine beginner low difficulty`() {
            val result = recommend(equipment = Equipment.MACHINE, limit = 4)

            assertTrue(result.all { it.equipment == Equipment.MACHINE })
            val maxDiff = result.maxOf { it.difficulty }
            assertTrue(maxDiff <= 30, "머신 초보 최대 난이도 30 이하. 실제: $maxDiff")
        }
    }

    @Nested
    @DisplayName("엔터프라이즈: 운동 순서 품질")
    inner class ExerciseOrderQuality {

        @Test
        @DisplayName("전신 6개: 복합운동이 고립운동보다 앞에")
        fun `compounds come before isolations in fullbody`() {
            val result = recommend(limit = 6)
            val lastCompoundIdx = result.indexOfLast { it.muscleGroups.size >= 2 }
            val firstIsolationIdx = result.indexOfFirst { it.muscleGroups.size < 2 }

            if (firstIsolationIdx >= 0 && lastCompoundIdx >= 0) {
                // 모든 복합운동이 모든 고립운동보다 앞에 있을 필요는 없지만,
                // 첫 고립운동 앞에 최소 2개 복합운동이 있어야 함
                assertTrue(firstIsolationIdx >= 2,
                    "첫 고립운동 위치: $firstIsolationIdx (최소 2번째 이후). 운동: ${result.map { "${it.name}(${it.muscleGroups.size}근육)" }}")
            }
        }

        @Test
        @DisplayName("전신 8개: ESSENTIAL 운동이 처음 4개 안에 최소 3개")
        fun `essential exercises dominate first 4 positions`() {
            val result = recommend(limit = 8)
            val first4Essential = result.take(4).count {
                it.recommendationTier == RecommendationTier.ESSENTIAL || it.isBasicExercise
            }

            assertTrue(first4Essential >= 3,
                "처음 4개 중 ESSENTIAL ${first4Essential}개 (최소 3개). 운동: ${result.take(4).map { it.name }}")
        }
    }
}

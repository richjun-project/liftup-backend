package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.PersonalRecordRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.exception.BadRequestException
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * WorkoutServiceV2에서 분리된 운동 카탈로그/상세 조회 서비스.
 * 운동 목록, 운동 상세(개인기록/1RM/설명), GIF·썸네일·비디오 URL 생성을 담당한다.
 */
@Service
@Transactional
class ExerciseCatalogDetailService(
    private val userRepository: UserRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
    private val exercisePatternClassifier: ExercisePatternClassifier,
    private val exerciseCatalogLocalizationService: ExerciseCatalogLocalizationService,
    private val sharedSupport: WorkoutV2SharedSupport,
    private val exerciseSearchIndexService: ExerciseSearchIndexService
) {
    @Value("\${app.exercise-media.base-url:https://liftup-cdn.com}")
    private var exerciseMediaBaseUrl: String = "https://liftup-cdn.com"

    /**
     * 운동 목록(페이징).
     * - 검색어 없음: 추천 티어(ESSENTIAL > STANDARD > SPECIALIZED) → 인기도 → 로컬라이즈 이름 순
     * - 검색어 있음: 다국어 검색 엔진의 관련도 순 (카테고리/장비 필터는 그대로 적용)
     * equipment 는 쉼표로 구분한 enum 이름 목록(DUMBBELL,BODYWEIGHT)도 받는다.
     */
    @Transactional(readOnly = true)
    fun getExercisesV2Paged(
        category: String?,
        equipment: String?,
        hasGif: Boolean,
        localeOverride: String?,
        search: String?,
        pageable: org.springframework.data.domain.Pageable
    ): Map<String, Any> {
        val locale = sharedSupport.resolveLocale(null, localeOverride)
        val cat = category?.takeIf { it.isNotBlank() }?.let { parseCategory(it) }
        val equipments = parseEquipments(equipment)

        val rankedIds = exerciseSearchIndexService.searchIds(search, cat, equipments, locale)
        val totalElements = rankedIds.size.toLong()
        val pageSize = pageable.pageSize
        val totalPages = if (rankedIds.isEmpty()) 0 else (rankedIds.size + pageSize - 1) / pageSize
        val from = pageable.offset.coerceAtMost(rankedIds.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(rankedIds.size)
        val pageIds = rankedIds.subList(from, to)

        val byId = exerciseRepository.findAllById(pageIds).associateBy { it.id }
        val exercises = pageIds.mapNotNull { byId[it] }
        val translations = sharedSupport.translationMap(exercises, locale)
        val content = exercises.map { toExerciseDetail(it, locale, translations, hasGif) }

        return mapOf(
            "content" to content,
            "totalElements" to totalElements,
            "totalPages" to totalPages,
            "number" to pageable.pageNumber,
            "last" to (pageable.pageNumber >= totalPages - 1),
            "size" to pageSize
        )
    }

    @Transactional(readOnly = true)
    fun getExercisesV2(category: String?, equipment: String?, hasGif: Boolean, localeOverride: String?): List<ExerciseDetailV2> {
        val locale = sharedSupport.resolveLocale(null, localeOverride)
        val cat = category?.takeIf { it.isNotBlank() }?.let { parseCategory(it) }
        val equipments = parseEquipments(equipment)
        val base = if (cat != null) exerciseRepository.findByCategory(cat) else exerciseRepository.findAll()
        val exercises = if (equipments.isNullOrEmpty()) base else base.filter { it.equipment in equipments }
        val translations = sharedSupport.translationMap(exercises, locale)

        return exercises.map { toExerciseDetail(it, locale, translations, hasGif) }
    }

    private fun parseCategory(raw: String): ExerciseCategory {
        return try {
            ExerciseCategory.valueOf(raw.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid category: $raw")
        }
    }

    /** "DUMBBELL,BODYWEIGHT" 처럼 쉼표로 구분된 장비 목록 (단일 값도 허용). 비어 있으면 null = 필터 없음 */
    private fun parseEquipments(raw: String?): Set<Equipment>? {
        val tokens = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (tokens.isEmpty()) return null
        return tokens.map {
            try {
                Equipment.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("Invalid equipment: $it")
            }
        }.toSet()
    }

    /** 운동 목록 → 응답 DTO (추천·대체 운동 등 다른 서비스가 같은 목록 형식을 쓰도록 공개) */
    @Transactional(readOnly = true)
    fun toExerciseDetails(exercises: List<Exercise>, locale: String, hasGif: Boolean = false): List<ExerciseDetailV2> {
        val translations = sharedSupport.translationMap(exercises, locale)
        return exercises.map { toExerciseDetail(it, locale, translations, hasGif) }
    }

    /** Exercise.difficulty(1-100) → beginner/intermediate/advanced (1-30 / 31-60 / 61+) */
    private fun difficultyKey(score: Int): String = when {
        score <= 30 -> "beginner"
        score <= 60 -> "intermediate"
        else -> "advanced"
    }

    private fun toExerciseDetail(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>,
        hasGif: Boolean
    ): ExerciseDetailV2 {
        return ExerciseDetailV2(
            id = exercise.id,
            name = sharedSupport.localizedName(exercise, locale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
            equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
            imageUrl = if (hasGif) generateGifUrl(exercise) else null,
            thumbnailUrl = generateThumbnailUrl(exercise),
            videoUrl = generateVideoUrl(exercise),
            difficulty = WorkoutLocalization.difficultyDisplayName(difficultyKey(exercise.difficulty), locale),
            description = sharedSupport.localizedInstructions(exercise, locale, translations),
            tier = exercise.recommendationTier.name,
            equipmentCode = exercise.equipment?.name,
            muscleGroupCodes = exercise.muscleGroups.map { it.name }.sorted(),
            popularity = exercise.popularity
        )
    }

    @Transactional(readOnly = true)
    fun getExerciseDetailsV2(userId: Long, exerciseId: Long, localeOverride: String?): ExerciseDetailResponseV2 {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = sharedSupport.resolveLocale(userId, localeOverride)

        val exercise = exerciseRepository.findById(exerciseId)
            .orElseThrow { ResourceNotFoundException("Exercise not found") }
        val translations = sharedSupport.translationMap(listOf(exercise), locale)

        val personalRecord = personalRecordRepository.findTopByUserAndExerciseOrderByWeightDesc(user, exercise)

        val lastSession = workoutSessionRepository.findTopByUserOrderByStartTimeDesc(user)
        val lastPerformed = if (lastSession != null) {
            val exercises = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(lastSession.id)
            exercises.find { it.exercise.id == exerciseId }?.let {
                lastSession.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
        } else null

        val totalSets = workoutExerciseRepository.countSetsByUserAndExercise(user.id, exerciseId) ?: 0
        val avgWeight = workoutExerciseRepository.calculateAverageWeight(user.id, exerciseId) ?: 0.0

        val exerciseDetail = ExerciseDetailV2(
            id = exercise.id,
            name = sharedSupport.localizedName(exercise, locale, translations),
            category = exercise.category.name,
            muscleGroups = exercise.muscleGroups.map { WorkoutLocalization.muscleGroupName(it, locale) },
            equipment = exercise.equipment?.let { WorkoutLocalization.equipmentName(it.name, locale) },
            imageUrl = generateGifUrl(exercise),
            thumbnailUrl = generateThumbnailUrl(exercise),
            videoUrl = generateVideoUrl(exercise),
            difficulty = WorkoutLocalization.difficultyDisplayName(difficultyKey(exercise.difficulty), locale),
            description = sharedSupport.localizedInstructions(exercise, locale, translations),
            tier = exercise.recommendationTier.name,
            equipmentCode = exercise.equipment?.name,
            muscleGroupCodes = exercise.muscleGroups.map { it.name }.sorted(),
            popularity = exercise.popularity,
            instructions = localizedLines(sharedSupport.localizedInstructions(exercise, locale, translations)) ?: generateInstructions(exercise, locale),
            tips = localizedLines(localizedTips(exercise, locale, translations)) ?: generateTips(exercise, locale),
            commonMistakes = generateCommonMistakes(exercise, locale),
            breathing = generateBreathingGuide(locale)
        )

        return ExerciseDetailResponseV2(
            exercise = exerciseDetail,
            userStats = UserExerciseStats(
                personalRecord = personalRecord?.let {
                    com.richjun.liftupai.domain.workout.dto.PersonalRecord(
                        weight = it.weight,
                        reps = it.reps,
                        date = it.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    )
                },
                lastPerformed = lastPerformed,
                totalSets = totalSets,
                averageWeight = avgWeight,
                estimatedOneRepMax = personalRecord?.let { calculate1RM(it.weight, it.reps) } ?: 0.0
            )
        )
    }

    /**
     * Brzycki 공식을 사용한 1RM 계산
     * 1RM = weight / (1.0278 - 0.0278 × reps)
     *
     * 과학적 근거: Journal of Strength and Conditioning Research
     * 정확도: ±2% (1-10 reps 범위)
     *
     * @param weight 수행한 무게 (kg)
     * @param reps 반복 횟수 (1-10 권장, 최대 15)
     * @return 추정 1RM (kg)
     */
    private fun calculate1RM(weight: Double, reps: Int): Double {
        if (reps == 1) return weight
        if (reps > 15) {
            // 15회 이상은 근지구력 영역으로 1RM 추정 부정확
            // Epley 공식 사용 (Brzycki보다 보수적)
            return weight * (1 + reps / 30.0)
        }

        // Brzycki 공식 (가장 정확)
        val oneRM = weight / (1.0278 - 0.0278 * reps)

        // NaN 체크 및 범위 검증
        return if (oneRM.isNaN() || oneRM.isInfinite() || oneRM < weight) {
            weight // 계산 오류 시 최소한 수행 무게 반환
        } else {
            oneRM.coerceAtMost(weight * 2.0) // 안전 상한선: 수행 무게의 2배
        }
    }

    private fun generateGifUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "animation.gif")
    }

    private fun generateThumbnailUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "thumb.jpg")
    }

    private fun generateVideoUrl(exercise: Exercise): String {
        return buildExerciseMediaUrl(exercise, "video.mp4")
    }

    private fun buildExerciseMediaUrl(exercise: Exercise, fileName: String): String {
        return "${exerciseMediaBaseUrl.trimEnd('/')}/exercises/${exercise.slug}/$fileName"
    }

    private fun generateInstructions(exercise: Exercise, locale: String): List<String> {
        val parsedInstructions = exercise.instructions
            ?.split("\n", ".")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        if (!parsedInstructions.isNullOrEmpty()) {
            return parsedInstructions.take(4)
        }

        return when (exercisePatternClassifier.classifyExercise(exercise)) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.VERTICAL_PRESS_MACHINE -> localizedMessages(
                locale,
                "exercise.instructions.press.1",
                "exercise.instructions.press.2",
                "exercise.instructions.press.3"
            )
            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LUNGE,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> localizedMessages(
                locale,
                "exercise.instructions.squat.1",
                "exercise.instructions.squat.2",
                "exercise.instructions.squat.3"
            )
            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> localizedMessages(
                locale,
                "exercise.instructions.hinge.1",
                "exercise.instructions.hinge.2",
                "exercise.instructions.hinge.3"
            )
            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN,
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP -> localizedMessages(
                locale,
                "exercise.instructions.row_pull.1",
                "exercise.instructions.row_pull.2",
                "exercise.instructions.row_pull.3"
            )
            else -> localizedMessages(locale, "exercise.instructions.generic.1")
        }
    }

    private fun generateTips(exercise: Exercise, locale: String): List<String> {
        return when (exercise.category) {
            ExerciseCategory.CHEST -> localizedMessages(
                locale,
                "exercise.tips.chest.1",
                "exercise.tips.chest.2",
                "exercise.tips.chest.3"
            )
            else -> localizedMessages(locale, "exercise.tips.generic.1")
        }
    }

    private fun generateCommonMistakes(exercise: Exercise, locale: String): List<String> {
        return when (exercisePatternClassifier.classifyExercise(exercise)) {
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_BARBELL,
            ExercisePatternClassifier.MovementPattern.INCLINE_PRESS_DUMBBELL -> localizedMessages(
                locale,
                "exercise.mistakes.press.1",
                "exercise.mistakes.press.2",
                "exercise.mistakes.press.3"
            )
            ExercisePatternClassifier.MovementPattern.SQUAT,
            ExercisePatternClassifier.MovementPattern.LUNGE,
            ExercisePatternClassifier.MovementPattern.LEG_PRESS -> localizedMessages(
                locale,
                "exercise.mistakes.squat.1",
                "exercise.mistakes.squat.2",
                "exercise.mistakes.squat.3"
            )
            ExercisePatternClassifier.MovementPattern.HIP_HINGE,
            ExercisePatternClassifier.MovementPattern.DEADLIFT -> localizedMessages(
                locale,
                "exercise.mistakes.hinge.1",
                "exercise.mistakes.hinge.2",
                "exercise.mistakes.hinge.3"
            )
            ExercisePatternClassifier.MovementPattern.BARBELL_ROW,
            ExercisePatternClassifier.MovementPattern.DUMBBELL_ROW,
            ExercisePatternClassifier.MovementPattern.CABLE_ROW,
            ExercisePatternClassifier.MovementPattern.LAT_PULLDOWN,
            ExercisePatternClassifier.MovementPattern.PULLUP_CHINUP -> localizedMessages(
                locale,
                "exercise.mistakes.row_pull.1",
                "exercise.mistakes.row_pull.2",
                "exercise.mistakes.row_pull.3"
            )
            else -> localizedMessages(
                locale,
                "exercise.mistakes.generic.1",
                "exercise.mistakes.generic.2"
            )
        }
    }

    private fun generateBreathingGuide(locale: String): String {
        return WorkoutLocalization.message("exercise.breathing.default", locale)
    }

    private fun localizedTips(
        exercise: Exercise,
        locale: String,
        translations: Map<Long, ExerciseTranslation>
    ): String? {
        return exerciseCatalogLocalizationService.tips(exercise, locale, translations)
    }

    private fun localizedLines(text: String?): List<String>? {
        return text
            ?.split("\n", ".")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(4)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun localizedMessages(locale: String, vararg keys: String): List<String> {
        return keys.map { key -> WorkoutLocalization.message(key, locale) }
    }
}

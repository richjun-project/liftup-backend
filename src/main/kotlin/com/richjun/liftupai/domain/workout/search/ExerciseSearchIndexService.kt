package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.repository.ExerciseTranslationRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * 운동 검색 인덱스(ExerciseSearchEngine)를 DB 에서 만들고 캐시한다.
 * - 첫 사용 시 지연 빌드, ExerciseCatalogChangedEvent 수신 시 무효화
 * - 수동 DB 수정 등을 놓치지 않도록 TTL 이 지나면 다음 요청에서 다시 빌드
 */
@Service
class ExerciseSearchIndexService(
    private val exerciseRepository: ExerciseRepository,
    private val exerciseTranslationRepository: ExerciseTranslationRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private class Snapshot(val engine: ExerciseSearchEngine, val builtAtMillis: Long)

    @Volatile
    private var snapshot: Snapshot? = null
    private val lock = Any()

    fun engine(): ExerciseSearchEngine {
        val current = snapshot
        if (current != null && System.currentTimeMillis() - current.builtAtMillis < TTL_MILLIS) return current.engine
        synchronized(lock) {
            val again = snapshot
            if (again != null && System.currentTimeMillis() - again.builtAtMillis < TTL_MILLIS) return again.engine
            val built = build()
            snapshot = Snapshot(built, System.currentTimeMillis())
            return built
        }
    }

    @EventListener(ExerciseCatalogChangedEvent::class)
    fun invalidate() {
        snapshot = null
    }

    /** 질의 검색(질의가 비면 브라우즈) — 결과는 점수 순 운동 id 목록 */
    fun searchIds(
        query: String?,
        category: ExerciseCategory?,
        equipments: Set<Equipment>?,
        locale: String?
    ): List<Long> = engine().search(query, category, equipments, locale).map { it.doc.id }

    /** 노출 가능한 운동(ESSENTIAL + STANDARD)만, 브라우즈 순서(티어 → 인기도 → 이름)로 */
    fun listableIds(category: ExerciseCategory?, locale: String?): List<Long> =
        engine().browse(category, null, locale)
            .filter { it.doc.tier == RecommendationTier.ESSENTIAL || it.doc.tier == RecommendationTier.STANDARD }
            .map { it.doc.id }

    private fun build(): ExerciseSearchEngine {
        val started = System.currentTimeMillis()
        val exercises = exerciseRepository.findAllWithMuscleGroups()
        val namesByExercise = HashMap<Long, MutableMap<String, String>>()
        exerciseTranslationRepository.findAllNameRows().forEach { row ->
            val id = (row[0] as Number).toLong()
            namesByExercise.getOrPut(id) { linkedMapOf() }[(row[1] as String).lowercase()] = row[2] as String
        }
        val docs = exercises.map { toDoc(it, namesByExercise[it.id].orEmpty()) }
        logger.info("Exercise search index built: {} docs in {}ms", docs.size, System.currentTimeMillis() - started)
        return ExerciseSearchEngine(docs)
    }

    private fun toDoc(e: Exercise, translated: Map<String, String>): ExerciseSearchDoc {
        val names = LinkedHashMap<String, String>()
        names.putAll(translated)
        // 번역이 없는 로케일을 대비해 기본 이름을 en(없으면 기본 로케일)으로 보존
        names.putIfAbsent(e.defaultLocale.lowercase(), e.name)
        return ExerciseSearchDoc(
            id = e.id,
            slug = e.slug,
            names = names,
            category = e.category,
            equipment = e.equipment,
            muscleGroups = e.muscleGroups.toSet(),
            movementPattern = e.movementPattern,
            tier = e.recommendationTier,
            popularity = e.popularity,
            difficulty = e.difficulty
        )
    }

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
    }
}

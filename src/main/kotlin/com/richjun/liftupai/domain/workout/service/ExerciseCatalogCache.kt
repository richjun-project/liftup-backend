package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.search.ExerciseCatalogChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.atomic.AtomicLong

/**
 * 운동 카탈로그(엔티티)의 메모리 스냅샷. 추천·제안·대체 운동처럼 "전체를 훑어 점수를 매기는" 경로가 요청마다 DB 를 훑지 않게 한다.
 * 카탈로그는 사실상 정적이라 TTL(10분) 후 다시 읽고, ExerciseCatalogChangedEvent 로도 무효화한다.
 * - 무효화는 커밋 이후(AFTER_COMMIT)에만 한다. 빌드가 도는 사이 이벤트가 오면 그 결과는 캐시하지 않는다(세대 번호).
 * - 재빌드가 실패하면 이전 스냅샷을 계속 쓴다(WARN). 앱 기동 직후 미리 채운다.
 */
@Component
class ExerciseCatalogCache(
    private val exerciseRepository: ExerciseRepository,
) {
    class Snapshot(val all: List<Exercise>, val builtAtMillis: Long) {
        val byId: Map<Long, Exercise> = all.associateBy { it.id }

        /** 플랜 후보 풀: 카테고리별 인기도 내림차순, id 오름차순 (findPlanEligibleByCategoryOrderByPopularity 와 같은 정렬) */
        val planPool: Map<ExerciseCategory, List<Exercise>> = all.filter { it.isPlanEligible }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sortedWith(compareBy({ -it.popularity }, { it.id })) }
    }

    private val logger = LoggerFactory.getLogger(this::class.java)

    @Volatile
    private var snapshot: Snapshot? = null
    private val generation = AtomicLong()
    private val lock = Any()

    /** 스냅샷을 만든 시점의 세대 — 이 값이 현재 세대와 다르면 이벤트 이후 다시 만들어야 한다 */
    @Volatile
    private var snapshotGeneration = -1L

    @Volatile
    private var retryNotBeforeMillis = 0L

    @TransactionalEventListener(classes = [ExerciseCatalogChangedEvent::class], phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun invalidate() {
        generation.incrementAndGet()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        try {
            get()
        } catch (e: Exception) {
            logger.warn("Exercise catalog cache warm-up failed (will load on first use): {}", e.message)
        }
    }

    fun get(): Snapshot {
        val current = snapshot
        if (current != null && isFresh(current)) return current
        synchronized(lock) {
            val again = snapshot
            if (again != null && isFresh(again)) return again
            // 직전 재빌드가 실패했다면 잠시 이전 스냅샷을 그대로 쓴다
            if (again != null && System.currentTimeMillis() < retryNotBeforeMillis) return again
            repeat(MAX_ATTEMPTS) { attempt ->
                val gen = generation.get()
                val built = try {
                    Snapshot(exerciseRepository.findAllWithMuscleGroups(), System.currentTimeMillis())
                } catch (e: Exception) {
                    if (again == null) throw e
                    retryNotBeforeMillis = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS
                    logger.warn("Exercise catalog cache rebuild failed, keeping previous snapshot: {}", e.message)
                    return again
                }
                retryNotBeforeMillis = 0L
                // 빌드 도중 카탈로그 변경이 커밋되었으면 낡았을 수 있다 — 캐시하지 않고 다시 읽는다
                if (gen == generation.get()) {
                    snapshot = built
                    snapshotGeneration = gen
                    return built
                }
                if (attempt == MAX_ATTEMPTS - 1) return built
            }
            error("unreachable")
        }
    }

    private fun isFresh(s: Snapshot): Boolean =
        snapshotGeneration == generation.get() && System.currentTimeMillis() - s.builtAtMillis < TTL_MILLIS

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
        private const val FAILURE_BACKOFF_MILLIS = 30 * 1000L
        private const val MAX_ATTEMPTS = 3
    }
}

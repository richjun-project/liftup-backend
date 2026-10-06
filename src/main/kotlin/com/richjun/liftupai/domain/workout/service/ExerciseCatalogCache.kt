package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import com.richjun.liftupai.domain.workout.search.ExerciseCatalogChangedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 운동 카탈로그(엔티티)의 메모리 스냅샷. 추천·제안·대체 운동처럼 "전체를 훑어 점수를 매기는" 경로가 요청마다 DB 를 훑지 않게 한다.
 * 카탈로그는 사실상 정적이라 TTL(10분) 후 다시 읽고, ExerciseCatalogChangedEvent 로도 무효화한다.
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

    @Volatile
    private var snapshot: Snapshot? = null
    private val lock = Any()

    @EventListener(ExerciseCatalogChangedEvent::class)
    fun invalidate() {
        snapshot = null
    }

    fun get(): Snapshot {
        val current = snapshot
        if (current != null && System.currentTimeMillis() - current.builtAtMillis < TTL_MILLIS) return current
        synchronized(lock) {
            val again = snapshot
            if (again != null && System.currentTimeMillis() - again.builtAtMillis < TTL_MILLIS) return again
            return Snapshot(exerciseRepository.findAllWithMuscleGroups(), System.currentTimeMillis()).also { snapshot = it }
        }
    }

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
    }
}

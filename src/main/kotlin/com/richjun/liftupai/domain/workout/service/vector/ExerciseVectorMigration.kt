package com.richjun.liftupai.domain.workout.service.vector

import com.richjun.liftupai.domain.workout.repository.ExerciseRepository
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ExerciseVectorMigration(
    private val vectorWorkoutRecommendationService: VectorWorkoutRecommendationService,
    private val exerciseRepository: ExerciseRepository
) {

    @Value("\${vector.migration.enabled:true}")
    private var migrationEnabled: Boolean = true

    /**
     * Indexes exercises into Qdrant on startup.
     */
    @PostConstruct
    @Transactional
    fun initializeExerciseVectors() {
        if (!migrationEnabled) {
            println("Vector migration is disabled (vector.migration.enabled=false)")
            return
        }

        try {
            // 벡터 ID가 없는 운동 개수 확인
            val totalExercises = exerciseRepository.count()
            val unindexedCount = exerciseRepository.findAll().count { it.vectorId == null }

            if (unindexedCount == 0) {
                println("All exercises are already indexed ($totalExercises)")
                return
            }

            println("=" * 60)
            println("Starting exercise vector indexing...")
            println("   Total: $totalExercises, pending: $unindexedCount")
            println("=" * 60)

            vectorWorkoutRecommendationService.indexAllExercises()

            println("=" * 60)
            println("Exercise vector indexing completed")
            println("=" * 60)
        } catch (e: Exception) {
            println("Exercise vector indexing failed: ${e.message}")
            println("   Verify that the Qdrant server is running.")
            println("   docker-compose up -d qdrant")
            println("   ")
            println("   To disable migration, add the following to application.yml:")
            println("   vector:")
            println("     migration:")
            println("       enabled: false")
        }
    }

    private operator fun String.times(n: Int): String = this.repeat(n)
}

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
     * 애플리케이션 시작 시 자동으로 모든 운동을 Qdrant에 인덱싱
     * - 이미 인덱싱된 운동은 스킵 (vectorId != null)
     * - 새로운 운동만 추가
     * - application.yml에서 vector.migration.enabled=false로 비활성화 가능
     */
    @PostConstruct
    @Transactional
    fun initializeExerciseVectors() {
        if (!migrationEnabled) {
            println("⏭️  벡터 마이그레이션이 비활성화되어 있습니다 (vector.migration.enabled=false)")
            return
        }

        try {
            // 벡터 ID가 없는 운동 개수 확인
            val totalExercises = exerciseRepository.count()
            val unindexedCount = exerciseRepository.findAll().count { it.vectorId == null }

            if (unindexedCount == 0) {
                println("✅ 모든 운동이 이미 인덱싱되어 있습니다 (${totalExercises}개)")
                return
            }

            println("=" * 60)
            println("🚀 운동 벡터 인덱싱 시작...")
            println("   전체: ${totalExercises}개, 인덱싱 필요: ${unindexedCount}개")
            println("=" * 60)

            vectorWorkoutRecommendationService.indexAllExercises()

            println("=" * 60)
            println("✅ 운동 벡터 인덱싱 완료!")
            println("=" * 60)
        } catch (e: Exception) {
            println("⚠️ 운동 벡터 인덱싱 실패: ${e.message}")
            println("   Qdrant 서버가 실행 중인지 확인하세요.")
            println("   docker-compose up -d qdrant")
            println("   ")
            println("   마이그레이션을 비활성화하려면 application.yml에 다음을 추가:")
            println("   vector:")
            println("     migration:")
            println("       enabled: false")
            // 실패해도 애플리케이션은 계속 실행
        }
    }

    private operator fun String.times(n: Int): String = this.repeat(n)
}

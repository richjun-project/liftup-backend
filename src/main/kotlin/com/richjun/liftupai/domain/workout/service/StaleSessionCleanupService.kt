package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutExerciseRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 시작만 하고 방치된 운동 세션을 ABANDONED 로 닫는다.
 *
 * 2026-09-13~14 실사용: 신규 사용자 3명이 운동 시작 후 1분 안에 나갔고 세션이 IN_PROGRESS 로 남아
 * 홈에 "이어하기" 카드가 영원히 떠 있었다(이미 안 할 운동을 이어하라고 계속 보여주는 셈).
 * 세트를 하나라도 완료한 세션은 사용자가 돌아올 수 있으니 더 길게(24h) 둔다.
 */
@Service
class StaleSessionCleanupService(
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val workoutExerciseRepository: WorkoutExerciseRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    @Transactional
    fun abandonStaleSessions() {
        val now = AppTime.utcNow()
        val untouchedCutoff = now.minusHours(UNTOUCHED_HOURS)
        val anyCutoff = now.minusHours(MAX_HOURS)
        val stale = workoutSessionRepository.findAllByStatusAndStartTimeBefore(SessionStatus.IN_PROGRESS, untouchedCutoff)
        var closed = 0
        for (s in stale) {
            val anySetDone = workoutExerciseRepository.findBySessionIdOrderByOrderInSession(s.id)
                .any { ex -> ex.sets.any { set -> set.completed } }
            if (anySetDone && s.startTime.isAfter(anyCutoff)) continue
            s.status = SessionStatus.ABANDONED
            s.endTime = now
            closed++
        }
        if (closed > 0) log.info("[StaleSession] abandoned {} stale IN_PROGRESS session(s)", closed)
    }

    companion object {
        /** 세트를 하나도 완료하지 않은 세션은 이 시간이 지나면 닫는다 */
        const val UNTOUCHED_HOURS = 6L
        /** 세트를 완료한 세션도 이 시간이 지나면 닫는다 */
        const val MAX_HOURS = 24L
    }
}

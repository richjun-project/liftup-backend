package com.richjun.liftupai.domain.workout.search

/**
 * 검색 품질을 검사하는 테스트의 엔진 시간 예산. 운영 예산(40ms)을 그대로 쓰면 기계가 바쁠 때(병렬 세션으로 부하 평균 100+)
 * 결과가 잘려 품질 테스트가 흔들린다(2026-10-10 "stair stepper" 가 부하에서만 실패). 예산 동작 자체는 별도 테스트가 잰다.
 */
const val QUALITY_BUDGET_NANOS: Long = 10_000_000_000L

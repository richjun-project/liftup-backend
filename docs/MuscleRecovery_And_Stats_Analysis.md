# Muscle Recovery 및 통계 시스템 분석

## 1. Muscle Recovery 데이터가 비어있는 문제

### 현재 상황
운동 완료 후 `updateMuscleRecoveryAfterWorkout` 메서드가 호출되고 있지만, 데이터가 저장되지 않을 수 있는 원인:

### 가능한 원인들

1. **테이블 생성 문제**
   - `muscle_recovery` 테이블이 생성되지 않았을 가능성
   - Hibernate가 자동으로 생성해야 하지만 DDL 설정 확인 필요

2. **근육 그룹 매칭 문제**
   - Exercise 엔티티의 `muscleGroups`가 비어있으면 데이터 저장 안됨
   - 현재 코드:
   ```kotlin
   completedExercises.forEach { completedExercise ->
       val exercise = exerciseRepository.findById(completedExercise.exerciseId).orElse(null)
       if (exercise != null) {
           muscleGroupsWorked.addAll(exercise.muscleGroups.map { it.name })
       }
   }
   ```

3. **트랜잭션 문제**
   - `completeWorkout` 메서드는 `@Transactional`이 없음
   - 저장이 롤백될 가능성

### 해결 방법
1. 테이블 존재 확인
2. Exercise 데이터에 muscleGroups가 제대로 설정되어 있는지 확인
3. `@Transactional` 추가
4. 로그 추가하여 디버깅

## 2. 통계(Stats) 계산 방식

### 2.1 통계 개요 (`StatsService.getOverview`)
- **기간**: week, month, year
- **계산 항목**:
  - `totalWorkouts`: 완료된 세션 수
  - `totalDuration`: 총 운동 시간 (초)
  - `totalVolume`: 총 볼륨 (무게 × 반복 횟수)
  - `averageDuration`: 평균 운동 시간
  - `streak`: 연속 운동 일수

### 2.2 볼륨 통계 (`StatsService.getVolumeStats`)
- 날짜별 볼륨 계산
- `WorkoutLog` 테이블에서 데이터 조회
- 볼륨 = Σ(무게 × 반복 횟수)

### 2.3 근육 분포 (`StatsService.getMuscleDistribution`)
**문제점**: 현재 로직이 복잡하고 일관성이 없음

```kotlin
// 현재 우선순위:
1. exercise.muscleGroup (단일 String 필드)
2. exercise.muscleGroups (Set<MuscleGroup> enum)
3. exercise.category 기반 매핑
```

**근육 분포가 비어있는 이유**:
- `WorkoutLog` 대신 `ExerciseSet`을 사용해야 함
- 현재 `WorkoutLogRepository.findBySession()`이 빈 결과 반환 가능

### 2.4 개인 기록 (`StatsService.getPersonalRecords`)
- 각 운동별 최고 무게 기록
- 1년간 데이터 조회

### 2.5 진행도 (`StatsService.getProgress`)
- metric: weight, volume, strength
- period: 3months, 6months, year
- 시간별 진행 상황 추적

## 3. WorkoutServiceV2의 통계 계산

### 운동 완료 시 계산되는 통계 (`completeWorkout`)
1. **WorkoutStats** (운동 통계)
   - `totalWorkoutDays`: 총 운동 일수
   - `currentWeekCount`: 이번 주 운동 횟수
   - `weeklyGoal`: 주간 목표 (기본 5회)
   - `currentStreak`: 현재 연속 일수
   - `longestStreak`: 최장 연속 일수

2. **계산 방법**:
   ```kotlin
   private fun calculateWorkoutStats(user: User): WorkoutStats {
       val totalWorkoutDays = workoutSessionRepository.countDistinctWorkoutDays(user)
       val currentWeekCount = calculateWeeklyWorkoutCount(user)
       val currentStreak = calculateCurrentStreak(user)
       val longestStreak = workoutStreakRepository.findLongestStreakByUser(user) ?: 0
   }
   ```

3. **Streak 계산 로직**:
   - 오늘부터 역순으로 검사
   - 운동한 날이 연속되면 streak 증가
   - 하루라도 빠지면 중단

## 4. 문제 해결 방안

### Muscle Recovery 데이터 저장 안되는 문제
```kotlin
// WorkoutServiceV2.kt에 추가
@Transactional
fun completeWorkout(...) {
    // 기존 코드...

    // 디버깅용 로그 추가
    logger.debug("Muscle groups worked: $muscleGroupsWorked")

    // 저장 확인
    muscleGroupsWorked.forEach { muscleGroup ->
        val saved = muscleRecoveryRepository.save(muscleRecovery)
        logger.debug("Saved muscle recovery: ${saved.id} for $muscleGroup")
    }
}
```

### 통계 근육 분포 문제
```kotlin
// StatsService.kt 수정 필요
fun getMuscleDistribution(userId: Long, period: String): MuscleDistributionResponse {
    // WorkoutLog 대신 ExerciseSet 사용
    val exerciseSets = sessions.flatMap { session ->
        workoutExerciseRepository.findBySessionIdOrderByOrderInSession(session.id)
            .flatMap { workoutExercise ->
                exerciseSetRepository.findByWorkoutExerciseId(workoutExercise.id)
                    .map { set -> workoutExercise.exercise }
            }
    }

    // 근육 그룹 집계
    val muscleGroups = exerciseSets.flatMap { exercise ->
        exercise.muscleGroups.map { it.name }
    }.groupingBy { it }.eachCount()
}
```

## 5. 데이터베이스 스키마 확인 필요

### 확인 사항
1. `muscle_recovery` 테이블 존재 여부
2. `workout_logs` vs `exercise_sets` 데이터
3. `exercises` 테이블의 `muscle_groups` 컬럼 데이터

### SQL 확인
```sql
-- muscle_recovery 테이블 확인
SHOW TABLES LIKE 'muscle_recovery';

-- 데이터 확인
SELECT * FROM muscle_recovery LIMIT 10;

-- Exercise의 muscle_groups 확인
SELECT id, name, muscle_groups FROM exercises LIMIT 10;

-- WorkoutLog vs ExerciseSet 데이터 확인
SELECT COUNT(*) FROM workout_logs;
SELECT COUNT(*) FROM exercise_sets;
```
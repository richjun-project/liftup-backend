# API 정리 가이드

## 권장 API (V2 및 최신 버전)

### 1. 운동 시작/종료
```
✅ POST /api/v2/workouts/start/new       - 새 운동 시작
✅ POST /api/v2/workouts/start/continue   - 진행 중인 운동 이어하기
✅ PUT  /api/v2/workouts/{sessionId}/complete - 운동 완료
```

### 2. 운동 조회
```
✅ GET  /api/workouts/current-session     - 현재 진행 중인 세션 조회
✅ GET  /api/workouts/history             - 운동 기록 조회
✅ GET  /api/workouts/{sessionId}         - 특정 세션 상세 조회
```

### 3. 운동 추천
```
✅ POST /api/v2/workouts/recommendations/today - 오늘의 운동 추천
✅ GET  /api/workouts/recommendations/quick  - 빠른 운동 추천
✅ POST /api/workouts/start-recommended      - 추천 운동 시작
```

### 4. 통계
```
✅ GET  /api/v2/stats/overview               - 통계 개요
✅ GET  /api/v2/stats/volume                 - 볼륨 통계
✅ GET  /api/v2/stats/muscle-distribution    - 근육별 분포
✅ GET  /api/v2/stats/personal-records       - 개인 기록
✅ GET  /api/v2/stats/progress               - 진행 상황
✅ GET  /api/v2/stats/workout-completion     - 운동 완료 통계
✅ GET  /api/v2/stats/calendar               - 운동 캘린더
✅ GET  /api/v2/stats/weekly                 - 주간 통계
```

## Deprecated API (사용 중지 권장)

### V1 API
```
❌ POST /api/workouts/start              - 대신 /api/v2/workouts/start/new 사용
❌ PUT  /api/workouts/{sessionId}/end    - 대신 /api/v2/workouts/{sessionId}/complete 사용
❌ GET  /api/workouts/recommendations/today - 대신 POST 메서드 사용
```

### 기존 V2 API
```
⚠️  POST /api/v2/workouts/start          - start/new 또는 start/continue 사용 권장
```

## Flutter 클라이언트 수정 필요 사항

### 1. WorkoutService 메서드 정리
- `startWorkout()` → `startNewWorkout()`
- `endWorkout()` → `completeWorkout()`
- 중복 메서드 제거

### 2. 요청 형식 수정
```dart
// 운동 시작 요청에 order_index 추가
{
  "workout_type": "CUSTOM",
  "exercises": [
    {
      "exercise_id": 37,
      "sets": 4,
      "target_reps": 8,
      "weight": 20.0,
      "order_index": 0  // 추가 필요
    }
  ]
}
```

### 3. API 엔드포인트 통일
- 모든 V1 엔드포인트를 V2로 변경
- 불필요한 중복 호출 제거

## 백엔드 정리 완료 사항

1. ✅ V1 API에 @Deprecated 어노테이션 추가
2. ✅ 중복 recommendation API 정리
3. ✅ lazy loading 문제 해결 (repository 직접 조회)
4. ✅ WorkoutExercise 엔티티 저장 로직 수정
5. ✅ WorkoutControllerV4 제거 (V2로 통합)
6. ✅ ExerciseController V1 제거
7. ✅ StatsController 제거 (StatsControllerV2로 통합)

## 향후 계획

1. V1 API 완전 제거 (3개월 후)
2. V3 API 설계 및 구현 (GraphQL 고려)
3. WebSocket 기반 실시간 운동 트래킹 추가
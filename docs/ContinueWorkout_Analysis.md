# 운동 이어하기 기능 분석

## API Endpoint
- **URL**: `POST /api/v2/workouts/start/continue`
- **Controller**: `WorkoutControllerV2.continueWorkout()`
- **Service**: `WorkoutServiceV2.continueWorkout()`

## 반환 데이터 구조

### StartWorkoutResponseV2
```kotlin
data class StartWorkoutResponseV2(
    val sessionId: Long,
    val startTime: String,
    val exercises: List<ExerciseDto>,
    val restTimerSettings: RestTimerSettings,
    val exerciseSets: List<ExerciseWithSets>?  // ✅ 완료된 세트 정보 포함
)
```

### ExerciseWithSets (완료된 세트 포함)
```kotlin
data class ExerciseWithSets(
    val exerciseId: Long,
    val exerciseName: String,
    val orderIndex: Int,
    val sets: List<SetInfo>  // ✅ 각 세트의 상세 정보
)
```

### SetInfo (세트 정보)
```kotlin
data class SetInfo(
    val setId: Long?,
    val setNumber: Int,
    val weight: Double?,
    val reps: Int?,
    val completed: Boolean,  // ✅ 완료 여부
    val completedAt: String?  // ✅ 완료 시간
)
```

## 동작 방식

1. **진행 중인 세션 확인**
   - `SessionStatus.IN_PROGRESS` 상태의 세션을 찾음
   - 세션이 없으면 에러 반환

2. **운동 정보 조회**
   - `WorkoutExercise` 엔티티들을 조회
   - 각 운동의 기본 정보를 `ExerciseDto`로 변환

3. **세트 정보 조회 및 반환** ✅
   - 각 운동별로 `ExerciseSet` 엔티티들을 조회
   - 완료된 세트 정보 포함:
     - `setId`: 세트 ID
     - `weight`: 사용한 무게
     - `reps`: 수행한 반복 횟수
     - `completed`: 완료 여부 (true/false)
     - `completedAt`: 완료 시간
   - 세트가 없는 경우 기본 3세트를 생성 (completed: false)

## 결론
**✅ 운동 이어하기 시 완료된 운동 세트도 모두 반환됩니다.**

`exerciseSets` 필드에 각 운동의 모든 세트 정보가 포함되며, 각 세트의 완료 여부(`completed`)와 완료 시간(`completedAt`)을 통해 이미 완료된 세트와 아직 수행하지 않은 세트를 구분할 수 있습니다.

## 예시 응답
```json
{
  "sessionId": 123,
  "startTime": "2024-01-15T10:00:00",
  "exercises": [...],
  "restTimerSettings": {...},
  "exerciseSets": [
    {
      "exerciseId": 1,
      "exerciseName": "벤치프레스",
      "orderIndex": 0,
      "sets": [
        {
          "setId": 101,
          "setNumber": 1,
          "weight": 60.0,
          "reps": 12,
          "completed": true,
          "completedAt": "2024-01-15T10:05:00"
        },
        {
          "setId": 102,
          "setNumber": 2,
          "weight": 60.0,
          "reps": 10,
          "completed": true,
          "completedAt": "2024-01-15T10:07:00"
        },
        {
          "setNumber": 3,
          "completed": false,
          "completedAt": null
        }
      ]
    }
  ]
}
```
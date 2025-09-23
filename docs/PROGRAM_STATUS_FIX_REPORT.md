# Program Status API 수정 보고서

## 🔍 문제점
Flutter 응답에서 `total_days`가 1로 나오는 문제:
```json
{
  "current_day": 1,
  "total_days": 1,  // ❌ 문제: 1일 프로그램?
  "current_cycle": 1,
  "next_workout_type": "FULL_BODY",
  "next_workout_description": "1일 프로그램 중 1일차: 전신 운동",
  "program_type": "auto",  // ❌ 문제: "auto"는 정의되지 않은 타입
  ...
}
```

## 🔧 원인 분석

### 1. program_type이 "auto"인 경우
- UserSettings와 UserProfile 둘 다 `workoutSplit`이 없거나 "auto"로 설정됨
- `getWorkoutTypeSequence("auto")`가 기본값 `[FULL_BODY]`만 반환 (크기 1)
- 결과적으로 `total_days = 1`

### 2. 기존 코드의 문제
```kotlin
// 이전 코드
fun getWorkoutTypeSequence(programType: String): List<WorkoutType> {
    return when (programType.uppercase()) {
        "PPL" -> listOf(WorkoutType.PUSH, WorkoutType.PULL, WorkoutType.LEGS)
        // ...
        else -> listOf(WorkoutType.FULL_BODY)  // ❌ 단일 요소만 반환
    }
}
```

## ✅ 수정 내용

### 1. WorkoutProgressTracker 개선
```kotlin
fun getWorkoutTypeSequence(programType: String): List<WorkoutType> {
    return when (programType.uppercase()) {
        "PPL", "PUSH_PULL_LEGS" -> listOf(
            WorkoutType.PUSH,
            WorkoutType.PULL,
            WorkoutType.LEGS
        )

        "UPPER_LOWER", "UPPER/LOWER" -> listOf(
            WorkoutType.UPPER,
            WorkoutType.LOWER,
            WorkoutType.UPPER,
            WorkoutType.LOWER
        )

        "FULL_BODY", "FULL" -> listOf(
            WorkoutType.FULL_BODY,
            WorkoutType.FULL_BODY,
            WorkoutType.FULL_BODY
        )

        "BRO_SPLIT", "5_SPLIT", "5-SPLIT" -> listOf(
            WorkoutType.CHEST,
            WorkoutType.BACK,
            WorkoutType.SHOULDERS,
            WorkoutType.ARMS,
            WorkoutType.LEGS
        )

        "AUTO", "" -> listOf(  // ✅ AUTO를 PPL로 처리
            WorkoutType.PUSH,
            WorkoutType.PULL,
            WorkoutType.LEGS
        )

        else -> listOf(  // ✅ 기본값도 PPL (3일)
            WorkoutType.PUSH,
            WorkoutType.PULL,
            WorkoutType.LEGS
        )
    }
}
```

### 2. 주요 개선사항
- **AUTO 처리**: "AUTO"를 PPL(3일) 프로그램으로 매핑
- **FULL_BODY 확장**: 3일 전신 운동으로 변경
- **UPPER_LOWER 확장**: 4일 상하체 분할로 변경
- **별칭 지원**: 다양한 프로그램 이름 지원 (예: "PUSH_PULL_LEGS", "5-SPLIT")
- **기본값 개선**: 알 수 없는 타입은 PPL(3일)로 처리

## 📊 수정 후 예상 응답

### program_type이 "auto"인 경우:
```json
{
  "current_day": 1,
  "total_days": 3,  // ✅ 수정됨: PPL 3일
  "current_cycle": 1,
  "next_workout_type": "PUSH",
  "next_workout_description": "3일 프로그램 중 1일차: 밀기 운동 (가슴/삼두/어깨)",
  "program_type": "auto",
  ...
}
```

## 🔄 프로그램별 일수

| Program Type | 변경 전 | 변경 후 | 설명 |
|-------------|---------|---------|------|
| PPL | 3일 | 3일 | 유지 |
| UPPER_LOWER | 2일 | 4일 | 더 현실적인 주 4회 프로그램 |
| FULL_BODY | 1일 | 3일 | 주 3회 전신 운동 |
| BRO_SPLIT | 5일 | 5일 | 유지 |
| AUTO | 1일 | 3일 | PPL로 매핑 |
| 기타/없음 | 1일 | 3일 | PPL 기본값 |

## 🎯 추가 권장사항

### 1. 사용자 설정 확인
```sql
-- 현재 'auto' 설정인 사용자 확인
SELECT user_id, workout_split
FROM user_settings
WHERE workout_split = 'auto' OR workout_split IS NULL;

-- 적절한 기본값으로 업데이트
UPDATE user_settings
SET workout_split = 'PPL'
WHERE workout_split = 'auto' OR workout_split IS NULL;
```

### 2. Flutter 앱 수정
```dart
// 프로그램 타입 선택 시 'auto' 제거
enum WorkoutSplit {
  PPL('PPL', 'Push Pull Legs'),
  UPPER_LOWER('UPPER_LOWER', '상하체 분할'),
  FULL_BODY('FULL_BODY', '전신 운동'),
  BRO_SPLIT('BRO_SPLIT', '5분할');
  // 'AUTO' 옵션 제거
}
```

## ✅ 결론
- **문제 해결**: `total_days=1` 문제 수정 완료
- **하위 호환성**: "auto" 타입도 정상 처리
- **개선된 기본값**: 모든 경우에 최소 3일 프로그램 보장
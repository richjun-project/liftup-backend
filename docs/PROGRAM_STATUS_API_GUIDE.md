# getProgramStatus 메서드 동작 가이드

## 📋 개요
`getProgramStatus`는 사용자의 현재 운동 프로그램 진행 상황을 조회하는 메서드입니다.

## 🔄 동작 방식

### 1. 데이터 조회 순서
```kotlin
// 1단계: UserSettings에서 먼저 조회 (우선순위)
val userSettings = userSettingsRepository.findByUser_Id(userId)

// 2단계: UserSettings에 없으면 UserProfile에서 조회 (하위 호환성)
val userProfile = userProfileRepository.findByUser_Id(userId)

// 3단계: 값 결정 (UserSettings 우선)
val programDays = userSettings?.weeklyWorkoutDays
    ?: userProfile?.weeklyWorkoutDays
    ?: 3  // 기본값

val programType = userSettings?.workoutSplit
    ?: userProfile?.workoutSplit
    ?: "PPL"  // 기본값
```

### 2. 프로그램 위치 계산
```kotlin
// WorkoutProgressTracker를 사용하여 다음 운동 위치 계산
val programPosition = workoutProgressTracker.getNextWorkoutInProgram(user, programDays)
// 반환: (day: Int, cycle: Int)
```

### 3. 운동 타입 결정
```kotlin
// 프로그램 타입에 따른 운동 시퀀스 가져오기
val sequence = workoutProgressTracker.getWorkoutTypeSequence(programType)
// 예: PPL → [PUSH, PULL, LEGS]
// 예: UPPER_LOWER → [UPPER, LOWER, UPPER, LOWER]

// 다음 운동 타입 결정
val nextWorkoutType = sequence.getOrNull(programPosition.day - 1) ?: WorkoutType.FULL_BODY
```

### 4. 운동 히스토리 조회
```kotlin
// 최근 10개 세션 조회
val recentSessions = workoutSessionRepository.findTop10ByUserAndStatusInOrderByStartTimeDesc(
    user,
    listOf(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS, SessionStatus.CANCELLED)
)

// 프로그램 운동만 필터링 (최대 5개)
val workoutHistory = recentSessions
    .filter { it.programDay != null }
    .take(5)
```

## 🔍 응답 데이터 구조

### ProgramStatusResponse
```json
{
  "currentDay": 2,              // 현재 프로그램 일차
  "totalDays": 3,               // 전체 프로그램 일수
  "currentCycle": 1,            // 현재 사이클 (몇 번째 반복)
  "nextWorkoutType": "PULL",    // 다음 운동 타입
  "nextWorkoutDescription": "Pull Day - 등/이두", // 설명
  "lastWorkoutDate": "2025-01-19T10:30:00",       // 마지막 운동 일시
  "workoutHistory": [           // 최근 프로그램 운동 기록
    {
      "dayNumber": 1,
      "workoutType": "PUSH",
      "date": "2025-01-18T10:00:00",
      "status": "COMPLETED",
      "cycleNumber": 1
    }
  ],
  "programName": "Push Pull Legs",
  "programType": "PPL",
  "isActive": true,
  "workoutsThisWeek": 2,       // 이번 주 운동 횟수
  "workoutsThisMonth": 8,      // 이번 달 운동 횟수
  "nextScheduledDate": null,   // 다음 예정 날짜
  "progressMessage": "다음 운동: Pull Day"
}
```

## ⚙️ 프로그램 타입별 시퀀스

### 1. PPL (Push Pull Legs)
```
Day 1: PUSH (가슴/삼두/어깨)
Day 2: PULL (등/이두)
Day 3: LEGS (하체)
→ 반복 (Cycle 2로 진행)
```

### 2. UPPER_LOWER (상하체 분할)
```
Day 1: UPPER (상체)
Day 2: LOWER (하체)
Day 3: UPPER (상체)
Day 4: LOWER (하체)
→ 반복
```

### 3. FULL_BODY (전신)
```
Day 1: FULL_BODY
Day 2: FULL_BODY
Day 3: FULL_BODY
→ 반복
```

### 4. BRO_SPLIT (5분할)
```
Day 1: CHEST
Day 2: BACK
Day 3: SHOULDERS
Day 4: ARMS
Day 5: LEGS
→ 반복
```

## 🚨 주의사항

### 1. 데이터 우선순위
- **UserSettings**가 최우선
- UserProfile은 하위 호환성을 위해 유지
- 둘 다 없으면 기본값 사용

### 2. 기본값
- `weeklyWorkoutDays`: 3일
- `workoutSplit`: "PPL"

### 3. 마이그레이션 필요
현재 일부 사용자는 UserProfile에만 데이터가 있을 수 있으므로:
```sql
-- UserProfile → UserSettings 마이그레이션
INSERT INTO user_settings (user_id, weekly_workout_days, workout_split, ...)
SELECT user_id, weekly_workout_days, workout_split, ...
FROM user_profiles
WHERE user_id NOT IN (SELECT user_id FROM user_settings);
```

## 🧪 테스트

### API 호출
```bash
curl -X GET "http://localhost:8080/api/v2/workouts/program-status" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Accept: application/json"
```

### 예상 시나리오

#### 시나리오 1: UserSettings에 데이터 있음
- UserSettings의 weeklyWorkoutDays = 4
- UserSettings의 workoutSplit = "UPPER_LOWER"
- **결과**: 4일 상하체 프로그램으로 계산

#### 시나리오 2: UserSettings 없고 UserProfile만 있음
- UserProfile의 weeklyWorkoutDays = 3
- UserProfile의 workoutSplit = "PPL"
- **결과**: 3일 PPL 프로그램으로 계산

#### 시나리오 3: 둘 다 없음
- **결과**: 기본값 3일 PPL 프로그램으로 계산

## ✅ 정상 동작 여부

**예, 정상적으로 동작합니다.**

코드가 다음과 같이 동작합니다:
1. ✅ UserSettings에서 우선 조회
2. ✅ 없으면 UserProfile에서 조회 (하위 호환성)
3. ✅ 둘 다 없으면 기본값 사용
4. ✅ WorkoutProgressTracker로 프로그램 위치 계산
5. ✅ 운동 히스토리 조회 및 반환

다만 `@Deprecated` 경고가 발생하므로 장기적으로는 UserProfile의 필드를 제거하고 UserSettings로 완전히 마이그레이션하는 것을 권장합니다.
# LiftUp AI 백엔드 API 요구사항

## 1. 인증 및 사용자 관리

### 1.1 회원가입/로그인
- **POST** `/api/auth/register`
  - Request: `{ email, password, nickname }`
  - Response: `{ userId, accessToken, refreshToken }`

- **POST** `/api/auth/login`
  - Request: `{ email, password }`
  - Response: `{ userId, accessToken, refreshToken, profile }`

- **POST** `/api/auth/refresh`
  - Request: `{ refreshToken }`
  - Response: `{ accessToken, refreshToken }`

- **POST** `/api/auth/logout`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ success }`

- **GET** `/api/auth/check-nickname`
  - Query: `?nickname={nickname}`
  - Response: `{ available, message }`

### 1.2 프로필 관리
- **GET** `/api/users/profile`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ userId, email, nickname, level, joinDate, bodyInfo, goals, ptStyle, subscription }`

- **PUT** `/api/users/profile`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ nickname, bodyInfo, goals, ptStyle }`
  - Response: `{ success, profile }`

- **POST** `/api/users/onboarding`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ nickname, experienceLevel, goals, bodyInfo, ptStyle, notificationEnabled }`
  - Response: `{ success, profile }`

### 1.3 사용자 설정
- **GET** `/api/users/settings`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ notifications, privacy, app }`

- **PUT** `/api/users/settings`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ notifications?, privacy?, app? }`
  - Response: `{ success, settings }`

## 2. AI 채팅 기능

### 2.1 메시지 관리
- **POST** `/api/chat/send`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ message, attachments? }`
  - Response: `{ messageId, userMessage, aiResponse, timestamp }`

- **GET** `/api/chat/history`
  - Header: `Authorization: Bearer {token}`
  - Query: `?page=1&limit=20&date=2024-01-01`
  - Response: `{ messages[], hasMore, totalCount }`

- **DELETE** `/api/chat/clear`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ success }`

### 2.2 AI 분석 및 추천
- **POST** `/api/ai/analyze-form`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ exerciseId, videoUrl or imageUrl }`
  - Response: `{ analysis, score, improvements[], corrections[] }`

- **GET** `/api/ai/recommendations`
  - Header: `Authorization: Bearer {token}`
  - Query: `?type=workout&muscleGroups=chest,back`
  - Response: `{ workouts[], nutrition[], recovery[] }`

### 2.3 식단 분석
- **POST** `/api/ai/analyze-meal`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ imageUrl }`
  - Response: `{ mealInfo, calories, macros { protein, carbs, fat }, suggestions[] }`

- **POST** `/api/upload/meal-image`
  - Header: `Authorization: Bearer {token}`
  - Request: FormData with image file
  - Response: `{ url }`

- **GET** `/api/nutrition/history`
  - Header: `Authorization: Bearer {token}`
  - Query: `?date=2024-01-01&period=week`
  - Response: `{ meals[], totalCalories, avgMacros { protein, carbs, fat } }`

- **POST** `/api/nutrition/log`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ mealType, foods[], calories, macros, timestamp }`
  - Response: `{ success, mealId }`

## 3. 운동 기록 관리

### 3.1 운동 세션
- **POST** `/api/workouts/start`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ plannedExercises[] }`
  - Response: `{ sessionId, startTime }`

- **PUT** `/api/workouts/{sessionId}/end`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ exercises[], duration, notes }`
  - Response: `{ success, summary }`

- **GET** `/api/workouts/sessions`
  - Header: `Authorization: Bearer {token}`
  - Query: `?page=1&limit=10&startDate=2024-01-01&endDate=2024-01-31`
  - Response: `{ sessions[], totalCount }`

- **GET** `/api/workouts/{sessionId}`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ sessionId, date, duration, exercises[], totalVolume, caloriesBurned }`

- **PUT** `/api/workouts/{sessionId}`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ exercises[], duration, notes }`
  - Response: `{ success, session }`

- **DELETE** `/api/workouts/{sessionId}`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ success }`

### 3.2 운동 데이터
- **GET** `/api/exercises`
  - Query: `?category=chest&equipment=barbell`
  - Response: `{ exercises[] }` (id, name, category, muscleGroups, equipment, instructions)

- **GET** `/api/exercises/{exerciseId}`
  - Response: `{ exercise, personalRecords, lastPerformed }`

- **POST** `/api/workouts/exercises/{exerciseId}/sets`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ sessionId, sets[] }` (weight, reps, restTime)
  - Response: `{ success, totalVolume, isPersonalRecord }`

### 3.3 운동 프로그램 & 추천
- **GET** `/api/workouts/programs/current`
  - Header: `Authorization: Bearer {token}`
  - Response: `{
    programId,
    programName,
    currentWeek,
    totalWeeks,
    workoutSplit,
    nextWorkout {
    dayName,
    targetMuscles[],
    exercises[],
    estimatedDuration
    },
    weeklySchedule[]
    }`
  - Description: 현재 진행 중인 운동 프로그램 정보

- **POST** `/api/workouts/programs/generate`
  - Header: `Authorization: Bearer {token}`
  - Request: `{
    goals[],
    experienceLevel,
    weeklyWorkoutDays,
    workoutSplit,
    availableEquipment[],
    duration,
    injuries[]
    }`
  - Response: `{
    programId,
    mesocycle {
    phase,
    weeks,
    focusAreas[],
    volumeProgression
    },
    weeklySchedule[]
    }`
  - Description: 개인 맞춤 메조사이클 프로그램 생성

- **GET** `/api/workouts/recommendations/today`
  - Header: `Authorization: Bearer {token}`
  - Response: `{
    programName,
    dayInProgram,
    targetMuscles[],
    exercises[] {
    exerciseId,
    name,
    recommendedSets,
    recommendedReps,
    recommendedWeight,
    rpe,
    restTime,
    previousPerformance
    },
    estimatedDuration,
    difficulty,
    recoveryStatus {
    muscle,
    recoveryPercentage,
    lastWorked
    }[],
    alternatives[]
    }`
  - Description: 프로그램 기반 오늘의 운동 (회복 상태 고려)

- **GET** `/api/workouts/recovery-status`
  - Header: `Authorization: Bearer {token}`
  - Response: `{
    muscleGroups[] {
    name,
    lastWorked,
    recoveryPercentage,
    readyForWork,
    estimatedFullRecovery
    },
    overallFatigue,
    deloadRecommended
    }`
  - Description: 근육별 회복 상태 및 피로도 분석

- **POST** `/api/workouts/adjust-volume`
  - Header: `Authorization: Bearer {token}`
  - Request: `{
    sessionId,
    fatigueLevel,
    timeAvailable,
    equipmentAvailable[]
    }`
  - Response: `{
    adjustedExercises[],
    volumeMultiplier,
    reason
    }`
  - Description: 실시간 볼륨/강도 조절

### 3.4 중량 계산 & 1RM 추정
- **POST** `/api/workouts/calculate-weight`
  - Header: `Authorization: Bearer {token}`
  - Request: `{
    exerciseId,
    experienceLevel,
    bodyWeight,
    gender,
    previousRecords[]
    }`
  - Response: `{
    recommendedWeight,
    warmupSets[] {weight, reps},
    workingSets[] {weight, reps, rpe},
    calculationMethod,
    confidence
    }`
  - Description: 운동별 추천 중량 계산

- **POST** `/api/workouts/estimate-1rm`
  - Header: `Authorization: Bearer {token}`
  - Request: `{
    exerciseId,
    weight,
    reps,
    rpe
    }`
  - Response: `{
    estimated1RM,
    formula,
    percentages {70, 75, 80, 85, 90}
    }`
  - Description: 수행 데이터로 1RM 추정 (Epley/Brzycki formula)

- **POST** `/api/workouts/strength-test`
  - Header: `Authorization: Bearer {token}`
  - Request: `{
    exercises[] {exerciseId, weight, reps, rpe}
    }`
  - Response: `{
    estimatedMaxes {},
    strengthLevel,
    strengthScore,
    recommendations[]
    }`
  - Description: 초기 근력 테스트 결과 처리

- **GET** `/api/workouts/strength-standards`
  - Query: `?gender=male&bodyWeight=70`
  - Response: `{
    standards[] {
    exercise,
    beginner,
    novice,
    intermediate,
    advanced,
    elite
    }
    }`
  - Description: 체중별 근력 기준표 (Symmetric Strength 기준)

## 4. 통계 및 분석

### 4.1 운동 통계
- **GET** `/api/stats/overview`
  - Header: `Authorization: Bearer {token}`
  - Query: `?period=week|month|year`
  - Response: `{ totalWorkouts, totalDuration, totalVolume, averageDuration, streak }`

- **GET** `/api/stats/volume`
  - Header: `Authorization: Bearer {token}`
  - Query: `?period=week&startDate=2024-01-01`
  - Response: `{ data[] }` (date, volume)

- **GET** `/api/stats/muscle-distribution`
  - Header: `Authorization: Bearer {token}`
  - Query: `?period=month`
  - Response: `{ distribution[] }` (muscleGroup, percentage, sessions)

- **GET** `/api/stats/personal-records`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ records[] }` (exerciseId, exerciseName, weight, reps, date)

### 4.2 진행도 추적
- **GET** `/api/stats/progress`
  - Header: `Authorization: Bearer {token}`
  - Query: `?metric=weight|volume|strength&period=3months`
  - Response: `{ progress[] }` (date, value, change)

## 5. 근육 회복도

### 5.1 회복 상태
- **GET** `/api/recovery/status`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ muscles[] }` (name, recoveryPercentage, lastWorked, estimatedRecoveryTime, status)

- **PUT** `/api/recovery/update`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ muscleGroup, feelingScore, soreness }`
  - Response: `{ success, updatedStatus }`

### 5.2 회복 추천
- **GET** `/api/recovery/recommendations`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ readyMuscles[], recoveryExercises[], nutritionTips[] }`

## 6. 구독 및 결제

### 6.1 구독 관리
- **GET** `/api/subscription/status`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ plan, status, expiryDate, features[] }`

- **POST** `/api/subscription/subscribe`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ plan, paymentMethod }`
  - Response: `{ success, subscription }`

- **POST** `/api/subscription/cancel`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ success, cancelDate }`

## 7. 알림 설정

### 7.1 푸시 알림
- **POST** `/api/notifications/register`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ deviceToken, platform }`
  - Response: `{ success }`

- **GET** `/api/notifications/settings`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ workoutReminder, aiMessages, achievements, marketing }`

- **PUT** `/api/notifications/settings`
  - Header: `Authorization: Bearer {token}`
  - Request: `{ workoutReminder, aiMessages, achievements, marketing }`
  - Response: `{ success, settings }`

## 8. 데이터 동기화

### 8.1 백업 및 복원
- **POST** `/api/sync/backup`
  - Header: `Authorization: Bearer {token}`
  - Response: `{ backupId, timestamp }`

- **GET** `/api/sync/restore`
  - Header: `Authorization: Bearer {token}`
  - Query: `?backupId={id}`
  - Response: `{ success, data }`

## 기술 요구사항

### 인증
- JWT 기반 인증
- Access Token: 1시간 유효
- Refresh Token: 30일 유효

### 데이터 타입 명세
- bodyInfo: `{ height: number, weight: number, bodyFat?: number, muscleMass?: number }`
- goals: `["muscle_gain" | "weight_loss" | "strength" | "endurance"]`
- ptStyle: `"friendly" | "strict" | "motivational"`
- mealType: `"breakfast" | "lunch" | "dinner" | "snack"`

### 응답 형식
```json
{
  "success": true,
  "data": {},
  "error": null,
  "timestamp": "2024-01-01T00:00:00Z"
}
```

### 에러 응답
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Error description",
    "details": {}
  },
  "timestamp": "2024-01-01T00:00:00Z"
}
```

### 에러 코드
- `AUTH001`: 인증 실패
- `AUTH002`: 토큰 만료
- `AUTH003`: 권한 부족
- `VALID001`: 유효성 검사 실패
- `VALID002`: 중복된 값
- `RATE001`: 요청 제한 초과
- `SERVER001`: 서버 내부 오류
- `NOT_FOUND`: 리소스를 찾을 수 없음

### 페이지네이션
```json
{
  "data": [],
  "pagination": {
    "page": 1,
    "limit": 20,
    "totalPages": 5,
    "totalCount": 100,
    "hasNext": true,
    "hasPrev": false
  }
}
```

## 보안 요구사항
- HTTPS 필수
- Rate Limiting: 100 requests/minute per user
- API Key 인증 (외부 서비스 연동용)
- 민감 정보 암호화 저장
- SQL Injection 방지
- XSS 방지
- 이미지 업로드: 최대 10MB, JPG/PNG/WEBP만 허용
- CORS 설정: 특정 도메인만 허용

## 성능 요구사항
- 응답 시간: < 200ms (일반 API)
- AI 응답 시간: < 3초
- 동시 접속: 10,000+ users
- 가용성: 99.9% uptime

## 데이터베이스 스키마 핵심 테이블

### users
- id, email, password_hash, created_at, updated_at

### user_profiles
- user_id, nickname, experience_level, goals, height, weight, age, gender
- pt_style, notification_enabled
- weekly_workout_days, workout_split, available_equipment
- preferred_workout_time, workout_duration, injuries
- current_program_id, current_week

### workout_programs
- id, user_id, name, type (mesocycle/microcycle)
- split_type, weekly_days, phase, total_weeks
- volume_progression, created_at, updated_at

### workout_sessions
- id, user_id, program_id, date, duration
- planned_exercises, completed_exercises
- total_volume, notes, fatigue_level

### exercises
- id, name, category, muscle_groups, equipment
- instructions, difficulty, mev, mrv

### muscle_recovery
- user_id, muscle_group, last_worked
- recovery_percentage, volume_accumulated

### exercise_sets
- session_id, exercise_id, set_number
- weight, reps, rpe, rest_time

### chat_messages
- id, session_id, user_id, content, is_ai, timestamp

### subscriptions
- user_id, plan_type, status, expires_at

### notifications
- user_id, type, content, is_read, created_at

### nutrition_logs
- user_id, date, calories, protein, carbs, fat

### meal_analyses
- user_id, image_url, analysis_result, timestamp
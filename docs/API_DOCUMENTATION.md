# LiftUp AI API Documentation

## Base URL
```
https://api.liftupai.com
```

## Authentication
모든 API 요청에는 JWT 토큰이 필요합니다.
```
Authorization: Bearer <token>
```

## API Versioning
- **V2 (현재 권장)**: `/api/v2/*`
- **V1 (Deprecated)**: `/api/*` - 3개월 후 제거 예정

---

## 1. Workout APIs

### 1.1 운동 시작/종료

#### 새 운동 시작
```http
POST /api/v2/workouts/start/new
```

**Request Body:**
```json
{
  "workout_type": "CUSTOM",
  "exercises": [
    {
      "exercise_id": 1,
      "sets": 4,
      "target_reps": 8,
      "weight": 60.0,
      "order_index": 0
    }
  ]
}
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "session_id": 123,
    "status": "IN_PROGRESS",
    "started_at": "2025-01-19T10:30:00",
    "exercises": [...],
    "rest_timer": {
      "enabled": true,
      "default_rest": 90,
      "compound_rest": 180,
      "isolation_rest": 60
    }
  }
}
```

#### 진행 중인 운동 이어하기
```http
POST /api/v2/workouts/start/continue
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "session_id": 123,
    "status": "IN_PROGRESS",
    "exercises": [...]
  }
}
```

#### 운동 완료
```http
PUT /api/v2/workouts/{sessionId}/complete
```

**Request Body:**
```json
{
  "duration": 45,
  "notes": "Good workout",
  "exercises": [
    {
      "exercise_id": 1,
      "sets": [
        {
          "reps": 8,
          "weight": 60.0,
          "rest_time": 90,
          "completed": true
        }
      ]
    }
  ]
}
```

### 1.2 운동 조회

#### 현재 진행 중인 세션
```http
GET /api/workouts/current-session
```

#### 운동 기록
```http
GET /api/workouts/history?page=1&limit=10
```

#### 특정 세션 상세
```http
GET /api/workouts/{sessionId}
```

### 1.3 운동 추천

#### 오늘의 운동 추천
```http
POST /api/v2/workouts/recommendations/today
```

**Request Body:**
```json
{
  "weekly_workout_days": 3,
  "workout_split": "full_body",
  "available_equipment": ["barbell", "dumbbell"],
  "workout_duration": 60
}
```

#### 빠른 운동 추천
```http
GET /api/workouts/recommendations/quick?duration=30&equipment=dumbbell&targetMuscle=chest
```

#### 추천 운동 시작
```http
POST /api/workouts/start-recommended
```

---

## 2. Exercise APIs

### 2.1 운동 목록 조회
```http
GET /api/v2/exercises?category=chest&equipment=barbell&has_gif=true
```

### 2.2 운동 상세 정보
```http
GET /api/v2/exercises/{exerciseId}
```

---

## 3. Statistics APIs

### 3.1 통계 개요
```http
GET /api/v2/stats/overview?period=week
```

### 3.2 볼륨 통계
```http
GET /api/v2/stats/volume?period=month
```

### 3.3 근육별 분포
```http
GET /api/v2/stats/muscle-distribution?period=month
```

### 3.4 개인 기록
```http
GET /api/v2/stats/personal-records
```

### 3.5 진행 상황
```http
GET /api/v2/stats/progress?metric=weight&period=3months
```

### 3.6 운동 완료 통계
```http
GET /api/v2/stats/workout-completion?sessionId=123
```

### 3.7 운동 캘린더
```http
GET /api/v2/stats/calendar?year=2025&month=1
```

### 3.8 주간 통계
```http
GET /api/v2/stats/weekly
```

---

## 4. User APIs

### 4.1 프로필 조회
```http
GET /api/users/profile
```

### 4.2 프로필 업데이트
```http
PUT /api/users/profile
```

### 4.3 설정 조회
```http
GET /api/users/settings
```

### 4.4 설정 업데이트
```http
PUT /api/users/settings
```

---

## 5. Program APIs

### 5.1 프로그램 생성
```http
POST /api/v2/workouts/generate-program
```

**Request Body:**
```json
{
  "goal": "muscle_building",
  "experience_level": "intermediate",
  "weekly_workout_days": 4,
  "workout_duration": 60,
  "available_equipment": ["barbell", "dumbbell", "cable"],
  "workout_split": "upper_lower"
}
```

### 5.2 운동 계획 업데이트
```http
PUT /api/v2/workouts/plan
```

---

## Error Codes

| Code | Description |
|------|-------------|
| 400 | Bad Request - 잘못된 요청 |
| 401 | Unauthorized - 인증 실패 |
| 403 | Forbidden - 접근 권한 없음 |
| 404 | Not Found - 리소스를 찾을 수 없음 |
| 409 | Conflict - 리소스 충돌 |
| 500 | Internal Server Error - 서버 오류 |

## Common Error Response
```json
{
  "status": "ERROR",
  "code": "WORKOUT001",
  "message": "진행 중인 운동 세션이 이미 있습니다",
  "timestamp": "2025-01-19T10:30:00"
}
```

## Migration Guide (V1 → V2)

### Deprecated Endpoints
| V1 (Deprecated) | V2 (Current) |
|-----------------|--------------|
| POST /api/workouts/start | POST /api/v2/workouts/start/new |
| PUT /api/workouts/{id}/end | PUT /api/v2/workouts/{id}/complete |
| GET /api/stats/* | GET /api/v2/stats/* |
| GET /api/exercises | GET /api/v2/exercises |

### Request Format Changes
1. **운동 시작 시 order_index 필수**
   ```json
   // V1 (Old)
   {
     "exercise_id": 1,
     "sets": 3,
     "target_reps": 10
   }

   // V2 (New)
   {
     "exercise_id": 1,
     "sets": 3,
     "target_reps": 10,
     "order_index": 0  // 필수 추가
   }
   ```

2. **JSON Property 명명 규칙**
   - V2에서는 snake_case 사용 (exercise_id, target_reps)
   - 응답도 동일하게 snake_case로 통일

### Response Format Changes
- V2 응답에는 추가 메타데이터 포함
- 통계 정보가 더 상세하게 제공됨
- 실시간 업데이트를 위한 타임스탬프 추가

---

## Rate Limiting
- 분당 60 requests per user
- 시간당 1000 requests per user

## Webhook Events (Coming Soon)
- workout.started
- workout.completed
- achievement.unlocked
- personal_record.broken
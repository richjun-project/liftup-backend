# V2 API 테스트 가이드

## 🔍 현재 사용 가능한 V2 엔드포인트

### ✅ Workout 엔드포인트
```bash
# 현재 진행 중인 세션 조회
GET /api/v2/workouts/current-session

# 운동 기록 조회
GET /api/v2/workouts/history?page=1&limit=10

# 특정 세션 상세 조회
GET /api/v2/workouts/{sessionId}

# 프로그램 상태 조회
GET /api/v2/workouts/program-status

# 새 운동 시작
POST /api/v2/workouts/start/new

# 진행 중인 운동 이어하기
POST /api/v2/workouts/start/continue

# 운동 완료
PUT /api/v2/workouts/{sessionId}/complete

# 세트 업데이트
POST /api/v2/workouts/{sessionId}/sets/update

# 다음 세트 조정
POST /api/v2/workouts/adjust-next-set

# 휴식 타이머 추천
GET /api/v2/workouts/rest-timer?exerciseType=compound&intensity=high&setNumber=1

# 운동 계획 업데이트
PUT /api/v2/workouts/plan

# 프로그램 생성
POST /api/v2/workouts/generate-program

# 오늘의 운동 추천
POST /api/v2/workouts/recommendations/today

# 빠른 운동 추천
GET /api/v2/workouts/recommendations/quick?duration=30&equipment=dumbbell&targetMuscle=chest

# 추천 운동 시작
POST /api/v2/workouts/start-recommended
```

### ✅ Exercise 엔드포인트
```bash
# 운동 목록
GET /api/v2/exercises?category=chest&equipment=barbell&has_gif=true

# 운동 상세
GET /api/v2/exercises/{exerciseId}
```

### ✅ Stats 엔드포인트
```bash
# 통계 개요
GET /api/v2/stats/overview?period=week

# 볼륨 통계
GET /api/v2/stats/volume?period=month

# 근육 분포
GET /api/v2/stats/muscle-distribution?period=month

# 개인 기록
GET /api/v2/stats/personal-records

# 진행 상황
GET /api/v2/stats/progress?metric=weight&period=3months

# 운동 완료 통계
GET /api/v2/stats/workout-completion?sessionId=123

# 운동 캘린더
GET /api/v2/stats/calendar?year=2025&month=1

# 주간 통계
GET /api/v2/stats/weekly
```

---

## 🧪 cURL 테스트 예제

### 1. 현재 세션 조회
```bash
curl -X GET "http://localhost:8080/api/v2/workouts/current-session" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Accept: application/json"
```

### 2. 새 운동 시작
```bash
curl -X POST "http://localhost:8080/api/v2/workouts/start/new" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "workout_type": "CUSTOM",
    "exercises": [
      {
        "exercise_id": 1,
        "sets": 3,
        "target_reps": 10,
        "weight": 60.0,
        "order_index": 0
      }
    ]
  }'
```

### 3. 운동 완료
```bash
curl -X PUT "http://localhost:8080/api/v2/workouts/123/complete" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "duration": 45,
    "notes": "Good workout",
    "exercises": [
      {
        "exercise_id": 1,
        "sets": [
          {
            "reps": 10,
            "weight": 60.0,
            "rest_time": 90,
            "completed": true
          }
        ]
      }
    ]
  }'
```

### 4. 운동 기록 조회
```bash
curl -X GET "http://localhost:8080/api/v2/workouts/history?page=1&limit=10" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Accept: application/json"
```

### 5. 빠른 운동 추천
```bash
curl -X GET "http://localhost:8080/api/v2/workouts/recommendations/quick?duration=30&equipment=dumbbell" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Accept: application/json"
```

---

## 🐛 일반적인 에러 해결

### 1. NoResourceFoundException
**에러:**
```
NoResourceFoundException: No static resource api/v2/workouts/current-session
```

**원인:** 엔드포인트가 존재하지 않음

**해결:**
- 서버를 재시작하여 새로 추가된 엔드포인트 반영
- URL 경로 확인 (`/api/v2/...`)

### 2. 409 Conflict - 진행 중인 운동
**에러:**
```json
{
  "status": "ERROR",
  "code": "WORKOUT001",
  "message": "진행 중인 운동 세션이 이미 있습니다"
}
```

**해결:**
```bash
# 옵션 1: 진행 중인 운동 이어하기
POST /api/v2/workouts/start/continue

# 옵션 2: 기존 세션 완료 후 새로 시작
PUT /api/v2/workouts/{sessionId}/complete
POST /api/v2/workouts/start/new
```

### 3. 400 Bad Request - order_index 누락
**에러:**
```json
{
  "status": "ERROR",
  "code": "VALIDATION_ERROR",
  "message": "order_index is required"
}
```

**해결:** exercises 배열의 각 항목에 order_index 추가
```json
{
  "exercises": [
    {
      "exercise_id": 1,
      "order_index": 0  // 필수!
    }
  ]
}
```

---

## 📋 체크리스트

### Backend 확인사항
- [x] WorkoutControllerV2에 모든 필요한 엔드포인트 추가
- [x] current-session 엔드포인트 추가
- [x] history 엔드포인트 추가
- [x] program-status 엔드포인트 추가
- [x] recommendations/quick 엔드포인트 추가
- [x] start-recommended 엔드포인트 추가

### Flutter 수정사항
- [ ] API Base URL을 /api/v2로 변경
- [ ] 모든 엔드포인트 경로 업데이트
- [ ] PlannedExercise에 order_index 필드 추가
- [ ] 에러 핸들링 업데이트 (409 처리)
- [ ] 새로운 엔드포인트 테스트

---

## 📝 Postman Collection

Postman에서 import할 수 있는 collection:

```json
{
  "info": {
    "name": "LiftUp AI V2 API",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Workout",
      "item": [
        {
          "name": "Get Current Session",
          "request": {
            "method": "GET",
            "header": [
              {
                "key": "Authorization",
                "value": "Bearer {{token}}"
              }
            ],
            "url": {
              "raw": "{{baseUrl}}/api/v2/workouts/current-session",
              "host": ["{{baseUrl}}"],
              "path": ["api", "v2", "workouts", "current-session"]
            }
          }
        },
        {
          "name": "Start New Workout",
          "request": {
            "method": "POST",
            "header": [
              {
                "key": "Authorization",
                "value": "Bearer {{token}}"
              },
              {
                "key": "Content-Type",
                "value": "application/json"
              }
            ],
            "body": {
              "mode": "raw",
              "raw": "{\n  \"workout_type\": \"CUSTOM\",\n  \"exercises\": [\n    {\n      \"exercise_id\": 1,\n      \"sets\": 3,\n      \"target_reps\": 10,\n      \"weight\": 60.0,\n      \"order_index\": 0\n    }\n  ]\n}"
            },
            "url": {
              "raw": "{{baseUrl}}/api/v2/workouts/start/new",
              "host": ["{{baseUrl}}"],
              "path": ["api", "v2", "workouts", "start", "new"]
            }
          }
        }
      ]
    }
  ],
  "variable": [
    {
      "key": "baseUrl",
      "value": "http://localhost:8080",
      "type": "string"
    },
    {
      "key": "token",
      "value": "YOUR_JWT_TOKEN",
      "type": "string"
    }
  ]
}
```
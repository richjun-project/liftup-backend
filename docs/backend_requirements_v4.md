# Backend API Requirements V4 - User Profile & Workout Plan

## 개요
Flutter 앱에서 사용자 프로필 및 운동 계획 기능이 구현되었으나, 백엔드 API가 없어 데이터가 서버와 동기화되지 않습니다.

## 필요한 API 엔드포인트

### 1. 사용자 프로필 API

#### 1.1 프로필 조회
```
GET /api/users/profile
Authorization: Bearer {token}

Response:
{
  "success": true,
  "data": {
    "id": "user_id",
    "nickname": "운동매니아",
    "experienceLevel": "중급",
    "goals": ["근육량 증가", "체지방 감소"],
    "height": 175.0,
    "weight": 70.0,
    "age": 28,
    "gender": "남성",
    "ptStyle": "friendly",
    "notificationEnabled": true,
    "weeklyWorkoutDays": 4,
    "workoutSplit": "upper_lower",
    "availableEquipment": ["덤벨", "바벨", "케이블"],
    "preferredWorkoutTime": "evening",
    "workoutDuration": 60,
    "injuries": [],
    "currentProgram": "중급_upper_lower",
    "currentWeek": 1,
    "lastWorkoutDate": "2024-01-15T18:30:00Z",
    "muscleRecovery": {
      "가슴": "2024-01-15T18:30:00Z",
      "등": "2024-01-14T18:30:00Z"
    },
    "strengthTestCompleted": false,
    "estimatedMaxes": {},
    "workingWeights": {},
    "strengthLevel": "intermediate",
    "createdAt": "2024-01-01T00:00:00Z",
    "updatedAt": "2024-01-15T18:30:00Z"
  }
}
```

#### 1.2 프로필 생성 (온보딩 완료 시)
```
POST /api/users/profile
Authorization: Bearer {token}
Content-Type: application/json

Request:
{
  "nickname": "운동매니아",
  "experienceLevel": "중급",
  "goals": ["근육량 증가", "체지방 감소"],
  "height": 175.0,
  "weight": 70.0,
  "age": 28,
  "gender": "남성",
  "ptStyle": "friendly",
  "notificationEnabled": true,
  "weeklyWorkoutDays": 4,
  "workoutSplit": "upper_lower",
  "availableEquipment": ["덤벨", "바벨"],
  "preferredWorkoutTime": "evening",
  "workoutDuration": 60,
  "injuries": []
}

Response:
{
  "success": true,
  "data": {
    "message": "프로필이 생성되었습니다",
    "profile": { /* 전체 프로필 객체 */ }
  }
}
```

#### 1.3 프로필 업데이트
```
PUT /api/users/profile
Authorization: Bearer {token}
Content-Type: application/json

Request (부분 업데이트 가능):
{
  "weeklyWorkoutDays": 5,
  "workoutSplit": "ppl",
  "preferredWorkoutTime": "morning"
}

Response:
{
  "success": true,
  "data": {
    "message": "프로필이 업데이트되었습니다",
    "profile": { /* 업데이트된 프로필 객체 */ }
  }
}
```

### 2. 운동 계획 API

#### 2.1 운동 계획 업데이트
```
PUT /api/workouts/plan
Authorization: Bearer {token}
Content-Type: application/json

Request:
{
  "weeklyWorkoutDays": 4,
  "workoutSplit": "upper_lower",
  "preferredWorkoutTime": "evening",
  "workoutDuration": 60,
  "availableEquipment": ["덤벨", "바벨", "케이블"]
}

Response:
{
  "success": true,
  "data": {
    "message": "운동 계획이 업데이트되었습니다",
    "plan": {
      "weeklyWorkoutDays": 4,
      "workoutSplit": "upper_lower",
      "preferredWorkoutTime": "evening",
      "workoutDuration": 60,
      "availableEquipment": ["덤벨", "바벨", "케이블"],
      "recommendedProgram": "중급 상하체 분할 프로그램"
    }
  }
}
```

#### 2.2 맞춤형 운동 프로그램 생성
```
POST /api/workouts/generate-program
Authorization: Bearer {token}
Content-Type: application/json

Request:
{
  "weeklyWorkoutDays": 4,
  "workoutSplit": "upper_lower",
  "experienceLevel": "중급",
  "goals": ["근육량 증가"],
  "availableEquipment": ["덤벨", "바벨"],
  "duration": 60
}

Response:
{
  "success": true,
  "data": {
    "programName": "4일 상하체 분할 근육량 증가 프로그램",
    "weeks": 8,
    "schedule": {
      "monday": {
        "name": "상체 A",
        "exercises": [
          {
            "name": "벤치프레스",
            "sets": 4,
            "reps": "8-10",
            "rest": 120
          }
          // ... more exercises
        ]
      },
      "tuesday": "휴식",
      "wednesday": {
        "name": "하체 A",
        "exercises": [/* ... */]
      }
      // ... rest of week
    }
  }
}
```

### 3. 개선된 추천 API

#### 3.1 오늘의 운동 추천 (프로필 기반)
```
POST /api/workouts/recommendations/today
Authorization: Bearer {token}
Content-Type: application/json

Request:
{
  "weeklyWorkoutDays": 4,
  "workoutSplit": "upper_lower",
  "experienceLevel": "중급",
  "goals": ["근육량 증가", "체지방 감소"],
  "lastWorkoutDate": "2024-01-15T18:30:00Z",
  "muscleRecovery": {
    "가슴": "2024-01-15T18:30:00Z",
    "등": "2024-01-14T18:30:00Z"
  }
}

Response:
{
  "success": true,
  "data": {
    "workoutName": "상체 B - 등/이두",
    "targetMuscles": ["등", "이두"],
    "estimatedDuration": 60,
    "exercises": [
      {
        "id": "1",
        "name": "풀업",
        "targetMuscle": "등",
        "sets": [
          {
            "setNumber": 1,
            "reps": 8,
            "weight": 0,
            "type": "working"
          }
        ],
        "restTime": 120,
        "tips": "광배근에 집중하세요"
      }
      // ... more exercises
    ],
    "reason": "마지막 등 운동으로부터 48시간이 경과했고, 주 4일 상하체 분할 프로그램에 따라 오늘은 상체 운동일입니다."
  }
}
```

### 4. 통계 API 개선

#### 4.1 주간 운동 통계
```
GET /api/stats/weekly
Authorization: Bearer {token}

Response:
{
  "success": true,
  "data": {
    "targetDays": 4,
    "completedDays": 2,
    "totalVolume": 12450,
    "totalSets": 48,
    "totalReps": 384,
    "workoutDates": [
      "2024-01-15",
      "2024-01-13"
    ],
    "nextWorkoutDay": "2024-01-17",
    "weeklyProgress": 50 // percentage
  }
}
```

## 데이터베이스 스키마 추가 필요

### UserProfile 테이블
```sql
CREATE TABLE user_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    experience_level VARCHAR(20) NOT NULL,
    goals JSON,
    height DECIMAL(5,2),
    weight DECIMAL(5,2),
    age INT,
    gender VARCHAR(10),
    pt_style VARCHAR(20),
    notification_enabled BOOLEAN DEFAULT true,
    weekly_workout_days INT DEFAULT 3,
    workout_split VARCHAR(20) DEFAULT 'full_body',
    available_equipment JSON,
    preferred_workout_time VARCHAR(20) DEFAULT 'evening',
    workout_duration INT DEFAULT 60,
    injuries JSON,
    current_program VARCHAR(100),
    current_week INT DEFAULT 1,
    last_workout_date TIMESTAMP,
    muscle_recovery JSON,
    strength_test_completed BOOLEAN DEFAULT false,
    estimated_maxes JSON,
    working_weights JSON,
    strength_level VARCHAR(20) DEFAULT 'beginner',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### WorkoutPlan 테이블
```sql
CREATE TABLE workout_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    weekly_days INT NOT NULL,
    split_type VARCHAR(20) NOT NULL,
    program_duration_weeks INT DEFAULT 8,
    schedule JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

## 구현 우선순위

1. **높음**: 프로필 CRUD API (조회, 생성, 업데이트)
2. **높음**: 운동 계획 업데이트 API
3. **중간**: 프로필 기반 추천 API 개선
4. **중간**: 주간 통계 API
5. **낮음**: 맞춤형 프로그램 생성 API

## 참고사항

- Flutter 앱은 이미 UserProfile 모델과 Provider를 구현했으므로, API 응답 형식을 맞춰주세요
- 온보딩 완료 시 자동으로 프로필이 생성되도록 `/api/auth/register` 엔드포인트와 연동 필요
- 운동 추천 시 사용자의 회복 상태와 운동 분할을 고려해야 함
- 모든 날짜/시간은 ISO 8601 형식 사용
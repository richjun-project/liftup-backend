# LiftUp AI API 명세서

## 기본 정보

### Base URL
```
http://localhost:8080/api
```

### 인증 방식
- JWT Bearer Token
- 헤더: `Authorization: Bearer {accessToken}`

### 공통 응답 형식
모든 API는 다음 형식으로 응답합니다:

#### 성공 응답
```json
{
  "success": true,
  "data": {
    // 응답 데이터
  },
  "error": null,
  "timestamp": "2024-01-20T10:30:00"
}
```

#### 에러 응답
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "에러 메시지",
    "details": {} // 선택적
  },
  "timestamp": "2024-01-20T10:30:00"
}
```

### 에러 코드
- `AUTH001`: 인증 실패
- `AUTH003`: 권한 부족
- `VALID001`: 유효성 검사 실패
- `VALID002`: 중복된 리소스
- `NOT_FOUND`: 리소스를 찾을 수 없음
- `SERVER001`: 서버 내부 오류

---

## 1. 인증 (Authentication)

### 1.1 회원가입
**POST** `/auth/register`

**요청 본문:**
```json
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "홍길동"
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "user_id": 1,
    "access_token": "eyJhbGciOiJIUzUxMiJ9...",
    "refresh_token": "eyJhbGciOiJIUzUxMiJ9...",
    "profile": null
  }
}
```

**유효성 검사:**
- 이메일: 유효한 이메일 형식
- 비밀번호: 최소 6자 이상
- 닉네임: 2-20자

### 1.2 로그인
**POST** `/auth/login`

**요청 본문:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "user_id": 1,
    "access_token": "eyJhbGciOiJIUzUxMiJ9...",
    "refresh_token": "eyJhbGciOiJIUzUxMiJ9...",
    "profile": {
      "user_id": 1,
      "email": "user@example.com",
      "nickname": "홍길동",
      "level": "BEGINNER",
      "join_date": "2024-01-20T10:30:00",
      "body_info": null,
      "goals": [],
      "pt_style": "FRIENDLY",
      "subscription": {
        "status": "FREE",
        "expired_at": null
      }
    }
  }
}
```

### 1.3 토큰 갱신
**POST** `/auth/refresh`

**요청 본문:**
```json
{
  "refresh_token": "eyJhbGciOiJIUzUxMiJ9..."
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "user_id": 1,
    "access_token": "새로운_액세스_토큰",
    "refresh_token": "새로운_리프레시_토큰"
  }
}
```

### 1.4 로그아웃
**POST** `/auth/logout`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "success": true
  }
}
```

### 1.5 디바이스 회원가입
**POST** `/auth/device/register`

**요청 본문:**
```json
{
  "deviceId": "unique_device_identifier",
  "nickname": "사용자닉네임",
  "experienceLevel": "beginner",
  "goals": ["muscle_gain", "strength"],
  "ptStyle": "friendly",
  "bodyInfo": {
    "height": 175,
    "weight": 70,
    "age": 25,
    "gender": "male"
  },
  "workoutPreferences": {
    "weeklyDays": 3,
    "workoutSplit": "full_body",
    "preferredWorkoutTime": "evening",
    "workoutDuration": 60,
    "availableEquipment": ["dumbbell", "barbell"]
  },
  "deviceInfo": {
    "model": "iPhone 14",
    "osVersion": "iOS 17.0"
  }
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "user": {
      "id": 1,
      "deviceId": "unique_device_identifier",
      "nickname": "사용자닉네임",
      "isDeviceAccount": true,
      "deviceRegisteredAt": "2024-01-20T10:30:00"
    },
    "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
  }
}
```

### 1.6 디바이스 로그인
**POST** `/auth/device/login`

**요청 본문:**
```json
{
  "deviceId": "unique_device_identifier"
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "user": {
      "id": 1,
      "deviceId": "unique_device_identifier",
      "nickname": "사용자닉네임",
      "isDeviceAccount": true,
      "deviceRegisteredAt": "2024-01-20T10:30:00"
    },
    "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
  }
}
```

### 1.7 디바이스 체크
**GET** `/auth/device/check?deviceId={deviceId}`

**응답:**
```json
{
  "success": true,
  "data": {
    "exists": false,
    "message": "새로운 디바이스입니다"
  }
}
```

### 1.8 닉네임 중복 확인
**GET** `/auth/check-nickname?nickname={nickname}`

**응답:**
```json
{
  "success": true,
  "data": {
    "available": true,
    "message": "사용 가능한 닉네임입니다"
  }
}
```

---

## 2. 사용자 프로필 (User Profile)

### 2.1 프로필 조회
**GET** `/users/profile`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "user_id": 1,
    "email": "user@example.com",
    "nickname": "홍길동",
    "level": "INTERMEDIATE",
    "join_date": "2024-01-20T10:30:00",
    "body_info": {
      "height": 175.5,
      "weight": 70.0,
      "body_fat": 15.0,
      "muscle_mass": 30.0
    },
    "goals": ["MUSCLE_GAIN", "FAT_LOSS"],
    "pt_style": "STRICT"
  }
}
```

### 2.2 프로필 수정
**PUT** `/users/profile`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "nickname": "새닉네임",
  "height": 175.5,
  "weight": 70.0,
  "body_fat": 15.0,
  "muscle_mass": 30.0,
  "goals": ["MUSCLE_GAIN"],
  "pt_style": "FRIENDLY"
}
```

### 2.3 설정 변경
**PUT** `/users/settings`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "language": "ko",
  "unit_system": "METRIC",
  "notification_enabled": true,
  "workout_reminder_time": "08:00",
  "rest_day_reminder": true
}
```

---

## 3. 운동 세션 (Workout Sessions) - V2

### 3.1 운동 시작
**POST** `/v2/workouts/start`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "workout_type": "PUSH",
  "exercises": [
    {
      "exercise_id": 1,
      "sets": 4,
      "target_reps": 12,
      "weight": 60.0
    }
  ]
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "session_id": 123,
    "started_at": "2024-01-20T10:30:00",
    "exercises": [
      {
        "exercise_id": 1,
        "name": "벤치프레스",
        "sets": 4,
        "target_reps": 12,
        "recommended_weight": 60.0
      }
    ]
  }
}
```

### 3.2 운동 완료
**PUT** `/v2/workouts/{sessionId}/complete`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "ended_at": "2024-01-20T11:30:00",
  "total_volume": 2880.0,
  "notes": "오늘 컨디션 좋았음"
}
```

### 3.3 세트 업데이트
**POST** `/v2/workouts/{sessionId}/sets/update`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "exercise_id": 1,
  "set_number": 1,
  "weight": 60.0,
  "reps": 12,
  "rest_time": 90
}
```

### 3.4 다음 세트 조정
**POST** `/v2/workouts/adjust-next-set`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "exercise_id": 1,
  "last_set_difficulty": "HARD",
  "completed_reps": 10,
  "target_reps": 12
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "recommended_weight": 55.0,
    "recommended_reps": 10,
    "rest_time": 120
  }
}
```

### 3.5 휴식 타이머
**GET** `/v2/workouts/rest-timer`

**인증 필요:** ✅

**쿼리 파라미터:**
- `exerciseType`: COMPOUND | ISOLATION
- `intensity`: LOW | MEDIUM | HIGH
- `setNumber`: 세트 번호

**응답:**
```json
{
  "success": true,
  "data": {
    "rest_seconds": 90,
    "min_rest": 60,
    "max_rest": 120
  }
}
```

### 3.6 오프라인 동기화
**POST** `/v2/sync/offline-workouts`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "workouts": [
    {
      "local_id": "offline_123",
      "workout_data": { /* 운동 데이터 */ },
      "created_at": "2024-01-20T10:30:00"
    }
  ]
}
```

---

## 4. 운동 정보 (Exercises)

### 4.1 운동 목록 조회
**GET** `/v2/exercises`

**인증 필요:** ✅

**쿼리 파라미터:**
- `muscle_group`: CHEST | BACK | LEGS | SHOULDERS | ARMS | CORE
- `equipment`: BARBELL | DUMBBELL | MACHINE | CABLE | BODYWEIGHT
- `difficulty`: BEGINNER | INTERMEDIATE | ADVANCED

**응답:**
```json
{
  "success": true,
  "data": {
    "exercises": [
      {
        "id": 1,
        "name": "벤치프레스",
        "muscle_group": "CHEST",
        "equipment": "BARBELL",
        "difficulty": "INTERMEDIATE",
        "instructions": "설명..."
      }
    ]
  }
}
```

### 4.2 운동 상세 조회
**GET** `/v2/exercises/{exerciseId}/details`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "벤치프레스",
    "muscle_group": "CHEST",
    "secondary_muscles": ["TRICEPS", "SHOULDERS"],
    "equipment": "BARBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "자세한 설명...",
    "tips": ["팁1", "팁2"],
    "common_mistakes": ["실수1", "실수2"]
  }
}
```

---

## 5. 통계 (Statistics)

### 5.1 운동 완료 통계
**GET** `/v2/stats/workout-completion`

**인증 필요:** ✅

**쿼리 파라미터:**
- `sessionId`: (선택) 특정 세션 ID

**응답:**
```json
{
  "success": true,
  "data": {
    "total_workouts": 50,
    "this_week": 3,
    "this_month": 12,
    "current_streak": 5,
    "longest_streak": 15,
    "favorite_exercise": "벤치프레스",
    "total_volume": 150000.0
  }
}
```

### 5.2 캘린더 데이터
**GET** `/v2/stats/calendar`

**인증 필요:** ✅

**쿼리 파라미터:**
- `year`: 년도 (예: 2024)
- `month`: 월 (1-12)

**응답:**
```json
{
  "success": true,
  "data": {
    "year": 2024,
    "month": 1,
    "workout_days": [
      {
        "date": "2024-01-15",
        "workout_type": "PUSH",
        "duration_minutes": 60,
        "exercises_count": 5
      }
    ]
  }
}
```

---

## 6. AI 채팅 (Chat)

### 6.1 메시지 전송
**POST** `/chat/send`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "message": "오늘 가슴 운동 추천해줘",
  "context": "WORKOUT_ADVICE"
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "response": "AI 응답 메시지...",
    "suggestions": ["벤치프레스", "덤벨 플라이"]
  }
}
```

### 6.2 채팅 기록 조회
**GET** `/chat/history`

**인증 필요:** ✅

**쿼리 파라미터:**
- `page`: 페이지 번호 (기본값: 1)
- `limit`: 페이지당 항목 수 (기본값: 20)
- `date`: (선택) 특정 날짜 필터 (YYYY-MM-DD 또는 ISO 8601)

**응답:**
```json
{
  "success": true,
  "data": {
    "messages": [
      {
        "id": 1,
        "role": "USER",
        "content": "오늘 가슴 운동 추천해줘",
        "timestamp": "2024-01-20T10:30:00"
      },
      {
        "id": 2,
        "role": "AI",
        "content": "AI 응답...",
        "timestamp": "2024-01-20T10:30:05"
      }
    ],
    "has_more": false
  }
}
```

---

## 7. 소셜 기능 (Social)

### 7.1 운동 공유
**POST** `/v2/social/share-workout`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "session_id": 123,
  "message": "오늘 운동 완료!",
  "visibility": "PUBLIC"
}
```

### 7.2 파트너 찾기
**GET** `/v2/social/find-partners`

**인증 필요:** ✅

**쿼리 파라미터:**
- `level`: BEGINNER | INTERMEDIATE | ADVANCED
- `goals`: MUSCLE_GAIN | FAT_LOSS | STRENGTH
- `location`: 지역

**응답:**
```json
{
  "success": true,
  "data": {
    "partners": [
      {
        "user_id": 2,
        "nickname": "운동매니아",
        "level": "INTERMEDIATE",
        "goals": ["MUSCLE_GAIN"],
        "compatibility_score": 85
      }
    ]
  }
}
```

---

## 8. 알림 (Notifications)

### 8.1 디바이스 등록
**POST** `/notifications/register`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "device_token": "fcm_token_here",
  "device_type": "IOS",
  "app_version": "1.0.0"
}
```

### 8.2 알림 설정 조회
**GET** `/notifications/settings`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "workout_reminder": true,
    "achievement_alerts": true,
    "social_notifications": true,
    "newsletter": false
  }
}
```

### 8.3 알림 설정 변경
**PUT** `/notifications/settings`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "workout_reminder": true,
  "achievement_alerts": false,
  "social_notifications": true,
  "newsletter": false
}
```

### 8.4 운동 리마인더 설정
**POST** `/notifications/schedule/workout`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "days": ["MON", "WED", "FRI"],
  "time": "18:00",
  "message": "운동할 시간입니다!"
}
```

**응답:**
```json
{
  "success": true,
  "data": {
    "schedule_id": 1,
    "days": ["MON", "WED", "FRI"],
    "time": "18:00"
  }
}
```

### 8.5 리마인더 삭제
**DELETE** `/notifications/schedule/workout/{scheduleId}`

**인증 필요:** ✅

### 8.6 알림 히스토리
**GET** `/notifications/history`

**인증 필요:** ✅

**쿼리 파라미터:**
- `page`: 페이지 번호
- `limit`: 페이지당 항목 수
- `unread_only`: 읽지 않은 것만

### 8.7 알림 읽음 처리
**PUT** `/notifications/{notificationId}/read`

**인증 필요:** ✅

---

## 9. 회복 관리 (Recovery)

### 9.1 회복 상태 조회
**GET** `/recovery/status`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "overall_recovery": 85,
    "muscle_groups": {
      "chest": 90,
      "back": 80,
      "legs": 75,
      "shoulders": 95,
      "arms": 88
    },
    "recommended_workout": "PULL",
    "rest_needed": false
  }
}
```

### 9.2 회복 활동 기록
**POST** `/recovery/activity`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "activity_type": "STRETCHING",
  "duration_minutes": 20,
  "notes": "요가 20분"
}
```

### 9.3 회복 히스토리
**GET** `/recovery/history`

**인증 필요:** ✅

**쿼리 파라미터:**
- `days`: 최근 N일 (기본값: 7)

---

## 10. 구독 관리 (Subscription)

### 10.1 구독 상태 조회
**GET** `/subscription/status`

**인증 필요:** ✅

**응답:**
```json
{
  "success": true,
  "data": {
    "status": "PREMIUM",
    "plan": "MONTHLY",
    "started_at": "2024-01-01T00:00:00",
    "expires_at": "2024-02-01T00:00:00",
    "auto_renew": true,
    "features": [
      "AI_COACHING",
      "UNLIMITED_WORKOUTS",
      "ADVANCED_STATS",
      "NUTRITION_TRACKING"
    ]
  }
}
```

---

## 11. 운동 추천 (Recommendations)

### 11.1 빠른 운동 추천
**GET** `/workouts/recommendations/quick`

**인증 필요:** ✅

**쿼리 파라미터:**
- `duration`: 운동 시간 (분)
- `equipment`: 사용 가능한 장비
- `targetMuscle`: 타겟 근육

**응답:**
```json
{
  "success": true,
  "data": {
    "workout_name": "15분 가슴 운동",
    "duration_minutes": 15,
    "exercises": [
      {
        "exercise_id": 1,
        "name": "푸시업",
        "sets": 3,
        "reps": 15,
        "rest_seconds": 30
      }
    ]
  }
}
```

### 11.2 추천 운동 시작
**POST** `/workouts/start-recommended`

**인증 필요:** ✅

**요청 본문:**
```json
{
  "recommendation_id": "quick_chest_15",
  "exercises": [
    {
      "exercise_id": 1,
      "sets": 3,
      "target_reps": 15
    }
  ]
}
```

---

## 12. 이미지 업로드 (Upload)

### 12.1 이미지 업로드
**POST** `/upload/image`

**인증 필요:** ✅

**Content-Type:** `multipart/form-data`

**Form Data:**
- `file`: 이미지 파일 (최대 10MB, JPEG/PNG/GIF/WebP)
- `type`: 이미지 타입 (profile | workout | meal | form_check | chat)
- `metadata`: (선택) 추가 메타데이터 JSON 문자열

**응답:**
```json
{
  "success": true,
  "data": {
    "success": true,
    "imageUrl": "/uploads/profile/1/uuid.jpg",
    "thumbnailUrl": "/uploads/profile/1/uuid_thumb.jpg",
    "imageId": "uuid-string",
    "uploadedAt": "2024-01-20T10:30:00Z"
  }
}
```

---

## 날짜 형식 지원

모든 날짜 파라미터는 다음 형식을 모두 지원합니다:
- 날짜만: `2024-01-20`
- ISO 8601: `2024-01-20T10:30:00`
- ISO 8601 with timezone: `2024-01-20T10:30:00Z`

---

## HTTP 상태 코드

- `200 OK`: 성공적인 조회/수정
- `201 Created`: 성공적인 생성
- `400 Bad Request`: 잘못된 요청
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 권한 부족
- `404 Not Found`: 리소스를 찾을 수 없음
- `409 Conflict`: 중복된 리소스
- `500 Internal Server Error`: 서버 오류

---

## 페이지네이션

페이지네이션이 필요한 엔드포인트는 다음 파라미터를 사용합니다:
- `page`: 페이지 번호 (1부터 시작)
- `limit`: 페이지당 항목 수 (기본값: 20, 최대: 100)

응답에는 다음 정보가 포함됩니다:
```json
{
  "data": [...],
  "pagination": {
    "page": 1,
    "limit": 20,
    "totalPages": 5,
    "totalCount": 95,
    "hasNext": true,
    "hasPrev": false
  }
}
```

---

## 테스트 계정

개발 환경에서 사용 가능한 테스트 계정:
- 이메일: `test@example.com`
- 비밀번호: `test123`

---

## 주의사항

1. **인증 토큰**: Access Token은 1시간, Refresh Token은 7일간 유효합니다.
2. **Rate Limiting**: API 호출은 분당 60회로 제한됩니다 (추후 구현 예정).
3. **파일 업로드**: 이미지는 최대 10MB, 지원 형식은 JPEG, PNG, GIF, WebP입니다.
4. **날짜 형식**: 모든 날짜는 UTC 기준이며, 클라이언트에서 로컬 시간대로 변환해야 합니다.

---

## 변경 이력

### v1.0.0 (2024-01-20)
- 초기 API 명세서 작성
- 모든 필수 엔드포인트 포함
- Flutter 앱 연동을 위한 형식 통일
# LiftUp AI 백엔드 API 요구사항 V3

## 변경 사항 요약 (V2 → V3)
- Flutter 앱과 백엔드 API 불일치 해결
- 누락된 필수 API 8개 추가
- 이미지 업로드 기능 구현
- 알림 스케줄링 및 히스토리 관리 추가
- 회복 활동 추적 기능 보완
- 빠른 운동 추천 API 추가

## 🚨 긴급 구현 필요 API (Flutter 앱 필수)

### 1. 이미지 업로드 API
- **POST** `/api/upload/image`
  - Header: `Authorization: Bearer {token}`
  - Request: `multipart/form-data`
    ```
    file: File (image/jpeg, image/png, image/gif)
    type: "chat" | "meal" | "form_check" | "profile"
    metadata: {
      "originalName": "string",
      "mimeType": "string",
      "size": number
    }
    ```
  - Response:
    ```json
    {
      "success": true,
      "imageUrl": "https://cdn.liftupai.com/images/abc123.jpg",
      "thumbnailUrl": "https://cdn.liftupai.com/thumbnails/abc123.jpg",
      "imageId": "string",
      "uploadedAt": "ISO 8601"
    }
    ```
  - 제약사항:
    - 최대 파일 크기: 10MB
    - 지원 포맷: JPEG, PNG, GIF, WebP
    - 자동 썸네일 생성 (200x200, 400x400)
    - CDN 업로드 및 최적화

### 2. 알림 스케줄링 API

#### 2.1 운동 리마인더 설정
- **POST** `/api/notifications/schedule/workout`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "scheduleName": "아침 운동",
      "days": ["MON", "WED", "FRI"],
      "time": "07:00",
      "enabled": true,
      "message": "오늘도 화이팅! 운동 시간입니다 💪",
      "notificationType": "workout_reminder"
    }
    ```
  - Response:
    ```json
    {
      "scheduleId": "string",
      "nextTriggerAt": "ISO 8601",
      "status": "scheduled",
      "created": true
    }
    ```

#### 2.2 운동 리마인더 취소
- **DELETE** `/api/notifications/schedule/workout/{scheduleId}`
  - Header: `Authorization: Bearer {token}`
  - Response:
    ```json
    {
      "success": true,
      "deletedScheduleId": "string",
      "message": "운동 리마인더가 취소되었습니다"
    }
    ```

### 3. 알림 히스토리 관리

#### 3.1 알림 히스토리 조회
- **GET** `/api/notifications/history`
  - Header: `Authorization: Bearer {token}`
  - Query: `?page=1&limit=20&unreadOnly=false`
  - Response:
    ```json
    {
      "notifications": [
        {
          "notificationId": "string",
          "type": "workout_reminder" | "achievement" | "streak" | "rest_day",
          "title": "운동 시간입니다!",
          "body": "오늘의 가슴 운동을 시작해보세요",
          "data": {
            "workoutId": "string",
            "programDay": 15
          },
          "isRead": false,
          "createdAt": "ISO 8601",
          "readAt": null
        }
      ],
      "pagination": {
        "page": 1,
        "limit": 20,
        "total": 45,
        "hasNext": true
      },
      "unreadCount": 5
    }
    ```

#### 3.2 알림 읽음 처리
- **PUT** `/api/notifications/{notificationId}/read`
  - Header: `Authorization: Bearer {token}`
  - Response:
    ```json
    {
      "success": true,
      "notificationId": "string",
      "readAt": "ISO 8601",
      "unreadCount": 4
    }
    ```

### 4. 회복 활동 추적

#### 4.1 회복 활동 기록
- **POST** `/api/recovery/activity`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "activityType": "stretching" | "foam_rolling" | "massage" | "cold_bath" | "sauna" | "sleep",
      "duration": 30,
      "intensity": "light" | "moderate" | "intense",
      "notes": "운동 후 10분 스트레칭 완료",
      "bodyParts": ["legs", "back"],
      "performedAt": "ISO 8601"
    }
    ```
  - Response:
    ```json
    {
      "activityId": "string",
      "recoveryScore": 85,
      "recoveryBoost": "+5%",
      "nextRecommendation": "다음 운동 전 폼롤링을 추천합니다",
      "recorded": true
    }
    ```

#### 4.2 회복 히스토리 조회
- **GET** `/api/recovery/history`
  - Header: `Authorization: Bearer {token}`
  - Query: `?startDate=2024-01-01&endDate=2024-01-31&activityType=all`
  - Response:
    ```json
    {
      "history": [
        {
          "date": "2024-01-20",
          "activities": [
            {
              "activityId": "string",
              "activityType": "stretching",
              "duration": 15,
              "intensity": "moderate",
              "performedAt": "2024-01-20T20:30:00Z",
              "recoveryImpact": "+3%"
            }
          ],
          "dailyRecoveryScore": 78,
          "muscleSoreness": {
            "overall": 3,
            "details": {
              "legs": 4,
              "chest": 2,
              "back": 3
            }
          }
        }
      ],
      "summary": {
        "totalActivities": 25,
        "mostFrequent": "stretching",
        "averageRecoveryScore": 75,
        "trend": "improving"
      }
    }
    ```

### 5. 빠른 운동 추천

#### 5.1 빠른 운동 추천 조회
- **GET** `/api/workouts/recommendations/quick`
  - Header: `Authorization: Bearer {token}`
  - Query: `?duration=30&equipment=dumbbells&targetMuscle=full_body`
  - Response:
    ```json
    {
      "recommendation": {
        "workoutId": "quick_30min_fullbody",
        "name": "30분 전신 덤벨 운동",
        "duration": 30,
        "difficulty": "intermediate",
        "exercises": [
          {
            "exerciseId": "string",
            "name": "덤벨 스쿼트",
            "sets": 3,
            "reps": "12-15",
            "rest": 60,
            "order": 1
          },
          {
            "exerciseId": "string",
            "name": "덤벨 벤치프레스",
            "sets": 3,
            "reps": "10-12",
            "rest": 90,
            "order": 2
          }
        ],
        "estimatedCalories": 250,
        "targetMuscles": ["legs", "chest", "shoulders"],
        "equipment": ["dumbbells"]
      },
      "alternatives": [
        {
          "workoutId": "quick_20min_core",
          "name": "20분 코어 집중",
          "duration": 20
        }
      ]
    }
    ```

#### 5.2 추천 운동 바로 시작
- **POST** `/api/workouts/start-recommended`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "recommendationId": "quick_30min_fullbody",
      "adjustments": {
        "duration": 25,
        "skipExercises": [],
        "substituteExercises": {}
      }
    }
    ```
  - Response:
    ```json
    {
      "sessionId": "string",
      "workoutName": "30분 전신 덤벨 운동",
      "startTime": "ISO 8601",
      "exercises": [
        {
          "exerciseId": "string",
          "name": "덤벨 스쿼트",
          "plannedSets": 3,
          "plannedReps": "12-15",
          "suggestedWeight": 15,
          "restTimer": 60
        }
      ],
      "estimatedDuration": 25,
      "started": true
    }
    ```

## 기존 V2 API 유지 (변경 없음)

### 운동 세션 관리
- POST `/api/v2/workouts/start`
- PUT `/api/v2/workouts/{sessionId}/complete`
- POST `/api/v2/workouts/{sessionId}/sets/update`
- POST `/api/v2/workouts/adjust-next-set`
- GET `/api/v2/workouts/rest-timer`

### 운동 데이터
- GET `/api/v2/exercises`
- GET `/api/v2/exercises/{exerciseId}/details`

### 통계 및 진행도
- GET `/api/v2/stats/workout-completion`
- GET `/api/v2/stats/calendar`

### 소셜 기능
- POST `/api/v2/social/share-workout`
- GET `/api/v2/social/find-partners`

### 데이터 동기화
- POST `/api/v2/sync/offline-workouts`

## 구현 우선순위

### 🔴 최우선 (Flutter 앱 필수)
1. **이미지 업로드** - 채팅 및 AI 분석 기능에 필수
2. **알림 스케줄링** - 사용자 리텐션의 핵심 기능
3. **알림 히스토리** - 알림 관리 UI 지원

### 🟡 높음 (사용자 경험 향상)
4. **회복 활동 추적** - 운동 후 회복 관리
5. **빠른 운동 추천** - 즉시 시작 가능한 운동 제공

### 🟢 보통 (기존 기능 유지)
- V2 API 유지보수 및 최적화
- 에러 처리 개선
- 성능 모니터링

## 기술 구현 가이드

### 이미지 업로드 구현
```kotlin
// FileUploadController.kt
@RestController
@RequestMapping("/api/upload")
class FileUploadController(
    private val fileUploadService: FileUploadService,
    private val s3Service: S3Service
) {
    @PostMapping("/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("type") type: String,
        @RequestParam("metadata") metadata: String,
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ImageUploadResponse> {
        // 파일 검증
        validateImageFile(file)

        // S3 업로드
        val imageUrl = s3Service.uploadImage(file, type, user.id)

        // 썸네일 생성
        val thumbnailUrl = imageProcessingService.createThumbnail(imageUrl)

        return ResponseEntity.ok(
            ImageUploadResponse(
                success = true,
                imageUrl = imageUrl,
                thumbnailUrl = thumbnailUrl,
                imageId = UUID.randomUUID().toString(),
                uploadedAt = Instant.now()
            )
        )
    }
}
```

### 알림 스케줄링 구현
```kotlin
// NotificationScheduleService.kt
@Service
class NotificationScheduleService(
    private val scheduleRepository: NotificationScheduleRepository,
    private val scheduler: TaskScheduler
) {
    fun scheduleWorkoutReminder(request: WorkoutReminderRequest, userId: Long): ScheduleResponse {
        val schedule = NotificationSchedule(
            userId = userId,
            scheduleName = request.scheduleName,
            days = request.days,
            time = request.time,
            enabled = request.enabled,
            message = request.message,
            type = NotificationType.WORKOUT_REMINDER
        )

        val saved = scheduleRepository.save(schedule)

        // Quartz 또는 Spring Scheduler 등록
        if (saved.enabled) {
            registerSchedule(saved)
        }

        return ScheduleResponse(
            scheduleId = saved.id,
            nextTriggerAt = calculateNextTrigger(saved),
            status = "scheduled",
            created = true
        )
    }
}
```

## 테스트 체크리스트

### 이미지 업로드
- [ ] 10MB 이하 파일 업로드 성공
- [ ] 10MB 초과 파일 거부
- [ ] JPEG, PNG, GIF, WebP 포맷 지원
- [ ] 썸네일 자동 생성 확인
- [ ] CDN URL 정상 접근

### 알림 스케줄링
- [ ] 운동 리마인더 생성
- [ ] 요일별 반복 설정
- [ ] 시간대별 알림 발송
- [ ] 스케줄 취소 기능
- [ ] 알림 히스토리 저장

### 회복 활동
- [ ] 다양한 활동 타입 기록
- [ ] 회복 점수 계산
- [ ] 히스토리 조회 (날짜 필터)
- [ ] 근육 부위별 회복도

### 빠른 추천
- [ ] 시간별 운동 추천
- [ ] 장비별 필터링
- [ ] 즉시 시작 기능
- [ ] 대체 운동 제안

## 에러 코드 (V3 추가)

- `UPLOAD001`: 파일 크기 초과
- `UPLOAD002`: 지원하지 않는 파일 형식
- `UPLOAD003`: 업로드 실패 (S3/CDN 오류)
- `NOTIF001`: 스케줄 생성 실패
- `NOTIF002`: 스케줄을 찾을 수 없음
- `NOTIF003`: 알림 발송 실패
- `RECOVERY001`: 유효하지 않은 활동 타입
- `RECOVERY002`: 회복 데이터 부족
- `RECOMMEND001`: 추천 생성 실패
- `RECOMMEND002`: 조건에 맞는 운동 없음

## 모니터링 메트릭 (V3 추가)

### 이미지 업로드
- 일일 업로드 횟수
- 평균 파일 크기
- 업로드 성공률
- CDN 응답 시간

### 알림
- 알림 발송 성공률
- 평균 읽음 시간
- 스케줄별 활성화율
- 알림 클릭률

### 회복 추적
- 일일 활동 기록 수
- 평균 회복 점수
- 가장 많이 사용되는 활동 타입

### 빠른 추천
- 추천 수락률
- 평균 운동 완료율
- 가장 인기 있는 추천 타입
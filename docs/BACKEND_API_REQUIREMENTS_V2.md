# LiftUp AI 백엔드 API 요구사항 V2

## 변경 사항 요약
- 운동 완료 축하 화면을 위한 통계 API 추가
- 운동 상세 정보 및 GIF 지원 추가
- 실시간 세트 완료 추적 기능
- 운동 중 휴식 타이머 지원

## 1. 운동 세션 관리 (개선)

### 1.1 운동 시작
- **POST** `/api/v2/workouts/start`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "plannedExercises": [],
      "workoutType": "empty" | "recommended" | "program",
      "programId": "string (optional)"
    }
    ```
  - Response:
    ```json
    {
      "sessionId": "string",
      "startTime": "ISO 8601",
      "exercises": [],
      "restTimerSettings": {
        "defaultRestSeconds": 90,
        "autoStartTimer": true
      }
    }
    ```

### 1.2 운동 종료 및 완료 통계
- **PUT** `/api/v2/workouts/{sessionId}/complete`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "exercises": [
        {
          "exerciseId": "string",
          "sets": [
            {
              "weight": 50,
              "reps": 10,
              "completed": true,
              "completedAt": "ISO 8601",
              "restTaken": 90
            }
          ]
        }
      ],
      "duration": 3600,
      "notes": "string"
    }
    ```
  - Response:
    ```json
    {
      "success": true,
      "summary": {
        "duration": 60,
        "totalVolume": 5400,
        "totalSets": 15,
        "exerciseCount": 5,
        "caloriesBurned": 320
      },
      "achievements": {
        "newPersonalRecords": [],
        "milestones": ["first_workout", "week_streak_3"]
      },
      "stats": {
        "totalWorkoutDays": 45,
        "currentWeekCount": 3,
        "weeklyGoal": 5,
        "currentStreak": 7,
        "longestStreak": 14
      }
    }
    ```

### 1.3 실시간 세트 업데이트
- **POST** `/api/v2/workouts/{sessionId}/sets/update`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "exerciseId": "string",
      "setNumber": 1,
      "weight": 50,
      "reps": 10,
      "completed": true,
      "completedAt": "ISO 8601"
    }
    ```
  - Response:
    ```json
    {
      "success": true,
      "setId": "string",
      "isPersonalRecord": false,
      "previousBest": {
        "weight": 45,
        "reps": 10,
        "date": "2024-01-01"
      }
    }
    ```

## 2. 운동 데이터 (개선)

### 2.1 운동 목록 조회 (GIF 포함)
- **GET** `/api/v2/exercises`
  - Query: `?category=chest&equipment=barbell&hasGif=true`
  - Response:
    ```json
    {
      "exercises": [
        {
          "id": "string",
          "name": "벤치프레스",
          "category": "가슴",
          "muscleGroups": ["대흉근", "삼두"],
          "equipment": "바벨",
          "imageUrl": "https://example.com/bench-press.gif",
          "thumbnailUrl": "https://example.com/bench-press-thumb.jpg",
          "difficulty": "intermediate"
        }
      ]
    }
    ```

### 2.2 운동 상세 정보
- **GET** `/api/v2/exercises/{exerciseId}/details`
  - Response:
    ```json
    {
      "exercise": {
        "id": "string",
        "name": "벤치프레스",
        "category": "가슴",
        "muscleGroups": ["대흉근", "삼두", "전면삼각근"],
        "equipment": "바벨",
        "imageUrl": "https://example.com/bench-press.gif",
        "videoUrl": "https://example.com/bench-press.mp4",
        "description": "벤치에 누워 바벨을 가슴 위로 들어올리는 운동",
        "instructions": [
          "벤치에 등을 대고 눕습니다",
          "견갑골을 모으고 아치를 만듭니다",
          "바벨을 어깨너비보다 약간 넓게 잡습니다",
          "천천히 가슴으로 내린 후 폭발적으로 밀어올립니다"
        ],
        "tips": [
          "견갑골을 모으고 아치를 유지하세요",
          "손목은 중립 위치를 유지하세요",
          "팔꿈치는 45-75도 각도를 유지하세요"
        ],
        "commonMistakes": [
          "바벨을 너무 높은 위치(목 쪽)에서 내리기",
          "엉덩이를 벤치에서 들어올리기",
          "바운싱(가슴에서 튕기기)"
        ],
        "breathing": "내릴 때 들이마시고, 올릴 때 내쉬세요"
      },
      "userStats": {
        "personalRecord": {
          "weight": 80,
          "reps": 5,
          "date": "2024-01-15"
        },
        "lastPerformed": "2024-01-20",
        "totalSets": 156,
        "averageWeight": 65,
        "estimatedOneRepMax": 93
      }
    }
    ```

## 3. 운동 통계 및 진행도

### 3.1 운동 완료 통계
- **GET** `/api/v2/stats/workout-completion`
  - Header: `Authorization: Bearer {token}`
  - Query: `?sessionId={sessionId}`
  - Response:
    ```json
    {
      "session": {
        "duration": 60,
        "totalVolume": 5400,
        "totalSets": 15,
        "exerciseCount": 5
      },
      "history": {
        "totalWorkoutDays": 45,
        "totalWorkouts": 52,
        "memberSince": "2023-06-01",
        "averageWorkoutsPerWeek": 4.2
      },
      "streaks": {
        "current": 7,
        "longest": 14,
        "weeklyCount": 3,
        "weeklyGoal": 5,
        "monthlyCount": 12,
        "monthlyGoal": 20
      },
      "achievements": [
        {
          "id": "week_warrior",
          "name": "주간 전사",
          "description": "일주일 연속 운동",
          "unlockedAt": "2024-01-20",
          "icon": "🔥"
        }
      ],
      "comparison": {
        "volumeChange": "+12%",
        "durationChange": "+5min",
        "comparedTo": "lastWeekAverage"
      }
    }
    ```

### 3.2 주간 운동 캘린더
- **GET** `/api/v2/stats/calendar`
  - Header: `Authorization: Bearer {token}`
  - Query: `?year=2024&month=1`
  - Response:
    ```json
    {
      "calendar": [
        {
          "date": "2024-01-01",
          "hasWorkout": true,
          "workoutCount": 1,
          "totalVolume": 4500,
          "primaryMuscles": ["가슴", "삼두"]
        }
      ],
      "summary": {
        "totalDays": 15,
        "restDays": 16,
        "averageVolume": 5200,
        "mostFrequentDay": "Monday"
      }
    }
    ```

## 4. 운동 추천 및 프로그램 (개선)

### 4.1 운동 중 실시간 조정
- **POST** `/api/v2/workouts/adjust-next-set`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "sessionId": "string",
      "exerciseId": "string",
      "previousSet": {
        "weight": 50,
        "reps": 10,
        "rpe": 8
      },
      "fatigue": "low" | "medium" | "high"
    }
    ```
  - Response:
    ```json
    {
      "recommendation": {
        "weight": 50,
        "reps": 8,
        "restSeconds": 120,
        "reason": "RPE가 높아 반복수를 줄였습니다"
      },
      "alternatives": [
        {
          "type": "drop_set",
          "weight": 40,
          "reps": 12,
          "description": "드롭세트로 볼륨 유지"
        }
      ]
    }
    ```

### 4.2 휴식 타이머 설정
- **GET** `/api/v2/workouts/rest-timer`
  - Header: `Authorization: Bearer {token}`
  - Query: `?exerciseType=compound&intensity=high&setNumber=3`
  - Response:
    ```json
    {
      "recommendedRest": 180,
      "minRest": 120,
      "maxRest": 300,
      "factors": {
        "exerciseType": "복합 운동",
        "intensity": "고강도",
        "setNumber": "후반 세트"
      }
    }
    ```

## 5. 소셜 기능 (신규)

### 5.1 운동 공유
- **POST** `/api/v2/social/share-workout`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "sessionId": "string",
      "shareType": "summary" | "detailed",
      "visibility": "public" | "friends" | "private"
    }
    ```
  - Response:
    ```json
    {
      "shareId": "string",
      "shareUrl": "https://liftup.ai/workout/abc123",
      "preview": {
        "title": "가슴 운동 완료! 💪",
        "stats": "60분 • 5.4톤 • 15세트",
        "image": "https://example.com/share-image.png"
      }
    }
    ```

### 5.2 운동 파트너 찾기
- **GET** `/api/v2/social/find-partners`
  - Header: `Authorization: Bearer {token}`
  - Query: `?gymLocation=gangnam&workoutTime=morning&level=intermediate`
  - Response:
    ```json
    {
      "partners": [
        {
          "userId": "string",
          "nickname": "철수",
          "level": "intermediate",
          "preferredTime": "07:00-09:00",
          "workoutSplit": "3분할",
          "matchScore": 85
        }
      ]
    }
    ```

## 6. 데이터 동기화 (개선)

### 6.1 오프라인 운동 동기화
- **POST** `/api/v2/sync/offline-workouts`
  - Header: `Authorization: Bearer {token}`
  - Request:
    ```json
    {
      "workouts": [
        {
          "localId": "string",
          "date": "2024-01-20",
          "exercises": [],
          "duration": 3600,
          "createdOffline": true
        }
      ],
      "lastSyncTime": "ISO 8601"
    }
    ```
  - Response:
    ```json
    {
      "synced": 3,
      "conflicts": [],
      "serverTime": "ISO 8601",
      "nextSyncToken": "string"
    }
    ```

## 에러 코드 (추가)

- `WORKOUT001`: 진행 중인 운동 세션이 이미 있음
- `WORKOUT002`: 운동 세션을 찾을 수 없음
- `WORKOUT003`: 세트 데이터 유효성 검사 실패
- `EXERCISE001`: 운동을 찾을 수 없음
- `EXERCISE002`: 운동 GIF/이미지를 로드할 수 없음
- `STATS001`: 통계 데이터 부족 (최소 운동 횟수 미달)
- `SOCIAL001`: 공유 권한 없음
- `SYNC001`: 동기화 충돌 발생

## 성능 최적화

### 이미지/GIF 처리
- CDN을 통한 이미지 제공
- 썸네일과 풀사이즈 이미지 분리
- WebP 포맷 지원
- 적응형 이미지 로딩 (디바이스별 최적화)

### 실시간 업데이트
- WebSocket을 통한 운동 중 실시간 동기화
- 세트 완료 즉시 서버 전송
- 오프라인 모드 지원 (로컬 캐싱)

### 캐싱 전략
```json
{
  "cacheControl": {
    "exercises": "max-age=86400",
    "exerciseDetails": "max-age=3600",
    "userStats": "max-age=300",
    "workoutHistory": "max-age=600"
  }
}
```

## 보안 강화

### Rate Limiting (세분화)
- 운동 시작: 5 requests/hour per user
- 세트 업데이트: 200 requests/hour per user
- 통계 조회: 100 requests/minute per user
- 이미지 다운로드: 500 requests/hour per user

### 데이터 검증
- 세트 데이터 이상치 탐지 (비현실적인 중량/반복수)
- 운동 시간 검증 (최소/최대 시간)
- 볼륨 계산 검증

## 모니터링 메트릭

### 핵심 지표
- 평균 운동 완료율
- 세트당 평균 소요 시간
- 이미지 로딩 실패율
- 오프라인 동기화 성공률
- 실시간 업데이트 지연 시간
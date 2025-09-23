# LiftupAI 전체 API 문서 (Flutter 연동용)

## Base URL
```
http://localhost:8080  (개발)
https://api.liftupai.com  (프로덕션)
```

## 인증
모든 API는 Bearer Token 인증 필요 (auth 엔드포인트 제외)
```
Authorization: Bearer {token}
```

---

## 🔐 인증 API (`/api/auth`)

### 회원가입
```http
POST /api/auth/register
```

### 로그인
```http
POST /api/auth/login
```

### 토큰 갱신
```http
POST /api/auth/refresh
```

### 로그아웃
```http
POST /api/auth/logout
```

### 닉네임 중복 확인
```http
GET /api/auth/check-nickname?nickname={nickname}
```

### 기기 등록
```http
POST /api/auth/device/register
```

### 기기 로그인
```http
POST /api/auth/device/login
```

### 기기 확인
```http
GET /api/auth/device/check
```

---

## 💪 운동 API v2 (`/api/v2/workouts`)

### 운동 시작
```http
POST /api/v2/workouts/start
```
**Request Body:**
```json
{
  "workout_type": "PUSH",  // PUSH, PULL, LEGS, UPPER, LOWER, FULL_BODY
  "exercises": [
    {
      "exercise_id": 1,
      "order_index": 1,
      "sets": [
        {"weight": 60, "reps": 12}
      ]
    }
  ]
}
```

### 새 운동 시작 (진행 중인 세션 취소)
```http
POST /api/v2/workouts/start/new
```

### 운동 이어하기
```http
POST /api/v2/workouts/start/continue
```

### 현재 세션 조회
```http
GET /api/v2/workouts/current-session
```

### 운동 기록 조회
```http
GET /api/v2/workouts/history?page=0&size=10
```

### 특정 세션 조회
```http
GET /api/v2/workouts/{sessionId}
```

### 프로그램 상태
```http
GET /api/v2/workouts/program-status
```

### 운동 완료
```http
PUT /api/v2/workouts/{sessionId}/complete
```

### 세션 업데이트
```http
PUT /api/v2/workouts/{sessionId}/update
```

### 세트 업데이트
```http
POST /api/v2/workouts/{sessionId}/sets/update
```

### 다음 세트 조정
```http
POST /api/v2/workouts/adjust-next-set
```

### 휴식 타이머
```http
GET /api/v2/workouts/rest-timer?last_rpe=7&exercise_type=compound
```

### 운동 계획 업데이트
```http
PUT /api/v2/workouts/plan
```

### 프로그램 생성
```http
POST /api/v2/workouts/generate-program
```

### ⚠️ 오늘의 추천 (v2 경로 주의!)
```http
POST /api/v2/workouts/recommendations/today
```

### 빠른 운동 추천
```http
GET /api/v2/workouts/recommendations/quick?duration=30&equipment=dumbbell&target_muscle=chest
```

### 추천 운동 시작
```http
POST /api/v2/workouts/start-recommended
```

---

## 📊 통계 API (`/api/v2/stats`)

### 운동 완료 통계
```http
GET /api/v2/stats/workout-completion?sessionId={sessionId}
```

### 📅 운동 캘린더
```http
GET /api/v2/stats/calendar?year=2025&month=1
```

### 주간 통계
```http
GET /api/v2/stats/weekly
```

### 전체 개요
```http
GET /api/v2/stats/overview?period=week
```

### 볼륨 통계 (⚠️ volume-trend 아님!)
```http
GET /api/v2/stats/volume?period=week&startDate=2025-01-01
```

### 근육 분포
```http
GET /api/v2/stats/muscle-distribution?period=month
```

### 개인 기록
```http
GET /api/v2/stats/personal-records
```

### 진행도
```http
GET /api/v2/stats/progress?metric=weight&period=3months
```

---

## 🚀 진급 시스템 API (`/api/v2/progression`)

### 진급 분석
```http
GET /api/v2/progression/analysis
```

### 볼륨 최적화
```http
GET /api/v2/progression/volume/optimization
```

### 회복 상태
```http
GET /api/v2/progression/recovery
```

### 프로그램 전환 체크
```http
GET /api/v2/progression/transition/check
```

### 진급 요약
```http
GET /api/v2/progression/summary
```

### 진급 적용
```http
POST /api/v2/progression/apply-recommendation
```

---

## 🏃 운동 종목 API (`/api/v2/exercises`)

### 운동 목록
```http
GET /api/v2/exercises?category=CHEST&muscle_group=chest
```

### 운동 상세
```http
GET /api/v2/exercises/{exerciseId}/details
```

---

## 🤖 AI API (`/api/ai`)

### 자세 분석
```http
POST /api/ai/analyze-form
```

### AI 추천
```http
GET /api/ai/recommendations?type=workout&muscle_groups=chest,triceps
```

### AI 운동 추천
```http
GET /api/ai/recommendations/workout?duration=30&target_muscle=chest
```

### AI 채팅
```http
POST /api/ai/chat
```

---

## 👤 사용자 API (`/api/users`)

### 프로필 조회
```http
GET /api/users/profile
```

### 프로필 생성
```http
POST /api/users/profile
```

### 프로필 수정
```http
PUT /api/users/profile
```

### 온보딩
```http
POST /api/users/onboarding
```

### 설정 조회
```http
GET /api/users/settings
```

### 설정 수정
```http
PUT /api/users/settings
```

---

## 🔔 알림 API (`/api/notifications`)

### FCM 토큰 등록
```http
POST /api/notifications/register
```

### 알림 설정 조회
```http
GET /api/notifications/settings
```

### 알림 설정 수정
```http
PUT /api/notifications/settings
```

### 운동 알림 예약
```http
POST /api/notifications/schedule/workout
```

### 알림 기록
```http
GET /api/notifications/history?page=0&size=20
```

---

## 🥗 영양 API (`/api/nutrition`)

### 영양 기록 조회
```http
GET /api/nutrition/history?date=2025-01-20
```

### 영양 기록
```http
POST /api/nutrition/log
```

### 식사 분석
```http
POST /api/nutrition/analyze-meal
```

---

## 💬 채팅 API (`/api/chat`)

### 메시지 전송
```http
POST /api/chat/send
```

### 채팅 기록
```http
GET /api/chat/history?limit=50
```

### 채팅 삭제
```http
DELETE /api/chat/clear
```

---

## 🔄 동기화 API (`/api/v2/sync`)

### 오프라인 운동 동기화
```http
POST /api/v2/sync/offline-workouts
```

---

## ❗ 자주 발생하는 에러 해결

### 1. "No static resource api/v2/stats/volume-trend"
**원인**: 존재하지 않는 엔드포인트
**해결**: `/api/v2/stats/volume` 사용

### 2. "Request method 'POST' is not supported"
**원인**: GET 엔드포인트에 POST 요청
**해결**: 올바른 HTTP 메서드 사용

### 3. "/api/workouts/recommendations/today" 500 에러
**원인**: v1 경로 사용
**해결**: `/api/v2/workouts/recommendations/today` 사용

---

## Flutter 예시 코드

### API Service 기본 구조
```dart
class ApiService {
  static const String baseUrl = 'http://localhost:8080';
  final Dio dio;

  ApiService() : dio = Dio() {
    dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        final token = getStoredToken();
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
    ));
  }

  // 운동 캘린더 조회
  Future<WorkoutCalendar> getWorkoutCalendar(int year, int month) async {
    try {
      final response = await dio.get(
        '$baseUrl/api/v2/stats/calendar',
        queryParameters: {'year': year, 'month': month},
      );
      return WorkoutCalendar.fromJson(response.data['data']);
    } catch (e) {
      throw handleError(e);
    }
  }

  // 볼륨 통계 (volume-trend 아님!)
  Future<VolumeStats> getVolumeStats(String period) async {
    try {
      final response = await dio.get(
        '$baseUrl/api/v2/stats/volume',  // ✅ 올바른 경로
        queryParameters: {'period': period},
      );
      return VolumeStats.fromJson(response.data['data']);
    } catch (e) {
      throw handleError(e);
    }
  }

  // 오늘의 운동 추천 (v2 경로!)
  Future<WorkoutRecommendation> getTodayRecommendation() async {
    try {
      final response = await dio.post(
        '$baseUrl/api/v2/workouts/recommendations/today',  // ✅ v2 경로
      );
      return WorkoutRecommendation.fromJson(response.data['data']);
    } catch (e) {
      throw handleError(e);
    }
  }
}
```

### 에러 처리
```dart
Exception handleError(dynamic error) {
  if (error is DioException) {
    if (error.response?.statusCode == 404) {
      // API 경로 확인
      print('API not found: ${error.requestOptions.path}');
      print('올바른 경로인지 확인하세요 (v1 → v2)');
    }
    if (error.response?.statusCode == 405) {
      // HTTP 메서드 확인
      print('Wrong HTTP method: ${error.requestOptions.method}');
    }
  }
  return error;
}
```

---

## 체크리스트
- [ ] API 경로에 v2가 포함되어 있는지 확인
- [ ] GET/POST/PUT/DELETE 메서드가 올바른지 확인
- [ ] Authorization 헤더가 포함되어 있는지 확인
- [ ] Request Body가 올바른 형식인지 확인
- [ ] Query Parameter가 올바른지 확인
# Flutter-Backend API 불일치 문제

## 🚨 발견된 주요 문제들

### 1. Response 데이터 접근 패턴 불일치 ⚠️
**문제**: Flutter와 Spring Boot의 응답 구조 차이
- Flutter 예상: `response.data['data']`
- Spring Boot 실제: `ApiResponse<T>` 구조

**영향받는 파일**:
- 모든 API 서비스 파일들

**해결 방법**:
백엔드의 `ApiResponse` 클래스가 다음 구조를 따르는지 확인:
```kotlin
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: ErrorResponse? = null
)
```

### 2. 토큰 리프레시 응답 구조 충돌 ⚠️
**문제**: 두 개의 다른 AuthInterceptor가 다른 응답 기대
- `services/api/interceptors/auth_interceptor.dart`: `response.data['data']['accessToken']`
- `core/network/api_client.dart`: `response.data['accessToken']`

**해결**: 하나의 AuthInterceptor만 사용하도록 통일 필요

### 3. 날짜/시간 형식 불일치 ⚠️
**문제**:
- Flutter: 날짜만 전송 (`2024-01-20`)
- Backend: ISO 8601 전체 형식 기대 (`2024-01-20T00:00:00Z`)

**영향받는 API**:
- `/api/workouts/sessions` (startDate, endDate)
- `/api/stats/*` (날짜 파라미터)
- `/api/chat/history` (date)

### 4. 에러 메시지 구조 불일치 ⚠️
**문제**: 에러 응답 접근 방식 차이
- Flutter: `response.data['error']['message']`
- 일부 Flutter: `response.data['message']`

**백엔드 확인 필요**:
```kotlin
data class ErrorResponse(
    val message: String,
    val code: String? = null
)
```

### 5. 중복 API Client 구현 🔴
**문제**: 두 개의 API Client가 존재
- `/lib/services/api/api_client.dart`
- `/lib/core/network/api_client.dart`

**해결**: 하나로 통일 필요

### 6. Null Safety 부족 ⚠️
**문제**: 중첩된 데이터 접근 시 null 체크 없음
```dart
// 위험한 코드
final muscles = response.data['data']['muscles']; // null 가능

// 안전한 코드
final muscles = response.data?['data']?['muscles'] ?? [];
```

## 📋 즉시 수정 필요 사항

### Flutter 측 수정
1. **API Response 래퍼 클래스 생성**:
```dart
class ApiResponse<T> {
  final bool success;
  final T? data;
  final ErrorResponse? error;

  ApiResponse({
    required this.success,
    this.data,
    this.error,
  });

  factory ApiResponse.fromJson(Map<String, dynamic> json, T Function(dynamic) fromJson) {
    return ApiResponse(
      success: json['success'] ?? false,
      data: json['data'] != null ? fromJson(json['data']) : null,
      error: json['error'] != null ? ErrorResponse.fromJson(json['error']) : null,
    );
  }
}
```

2. **날짜 형식 통일**:
```dart
// 변경 전
params: {'date': date.toIso8601String().split('T').first}

// 변경 후
params: {'date': date.toIso8601String()}
```

### Backend 측 확인 필요
1. **ApiResponse 구조 확인**
2. **날짜 파라미터 파싱 유연성**
3. **에러 응답 형식 일관성**

## 🧪 테스트 시나리오

### 1. 인증 플로우
- [ ] 회원가입 → 로그인 → 토큰 저장
- [ ] 토큰 만료 → 자동 리프레시
- [ ] 로그아웃

### 2. 데이터 조회
- [ ] 운동 목록 조회
- [ ] 통계 데이터 조회 (날짜 필터)
- [ ] 채팅 히스토리

### 3. 데이터 생성/수정
- [ ] 운동 세션 시작/종료
- [ ] 이미지 업로드
- [ ] 알림 설정 변경

### 4. 에러 처리
- [ ] 401 Unauthorized 처리
- [ ] 네트워크 에러
- [ ] 서버 에러 (500)

## 🎯 우선순위

1. **긴급 (앱 실행 불가)**
   - API Client 통일
   - Response 구조 매칭

2. **높음 (기능 오류)**
   - 날짜 형식 통일
   - Null safety 추가

3. **보통 (개선 사항)**
   - 에러 처리 일관성
   - 로깅 개선
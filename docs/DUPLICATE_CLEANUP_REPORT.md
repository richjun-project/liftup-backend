# 중복 코드 정리 보고서

## 🔴 심각한 중복 (즉시 정리 필요)

### 1. Workout Controllers (3개 버전)
- `WorkoutController.kt` - V1 API (`/api/workouts`)
- `WorkoutControllerV2.kt` - V2 API (`/api/v2/workouts`)
- `WorkoutControllerV4.kt` - V4 API (`/api`)

**문제점:**
- 같은 기능이 3개 버전으로 존재
- 클라이언트가 어떤 API를 써야 할지 혼란
- 유지보수 시 3곳 모두 수정 필요

**해결방안:**
```kotlin
// V1, V4 제거하고 V2로 통일
@RestController
@RequestMapping("/api/v2/workouts")
class WorkoutController  // V2를 메인으로 사용
```

### 2. Exercise Controllers (2개 버전)
- `ExerciseController.kt` - V1 (`/api/exercises`)
- `ExerciseControllerV2.kt` - V2 (`/api/v2/exercises`)

**해결방안:**
- V1 제거, V2만 유지

### 3. Stats Controllers (2개)
- `StatsController.kt` - `/api/stats` (StatsService 사용)
- `StatsControllerV2.kt` - `/api/v2/stats` (WorkoutServiceV2 사용)

**문제점:**
- 두 컨트롤러가 다른 서비스 사용
- 통계 로직이 분산됨

## 🟡 중간 수준 중복 (리팩토링 필요)

### 1. Workout Services
- `WorkoutService.kt` - 복잡한 레거시 코드
- `WorkoutServiceV2.kt` - 개선된 버전

**문제점:**
- 비슷한 메서드가 양쪽에 존재
- `endWorkout()` vs `completeWorkout()`
- `startWorkout()` vs `startNewWorkout()`, `continueWorkout()`

### 2. Workout DTOs
- `WorkoutDto.kt` - V1 DTOs
- `WorkoutDtoV2.kt` - V2 DTOs
- `QuickWorkoutDto.kt` - 빠른 운동 전용
- `ProgramStatusDto.kt` - 프로그램 상태 전용

**문제점:**
- 비슷한 DTO가 여러 파일에 분산
- `StartWorkoutRequest` vs `StartWorkoutRequestV2`
- `WorkoutSummaryResponse` vs `CompleteWorkoutResponseV2`

## 🟢 경미한 중복 (장기 계획)

### 1. Repository 메서드
```kotlin
// WorkoutSessionRepository
findFirstByUserAndStatusOrderByStartTimeDesc()
findAllByUserAndStatus()
findByUserAndStartTimeBetween()
// 비슷한 쿼리가 여러 개
```

### 2. 중복된 엔티티 관계
```kotlin
// WorkoutSession
val exercises: MutableList<WorkoutExercise>  // 사용 안 함
val logs: MutableList<WorkoutLog>  // 사용 안 함
// 직접 repository 조회로 대체됨
```

## 📋 정리 우선순위

### 1단계 (즉시)
- [x] V1 API에 @Deprecated 추가
- [x] WorkoutControllerV4 제거 (내용을 V2로 이동)
- [x] ExerciseController V1 제거
- [x] StatsController 통합

### 2단계 (1주일 내)
- [x] WorkoutService 메서드 통합 (@Deprecated 추가)
- [→] DTO 파일 정리 및 통합 (진행 중)
- [x] 사용하지 않는 엔티티 관계 제거

### 3단계 (1개월 내)
- [ ] Repository 메서드 정리
- [ ] 테스트 코드 작성
- [ ] API 문서 업데이트

## 🔧 리팩토링 제안

### 1. Controller 통합
```kotlin
// Before: 3개 파일
WorkoutController.kt
WorkoutControllerV2.kt
WorkoutControllerV4.kt

// After: 1개 파일
WorkoutController.kt  // V2 기준으로 통합
```

### 2. Service 통합
```kotlin
// Before
class WorkoutService {
    fun startWorkout()
    fun endWorkout()
}
class WorkoutServiceV2 {
    fun startNewWorkout()
    fun continueWorkout()
    fun completeWorkout()
}

// After
class WorkoutService {
    fun startNewWorkout()
    fun continueWorkout()
    fun completeWorkout()

    @Deprecated
    fun startWorkout() = startNewWorkout()

    @Deprecated
    fun endWorkout() = completeWorkout()
}
```

### 3. DTO 구조 개선
```kotlin
// dto/request/
StartWorkoutRequest.kt
CompleteWorkoutRequest.kt

// dto/response/
WorkoutSessionResponse.kt
WorkoutSummaryResponse.kt

// dto/common/
ExerciseDto.kt
SetDto.kt
```

## 📊 영향 분석

### 클라이언트 영향
- Flutter 앱: API 엔드포인트 변경 필요
- 약 20-30개 API 호출 수정 예상

### 데이터베이스 영향
- 스키마 변경 없음
- 마이그레이션 불필요

### 성능 영향
- Lazy loading 제거로 성능 개선
- 중복 코드 제거로 메모리 사용량 감소

## ✅ 완료된 작업

1. `session.exercises` → repository 직접 조회로 변경
2. `session.logs` → repository 직접 조회로 변경
3. V1 API에 @Deprecated 어노테이션 추가
4. API 정리 가이드 문서 작성

## 🚀 다음 단계

1. **WorkoutControllerV4 내용을 V2로 이동**
   - 오늘의 운동 추천 API
   - 프로필 기반 프로그램 생성

2. **Service 레이어 통합**
   - WorkoutService와 WorkoutServiceV2 통합
   - 중복 로직 제거

3. **DTO 패키지 재구성**
   - request/response/common으로 분리
   - 버전별 DTO 통합

4. **테스트 코드 작성**
   - 통합 테스트
   - API 테스트

5. **문서화**
   - Swagger/OpenAPI 스펙 업데이트
   - README 업데이트
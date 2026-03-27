# EVAL: recommendation-consistency

## Date: 2026-03-28

## Scope
운동 추천 시스템의 데이터 일관성 및 edge case 검증

---

## CAPABILITY EVALS

### [CE-01] 프로필 없는 사용자 → FULL_BODY 기본 추천
- **Task:** userProfile=null, userSettings=null인 사용자에게 추천 요청 시 FULL_BODY 운동 반환
- **Previous Bug:** 기본값이 "PPL"이어서 첫 날=PUSH="chest"만 추천됨
- **Fix:** `WorkoutServiceV2.kt:2108` — default "PPL" → "FULL_BODY"
- **Test:** `WorkoutServiceV2PTTest.DefaultProgramFallback.no profile no settings defaults to FULL_BODY not PPL`
- **Result:** PASS (pass@1)

### [CE-02] 벡터 추천 불충분 시 rule-based 폴백
- **Task:** 벡터 인덱싱 미완료 등으로 결과가 목표의 절반 미만일 때 rule-based 폴백 실행
- **Previous Bug:** `takeIf { it.isNotEmpty() }` — 1개만 있어도 통과하여 60분에 2개만 추천
- **Fix:** `WorkoutServiceV2.kt:1146` — `takeIf { it.size >= minAcceptableCount }`
- **Test:** `WorkoutServiceV2PTTest.DefaultProgramFallback.vector recommendation below threshold triggers rule-based fallback`
- **Result:** PASS (pass@1)

### [CE-03] WorkoutProgressTracker 기본 시퀀스
- **Task:** AUTO/unknown program type → FULL_BODY 시퀀스 반환
- **Previous Bug:** PPL 시퀀스(PUSH, PULL, LEGS) 반환
- **Fix:** `WorkoutProgressTracker.kt:115-116`
- **Test:** Verified via CE-01 (mocked `getWorkoutTypeSequence("FULL_BODY")`)
- **Result:** PASS (pass@1)

---

## REGRESSION EVALS

### [RE-01] 기존 추천 품질 테스트
- `WorkoutRecommendationQualityTest`: PASS (45+ tests)
- `WorkoutRecommendationProductEvalTest`: PASS (19 tests)
- `RecommendationExerciseRankingTest`: PASS (3 tests)
- **Result:** ALL PASS (pass^1)

### [RE-02] 기존 PT 시스템 테스트
- `WorkoutServiceV2PTTest`: PASS (25+ tests)
- `WorkoutServiceV2Test`: PASS (6 tests)
- **Result:** ALL PASS (pass^1)

### [RE-03] 벡터 추천 로직 테스트
- `VectorRecommendationLogicTest`: PASS (all tests)
- **Result:** PASS (pass^1)

### [RE-04] 카탈로그 감사 테스트
- `FinalAuditTest`: PASS
- `RealCatalogRecommendationTest`: PASS
- **Result:** PASS (pass^1)

---

## EDGE CASE EVALS

### [EC-01] 프론트엔드 하드코딩 한국어 기본값
- **Issue:** '맞춤형 운동', '중간', '보통' 등이 모델에 하드코딩 → 영어 사용자에게 한국어 표시
- **Files Fixed:**
  - `workout_recommendation_model.dart` — '맞춤형 운동' → 'Custom Workout', '중간' → 'Medium'
  - `workout_v3_models.dart` — '맞춤형 운동' → 'Custom Workout', '보통' → 'Medium'
  - `workout_screen.dart` — '중간' → 'Medium' (2곳)
- **Result:** FIXED

### [EC-02] 토큰 유효 + 프로필 미존재 (NOT_FOUND)
- **Issue:** `_checkAuthStatus()`에서 프로필 404 시 `isAuthenticated: false`로 설정 → /welcome로 보냄
- **Fix:** NOT_FOUND 에러 시 `isAuthenticated: true, onboardingCompleted: false`
- **Result:** FIXED

### [EC-03] 로그아웃 시 프로필 캐시 잔존
- **Issue:** SharedPreferences `user_profile`이 로그아웃 후에도 남아있어 이전 사용자 데이터 표시
- **Fix:** `logout()`에서 `UserProfile.clear()` 호출
- **Result:** FIXED

---

## METRICS

```
Capability Evals:  3/3 passed (pass@1: 100%)
Regression Evals:  4/4 passed (pass^1: 100%)
Edge Case Evals:   3/3 fixed

Total Backend Tests Run: 166+
Total Backend Tests Passed: ALL
New Tests Added: 2
```

## STATUS: SHIP IT

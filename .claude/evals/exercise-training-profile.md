## EVAL: exercise-training-profile

### Context
운동 추천 시 세트/횟수/휴식이 주기화 단계(PeriodizationPhase)에만 의존하던 문제를
ExerciseTrainingProfile 7가지 분류 기반 28셀 매트릭스로 분화.

### Capability Evals
1. [CAP-1] ExerciseTrainingProfileResolver가 7가지 프로필을 올바르게 분류
2. [CAP-2] 28셀 매트릭스가 완전하고 올바른 값 반환
3. [CAP-3] 같은 phase에서 운동 종류별 다른 sets/reps/rest 처방
4. [CAP-4] WorkoutServiceV2의 calculateProgressiveOverload가 분화된 처방 사용
5. [CAP-5] AI 프롬프트에 운동별 처방 가이드 포함

### Regression Evals
1. [REG-1] 기존 WorkoutServiceV2PTTest 전체 통과
2. [REG-2] 전체 테스트 스위트 통과 (./gradlew test)
3. [REG-3] Kotlin 컴파일 성공 (경고만 허용, 에러 없음)
4. [REG-4] PTRecommendation/ProgressionPlan 데이터 클래스 구조 변경 없음
5. [REG-5] API 응답 JSON 형식 변경 없음

### Success Metrics
- Capability evals: pass@1 >= 80%, pass@3 >= 100%
- Regression evals: pass^3 = 100%

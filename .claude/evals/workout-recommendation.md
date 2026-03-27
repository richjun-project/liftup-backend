# EVAL DEFINITION: workout-recommendation

## Overview
운동 추천 시스템 전체 품질 평가. 6라운드에 걸쳐 코드, 알고리즘, 보안, 동시성, Product 품질을 검증.

## Capability Evals (20 items)

### R1: 추천 엔진 핵심
1. [x] E01: Recovery 필터가 secondary muscle 포함 전체 근육 체크
2. [x] E02: Recovery 임계값 양쪽 서비스 50%로 통일
3. [x] E03: 벡터 검색 쿼리 영어 locale 사용
4. [x] E04: N+1 쿼리 배치 변환 (2개 repository 메서드)
5. [x] E05: broadPatternCategory에 CORE 포함

### R2: 안전성 + 벡터 통합
6. [x] E06: Recovery % coerceIn(0, 100)
7. [x] E07: Soreness coerceIn(0, 10)
8. [x] E08: Vector 추천이 Quick 플로우에 통합
9. [x] E09: Duration 10-180분 바운딩
10. [x] E10: Weight 0-500kg, Reps 0-100 바운딩

### R3: 주기화 + 칼로리 + 품질
11. [x] E11: Periodization 연속 주 기반 계산
12. [x] E12: 칼로리 수식 5 cal/min 통일
13. [x] E13: Recovery boost Int 기반 타입 안전
14. [x] E14: Achievement locale 유저 설정 사용
15. [x] E15: AutoProgramSelector NOVICE/1일 처리

### R4: 동시성 + 검증 + 보안
16. [x] E16: 세션 시작 PESSIMISTIC_WRITE 락
17. [x] E17: DTO @Min/@Max 검증 어노테이션
18. [x] E18: Streak 루프 365일 바운딩
19. [x] E19: CORS 환경변수 기반
20. [x] E20: 핵심 서비스 println = 0

## Product Evals (19 tests)
- `WorkoutRecommendationProductEvalTest.kt`
- CE-01 ~ CE-10: Capability (회복필터, 패턴, Tier, 정렬, 목표, 장비, 복합운동)
- RE-01 ~ RE-03: Regression (파이프라인, 빈 회복, core candidate)
- EC-01 ~ EC-06: Edge Case (전체 회복, 빈 근육, compound 비율, 복합 이름, 정렬 안정성, 복합 필터)

## Human Review Required

### HIGH RISK
1. **readOnly 트랜잭션 내 write 수정** (RecoveryService.getRecoveryStatus)
   - 변경: `@Transactional(readOnly = true)` → `@Transactional`
   - 이유: updateRecoveryPercentages()에서 save() 호출
   - 리스크: DB 부하 약간 증가 가능 (read replica 미사용)

2. **비관적 락 도입** (WorkoutSessionRepository)
   - 변경: findFirstByUserAndStatusWithLock에 PESSIMISTIC_WRITE
   - 이유: 동시 세션 생성 race condition 방지
   - 리스크: 높은 동시성에서 데드락 가능성 (타임아웃 설정 확인 필요)

### MEDIUM RISK
3. **벡터 추천 통합** (WorkoutServiceV2.generateQuickRecommendation)
   - 변경: vectorRecommendationService 주입 (optional) + fallback
   - 이유: 벡터 추천이 Quick 플로우에서 사용되지 않았음
   - 리스크: Qdrant 서버 다운 시 fallback 정상 작동 확인 필요

4. **CORS 환경변수화** (SecurityConfig)
   - 변경: 하드코딩 localhost → @Value 주입
   - 이유: 프로덕션 배포 시 CORS 차단 방지
   - 리스크: application.yml에 cors.allowed-origins 미설정 시 localhost만 허용 (안전)

## Grader Command
```bash
# 코드 그레이더 (20 checks)
grep -q 'pattern' file && echo PASS || echo FAIL

# 테스트 그레이더
./gradlew test --tests "*WorkoutRecommendationProductEvalTest"

# 전체 빌드
./gradlew compileKotlin compileTestKotlin
```

## Success Metrics
- pass@1 >= 100% for all capability evals
- pass^3 = 100% for regression evals
- Product eval: 19/19 tests pass

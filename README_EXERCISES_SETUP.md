# 운동 카탈로그 설정 가이드

## 현재 소스 오브 트루스

- 원본 워크북: `1500+ exercise data.xlsx`
- 정규화 스크립트: `scripts/generate_exercise_catalog.py`
- 앱 리소스: `src/main/resources/catalog/exercise-catalog.json`
- DB 적재 방식: Spring Boot bootstrap (`ExerciseCatalogBootstrapService`)

구 SQL 시드 파일은 더 이상 사용하지 않습니다.

## 베스트 프랙티스

운동 카탈로그는 `언어별로 운동 row를 중복 저장`하면 안 됩니다.

- `exercises`
  - 언어 중립 메타데이터 저장
  - `slug`, `category`, `movement_pattern`, `equipment`, `muscle_groups`, `recommendation_tier`
- `exercise_translations`
  - 언어별 표시 문자열 저장
  - `locale`, `display_name`, `instructions`, `tips`

이 구조를 쓰면 좋은 점:

- 추천 알고리즘이 문자열 하드코딩 없이 `slug / movement_pattern / muscle_groups` 기준으로 동작합니다.
- 영어, 한국어, 이후 일본어/스페인어를 추가해도 운동 ID와 알고리즘은 그대로 유지됩니다.
- 검색은 번역 테이블까지 같이 조회하면 되고, 내부 판단 로직은 항상 언어 중립으로 유지됩니다.

## 로컬에서 전체 리셋 + 재적재

```bash
chmod +x scripts/reset_exercise_catalog.sh
./scripts/reset_exercise_catalog.sh local
```

동작 순서:

1. `1500+ exercise data.xlsx`를 읽어 JSON 카탈로그 생성
2. Flyway/JPA 스키마 반영
3. 운동/번역/관련 workout domain 데이터 reset
4. `exercise-catalog.json` 기준으로 재적재

## 환경별 실행

```bash
./setup_exercises_db.sh
./setup_exercises_local.sh
./setup_exercises_ec2.sh
```

모든 스크립트는 내부적으로 동일한 reset/import 파이프라인을 사용합니다.

## 카탈로그 재생성만 할 때

```bash
python3 scripts/generate_exercise_catalog.py
```

## 현재 카탈로그 특성

- 원본 워크북의 유효 레코드를 정규화해 약 1300개 수준의 운동으로 축약
- `_female`, `_male` 중복 제거
- 운동별 `slug`, `movement_pattern`, `recommendation_tier`, `difficulty`, `popularity` 생성
- 영어 원문 저장
- 한국어 표시명 translation 동시 생성

## 운영 원칙

- 운동 추천은 `ESSENTIAL` 우선, 부족할 때만 `STANDARD` 보충
- `ADVANCED`, `SPECIALIZED`는 검색/탐색용으로는 유지하되 자동 추천에서는 보수적으로 사용
- 신규 언어 추가 시 `exercise_translations`만 확장하고 추천 로직은 수정하지 않음

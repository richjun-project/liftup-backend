-- LiftUp AI: 미사용 스키마 정리 + 중복 재발 방지 제약.
--
-- 2026-07-11 프로덕션 실사 결과:
--   - 아래 컬럼들은 애플리케이션 코드에서 읽는 곳이 없고(또는 쓰는 곳조차 없고),
--     실데이터도 전부 NULL/기본값임을 확인함. 동반 배포되는 엔티티 변경과 함께 제거.
--   - workout_logs는 exercise_sets와 중복되는 레거시 테이블(0행)로 삭제.
--   - exercises.slug만 유니크라 표시 이름 중복(과거 17쌍 + 이번 6쌍)을 DB가 막지
--     못했음 → (locale, display_name) 유니크 제약으로 재발을 구조적으로 차단.
--
-- 주의: MySQL DDL은 implicit commit이라 문장 단위로 확정된다. 중간 실패 시
-- flyway_schema_history의 failed row를 지우고 남은 문장만 재실행할 것.

-- ── 중복 재발 방지: 같은 locale에 같은 표시 이름 금지 ──────────────────────
-- (기존 non-unique 인덱스 idx_exercise_translation_locale_name은 조회용으로 유지)
ALTER TABLE exercise_translations
    ADD CONSTRAINT uk_exercise_translations_locale_name UNIQUE (locale, display_name);

-- ── 미사용 컬럼 제거 ────────────────────────────────────────────────────────

-- 미디어 URL은 slug 기반으로 생성되며 이 컬럼은 전 행 NULL.
-- equipment_detail/source_category는 카탈로그 임포트가 쓰기만 하고 읽는 곳 없음.
ALTER TABLE exercises
    DROP COLUMN image_url,
    DROP COLUMN equipment_detail,
    DROP COLUMN source_category;

ALTER TABLE workout_sessions
    DROP COLUMN recommendation_type,
    DROP COLUMN synced_from_offline;

ALTER TABLE workout_exercises
    DROP COLUMN notes;

-- is_personal_record: 항상 false (PR 판정은 personal_records 테이블 기준으로 동작).
-- notes: "completed"/"incomplete" 하드코딩 마커로 completed 컬럼과 완전 중복.
ALTER TABLE exercise_sets
    DROP COLUMN is_personal_record,
    DROP COLUMN notes;

ALTER TABLE personal_records
    DROP COLUMN video_url,
    DROP COLUMN notes;

-- 런타임 BLOCK 주기화 계산(BlockPhaseAdjustment)으로 대체된 시드 잔재.
ALTER TABLE program_day_exercises
    DROP COLUMN intensity_percent_low,
    DROP COLUMN intensity_percent_high;

ALTER TABLE injury_exercise_restrictions
    DROP COLUMN reason;

ALTER TABLE exercise_substitutions
    DROP COLUMN movement_pattern,
    DROP COLUMN notes;

-- ── 레거시 테이블 제거 ──────────────────────────────────────────────────────
DROP TABLE IF EXISTS workout_logs;

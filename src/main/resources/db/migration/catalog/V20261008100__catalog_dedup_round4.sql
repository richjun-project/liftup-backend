-- LiftUp AI: 운동 카탈로그 4차 정리 — 중복 운동 61개를 사용자 기록과 함께 표준형으로 합친 뒤 삭제한다.
--
-- 사라지는 slug -> 남는 slug 는 src/main/resources/catalog/duplicate-map.json 과 같다(scripts/catalog/overhaul_catalog.py 가 둘을 함께 만든다).
-- 정규화하면 이름이 같은 쌍, 같은 동작의 표기/자세 변형, 브랜드 기구의 한쪽·교대 모드 변형이 대상이다. 남는 쪽은 모두 이미 운영에 있는 운동이다.
--
-- V20260925001 과 같은 방식: 사용자의 운동 기록·PR·플랜·대체 기록이 가리키는 변형 id 를 표준형 id 로 옮긴 뒤 지운다.
-- 슬러그로 매핑하므로 DB 에 없는 슬러그 쌍은 조용히 건너뛴다. exercises.id 를 참조하는 테이블은 엔티티 FK 전수 기준이며,
-- CatalogSyncIntegrationTest 가 스키마의 FK 목록과 이 파일을 대조해 새 참조 테이블이 생기면 실패한다.
-- 새 운동 삽입·메타데이터·이름 갱신은 이 마이그레이션이 아니라 기동 시 카탈로그 동기화가 한다.
--
-- 이식성: H2(MODE=MySQL)와 MySQL 8 에서 같은 SQL 이 돌도록 UPDATE ... JOIN 대신 상관 서브쿼리와 임시 스크래치 테이블을 쓴다
-- (MySQL 의 TEMPORARY 테이블은 한 문장에서 두 번 참조할 수 없어 일반 테이블을 쓰고 끝에 지운다).
-- 주의: MySQL DDL 은 implicit commit 이라 문장 단위로 확정된다. 중간 실패 시 flyway_schema_history 의 failed row 를 지우고 다시 실행하면 된다
-- (맨 앞에서 스크래치 테이블을 먼저 지우고, 모든 갱신은 재실행해도 결과가 같다).

DROP TABLE IF EXISTS catalog_dedup_slugs;
DROP TABLE IF EXISTS catalog_dedup_map;
DROP TABLE IF EXISTS catalog_dedup_ueo_ids;
DROP TABLE IF EXISTS catalog_dedup_es_ids;

CREATE TABLE catalog_dedup_slugs (
    duplicate_slug VARCHAR(191) NOT NULL PRIMARY KEY,
    canonical_slug VARCHAR(191) NOT NULL
);

INSERT INTO catalog_dedup_slugs (duplicate_slug, canonical_slug) VALUES
    ('45-degree-bicycle-twisting-crunch', '45-degree-bicycle-twisting'),
    ('alternate-arm-leg-plank-hold', 'alternate-arm-leg-plank'),
    ('alternate-bicep-curl-standing-dumbbells', 'dumbbell-alternate-biceps-curl'),
    ('alternating-superman', 'alternate-superman'),
    ('barbell-bicep-curl-wide-grip', 'barbell-wide-grip-biceps-curl'),
    ('barbell-narrow-stance-full-squat', 'barbell-narrow-stance-squat'),
    ('barbell-reverse-grip-bent-over-row', 'barbell-bent-over-row-supinated-grip'),
    ('barbell-seated-military-press', 'seated-shoulder-press-barbell'),
    ('barbell-standing-shoulder-press', 'barbell-standing-military-press'),
    ('barbell-standing-wide-grip-biceps-curls', 'barbell-wide-grip-biceps-curl'),
    ('barbell-underhand-bent-over-row', 'barbell-bent-over-row-supinated-grip'),
    ('bent-over-barbell-row', 'barbell-bent-over-row-pronated-grip'),
    ('bent-over-row-resistance-band', 'band-bent-over-row'),
    ('bodyweight-calf-raises', 'bodyweight-standing-calf-raise'),
    ('cable-biceps-curl-ez-bar', 'biceps-curl-cable'),
    ('decline-bench-oblique-crunches', 'decline-bench-oblique-crunches-dumbbells'),
    ('dumbbell-alternate-seated-hammer-curl', 'alternate-hammer-curl-seated-dumbbells'),
    ('dumbbell-bulgarian-box-split-squat', 'dumbbell-bulgarian-split-squat'),
    ('dumbbell-chest-press-decline', 'dumbbell-decline-bench-press'),
    ('dumbbell-chest-press-flat', 'dumbbell-bench-press'),
    ('dumbbell-chest-press-incline-bench', 'dumbbell-incline-bench-press'),
    ('dumbbell-fly-flat-bench-slow', 'dumbbell-fly-flat-bench'),
    ('dumbbell-fly-incline-bench', 'dumbbell-incline-fly'),
    ('dumbbell-incline-alternate-curl', 'dumbbell-incline-alternate-bicep-curl'),
    ('dumbbell-incline-biceps-simultaneous-curl', 'incline-dumbbell-curl'),
    ('dumbbell-incline-lying-front-raise', 'dumbbell-incline-front-raise'),
    ('dumbbell-incline-simultaneous-curl', 'incline-dumbbell-curl'),
    ('dumbbell-seated-alternate-shoulder-press', 'dumbbell-alternate-shoulder-press'),
    ('dumbbell-seated-preacher-curl', 'dumbbell-preacher-curl'),
    ('dumbbell-single-arm-floor-press', 'dumbbell-one-arm-floor-press'),
    ('dumbbell-standing-simultaneous-biceps-curl', 'dumbbell-simultaneous-biceps-curl'),
    ('dumbbell-standing-wrist-curl', 'wrist-curl-dumbbell'),
    ('dumbbells-bent-over-row-pronated-grip', 'dumbbell-bent-over-row'),
    ('ez-bar-cable-curl-wide-grip-crossover-machine', 'ez-bar-cable-curl-wide-grip'),
    ('front-raises-dumbbell-seated', 'dumbbell-seated-front-raise'),
    ('hammer-strength-iso-lateral-leg-curl-alternate-legs', 'hammer-strength-iso-lateral-leg-curl-both-leg'),
    ('hammer-strength-iso-lateral-leg-curl-single-leg', 'hammer-strength-iso-lateral-leg-curl-both-leg'),
    ('hammer-strength-mts-iso-lateral-biceps-curl-alternate-arm', 'hammer-strength-mts-iso-lateral-biceps-curl-both-arm'),
    ('hammer-strength-mts-iso-lateral-biceps-curl-single-arm', 'hammer-strength-mts-iso-lateral-biceps-curl-both-arm'),
    ('hammer-strength-mts-iso-lateral-decline-press-alternate-arms', 'hammer-strength-mts-iso-lateral-decline-press-both-arms'),
    ('hammer-strength-mts-iso-lateral-decline-press-single-arm', 'hammer-strength-mts-iso-lateral-decline-press-both-arms'),
    ('hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press-alternate-arm', 'hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press'),
    ('hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press-single-arm', 'hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press'),
    ('hammer-strength-plate-loaded-high-row-alternate-arms', 'hammer-strength-plate-loaded-high-row-both-arms'),
    ('hammer-strength-plate-loaded-high-row-single-arm', 'hammer-strength-plate-loaded-high-row-both-arms'),
    ('iso-lateral-shoulder-press-alternate-arms', 'iso-lateral-shoulder-press-both-arms'),
    ('iso-lateral-shoulder-press-single-arm', 'iso-lateral-shoulder-press-both-arms'),
    ('lat-pull-down-normal-grip', 'cable-pulldown'),
    ('lateral-raises-on-cable-machine', 'cable-lateral-raises'),
    ('machine-fly', 'pec-deck-fly-machine'),
    ('military-press-bodyweight', 'bodyweight-standing-military-press'),
    ('pull-apart-cable-resistance-band', 'resistance-band-pull-apart'),
    ('seated-cable-row-v-bar-machine', 'cable-seated-row-with-v-bar'),
    ('seated-resistance-band-cable-rows', 'resistance-band-cable-row'),
    ('seated-v-up', 'v-up'),
    ('shoulder-abduction', 'lateral-raise-bodyweight'),
    ('single-leg-romanian-deadlift-dumbbell', 'dumbbell-single-leg-deadlift'),
    ('standing-arnold-press-dumbbell', 'arnold-press-dumbbell'),
    ('standing-dumbbell-alternate-shoulder-press', 'dumbbell-alternate-shoulder-press'),
    ('standing-front-raise-dumbbell', 'dumbbell-front-raise'),
    ('wrist-extension-dumbbell', 'dumbbell-reverse-wrist-curl');

CREATE TABLE catalog_dedup_map (
    duplicate_id BIGINT NOT NULL PRIMARY KEY,
    canonical_id BIGINT NOT NULL
);

INSERT INTO catalog_dedup_map (duplicate_id, canonical_id)
SELECT dup.id, canon.id
FROM catalog_dedup_slugs s
JOIN exercises dup ON dup.slug = s.duplicate_slug
JOIN exercises canon ON canon.slug = s.canonical_slug;

-- ── FK 참조 이동: 변형 id -> 표준형 id ─────────────────────────────────────
UPDATE workout_exercises
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = workout_exercises.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE personal_records
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = personal_records.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE exercise_templates
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = exercise_templates.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE template_day_exercises
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = template_day_exercises.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE user_plan_day_exercises
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = user_plan_day_exercises.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE program_day_exercises
SET exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = program_day_exercises.exercise_id)
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE exercise_substitutions
SET original_exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = exercise_substitutions.original_exercise_id)
WHERE original_exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE exercise_substitutions
SET substitute_exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = exercise_substitutions.substitute_exercise_id)
WHERE substitute_exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE injury_exercise_restrictions
SET restricted_exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = injury_exercise_restrictions.restricted_exercise_id)
WHERE restricted_exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE injury_exercise_restrictions
SET suggested_substitute_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = injury_exercise_restrictions.suggested_substitute_id)
WHERE suggested_substitute_id IN (SELECT duplicate_id FROM catalog_dedup_map);


-- user_exercise_overrides (UNIQUE: enrollment_id + original_exercise_id) —
-- 표준형 row 가 이미 있거나 같은 등록에서 두 변형이 같은 표준형으로 모이면, 이동 전에 겹치는 변형 row 를 먼저 지운다.
CREATE TABLE catalog_dedup_ueo_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO catalog_dedup_ueo_ids (id)
SELECT ueo.id
FROM user_exercise_overrides ueo
JOIN catalog_dedup_map m ON m.duplicate_id = ueo.original_exercise_id
JOIN user_exercise_overrides ueo2
    ON ueo2.enrollment_id = ueo.enrollment_id
    AND ueo2.original_exercise_id = m.canonical_id;

INSERT INTO catalog_dedup_ueo_ids (id)
SELECT ueo.id
FROM user_exercise_overrides ueo
JOIN catalog_dedup_map m ON m.duplicate_id = ueo.original_exercise_id
JOIN user_exercise_overrides ueo2
    ON ueo2.enrollment_id = ueo.enrollment_id
    AND ueo2.id < ueo.id
JOIN catalog_dedup_map m2
    ON m2.duplicate_id = ueo2.original_exercise_id
    AND m2.canonical_id = m.canonical_id
WHERE ueo.id NOT IN (SELECT id FROM catalog_dedup_ueo_ids);

DELETE FROM user_exercise_overrides
WHERE id IN (SELECT id FROM catalog_dedup_ueo_ids);

UPDATE user_exercise_overrides
SET original_exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = user_exercise_overrides.original_exercise_id)
WHERE original_exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

UPDATE user_exercise_overrides
SET substitute_exercise_id = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = user_exercise_overrides.substitute_exercise_id)
WHERE substitute_exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);


-- ── self-reference / 중복 row 정리 ─────────────────────────────────────────

DELETE FROM exercise_substitutions WHERE original_exercise_id = substitute_exercise_id;

CREATE TABLE catalog_dedup_es_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO catalog_dedup_es_ids (id)
SELECT es.id
FROM exercise_substitutions es
JOIN exercise_substitutions es2
    ON es2.original_exercise_id = es.original_exercise_id
    AND es2.substitute_exercise_id = es.substitute_exercise_id
    AND es2.id < es.id;

DELETE FROM exercise_substitutions
WHERE id IN (SELECT id FROM catalog_dedup_es_ids);

DELETE FROM user_exercise_overrides
WHERE original_exercise_id = substitute_exercise_id;

UPDATE injury_exercise_restrictions
SET suggested_substitute_id = NULL
WHERE suggested_substitute_id IS NOT NULL
  AND suggested_substitute_id = restricted_exercise_id;

-- ── 변형 운동 자체 + 부속 row 삭제 ─────────────────────────────────────────

DELETE FROM exercise_translations
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

DELETE FROM exercise_muscle_groups
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

DELETE FROM exercises
WHERE id IN (SELECT duplicate_id FROM catalog_dedup_map);

DROP TABLE catalog_dedup_es_ids;
DROP TABLE catalog_dedup_ueo_ids;
DROP TABLE catalog_dedup_map;
DROP TABLE catalog_dedup_slugs;

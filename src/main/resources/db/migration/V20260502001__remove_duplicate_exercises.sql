-- LiftUp AI: 중복 운동(같은 한국어 표시명) 17쌍을 하드 삭제한다.
--
-- 운동 카탈로그에 단/복수 슬러그 변형, 철자 변형 등으로 같은 운동이 두 번 들어가
-- 있어 대체 운동/검색 화면에 같은 한국어 이름이 두 번 노출되는 문제를 해결한다.
-- 각 쌍에서 표준형(예: barbell-lunge)을 남기고 변형(barbell-lunges)을 제거한다.
--
-- 모든 FK 참조(사용자 플랜, 운동 기록, 추천 매핑 등)를 표준형 ID로 마이그레이션한
-- 뒤 변형 운동 row를 삭제한다. ddl-auto=validate라 스키마 변경은 없고 데이터만 조작.

-- ── 매핑 임시 테이블: duplicate_id -> canonical_id ────────────────────────
CREATE TEMPORARY TABLE _dup_exercise_map (
    duplicate_id BIGINT NOT NULL PRIMARY KEY,
    canonical_id BIGINT NOT NULL
);

INSERT INTO _dup_exercise_map (duplicate_id, canonical_id)
SELECT dup.id, canon.id
FROM exercises dup
JOIN exercises canon ON canon.slug = (
    CASE dup.slug
        WHEN '90-degree-heels-touch' THEN '90-degree-heel-touch'
        WHEN 'alternate-single-leg-raises-plank' THEN 'alternate-single-leg-raise-plank'
        WHEN 'back-forward-leg-swings' THEN 'back-and-forward-leg-swings'
        WHEN 'back-and-shoulders-stretch' THEN 'back-and-shoulder-stretch'
        WHEN 'back-slaps-wrap-arround-stretch' THEN 'back-slaps-wrap-around-stretch'
        WHEN 'barbell-back-wide-shrugs' THEN 'barbell-back-wide-shrug'
        WHEN 'barbell-bench-squats' THEN 'barbell-bench-squat'
        WHEN 'barbell-lateral-lunges' THEN 'barbell-lateral-lunge'
        WHEN 'barbell-lunges' THEN 'barbell-lunge'
        WHEN 'barbell-lying-triceps-skull-crushers' THEN 'barbell-lying-triceps-skull-crusher'
        WHEN 'bodyweight-full-squat-with-overhead-front-raises' THEN 'bodyweight-full-squat-with-overhead-front-raise'
        WHEN 'elbows-back-stretch' THEN 'elbow-back-stretch'
        WHEN 'hammer-strength-mts-iso-lateral-decline-press-alternating-arms' THEN 'hammer-strength-mts-iso-lateral-decline-press-alternate-arms'
        WHEN 'hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press-alternating-arms' THEN 'hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press-alternate-arm'
        WHEN 'hammer-strength-plate-loaded-high-row-single-arms' THEN 'hammer-strength-plate-loaded-high-row-single-arm'
        WHEN 'kettlebells-sumo-deadlift-with-high-pull' THEN 'kettlebell-sumo-deadlift-with-high-pull'
        WHEN 'resistance-bands-facepull' THEN 'resistance-band-face-pull'
    END
)
WHERE dup.slug IN (
    '90-degree-heels-touch',
    'alternate-single-leg-raises-plank',
    'back-forward-leg-swings',
    'back-and-shoulders-stretch',
    'back-slaps-wrap-arround-stretch',
    'barbell-back-wide-shrugs',
    'barbell-bench-squats',
    'barbell-lateral-lunges',
    'barbell-lunges',
    'barbell-lying-triceps-skull-crushers',
    'bodyweight-full-squat-with-overhead-front-raises',
    'elbows-back-stretch',
    'hammer-strength-mts-iso-lateral-decline-press-alternating-arms',
    'hammer-strength-plate-loaded-combination-iso-lateral-chest-chest-press-alternating-arms',
    'hammer-strength-plate-loaded-high-row-single-arms',
    'kettlebells-sumo-deadlift-with-high-pull',
    'resistance-bands-facepull'
);

-- ── FK 참조 마이그레이션: 변형 ID -> 표준형 ID ─────────────────────────────

-- 사용자 운동 기록
UPDATE workout_exercises we
JOIN _dup_exercise_map m ON m.duplicate_id = we.exercise_id
SET we.exercise_id = m.canonical_id;

UPDATE workout_logs wl
JOIN _dup_exercise_map m ON m.duplicate_id = wl.exercise_id
SET wl.exercise_id = m.canonical_id;

-- 사용자 플랜
UPDATE user_plan_day_exercises upde
JOIN _dup_exercise_map m ON m.duplicate_id = upde.exercise_id
SET upde.exercise_id = m.canonical_id;

-- 개인 기록 (PR)
UPDATE personal_records pr
JOIN _dup_exercise_map m ON m.duplicate_id = pr.exercise_id
SET pr.exercise_id = m.canonical_id;

-- 캐노니컬 프로그램 / 템플릿
UPDATE program_day_exercises pde
JOIN _dup_exercise_map m ON m.duplicate_id = pde.exercise_id
SET pde.exercise_id = m.canonical_id;

UPDATE template_day_exercises tde
JOIN _dup_exercise_map m ON m.duplicate_id = tde.exercise_id
SET tde.exercise_id = m.canonical_id;

UPDATE exercise_templates et
JOIN _dup_exercise_map m ON m.duplicate_id = et.exercise_id
SET et.exercise_id = m.canonical_id;

-- 추천 매핑 (original/substitute 양쪽)
UPDATE exercise_substitutions es
JOIN _dup_exercise_map m ON m.duplicate_id = es.original_exercise_id
SET es.original_exercise_id = m.canonical_id;

UPDATE exercise_substitutions es
JOIN _dup_exercise_map m ON m.duplicate_id = es.substitute_exercise_id
SET es.substitute_exercise_id = m.canonical_id;

-- 부상 제한
UPDATE injury_exercise_restrictions ier
JOIN _dup_exercise_map m ON m.duplicate_id = ier.restricted_exercise_id
SET ier.restricted_exercise_id = m.canonical_id;

UPDATE injury_exercise_restrictions ier
JOIN _dup_exercise_map m ON m.duplicate_id = ier.suggested_substitute_id
SET ier.suggested_substitute_id = m.canonical_id
WHERE ier.suggested_substitute_id IS NOT NULL;

-- 사용자 운동 오버라이드 (UNIQUE: enrollment_id + original_exercise_id):
-- 같은 enrollment 안에서 표준형 row가 이미 있으면 변형 row를 먼저 삭제해 충돌 방지.
-- MySQL은 DELETE 대상 테이블을 서브쿼리에서 참조할 수 없어 (error 1093)
-- 삭제 대상 ID를 별도 temp 테이블에 모은 뒤 IN 절로 삭제한다.
CREATE TEMPORARY TABLE _ueo_delete_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO _ueo_delete_ids (id)
SELECT ueo.id
FROM user_exercise_overrides ueo
JOIN _dup_exercise_map m ON m.duplicate_id = ueo.original_exercise_id
JOIN user_exercise_overrides ueo2
    ON ueo2.enrollment_id = ueo.enrollment_id
    AND ueo2.original_exercise_id = m.canonical_id;

DELETE FROM user_exercise_overrides
WHERE id IN (SELECT id FROM _ueo_delete_ids);

DROP TEMPORARY TABLE _ueo_delete_ids;

UPDATE user_exercise_overrides ueo
JOIN _dup_exercise_map m ON m.duplicate_id = ueo.original_exercise_id
SET ueo.original_exercise_id = m.canonical_id;

UPDATE user_exercise_overrides ueo
JOIN _dup_exercise_map m ON m.duplicate_id = ueo.substitute_exercise_id
SET ueo.substitute_exercise_id = m.canonical_id;

-- ── self-reference / 중복 row 정리 ─────────────────────────────────────────
-- FK 마이그레이션 결과로 (original == substitute)이 된 row는 의미가 없으므로 제거.
-- 또한 (original, substitute) 쌍이 동일한 row가 여러 개 생겼을 수 있어 1개만 남긴다.
-- self-join DELETE도 안전성을 위해 staging temp 테이블 사용.

DELETE FROM exercise_substitutions WHERE original_exercise_id = substitute_exercise_id;

CREATE TEMPORARY TABLE _es_delete_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO _es_delete_ids (id)
SELECT es.id
FROM exercise_substitutions es
JOIN exercise_substitutions es2
    ON es2.original_exercise_id = es.original_exercise_id
    AND es2.substitute_exercise_id = es.substitute_exercise_id
    AND es2.id < es.id;

DELETE FROM exercise_substitutions
WHERE id IN (SELECT id FROM _es_delete_ids);

DROP TEMPORARY TABLE _es_delete_ids;

DELETE FROM user_exercise_overrides
WHERE original_exercise_id = substitute_exercise_id;

UPDATE injury_exercise_restrictions
SET suggested_substitute_id = NULL
WHERE suggested_substitute_id IS NOT NULL
  AND suggested_substitute_id = restricted_exercise_id;

-- ── 변형 운동 자체 + 부속 row 삭제 ─────────────────────────────────────────

DELETE FROM exercise_translations
WHERE exercise_id IN (SELECT duplicate_id FROM _dup_exercise_map);

DELETE FROM exercise_muscle_groups
WHERE exercise_id IN (SELECT duplicate_id FROM _dup_exercise_map);

DELETE FROM exercises
WHERE id IN (SELECT duplicate_id FROM _dup_exercise_map);

DROP TEMPORARY TABLE _dup_exercise_map;

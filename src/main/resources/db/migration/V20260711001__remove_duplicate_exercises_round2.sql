-- LiftUp AI: 중복 운동 2차 정리 — 6쌍 하드 삭제.
--
-- 2026-07-11 프로덕션 실사에서 발견된 잔여 중복. 모두 equipment/난이도/tier/근육군이
-- 동일한 완전 중복이며, 원인은 단/복수 슬러그 변형, 띄어쓰기 변형, 임포트 시
-- "V.2"/"(2)" 잔재. 각 쌍에서 표준형을 남기고 변형을 제거한다.
-- (hammer-strength / iso-lateral 쌍은 복수형(-both-arms)이 한국어 번역도 올바른
--  "양팔"이므로 복수형을 표준으로 삼는다. 단수형의 ko 번역은 "보스 암" 오역.)
--
-- 실사 기준 변형 6개의 FK 참조는 0건이지만, 마이그레이션 적용 시점의 데이터가
-- 다를 수 있으므로 V20260502001과 동일하게 전체 FK 리매핑을 방어적으로 수행한다.

CREATE TEMPORARY TABLE _dup_exercise_map (
    duplicate_id BIGINT NOT NULL PRIMARY KEY,
    canonical_id BIGINT NOT NULL
);

INSERT INTO _dup_exercise_map (duplicate_id, canonical_id)
SELECT dup.id, canon.id
FROM exercises dup
JOIN exercises canon ON canon.slug = (
    CASE dup.slug
        WHEN 'archer-stepback' THEN 'archer-step-back'
        WHEN 'dumbbells-bent-over-row' THEN 'dumbbell-bent-over-row'
        WHEN 'hammer-strength-mts-iso-lateral-decline-press-both-arm' THEN 'hammer-strength-mts-iso-lateral-decline-press-both-arms'
        WHEN 'iso-lateral-shoulder-press-both-arm' THEN 'iso-lateral-shoulder-press-both-arms'
        WHEN 'exercise-ball-lying-side-lat-stretch-v-2' THEN 'exercise-ball-lying-side-lat-stretch'
        WHEN 'in-and-out-squats-jump-bodyweight-2' THEN 'in-and-out-squats-jump-bodyweight'
    END
)
WHERE dup.slug IN (
    'archer-stepback',
    'dumbbells-bent-over-row',
    'hammer-strength-mts-iso-lateral-decline-press-both-arm',
    'iso-lateral-shoulder-press-both-arm',
    'exercise-ball-lying-side-lat-stretch-v-2',
    'in-and-out-squats-jump-bodyweight-2'
);

-- ── FK 참조 마이그레이션: 변형 ID -> 표준형 ID ─────────────────────────────

UPDATE workout_exercises we
JOIN _dup_exercise_map m ON m.duplicate_id = we.exercise_id
SET we.exercise_id = m.canonical_id;

UPDATE workout_logs wl
JOIN _dup_exercise_map m ON m.duplicate_id = wl.exercise_id
SET wl.exercise_id = m.canonical_id;

UPDATE user_plan_day_exercises upde
JOIN _dup_exercise_map m ON m.duplicate_id = upde.exercise_id
SET upde.exercise_id = m.canonical_id;

UPDATE personal_records pr
JOIN _dup_exercise_map m ON m.duplicate_id = pr.exercise_id
SET pr.exercise_id = m.canonical_id;

UPDATE program_day_exercises pde
JOIN _dup_exercise_map m ON m.duplicate_id = pde.exercise_id
SET pde.exercise_id = m.canonical_id;

UPDATE template_day_exercises tde
JOIN _dup_exercise_map m ON m.duplicate_id = tde.exercise_id
SET tde.exercise_id = m.canonical_id;

UPDATE exercise_templates et
JOIN _dup_exercise_map m ON m.duplicate_id = et.exercise_id
SET et.exercise_id = m.canonical_id;

UPDATE exercise_substitutions es
JOIN _dup_exercise_map m ON m.duplicate_id = es.original_exercise_id
SET es.original_exercise_id = m.canonical_id;

UPDATE exercise_substitutions es
JOIN _dup_exercise_map m ON m.duplicate_id = es.substitute_exercise_id
SET es.substitute_exercise_id = m.canonical_id;

UPDATE injury_exercise_restrictions ier
JOIN _dup_exercise_map m ON m.duplicate_id = ier.restricted_exercise_id
SET ier.restricted_exercise_id = m.canonical_id;

UPDATE injury_exercise_restrictions ier
JOIN _dup_exercise_map m ON m.duplicate_id = ier.suggested_substitute_id
SET ier.suggested_substitute_id = m.canonical_id
WHERE ier.suggested_substitute_id IS NOT NULL;

-- user_exercise_overrides (UNIQUE: enrollment_id + original_exercise_id) —
-- 표준형 row가 이미 있으면 변형 row 먼저 삭제 (MySQL error 1093 회피용 temp 테이블).
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

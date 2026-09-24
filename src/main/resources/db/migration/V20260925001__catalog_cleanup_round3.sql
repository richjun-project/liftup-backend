-- LiftUp AI: 운동 카탈로그 3차 정리 — 중복·좌우/촬영각도 변형·기믹 운동 72개 하드 삭제.
--
-- 2026-09-25 골든셋 평가 중 카탈로그 전수 점검. 삭제 대상은 모두 남는 표준형과 같은 동작이다:
--   · 지시문이 글자까지 같은 촬영 각도/POV/360도 변형 ("풀 스쿼트 백 시점", "데드리프트 360 도")
--   · 단어 순서만 바뀐 슬러그 ("upright-row-barbell" ↔ "barbell-upright-row")
--   · "(버전 2)" 임포트 잔재, 좌/우 한쪽만 다른 변형 ("불가리안 스플릿 스쿼트 맨몸 레프트/라이트")
--   · 소품 기믹 ("플랭크 리버스 플라이 보틀", "커시 런지 슬라이드 타월")
-- 이름 수정·추천 후보(plan-eligible) 지정은 exercise-catalog.json 에 반영되어 기동 시 카탈로그 싱크가 적용한다.
--
-- V20260711001 과 같은 방식: 사용자의 운동 기록·PR·플랜이 가리키는 변형 ID 를 표준형 ID 로 옮긴 뒤 지운다.
-- 슬러그로 매핑하므로 DB 에 없는 슬러그 쌍은 조용히 건너뛴다.

CREATE TEMPORARY TABLE _dup_slug_map (
    duplicate_slug VARCHAR(255) NOT NULL PRIMARY KEY,
    canonical_slug VARCHAR(255) NOT NULL
);

INSERT INTO _dup_slug_map (duplicate_slug, canonical_slug) VALUES
    ('45-degree-bicycle-twist-knee-to-elbow', '45-degree-bicycle-twisting'),
    ('45-degree-hyperextension-arms-in-front-of-chest', 'back-extension-machine'),
    ('45-degree-hyperextension-arms-to-chest', 'back-extension-machine'),
    ('ab-wheel-right-out', 'ab-wheel-left-out'),
    ('abdominal-stretch-version2', 'abdominal-stretch'),
    ('air-bike-version-2', 'air-bike'),
    ('alternate-leg-raise-with-head-up', 'alternate-leg-raise'),
    ('alternating-hammer-curls-seated-dumbbells', 'alternate-hammer-curl-seated-dumbbells'),
    ('assisted-pull-up-normal-grip', 'assisted-pull-up'),
    ('balance-board-version-2', 'balance-board-lateral-squat'),
    ('barbell-bench-press-incline', 'barbell-incline-bench-press'),
    ('barbell-bicep-curl-normal-grip', 'barbell-biceps-curl'),
    ('barbell-bulgarian-split-squat-left-front-back-view', 'barbell-bulgarian-split-squat'),
    ('barbell-bulgarian-split-squat-left-side-view', 'barbell-bulgarian-split-squat'),
    ('barbell-deadlift-360-degrees', 'barbell-deadlift'),
    ('barbell-drag-biceps-curl', 'barbell-drag-curl'),
    ('barbell-front-chest-squat', 'barbell-front-squats'),
    ('barbell-full-squat-back-pov', 'barbell-full-squat'),
    ('barbell-full-squat-side-pov', 'barbell-full-squat'),
    ('barbell-lunges-on-the-spot', 'barbell-lunge'),
    ('bench-bulgarian-split-squats-left', 'bulgarian-split-squat'),
    ('bench-bulgarian-split-squats-right', 'bulgarian-split-squat'),
    ('bird-dog-version-2', 'bird-dog'),
    ('briskly-walking-360-degrees', 'briskly-walking'),
    ('bulgarian-split-squat-bodyweight', 'bulgarian-split-squat'),
    ('bulgarian-split-squat-bodyweight-left', 'bulgarian-split-squat'),
    ('bulgarian-split-squat-bodyweight-right', 'bulgarian-split-squat'),
    ('bulgarian-split-squat-left-bodyweight-side-view', 'bulgarian-split-squat'),
    ('bulgarian-split-squat-right-bodyweight-side-view', 'bulgarian-split-squat'),
    ('butt-kicks-slow', 'butt-kicks'),
    ('cable-reverse-grip-triceps-push-down-straight-bar-on-crossover', 'cable-reverse-grip-pushdown'),
    ('cable-reverse-grip-triceps-pushdown-back-side-pov', 'cable-reverse-grip-pushdown'),
    ('calf-push-stretch-with-hands-against-wall', 'calf-stretch-with-hands-against-wall'),
    ('chair-bulgarian-split-squats-bodyweight-left', 'chair-bulgarian-split-squats-bodyweight'),
    ('chair-bulgarian-split-squats-bodyweight-right', 'chair-bulgarian-split-squats-bodyweight'),
    ('chin-up-reverse-normal-grip', 'chin-up'),
    ('close-grip-barbell-bench-press', 'barbell-close-grip-bench-press'),
    ('close-reverse-grip-chin-up', 'chin-up-reverse-close-grip'),
    ('curtsy-lunge-slide-with-towel', 'curtsy-lunge-bodyweight'),
    ('decline-sit-ups-dumbbells', 'dumbbell-decline-sit-up'),
    ('diagonal-chop-right', 'diagonal-chop-left'),
    ('dumbbell-alternate-biceps-curl-version-2', 'dumbbell-alternate-biceps-curl'),
    ('dumbbell-arnold-press-version-2', 'arnold-press-dumbbell'),
    ('dumbbell-cossack-squats-version-2', 'cossack-squat'),
    ('dumbbell-front-raise-version-2', 'dumbbell-front-raise'),
    ('dumbbell-hip-thrust-version-2', 'dumbbell-hip-thrust'),
    ('dumbbell-one-arm-shoulder-press-version-2', 'seated-dumbbell-one-arm-shoulder-press'),
    ('dumbbell-pullover-version-2', 'dumbbell-pullover'),
    ('dumbbell-scott-press-360-degrees', 'dumbbell-scott-press'),
    ('front-raises-barbell', 'barbell-front-raise'),
    ('good-mornings-barbell', 'barbell-good-morning'),
    ('hack-squat-machine-squat', 'hack-squat-machine'),
    ('hammer-strength-iso-lateral-leg-curl-alternating-legs', 'hammer-strength-iso-lateral-leg-curl-alternate-legs'),
    ('hammer-strength-mts-iso-lateral-biceps-curl-arm-alternating', 'hammer-strength-mts-iso-lateral-biceps-curl-alternate-arm'),
    ('kettlebell-bulgarian-split-squat-right-side-view', 'kettlebell-bulgarian-split-squat-right'),
    ('kettlebell-sumo-squat-version-2', 'kettlebell-sumo-squat'),
    ('kettlebell-swing-version-2', 'kettlebell-swing'),
    ('leg-press-wide-high-stance-behind-view', 'leg-press-wide-high-stance'),
    ('lunge-to-knee-drive-right-bodyweight', 'lunge-to-knee-drive-left-bodyweight'),
    ('overhead-triceps-stretch-side-angle', 'overhand-tricep-stretching-single-arm'),
    ('plank-reverse-fly-with-bottle', 'dumbbell-reverse-fly'),
    ('preacher-curl-barbell', 'barbell-preacher-curl'),
    ('pull-up-wide-grip-front-view', 'pull-up-wide-grip'),
    ('resistance-band-glute-bridge-version2', 'resistance-band-glute-bridge'),
    ('resistance-band-hip-thrust-version-2', 'resistance-band-hip-thrusts-on-knees'),
    ('resistance-band-standing-balance-glute-kickback-version-2', 'resistance-band-standing-balance-glute-kickback'),
    ('reverse-lunge-left-resistance-band', 'lunge-reverse-with-resistance-band'),
    ('side-lying-leg-lift-right', 'side-lying-leg-lift-left'),
    ('single-arm-cable-row-right', 'single-arm-cable-row-left'),
    ('standing-quadriceps-stretch-side-angle', 'standing-quadriceps-stretch'),
    ('upright-row-barbell', 'barbell-upright-row'),
    ('wall-squat-bodyweight', 'bodyweight-wall-squat');

CREATE TEMPORARY TABLE _dup_exercise_map (
    duplicate_id BIGINT NOT NULL PRIMARY KEY,
    canonical_id BIGINT NOT NULL
);

INSERT INTO _dup_exercise_map (duplicate_id, canonical_id)
SELECT dup.id, canon.id
FROM _dup_slug_map m
JOIN exercises dup ON dup.slug = m.duplicate_slug
JOIN exercises canon ON canon.slug = m.canonical_slug;

DROP TEMPORARY TABLE _dup_slug_map;

-- ── FK 참조 마이그레이션: 변형 ID -> 표준형 ID ─────────────────────────────

UPDATE workout_exercises we
JOIN _dup_exercise_map m ON m.duplicate_id = we.exercise_id
SET we.exercise_id = m.canonical_id;

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

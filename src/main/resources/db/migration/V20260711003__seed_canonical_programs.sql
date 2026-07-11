-- ============================================================
-- V20260711003: 표준 프로그램(Canonical Program) 시드 데이터
-- ============================================================
--
-- 배경: canonical_programs / program_days / program_day_exercises 가 프로덕션에서
-- 전부 0행이라, 가장 정교한 운동 생성 엔진(ProgramWorkoutGeneratorService — BLOCK 주기화,
-- RPE 자동조절, MEV/MAV 볼륨 조정, 부상/장비 자동 대체)이 단 한 번도 실행되지 못하고 있었다.
-- 이 마이그레이션은 실제로 동작 가능한 4개의 표준 프로그램을 시드한다.
--
-- 코드 선택 근거:
--   AutoProgramSelector.selectProgram()은 CanonicalProgramRepository.findByCode(programType)로
--   정확 문자열 매칭한다 (programType ∈ {FULL_BODY, UPPER_LOWER, PPL, PPLUL}).
--   canonical_programs.code는 uk_canonical_programs_code 유니크 제약이 걸려 있으므로
--   이 4개 코드는 정확히 이 문자열로, 프로그램당 1행만 존재해야 selector가 항상 매칭된다.
--   (초보자/1일 이하 및 주 2~3일은 selector가 전부 FULL_BODY로 수렴하므로 별도 2일차 변형은
--   만들지 않는다 — code가 어차피 같아서 별 프로그램을 만들어도 selector가 찾지 못한다.)
--
-- CanonicalProgramService.getRecommendedProgram()의 폴백 체인(경험치+목표 정확매치 →
-- 경험치만 매치 → 경험치 거리순)도 4개 경험치 구간(BEGINNER/INTERMEDIATE/ADVANCED)에
-- 대해 항상 최소 1개 프로그램이 존재하도록 구성했다.
--
-- progressionModel과 deloadEveryNWeeks의 정합성 (ProgramProgressiveOverloadService 참고):
--   - UNDULATING: calculateUndulating()의 주간 램프가 4주 메조사이클(week-1)%4로 순환하므로
--     deload_every_n_weeks=4로 맞춰 매 메조사이클 끝에 디로드가 오도록 했다.
--   - BLOCK: getBlockPhaseAdjustment()/calculateBlock()이 7주 블록(week-1)%7로 축적기(0~1주)→
--     강화기(2~3주)→실현기(4~5주)→디로드(6주)를 계산하므로 deload_every_n_weeks=7로 맞춰
--     ProgramEnrollmentService.getCurrentPosition()이 계산하는 isDeloadWeek이 엔진의 내부
--     블록 디로드 주차와 정확히 일치하도록 했다 (그렇지 않으면 두 디로드 판정이 어긋난다).
--
-- 운동 선택: exercise-catalog.json(1295개)에서 slug를 대조해 recommendationTier=ESSENTIAL을
-- 우선으로, 없으면 잘 알려진 STANDARD 슬러그만 사용했다. ADVANCED/SPECIALIZED 등급이나
-- 삭제된 슬러그(archer-stepback 등)는 사용하지 않는다.
--
-- 안전장치(부분 시드 방지): program_day_exercises.exercise_id는 NOT NULL이다. 아래에서
-- exercise_id는 항상 스칼라 서브쿼리 (SELECT id FROM exercises WHERE slug = '...')로만 채운다.
-- exercises를 JOIN해버리면 슬러그가 없을 때 그 행이 조용히 사라지므로(silent partial seed),
-- 절대 JOIN하지 않고 스칼라 서브쿼리를 쓴다 — 슬러그가 존재하지 않으면 NULL이 반환되어
-- NOT NULL 제약 위반으로 문장 전체가 즉시 실패한다(조용한 부분 시드 없음).
--
-- 멱등성: 모든 INSERT에 WHERE NOT EXISTS 가드를 둬 재실행해도 중복 삽입되지 않는다.
-- MySQL의 오류 1093("You can't specify target table for update in FROM clause")은
-- UPDATE/DELETE가 같은 테이블을 서브쿼리에서 갱신 대상으로 참조할 때만 발생하고,
-- 단순 SELECT 서브쿼리를 사용하는 INSERT ... SELECT ... WHERE NOT EXISTS 패턴에는
-- 적용되지 않으므로 안전하다.
--
-- 주의: program_day_exercises.intensity_percent_low/high 컬럼은 선행 마이그레이션
-- V20260711002에서 DROP되었으므로(런타임 BlockPhaseAdjustment로 대체) 여기서는 사용하지 않는다.

-- ── 1. Canonical Programs ──────────────────────────────────────────────────

-- FULL_BODY: 초보자 전신 3일 프로그램 (Full Body Beginner)
INSERT INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal, days_per_week,
     program_duration_weeks, deload_every_n_weeks, progression_model, next_program_code,
     version, description, is_active)
SELECT * FROM (SELECT
    'FULL_BODY' AS code, '초보자 전신 3일 프로그램 (Full Body Beginner)' AS name, 'FULL_BODY' AS split_type,
    'BEGINNER' AS target_experience_level, 'MUSCLE_GAIN' AS target_goal,
    3 AS days_per_week, 10 AS program_duration_weeks,
    6 AS deload_every_n_weeks, 'LINEAR' AS progression_model,
    'UPPER_LOWER' AS next_program_code, 1 AS version, '주 3회 전신 운동. 스쿼트/벤치프레스/데드리프트 3대 운동을 매 세션 소량씩 증량하는 선형 진행(Linear Progression) 프로그램으로, 초보자의 빠른 신경계 적응을 활용해 가장 단순하고 확실하게 근력을 쌓는다. 6주마다 디로드.' AS description,
    1 AS is_active
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM canonical_programs WHERE code = 'FULL_BODY');

-- UPPER_LOWER: 중급자 상하체 4분할 프로그램 (Upper/Lower Split)
INSERT INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal, days_per_week,
     program_duration_weeks, deload_every_n_weeks, progression_model, next_program_code,
     version, description, is_active)
SELECT * FROM (SELECT
    'UPPER_LOWER' AS code, '중급자 상하체 4분할 프로그램 (Upper/Lower Split)' AS name, 'UPPER_LOWER' AS split_type,
    'INTERMEDIATE' AS target_experience_level, 'MUSCLE_GAIN' AS target_goal,
    4 AS days_per_week, 8 AS program_duration_weeks,
    4 AS deload_every_n_weeks, 'UNDULATING' AS progression_model,
    'PPL' AS next_program_code, 1 AS version, '주 4회 상체/하체 분할. 세션마다 고강도(Heavy)·저강도(Volume) 자극을 번갈아 적용하는 일일 파동 주기화(Daily Undulating Periodization)로 근력과 근비대를 동시에 추구한다. 4주 메조사이클마다 디로드.' AS description,
    1 AS is_active
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM canonical_programs WHERE code = 'UPPER_LOWER');

-- PPL: 중급자 푸시/풀/레그 6일 분할 (Push/Pull/Legs)
INSERT INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal, days_per_week,
     program_duration_weeks, deload_every_n_weeks, progression_model, next_program_code,
     version, description, is_active)
SELECT * FROM (SELECT
    'PPL' AS code, '중급자 푸시/풀/레그 6일 분할 (Push/Pull/Legs)' AS name, 'PPL' AS split_type,
    'INTERMEDIATE' AS target_experience_level, 'MUSCLE_GAIN' AS target_goal,
    6 AS days_per_week, 8 AS program_duration_weeks,
    4 AS deload_every_n_weeks, 'UNDULATING' AS progression_model,
    'PPLUL' AS next_program_code, 1 AS version, '주 6회 Push/Pull/Legs를 2회씩 반복하는 고빈도 분할. 전반부는 고강도 저반복, 후반부는 고반복 볼륨 세션으로 파동 주기화하여 근육군별 주 2회 자극 빈도를 확보한다. 4주 메조사이클마다 디로드.' AS description,
    1 AS is_active
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM canonical_programs WHERE code = 'PPL');

-- PPLUL: 상급자 PPL+상하체 5일 하이브리드 (Advanced Block Periodization)
INSERT INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal, days_per_week,
     program_duration_weeks, deload_every_n_weeks, progression_model, next_program_code,
     version, description, is_active)
SELECT * FROM (SELECT
    'PPLUL' AS code, '상급자 PPL+상하체 5일 하이브리드 (Advanced Block Periodization)' AS name, 'PPLUL' AS split_type,
    'ADVANCED' AS target_experience_level, 'STRENGTH' AS target_goal,
    5 AS days_per_week, 14 AS program_duration_weeks,
    7 AS deload_every_n_weeks, 'BLOCK' AS progression_model,
    NULL AS next_program_code, 1 AS version, '주 5회 Push/Pull/Legs/Upper/Lower 하이브리드 분할. 축적기(고반복·고볼륨) → 강화기(중반복·중강도) → 실현기(저반복·고강도) → 디로드로 이어지는 7주 블록 주기화(Block Periodization)를 통해 상급자의 정체기를 관리하며 최고 강도를 향해 쌓아올린다. deload_every_n_weeks=7은 ProgramProgressiveOverloadService의 7주 블록 계산과 정확히 맞물린다.' AS description,
    1 AS is_active
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM canonical_programs WHERE code = 'PPLUL');

-- ── 2. Program Days ────────────────────────────────────────────────────────

-- FULL_BODY: program_days
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 1, 'FULL_BODY', '전신 A - 스쿼트/벤치프레스/로우', 60
FROM canonical_programs cp WHERE cp.code = 'FULL_BODY'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 1
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 2, 'FULL_BODY', '전신 B - 스쿼트/오버헤드프레스/풀업', 60
FROM canonical_programs cp WHERE cp.code = 'FULL_BODY'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 2
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 3, 'FULL_BODY', '전신 C - 데드리프트/벤치프레스/로우', 65
FROM canonical_programs cp WHERE cp.code = 'FULL_BODY'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 3
  );

-- UPPER_LOWER: program_days
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 1, 'UPPER', '상체 A (고강도)', 65
FROM canonical_programs cp WHERE cp.code = 'UPPER_LOWER'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 1
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 2, 'LOWER', '하체 A (고강도)', 65
FROM canonical_programs cp WHERE cp.code = 'UPPER_LOWER'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 2
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 3, 'UPPER', '상체 B (저강도/볼륨)', 60
FROM canonical_programs cp WHERE cp.code = 'UPPER_LOWER'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 3
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 4, 'LOWER', '하체 B (저강도/볼륨)', 60
FROM canonical_programs cp WHERE cp.code = 'UPPER_LOWER'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 4
  );

-- PPL: program_days
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 1, 'PUSH', 'Push A (고강도)', 65
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 1
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 2, 'PULL', 'Pull A (고강도)', 65
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 2
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 3, 'LEGS', 'Legs A (고강도)', 65
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 3
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 4, 'PUSH', 'Push B (볼륨)', 60
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 4
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 5, 'PULL', 'Pull B (볼륨)', 60
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 5
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 6, 'LEGS', 'Legs B (볼륨)', 60
FROM canonical_programs cp WHERE cp.code = 'PPL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 6
  );

-- PPLUL: program_days
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 1, 'PUSH', 'Push (Block)', 65
FROM canonical_programs cp WHERE cp.code = 'PPLUL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 1
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 2, 'PULL', 'Pull (Block)', 65
FROM canonical_programs cp WHERE cp.code = 'PPLUL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 2
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 3, 'LEGS', 'Legs (Block)', 65
FROM canonical_programs cp WHERE cp.code = 'PPLUL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 3
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 4, 'UPPER', 'Upper (Block)', 65
FROM canonical_programs cp WHERE cp.code = 'PPLUL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 4
  );
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
SELECT cp.id, 5, 'LOWER', 'Lower (Block)', 65
FROM canonical_programs cp WHERE cp.code = 'PPLUL'
  AND NOT EXISTS (
    SELECT 1 FROM program_days pd WHERE pd.program_id = cp.id AND pd.day_number = 5
  );

-- ── 3. Program Day Exercises ───────────────────────────────────────────────

-- FULL_BODY day 1 (전신 A - 스쿼트/벤치프레스/로우)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'FULL_BODY' AND pd.day_number = 1
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-full-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 5 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 5 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'bent-over-barbell-row' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'high-plank' AS slug, 0 AS is_compound, 3 AS `sets`, 30 AS min_reps, 45 AS max_reps, 60 AS rest_seconds, 6.5 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, '플랭크: reps는 초 단위 유지 시간' AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- FULL_BODY day 2 (전신 B - 스쿼트/오버헤드프레스/풀업)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'FULL_BODY' AND pd.day_number = 2
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-full-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 5 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-seated-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 5 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'assisted-pull-up' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'side-plank' AS slug, 0 AS is_compound, 3 AS `sets`, 20 AS min_reps, 30 AS max_reps, 60 AS rest_seconds, 6.5 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, '사이드 플랭크: 좌우 각 20~30초' AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- FULL_BODY day 3 (전신 C - 데드리프트/벤치프레스/로우)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'FULL_BODY' AND pd.day_number = 3
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 4 AS min_reps, 6 AS max_reps, 210 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 5 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'bent-over-barbell-row' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'hanging-knee-raises' AS slug, 0 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 60 AS rest_seconds, 6.5 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- UPPER_LOWER day 1 (상체 A (고강도))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'UPPER_LOWER' AND pd.day_number = 1
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-bench-press' AS slug, 1 AS is_compound, 4 AS `sets`, 4 AS min_reps, 6 AS max_reps, 180 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'bent-over-barbell-row' AS slug, 1 AS is_compound, 4 AS `sets`, 6 AS min_reps, 8 AS max_reps, 150 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'barbell-seated-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'cable-pulldown' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 90 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'ez-barbell-curl' AS slug, 0 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 6 AS order_in_day, 'barbell-lying-triceps-skull-crusher' AS slug, 0 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- UPPER_LOWER day 2 (하체 A (고강도))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'UPPER_LOWER' AND pd.day_number = 2
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-full-squat' AS slug, 1 AS is_compound, 4 AS `sets`, 4 AS min_reps, 6 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-romanian-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'leg-press-machine-normal-stance' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'lying-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'hanging-knee-raises' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- UPPER_LOWER day 3 (상체 B (저강도/볼륨))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'UPPER_LOWER' AND pd.day_number = 3
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'dumbbell-incline-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'dumbbell-incline-row' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'dumbbell-standing-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'straight-bar-cable-row-normal-grip' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 90 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'lateral-raises-dumbbell' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 6 AS order_in_day, 'resistance-band-face-pull' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- UPPER_LOWER day 4 (하체 B (저강도/볼륨))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'UPPER_LOWER' AND pd.day_number = 4
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-front-squats' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'dumbbell-hip-thrust' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'barbell-bulgarian-split-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, '좌우 각 8~10회' AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'seated-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'dumbbell-shrugs' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 1 (Push A (고강도))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 1
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-bench-press' AS slug, 1 AS is_compound, 4 AS `sets`, 4 AS min_reps, 6 AS max_reps, 180 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-seated-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'barbell-incline-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'lateral-raises-dumbbell' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'barbell-lying-triceps-skull-crusher' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 2 (Pull A (고강도))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 2
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 4 AS min_reps, 6 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'bent-over-barbell-row' AS slug, 1 AS is_compound, 4 AS `sets`, 6 AS min_reps, 8 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'assisted-pull-up' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'dumbbell-incline-row' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'ez-barbell-curl' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 6 AS order_in_day, 'resistance-band-face-pull' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 3 (Legs A (고강도))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 3
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-full-squat' AS slug, 1 AS is_compound, 4 AS `sets`, 4 AS min_reps, 6 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-romanian-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'leg-press-machine-normal-stance' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'lying-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'hanging-knee-raises' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 1 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 4 (Push B (볼륨))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 4
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'dumbbell-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'dumbbell-standing-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'dumbbell-fly-flat-bench' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 15 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'pec-deck-fly-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'barbell-close-grip-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 5 (Pull B (볼륨))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 5
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'cable-seated-row' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'cable-pulldown' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'straight-bar-cable-row-normal-grip' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 90 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'dumbbell-bent-over-face-pull' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'dumbbell-shrugs' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPL day 6 (Legs B (볼륨))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPL' AND pd.day_number = 6
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'dumbbell-goblet-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 15 AS max_reps, 90 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-hip-thrust' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'barbell-bulgarian-split-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, '좌우 각 8~10회' AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'seated-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'dumbbell-lunge-to-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 90 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, '좌우 각 8~10회, 전신 컨디셔닝 마무리' AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPLUL day 1 (Push (Block))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPLUL' AND pd.day_number = 1
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-bench-press' AS slug, 1 AS is_compound, 4 AS `sets`, 8 AS min_reps, 10 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-seated-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'dumbbell-incline-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'lateral-raises-dumbbell' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'barbell-lying-triceps-skull-crusher' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPLUL day 2 (Pull (Block))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPLUL' AND pd.day_number = 2
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'bent-over-barbell-row' AS slug, 1 AS is_compound, 4 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'pull-up-normal-grip' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'cable-pulldown' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'ez-barbell-curl' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPLUL day 3 (Legs (Block))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPLUL' AND pd.day_number = 3
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-full-squat' AS slug, 1 AS is_compound, 4 AS `sets`, 8 AS min_reps, 10 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-romanian-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'leg-press-machine-normal-stance' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'lying-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'barbell-hip-thrust' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPLUL day 4 (Upper (Block))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPLUL' AND pd.day_number = 4
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-incline-bench-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'cable-seated-row' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 150 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'dumbbell-standing-overhead-press' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'dumbbell-incline-row' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 90 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'lateral-raises-dumbbell' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 60 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 6 AS order_in_day, 'barbell-lying-triceps-skull-crusher' AS slug, 0 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

-- PPLUL day 5 (Lower (Block))
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, `sets`, min_reps, max_reps,
     rest_seconds, target_rpe, set_type, is_optional, notes)
SELECT
    d.id,
    (SELECT id FROM exercises WHERE slug = x.slug),
    x.order_in_day, x.is_compound, x.`sets`, x.min_reps, x.max_reps,
    x.rest_seconds, x.target_rpe, x.set_type, x.is_optional, x.notes
FROM (
    SELECT pd.id AS id FROM program_days pd
    JOIN canonical_programs cp ON cp.id = pd.program_id
    WHERE cp.code = 'PPLUL' AND pd.day_number = 5
) d
CROSS JOIN (
    SELECT 1 AS order_in_day, 'barbell-sumo-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 6 AS min_reps, 8 AS max_reps, 210 AS rest_seconds, 8.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 2 AS order_in_day, 'barbell-front-squats' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 180 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 3 AS order_in_day, 'dumbbell-romanian-deadlift' AS slug, 1 AS is_compound, 3 AS `sets`, 10 AS min_reps, 12 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
    UNION ALL
    SELECT 4 AS order_in_day, 'barbell-bulgarian-split-squat' AS slug, 1 AS is_compound, 3 AS `sets`, 8 AS min_reps, 10 AS max_reps, 120 AS rest_seconds, 7.5 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, '좌우 각 8~10회' AS notes
    UNION ALL
    SELECT 5 AS order_in_day, 'seated-leg-curl-machine' AS slug, 0 AS is_compound, 3 AS `sets`, 12 AS min_reps, 15 AS max_reps, 75 AS rest_seconds, 7.0 AS target_rpe, 'WORKING' AS set_type, 0 AS is_optional, NULL AS notes
) x
WHERE NOT EXISTS (
    SELECT 1 FROM program_day_exercises pde
    WHERE pde.program_day_id = d.id
      AND pde.exercise_id = (SELECT id FROM exercises WHERE slug = x.slug)
);

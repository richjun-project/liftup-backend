-- Flutter 프론트엔드와 일치하는 16개 근육 그룹으로 업데이트
-- 기존의 복잡한 매핑을 단순화

DELETE FROM exercise_muscle_groups;

-- CHEST 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.category = 'CHEST';

-- BACK 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.category = 'BACK';

-- 광배근 중점 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LATS' FROM exercises e
WHERE e.name LIKE '%풀다운%' OR e.name LIKE '%풀업%' OR e.name LIKE '%로우%';

-- 승모근 중점 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e
WHERE e.name LIKE '%슈러그%' OR e.name LIKE '%업라이트%';

-- SHOULDERS 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.category = 'SHOULDERS';

-- ARMS - 이두근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e
WHERE e.category = 'ARMS' AND (e.name LIKE '%컬%' OR e.name LIKE '%이두%');

-- ARMS - 삼두근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e
WHERE e.category = 'ARMS' AND (e.name LIKE '%익스텐션%' OR e.name LIKE '%삼두%' OR e.name LIKE '%프레스 다운%');

-- ARMS - 전완근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e
WHERE e.category = 'ARMS' AND e.name LIKE '%전완%';

-- LEGS 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.category = 'LEGS';

-- 대퇴사두근 중점
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e
WHERE e.name LIKE '%스쿼트%' OR e.name LIKE '%레그 익스텐션%' OR e.name LIKE '%런지%';

-- 햄스트링 중점
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e
WHERE e.name LIKE '%레그 컬%' OR e.name LIKE '%데드리프트%' OR e.name LIKE '%굿모닝%';

-- 둔근 중점
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e
WHERE e.name LIKE '%힙 스러스트%' OR e.name LIKE '%브릿지%' OR e.name LIKE '%힙%';

-- 종아리
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CALVES' FROM exercises e
WHERE e.name LIKE '%카프%' OR e.name LIKE '%종아리%';

-- CORE & ABS 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.category = 'CORE';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e
WHERE e.category = 'CORE' AND (e.name LIKE '%크런치%' OR e.name LIKE '%싯업%' OR e.name LIKE '%레그레이즈%');

-- 목 운동 (있다면)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'NECK' FROM exercises e
WHERE e.name LIKE '%넥%' OR e.name LIKE '%목%';

-- 복합 운동들에 대한 추가 매핑
-- 벤치프레스는 가슴과 삼두근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e
WHERE e.name LIKE '%벤치프레스%' AND NOT EXISTS (
    SELECT 1 FROM exercise_muscle_groups emg
    WHERE emg.exercise_id = e.id AND emg.muscle_groups = 'TRICEPS'
);

-- 풀업/친업은 등과 이두근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e
WHERE (e.name LIKE '%풀업%' OR e.name LIKE '%친업%') AND NOT EXISTS (
    SELECT 1 FROM exercise_muscle_groups emg
    WHERE emg.exercise_id = e.id AND emg.muscle_groups = 'BICEPS'
);

-- 스쿼트는 다리, 대퇴사두근, 둔근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e
WHERE e.name LIKE '%스쿼트%' AND NOT EXISTS (
    SELECT 1 FROM exercise_muscle_groups emg
    WHERE emg.exercise_id = e.id AND emg.muscle_groups = 'GLUTES'
);

-- 데드리프트는 등, 햄스트링, 둔근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e
WHERE e.name LIKE '%데드리프트%' AND NOT EXISTS (
    SELECT 1 FROM exercise_muscle_groups emg
    WHERE emg.exercise_id = e.id AND emg.muscle_groups = 'BACK'
);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e
WHERE e.name LIKE '%데드리프트%' AND NOT EXISTS (
    SELECT 1 FROM exercise_muscle_groups emg
    WHERE emg.exercise_id = e.id AND emg.muscle_groups = 'GLUTES'
);
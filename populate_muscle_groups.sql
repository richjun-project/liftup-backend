-- Clear existing data
DELETE FROM exercise_muscle_groups;

-- Populate muscle groups for exercises based on category and name
-- Note: Using muscle_groups column (with 's') as Hibernate expects

-- CHEST exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'CHEST' FROM exercises WHERE category = 'CHEST';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'TRICEPS' FROM exercises WHERE name LIKE '%프레스%' AND category = 'CHEST';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'SHOULDERS' FROM exercises WHERE name LIKE '%프레스%' AND category = 'CHEST';

-- BACK exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'BACK' FROM exercises WHERE category = 'BACK';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'BICEPS' FROM exercises WHERE name LIKE '%로우%' OR name LIKE '%풀%';

-- LEGS exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'QUADRICEPS' FROM exercises WHERE name LIKE '%스쿼트%' OR name LIKE '%레그 프레스%' OR name LIKE '%런지%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'HAMSTRINGS' FROM exercises WHERE name LIKE '%데드리프트%' OR name LIKE '%레그 컬%' OR name LIKE '%루마니안%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'GLUTES' FROM exercises WHERE name LIKE '%스쿼트%' OR name LIKE '%데드리프트%' OR name LIKE '%힙%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'CALVES' FROM exercises WHERE name LIKE '%카프%' OR name LIKE '%레이즈%' AND category = 'LEGS';

-- SHOULDERS exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'SHOULDERS' FROM exercises WHERE category = 'SHOULDERS';

-- ARMS exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'BICEPS' FROM exercises WHERE name LIKE '%컬%' AND category = 'ARMS';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'TRICEPS' FROM exercises WHERE (name LIKE '%익스텐션%' OR name LIKE '%푸시다운%' OR name LIKE '%딥%') AND category = 'ARMS';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'FOREARMS' FROM exercises WHERE name LIKE '%리스트%' OR name LIKE '%그립%';

-- CORE exercises
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'ABS' FROM exercises WHERE category = 'CORE';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'OBLIQUES' FROM exercises WHERE name LIKE '%사이드%' OR name LIKE '%우드챱%' OR name LIKE '%러시안%';

-- Check results
SELECT
    e.id,
    e.name,
    e.category,
    GROUP_CONCAT(emg.muscle_groups) as muscle_groups
FROM exercises e
LEFT JOIN exercise_muscle_groups emg ON e.id = emg.exercise_id
GROUP BY e.id, e.name, e.category
LIMIT 30;
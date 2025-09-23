-- Check exercise_muscle_groups structure and data
SHOW CREATE TABLE exercise_muscle_groups;

-- Check sample data
SELECT * FROM exercise_muscle_groups LIMIT 10;

-- Count by muscle group
SELECT muscle_groups, COUNT(*) as count
FROM exercise_muscle_groups
GROUP BY muscle_groups;

-- Check if foreign keys match
SELECT
    e.id,
    e.name,
    e.category,
    GROUP_CONCAT(emg.muscle_groups) as muscle_groups
FROM exercises e
LEFT JOIN exercise_muscle_groups emg ON e.id = emg.exercise_id
WHERE e.id IN (1, 2, 3, 4, 5)
GROUP BY e.id, e.name, e.category;

-- Check column name (muscle_groups vs muscle_group)
SHOW COLUMNS FROM exercise_muscle_groups;
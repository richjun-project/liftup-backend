-- Check if exercise_muscle_groups table exists and has data
SHOW TABLES LIKE 'exercise_muscle_groups';

-- Check structure
DESC exercise_muscle_groups;

-- Count data
SELECT COUNT(*) as total FROM exercise_muscle_groups;

-- Sample data
SELECT * FROM exercise_muscle_groups LIMIT 10;

-- Check which exercises have muscle groups
SELECT
    e.id,
    e.name,
    COUNT(emg.muscle_groups) as muscle_group_count
FROM exercises e
LEFT JOIN exercise_muscle_groups emg ON e.id = emg.exercise_id
GROUP BY e.id, e.name
LIMIT 20;
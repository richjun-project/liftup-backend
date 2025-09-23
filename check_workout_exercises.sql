-- 세션 56번의 WorkoutExercise 확인
SELECT we.*, e.name as exercise_name
FROM workout_exercises we
JOIN exercises e ON we.exercise_id = e.id
WHERE we.session_id = 56;

-- 세션 56번 정보 확인
SELECT * FROM workout_sessions WHERE id = 56;

-- 최근 WorkoutExercise 데이터 확인
SELECT we.*, ws.id as session_id, e.name as exercise_name
FROM workout_exercises we
JOIN workout_sessions ws ON we.session_id = ws.id
JOIN exercises e ON we.exercise_id = e.id
ORDER BY we.id DESC
LIMIT 10;
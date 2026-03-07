ALTER TABLE achievements
    ADD COLUMN code VARCHAR(100) NULL AFTER user_id;

CREATE INDEX idx_achievements_user_code ON achievements(user_id, code);

UPDATE achievements
SET code = CASE
    WHEN name IN ('첫 운동 완료', 'First Workout Complete') THEN 'first_workout'
    WHEN name IN ('운동 10회 달성', '10 Workouts Completed') THEN 'workout_10'
    WHEN name IN ('운동 50회 달성', '50 Workouts Completed') THEN 'workout_50'
    WHEN name IN ('운동 100회 달성', '100 Workouts Completed') THEN 'workout_100'
    WHEN name IN ('운동 200회 달성', '200 Workouts Completed') THEN 'workout_200'
    WHEN name IN ('일주일 연속 운동', '7-Day Workout Streak') THEN 'week_streak_7'
    WHEN name IN ('2주 연속 운동', '14-Day Workout Streak') THEN 'week_streak_14'
    WHEN name IN ('한 달 연속 운동', '30-Day Workout Streak') THEN 'month_streak_30'
    WHEN name IN ('두 달 연속 운동', '60-Day Workout Streak') THEN 'month_streak_60'
    WHEN name IN ('10톤 마스터', '10 Ton Master') THEN 'volume_10000'
    WHEN name IN ('20톤 전사', '20 Ton Warrior') THEN 'volume_20000'
    WHEN name IN ('한 시간 집중', '60-Minute Focus') THEN 'duration_60'
    WHEN name IN ('90분 전사', '90-Minute Warrior') THEN 'duration_90'
    ELSE code
END
WHERE code IS NULL OR code = '';

UPDATE muscle_recovery
SET muscle_group = CASE
    WHEN LOWER(TRIM(muscle_group)) IN ('가슴', 'chest') THEN 'chest'
    WHEN LOWER(TRIM(muscle_group)) IN ('등', 'back') THEN 'back'
    WHEN LOWER(TRIM(muscle_group)) IN ('어깨', 'shoulders', 'shoulder') THEN 'shoulders'
    WHEN LOWER(TRIM(muscle_group)) IN ('이두근', '이두', 'biceps', 'bicep') THEN 'biceps'
    WHEN LOWER(TRIM(muscle_group)) IN ('삼두근', '삼두', 'triceps', 'tricep') THEN 'triceps'
    WHEN LOWER(TRIM(muscle_group)) IN ('다리', '하체', 'legs', 'leg', 'lower', 'lower_body') THEN 'legs'
    WHEN LOWER(TRIM(muscle_group)) IN ('코어', 'core') THEN 'core'
    WHEN LOWER(TRIM(muscle_group)) IN ('복근', 'abs', 'abdominals') THEN 'abs'
    WHEN LOWER(TRIM(muscle_group)) IN ('둔근', '엉덩이', 'glutes', 'glute') THEN 'glutes'
    WHEN LOWER(TRIM(muscle_group)) IN ('종아리', 'calves', 'calf') THEN 'calves'
    WHEN LOWER(TRIM(muscle_group)) IN ('전완근', '전완', 'forearms', 'forearm') THEN 'forearms'
    WHEN LOWER(TRIM(muscle_group)) IN ('목', 'neck') THEN 'neck'
    WHEN LOWER(TRIM(muscle_group)) IN ('대퇴사두근', '대퇴사두', 'quadriceps', 'quads') THEN 'quadriceps'
    WHEN LOWER(TRIM(muscle_group)) IN ('햄스트링', 'hamstrings', 'hamstring') THEN 'hamstrings'
    WHEN LOWER(TRIM(muscle_group)) IN ('광배근', 'lats', 'lat') THEN 'lats'
    WHEN LOWER(TRIM(muscle_group)) IN ('승모근', 'traps', 'trap') THEN 'traps'
    WHEN LOWER(TRIM(muscle_group)) IN ('팔', 'arms', 'arm') THEN 'arms'
    WHEN LOWER(TRIM(muscle_group)) IN ('유산소', 'cardio') THEN 'cardio'
    WHEN LOWER(TRIM(muscle_group)) IN ('전신', 'full body', 'full_body', 'fullbody') THEN 'full_body'
    ELSE LOWER(REPLACE(REPLACE(TRIM(muscle_group), ' ', '_'), '-', '_'))
END;

UPDATE recovery_activity_body_parts
SET body_part = CASE
    WHEN LOWER(TRIM(body_part)) IN ('가슴', 'chest') THEN 'chest'
    WHEN LOWER(TRIM(body_part)) IN ('등', 'back') THEN 'back'
    WHEN LOWER(TRIM(body_part)) IN ('어깨', 'shoulders', 'shoulder') THEN 'shoulders'
    WHEN LOWER(TRIM(body_part)) IN ('이두근', '이두', 'biceps', 'bicep') THEN 'biceps'
    WHEN LOWER(TRIM(body_part)) IN ('삼두근', '삼두', 'triceps', 'tricep') THEN 'triceps'
    WHEN LOWER(TRIM(body_part)) IN ('다리', '하체', 'legs', 'leg', 'lower', 'lower_body') THEN 'legs'
    WHEN LOWER(TRIM(body_part)) IN ('코어', 'core') THEN 'core'
    WHEN LOWER(TRIM(body_part)) IN ('복근', 'abs', 'abdominals') THEN 'abs'
    WHEN LOWER(TRIM(body_part)) IN ('둔근', '엉덩이', 'glutes', 'glute') THEN 'glutes'
    WHEN LOWER(TRIM(body_part)) IN ('종아리', 'calves', 'calf') THEN 'calves'
    WHEN LOWER(TRIM(body_part)) IN ('전완근', '전완', 'forearms', 'forearm') THEN 'forearms'
    WHEN LOWER(TRIM(body_part)) IN ('목', 'neck') THEN 'neck'
    WHEN LOWER(TRIM(body_part)) IN ('대퇴사두근', '대퇴사두', 'quadriceps', 'quads') THEN 'quadriceps'
    WHEN LOWER(TRIM(body_part)) IN ('햄스트링', 'hamstrings', 'hamstring') THEN 'hamstrings'
    WHEN LOWER(TRIM(body_part)) IN ('광배근', 'lats', 'lat') THEN 'lats'
    WHEN LOWER(TRIM(body_part)) IN ('승모근', 'traps', 'trap') THEN 'traps'
    WHEN LOWER(TRIM(body_part)) IN ('팔', 'arms', 'arm') THEN 'arms'
    WHEN LOWER(TRIM(body_part)) IN ('유산소', 'cardio') THEN 'cardio'
    WHEN LOWER(TRIM(body_part)) IN ('전신', 'full body', 'full_body', 'fullbody') THEN 'full_body'
    ELSE LOWER(REPLACE(REPLACE(TRIM(body_part), ' ', '_'), '-', '_'))
END;

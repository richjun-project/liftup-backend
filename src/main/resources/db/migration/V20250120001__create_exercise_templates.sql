-- 운동 템플릿 테이블 생성
CREATE TABLE exercise_templates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    exercise_id BIGINT NOT NULL,
    experience_level VARCHAR(50) NOT NULL,
    workout_goal VARCHAR(50) NOT NULL,
    sets INT NOT NULL,
    min_reps INT NOT NULL,
    max_reps INT NOT NULL,
    weight_percentage DOUBLE,
    rest_seconds INT NOT NULL,
    set_type VARCHAR(50) NOT NULL DEFAULT 'WORKING',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (exercise_id) REFERENCES exercises(id),
    INDEX idx_exercise_level_goal (exercise_id, experience_level, workout_goal)
);

-- 기본 템플릿 데이터 삽입

-- 벤치프레스 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type, notes)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 8, 12, 0.5, 90, 'WORKING', '체중의 50%로 시작'
FROM exercises e WHERE e.name = 'Bench Press';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 10, 0.75, 120, 'WORKING'
FROM exercises e WHERE e.name = 'Bench Press';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'ADVANCED', 'STRENGTH', 5, 3, 5, 0.85, 180, 'WORKING'
FROM exercises e WHERE e.name = 'Bench Press';

-- 스쿼트 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 10, 15, 0.6, 120, 'WORKING'
FROM exercises e WHERE e.name = 'Squat';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 12, 0.8, 150, 'WORKING'
FROM exercises e WHERE e.name = 'Squat';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'ADVANCED', 'STRENGTH', 5, 5, 5, 0.9, 180, 'WORKING'
FROM exercises e WHERE e.name = 'Squat';

-- 데드리프트 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'STRENGTH', 3, 5, 8, 0.7, 150, 'WORKING'
FROM exercises e WHERE e.name = 'Deadlift';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'STRENGTH', 4, 5, 6, 0.85, 180, 'WORKING'
FROM exercises e WHERE e.name = 'Deadlift';

-- 풀업 템플릿 (체중 운동)
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 5, 8, NULL, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Pull Up';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 12, NULL, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Pull Up';

-- 레그프레스 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 12, 15, 1.0, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Leg Press';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 10, 12, 1.5, 120, 'WORKING'
FROM exercises e WHERE e.name = 'Leg Press';

-- 숄더프레스 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 10, 12, 0.3, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Shoulder Press';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 10, 0.4, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Shoulder Press';

-- 바벨로우 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 10, 12, 0.5, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Barbell Row';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 10, 0.6, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Barbell Row';

-- 인클라인 덤벨프레스 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 10, 12, 0.25, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Incline Dumbbell Press';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 3, 8, 10, 0.35, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Incline Dumbbell Press';

-- 런지 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 10, 12, 0.2, 60, 'WORKING'
FROM exercises e WHERE e.name = 'Lunge';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 3, 10, 12, 0.3, 90, 'WORKING'
FROM exercises e WHERE e.name = 'Lunge';

-- 사이드 레터럴 레이즈 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'BEGINNER', 'MUSCLE_GAIN', 3, 12, 15, 0.05, 60, 'WORKING'
FROM exercises e WHERE e.name = 'Lateral Raise';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'MUSCLE_GAIN', 3, 10, 12, 0.08, 60, 'WORKING'
FROM exercises e WHERE e.name = 'Lateral Raise';

-- 플랭크 템플릿 (시간 기반)
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type, notes)
SELECT e.id, 'BEGINNER', 'GENERAL_FITNESS', 3, 20, 30, NULL, 60, 'WORKING', '초 단위'
FROM exercises e WHERE e.name = 'Plank';

INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type, notes)
SELECT e.id, 'INTERMEDIATE', 'GENERAL_FITNESS', 3, 30, 60, NULL, 60, 'WORKING', '초 단위'
FROM exercises e WHERE e.name = 'Plank';

-- 지방 감소 목표 템플릿 (높은 반복수, 짧은 휴식)
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'FAT_LOSS', 4, 15, 20, 0.4, 45, 'WORKING'
FROM exercises e WHERE e.name IN ('Bench Press', 'Squat', 'Deadlift');

-- 지구력 목표 템플릿
INSERT INTO exercise_templates (exercise_id, experience_level, workout_goal, sets, min_reps, max_reps, weight_percentage, rest_seconds, set_type)
SELECT e.id, 'INTERMEDIATE', 'ENDURANCE', 3, 15, 25, 0.3, 30, 'WORKING'
FROM exercises e WHERE e.name IN ('Push Up', 'Pull Up', 'Squat');
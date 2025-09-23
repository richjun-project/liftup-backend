-- 운동 데이터 초기화
INSERT INTO exercises (name, category, equipment, instructions)
VALUES
-- 가슴 운동
('벤치프레스', 'CHEST', 'BARBELL', '바벨을 가슴 위로 들어올립니다'),
('덤벨 플라이', 'CHEST', 'DUMBBELL', '덤벨을 양옆으로 벌렸다가 모읍니다'),
('인클라인 벤치프레스', 'CHEST', 'BARBELL', '경사진 벤치에서 바벨을 들어올립니다'),
('푸시업', 'CHEST', 'BODYWEIGHT', '팔굽혀펴기를 수행합니다'),
('케이블 크로스오버', 'CHEST', 'CABLE', '케이블을 교차하여 가슴을 수축합니다'),

-- 등 운동
('풀업', 'BACK', 'BODYWEIGHT', '턱걸이를 수행합니다'),
('바벨 로우', 'BACK', 'BARBELL', '바벨을 배꼽쪽으로 당깁니다'),
('랫 풀다운', 'BACK', 'MACHINE', '케이블을 아래로 당깁니다'),
('덤벨 로우', 'BACK', 'DUMBBELL', '한쪽씩 덤벨을 당깁니다'),
('데드리프트', 'BACK', 'BARBELL', '바닥에서 바벨을 들어올립니다'),

-- 다리 운동
('스쿼트', 'LEGS', 'BARBELL', '바벨을 어깨에 얹고 앉았다 일어납니다'),
('레그프레스', 'LEGS', 'MACHINE', '다리로 무게를 밀어냅니다'),
('런지', 'LEGS', 'DUMBBELL', '한 발씩 앞으로 나가며 앉습니다'),
('레그컬', 'LEGS', 'MACHINE', '누워서 다리를 구부립니다'),
('카프레이즈', 'LEGS', 'BARBELL', '발꿈치를 들어올립니다'),

-- 어깨 운동
('숄더프레스', 'SHOULDERS', 'DUMBBELL', '덤벨을 머리 위로 들어올립니다'),
('사이드 레터럴 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 옆으로 들어올립니다'),
('프론트 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 앞으로 들어올립니다'),
('리어 델트 플라이', 'SHOULDERS', 'DUMBBELL', '뒤쪽 어깨를 자극합니다'),
('업라이트 로우', 'SHOULDERS', 'BARBELL', '바벨을 턱까지 끌어올립니다'),

-- 팔 운동
('바벨 컬', 'ARMS', 'BARBELL', '바벨을 구부려 올립니다'),
('해머 컬', 'ARMS', 'DUMBBELL', '덤벨을 해머 그립으로 들어올립니다'),
('트라이셉스 익스텐션', 'ARMS', 'DUMBBELL', '삼두근을 늘렸다 수축합니다'),
('케이블 푸시다운', 'ARMS', 'CABLE', '케이블을 아래로 밀어내립니다'),
('클로즈그립 벤치프레스', 'ARMS', 'BARBELL', '좁은 그립으로 벤치프레스를 합니다'),

-- 복근 운동
('크런치', 'CORE', 'BODYWEIGHT', '상체를 구부려 복근을 수축합니다'),
('플랭크', 'CORE', 'BODYWEIGHT', '팔꿈치와 발끝으로 버팁니다'),
('레그레이즈', 'CORE', 'BODYWEIGHT', '다리를 들어올려 복근을 자극합니다'),
('러시안 트위스트', 'CORE', 'BODYWEIGHT', '상체를 좌우로 비틉니다'),
('AB 휠', 'CORE', 'OTHER', 'AB휠을 굴려 복근을 자극합니다')
ON DUPLICATE KEY UPDATE
    category = VALUES(category),
    equipment = VALUES(equipment),
    instructions = VALUES(instructions);

-- 운동별 근육군 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name IN ('벤치프레스', '덤벨 플라이', '인클라인 벤치프레스', '푸시업', '케이블 크로스오버')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name IN ('벤치프레스', '푸시업', '클로즈그립 벤치프레스', '트라이셉스 익스텐션', '케이블 푸시다운')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name IN ('풀업', '바벨 로우', '랫 풀다운', '덤벨 로우', '데드리프트')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name IN ('풀업', '바벨 로우', '바벨 컬', '해머 컬')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name IN ('스쿼트', '레그프레스', '런지')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name IN ('스쿼트', '데드리프트', '레그컬')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name IN ('스쿼트', '데드리프트', '런지', '레그프레스')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CALVES' FROM exercises e WHERE e.name = '카프레이즈'
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name IN ('숄더프레스', '사이드 레터럴 레이즈', '프론트 레이즈', '리어 델트 플라이', '업라이트 로우')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name IN ('크런치', '플랭크', '레그레이즈', '러시안 트위스트', 'AB 휠')
ON DUPLICATE KEY UPDATE muscle_groups = VALUES(muscle_groups);
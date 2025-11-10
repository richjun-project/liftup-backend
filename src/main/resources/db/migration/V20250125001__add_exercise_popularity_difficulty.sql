-- 운동 인기도 및 난이도 컬럼 추가

-- Add popularity column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'exercises' AND COLUMN_NAME = 'popularity');
SET @sqlstmt := IF(@exist = 0,
    'ALTER TABLE exercises ADD COLUMN popularity INT NOT NULL DEFAULT 50',
    'SELECT ''popularity already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add difficulty column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'exercises' AND COLUMN_NAME = 'difficulty');
SET @sqlstmt := IF(@exist = 0,
    'ALTER TABLE exercises ADD COLUMN difficulty INT NOT NULL DEFAULT 50',
    'SELECT ''difficulty already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add is_basic_exercise column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'exercises' AND COLUMN_NAME = 'is_basic_exercise');
SET @sqlstmt := IF(@exist = 0,
    'ALTER TABLE exercises ADD COLUMN is_basic_exercise BOOLEAN NOT NULL DEFAULT false',
    'SELECT ''is_basic_exercise already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 기본 운동 (Big 3 + 핵심 운동) 설정
-- Big 3: 매우 높은 인기도, 중간 난이도
UPDATE exercises SET popularity = 95, difficulty = 50, is_basic_exercise = true
WHERE name IN ('벤치프레스', '스쿼트', '데드리프트');

-- 기초 상체 운동: 높은 인기도, 낮은~중간 난이도
UPDATE exercises SET popularity = 85, difficulty = 35, is_basic_exercise = true
WHERE name IN ('덤벨 플라이', '푸시업', '풀업', '바벨 로우', '랫 풀다운', '덤벨 로우');

-- 기초 하체 운동: 높은 인기도, 낮은~중간 난이도
UPDATE exercises SET popularity = 85, difficulty = 30, is_basic_exercise = true
WHERE name IN ('레그프레스', '런지', '레그컬', '카프레이즈');

-- 기초 어깨 운동: 높은 인기도, 낮은~중간 난이도
UPDATE exercises SET popularity = 80, difficulty = 35, is_basic_exercise = true
WHERE name IN ('숄더프레스', '사이드 레터럴 레이즈', '프론트 레이즈');

-- 기초 팔 운동: 높은 인기도, 낮은 난이도
UPDATE exercises SET popularity = 75, difficulty = 25, is_basic_exercise = true
WHERE name IN ('바벨 컬', '해머 컬', '트라이셉스 익스텐션', '케이블 푸시다운');

-- 기초 코어 운동: 높은 인기도, 낮은 난이도
UPDATE exercises SET popularity = 80, difficulty = 20, is_basic_exercise = true
WHERE name IN ('크런치', '플랭크', '레그레이즈', '러시안 트위스트');

-- 중급 운동: 중간 인기도, 중간 난이도
UPDATE exercises SET popularity = 60, difficulty = 55
WHERE name LIKE '%인클라인%' OR name LIKE '%디클라인%' OR name LIKE '%케이블%';

-- 고급 변형 운동: 낮은~중간 인기도, 높은 난이도
UPDATE exercises SET popularity = 40, difficulty = 70
WHERE name LIKE '%원암%' OR name LIKE '%싱글%' OR name LIKE '%플라이오%';

-- 머신 운동: 인기도 다양, 낮은 난이도 (초보자 친화적)
UPDATE exercises SET difficulty = 25
WHERE equipment = 'MACHINE';

-- 맨몸 운동: 높은 인기도, 낮은~중간 난이도
UPDATE exercises SET popularity = 85, difficulty = 30
WHERE equipment = 'BODYWEIGHT' AND difficulty = 50;

-- 바벨 운동: 중간~높은 인기도, 중간 난이도
UPDATE exercises SET popularity = 70, difficulty = 50
WHERE equipment = 'BARBELL' AND popularity = 50;

-- 덤벨 운동: 높은 인기도, 낮은~중간 난이도
UPDATE exercises SET popularity = 75, difficulty = 40
WHERE equipment = 'DUMBBELL' AND popularity = 50;

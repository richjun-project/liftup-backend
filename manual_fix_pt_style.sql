-- PT Style 컬럼 크기 수정을 위한 수동 스크립트
-- 데이터베이스에 직접 접속하여 실행하세요

USE liftupai_db;

-- 현재 컬럼 정보 확인
SHOW COLUMNS FROM user_profiles LIKE 'pt_style';

-- 컬럼 크기 변경
ALTER TABLE user_profiles MODIFY COLUMN pt_style VARCHAR(30);

-- 변경 확인
SHOW COLUMNS FROM user_profiles LIKE 'pt_style';

-- 현재 데이터 확인 (문제가 있는 행 찾기)
SELECT id, pt_style, LENGTH(pt_style) as len
FROM user_profiles
WHERE LENGTH(pt_style) > 20;
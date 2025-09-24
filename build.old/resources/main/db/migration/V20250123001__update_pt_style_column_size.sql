-- PT Style 컬럼 크기 확장
-- 새로운 PT 스타일 enum 값들을 수용하기 위해 VARCHAR(20)에서 VARCHAR(30)으로 변경

ALTER TABLE user_profiles
MODIFY COLUMN pt_style VARCHAR(30);
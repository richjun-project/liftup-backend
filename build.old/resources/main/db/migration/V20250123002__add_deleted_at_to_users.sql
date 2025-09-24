-- 회원 탈퇴 기능을 위한 deleted_at 컬럼 추가
-- 소프트 삭제를 위해 탈퇴 시간을 기록

ALTER TABLE users
ADD COLUMN deleted_at TIMESTAMP NULL DEFAULT NULL;

-- 인덱스 추가 (탈퇴 계정 조회 성능 향상)
CREATE INDEX idx_users_deleted_at ON users(deleted_at);
CREATE INDEX idx_users_is_active ON users(is_active);
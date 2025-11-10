-- 회원 탈퇴 기능을 위한 deleted_at 컬럼 추가
-- 소프트 삭제를 위해 탈퇴 시간을 기록

-- Add deleted_at column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'users' AND COLUMN_NAME = 'deleted_at');
SET @sqlstmt := IF(@exist = 0,
    'ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP NULL DEFAULT NULL',
    'SELECT ''deleted_at already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add idx_users_deleted_at index if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_deleted_at');
SET @sqlstmt := IF(@exist = 0,
    'CREATE INDEX idx_users_deleted_at ON users(deleted_at)',
    'SELECT ''idx_users_deleted_at already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add idx_users_is_active index if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_is_active');
SET @sqlstmt := IF(@exist = 0,
    'CREATE INDEX idx_users_is_active ON users(is_active)',
    'SELECT ''idx_users_is_active already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
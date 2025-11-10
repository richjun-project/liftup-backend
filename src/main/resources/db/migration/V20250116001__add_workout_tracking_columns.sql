-- Add workout tracking columns to workout_sessions table
-- MySQL doesn't support ADD COLUMN IF NOT EXISTS in single statement, so we check manually

-- Add workout_type column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND COLUMN_NAME = 'workout_type');
SET @sqlstmt := IF(@exist = 0, 'ALTER TABLE workout_sessions ADD COLUMN workout_type VARCHAR(20)', 'SELECT ''workout_type already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add program_day column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND COLUMN_NAME = 'program_day');
SET @sqlstmt := IF(@exist = 0, 'ALTER TABLE workout_sessions ADD COLUMN program_day INT', 'SELECT ''program_day already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add program_cycle column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND COLUMN_NAME = 'program_cycle');
SET @sqlstmt := IF(@exist = 0, 'ALTER TABLE workout_sessions ADD COLUMN program_cycle INT', 'SELECT ''program_cycle already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add recommendation_type column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND COLUMN_NAME = 'recommendation_type');
SET @sqlstmt := IF(@exist = 0, 'ALTER TABLE workout_sessions ADD COLUMN recommendation_type VARCHAR(50)', 'SELECT ''recommendation_type already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add indexes (check if not exists using prepared statements)
-- MySQL older versions don't support CREATE INDEX IF NOT EXISTS

-- Add idx_workout_sessions_workout_type if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND INDEX_NAME = 'idx_workout_sessions_workout_type');
SET @sqlstmt := IF(@exist = 0,
    'CREATE INDEX idx_workout_sessions_workout_type ON workout_sessions(workout_type)',
    'SELECT ''idx_workout_sessions_workout_type already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add idx_workout_sessions_program_day if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND INDEX_NAME = 'idx_workout_sessions_program_day');
SET @sqlstmt := IF(@exist = 0,
    'CREATE INDEX idx_workout_sessions_program_day ON workout_sessions(program_day)',
    'SELECT ''idx_workout_sessions_program_day already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add idx_workout_sessions_recommendation_type if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'workout_sessions' AND INDEX_NAME = 'idx_workout_sessions_recommendation_type');
SET @sqlstmt := IF(@exist = 0,
    'CREATE INDEX idx_workout_sessions_recommendation_type ON workout_sessions(recommendation_type)',
    'SELECT ''idx_workout_sessions_recommendation_type already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
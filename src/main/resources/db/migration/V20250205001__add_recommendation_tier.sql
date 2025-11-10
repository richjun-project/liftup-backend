-- Add recommendation_tier column to exercises table
-- This field is used for filtering exercises in the recommendation system
-- All data is preserved; only affects which exercises are recommended

-- Add recommendation_tier column if not exists
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'liftupai_db' AND TABLE_NAME = 'exercises' AND COLUMN_NAME = 'recommendation_tier');
SET @sqlstmt := IF(@exist = 0,
    'ALTER TABLE exercises ADD COLUMN recommendation_tier VARCHAR(20) NOT NULL DEFAULT ''STANDARD'' COMMENT ''Exercise recommendation tier: ESSENTIAL, STANDARD, ADVANCED, SPECIALIZED''',
    'SELECT ''recommendation_tier already exists'' AS message');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

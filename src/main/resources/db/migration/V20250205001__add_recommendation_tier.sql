-- Add recommendation_tier column to exercises table
-- This field is used for filtering exercises in the recommendation system
-- All data is preserved; only affects which exercises are recommended

ALTER TABLE exercises
ADD COLUMN IF NOT EXISTS recommendation_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- Add comment explaining the column (MySQL syntax)
-- Note: MySQL doesn't support COMMENT ON COLUMN, use ALTER TABLE ... MODIFY COLUMN instead
ALTER TABLE exercises MODIFY COLUMN recommendation_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD' COMMENT 'Exercise recommendation tier: ESSENTIAL (30-40 core exercises), STANDARD (80-100 common exercises), ADVANCED (50-70 advanced exercises), SPECIALIZED (special/rare exercises, search only)';

-- Add workout tracking columns to workout_sessions table (IF NOT EXISTS)
ALTER TABLE workout_sessions
ADD COLUMN IF NOT EXISTS workout_type VARCHAR(20),
ADD COLUMN IF NOT EXISTS program_day INT,
ADD COLUMN IF NOT EXISTS program_cycle INT,
ADD COLUMN IF NOT EXISTS recommendation_type VARCHAR(50);

-- Add index for better query performance (ignore if exists)
CREATE INDEX IF NOT EXISTS idx_workout_sessions_workout_type ON workout_sessions(workout_type);
CREATE INDEX IF NOT EXISTS idx_workout_sessions_program_day ON workout_sessions(program_day);
CREATE INDEX IF NOT EXISTS idx_workout_sessions_recommendation_type ON workout_sessions(recommendation_type);
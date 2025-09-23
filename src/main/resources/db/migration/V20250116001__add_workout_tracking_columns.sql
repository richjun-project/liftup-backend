-- Add workout tracking columns to workout_sessions table
ALTER TABLE workout_sessions
ADD COLUMN workout_type VARCHAR(20),
ADD COLUMN program_day INT,
ADD COLUMN program_cycle INT,
ADD COLUMN recommendation_type VARCHAR(50);

-- Add index for better query performance
CREATE INDEX idx_workout_sessions_workout_type ON workout_sessions(workout_type);
CREATE INDEX idx_workout_sessions_program_day ON workout_sessions(program_day);
CREATE INDEX idx_workout_sessions_recommendation_type ON workout_sessions(recommendation_type);
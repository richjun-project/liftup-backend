-- Fix status column length in workout_sessions table
ALTER TABLE workout_sessions
MODIFY COLUMN status VARCHAR(20);
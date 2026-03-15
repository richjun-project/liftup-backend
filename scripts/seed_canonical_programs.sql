-- =============================================================================
-- Seed: Canonical Programs, Program Days, Program Day Exercises
-- Exercise Substitutions, Injury Exercise Restrictions
--
-- Manual execution only (not Flyway).
-- Idempotent: all INSERTs use INSERT IGNORE.
-- MySQL-compatible (no CROSS JOIN VALUES).
-- =============================================================================

-- =============================================================================
-- 1. CANONICAL PROGRAMS
-- =============================================================================

INSERT IGNORE INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal,
     days_per_week, program_duration_weeks, deload_every_n_weeks,
     progression_model, next_program_code, version, description, is_active)
VALUES
('FULL_BODY_BEG_GENERAL', 'Beginner Full Body – General Fitness', 'FULL_BODY', 'BEGINNER', 'GENERAL_FITNESS', 3, 8, 4, 'LINEAR', 'FULL_BODY_BEG_MUSCLE', 1, 'Machine and dumbbell-based full-body program for beginners. Balanced stimulus across all major muscle groups. 3 rotating days (A/B/C).', TRUE),
('FULL_BODY_BEG_MUSCLE', 'Beginner Full Body – Muscle Gain', 'FULL_BODY', 'BEGINNER', 'MUSCLE_GAIN', 3, 8, 4, 'LINEAR', 'PPL_INT_MUSCLE', 1, 'Beginner hypertrophy full-body program. Machine and dumbbell focus. 3 rotating days (A/B/C). Progress weight each session.', TRUE),
('FULL_BODY_BEG_FATLOSS', 'Beginner Full Body – Fat Loss', 'FULL_BODY', 'BEGINNER', 'FAT_LOSS', 3, 8, 4, 'LINEAR', 'FULL_BODY_BEG_MUSCLE', 1, 'Beginner fat-loss full-body program. Higher reps and shorter rest periods to maximise caloric expenditure while preserving muscle.', TRUE),
('PPL_INT_MUSCLE', 'Intermediate PPL – Muscle Gain', 'PPL', 'INTERMEDIATE', 'MUSCLE_GAIN', 3, 8, 4, 'UNDULATING', 'PPLUL_ADV_MUSCLE', 1, 'Intermediate Push/Pull/Legs hypertrophy program. Undulating periodisation with Heavy/Medium/Light rotation. 3 days per week.', TRUE),
('PPL_INT_STRENGTH', 'Intermediate PPL – Strength', 'PPL', 'INTERMEDIATE', 'STRENGTH', 3, 8, 4, 'UNDULATING', 'PPL2X_ADV_MUSCLE', 1, 'Intermediate Push/Pull/Legs strength program. Compound-focused with higher intensity (80-90% 1RM). Long rest periods for CNS recovery.', TRUE),
('UL_INT_MUSCLE', 'Intermediate Upper/Lower – Muscle Gain', 'UPPER_LOWER', 'INTERMEDIATE', 'MUSCLE_GAIN', 4, 8, 4, 'UNDULATING', 'PPLUL_ADV_MUSCLE', 1, 'Intermediate Upper/Lower split for hypertrophy. 4 days per week with A/B variation. Balances frequency and volume.', TRUE),
('PPLUL_ADV_MUSCLE', 'Advanced PPL+Upper/Lower – Muscle Gain', 'PPLUL', 'ADVANCED', 'MUSCLE_GAIN', 5, 8, 0, 'BLOCK', 'PPL2X_ADV_MUSCLE', 1, 'Advanced 5-day PPLUL program. Block periodisation across accumulation, intensification, and realisation phases.', TRUE),
('PPL2X_ADV_MUSCLE', 'Advanced 6-Day PPL 2x – Muscle Gain', 'PPL', 'ADVANCED', 'MUSCLE_GAIN', 6, 8, 0, 'BLOCK', NULL, 1, 'Advanced 6-day double PPL for maximal hypertrophy. Each muscle group hit twice per week with Push A/B, Pull A/B, Legs A/B variation.', TRUE);

-- =============================================================================
-- 2. PROGRAM DAYS
-- =============================================================================

-- 2.1 FULL_BODY_BEG_GENERAL
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'), 1, 'FULL_BODY', 'Full Body A', 50);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'), 2, 'FULL_BODY', 'Full Body B', 50);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'), 3, 'FULL_BODY', 'Full Body C', 50);

-- 2.2 FULL_BODY_BEG_MUSCLE
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'), 1, 'FULL_BODY', 'Full Body A', 55);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'), 2, 'FULL_BODY', 'Full Body B', 55);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'), 3, 'FULL_BODY', 'Full Body C', 55);

-- 2.3 FULL_BODY_BEG_FATLOSS
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'), 1, 'FULL_BODY', 'Full Body A', 50);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'), 2, 'FULL_BODY', 'Full Body B', 50);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'), 3, 'FULL_BODY', 'Full Body C', 50);

-- 2.4 PPL_INT_MUSCLE
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'), 1, 'PUSH', 'Push', 65);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'), 2, 'PULL', 'Pull', 65);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'), 3, 'LEGS', 'Legs', 65);

-- 2.5 PPL_INT_STRENGTH
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'), 1, 'PUSH', 'Push', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'), 2, 'PULL', 'Pull', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'), 3, 'LEGS', 'Legs', 70);

-- 2.6 UL_INT_MUSCLE
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'), 1, 'UPPER', 'Upper A', 65);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'), 2, 'LOWER', 'Lower A', 65);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'), 3, 'UPPER', 'Upper B', 65);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'), 4, 'LOWER', 'Lower B', 65);

-- 2.7 PPLUL_ADV_MUSCLE
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'), 1, 'PUSH', 'Push', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'), 2, 'PULL', 'Pull', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'), 3, 'LEGS', 'Legs', 70);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'), 4, 'UPPER', 'Upper', 70);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'), 5, 'LOWER', 'Lower', 65);

-- 2.8 PPL2X_ADV_MUSCLE
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 1, 'PUSH', 'Push A', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 2, 'PULL', 'Pull A', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 3, 'LEGS', 'Legs A', 70);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 4, 'PUSH', 'Push B', 75);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 5, 'PULL', 'Pull B', 70);
INSERT IGNORE INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES ((SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'), 6, 'LEGS', 'Legs B', 75);

-- =============================================================================
-- 3. PROGRAM DAY EXERCISES
-- =============================================================================

-- Helper macro (inline):
--   COALESCE((SELECT id FROM exercises WHERE slug = 'X' LIMIT 1),
--            (SELECT id FROM exercises WHERE category = 'CAT' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))

-- ── 3.1 FULL_BODY_BEG_GENERAL ── Day 1 Full Body A (3x10-15, 75s, RPE 6.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 1;

-- ── 3.1 FULL_BODY_BEG_GENERAL ── Day 2 Full Body B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ABS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 30, 45, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 2;

-- ── 3.1 FULL_BODY_BEG_GENERAL ── Day 3 Full Body C
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 12, 15, 75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND pd.day_number = 3;

-- ── 3.2 FULL_BODY_BEG_MUSCLE ── Day 1 Full Body A (3x8-12, tiered rest, RPE 7.0)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 75, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 12, 75, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 10, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 1;

-- ── 3.2 FULL_BODY_BEG_MUSCLE ── Day 2 Full Body B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 75, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 12, 75, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 10, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, TRUE, 3, 8, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ABS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 7, FALSE, 3, 30, 60, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 2;

-- ── 3.2 FULL_BODY_BEG_MUSCLE ── Day 3 Full Body C
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 75, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 12, 15, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 10, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND pd.day_number = 3;

-- ── 3.3 FULL_BODY_BEG_FATLOSS ── Day 1 (3x12-20, 52s, RPE 6.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 1;

-- ── 3.3 FULL_BODY_BEG_FATLOSS ── Day 2
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ABS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 7, FALSE, 3, 30, 60, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 2;

-- ── 3.3 FULL_BODY_BEG_FATLOSS ── Day 3
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 12, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 15, 20, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND pd.day_number = 3;

-- ── 3.4 PPL_INT_MUSCLE ── Day 1 Push (tiered rest, RPE 7.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 12, 75, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 1;

-- ── 3.4 PPL_INT_MUSCLE ── Day 2 Pull
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 75, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 8, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 2;

-- ── 3.4 PPL_INT_MUSCLE ── Day 3 Legs
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 75, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 15, 20, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND pd.day_number = 3;

-- ── 3.5 PPL_INT_STRENGTH ── Day 1 Push
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 3, 6, 150, 8.5, 80, 90, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 5, 8, 120, 8.0, 70, 80, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 6, 8, 90, 8.0, 65, 75, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 8, 10, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 1;

-- ── 3.5 PPL_INT_STRENGTH ── Day 2 Pull
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 3, 5, 180, 9.0, 82, 92, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 5, 8, 90, 8.0, 70, 80, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 6, 8, 75, 8.0, 65, 75, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 8, 10, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 2;

-- ── 3.5 PPL_INT_STRENGTH ── Day 3 Legs
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 3, 6, 150, 8.5, 80, 90, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 6, 8, 90, 8.0, 70, 80, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 8, 10, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 15, 60, 7.0, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND pd.day_number = 3;

-- ── 3.6 UL_INT_MUSCLE ── Day 1 Upper A (90s, RPE 7.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 7, FALSE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 1;

-- ── 3.6 UL_INT_MUSCLE ── Day 2 Lower A
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 2;

-- ── 3.6 UL_INT_MUSCLE ── Day 3 Upper B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 3;

-- ── 3.6 UL_INT_MUSCLE ── Day 4 Lower B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND pd.day_number = 4;

-- ── 3.7 PPLUL_ADV_MUSCLE ── Day 1 Push (105s, RPE 7.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 120, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 4, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 10, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 4, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 7, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 1;

-- ── 3.7 PPLUL_ADV_MUSCLE ── Day 2 Pull
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 120, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 4, 6, 10, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 8, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 2;

-- ── 3.7 PPLUL_ADV_MUSCLE ── Day 3 Legs
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 120, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 4, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 3;

-- ── 3.7 PPLUL_ADV_MUSCLE ── Day 4 Upper (90s)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-barbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 8, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 12, 15, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 2, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 2, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 4;

-- ── 3.7 PPLUL_ADV_MUSCLE ── Day 5 Lower
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 6, 10, 120, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 15, 20, 60, 7.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND pd.day_number = 5;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 1 Push A (105s, RPE 8.5)
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 6, 10, 120, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 6, 10, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 4, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 1;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 2 Pull A
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 5, 8, 120, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 120, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 8, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 2, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 2;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 3 Legs A
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 6, 10, 120, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 3;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 4, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 3;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 4 Push B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 3, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-barbell-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-fly' LIMIT 1),(SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, TRUE, 3, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 4;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 5 Pull B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 120, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 6, 10, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),(SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, TRUE, 3, 10, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'rear-delt-fly' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 15, 20, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 5;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, FALSE, 3, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 5;

-- ── 3.8 PPL2X_ADV_MUSCLE ── Day 6 Legs B
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'front-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 1, TRUE, 4, 8, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 2, TRUE, 3, 10, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 3, FALSE, 3, 12, 15, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 4, FALSE, 3, 10, 12, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'hip-thrust' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 5, TRUE, 3, 10, 12, 90, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;
INSERT IGNORE INTO program_day_exercises (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps, rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)), 6, FALSE, 3, 15, 20, 60, 8.5, NULL, NULL, 'WORKING', FALSE, NULL FROM program_days pd WHERE pd.program_id = (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND pd.day_number = 6;


-- ════════════════════════════════════════════════════════════════════════════
-- 4. EXERCISE SUBSTITUTIONS
-- ════════════════════════════════════════════════════════════════════════════

INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HORIZONTAL_PUSH', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'bench-press'   AND s.slug = 'dumbbell-bench-press');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'HORIZONTAL_PUSH', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'bench-press'   AND s.slug = 'push-up');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'QUAD_DOMINANT', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-back-squat' AND s.slug = 'leg-press');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'QUAD_DOMINANT', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-back-squat' AND s.slug = 'goblet-squat');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HIP_HINGE', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-deadlift' AND s.slug = 'romanian-deadlift');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'HIP_HINGE', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-deadlift' AND s.slug = 'trap-bar-deadlift');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'VERTICAL_PULL', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'pull-up' AND s.slug = 'lat-pulldown');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'VERTICAL_PULL', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'pull-up' AND s.slug = 'assisted-pull-up');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HORIZONTAL_PULL', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-row' AND s.slug = 'dumbbell-row');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'HORIZONTAL_PULL', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'barbell-row' AND s.slug = 'seated-cable-row');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'VERTICAL_PUSH', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'overhead-press' AND s.slug = 'dumbbell-shoulder-press');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 2, 'EQUIPMENT', 'VERTICAL_PUSH', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'overhead-press' AND s.slug = 'seated-barbell-press');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HIP_HINGE', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'romanian-deadlift' AND s.slug = 'dumbbell-romanian-deadlift');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HORIZONTAL_PUSH', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'incline-barbell-press' AND s.slug = 'incline-dumbbell-press');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'CHEST_FLY', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'cable-fly' AND s.slug = 'dumbbell-fly');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'TRICEP_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'tricep-pushdown' AND s.slug = 'overhead-tricep-extension');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'QUAD_DOMINANT', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'leg-press' AND s.slug = 'hack-squat');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'HAMSTRING_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'leg-curl' AND s.slug = 'nordic-curl');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'CALF_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'calf-raise' AND s.slug = 'seated-calf-raise');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'REAR_DELT_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'face-pull' AND s.slug = 'band-pull-apart');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'BICEP_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'dumbbell-curl' AND s.slug = 'barbell-curl');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'LATERAL_DELT_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'lateral-raise' AND s.slug = 'cable-lateral-raise');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'GLUTE_ISOLATION', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'hip-thrust' AND s.slug = 'glute-bridge');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'QUAD_DOMINANT', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'walking-lunge' AND s.slug = 'reverse-lunge');
INSERT IGNORE INTO exercise_substitutions (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)
SELECT p.id, s.id, 1, 'EQUIPMENT', 'QUAD_DOMINANT', NULL
FROM exercises p JOIN exercises s ON s.id != p.id
WHERE (p.slug = 'front-squat' AND s.slug = 'barbell-back-squat');

-- ════════════════════════════════════════════════════════════════════════════
-- 5. INJURY EXERCISE RESTRICTIONS
-- ════════════════════════════════════════════════════════════════════════════

-- SHOULDER_IMPINGEMENT — SEVERE (avoid entirely)
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    'SEVERE', 'Overhead barbell pressing directly aggravates shoulder impingement';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'seated-barbell-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    'SEVERE', 'Overhead barbell pressing directly aggravates shoulder impingement';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'push-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    'SEVERE', 'Explosive overhead pressing places high stress on impinged shoulder';
-- SHOULDER_IMPINGEMENT — MODERATE (modify technique)
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    'MODERATE', 'Use neutral grip dumbbells and reduce ROM if shoulder pain occurs';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'incline-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),
    'MODERATE', 'Use dumbbells with neutral grip to reduce shoulder impingement stress';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),
    'MODERATE', 'Use neutral grip and avoid full shoulder elevation at top';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'SHOULDER_IMPINGEMENT',
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'cable-lateral-raise' LIMIT 1),
    'MILD', 'Reduce weight and avoid locking out overhead; stop just below lockout';

-- KNEE_PAIN — SEVERE (avoid entirely)
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'Deep knee flexion under axial load places extreme compressive force on the joint';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'front-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'High knee flexion angle aggravates patellofemoral pain';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'hack-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'Knee tracking and deep flexion create compressive knee load';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'SEVERE', 'Lunging places high unilateral compressive load on the knee';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'reverse-lunge' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'SEVERE', 'Lunging places high unilateral compressive load on the knee';
-- KNEE_PAIN — MODERATE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'MODERATE', 'Limit depth to 90 degrees; avoid full knee flexion';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    'MODERATE', 'Use partial ROM; avoid full extension under heavy load';
-- KNEE_PAIN — MILD
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'KNEE_PAIN',
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'MILD', 'Generally safe; reduce load if discomfort arises behind the knee';

-- LOWER_BACK_PAIN — SEVERE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'SEVERE', 'Conventional deadlift places maximal axial load on a compromised lumbar spine';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'Axial spinal loading with lumbar flexion risk during barbell squat';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    'SEVERE', 'Bent-over position under heavy load places high shear force on lumbar spine';
-- LOWER_BACK_PAIN — MODERATE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    'MODERATE', 'Brace core tightly; use lifting belt; reduce load significantly';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    'MODERATE', 'Use supported position; brace core; reduce load';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),
    'MODERATE', 'Avoid excessive lumbar extension; keep neutral spine throughout';
-- LOWER_BACK_PAIN — MILD
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'LOWER_BACK_PAIN',
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    'MILD', 'Keep lower back flat against pad; avoid raising hips off the seat';

-- WRIST_PAIN — SEVERE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    'SEVERE', 'Fixed barbell grip forces wrist into extension under heavy load';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    'SEVERE', 'Wrist extension under overhead barbell load aggravates wrist pain';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'front-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    'SEVERE', 'Front rack position requires extreme wrist extension';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),
    'SEVERE', 'Fixed supinated barbell grip strains wrist under load';
-- WRIST_PAIN — MODERATE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'incline-barbell-press' LIMIT 1),
    'MODERATE', 'Use neutral grip where possible to reduce wrist strain';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),
    'MODERATE', 'Use rope attachment or neutral grip bar to reduce wrist extension';
-- WRIST_PAIN — MILD
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'WRIST_PAIN',
    (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),
    'MILD', 'Use hammer grip to reduce wrist supination stress if discomfort occurs';

-- ELBOW_TENDINITIS — SEVERE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),
    'SEVERE', 'Heavy supinated barbell curl places maximal stress on elbow flexor tendons';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),
    'SEVERE', 'Repetitive elbow extension under load aggravates tricep tendinitis';
-- ELBOW_TENDINITIS — MODERATE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),
    'MODERATE', 'Use neutral grip; reduce load and avoid full extension at bottom';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    'MODERATE', 'Reduce load; avoid full elbow extension under stretch';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),
    'MODERATE', 'Use assisted variation; avoid dead hang position that strains medial elbow';
-- ELBOW_TENDINITIS — MILD
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'ELBOW_TENDINITIS',
    (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    'MILD', 'Use neutral grip attachment; avoid supinated wide grip';

-- HIP_FLEXOR_STRAIN — SEVERE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'Deep squat requires full hip flexion range which aggravates hip flexor strain';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'front-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'SEVERE', 'Front squat demands greater hip flexion depth than back squat';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'SEVERE', 'Lunge positions hip flexor in stretched and loaded position';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'hip-thrust' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'glute-bridge' LIMIT 1),
    'SEVERE', 'Hip thrust requires hip flexors to decelerate and control hip extension range';
-- HIP_FLEXOR_STRAIN — MODERATE
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    'MODERATE', 'Limit depth to 90 degrees; avoid drawing knees fully into chest';
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),
    'MODERATE', 'Avoid excessive forward lean; keep hips from dropping below knee level';
-- HIP_FLEXOR_STRAIN — MILD
INSERT IGNORE INTO injury_exercise_restrictions (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)
SELECT 'HIP_FLEXOR_STRAIN',
    (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    'MILD', 'Warm up hip flexors thoroughly; use conventional stance; monitor for tightness';


-- =============================================================================
-- Seed: Canonical Programs, Program Days, Program Day Exercises
-- Exercise Substitutions, Injury Exercise Restrictions
--
-- Manual execution only (not Flyway).
-- Idempotent: all INSERTs use ON CONFLICT DO NOTHING.
-- Exercise IDs resolved by slug with ESSENTIAL fallback.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- HELPER: reusable slug-lookup with ESSENTIAL fallback by category
-- Usage inline as subquery:
--   (SELECT COALESCE(
--       (SELECT id FROM exercises WHERE slug = 'xxx' LIMIT 1),
--       (SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)
--   ))
-- ---------------------------------------------------------------------------

-- =============================================================================
-- 1. CANONICAL PROGRAMS
-- =============================================================================

INSERT INTO canonical_programs
    (code, name, split_type, target_experience_level, target_goal,
     days_per_week, program_duration_weeks, deload_every_n_weeks,
     progression_model, next_program_code, version, description, is_active)
VALUES
-- 1) FULL_BODY_BEG_GENERAL
(
    'FULL_BODY_BEG_GENERAL',
    'Beginner Full Body – General Fitness',
    'FULL_BODY', 'BEGINNER', 'GENERAL_FITNESS',
    3, 8, 4, 'LINEAR',
    'FULL_BODY_BEG_MUSCLE',
    1,
    'Machine and dumbbell-based full-body program for beginners. Balanced stimulus across all major muscle groups. 3 rotating days (A/B/C).',
    TRUE
),
-- 2) FULL_BODY_BEG_MUSCLE
(
    'FULL_BODY_BEG_MUSCLE',
    'Beginner Full Body – Muscle Gain',
    'FULL_BODY', 'BEGINNER', 'MUSCLE_GAIN',
    3, 8, 4, 'LINEAR',
    'PPL_INT_MUSCLE',
    1,
    'Beginner hypertrophy full-body program. Machine and dumbbell focus. 3 rotating days (A/B/C). Progress weight each session.',
    TRUE
),
-- 3) FULL_BODY_BEG_FATLOSS
(
    'FULL_BODY_BEG_FATLOSS',
    'Beginner Full Body – Fat Loss',
    'FULL_BODY', 'BEGINNER', 'FAT_LOSS',
    3, 8, 4, 'LINEAR',
    'FULL_BODY_BEG_MUSCLE',
    1,
    'Beginner fat-loss full-body program. Higher reps and shorter rest periods to maximise caloric expenditure while preserving muscle.',
    TRUE
),
-- 4) PPL_INT_MUSCLE
(
    'PPL_INT_MUSCLE',
    'Intermediate PPL – Muscle Gain',
    'PPL', 'INTERMEDIATE', 'MUSCLE_GAIN',
    3, 8, 4, 'UNDULATING',
    'PPLUL_ADV_MUSCLE',
    1,
    'Intermediate Push/Pull/Legs hypertrophy program. Undulating periodisation with Heavy/Medium/Light rotation. 3 days per week.',
    TRUE
),
-- 5) PPL_INT_STRENGTH
(
    'PPL_INT_STRENGTH',
    'Intermediate PPL – Strength',
    'PPL', 'INTERMEDIATE', 'STRENGTH',
    3, 8, 4, 'UNDULATING',
    'PPL2X_ADV_MUSCLE',
    1,
    'Intermediate Push/Pull/Legs strength program. Compound-focused with higher intensity (80-90% 1RM). Long rest periods for CNS recovery.',
    TRUE
),
-- 6) UL_INT_MUSCLE
(
    'UL_INT_MUSCLE',
    'Intermediate Upper/Lower – Muscle Gain',
    'UPPER_LOWER', 'INTERMEDIATE', 'MUSCLE_GAIN',
    4, 8, 4, 'UNDULATING',
    'PPLUL_ADV_MUSCLE',
    1,
    'Intermediate Upper/Lower split for hypertrophy. 4 days per week with A/B variation. Balances frequency and volume.',
    TRUE
),
-- 7) PPLUL_ADV_MUSCLE
(
    'PPLUL_ADV_MUSCLE',
    'Advanced PPL+Upper/Lower – Muscle Gain',
    'PPLUL', 'ADVANCED', 'MUSCLE_GAIN',
    5, 8, 4, 'BLOCK',
    'PPL2X_ADV_MUSCLE',
    1,
    'Advanced 5-day PPLUL program. Block periodisation across accumulation, intensification, and realisation phases.',
    TRUE
),
-- 8) PPL2X_ADV_MUSCLE
(
    'PPL2X_ADV_MUSCLE',
    'Advanced 6-Day PPL 2x – Muscle Gain',
    'PPL', 'ADVANCED', 'MUSCLE_GAIN',
    6, 8, 4, 'BLOCK',
    NULL,
    1,
    'Advanced 6-day double PPL for maximal hypertrophy. Each muscle group hit twice per week with Push A/B, Pull A/B, Legs A/B variation.',
    TRUE
)
ON CONFLICT (code) DO NOTHING;


-- =============================================================================
-- 2. PROGRAM DAYS
-- =============================================================================

-- ── 2.1  FULL_BODY_BEG_GENERAL ──────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'),
    1, 'FULL_BODY', 'Full Body A', 50
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'),
    2, 'FULL_BODY', 'Full Body B', 50
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL'),
    3, 'FULL_BODY', 'Full Body C', 50
)
ON CONFLICT DO NOTHING;

-- ── 2.2  FULL_BODY_BEG_MUSCLE ────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'),
    1, 'FULL_BODY', 'Full Body A', 55
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'),
    2, 'FULL_BODY', 'Full Body B', 55
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE'),
    3, 'FULL_BODY', 'Full Body C', 55
)
ON CONFLICT DO NOTHING;

-- ── 2.3  FULL_BODY_BEG_FATLOSS ───────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'),
    1, 'FULL_BODY', 'Full Body A', 50
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'),
    2, 'FULL_BODY', 'Full Body B', 50
),
(
    (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS'),
    3, 'FULL_BODY', 'Full Body C', 50
)
ON CONFLICT DO NOTHING;

-- ── 2.4  PPL_INT_MUSCLE ──────────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'),
    1, 'PUSH', 'Push', 65
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'),
    2, 'PULL', 'Pull', 65
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE'),
    3, 'LEGS', 'Legs', 65
)
ON CONFLICT DO NOTHING;

-- ── 2.5  PPL_INT_STRENGTH ────────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'),
    1, 'PUSH', 'Push', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'),
    2, 'PULL', 'Pull', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH'),
    3, 'LEGS', 'Legs', 70
)
ON CONFLICT DO NOTHING;

-- ── 2.6  UL_INT_MUSCLE ───────────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'),
    1, 'UPPER', 'Upper A', 65
),
(
    (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'),
    2, 'LOWER', 'Lower A', 65
),
(
    (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'),
    3, 'UPPER', 'Upper B', 65
),
(
    (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE'),
    4, 'LOWER', 'Lower B', 65
)
ON CONFLICT DO NOTHING;

-- ── 2.7  PPLUL_ADV_MUSCLE ────────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'),
    1, 'PUSH', 'Push', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'),
    2, 'PULL', 'Pull', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'),
    3, 'LEGS', 'Legs', 70
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'),
    4, 'UPPER', 'Upper', 70
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE'),
    5, 'LOWER', 'Lower', 65
)
ON CONFLICT DO NOTHING;

-- ── 2.8  PPL2X_ADV_MUSCLE ────────────────────────────────────────────────────
INSERT INTO program_days (program_id, day_number, workout_type, name, estimated_duration_minutes)
VALUES
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    1, 'PUSH', 'Push A', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    2, 'PULL', 'Pull A', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    3, 'LEGS', 'Legs A', 70
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    4, 'PUSH', 'Push B', 75
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    5, 'PULL', 'Pull B', 70
),
(
    (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE'),
    6, 'LEGS', 'Legs B', 75
)
ON CONFLICT DO NOTHING;


-- =============================================================================
-- 3. PROGRAM DAY EXERCISES
--
-- Slug lookup pattern:
--   COALESCE(
--       (SELECT id FROM exercises WHERE slug = 'slug-here' LIMIT 1),
--       (SELECT id FROM exercises WHERE category = 'CAT' AND recommendation_tier = 'ESSENTIAL'
--        ORDER BY popularity_score DESC LIMIT 1)
--   )
--
-- Columns: program_day_id, exercise_id, order_in_day, is_compound,
--          sets, min_reps, max_reps, rest_seconds, target_rpe,
--          intensity_percent_low, intensity_percent_high, set_type, is_optional, notes
-- =============================================================================

-- ────────────────────────────────────────────────────────────────────────────
-- 3.1  FULL_BODY_BEG_GENERAL  – shared exercise spec: 3x10-15, 75s rest, RPE 6.5
-- Day 1 = Full Body A, Day 2 = Full Body B, Day 3 = Full Body C
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Full Body A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT
    pd.id,
    ex.eid,
    ex.ord,
    ex.compound,
    3, ex.min_r, ex.max_r,
    75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM
    (SELECT id FROM program_days WHERE program_id =
        (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND day_number = 1
    ) pd
CROSS JOIN (
    VALUES
        (1, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'LEGS'  AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (2, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (3, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK'  AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (4, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (5, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'ARMS'  AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (6, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'ARMS'  AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Full Body B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT
    pd.id,
    ex.eid,
    ex.ord,
    ex.compound,
    3, ex.min_r, ex.max_r,
    75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM
    (SELECT id FROM program_days WHERE program_id =
        (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND day_number = 2
    ) pd
CROSS JOIN (
    VALUES
        (1, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'LEGS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (2, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'CHEST'    AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (3, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (4, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (5, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'LEGS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (6, FALSE,30, 45, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),                  (SELECT id FROM exercises WHERE category = 'ABS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Full Body C
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT
    pd.id,
    ex.eid,
    ex.ord,
    ex.compound,
    3, ex.min_r, ex.max_r,
    75, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM
    (SELECT id FROM program_days WHERE program_id =
        (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_GENERAL') AND day_number = 3
    ) pd
CROSS JOIN (
    VALUES
        (1, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (2, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1), (SELECT id FROM exercises WHERE category = 'CHEST'    AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (3, TRUE, 10, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (4, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (5, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'ARMS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
        (6, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS'   AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.2  FULL_BODY_BEG_MUSCLE – 3x8-12, 82s rest, RPE 7
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Full Body A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 82, 7.0, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Full Body B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 82, 7.0, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1), (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (7, FALSE,30, 60, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),                  (SELECT id FROM exercises WHERE category = 'ABS'       AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Full Body C
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 82, 7.0, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_MUSCLE') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.3  FULL_BODY_BEG_FATLOSS – same exercises as BEG_MUSCLE, 2-3 sets, 12-20 reps, 52s rest, RPE 6.5
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Full Body A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Full Body B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-lunge' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1), (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),(SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (7, FALSE, 30, 60, COALESCE((SELECT id FROM exercises WHERE slug = 'plank' LIMIT 1),                  (SELECT id FROM exercises WHERE category = 'ABS'       AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Full Body C
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       3, ex.min_r, ex.max_r, 52, 6.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'FULL_BODY_BEG_FATLOSS') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'LEGS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  12, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),(SELECT id FROM exercises WHERE category = 'ARMS'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.4  PPL_INT_MUSCLE – 3-4 sets, 8-12 reps, 90s rest, RPE 7.5
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Push
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1), (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Pull
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1), (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Legs
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_MUSCLE') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.5  PPL_INT_STRENGTH
-- Compounds: 120-180s rest, RPE 8.5, intensity 80-90%
-- Isolations: 90s rest, RPE 8
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Push (Strength)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, ex.rest, ex.rpe, ex.ip_lo, ex.ip_hi, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 3, 6, 150, 8.5, 80, 90, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 5, 8,  90, 8.0, 70, 80, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3, 6, 8,  90, 8.0, 65, 75, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1), (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3, 8,10,  90, 7.5, NULL, NULL, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, rest, rpe, ip_lo, ip_hi, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Pull (Strength)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, ex.rest, ex.rpe, ex.ip_lo, ex.ip_hi, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 3, 5, 180, 9.0, 82, 92, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 5, 8,  90, 8.0, 70, 80, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3, 6, 8,  90, 8.0, 65, 75, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3, 8,10,  90, 7.5, NULL, NULL, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, rest, rpe, ip_lo, ip_hi, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Legs (Strength)
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, ex.rest, ex.rpe, ex.ip_lo, ex.ip_hi, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL_INT_STRENGTH') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 3, 6, 150, 8.5, 80, 90, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 6, 8,  90, 8.0, 70, 80, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3, 8,10,  90, 7.5, NULL, NULL, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10,15,  90, 7.0, NULL, NULL, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, rest, rpe, ip_lo, ip_hi, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.6  UL_INT_MUSCLE – 3-4 sets, 8-12 reps, 90s rest, RPE 7.5
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Upper A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (7, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Lower A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Upper B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),                 (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1), (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 4 – Lower B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'UL_INT_MUSCLE') AND day_number = 4) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.7  PPLUL_ADV_MUSCLE – 3-4 sets, 90-120s rest, RPE 7.5
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Push
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),                   (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  3, 8, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 4,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),               (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (7, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Pull
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  4, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Legs
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1), (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 4,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 4 – Upper
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 90, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND day_number = 4) pd
CROSS JOIN (VALUES
    (1, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-barbell-press' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'BACK'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 2,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 2,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 5 – Lower
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 7.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPLUL_ADV_MUSCLE') AND day_number = 5) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- ────────────────────────────────────────────────────────────────────────────
-- 3.8  PPL2X_ADV_MUSCLE – 90-120s rest, RPE 8.5
-- ────────────────────────────────────────────────────────────────────────────

-- Day 1 – Push A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 1) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-dumbbell-press' LIMIT 1),    (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),                 (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  3, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),            (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 4,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 2 – Pull A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 2) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 5, 8,  COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'face-pull' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 2,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 3 – Legs A
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 3) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),(SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1), (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),         (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 4,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 4 – Push B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 4) pd
CROSS JOIN (VALUES
    (1, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),      (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'incline-barbell-press' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-fly' LIMIT 1),              (SELECT id FROM exercises WHERE category = 'CHEST'     AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, TRUE,  3, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),   (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),             (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1), (SELECT id FROM exercises WHERE category = 'ARMS'      AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 5 – Pull B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 5) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3, 6, 10, COALESCE((SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),           (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),  (SELECT id FROM exercises WHERE category = 'BACK' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'rear-delt-fly' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'SHOULDERS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'ARMS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;

-- Day 6 – Legs B
INSERT INTO program_day_exercises
    (program_day_id, exercise_id, order_in_day, is_compound, sets, min_reps, max_reps,
     rest_seconds, target_rpe, intensity_percent_low, intensity_percent_high, set_type, is_optional, notes)
SELECT pd.id, ex.eid, ex.ord, ex.compound,
       ex.sets, ex.min_r, ex.max_r, 105, 8.5, NULL, NULL, 'WORKING', FALSE, NULL
FROM (SELECT id FROM program_days WHERE program_id =
         (SELECT id FROM canonical_programs WHERE code = 'PPL2X_ADV_MUSCLE') AND day_number = 6) pd
CROSS JOIN (VALUES
    (1, TRUE,  4, 8, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'front-squat' LIMIT 1),       (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (2, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (3, FALSE, 3,12, 15, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),     (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (4, FALSE, 3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'leg-curl' LIMIT 1),          (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (5, TRUE,  3,10, 12, COALESCE((SELECT id FROM exercises WHERE slug = 'hip-thrust' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1))),
    (6, FALSE, 3,15, 20, COALESCE((SELECT id FROM exercises WHERE slug = 'calf-raise' LIMIT 1),        (SELECT id FROM exercises WHERE category = 'LEGS' AND recommendation_tier = 'ESSENTIAL' ORDER BY popularity_score DESC LIMIT 1)))
) AS ex(ord, compound, sets, min_r, max_r, eid)
ON CONFLICT DO NOTHING;


-- =============================================================================
-- 4. EXERCISE SUBSTITUTIONS  (~30 rows)
-- reason: EQUIVALENT (same movement pattern) | EQUIPMENT (different equipment)
-- movement_pattern is a free-text label used for grouping
-- =============================================================================

INSERT INTO exercise_substitutions
    (original_exercise_id, substitute_exercise_id, priority, reason, movement_pattern, notes)

-- ── Bench Press family ────────────────────────────────────────────────────
SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    1, 'EQUIPMENT', 'HORIZONTAL_PUSH',
    'Dumbbell variant allows greater range of motion and unilateral correction'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1) IS NOT NULL
ON CONFLICT DO NOTHING

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),
    2, 'EQUIPMENT', 'HORIZONTAL_PUSH',
    'Machine variant for beginners or injury rehab — removes stability requirement'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),
    1, 'EQUIPMENT', 'HORIZONTAL_PUSH',
    'Machine alternative when dumbbells unavailable'
WHERE (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1) IS NOT NULL

-- ── Squat family ──────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),
    1, 'EQUIVALENT', 'SQUAT',
    'Goblet squat for beginners or as a warm-up pattern'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    2, 'EQUIPMENT', 'SQUAT',
    'Leg press when barbell squat is unavailable or knee pain limits depth'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'smith-machine-squat' LIMIT 1),
    3, 'EQUIPMENT', 'SQUAT',
    'Smith machine squat adds stability via guided bar path'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'smith-machine-squat' LIMIT 1) IS NOT NULL

-- ── Deadlift family ───────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1),
    1, 'EQUIVALENT', 'HIP_HINGE',
    'Romanian deadlift emphasises hamstrings/glutes with reduced lower-back compression'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'romanian-deadlift' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'trap-bar-deadlift' LIMIT 1),
    2, 'EQUIPMENT', 'HIP_HINGE',
    'Trap-bar reduces shear force on lumbar spine — good for lower-back issues'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'trap-bar-deadlift' LIMIT 1) IS NOT NULL

-- ── OHP family ────────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1),
    1, 'EQUIPMENT', 'VERTICAL_PUSH',
    'Dumbbell press allows independent arm movement and natural wrist rotation'
WHERE (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dumbbell-shoulder-press' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'machine-shoulder-press' LIMIT 1),
    2, 'EQUIPMENT', 'VERTICAL_PUSH',
    'Machine press for beginners or shoulder rehab'
WHERE (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'machine-shoulder-press' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'landmine-press' LIMIT 1),
    3, 'EQUIVALENT', 'VERTICAL_PUSH',
    'Landmine press is shoulder-friendly due to angled pressing path'
WHERE (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'landmine-press' LIMIT 1) IS NOT NULL

-- ── Row family ────────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1),
    1, 'EQUIPMENT', 'HORIZONTAL_PULL',
    'Dumbbell row allows unilateral focus and greater ROM'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dumbbell-row' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    2, 'EQUIVALENT', 'HORIZONTAL_PULL',
    'Cable row provides constant tension through full ROM'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 't-bar-row' LIMIT 1),
    3, 'EQUIVALENT', 'HORIZONTAL_PULL',
    'T-bar row allows heavier loading with chest support option'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 't-bar-row' LIMIT 1) IS NOT NULL

-- ── Pulldown / Pull-up family ─────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),
    1, 'EQUIVALENT', 'VERTICAL_PULL',
    'Pull-up is the bodyweight equivalent — superior for strength'
WHERE (SELECT id FROM exercises WHERE slug = 'lat-pulldown' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'assisted-pull-up' LIMIT 1),
    1, 'EQUIVALENT', 'VERTICAL_PULL',
    'Assisted pull-up for those who cannot yet do full bodyweight pull-ups'
WHERE (SELECT id FROM exercises WHERE slug = 'pull-up' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'assisted-pull-up' LIMIT 1) IS NOT NULL

-- ── Curl family ───────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),
    1, 'EQUIPMENT', 'ELBOW_FLEXION',
    'Dumbbell curl allows supination and unilateral correction'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),
    2, 'EQUIVALENT', 'ELBOW_FLEXION',
    'Hammer curl targets brachialis and brachioradialis in neutral grip'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'cable-curl' LIMIT 1),
    3, 'EQUIPMENT', 'ELBOW_FLEXION',
    'Cable curl provides constant tension especially at peak contraction'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'cable-curl' LIMIT 1) IS NOT NULL

-- ── Tricep family ─────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),
    1, 'EQUIVALENT', 'ELBOW_EXTENSION',
    'Overhead extension provides long-head stretch under load'
WHERE (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dips' LIMIT 1),
    2, 'EQUIVALENT', 'ELBOW_EXTENSION',
    'Dips are a compound tricep/chest movement with bodyweight or added load'
WHERE (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dips' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'close-grip-bench-press' LIMIT 1),
    3, 'EQUIVALENT', 'ELBOW_EXTENSION',
    'Close-grip bench press allows heavy tricep loading with compound movement'
WHERE (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'close-grip-bench-press' LIMIT 1) IS NOT NULL

-- ── Lateral raise family ──────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'cable-lateral-raise' LIMIT 1),
    1, 'EQUIPMENT', 'SHOULDER_ABDUCTION',
    'Cable version provides constant tension through full arc — especially at bottom'
WHERE (SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'cable-lateral-raise' LIMIT 1) IS NOT NULL

-- ── Fly family ────────────────────────────────────────────────────────────
UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-fly' LIMIT 1),
    1, 'EQUIPMENT', 'CHEST_FLY',
    'Dumbbell fly when cable machine unavailable — less constant tension'
WHERE (SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'dumbbell-fly' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    (SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'pec-deck' LIMIT 1),
    2, 'EQUIVALENT', 'CHEST_FLY',
    'Pec-deck machine fly — fixed arc, beginner-friendly'
WHERE (SELECT id FROM exercises WHERE slug = 'cable-fly' LIMIT 1) IS NOT NULL
  AND (SELECT id FROM exercises WHERE slug = 'pec-deck' LIMIT 1) IS NOT NULL;


-- =============================================================================
-- 5. INJURY EXERCISE RESTRICTIONS  (~15 rows)
-- =============================================================================

INSERT INTO injury_exercise_restrictions
    (injury_type, restricted_exercise_id, suggested_substitute_id, severity, reason)

-- ── Shoulder injury ───────────────────────────────────────────────────────
SELECT
    'SHOULDER',
    (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'landmine-press' LIMIT 1),
    'MODERATE',
    'Overhead pressing places shoulder in impingement-prone position; landmine press uses angled path with less impingement risk'
WHERE (SELECT id FROM exercises WHERE slug = 'overhead-press' LIMIT 1) IS NOT NULL
ON CONFLICT DO NOTHING

UNION ALL SELECT
    'SHOULDER',
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'machine-chest-press' LIMIT 1),
    'MODERATE',
    'Barbell bench press fixed bar path can aggravate shoulder impingement; machine press allows adjustable path'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1) IS NOT NULL
ON CONFLICT DO NOTHING

UNION ALL SELECT
    'SHOULDER',
    (SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'cable-lateral-raise' LIMIT 1),
    'MILD',
    'Heavy dumbbell lateral raises can impinge; cable variant allows lower starting weight and controlled arc'
WHERE (SELECT id FROM exercises WHERE slug = 'lateral-raise' LIMIT 1) IS NOT NULL
ON CONFLICT DO NOTHING

UNION ALL SELECT
    'SHOULDER',
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    'MILD',
    'Dumbbell bench allows natural wrist rotation reducing shoulder stress'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1) IS NOT NULL
ON CONFLICT DO NOTHING

-- ── Knee injury ───────────────────────────────────────────────────────────
UNION ALL SELECT
    'KNEE',
    (SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'MODERATE',
    'Leg extension places isolated shear force on patellofemoral joint; leg press distributes load more safely'
WHERE (SELECT id FROM exercises WHERE slug = 'leg-extension' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'KNEE',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'MODERATE',
    'Deep barbell squat increases patellofemoral compressive forces; leg press allows controlled depth'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'KNEE',
    (SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'goblet-squat' LIMIT 1),
    'MILD',
    'Lunges create asymmetric knee tracking; goblet squat with controlled depth is safer alternative'
WHERE (SELECT id FROM exercises WHERE slug = 'walking-lunge' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'KNEE',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'trap-bar-deadlift' LIMIT 1),
    'SEVERE',
    'Severe knee injury: avoid knee-dominant loading; trap-bar deadlift shifts load to hip hinge pattern'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL

-- ── Lower back injury ─────────────────────────────────────────────────────
UNION ALL SELECT
    'LOWER_BACK',
    (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'trap-bar-deadlift' LIMIT 1),
    'MODERATE',
    'Conventional deadlift places high lumbar shear; trap-bar keeps load inline with body reducing spinal stress'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-deadlift' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'LOWER_BACK',
    (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'leg-press' LIMIT 1),
    'MODERATE',
    'Barbell squat loads spine axially; leg press removes spinal compression during lower-back rehab'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-back-squat' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'LOWER_BACK',
    (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'seated-cable-row' LIMIT 1),
    'MILD',
    'Bent-over row requires sustained lumbar extension under load; seated cable row is upright and supported'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-row' LIMIT 1) IS NOT NULL

-- ── Wrist injury ──────────────────────────────────────────────────────────
UNION ALL SELECT
    'WRIST',
    (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-bench-press' LIMIT 1),
    'MILD',
    'Fixed pronation of barbell bench can stress wrist extensors; dumbbells allow neutral/semi-supinated grip'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-bench-press' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'WRIST',
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'hammer-curl' LIMIT 1),
    'MILD',
    'Supinated barbell curl stresses wrist flexors; hammer curl uses neutral grip reducing wrist torque'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1) IS NOT NULL

-- ── Elbow injury ──────────────────────────────────────────────────────────
UNION ALL SELECT
    'ELBOW',
    (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'overhead-tricep-extension' LIMIT 1),
    'MILD',
    'Heavy pushdown can aggravate lateral epicondyle; overhead extension with lighter load is often better tolerated'
WHERE (SELECT id FROM exercises WHERE slug = 'tricep-pushdown' LIMIT 1) IS NOT NULL

UNION ALL SELECT
    'ELBOW',
    (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1),
    (SELECT id FROM exercises WHERE slug = 'dumbbell-curl' LIMIT 1),
    'MILD',
    'Barbell curl fixed supination stresses medial elbow; dumbbell allows slight wrist rotation reducing strain'
WHERE (SELECT id FROM exercises WHERE slug = 'barbell-curl' LIMIT 1) IS NOT NULL;

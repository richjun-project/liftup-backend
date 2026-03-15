-- Phase 1: Enterprise Workout System — canonical program tables
-- Decisions: D-5 (no currentWeek/currentDayInCycle), D-6 (minReps/maxReps, targetRPE, intensityPercent)

-- 1. canonical_programs
CREATE TABLE canonical_programs (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                     VARCHAR(100)  NOT NULL,
    name                     VARCHAR(255)  NOT NULL,
    split_type               VARCHAR(20)   NOT NULL,
    target_experience_level  VARCHAR(20)   NOT NULL,
    target_goal              VARCHAR(20)   NOT NULL,
    days_per_week            INT           NOT NULL,
    program_duration_weeks   INT           NOT NULL DEFAULT 8,
    deload_every_n_weeks     INT           NOT NULL DEFAULT 4,
    progression_model        VARCHAR(20)   NOT NULL,
    next_program_code        VARCHAR(100)  NULL,
    version                  INT           NOT NULL DEFAULT 1,
    description              TEXT          NULL,
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_canonical_programs_code UNIQUE (code)
);

CREATE INDEX idx_canonical_program_filter
    ON canonical_programs (target_experience_level, target_goal, days_per_week, is_active);

-- 2. program_days
CREATE TABLE program_days (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id                  BIGINT       NOT NULL,
    day_number                  INT          NOT NULL,
    workout_type                VARCHAR(20)  NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    estimated_duration_minutes  INT          NOT NULL DEFAULT 60,
    CONSTRAINT fk_program_days_program FOREIGN KEY (program_id) REFERENCES canonical_programs (id)
);

CREATE INDEX idx_program_day_program
    ON program_days (program_id, day_number);

-- 3. program_day_exercises
CREATE TABLE program_day_exercises (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_day_id          BIGINT       NOT NULL,
    exercise_id             BIGINT       NOT NULL,
    order_in_day            INT          NOT NULL,
    is_compound             BOOLEAN      NOT NULL DEFAULT TRUE,
    sets                    INT          NOT NULL,
    min_reps                INT          NOT NULL,
    max_reps                INT          NOT NULL,
    rest_seconds            INT          NOT NULL,
    target_rpe              DOUBLE       NOT NULL DEFAULT 7.0,
    intensity_percent_low   INT          NULL,
    intensity_percent_high  INT          NULL,
    set_type                VARCHAR(20)  NOT NULL DEFAULT 'WORKING',
    is_optional             BOOLEAN      NOT NULL DEFAULT FALSE,
    notes                   TEXT         NULL,
    CONSTRAINT fk_program_day_exercises_day      FOREIGN KEY (program_day_id) REFERENCES program_days (id),
    CONSTRAINT fk_program_day_exercises_exercise FOREIGN KEY (exercise_id)    REFERENCES exercises (id)
);

CREATE INDEX idx_day_exercise_day
    ON program_day_exercises (program_day_id, order_in_day);

-- 4. exercise_substitutions
CREATE TABLE exercise_substitutions (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_exercise_id  BIGINT       NOT NULL,
    substitute_exercise_id BIGINT      NOT NULL,
    priority              INT          NOT NULL DEFAULT 1,
    reason                VARCHAR(20)  NOT NULL,
    movement_pattern      VARCHAR(255) NOT NULL,
    notes                 TEXT         NULL,
    CONSTRAINT fk_exercise_substitutions_original   FOREIGN KEY (original_exercise_id)   REFERENCES exercises (id),
    CONSTRAINT fk_exercise_substitutions_substitute FOREIGN KEY (substitute_exercise_id) REFERENCES exercises (id)
);

CREATE INDEX idx_substitution_original
    ON exercise_substitutions (original_exercise_id);

-- 5. injury_exercise_restrictions
CREATE TABLE injury_exercise_restrictions (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    injury_type             VARCHAR(50)  NOT NULL,
    restricted_exercise_id  BIGINT       NOT NULL,
    suggested_substitute_id BIGINT       NULL,
    severity                VARCHAR(20)  NOT NULL,
    reason                  TEXT         NULL,
    CONSTRAINT fk_injury_restrictions_restricted  FOREIGN KEY (restricted_exercise_id)  REFERENCES exercises (id),
    CONSTRAINT fk_injury_restrictions_substitute  FOREIGN KEY (suggested_substitute_id) REFERENCES exercises (id)
);

CREATE INDEX idx_injury_restriction_type
    ON injury_exercise_restrictions (injury_type);

-- 6. user_program_enrollments  (D-5: no currentWeek / currentDayInCycle — computed from sessions)
CREATE TABLE user_program_enrollments (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                  BIGINT      NOT NULL,
    program_id               BIGINT      NOT NULL,
    program_version          INT         NOT NULL,
    start_date               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    total_completed_workouts INT         NOT NULL DEFAULT 0,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_active_date         DATETIME    NULL,
    end_date                 DATETIME    NULL,
    entity_version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_enrollments_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_enrollments_program FOREIGN KEY (program_id) REFERENCES canonical_programs (id)
);

CREATE INDEX idx_enrollment_user_status
    ON user_program_enrollments (user_id, status);

-- 7. user_exercise_overrides
CREATE TABLE user_exercise_overrides (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    enrollment_id           BIGINT      NOT NULL,
    original_exercise_id    BIGINT      NOT NULL,
    substitute_exercise_id  BIGINT      NOT NULL,
    reason                  VARCHAR(20) NOT NULL,
    CONSTRAINT fk_overrides_enrollment  FOREIGN KEY (enrollment_id)          REFERENCES user_program_enrollments (id),
    CONSTRAINT fk_overrides_original    FOREIGN KEY (original_exercise_id)   REFERENCES exercises (id),
    CONSTRAINT fk_overrides_substitute  FOREIGN KEY (substitute_exercise_id) REFERENCES exercises (id),
    CONSTRAINT uk_user_exercise_overrides_enrollment_original UNIQUE (enrollment_id, original_exercise_id)
);

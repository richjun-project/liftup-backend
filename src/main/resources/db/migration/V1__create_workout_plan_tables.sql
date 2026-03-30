-- ============================================================
-- V1: AI Workout Plan System Tables
-- ============================================================

-- 1. ALTER exercises table (add new columns)
-- ============================================================
ALTER TABLE exercises
    ADD COLUMN is_plan_eligible TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN plan_category VARCHAR(50) NULL;

-- 2. CREATE workout_plan_templates
-- ============================================================
CREATE TABLE IF NOT EXISTS workout_plan_templates (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    code            VARCHAR(50)     NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     TEXT            NULL,
    target_goal     VARCHAR(30)     NOT NULL,
    target_experience VARCHAR(20)   NOT NULL,
    split_type      VARCHAR(20)     NOT NULL,
    total_days      INT             NOT NULL,
    estimated_weeks INT             NOT NULL DEFAULT 8,
    icon_name       VARCHAR(50)     NULL,
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       TINYINT(1)      NOT NULL DEFAULT 1,
    is_premium      TINYINT(1)      NOT NULL DEFAULT 0,
    owner_user_id   BIGINT          NULL,
    source_type     VARCHAR(20)     NOT NULL DEFAULT 'PRESET',
    ai_coaching_notes TEXT          NULL,
    created_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_templates_code UNIQUE (code),
    CONSTRAINT fk_templates_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. CREATE template_days
-- ============================================================
CREATE TABLE IF NOT EXISTS template_days (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    template_id                 BIGINT      NOT NULL,
    day_number                  INT         NOT NULL,
    day_name                    VARCHAR(50) NOT NULL,
    workout_type                VARCHAR(20) NOT NULL,
    estimated_duration_minutes  INT         NULL DEFAULT 60,
    PRIMARY KEY (id),
    CONSTRAINT fk_template_days_template
        FOREIGN KEY (template_id) REFERENCES workout_plan_templates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. CREATE template_day_exercises
-- ============================================================
CREATE TABLE IF NOT EXISTS template_day_exercises (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    template_day_id BIGINT      NOT NULL,
    exercise_id     BIGINT      NOT NULL,
    order_in_day    INT         NOT NULL,
    sets            INT         NOT NULL,
    min_reps        INT         NOT NULL,
    max_reps        INT         NOT NULL,
    rest_seconds    INT         NOT NULL DEFAULT 90,
    is_compound     TINYINT(1)  NOT NULL DEFAULT 0,
    target_rpe      DOUBLE      NOT NULL DEFAULT 7.0,
    notes           TEXT        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tde_template_day
        FOREIGN KEY (template_day_id) REFERENCES template_days (id),
    CONSTRAINT fk_tde_exercise
        FOREIGN KEY (exercise_id) REFERENCES exercises (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. CREATE user_workout_plans
-- ============================================================
CREATE TABLE IF NOT EXISTS user_workout_plans (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT      NOT NULL,
    source_type             VARCHAR(20) NOT NULL,
    source_id               VARCHAR(50) NULL,
    plan_name               VARCHAR(100) NOT NULL,
    plan_description        TEXT        NULL,
    split_type              VARCHAR(20) NOT NULL,
    total_days              INT         NOT NULL,
    current_day             INT         NOT NULL DEFAULT 1,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    progression_model       VARCHAR(20) NOT NULL DEFAULT 'LINEAR',
    deload_every_n_weeks    INT         NOT NULL DEFAULT 4,
    ai_coaching_notes       TEXT        NULL,
    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_user_plans_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. CREATE user_plan_days
-- ============================================================
CREATE TABLE IF NOT EXISTS user_plan_days (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    plan_id                     BIGINT      NOT NULL,
    day_number                  INT         NOT NULL,
    day_name                    VARCHAR(50) NOT NULL,
    workout_type                VARCHAR(20) NOT NULL,
    estimated_duration_minutes  INT         NULL DEFAULT 60,
    total_completions           INT         NOT NULL DEFAULT 0,
    last_completed_at           DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_day UNIQUE (plan_id, day_number),
    CONSTRAINT fk_plan_days_plan
        FOREIGN KEY (plan_id) REFERENCES user_workout_plans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. CREATE user_plan_day_exercises
-- ============================================================
CREATE TABLE IF NOT EXISTS user_plan_day_exercises (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    plan_day_id     BIGINT      NOT NULL,
    exercise_id     BIGINT      NOT NULL,
    order_in_day    INT         NOT NULL,
    sets            INT         NOT NULL,
    min_reps        INT         NOT NULL,
    max_reps        INT         NOT NULL,
    rest_seconds    INT         NOT NULL DEFAULT 90,
    is_compound     TINYINT(1)  NOT NULL DEFAULT 0,
    target_rpe      DOUBLE      NOT NULL DEFAULT 7.0,
    notes           TEXT        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_upde_plan_day
        FOREIGN KEY (plan_day_id) REFERENCES user_plan_days (id),
    CONSTRAINT fk_upde_exercise
        FOREIGN KEY (exercise_id) REFERENCES exercises (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Indexes
-- ============================================================
CREATE INDEX idx_user_active_plan ON user_workout_plans (user_id, status);

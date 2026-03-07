ALTER TABLE exercises
    ADD COLUMN slug VARCHAR(191) NULL AFTER id,
    ADD COLUMN default_locale VARCHAR(10) NOT NULL DEFAULT 'en' AFTER name,
    ADD COLUMN movement_pattern VARCHAR(50) NULL AFTER category,
    ADD COLUMN equipment_detail VARCHAR(255) NULL AFTER equipment,
    ADD COLUMN source_category VARCHAR(100) NULL AFTER equipment_detail;

UPDATE exercises
SET slug = LOWER(
    TRIM(
        REPLACE(
            REPLACE(
                REPLACE(name, ' ', '-'),
                '_',
                '-'
            ),
            '--',
            '-'
        )
    )
)
WHERE slug IS NULL OR slug = '';

ALTER TABLE exercises
    MODIFY COLUMN slug VARCHAR(191) NOT NULL;

CREATE UNIQUE INDEX uk_exercises_slug ON exercises(slug);

CREATE TABLE IF NOT EXISTS exercise_translations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exercise_id BIGINT NOT NULL,
    locale VARCHAR(10) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    instructions TEXT NULL,
    tips TEXT NULL,
    CONSTRAINT fk_exercise_translation_exercise
        FOREIGN KEY (exercise_id) REFERENCES exercises(id)
        ON DELETE CASCADE,
    CONSTRAINT uk_exercise_translation_locale UNIQUE (exercise_id, locale)
);

CREATE INDEX idx_exercise_translation_locale_name
    ON exercise_translations(locale, display_name);

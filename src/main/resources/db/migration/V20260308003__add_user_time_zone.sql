ALTER TABLE user_settings
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul' AFTER language;

UPDATE user_settings
SET time_zone = 'Asia/Seoul'
WHERE time_zone IS NULL OR TRIM(time_zone) = '';

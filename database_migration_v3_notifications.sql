-- Notification Scheduling V3 Database Migration
-- Execute this script to create the necessary tables for notification scheduling and history

-- Create notification_schedules table
CREATE TABLE notification_schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    schedule_name VARCHAR(255) NOT NULL,
    time TIME NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    message VARCHAR(500) NOT NULL,
    notification_type VARCHAR(50) NOT NULL DEFAULT 'WORKOUT_REMINDER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    next_trigger_at TIMESTAMP NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_enabled (user_id, enabled),
    INDEX idx_next_trigger (enabled, next_trigger_at)
);

-- Create notification_schedule_days table for many-to-many relationship
CREATE TABLE notification_schedule_days (
    schedule_id BIGINT NOT NULL,
    day_of_week VARCHAR(3) NOT NULL,
    PRIMARY KEY (schedule_id, day_of_week),
    FOREIGN KEY (schedule_id) REFERENCES notification_schedules(id) ON DELETE CASCADE,
    CHECK (day_of_week IN ('MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'))
);

-- Create notification_history table
CREATE TABLE notification_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    notification_id VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP NULL,
    schedule_id BIGINT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (schedule_id) REFERENCES notification_schedules(id) ON DELETE SET NULL,
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_user_unread (user_id, is_read),
    INDEX idx_notification_id (notification_id)
);

-- Create notification_data table for key-value data storage
CREATE TABLE notification_data (
    notification_history_id BIGINT NOT NULL,
    data_key VARCHAR(255) NOT NULL,
    data_value TEXT,
    PRIMARY KEY (notification_history_id, data_key),
    FOREIGN KEY (notification_history_id) REFERENCES notification_history(id) ON DELETE CASCADE
);

-- Insert sample data for testing (optional)
-- Uncomment the following lines to insert test data

-- INSERT INTO notification_schedules (user_id, schedule_name, time, enabled, message, notification_type, next_trigger_at)
-- VALUES
-- (1, '아침 운동', '07:00:00', true, '오늘도 화이팅! 운동 시간입니다 💪', 'WORKOUT_REMINDER', '2024-01-20 07:00:00'),
-- (1, '저녁 운동', '18:00:00', true, '하루를 마무리하는 운동 시간! 💪', 'WORKOUT_REMINDER', '2024-01-20 18:00:00');

-- INSERT INTO notification_schedule_days (schedule_id, day_of_week)
-- VALUES
-- (1, 'MON'), (1, 'WED'), (1, 'FRI'),
-- (2, 'TUE'), (2, 'THU'), (2, 'SAT');

-- Verify table creation
SELECT
    TABLE_NAME,
    TABLE_ROWS,
    CREATE_TIME
FROM
    information_schema.TABLES
WHERE
    TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN (
        'notification_schedules',
        'notification_schedule_days',
        'notification_history',
        'notification_data'
    );
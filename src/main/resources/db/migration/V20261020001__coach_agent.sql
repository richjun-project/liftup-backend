-- AI PT 에이전트: 트레이너가 기억하는 사용자 사실(선호·일정·장비·부상 메모 등)과, 채팅 답마다 트레이너가 쓴 도구 기록.
-- 추가만 하는 마이그레이션.
CREATE TABLE IF NOT EXISTS coach_memories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    content VARCHAR(300) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coach_memories_user (user_id, updated_at)
);
ALTER TABLE chat_messages ADD COLUMN tool_trace VARCHAR(500) NULL;

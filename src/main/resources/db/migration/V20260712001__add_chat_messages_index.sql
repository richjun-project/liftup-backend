-- LiftUp AI: chat_messages 조회 성능 인덱스.
--
-- ChatMessageRepository의 핵심 쿼리(findByUserOrderByTimestampDesc,
-- findByUserAndTimestampBetweenOrderByTimestampDesc)가 (user_id, timestamp)
-- 복합 인덱스 없이 FK 단일 인덱스 + filesort로 동작하고 있었다.
-- 채팅은 앱의 핵심 기능이라 사용자·메시지 증가에 선형으로 느려지는 경로.

ALTER TABLE chat_messages
    ADD INDEX idx_chat_messages_user_timestamp (user_id, timestamp);

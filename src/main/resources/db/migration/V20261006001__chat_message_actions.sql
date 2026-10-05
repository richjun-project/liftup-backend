-- AI 트레이너 메시지 아래에 붙는 행동 버튼(오늘 운동 시작·식단 기록 등). 쉼표로 구분한 ChatAction 이름.
ALTER TABLE chat_messages ADD COLUMN actions VARCHAR(255) NULL;

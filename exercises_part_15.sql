USE liftupai_db;

INSERT INTO exercises (name, category, equipment, instructions) VALUES
('슬링샷 벤치', 'CHEST', 'OTHER', '슬링샷을 이용한 벤치프레스'),
('3보드 프레스', 'CHEST', 'BARBELL', '3개 보드를 가슴에 대고 프레스'),
('2보드 프레스', 'CHEST', 'BARBELL', '2개 보드를 가슴에 대고 프레스'),
('1보드 프레스', 'CHEST', 'BARBELL', '1개 보드를 가슴에 대고 프레스'),
-- 스쿼트 보조
('박스 스쿼트 와이드', 'LEGS', 'BARBELL', '넓은 스탠스로 박스 스쿼트'),
('박스 스쿼트 내로우', 'LEGS', 'BARBELL', '좁은 스탠스로 박스 스쿼트'),
('스피드 스쿼트', 'LEGS', 'BARBELL', '가벼운 무게로 빠르게 스쿼트'),
('체인 스쿼트', 'LEGS', 'BARBELL', '체인을 달고 스쿼트'),
('밴드 스쿼트', 'LEGS', 'BARBELL', '밴드 저항으로 스쿼트'),
-- 데드리프트 보조
('블록 풀', 'BACK', 'BARBELL', '블록 위에서 데드리프트'),
('래크 풀 니 하이트', 'BACK', 'BARBELL', '무릎 높이에서 데드리프트'),
('래크 풀 신 하이트', 'BACK', 'BARBELL', '정강이 높이에서 데드리프트'),
('리버스 밴드 데드리프트', 'BACK', 'BARBELL', '밴드 보조 데드리프트'),
('체인 데드리프트', 'BACK', 'BARBELL', '체인을 달고 데드리프트');
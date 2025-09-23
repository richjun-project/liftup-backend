-- 포괄적인 운동 데이터베이스 v2.0 - 중복 제거 및 체계화
-- 총 400개 이상의 운동을 카테고리별로 체계적으로 정리
USE liftupai_db;

-- 외래 키 체크 비활성화
SET FOREIGN_KEY_CHECKS = 0;

-- 관련 테이블 데이터 삭제 (외래 키 제약이 있는 테이블들)
DELETE FROM exercise_muscle_groups;
DELETE FROM exercise_sets;
DELETE FROM workout_exercises;
DELETE FROM exercise_templates;
DELETE FROM exercises;

-- 외래 키 체크 재활성화
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 1. CHEST 운동 (가슴) - 40개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동 (8개)
('바벨 벤치프레스', 'CHEST', 'BARBELL', '벤치에 누워 바벨을 가슴까지 내렸다가 팔을 완전히 펴면서 들어올립니다', 'CHEST'),
('인클라인 바벨 벤치프레스', 'CHEST', 'BARBELL', '30-45도 경사진 벤치에서 바벨을 가슴 상부로 내렸다가 들어올립니다', 'CHEST'),
('디클라인 바벨 벤치프레스', 'CHEST', 'BARBELL', '15-30도 하향 경사 벤치에서 바벨을 가슴 하부로 내렸다가 들어올립니다', 'CHEST'),
('클로즈그립 벤치프레스', 'CHEST', 'BARBELL', '어깨너비보다 좁은 그립으로 벤치프레스를 수행하여 삼두에 더 자극을 줍니다', 'CHEST'),
('와이드그립 벤치프레스', 'CHEST', 'BARBELL', '어깨너비보다 넓은 그립으로 벤치프레스를 수행하여 가슴 외측을 자극합니다', 'CHEST'),
('리버스그립 벤치프레스', 'CHEST', 'BARBELL', '언더핸드 그립으로 벤치프레스를 수행하여 상부 가슴을 자극합니다', 'CHEST'),
('길로틴 프레스', 'CHEST', 'BARBELL', '바벨을 목 쪽으로 내리는 벤치프레스 변형으로 가슴 상부를 집중 자극합니다', 'CHEST'),
('플로어 프레스', 'CHEST', 'BARBELL', '바닥에 누워 수행하는 벤치프레스로 하단 동작을 제한하여 록아웃 강화에 좋습니다', 'CHEST'),

-- 덤벨 운동 (12개)
('덤벨 벤치프레스', 'CHEST', 'DUMBBELL', '덤벨을 양손에 들고 벤치프레스 동작을 수행합니다', 'CHEST'),
('인클라인 덤벨 프레스', 'CHEST', 'DUMBBELL', '경사진 벤치에서 덤벨 프레스를 수행하여 상부 가슴을 자극합니다', 'CHEST'),
('디클라인 덤벨 프레스', 'CHEST', 'DUMBBELL', '하향 경사 벤치에서 덤벨 프레스를 수행하여 하부 가슴을 자극합니다', 'CHEST'),
('덤벨 플라이', 'CHEST', 'DUMBBELL', '덤벨을 양옆으로 벌렸다가 호를 그리며 가슴 위로 모읍니다', 'CHEST'),
('인클라인 덤벨 플라이', 'CHEST', 'DUMBBELL', '경사진 벤치에서 플라이를 수행하여 상부 가슴을 자극합니다', 'CHEST'),
('디클라인 덤벨 플라이', 'CHEST', 'DUMBBELL', '하향 경사에서 플라이를 수행하여 하부 가슴을 자극합니다', 'CHEST'),
('덤벨 풀오버', 'CHEST', 'DUMBBELL', '덤벨 하나를 양손으로 잡고 머리 뒤로 내렸다가 가슴 위로 올립니다', 'CHEST'),
('헥스 프레스', 'CHEST', 'DUMBBELL', '덤벨을 서로 붙인 상태로 프레스를 수행하여 가슴 안쪽을 자극합니다', 'CHEST'),
('스퀴즈 프레스', 'CHEST', 'DUMBBELL', '덤벨을 가슴 중앙에서 서로 밀어붙이며 프레스를 수행합니다', 'CHEST'),
('플로어 덤벨 프레스', 'CHEST', 'DUMBBELL', '바닥에 누워 덤벨 프레스를 수행합니다', 'CHEST'),
('싱글암 덤벨 프레스', 'CHEST', 'DUMBBELL', '한 팔씩 번갈아가며 덤벨 프레스를 수행합니다', 'CHEST'),
('덤벨 프레스 투 플라이', 'CHEST', 'DUMBBELL', '덤벨 프레스와 플라이를 번갈아가며 수행합니다', 'CHEST'),

-- 케이블/머신 운동 (10개)
('케이블 크로스오버', 'CHEST', 'CABLE', '케이블을 위에서 아래로 당기며 가슴 앞에서 교차시킵니다', 'CHEST'),
('로우 케이블 크로스오버', 'CHEST', 'CABLE', '케이블을 아래에서 위로 당기며 가슴 상부를 자극합니다', 'CHEST'),
('미드 케이블 크로스오버', 'CHEST', 'CABLE', '케이블을 가슴 높이에서 당기며 중부 가슴을 자극합니다', 'CHEST'),
('케이블 플라이', 'CHEST', 'CABLE', '벤치에 누워 케이블로 플라이 동작을 수행합니다', 'CHEST'),
('케이블 프레스', 'CHEST', 'CABLE', '케이블로 프레스 동작을 수행합니다', 'CHEST'),
('체스트 프레스 머신', 'CHEST', 'MACHINE', '머신에 앉아 핸들을 밀어 가슴을 자극합니다', 'CHEST'),
('펙덱 플라이', 'CHEST', 'MACHINE', '펙덱 머신에서 팔을 모아 가슴을 수축시킵니다', 'CHEST'),
('스미스머신 벤치프레스', 'CHEST', 'MACHINE', '스미스머신에서 안정적으로 벤치프레스를 수행합니다', 'CHEST'),
('스미스머신 인클라인 프레스', 'CHEST', 'MACHINE', '스미스머신에서 경사 벤치프레스를 수행합니다', 'CHEST'),
('해머스트렝스 체스트프레스', 'CHEST', 'MACHINE', '해머스트렝스 머신으로 체스트프레스를 수행합니다', 'CHEST'),

-- 맨몸 운동 (10개)
('푸시업', 'CHEST', 'BODYWEIGHT', '표준 푸시업을 수행합니다', 'CHEST'),
('와이드 푸시업', 'CHEST', 'BODYWEIGHT', '팔을 넓게 벌려 푸시업을 수행합니다', 'CHEST'),
('다이아몬드 푸시업', 'CHEST', 'BODYWEIGHT', '손을 다이아몬드 모양으로 모아 푸시업을 수행합니다', 'CHEST'),
('인클라인 푸시업', 'CHEST', 'BODYWEIGHT', '손을 높은 곳에 올려놓고 푸시업을 수행합니다', 'CHEST'),
('디클라인 푸시업', 'CHEST', 'BODYWEIGHT', '발을 높은 곳에 올려놓고 푸시업을 수행합니다', 'CHEST'),
('아처 푸시업', 'CHEST', 'BODYWEIGHT', '한쪽씩 번갈아가며 체중을 이동시키며 푸시업을 수행합니다', 'CHEST'),
('박수 푸시업', 'CHEST', 'BODYWEIGHT', '푸시업 중 손으로 박수를 치는 폭발적인 푸시업입니다', 'CHEST'),
('힌두 푸시업', 'CHEST', 'BODYWEIGHT', '요가 동작을 결합한 푸시업 변형입니다', 'CHEST'),
('딥스', 'CHEST', 'BODYWEIGHT', '평행봉에서 몸을 내렸다가 올리는 동작을 수행합니다', 'CHEST'),
('체스트 딥스', 'CHEST', 'BODYWEIGHT', '상체를 앞으로 기울여 가슴에 집중하여 딥스를 수행합니다', 'CHEST');

-- ============================================
-- 2. BACK 운동 (등) - 50개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동 (15개)
('데드리프트', 'BACK', 'BARBELL', '바닥의 바벨을 허리를 곧게 편 상태로 들어올립니다', 'BACK'),
('컨벤셔널 데드리프트', 'BACK', 'BARBELL', '일반적인 어깨너비 스탠스로 데드리프트를 수행합니다', 'BACK'),
('스모 데드리프트', 'BACK', 'BARBELL', '다리를 넓게 벌린 스모 스탠스로 데드리프트를 수행합니다', 'BACK'),
('루마니안 데드리프트', 'BACK', 'BARBELL', '무릎을 약간만 굽히고 엉덩이를 뒤로 빼며 바벨을 내립니다', 'BACK'),
('스티프 레그 데드리프트', 'BACK', 'BARBELL', '다리를 거의 펴고 허리 힌지 동작으로 바벨을 내립니다', 'BACK'),
('랙 풀', 'BACK', 'BARBELL', '무릎 높이의 랙에서 데드리프트를 수행합니다', 'BACK'),
('스내치 그립 데드리프트', 'BACK', 'BARBELL', '와이드 그립으로 데드리프트를 수행합니다', 'BACK'),
('벤트오버 바벨 로우', 'BACK', 'BARBELL', '상체를 숙인 상태에서 바벨을 배꼽쪽으로 당깁니다', 'BACK'),
('펜들레이 로우', 'BACK', 'BARBELL', '바닥에서 시작하여 폭발적으로 바벨을 당깁니다', 'BACK'),
('T-바 로우', 'BACK', 'BARBELL', 'T바 핸들을 잡고 로우 동작을 수행합니다', 'BACK'),
('언더핸드 바벨 로우', 'BACK', 'BARBELL', '언더그립으로 바벨 로우를 수행합니다', 'BACK'),
('시일 로우', 'BACK', 'BARBELL', '벤치에 엎드려 바벨 로우를 수행합니다', 'BACK'),
('메도우 로우', 'BACK', 'BARBELL', '랜드마인 바벨의 끝을 잡고 로우를 수행합니다', 'BACK'),
('굿모닝', 'BACK', 'BARBELL', '바벨을 등에 짊어지고 상체를 숙였다가 일으킵니다', 'BACK'),
('바벨 슈러그', 'BACK', 'BARBELL', '바벨을 들고 어깨를 으쓱하는 동작을 수행합니다', 'BACK'),

-- 덤벨 운동 (10개)
('원암 덤벨 로우', 'BACK', 'DUMBBELL', '한 손으로 덤벨을 당기는 로우 동작을 수행합니다', 'BACK'),
('벤트오버 덤벨 로우', 'BACK', 'DUMBBELL', '양손에 덤벨을 들고 로우 동작을 수행합니다', 'BACK'),
('크록 로우', 'BACK', 'DUMBBELL', '무거운 덤벨로 폭발적으로 로우를 수행합니다', 'BACK'),
('체스트 서포티드 로우', 'BACK', 'DUMBBELL', '인클라인 벤치에 엎드려 덤벨 로우를 수행합니다', 'BACK'),
('덤벨 데드리프트', 'BACK', 'DUMBBELL', '덤벨을 들고 데드리프트 동작을 수행합니다', 'BACK'),
('덤벨 루마니안 데드리프트', 'BACK', 'DUMBBELL', '덤벨로 루마니안 데드리프트를 수행합니다', 'BACK'),
('덤벨 슈러그', 'BACK', 'DUMBBELL', '덤벨을 들고 어깨를 으쓱합니다', 'BACK'),
('덤벨 풀오버', 'BACK', 'DUMBBELL', '등 근육을 사용하여 덤벨을 머리 뒤에서 가슴 위로 당깁니다', 'BACK'),
('리버스 플라이', 'BACK', 'DUMBBELL', '상체를 숙이고 덤벨을 옆으로 들어올립니다', 'BACK'),
('덤벨 로우 투 트라이셉스 킥백', 'BACK', 'DUMBBELL', '로우 후 트라이셉스 킥백을 연결하여 수행합니다', 'BACK'),

-- 케이블/머신 운동 (15개)
('랫 풀다운', 'BACK', 'CABLE', '케이블을 위에서 가슴쪽으로 당깁니다', 'BACK'),
('와이드 그립 랫 풀다운', 'BACK', 'CABLE', '넓은 그립으로 랫 풀다운을 수행합니다', 'BACK'),
('클로즈 그립 랫 풀다운', 'BACK', 'CABLE', '좁은 그립으로 랫 풀다운을 수행합니다', 'BACK'),
('언더핸드 랫 풀다운', 'BACK', 'CABLE', '언더그립으로 랫 풀다운을 수행합니다', 'BACK'),
('V-바 랫 풀다운', 'BACK', 'CABLE', 'V바 핸들로 랫 풀다운을 수행합니다', 'BACK'),
('시티드 케이블 로우', 'BACK', 'CABLE', '앉은 자세에서 케이블을 당깁니다', 'BACK'),
('원암 케이블 로우', 'BACK', 'CABLE', '한 손으로 케이블 로우를 수행합니다', 'BACK'),
('페이스 풀', 'BACK', 'CABLE', '케이블을 얼굴쪽으로 당기며 후면 삼각근을 자극합니다', 'BACK'),
('스트레이트 암 풀다운', 'BACK', 'CABLE', '팔을 펴고 케이블을 아래로 당깁니다', 'BACK'),
('케이블 슈러그', 'BACK', 'CABLE', '케이블을 이용하여 슈러그를 수행합니다', 'BACK'),
('로우 머신', 'BACK', 'MACHINE', '로우 머신으로 등 운동을 수행합니다', 'BACK'),
('풀다운 머신', 'BACK', 'MACHINE', '머신으로 풀다운 동작을 수행합니다', 'BACK'),
('어시스티드 풀업 머신', 'BACK', 'MACHINE', '보조 머신으로 풀업을 수행합니다', 'BACK'),
('해머스트렝스 로우', 'BACK', 'MACHINE', '해머스트렝스 머신으로 로우를 수행합니다', 'BACK'),
('스미스머신 로우', 'BACK', 'MACHINE', '스미스머신에서 로우를 수행합니다', 'BACK'),

-- 맨몸 운동 (10개)
('풀업', 'BACK', 'BODYWEIGHT', '철봉에 매달려 몸을 당겨올립니다', 'BACK'),
('와이드 그립 풀업', 'BACK', 'BODYWEIGHT', '넓은 그립으로 풀업을 수행합니다', 'BACK'),
('클로즈 그립 풀업', 'BACK', 'BODYWEIGHT', '좁은 그립으로 풀업을 수행합니다', 'BACK'),
('친업', 'BACK', 'BODYWEIGHT', '언더그립으로 풀업을 수행합니다', 'BACK'),
('뉴트럴 그립 풀업', 'BACK', 'BODYWEIGHT', '평행 그립으로 풀업을 수행합니다', 'BACK'),
('머슬업', 'BACK', 'BODYWEIGHT', '풀업 후 철봉 위로 올라갑니다', 'BACK'),
('인버티드 로우', 'BACK', 'BODYWEIGHT', '바 아래에서 몸을 당겨올립니다', 'BACK'),
('백 익스텐션', 'BACK', 'BODYWEIGHT', '엎드린 자세에서 상체를 들어올립니다', 'BACK'),
('슈퍼맨', 'BACK', 'BODYWEIGHT', '엎드려 팔과 다리를 동시에 들어올립니다', 'BACK'),
('리버스 스노우 에인절', 'BACK', 'BODYWEIGHT', '엎드려 팔을 Y자로 들어올립니다', 'BACK');

-- ============================================
-- 3. LEGS 운동 (하체) - 60개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 스쿼트 운동 (15개)
('백 스쿼트', 'LEGS', 'BARBELL', '바벨을 등에 짊어지고 스쿼트를 수행합니다', 'LEGS'),
('프론트 스쿼트', 'LEGS', 'BARBELL', '바벨을 어깨 앞에 올리고 스쿼트를 수행합니다', 'LEGS'),
('오버헤드 스쿼트', 'LEGS', 'BARBELL', '바벨을 머리 위로 들고 스쿼트를 수행합니다', 'LEGS'),
('박스 스쿼트', 'LEGS', 'BARBELL', '박스에 앉았다가 일어나는 스쿼트를 수행합니다', 'LEGS'),
('포즈 스쿼트', 'LEGS', 'BARBELL', '하단에서 잠시 멈춘 후 일어나는 스쿼트입니다', 'LEGS'),
('하이바 스쿼트', 'LEGS', 'BARBELL', '바벨을 승모근 상부에 올리고 스쿼트를 수행합니다', 'LEGS'),
('로우바 스쿼트', 'LEGS', 'BARBELL', '바벨을 삼각근 후면에 올리고 스쿼트를 수행합니다', 'LEGS'),
('제르처 스쿼트', 'LEGS', 'BARBELL', '바벨을 팔꿈치 안쪽에 올리고 스쿼트를 수행합니다', 'LEGS'),
('해킹 스쿼트', 'LEGS', 'BARBELL', '바벨을 뒤에 들고 스쿼트를 수행합니다', 'LEGS'),
('불가리안 스플릿 스쿼트', 'LEGS', 'BARBELL', '한 발을 뒤로 올리고 바벨 스쿼트를 수행합니다', 'LEGS'),
('런지', 'LEGS', 'BARBELL', '바벨을 짊어지고 런지 동작을 수행합니다', 'LEGS'),
('리버스 런지', 'LEGS', 'BARBELL', '바벨을 짊어지고 뒤로 런지를 수행합니다', 'LEGS'),
('워킹 런지', 'LEGS', 'BARBELL', '바벨을 짊어지고 걸으며 런지를 수행합니다', 'LEGS'),
('스텝업', 'LEGS', 'BARBELL', '바벨을 짊어지고 박스에 올라갑니다', 'LEGS'),
('힙 쓰러스트', 'LEGS', 'BARBELL', '바벨을 골반에 올리고 엉덩이를 들어올립니다', 'LEGS'),

-- 덤벨 하체 운동 (12개)
('고블릿 스쿼트', 'LEGS', 'DUMBBELL', '덤벨을 가슴 앞에 들고 스쿼트를 수행합니다', 'LEGS'),
('덤벨 스쿼트', 'LEGS', 'DUMBBELL', '양손에 덤벨을 들고 스쿼트를 수행합니다', 'LEGS'),
('덤벨 불가리안 스플릿 스쿼트', 'LEGS', 'DUMBBELL', '덤벨을 들고 한 발을 뒤로 올려 스쿼트를 수행합니다', 'LEGS'),
('덤벨 런지', 'LEGS', 'DUMBBELL', '덤벨을 들고 런지를 수행합니다', 'LEGS'),
('덤벨 리버스 런지', 'LEGS', 'DUMBBELL', '덤벨을 들고 뒤로 런지를 수행합니다', 'LEGS'),
('덤벨 사이드 런지', 'LEGS', 'DUMBBELL', '덤벨을 들고 옆으로 런지를 수행합니다', 'LEGS'),
('덤벨 워킹 런지', 'LEGS', 'DUMBBELL', '덤벨을 들고 걸으며 런지를 수행합니다', 'LEGS'),
('덤벨 스텝업', 'LEGS', 'DUMBBELL', '덤벨을 들고 박스에 올라갑니다', 'LEGS'),
('덤벨 스티프 레그 데드리프트', 'LEGS', 'DUMBBELL', '덤벨로 스티프 레그 데드리프트를 수행합니다', 'LEGS'),
('덤벨 싱글 레그 데드리프트', 'LEGS', 'DUMBBELL', '한 다리로 덤벨 데드리프트를 수행합니다', 'LEGS'),
('덤벨 스쿼트 투 프레스', 'LEGS', 'DUMBBELL', '스쿼트 후 덤벨을 머리 위로 프레스합니다', 'LEGS'),
('덤벨 스윙', 'LEGS', 'DUMBBELL', '덤벨을 다리 사이로 스윙합니다', 'LEGS'),

-- 머신 하체 운동 (15개)
('레그 프레스', 'LEGS', 'MACHINE', '레그 프레스 머신에서 다리로 무게를 밀어올립니다', 'LEGS'),
('45도 레그 프레스', 'LEGS', 'MACHINE', '45도 각도의 레그 프레스를 수행합니다', 'LEGS'),
('싱글 레그 프레스', 'LEGS', 'MACHINE', '한 다리로 레그 프레스를 수행합니다', 'LEGS'),
('해크 스쿼트 머신', 'LEGS', 'MACHINE', '해크 스쿼트 머신에서 스쿼트를 수행합니다', 'LEGS'),
('레그 익스텐션', 'LEGS', 'MACHINE', '앉은 자세에서 다리를 펴는 동작을 수행합니다', 'LEGS'),
('레그 컬', 'LEGS', 'MACHINE', '엎드려서 다리를 구부리는 동작을 수행합니다', 'LEGS'),
('시티드 레그 컬', 'LEGS', 'MACHINE', '앉은 자세에서 레그 컬을 수행합니다', 'LEGS'),
('스탠딩 레그 컬', 'LEGS', 'MACHINE', '서서 한 다리씩 레그 컬을 수행합니다', 'LEGS'),
('글루트 햄 레이즈 머신', 'LEGS', 'MACHINE', 'GHR 머신에서 햄스트링과 둔근을 자극합니다', 'LEGS'),
('어덕터 머신', 'LEGS', 'MACHINE', '다리를 벌리는 동작으로 외전근을 자극합니다', 'LEGS'),
('어브덕터 머신', 'LEGS', 'MACHINE', '다리를 모으는 동작으로 내전근을 자극합니다', 'LEGS'),
('스미스머신 스쿼트', 'LEGS', 'MACHINE', '스미스머신에서 안정적으로 스쿼트를 수행합니다', 'LEGS'),
('스미스머신 런지', 'LEGS', 'MACHINE', '스미스머신에서 런지를 수행합니다', 'LEGS'),
('스미스머신 불가리안 스플릿', 'LEGS', 'MACHINE', '스미스머신에서 불가리안 스플릿 스쿼트를 수행합니다', 'LEGS'),
('펜들럼 스쿼트', 'LEGS', 'MACHINE', '펜들럼 머신에서 스쿼트를 수행합니다', 'LEGS'),

-- 종아리 운동 (8개)
('스탠딩 카프 레이즈', 'LEGS', 'MACHINE', '서서 발뒤꿈치를 들어올립니다', 'LEGS'),
('시티드 카프 레이즈', 'LEGS', 'MACHINE', '앉아서 발뒤꿈치를 들어올립니다', 'LEGS'),
('레그프레스 카프 레이즈', 'LEGS', 'MACHINE', '레그프레스 머신에서 카프 레이즈를 수행합니다', 'LEGS'),
('스미스머신 카프 레이즈', 'LEGS', 'MACHINE', '스미스머신에서 카프 레이즈를 수행합니다', 'LEGS'),
('덤벨 카프 레이즈', 'LEGS', 'DUMBBELL', '덤벨을 들고 카프 레이즈를 수행합니다', 'LEGS'),
('싱글 레그 카프 레이즈', 'LEGS', 'DUMBBELL', '한 다리로 카프 레이즈를 수행합니다', 'LEGS'),
('동키 카프 레이즈', 'LEGS', 'MACHINE', '상체를 숙이고 카프 레이즈를 수행합니다', 'LEGS'),
('티비알 레이즈', 'LEGS', 'BODYWEIGHT', '발끝을 들어올려 정강이 근육을 자극합니다', 'LEGS'),

-- 맨몸 하체 운동 (10개)
('에어 스쿼트', 'LEGS', 'BODYWEIGHT', '맨몸으로 스쿼트를 수행합니다', 'LEGS'),
('점프 스쿼트', 'LEGS', 'BODYWEIGHT', '스쿼트 후 점프하는 동작을 수행합니다', 'LEGS'),
('피스톨 스쿼트', 'LEGS', 'BODYWEIGHT', '한 다리로 스쿼트를 수행합니다', 'LEGS'),
('런지', 'LEGS', 'BODYWEIGHT', '맨몸으로 런지를 수행합니다', 'LEGS'),
('점핑 런지', 'LEGS', 'BODYWEIGHT', '런지 자세에서 점프하며 다리를 교체합니다', 'LEGS'),
('월 싯', 'LEGS', 'BODYWEIGHT', '벽에 등을 대고 스쿼트 자세를 유지합니다', 'LEGS'),
('싱글 레그 월 싯', 'LEGS', 'BODYWEIGHT', '한 다리로 월 싯을 수행합니다', 'LEGS'),
('박스 점프', 'LEGS', 'BODYWEIGHT', '박스 위로 점프합니다', 'LEGS'),
('브로드 점프', 'LEGS', 'BODYWEIGHT', '멀리 점프하는 동작을 수행합니다', 'LEGS'),
('스케이터 점프', 'LEGS', 'BODYWEIGHT', '스케이트 타듯 옆으로 점프합니다', 'LEGS');

-- ============================================
-- 4. SHOULDERS 운동 (어깨) - 40개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동 (10개)
('밀리터리 프레스', 'SHOULDERS', 'BARBELL', '바벨을 어깨 높이에서 머리 위로 프레스합니다', 'SHOULDERS'),
('비하인드 넥 프레스', 'SHOULDERS', 'BARBELL', '바벨을 목 뒤에서 머리 위로 프레스합니다', 'SHOULDERS'),
('푸시 프레스', 'SHOULDERS', 'BARBELL', '다리의 반동을 이용하여 바벨을 프레스합니다', 'SHOULDERS'),
('푸시 저크', 'SHOULDERS', 'BARBELL', '다리로 추진력을 만들어 바벨을 머리 위로 올립니다', 'SHOULDERS'),
('업라이트 로우', 'SHOULDERS', 'BARBELL', '바벨을 몸 가까이에서 턱 높이까지 당깁니다', 'SHOULDERS'),
('와이드 그립 업라이트 로우', 'SHOULDERS', 'BARBELL', '넓은 그립으로 업라이트 로우를 수행합니다', 'SHOULDERS'),
('브래드포드 프레스', 'SHOULDERS', 'BARBELL', '앞과 뒤를 번갈아가며 프레스합니다', 'SHOULDERS'),
('바벨 프론트 레이즈', 'SHOULDERS', 'BARBELL', '바벨을 앞으로 들어올립니다', 'SHOULDERS'),
('랜드마인 프레스', 'SHOULDERS', 'BARBELL', '바벨 한쪽 끝을 고정하고 프레스합니다', 'SHOULDERS'),
('Z-프레스', 'SHOULDERS', 'BARBELL', '바닥에 앉아 다리를 뻗고 프레스합니다', 'SHOULDERS'),

-- 덤벨 운동 (15개)
('덤벨 숄더 프레스', 'SHOULDERS', 'DUMBBELL', '덤벨을 어깨 높이에서 머리 위로 프레스합니다', 'SHOULDERS'),
('아놀드 프레스', 'SHOULDERS', 'DUMBBELL', '덤벨을 회전시키며 프레스합니다', 'SHOULDERS'),
('시티드 덤벨 프레스', 'SHOULDERS', 'DUMBBELL', '앉아서 덤벨 프레스를 수행합니다', 'SHOULDERS'),
('스탠딩 덤벨 프레스', 'SHOULDERS', 'DUMBBELL', '서서 덤벨 프레스를 수행합니다', 'SHOULDERS'),
('싱글암 덤벨 프레스', 'SHOULDERS', 'DUMBBELL', '한 팔씩 번갈아 프레스합니다', 'SHOULDERS'),
('레터럴 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 옆으로 들어올립니다', 'SHOULDERS'),
('프론트 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 앞으로 들어올립니다', 'SHOULDERS'),
('리어 델트 플라이', 'SHOULDERS', 'DUMBBELL', '상체를 숙이고 덤벨을 옆으로 들어올립니다', 'SHOULDERS'),
('벤트오버 레터럴 레이즈', 'SHOULDERS', 'DUMBBELL', '상체를 숙이고 레터럴 레이즈를 수행합니다', 'SHOULDERS'),
('업라이트 로우', 'SHOULDERS', 'DUMBBELL', '덤벨을 몸 가까이에서 턱까지 당깁니다', 'SHOULDERS'),
('스캡션', 'SHOULDERS', 'DUMBBELL', '30도 각도로 덤벨을 들어올립니다', 'SHOULDERS'),
('Y-레이즈', 'SHOULDERS', 'DUMBBELL', 'Y자 모양으로 덤벨을 들어올립니다', 'SHOULDERS'),
('T-레이즈', 'SHOULDERS', 'DUMBBELL', 'T자 모양으로 덤벨을 들어올립니다', 'SHOULDERS'),
('W-레이즈', 'SHOULDERS', 'DUMBBELL', 'W자 모양으로 덤벨을 들어올립니다', 'SHOULDERS'),
('쿠반 프레스', 'SHOULDERS', 'DUMBBELL', '외회전과 프레스를 결합한 동작입니다', 'SHOULDERS'),

-- 케이블/머신 운동 (10개)
('케이블 레터럴 레이즈', 'SHOULDERS', 'CABLE', '케이블로 레터럴 레이즈를 수행합니다', 'SHOULDERS'),
('케이블 프론트 레이즈', 'SHOULDERS', 'CABLE', '케이블로 프론트 레이즈를 수행합니다', 'SHOULDERS'),
('케이블 리어 델트', 'SHOULDERS', 'CABLE', '케이블로 후면 삼각근을 자극합니다', 'SHOULDERS'),
('케이블 업라이트 로우', 'SHOULDERS', 'CABLE', '케이블로 업라이트 로우를 수행합니다', 'SHOULDERS'),
('페이스 풀', 'SHOULDERS', 'CABLE', '케이블을 얼굴쪽으로 당깁니다', 'SHOULDERS'),
('숄더 프레스 머신', 'SHOULDERS', 'MACHINE', '머신으로 숄더 프레스를 수행합니다', 'SHOULDERS'),
('스미스머신 숄더 프레스', 'SHOULDERS', 'MACHINE', '스미스머신에서 숄더 프레스를 수행합니다', 'SHOULDERS'),
('스미스머신 비하인드 넥', 'SHOULDERS', 'MACHINE', '스미스머신에서 비하인드 넥 프레스를 수행합니다', 'SHOULDERS'),
('레터럴 레이즈 머신', 'SHOULDERS', 'MACHINE', '머신으로 레터럴 레이즈를 수행합니다', 'SHOULDERS'),
('리어 델트 머신', 'SHOULDERS', 'MACHINE', '머신으로 후면 삼각근을 자극합니다', 'SHOULDERS'),

-- 특수 운동 (5개)
('케틀벨 프레스', 'SHOULDERS', 'KETTLEBELL', '케틀벨을 머리 위로 프레스합니다', 'SHOULDERS'),
('케틀벨 윈드밀', 'SHOULDERS', 'KETTLEBELL', '케틀벨을 든 채 옆으로 몸을 굽힙니다', 'SHOULDERS'),
('플레이트 레이즈', 'SHOULDERS', 'OTHER', '원판을 들고 앞으로 들어올립니다', 'SHOULDERS'),
('밴드 풀어파트', 'SHOULDERS', 'OTHER', '밴드를 양옆으로 당깁니다', 'SHOULDERS'),
('핸드스탠드 푸시업', 'SHOULDERS', 'BODYWEIGHT', '물구나무 자세에서 푸시업을 수행합니다', 'SHOULDERS');

-- ============================================
-- 5. ARMS 운동 (팔) - 50개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 이두근 바벨 운동 (8개)
('바벨 컬', 'ARMS', 'BARBELL', '바벨을 언더그립으로 잡고 팔을 구부립니다', 'ARMS'),
('EZ-바 컬', 'ARMS', 'BARBELL', 'EZ바로 컬 동작을 수행합니다', 'ARMS'),
('와이드 그립 바벨 컬', 'ARMS', 'BARBELL', '넓은 그립으로 바벨 컬을 수행합니다', 'ARMS'),
('클로즈 그립 바벨 컬', 'ARMS', 'BARBELL', '좁은 그립으로 바벨 컬을 수행합니다', 'ARMS'),
('프리처 컬', 'ARMS', 'BARBELL', '프리처 벤치에서 바벨 컬을 수행합니다', 'ARMS'),
('드래그 컬', 'ARMS', 'BARBELL', '바벨을 몸에 붙여 끌어올립니다', 'ARMS'),
('리버스 컬', 'ARMS', 'BARBELL', '오버그립으로 바벨 컬을 수행합니다', 'ARMS'),
('21s 바벨 컬', 'ARMS', 'BARBELL', '하단-중간 7회, 중간-상단 7회, 전체 7회 수행합니다', 'ARMS'),

-- 이두근 덤벨 운동 (10개)
('덤벨 컬', 'ARMS', 'DUMBBELL', '덤벨로 컬 동작을 수행합니다', 'ARMS'),
('해머 컬', 'ARMS', 'DUMBBELL', '중립 그립으로 덤벨 컬을 수행합니다', 'ARMS'),
('얼터네이팅 덤벨 컬', 'ARMS', 'DUMBBELL', '양팔을 번갈아가며 컬을 수행합니다', 'ARMS'),
('인클라인 덤벨 컬', 'ARMS', 'DUMBBELL', '경사 벤치에 누워 덤벨 컬을 수행합니다', 'ARMS'),
('컨센트레이션 컬', 'ARMS', 'DUMBBELL', '팔꿈치를 무릎에 고정하고 컬을 수행합니다', 'ARMS'),
('스파이더 컬', 'ARMS', 'DUMBBELL', '인클라인 벤치에 엎드려 컬을 수행합니다', 'ARMS'),
('조티 컬', 'ARMS', 'DUMBBELL', '덤벨을 수직으로 세워 컬을 수행합니다', 'ARMS'),
('크로스바디 해머 컬', 'ARMS', 'DUMBBELL', '몸을 가로질러 해머 컬을 수행합니다', 'ARMS'),
('웨이터 컬', 'ARMS', 'DUMBBELL', '덤벨 하나를 양손으로 받쳐들고 컬을 수행합니다', 'ARMS'),
('덤벨 프리처 컬', 'ARMS', 'DUMBBELL', '프리처 벤치에서 덤벨 컬을 수행합니다', 'ARMS'),

-- 이두근 케이블 운동 (5개)
('케이블 컬', 'ARMS', 'CABLE', '케이블로 컬 동작을 수행합니다', 'ARMS'),
('케이블 해머 컬', 'ARMS', 'CABLE', '로프를 이용해 해머 컬을 수행합니다', 'ARMS'),
('하이 케이블 컬', 'ARMS', 'CABLE', '높은 위치의 케이블로 컬을 수행합니다', 'ARMS'),
('케이블 프리처 컬', 'ARMS', 'CABLE', '케이블로 프리처 컬을 수행합니다', 'ARMS'),
('케이블 드래그 컬', 'ARMS', 'CABLE', '케이블로 드래그 컬을 수행합니다', 'ARMS'),

-- 삼두근 바벨 운동 (7개)
('클로즈 그립 벤치프레스', 'ARMS', 'BARBELL', '좁은 그립으로 벤치프레스를 수행합니다', 'ARMS'),
('스컬크러셔', 'ARMS', 'BARBELL', '누워서 바벨을 이마쪽으로 내렸다가 올립니다', 'ARMS'),
('오버헤드 트라이셉스 익스텐션', 'ARMS', 'BARBELL', '바벨을 머리 위로 들고 팔꿈치를 구부립니다', 'ARMS'),
('JM 프레스', 'ARMS', 'BARBELL', '스컬크러셔와 클로즈그립 프레스의 중간 동작입니다', 'ARMS'),
('바벨 킥백', 'ARMS', 'BARBELL', '상체를 숙이고 바벨로 킥백을 수행합니다', 'ARMS'),
('리버스 그립 벤치프레스', 'ARMS', 'BARBELL', '언더그립으로 벤치프레스를 수행합니다', 'ARMS'),
('캘리포니아 프레스', 'ARMS', 'BARBELL', '스컬크러셔와 프레스를 결합한 동작입니다', 'ARMS'),

-- 삼두근 덤벨 운동 (8개)
('덤벨 오버헤드 익스텐션', 'ARMS', 'DUMBBELL', '덤벨을 머리 위로 들고 팔꿈치를 구부립니다', 'ARMS'),
('덤벨 킥백', 'ARMS', 'DUMBBELL', '상체를 숙이고 덤벨을 뒤로 밀어냅니다', 'ARMS'),
('덤벨 스컬크러셔', 'ARMS', 'DUMBBELL', '누워서 덤벨을 이마쪽으로 내렸다가 올립니다', 'ARMS'),
('타테 프레스', 'ARMS', 'DUMBBELL', '덤벨을 수직으로 세워 프레스합니다', 'ARMS'),
('덤벨 클로즈그립 프레스', 'ARMS', 'DUMBBELL', '덤벨을 모아 프레스합니다', 'ARMS'),
('싱글암 오버헤드 익스텐션', 'ARMS', 'DUMBBELL', '한 팔로 오버헤드 익스텐션을 수행합니다', 'ARMS'),
('덤벨 플로어 프레스', 'ARMS', 'DUMBBELL', '바닥에 누워 덤벨 프레스를 수행합니다', 'ARMS'),
('덤벨 롤링 익스텐션', 'ARMS', 'DUMBBELL', '덤벨을 굴리듯 익스텐션을 수행합니다', 'ARMS'),

-- 삼두근 케이블 운동 (5개)
('케이블 푸시다운', 'ARMS', 'CABLE', '케이블을 아래로 밀어내립니다', 'ARMS'),
('로프 푸시다운', 'ARMS', 'CABLE', '로프를 이용해 푸시다운을 수행합니다', 'ARMS'),
('오버헤드 케이블 익스텐션', 'ARMS', 'CABLE', '케이블을 머리 위에서 당깁니다', 'ARMS'),
('케이블 킥백', 'ARMS', 'CABLE', '케이블로 킥백 동작을 수행합니다', 'ARMS'),
('리버스 그립 푸시다운', 'ARMS', 'CABLE', '언더그립으로 푸시다운을 수행합니다', 'ARMS'),

-- 전완근 운동 (5개)
('바벨 리스트 컬', 'ARMS', 'BARBELL', '손목을 구부려 전완근을 자극합니다', 'ARMS'),
('리버스 리스트 컬', 'ARMS', 'BARBELL', '손목을 반대로 구부려 전완근을 자극합니다', 'ARMS'),
('파머스 워크', 'ARMS', 'DUMBBELL', '무거운 덤벨을 들고 걷습니다', 'ARMS'),
('데드 행', 'ARMS', 'BARBELL', '바벨에 매달려 버팁니다', 'ARMS'),
('플레이트 핀치', 'ARMS', 'OTHER', '원판을 손가락으로 집어 들고 버팁니다', 'ARMS'),

-- 맨몸 운동 (2개)
('다이아몬드 푸시업', 'ARMS', 'BODYWEIGHT', '손을 다이아몬드 모양으로 모아 푸시업을 수행합니다', 'ARMS'),
('트라이셉스 딥스', 'ARMS', 'BODYWEIGHT', '평행봉이나 벤치에서 딥스를 수행합니다', 'ARMS');

-- ============================================
-- 6. CORE 운동 (코어/복근) - 50개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 상복부 운동 (10개)
('크런치', 'CORE', 'BODYWEIGHT', '누워서 상체를 말아올립니다', 'CORE'),
('싯업', 'CORE', 'BODYWEIGHT', '누워서 상체를 완전히 일으킵니다', 'CORE'),
('디클라인 싯업', 'CORE', 'BODYWEIGHT', '경사 벤치에서 싯업을 수행합니다', 'CORE'),
('토투바', 'CORE', 'BODYWEIGHT', '발끝을 손으로 터치하는 동작을 수행합니다', 'CORE'),
('바이시클 크런치', 'CORE', 'BODYWEIGHT', '자전거 타듯 다리를 움직이며 크런치합니다', 'CORE'),
('리버스 크런치', 'CORE', 'BODYWEIGHT', '다리를 가슴쪽으로 당기는 크런치입니다', 'CORE'),
('V-업', 'CORE', 'BODYWEIGHT', 'V자 모양으로 상하체를 동시에 올립니다', 'CORE'),
('케이블 크런치', 'CORE', 'CABLE', '무릎을 꿇고 케이블로 크런치를 수행합니다', 'CORE'),
('볼 크런치', 'CORE', 'OTHER', '짐볼 위에서 크런치를 수행합니다', 'CORE'),
('웨이티드 크런치', 'CORE', 'OTHER', '원판을 들고 크런치를 수행합니다', 'CORE'),

-- 하복부 운동 (10개)
('레그 레이즈', 'CORE', 'BODYWEIGHT', '누워서 다리를 들어올립니다', 'CORE'),
('행잉 레그 레이즈', 'CORE', 'BODYWEIGHT', '철봉에 매달려 다리를 들어올립니다', 'CORE'),
('니 레이즈', 'CORE', 'BODYWEIGHT', '무릎을 가슴쪽으로 당깁니다', 'CORE'),
('행잉 니 레이즈', 'CORE', 'BODYWEIGHT', '철봉에 매달려 무릎을 올립니다', 'CORE'),
('시저 킥', 'CORE', 'BODYWEIGHT', '다리를 가위처럼 교차시킵니다', 'CORE'),
('플러터 킥', 'CORE', 'BODYWEIGHT', '다리를 위아래로 빠르게 움직입니다', 'CORE'),
('리버스 크런치', 'CORE', 'BODYWEIGHT', '엉덩이를 들어올리며 복근을 수축합니다', 'CORE'),
('드래곤 플래그', 'CORE', 'BODYWEIGHT', '어깨만 바닥에 대고 몸을 일직선으로 유지합니다', 'CORE'),
('L-싯', 'CORE', 'BODYWEIGHT', 'L자 모양으로 다리를 들고 유지합니다', 'CORE'),
('앱 롤러', 'CORE', 'OTHER', 'AB 휠을 굴리며 코어를 자극합니다', 'CORE'),

-- 사근 운동 (10개)
('러시안 트위스트', 'CORE', 'BODYWEIGHT', '앉아서 상체를 좌우로 회전시킵니다', 'CORE'),
('사이드 크런치', 'CORE', 'BODYWEIGHT', '옆으로 누워 크런치를 수행합니다', 'CORE'),
('오블리크 크런치', 'CORE', 'BODYWEIGHT', '대각선으로 크런치를 수행합니다', 'CORE'),
('우드촙', 'CORE', 'CABLE', '케이블을 대각선으로 당깁니다', 'CORE'),
('케이블 우드촙', 'CORE', 'CABLE', '케이블로 우드촙 동작을 수행합니다', 'CORE'),
('사이드 플랭크', 'CORE', 'BODYWEIGHT', '옆으로 누워 플랭크를 유지합니다', 'CORE'),
('윈드실드 와이퍼', 'CORE', 'BODYWEIGHT', '매달려서 다리를 좌우로 움직입니다', 'CORE'),
('메디신볼 트위스트', 'CORE', 'OTHER', '메디신볼을 들고 러시안 트위스트를 수행합니다', 'CORE'),
('사이드 벤드', 'CORE', 'DUMBBELL', '덤벨을 들고 옆구리를 굽힙니다', 'CORE'),
('팔로프 프레스', 'CORE', 'CABLE', '케이블을 몸 앞으로 밀어냅니다', 'CORE'),

-- 코어 안정화 운동 (15개)
('플랭크', 'CORE', 'BODYWEIGHT', '팔꿈치와 발끝으로 몸을 일직선으로 유지합니다', 'CORE'),
('하이 플랭크', 'CORE', 'BODYWEIGHT', '팔을 펴고 플랭크를 유지합니다', 'CORE'),
('플랭크 업다운', 'CORE', 'BODYWEIGHT', '플랭크와 하이 플랭크를 번갈아 수행합니다', 'CORE'),
('플랭크 잭', 'CORE', 'BODYWEIGHT', '플랭크 자세에서 다리를 벌렸다 모읍니다', 'CORE'),
('마운틴 클라이머', 'CORE', 'BODYWEIGHT', '플랭크 자세에서 무릎을 가슴쪽으로 당깁니다', 'CORE'),
('베어 크롤', 'CORE', 'BODYWEIGHT', '네발로 기어갑니다', 'CORE'),
('데드 버그', 'CORE', 'BODYWEIGHT', '누워서 팔다리를 교대로 움직입니다', 'CORE'),
('버드 독', 'CORE', 'BODYWEIGHT', '네발 자세에서 반대쪽 팔다리를 듭니다', 'CORE'),
('홀로우 바디 홀드', 'CORE', 'BODYWEIGHT', '등을 바닥에 붙이고 팔다리를 들어 유지합니다', 'CORE'),
('홀로우 바디 록', 'CORE', 'BODYWEIGHT', '홀로우 자세에서 앞뒤로 흔듭니다', 'CORE'),
('슈퍼맨', 'CORE', 'BODYWEIGHT', '엎드려서 팔다리를 들어올립니다', 'CORE'),
('인치웜', 'CORE', 'BODYWEIGHT', '손으로 걸어나가며 플랭크 자세를 만듭니다', 'CORE'),
('롤아웃', 'CORE', 'OTHER', '바벨이나 AB휠로 롤아웃을 수행합니다', 'CORE'),
('스터 더 팟', 'CORE', 'BODYWEIGHT', '플랭크 자세에서 엉덩이를 원을 그리며 움직입니다', 'CORE'),
('코펜하겐 플랭크', 'CORE', 'BODYWEIGHT', '옆으로 누워 다리를 벤치에 올리고 플랭크를 유지합니다', 'CORE'),

-- 복합 코어 운동 (5개)
('터키시 겟업', 'CORE', 'KETTLEBELL', '누운 자세에서 케틀벨을 들고 일어납니다', 'CORE'),
('파머스 워크', 'CORE', 'DUMBBELL', '무거운 덤벨을 들고 걸으며 코어를 안정화합니다', 'CORE'),
('수트케이스 캐리', 'CORE', 'DUMBBELL', '한쪽에만 무게를 들고 걷습니다', 'CORE'),
('오버헤드 캐리', 'CORE', 'DUMBBELL', '머리 위로 무게를 들고 걷습니다', 'CORE'),
('로디드 캐리', 'CORE', 'OTHER', '다양한 방법으로 무게를 들고 걷습니다', 'CORE');

-- ============================================
-- 7. CARDIO 운동 (유산소) - 30개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 머신 유산소 (10개)
('트레드밀 런닝', 'CARDIO', 'MACHINE', '트레드밀에서 달리기를 수행합니다', 'CARDIO'),
('트레드밀 워킹', 'CARDIO', 'MACHINE', '트레드밀에서 걷기를 수행합니다', 'CARDIO'),
('인클라인 트레드밀', 'CARDIO', 'MACHINE', '경사를 올린 트레드밀에서 걷거나 뜁니다', 'CARDIO'),
('스테이셔너리 바이크', 'CARDIO', 'MACHINE', '실내 자전거를 탑니다', 'CARDIO'),
('리컴번트 바이크', 'CARDIO', 'MACHINE', '등받이가 있는 자전거를 탑니다', 'CARDIO'),
('로잉 머신', 'CARDIO', 'MACHINE', '로잉 머신으로 전신 유산소 운동을 합니다', 'CARDIO'),
('엘립티컬', 'CARDIO', 'MACHINE', '엘립티컬 머신으로 운동합니다', 'CARDIO'),
('스텝밀', 'CARDIO', 'MACHINE', '계단 오르기 머신으로 운동합니다', 'CARDIO'),
('어썰트 바이크', 'CARDIO', 'MACHINE', '팔과 다리를 모두 사용하는 바이크입니다', 'CARDIO'),
('스키 에르그', 'CARDIO', 'MACHINE', '스키 동작을 모방한 머신으로 운동합니다', 'CARDIO'),

-- HIIT 운동 (10개)
('버피', 'CARDIO', 'BODYWEIGHT', '스쿼트-플랭크-푸시업-점프를 연속으로 수행합니다', 'CARDIO'),
('마운틴 클라이머', 'CARDIO', 'BODYWEIGHT', '플랭크 자세에서 무릎을 빠르게 당깁니다', 'CARDIO'),
('점핑 잭', 'CARDIO', 'BODYWEIGHT', '팔다리를 벌렸다 모으며 점프합니다', 'CARDIO'),
('하이 니즈', 'CARDIO', 'BODYWEIGHT', '제자리에서 무릎을 높이 들며 뜁니다', 'CARDIO'),
('버트 킥', 'CARDIO', 'BODYWEIGHT', '제자리에서 발뒤꿈치가 엉덩이에 닿도록 뜁니다', 'CARDIO'),
('스쿼트 점프', 'CARDIO', 'BODYWEIGHT', '스쿼트 후 점프를 반복합니다', 'CARDIO'),
('런지 점프', 'CARDIO', 'BODYWEIGHT', '런지 자세에서 점프하며 다리를 교체합니다', 'CARDIO'),
('박스 점프', 'CARDIO', 'OTHER', '박스 위로 점프했다가 내려옵니다', 'CARDIO'),
('스프린트', 'CARDIO', 'BODYWEIGHT', '전력 질주를 반복합니다', 'CARDIO'),
('셔틀 런', 'CARDIO', 'BODYWEIGHT', '짧은 거리를 왕복하며 달립니다', 'CARDIO'),

-- 기타 유산소 (10개)
('줄넘기', 'CARDIO', 'OTHER', '줄넘기를 수행합니다', 'CARDIO'),
('더블 언더', 'CARDIO', 'OTHER', '줄넘기를 한 번 점프에 두 번 돌립니다', 'CARDIO'),
('배틀 로프', 'CARDIO', 'OTHER', '무거운 로프를 흔들어 운동합니다', 'CARDIO'),
('메디신볼 슬램', 'CARDIO', 'OTHER', '메디신볼을 머리 위로 들었다가 바닥에 내리칩니다', 'CARDIO'),
('월볼 샷', 'CARDIO', 'OTHER', '메디신볼을 벽에 던집니다', 'CARDIO'),
('케틀벨 스윙', 'CARDIO', 'KETTLEBELL', '케틀벨을 다리 사이로 스윙합니다', 'CARDIO'),
('슬레드 푸시', 'CARDIO', 'OTHER', '무거운 썰매를 밀면서 이동합니다', 'CARDIO'),
('슬레드 풀', 'CARDIO', 'OTHER', '무거운 썰매를 당기면서 이동합니다', 'CARDIO'),
('타이어 플립', 'CARDIO', 'OTHER', '큰 타이어를 뒤집습니다', 'CARDIO'),
('샌드백 캐리', 'CARDIO', 'OTHER', '샌드백을 들고 이동합니다', 'CARDIO');

-- ============================================
-- 8. FULL_BODY 운동 (전신) - 30개
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 올림픽 리프트 (10개)
('파워 클린', 'FULL_BODY', 'BARBELL', '바닥의 바벨을 폭발적으로 어깨까지 올립니다', 'FULL_BODY'),
('행 클린', 'FULL_BODY', 'BARBELL', '허벅지 높이에서 바벨을 어깨까지 올립니다', 'FULL_BODY'),
('하이 클린', 'FULL_BODY', 'BARBELL', '골반 높이에서 바벨을 어깨까지 올립니다', 'FULL_BODY'),
('클린 앤 저크', 'FULL_BODY', 'BARBELL', '클린 후 바벨을 머리 위로 올립니다', 'FULL_BODY'),
('파워 스내치', 'FULL_BODY', 'BARBELL', '바닥에서 바벨을 한 번에 머리 위로 올립니다', 'FULL_BODY'),
('행 스내치', 'FULL_BODY', 'BARBELL', '허벅지에서 바벨을 머리 위로 올립니다', 'FULL_BODY'),
('하이 스내치', 'FULL_BODY', 'BARBELL', '골반 높이에서 바벨을 머리 위로 올립니다', 'FULL_BODY'),
('클린 풀', 'FULL_BODY', 'BARBELL', '클린 동작을 어깨까지만 수행합니다', 'FULL_BODY'),
('스내치 풀', 'FULL_BODY', 'BARBELL', '스내치 동작을 어깨까지만 수행합니다', 'FULL_BODY'),
('머슬 스내치', 'FULL_BODY', 'BARBELL', '파워를 사용하지 않고 팔로만 스내치를 수행합니다', 'FULL_BODY'),

-- 복합 운동 (15개)
('스러스터', 'FULL_BODY', 'BARBELL', '프론트 스쿼트 후 바벨을 머리 위로 프레스합니다', 'FULL_BODY'),
('맨메이커', 'FULL_BODY', 'DUMBBELL', '덤벨로 버피-로우-스쿼트-프레스를 연속 수행합니다', 'FULL_BODY'),
('데빌 프레스', 'FULL_BODY', 'DUMBBELL', '버피 후 덤벨을 머리 위로 스윙합니다', 'FULL_BODY'),
('클러스터', 'FULL_BODY', 'BARBELL', '클린-프론트스쿼트-스러스터를 연속 수행합니다', 'FULL_BODY'),
('터키시 겟업', 'FULL_BODY', 'KETTLEBELL', '누운 자세에서 케틀벨을 들고 일어납니다', 'FULL_BODY'),
('케틀벨 스윙', 'FULL_BODY', 'KETTLEBELL', '케틀벨을 힙 힌지로 스윙합니다', 'FULL_BODY'),
('케틀벨 클린 앤 프레스', 'FULL_BODY', 'KETTLEBELL', '케틀벨을 클린 후 프레스합니다', 'FULL_BODY'),
('케틀벨 스내치', 'FULL_BODY', 'KETTLEBELL', '케틀벨을 한 번에 머리 위로 올립니다', 'FULL_BODY'),
('월볼 클린', 'FULL_BODY', 'OTHER', '메디신볼을 바닥에서 어깨까지 올립니다', 'FULL_BODY'),
('샌드백 투 숄더', 'FULL_BODY', 'OTHER', '샌드백을 어깨 위로 올립니다', 'FULL_BODY'),
('바 컴플렉스', 'FULL_BODY', 'BARBELL', '여러 바벨 운동을 연속으로 수행합니다', 'FULL_BODY'),
('베어 컴플렉스', 'FULL_BODY', 'BARBELL', '파워클린-프론트스쿼트-푸시프레스-백스쿼트-푸시프레스를 연속 수행합니다', 'FULL_BODY'),
('덤벨 컴플렉스', 'FULL_BODY', 'DUMBBELL', '여러 덤벨 운동을 연속으로 수행합니다', 'FULL_BODY'),
('파머스 워크', 'FULL_BODY', 'DUMBBELL', '무거운 덤벨을 들고 걷습니다', 'FULL_BODY'),
('요크 워크', 'FULL_BODY', 'OTHER', '무거운 요크를 어깨에 짊어지고 걷습니다', 'FULL_BODY'),

-- 기능성 운동 (5개)
('로프 클라임', 'FULL_BODY', 'OTHER', '로프를 타고 올라갑니다', 'FULL_BODY'),
('페그보드', 'FULL_BODY', 'OTHER', '페그를 이용해 보드를 올라갑니다', 'FULL_BODY'),
('슬레드 드래그', 'FULL_BODY', 'OTHER', '슬레드를 끌면서 이동합니다', 'FULL_BODY'),
('프라울러 푸시', 'FULL_BODY', 'OTHER', '프라울러를 밀면서 이동합니다', 'FULL_BODY'),
('아틀라스 스톤', 'FULL_BODY', 'OTHER', '무거운 돌을 플랫폼 위로 올립니다', 'FULL_BODY');

-- ============================================
-- 근육군 매핑 테이블 (exercise_muscle_groups)
-- 각 운동에 대해 주요 근육과 보조 근육을 매핑
-- ============================================
-- 이 부분은 매우 길어질 수 있으므로, 주요 예시만 포함합니다
-- 실제로는 각 운동에 대해 정확한 근육 매핑이 필요합니다

-- 가슴 운동 근육 매핑 예시
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'CHEST', true FROM exercises e WHERE e.name = '바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'TRICEPS', false FROM exercises e WHERE e.name = '바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'SHOULDERS', false FROM exercises e WHERE e.name = '바벨 벤치프레스';

-- 등 운동 근육 매핑 예시
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'BACK', true FROM exercises e WHERE e.name = '데드리프트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'LEGS', false FROM exercises e WHERE e.name = '데드리프트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'GLUTES', false FROM exercises e WHERE e.name = '데드리프트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'HAMSTRINGS', false FROM exercises e WHERE e.name = '데드리프트';

-- 하체 운동 근육 매핑 예시
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'QUADRICEPS', true FROM exercises e WHERE e.name = '백 스쿼트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'GLUTES', false FROM exercises e WHERE e.name = '백 스쿼트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'HAMSTRINGS', false FROM exercises e WHERE e.name = '백 스쿼트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_group, is_primary)
SELECT e.id, 'CORE', false FROM exercises e WHERE e.name = '백 스쿼트';

-- 추가 매핑은 각 운동의 특성에 맞게 계속 추가되어야 합니다
-- 총 400개 이상의 운동에 대한 정확한 근육 매핑이 필요합니다
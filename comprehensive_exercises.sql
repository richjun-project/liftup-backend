-- 포괄적인 운동 데이터베이스 초기화
USE liftupai_db;

-- 기존 데이터 삭제
DELETE FROM exercise_muscle_groups;
DELETE FROM exercises;

-- ============================================
-- CHEST 운동 (가슴)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동
('바벨 벤치프레스', 'CHEST', 'BARBELL', '바벨을 가슴 위로 들어올립니다', '가슴'),
('인클라인 바벨 벤치프레스', 'CHEST', 'BARBELL', '30-45도 경사진 벤치에서 바벨을 들어올립니다', '가슴'),
('디클라인 바벨 벤치프레스', 'CHEST', 'BARBELL', '하향 경사 벤치에서 바벨을 들어올립니다', '가슴'),
('클로즈그립 벤치프레스', 'CHEST', 'BARBELL', '좁은 그립으로 벤치프레스를 수행합니다', '가슴'),
('와이드그립 벤치프레스', 'CHEST', 'BARBELL', '넓은 그립으로 벤치프레스를 수행합니다', '가슴'),

-- 덤벨 운동
('덤벨 벤치프레스', 'CHEST', 'DUMBBELL', '덤벨을 가슴 위로 들어올립니다', '가슴'),
('인클라인 덤벨 프레스', 'CHEST', 'DUMBBELL', '경사진 벤치에서 덤벨을 들어올립니다', '가슴'),
('디클라인 덤벨 프레스', 'CHEST', 'DUMBBELL', '하향 경사에서 덤벨을 들어올립니다', '가슴'),
('덤벨 플라이', 'CHEST', 'DUMBBELL', '덤벨을 양옆으로 벌렸다가 모읍니다', '가슴'),
('인클라인 덤벨 플라이', 'CHEST', 'DUMBBELL', '경사진 벤치에서 플라이를 수행합니다', '가슴'),
('덤벨 풀오버', 'CHEST', 'DUMBBELL', '덤벨을 머리 뒤로 내렸다가 올립니다', '가슴'),

-- 머신/케이블 운동
('체스트 프레스 머신', 'CHEST', 'MACHINE', '머신으로 가슴 프레스를 수행합니다', '가슴'),
('펙덱 플라이', 'CHEST', 'MACHINE', '펙덱 머신으로 가슴을 모읍니다', '가슴'),
('케이블 크로스오버', 'CHEST', 'CABLE', '케이블을 교차하여 가슴을 수축합니다', '가슴'),
('케이블 플라이', 'CHEST', 'CABLE', '케이블로 플라이 동작을 수행합니다', '가슴'),
('로우 케이블 크로스오버', 'CHEST', 'CABLE', '아래에서 위로 케이블을 당깁니다', '가슴'),
('하이 케이블 크로스오버', 'CHEST', 'CABLE', '위에서 아래로 케이블을 당깁니다', '가슴'),

-- 맨몸 운동
('푸시업', 'CHEST', 'BODYWEIGHT', '팔굽혀펴기를 수행합니다', '가슴'),
('와이드 푸시업', 'CHEST', 'BODYWEIGHT', '넓은 간격으로 푸시업을 수행합니다', '가슴'),
('다이아몬드 푸시업', 'CHEST', 'BODYWEIGHT', '손을 다이아몬드 모양으로 하여 푸시업', '가슴'),
('인클라인 푸시업', 'CHEST', 'BODYWEIGHT', '상체를 높인 상태로 푸시업', '가슴'),
('디클라인 푸시업', 'CHEST', 'BODYWEIGHT', '발을 높인 상태로 푸시업', '가슴'),
('딥스', 'CHEST', 'BODYWEIGHT', '평행봉에서 몸을 내렸다 올립니다', '가슴'),
('체스트 딥스', 'CHEST', 'BODYWEIGHT', '상체를 앞으로 기울여 딥스를 수행합니다', '가슴'),

-- ============================================
-- BACK 운동 (등)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동
('데드리프트', 'BACK', 'BARBELL', '바닥에서 바벨을 들어올립니다', '등'),
('컨벤셔널 데드리프트', 'BACK', 'BARBELL', '일반적인 데드리프트를 수행합니다', '등'),
('루마니안 데드리프트', 'BACK', 'BARBELL', '무릎을 살짝 굽힌 상태로 수행합니다', '등'),
('스티프 레그 데드리프트', 'BACK', 'BARBELL', '다리를 곧게 편 상태로 수행합니다', '등'),
('스모 데드리프트', 'BACK', 'BARBELL', '다리를 넓게 벌려 데드리프트', '등'),
('바벨 로우', 'BACK', 'BARBELL', '바벨을 배꼽쪽으로 당깁니다', '등'),
('벤트오버 로우', 'BACK', 'BARBELL', '상체를 숙인 상태로 바벨을 당깁니다', '등'),
('펜들레이 로우', 'BACK', 'BARBELL', '바닥에서 시작하는 폭발적인 로우', '등'),
('티바 로우', 'BACK', 'BARBELL', '티바를 사용한 로우 운동', '등'),
('언더그립 바벨 로우', 'BACK', 'BARBELL', '언더그립으로 바벨 로우 수행', '등'),

-- 덤벨 운동
('덤벨 로우', 'BACK', 'DUMBBELL', '한쪽씩 덤벨을 당깁니다', '등'),
('원암 덤벨 로우', 'BACK', 'DUMBBELL', '한 팔로 덤벨 로우를 수행합니다', '등'),
('투암 덤벨 로우', 'BACK', 'DUMBBELL', '양손으로 덤벨 로우를 수행합니다', '등'),
('덤벨 데드리프트', 'BACK', 'DUMBBELL', '덤벨로 데드리프트를 수행합니다', '등'),
('렌느게이드 로우', 'BACK', 'DUMBBELL', '플랭크 자세에서 로우를 수행합니다', '등'),

-- 머신/케이블 운동
('랫 풀다운', 'BACK', 'MACHINE', '위에서 아래로 바를 당깁니다', '등'),
('와이드 그립 랫 풀다운', 'BACK', 'MACHINE', '넓은 그립으로 랫 풀다운', '등'),
('클로즈 그립 랫 풀다운', 'BACK', 'MACHINE', '좁은 그립으로 랫 풀다운', '등'),
('언더그립 랫 풀다운', 'BACK', 'MACHINE', '언더그립으로 랫 풀다운', '등'),
('비하인드 넥 랫 풀다운', 'BACK', 'MACHINE', '목 뒤로 바를 당깁니다', '등'),
('케이블 로우', 'BACK', 'CABLE', '케이블을 가슴쪽으로 당깁니다', '등'),
('시티드 케이블 로우', 'BACK', 'CABLE', '앉아서 케이블 로우를 수행합니다', '등'),
('원암 케이블 로우', 'BACK', 'CABLE', '한 팔로 케이블 로우', '등'),
('페이스 풀', 'BACK', 'CABLE', '케이블을 얼굴쪽으로 당깁니다', '등'),
('스트레이트 암 풀다운', 'BACK', 'CABLE', '팔을 곧게 편 상태로 풀다운', '등'),
('케이블 풀오버', 'BACK', 'CABLE', '케이블로 풀오버를 수행합니다', '등'),

-- 맨몸 운동
('풀업', 'BACK', 'BODYWEIGHT', '턱걸이를 수행합니다', '등'),
('친업', 'BACK', 'BODYWEIGHT', '언더그립으로 턱걸이를 수행합니다', '등'),
('와이드 그립 풀업', 'BACK', 'BODYWEIGHT', '넓은 그립으로 풀업', '등'),
('내로우 그립 풀업', 'BACK', 'BODYWEIGHT', '좁은 그립으로 풀업', '등'),
('뉴트럴 그립 풀업', 'BACK', 'BODYWEIGHT', '중립 그립으로 풀업', '등'),
('어시스트 풀업', 'BACK', 'MACHINE', '보조 머신으로 풀업', '등'),

-- ============================================
-- LEGS 운동 (하체)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동
('바벨 스쿼트', 'LEGS', 'BARBELL', '바벨을 어깨에 얹고 스쿼트를 수행합니다', '하체'),
('백 스쿼트', 'LEGS', 'BARBELL', '바벨을 등 뒤에 얹고 스쿼트', '하체'),
('프론트 스쿼트', 'LEGS', 'BARBELL', '바벨을 앞쪽 어깨에 얹고 스쿼트', '하체'),
('하이바 스쿼트', 'LEGS', 'BARBELL', '바벨을 승모근 위쪽에 얹고 스쿼트', '하체'),
('로우바 스쿼트', 'LEGS', 'BARBELL', '바벨을 어깨 뒤 낮게 얹고 스쿼트', '하체'),
('박스 스쿼트', 'LEGS', 'BARBELL', '박스에 앉았다가 일어나는 스쿼트', '하체'),
('포즈 스쿼트', 'LEGS', 'BARBELL', '바닥에서 잠시 멈춘 후 일어나는 스쿼트', '하체'),
('굿모닝', 'LEGS', 'BARBELL', '바벨을 어깨에 얹고 상체를 숙입니다', '하체'),

-- 덤벨 운동
('덤벨 스쿼트', 'LEGS', 'DUMBBELL', '덤벨을 들고 스쿼트를 수행합니다', '하체'),
('고블릿 스쿼트', 'LEGS', 'DUMBBELL', '덤벨을 가슴 앞에 들고 스쿼트', '하체'),
('덤벨 런지', 'LEGS', 'DUMBBELL', '덤벨을 들고 런지를 수행합니다', '하체'),
('워킹 런지', 'LEGS', 'DUMBBELL', '걸으면서 런지를 수행합니다', '하체'),
('리버스 런지', 'LEGS', 'DUMBBELL', '뒤로 런지를 수행합니다', '하체'),
('사이드 런지', 'LEGS', 'DUMBBELL', '옆으로 런지를 수행합니다', '하체'),
('불가리안 스플릿 스쿼트', 'LEGS', 'DUMBBELL', '뒷발을 벤치에 올리고 스쿼트', '하체'),
('스텝업', 'LEGS', 'DUMBBELL', '박스나 벤치를 올라갑니다', '하체'),
('덤벨 루마니안 데드리프트', 'LEGS', 'DUMBBELL', '덤벨로 루마니안 데드리프트', '하체'),

-- 머신 운동
('레그 프레스', 'LEGS', 'MACHINE', '다리로 무게를 밀어냅니다', '하체'),
('45도 레그 프레스', 'LEGS', 'MACHINE', '45도 각도로 레그 프레스', '하체'),
('수평 레그 프레스', 'LEGS', 'MACHINE', '수평으로 레그 프레스', '하체'),
('레그 익스텐션', 'LEGS', 'MACHINE', '앉아서 다리를 펴줍니다', '하체'),
('레그 컬', 'LEGS', 'MACHINE', '누워서 다리를 구부립니다', '하체'),
('시티드 레그 컬', 'LEGS', 'MACHINE', '앉아서 레그 컬을 수행합니다', '하체'),
('스탠딩 레그 컬', 'LEGS', 'MACHINE', '서서 한쪽 다리씩 레그 컬', '하체'),
('핵 스쿼트', 'LEGS', 'MACHINE', '핵 스쿼트 머신을 사용합니다', '하체'),
('스미스 머신 스쿼트', 'LEGS', 'MACHINE', '스미스 머신으로 스쿼트', '하체'),
('카프 레이즈 머신', 'LEGS', 'MACHINE', '종아리 운동 머신', '하체'),

-- 종아리 운동
('스탠딩 카프 레이즈', 'LEGS', 'BARBELL', '서서 발꿈치를 들어올립니다', '하체'),
('시티드 카프 레이즈', 'LEGS', 'MACHINE', '앉아서 발꿈치를 들어올립니다', '하체'),
('덤벨 카프 레이즈', 'LEGS', 'DUMBBELL', '덤벨을 들고 카프 레이즈', '하체'),
('원레그 카프 레이즈', 'LEGS', 'DUMBBELL', '한 다리로 카프 레이즈', '하체'),
('동키 카프 레이즈', 'LEGS', 'MACHINE', '동키 카프 레이즈 머신 사용', '하체'),

-- 맨몸 운동
('에어 스쿼트', 'LEGS', 'BODYWEIGHT', '맨몸으로 스쿼트를 수행합니다', '하체'),
('점프 스쿼트', 'LEGS', 'BODYWEIGHT', '스쿼트 후 점프합니다', '하체'),
('피스톨 스쿼트', 'LEGS', 'BODYWEIGHT', '한 다리로 스쿼트', '하체'),
('런지', 'LEGS', 'BODYWEIGHT', '맨몸으로 런지를 수행합니다', '하체'),
('점프 런지', 'LEGS', 'BODYWEIGHT', '런지 후 점프하여 다리 교체', '하체'),
('월 싯', 'LEGS', 'BODYWEIGHT', '벽에 기대어 앉은 자세 유지', '하체'),

-- ============================================
-- SHOULDERS 운동 (어깨)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 바벨 운동
('바벨 숄더 프레스', 'SHOULDERS', 'BARBELL', '바벨을 머리 위로 들어올립니다', '어깨'),
('밀리터리 프레스', 'SHOULDERS', 'BARBELL', '서서 바벨을 머리 위로 프레스', '어깨'),
('비하인드 넥 프레스', 'SHOULDERS', 'BARBELL', '목 뒤에서 바벨을 프레스', '어깨'),
('푸시 프레스', 'SHOULDERS', 'BARBELL', '다리 반동을 이용한 프레스', '어깨'),
('업라이트 로우', 'SHOULDERS', 'BARBELL', '바벨을 턱까지 끌어올립니다', '어깨'),
('바벨 프론트 레이즈', 'SHOULDERS', 'BARBELL', '바벨을 앞으로 들어올립니다', '어깨'),
('바벨 슈러그', 'SHOULDERS', 'BARBELL', '어깨를 으쓱하는 동작', '어깨'),

-- 덤벨 운동
('덤벨 숄더 프레스', 'SHOULDERS', 'DUMBBELL', '덤벨을 머리 위로 들어올립니다', '어깨'),
('시티드 덤벨 프레스', 'SHOULDERS', 'DUMBBELL', '앉아서 덤벨 프레스', '어깨'),
('스탠딩 덤벨 프레스', 'SHOULDERS', 'DUMBBELL', '서서 덤벨 프레스', '어깨'),
('아놀드 프레스', 'SHOULDERS', 'DUMBBELL', '회전하면서 프레스', '어깨'),
('덤벨 사이드 레터럴 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 옆으로 들어올립니다', '어깨'),
('덤벨 프론트 레이즈', 'SHOULDERS', 'DUMBBELL', '덤벨을 앞으로 들어올립니다', '어깨'),
('덤벨 리어 델트 플라이', 'SHOULDERS', 'DUMBBELL', '뒤쪽 어깨를 자극합니다', '어깨'),
('벤트오버 리어 델트 레이즈', 'SHOULDERS', 'DUMBBELL', '숙인 자세로 리어 델트', '어깨'),
('덤벨 슈러그', 'SHOULDERS', 'DUMBBELL', '덤벨로 슈러그', '어깨'),
('덤벨 업라이트 로우', 'SHOULDERS', 'DUMBBELL', '덤벨을 턱까지 끌어올립니다', '어깨'),

-- 케이블/머신 운동
('케이블 사이드 레터럴 레이즈', 'SHOULDERS', 'CABLE', '케이블로 사이드 레이즈', '어깨'),
('케이블 프론트 레이즈', 'SHOULDERS', 'CABLE', '케이블로 프론트 레이즈', '어깨'),
('케이블 리어 델트 플라이', 'SHOULDERS', 'CABLE', '케이블로 리어 델트', '어깨'),
('페이스 풀', 'SHOULDERS', 'CABLE', '케이블을 얼굴쪽으로 당깁니다', '어깨'),
('케이블 업라이트 로우', 'SHOULDERS', 'CABLE', '케이블로 업라이트 로우', '어깨'),
('숄더 프레스 머신', 'SHOULDERS', 'MACHINE', '머신으로 숄더 프레스', '어깨'),
('리어 델트 머신', 'SHOULDERS', 'MACHINE', '리어 델트 전용 머신', '어깨'),

-- ============================================
-- ARMS 운동 (팔)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 이두근 (Biceps)
('바벨 컬', 'ARMS', 'BARBELL', '바벨을 구부려 올립니다', '팔'),
('EZ바 컬', 'ARMS', 'BARBELL', 'EZ바로 컬을 수행합니다', '팔'),
('프리처 컬', 'ARMS', 'BARBELL', '팔을 고정하고 바벨을 들어올립니다', '팔'),
('리버스 컬', 'ARMS', 'BARBELL', '오버그립으로 컬을 수행합니다', '팔'),
('덤벨 컬', 'ARMS', 'DUMBBELL', '덤벨로 컬을 수행합니다', '팔'),
('해머 컬', 'ARMS', 'DUMBBELL', '덤벨을 해머 그립으로 들어올립니다', '팔'),
('얼터네이팅 덤벨 컬', 'ARMS', 'DUMBBELL', '교대로 덤벨 컬', '팔'),
('인클라인 덤벨 컬', 'ARMS', 'DUMBBELL', '경사 벤치에서 덤벨 컬', '팔'),
('컨센트레이션 컬', 'ARMS', 'DUMBBELL', '집중 컬을 수행합니다', '팔'),
('스파이더 컬', 'ARMS', 'DUMBBELL', '프리처 벤치 반대편에서 컬', '팔'),
('케이블 컬', 'ARMS', 'CABLE', '케이블로 컬을 수행합니다', '팔'),
('케이블 해머 컬', 'ARMS', 'CABLE', '케이블로 해머 컬', '팔'),
('하이 케이블 컬', 'ARMS', 'CABLE', '위에서 케이블 컬', '팔'),

-- 삼두근 (Triceps)
('클로즈그립 벤치프레스', 'ARMS', 'BARBELL', '좁은 그립으로 벤치프레스', '팔'),
('바벨 트라이셉스 익스텐션', 'ARMS', 'BARBELL', '바벨로 삼두 익스텐션', '팔'),
('오버헤드 트라이셉스 익스텐션', 'ARMS', 'BARBELL', '머리 위로 바벨 익스텐션', '팔'),
('덤벨 트라이셉스 익스텐션', 'ARMS', 'DUMBBELL', '덤벨로 삼두 익스텐션', '팔'),
('덤벨 킥백', 'ARMS', 'DUMBBELL', '덤벨을 뒤로 킥백', '팔'),
('오버헤드 덤벨 익스텐션', 'ARMS', 'DUMBBELL', '머리 위로 덤벨 익스텐션', '팔'),
('케이블 푸시다운', 'ARMS', 'CABLE', '케이블을 아래로 밀어내립니다', '팔'),
('로프 푸시다운', 'ARMS', 'CABLE', '로프로 푸시다운', '팔'),
('오버헤드 케이블 익스텐션', 'ARMS', 'CABLE', '케이블로 오버헤드 익스텐션', '팔'),
('트라이셉스 딥스', 'ARMS', 'BODYWEIGHT', '삼두근 중심 딥스', '팔'),
('벤치 딥스', 'ARMS', 'BODYWEIGHT', '벤치에서 딥스', '팔'),
('다이아몬드 푸시업', 'ARMS', 'BODYWEIGHT', '다이아몬드 그립 푸시업', '팔'),

-- 전완근 (Forearms)
('리스트 컬', 'ARMS', 'BARBELL', '손목을 구부려 올립니다', '팔'),
('리버스 리스트 컬', 'ARMS', 'BARBELL', '손목을 반대로 구부립니다', '팔'),
('파머스 워크', 'ARMS', 'DUMBBELL', '무거운 덤벨을 들고 걷기', '팔'),

-- ============================================
-- CORE 운동 (코어/복근)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
-- 상복부
('크런치', 'CORE', 'BODYWEIGHT', '상체를 구부려 복근을 수축합니다', '복근'),
('싯업', 'CORE', 'BODYWEIGHT', '완전히 일어나는 복근 운동', '복근'),
('케이블 크런치', 'CORE', 'CABLE', '케이블로 크런치를 수행합니다', '복근'),
('머신 크런치', 'CORE', 'MACHINE', '크런치 머신을 사용합니다', '복근'),
('디클라인 크런치', 'CORE', 'BODYWEIGHT', '경사 벤치에서 크런치', '복근'),
('볼 크런치', 'CORE', 'OTHER', '짐볼 위에서 크런치', '복근'),

-- 하복부
('레그레이즈', 'CORE', 'BODYWEIGHT', '다리를 들어올려 복근을 자극합니다', '복근'),
('행잉 레그레이즈', 'CORE', 'BODYWEIGHT', '매달려서 다리를 들어올립니다', '복근'),
('니업', 'CORE', 'BODYWEIGHT', '무릎을 가슴쪽으로 당깁니다', '복근'),
('행잉 니업', 'CORE', 'BODYWEIGHT', '매달려서 무릎을 당깁니다', '복근'),
('리버스 크런치', 'CORE', 'BODYWEIGHT', '하체를 들어올리는 크런치', '복근'),
('시저 킥', 'CORE', 'BODYWEIGHT', '다리를 교차하며 움직입니다', '복근'),

-- 옆구리/회전
('러시안 트위스트', 'CORE', 'BODYWEIGHT', '상체를 좌우로 비틉니다', '복근'),
('사이드 크런치', 'CORE', 'BODYWEIGHT', '옆구리 크런치', '복근'),
('오블리크 크런치', 'CORE', 'BODYWEIGHT', '사선으로 크런치', '복근'),
('바이시클 크런치', 'CORE', 'BODYWEIGHT', '자전거 타듯이 복근운동', '복근'),
('우드챱', 'CORE', 'CABLE', '케이블로 나무 찍기 동작', '복근'),
('케이블 사이드 벤드', 'CORE', 'CABLE', '옆구리를 굽히는 운동', '복근'),

-- 코어 안정화
('플랭크', 'CORE', 'BODYWEIGHT', '팔꿈치와 발끝으로 버팁니다', '복근'),
('사이드 플랭크', 'CORE', 'BODYWEIGHT', '옆으로 플랭크', '복근'),
('플랭크 업다운', 'CORE', 'BODYWEIGHT', '플랭크에서 팔을 번갈아 폅니다', '복근'),
('마운틴 클라이머', 'CORE', 'BODYWEIGHT', '플랭크 자세에서 무릎 당기기', '복근'),
('버드독', 'CORE', 'BODYWEIGHT', '반대 팔다리를 들어올립니다', '복근'),
('데드버그', 'CORE', 'BODYWEIGHT', '누워서 팔다리를 교대로 움직입니다', '복근'),
('홀로우 홀드', 'CORE', 'BODYWEIGHT', '배를 움푹 들어간 자세 유지', '복근'),
('AB 롤아웃', 'CORE', 'OTHER', 'AB휠을 굴려 복근을 자극합니다', '복근'),
('팔로프 프레스', 'CORE', 'CABLE', '케이블을 앞으로 밀며 코어 안정화', '복근'),

-- ============================================
-- CARDIO 운동 (유산소)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
('트레드밀 런닝', 'CARDIO', 'MACHINE', '러닝머신에서 달리기', '유산소'),
('트레드밀 워킹', 'CARDIO', 'MACHINE', '러닝머신에서 걷기', '유산소'),
('인클라인 워킹', 'CARDIO', 'MACHINE', '경사를 올려 걷기', '유산소'),
('사이클', 'CARDIO', 'MACHINE', '실내 자전거 타기', '유산소'),
('로잉머신', 'CARDIO', 'MACHINE', '로잉 머신으로 전신 유산소', '유산소'),
('엘립티컬', 'CARDIO', 'MACHINE', '엘립티컬 머신 운동', '유산소'),
('스텝퍼', 'CARDIO', 'MACHINE', '계단 오르기 머신', '유산소'),
('버피', 'CARDIO', 'BODYWEIGHT', '전신을 사용한 버피 운동', '유산소'),
('점핑잭', 'CARDIO', 'BODYWEIGHT', '팔다리를 벌렸다 모으기', '유산소'),
('하이니스', 'CARDIO', 'BODYWEIGHT', '제자리에서 무릎 높이 들기', '유산소'),
('로프 점핑', 'CARDIO', 'OTHER', '줄넘기', '유산소'),
('배틀로프', 'CARDIO', 'OTHER', '배틀로프 웨이브', '유산소'),
('박스 점프', 'CARDIO', 'OTHER', '박스 위로 점프', '유산소'),

-- ============================================
-- FULL_BODY 운동 (전신)
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES
('클린', 'FULL_BODY', 'BARBELL', '바닥에서 어깨까지 바벨을 들어올립니다', '전신'),
('파워 클린', 'FULL_BODY', 'BARBELL', '폭발적으로 바벨을 들어올립니다', '전신'),
('행 클린', 'FULL_BODY', 'BARBELL', '무릎 높이에서 시작하는 클린', '전신'),
('클린 앤 저크', 'FULL_BODY', 'BARBELL', '클린 후 머리 위로 저크', '전신'),
('스내치', 'FULL_BODY', 'BARBELL', '한 동작으로 머리 위까지 바벨 들기', '전신'),
('파워 스내치', 'FULL_BODY', 'BARBELL', '폭발적인 스내치', '전신'),
('스러스터', 'FULL_BODY', 'BARBELL', '프론트 스쿼트 후 프레스', '전신'),
('맨메이커', 'FULL_BODY', 'DUMBBELL', '버피+로우+스쿼트 프레스 복합운동', '전신'),
('터키시 겟업', 'FULL_BODY', 'KETTLEBELL', '누운 자세에서 일어나기', '전신'),
('케틀벨 스윙', 'FULL_BODY', 'KETTLEBELL', '케틀벨을 스윙하는 운동', '전신'),
('월볼 슬램', 'FULL_BODY', 'OTHER', '메디신볼을 바닥에 내리치기', '전신'),
('타이어 플립', 'FULL_BODY', 'OTHER', '타이어 뒤집기', '전신');

-- ============================================
-- 운동별 근육군 매핑 (세부 근육까지 포함)
-- ============================================

-- CHEST 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.category = 'CHEST';

-- 상부 가슴 중점 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'UPPER_CHEST' FROM exercises e WHERE e.name LIKE '%인클라인%';

-- 중부 가슴 중점 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'MIDDLE_CHEST' FROM exercises e WHERE e.name IN ('바벨 벤치프레스', '덤벨 벤치프레스', '체스트 프레스 머신', '푸시업');

-- 하부 가슴 중점 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LOWER_CHEST' FROM exercises e WHERE e.name LIKE '%디클라인%' OR e.name LIKE '%딥스%';

-- BACK 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.category = 'BACK';

-- 광배근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'UPPER_LATS' FROM exercises e WHERE e.name LIKE '%풀다운%' OR e.name LIKE '%풀업%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LOWER_LATS' FROM exercises e WHERE e.name LIKE '%로우%';

-- 승모근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'UPPER_TRAPS' FROM exercises e WHERE e.name LIKE '%슈러그%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'MIDDLE_TRAPS' FROM exercises e WHERE e.name LIKE '%로우%' OR e.name LIKE '%페이스 풀%';

-- 척추기립근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ERECTOR_SPINAE' FROM exercises e WHERE e.name LIKE '%데드리프트%' OR e.name LIKE '%굿모닝%';

-- SHOULDERS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.category = 'SHOULDERS';

-- 전면 삼각근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FRONT_DELTS' FROM exercises e WHERE e.name LIKE '%프론트 레이즈%' OR e.name LIKE '%프레스%';

-- 측면 삼각근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SIDE_DELTS' FROM exercises e WHERE e.name LIKE '%사이드%' OR e.name LIKE '%레터럴%';

-- 후면 삼각근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'REAR_DELTS' FROM exercises e WHERE e.name LIKE '%리어%' OR e.name LIKE '%페이스 풀%';

-- ARMS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name LIKE '%컬%' AND e.category = 'ARMS';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name LIKE '%익스텐션%' OR e.name LIKE '%푸시다운%' OR e.name LIKE '%킥백%';

-- 상완근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BRACHIALIS' FROM exercises e WHERE e.name LIKE '%해머%';

-- 전완근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e WHERE e.name LIKE '%리스트%' OR e.name LIKE '%파머스%';

-- LEGS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name LIKE '%스쿼트%' OR e.name LIKE '%레그 익스텐션%' OR e.name LIKE '%레그 프레스%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name LIKE '%레그 컬%' OR e.name LIKE '%루마니안%' OR e.name LIKE '%스티프%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name LIKE '%스쿼트%' OR e.name LIKE '%런지%' OR e.name LIKE '%데드리프트%' AND e.category = 'LEGS';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CALVES' FROM exercises e WHERE e.name LIKE '%카프%';

-- CORE 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.category = 'CORE';

-- 상복부
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'UPPER_ABS' FROM exercises e WHERE e.name LIKE '%크런치%' OR e.name LIKE '%싯업%';

-- 하복부
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LOWER_ABS' FROM exercises e WHERE e.name LIKE '%레그레이즈%' OR e.name LIKE '%니업%';

-- 복사근
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'OBLIQUES' FROM exercises e WHERE e.name LIKE '%트위스트%' OR e.name LIKE '%사이드%' OR e.name LIKE '%우드챱%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e
WHERE e.category = 'CHEST' AND (e.name LIKE '%프레스%' OR e.name LIKE '%딥스%' OR e.name LIKE '%푸시업%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e
WHERE e.category = 'CHEST' AND e.name LIKE '%인클라인%';

-- BACK 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.category = 'BACK';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e
WHERE e.category = 'BACK' AND (e.name LIKE '%로우%' OR e.name LIKE '%풀%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e
WHERE e.category = 'BACK' AND e.name LIKE '%데드리프트%';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e
WHERE e.category = 'BACK' AND e.name LIKE '%데드리프트%';

-- LEGS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e
WHERE e.category = 'LEGS' AND (e.name LIKE '%스쿼트%' OR e.name LIKE '%프레스%' OR e.name LIKE '%익스텐션%' OR e.name LIKE '%런지%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e
WHERE e.category = 'LEGS' AND (e.name LIKE '%컬%' OR e.name LIKE '%데드리프트%' OR e.name LIKE '%굿모닝%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e
WHERE e.category = 'LEGS' AND (e.name LIKE '%스쿼트%' OR e.name LIKE '%런지%' OR e.name LIKE '%데드리프트%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CALVES' FROM exercises e
WHERE e.category = 'LEGS' AND e.name LIKE '%카프%';

-- SHOULDERS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.category = 'SHOULDERS';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e
WHERE e.category = 'SHOULDERS' AND e.name LIKE '%프레스%';

-- ARMS 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e
WHERE e.category = 'ARMS' AND (e.name LIKE '%컬%' AND e.name NOT LIKE '%트라이셉%' AND e.name NOT LIKE '%리스트%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e
WHERE e.category = 'ARMS' AND (e.name LIKE '%트라이셉%' OR e.name LIKE '%익스텐션%' OR e.name LIKE '%푸시다운%' OR e.name LIKE '%킥백%' OR e.name LIKE '%클로즈그립%');

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e
WHERE e.category = 'ARMS' AND (e.name LIKE '%리스트%' OR e.name LIKE '%파머스%');

-- CORE 운동 근육 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.category = 'CORE';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'OBLIQUES' FROM exercises e
WHERE e.category = 'CORE' AND (e.name LIKE '%사이드%' OR e.name LIKE '%트위스트%' OR e.name LIKE '%오블리크%' OR e.name LIKE '%우드챱%');

-- FULL_BODY 운동은 여러 근육군 매핑
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.category = 'FULL_BODY';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.category = 'FULL_BODY';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.category = 'FULL_BODY';

INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.category = 'FULL_BODY';
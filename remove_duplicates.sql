-- 중복된 운동 제거를 위한 SQL
-- 중복된 운동들을 찾아서 하나만 남기고 삭제
USE liftupai_db;

-- 중복 운동 목록과 제거 계획
-- 다음 운동들이 여러 카테고리에 중복되어 있음:

-- 1. 다이아몬드 푸시업 - CHEST와 ARMS에 중복
--    -> ARMS 카테고리의 것을 제거 (CHEST가 더 적합)

-- 2. 덤벨 풀오버 - CHEST와 BACK에 중복
--    -> 둘 다 적합하므로 하나만 유지

-- 3. 리버스그립 벤치프레스 - CHEST와 ARMS에 중복
--    -> ARMS 카테고리 제거 (CHEST가 주요)

-- 4. 마운틴 클라이머 - CORE와 CARDIO에 중복
--    -> CARDIO 제거 (CORE가 주요)

-- 5. 케틀벨 스윙 - CARDIO와 FULL_BODY에 중복
--    -> CARDIO 제거 (FULL_BODY가 더 적합)

-- 6. 터키시 겟업 - CORE와 FULL_BODY에 중복
--    -> CORE 제거 (FULL_BODY가 더 적합)

-- 7. 파머스 워크 - ARMS와 FULL_BODY에 중복
--    -> ARMS 제거 (FULL_BODY가 더 적합)

-- 8. 클로즈그립 벤치프레스 - CHEST와 ARMS에 중복
--    -> ARMS의 '클로즈그립 벤치프레스' 제거

-- 9. 박스 점프 - LEGS와 CARDIO에 중복
--    -> CARDIO 제거 (LEGS가 주요)

-- 10. 파이크 푸시업 - CHEST와 SHOULDERS에 중복
--    -> CHEST 제거 (SHOULDERS가 주요)

-- 중복 제거 쿼리
DELETE FROM exercises
WHERE name = '다이아몬드 푸시업' AND category = 'ARMS';

DELETE FROM exercises
WHERE name = '덤벨 풀오버' AND category = 'BACK';

DELETE FROM exercises
WHERE name = '리버스그립 벤치프레스' AND category = 'ARMS';

DELETE FROM exercises
WHERE name = '마운틴 클라이머' AND category = 'CARDIO';

DELETE FROM exercises
WHERE name = '케틀벨 스윙' AND category = 'CARDIO';

DELETE FROM exercises
WHERE name = '터키시 겟업' AND category = 'CORE';

DELETE FROM exercises
WHERE name = '파머스 워크' AND category = 'ARMS';

DELETE FROM exercises
WHERE name = '클로즈그립 벤치프레스' AND category = 'ARMS';

DELETE FROM exercises
WHERE name = '박스 점프' AND category = 'CARDIO';

DELETE FROM exercises
WHERE name = '파이크 푸시업' AND category = 'CHEST';

DELETE FROM exercises
WHERE name = '싱글암 덤벨 프레스' AND category = 'CHEST';

DELETE FROM exercises
WHERE name = '아처 푸시업' AND category = 'CHEST';

DELETE FROM exercises
WHERE name = '오버헤드 캐리' AND category = 'CORE';

DELETE FROM exercises
WHERE name = '케틀벨 윈드밀' AND category = 'SHOULDERS';

DELETE FROM exercises
WHERE name = '랜드마인 프레스' AND category = 'SHOULDERS'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = '랜드마인 프레스' AND category = 'SHOULDERS') AS temp);

DELETE FROM exercises
WHERE name = '랜드마인 스쿼트' AND category = 'LEGS'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = '랜드마인 스쿼트' AND category = 'LEGS') AS temp);

DELETE FROM exercises
WHERE name = '리버스 크런치' AND category = 'CORE'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = '리버스 크런치' AND category = 'CORE') AS temp);

DELETE FROM exercises
WHERE name = '밴드 스쿼트' AND category = 'LEGS'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = '밴드 스쿼트' AND category = 'LEGS') AS temp);

DELETE FROM exercises
WHERE name = '플레이트 컬' AND category = 'ARMS'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = '플레이트 컬' AND category = 'ARMS') AS temp);

DELETE FROM exercises
WHERE name = '플랭크 잭' AND category = 'CARDIO';

DELETE FROM exercises
WHERE name = 'TRX 니 턱' AND category = 'CORE'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = 'TRX 니 턱' AND category = 'CORE') AS temp);

DELETE FROM exercises
WHERE name = 'TRX 파이크' AND category = 'CORE'
AND id NOT IN (SELECT MIN(id) FROM (SELECT id FROM exercises WHERE name = 'TRX 파이크' AND category = 'CORE') AS temp);

-- 중복 제거 후 확인
SELECT name, COUNT(*) as count, GROUP_CONCAT(category) as categories
FROM exercises
GROUP BY name
HAVING COUNT(*) > 1;
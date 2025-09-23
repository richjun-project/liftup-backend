-- 중복 운동 확인 쿼리
USE liftupai_db;

-- 1. 이름이 완전히 같은 중복 운동 찾기
SELECT name, COUNT(*) as count, GROUP_CONCAT(category ORDER BY category) as categories
FROM exercises
GROUP BY name
HAVING COUNT(*) > 1
ORDER BY count DESC, name;

-- 2. 전체 운동 개수 확인
SELECT COUNT(*) as total_exercises FROM exercises;

-- 3. 카테고리별 운동 개수
SELECT category, COUNT(*) as count
FROM exercises
GROUP BY category
ORDER BY category;

-- 4. 중복 운동 상세 정보
SELECT e1.name, e1.category, e1.equipment, e1.id
FROM exercises e1
WHERE e1.name IN (
    SELECT name
    FROM exercises
    GROUP BY name
    HAVING COUNT(*) > 1
)
ORDER BY e1.name, e1.category;
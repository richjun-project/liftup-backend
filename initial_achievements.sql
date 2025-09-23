-- 업적 시스템 초기 데이터 초기화 SQL
-- 기존 사용자들을 위한 업적 소급 적용

-- 운동 횟수 업적 소급 적용
-- 첫 운동 완료 업적
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    u.id,
    '첫 운동 완료',
    '운동 여정의 첫 걸음을 내딛었습니다!',
    '🌟',
    'MILESTONE',
    MIN(ws.start_time)
FROM users u
INNER JOIN workout_sessions ws ON u.id = ws.user_id
WHERE ws.status = 'COMPLETED'
GROUP BY u.id
ON CONFLICT DO NOTHING;

-- 10회 운동 업적
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT
    u.id,
    '운동 10회 달성',
    '꾸준함이 습관이 되고 있습니다!',
    '💪',
    'WORKOUT_COUNT',
    (SELECT ws2.start_time
     FROM workout_sessions ws2
     WHERE ws2.user_id = u.id AND ws2.status = 'COMPLETED'
     ORDER BY ws2.start_time
     LIMIT 1 OFFSET 9)
FROM users u
WHERE (SELECT COUNT(*) FROM workout_sessions ws WHERE ws.user_id = u.id AND ws.status = 'COMPLETED') >= 10
ON CONFLICT DO NOTHING;

-- 50회 운동 업적
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT
    u.id,
    '운동 50회 달성',
    '반백 운동! 당신은 진정한 운동인입니다!',
    '🏅',
    'WORKOUT_COUNT',
    (SELECT ws2.start_time
     FROM workout_sessions ws2
     WHERE ws2.user_id = u.id AND ws2.status = 'COMPLETED'
     ORDER BY ws2.start_time
     LIMIT 1 OFFSET 49)
FROM users u
WHERE (SELECT COUNT(*) FROM workout_sessions ws WHERE ws.user_id = u.id AND ws.status = 'COMPLETED') >= 50
ON CONFLICT DO NOTHING;

-- 100회 운동 업적
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT
    u.id,
    '운동 100회 달성',
    '백 번의 도전, 백 번의 성장! 놀라운 성취입니다!',
    '🏆',
    'WORKOUT_COUNT',
    (SELECT ws2.start_time
     FROM workout_sessions ws2
     WHERE ws2.user_id = u.id AND ws2.status = 'COMPLETED'
     ORDER BY ws2.start_time
     LIMIT 1 OFFSET 99)
FROM users u
WHERE (SELECT COUNT(*) FROM workout_sessions ws WHERE ws.user_id = u.id AND ws.status = 'COMPLETED') >= 100
ON CONFLICT DO NOTHING;

-- 200회 운동 업적
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT
    u.id,
    '운동 200회 달성',
    '200회의 노력이 빛나는 순간입니다!',
    '👑',
    'WORKOUT_COUNT',
    (SELECT ws2.start_time
     FROM workout_sessions ws2
     WHERE ws2.user_id = u.id AND ws2.status = 'COMPLETED'
     ORDER BY ws2.start_time
     LIMIT 1 OFFSET 199)
FROM users u
WHERE (SELECT COUNT(*) FROM workout_sessions ws WHERE ws.user_id = u.id AND ws.status = 'COMPLETED') >= 200
ON CONFLICT DO NOTHING;

-- 볼륨 업적 - 세션당 10톤 이상
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    ws.user_id,
    '10톤 마스터',
    '한 세션에서 10,000kg을 들어올렸습니다!',
    '💪',
    'VOLUME',
    MIN(ws.start_time)
FROM workout_sessions ws
WHERE ws.status = 'COMPLETED'
  AND ws.total_volume >= 10000
GROUP BY ws.user_id
ON CONFLICT DO NOTHING;

-- 볼륨 업적 - 세션당 20톤 이상
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    ws.user_id,
    '20톤 전사',
    '한 세션에서 20,000kg을 들어올렸습니다!',
    '🦾',
    'VOLUME',
    MIN(ws.start_time)
FROM workout_sessions ws
WHERE ws.status = 'COMPLETED'
  AND ws.total_volume >= 20000
GROUP BY ws.user_id
ON CONFLICT DO NOTHING;

-- 운동 시간 업적 - 60분 이상
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    ws.user_id,
    '한 시간 집중',
    '60분 이상 운동을 완료했습니다!',
    '⏱️',
    'CONSISTENCY',
    MIN(ws.start_time)
FROM workout_sessions ws
WHERE ws.status = 'COMPLETED'
  AND ws.duration >= 60
GROUP BY ws.user_id
ON CONFLICT DO NOTHING;

-- 운동 시간 업적 - 90분 이상
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    ws.user_id,
    '90분 전사',
    '90분 이상 운동을 완료했습니다!',
    '⚡',
    'CONSISTENCY',
    MIN(ws.start_time)
FROM workout_sessions ws
WHERE ws.status = 'COMPLETED'
  AND ws.duration >= 90
GROUP BY ws.user_id
ON CONFLICT DO NOTHING;

-- 개인 기록 업적 - 첫 PR 달성
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT DISTINCT
    pr.user_id,
    '첫 개인 기록',
    '첫 개인 기록을 달성했습니다!',
    '🎯',
    'PERSONAL_RECORD',
    MIN(pr.set_date)
FROM personal_records pr
GROUP BY pr.user_id
ON CONFLICT DO NOTHING;

-- 개인 기록 업적 - 10개 PR 달성
INSERT INTO achievements (user_id, name, description, icon, type, unlocked_at)
SELECT
    pr.user_id,
    'PR 헌터',
    '10개의 개인 기록을 달성했습니다!',
    '🚀',
    'PERSONAL_RECORD',
    (SELECT pr2.set_date
     FROM personal_records pr2
     WHERE pr2.user_id = pr.user_id
     ORDER BY pr2.set_date
     LIMIT 1 OFFSET 9)
FROM personal_records pr
GROUP BY pr.user_id
HAVING COUNT(*) >= 10
ON CONFLICT DO NOTHING;

-- 연속 운동 스트릭 업적은 별도 쿼리로 처리
-- (복잡한 날짜 계산이 필요하므로 애플리케이션 로직에서 처리하는 것이 좋음)

-- 업적 통계 조회 쿼리
SELECT
    'Total Users' as metric,
    COUNT(DISTINCT id) as count
FROM users
UNION ALL
SELECT
    'Users with Achievements' as metric,
    COUNT(DISTINCT user_id) as count
FROM achievements
UNION ALL
SELECT
    'Total Achievements Unlocked' as metric,
    COUNT(*) as count
FROM achievements
UNION ALL
SELECT
    type as metric,
    COUNT(*) as count
FROM achievements
GROUP BY type;
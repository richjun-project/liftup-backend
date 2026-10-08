-- 첫 운동 활성화 퍼널 (app_events) — GA4 없이 "어느 버전·플랫폼에서 어디서 멈췄나"를 본다.
-- 테스트 기기(X-Test-Device=1, Play 사전 출시 보고서)와 개발자 계정은 뺀다. 시각은 UTC.
--
-- 실행(운영 서버):
--   docker exec -i liftupai-mysql-prod sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" liftupai_db' < activation_funnel.sql

SET @days = 14;
SET @exclude_ids = '22';

SELECT '== 버전·플랫폼별 퍼널(사용자 수)' AS section;
SELECT COALESCE(platform, '?') platform, COALESCE(app_version, '?') app_version,
       COUNT(DISTINCT user_id) users,
       COUNT(DISTINCT CASE WHEN name = 'onboarding_complete' THEN user_id END) onboarded,
       COUNT(DISTINCT CASE WHEN name = 'workout_preview_open' THEN user_id END) preview,
       COUNT(DISTINCT CASE WHEN name = 'workout_start' THEN user_id END) started,
       COUNT(DISTINCT CASE WHEN name = 'set_complete' THEN user_id END) first_set,
       COUNT(DISTINCT CASE WHEN name = 'workout_complete' THEN user_id END) completed
FROM app_events
WHERE created_at >= TIMESTAMPADD(DAY, -@days, UTC_TIMESTAMP()) AND test_device = 0 AND NOT FIND_IN_SET(user_id, @exclude_ids)
GROUP BY platform, app_version ORDER BY users DESC;

SELECT '== 운동 화면 이탈: 세트 0개로 나간 비율과 머문 시간' AS section;
SELECT COALESCE(platform, '?') platform,
       COUNT(*) exits,
       SUM(JSON_EXTRACT(params, '$.sets_completed') = 0) zero_set_exits,
       ROUND(AVG(JSON_EXTRACT(params, '$.seconds_open'))) avg_seconds_open,
       MAX(JSON_EXTRACT(params, '$.seconds_open')) max_seconds_open
FROM app_events
WHERE name = 'workout_screen_exit' AND created_at >= TIMESTAMPADD(DAY, -@days, UTC_TIMESTAMP()) AND test_device = 0 AND NOT FIND_IN_SET(user_id, @exclude_ids)
GROUP BY platform;

SELECT '== 첫 운동 이탈 이유' AS section;
SELECT JSON_UNQUOTE(JSON_EXTRACT(params, '$.reason')) reason, COUNT(*) n
FROM app_events
WHERE name = 'first_workout_exit_reason' AND created_at >= TIMESTAMPADD(DAY, -@days, UTC_TIMESTAMP()) AND test_device = 0 AND NOT FIND_IN_SET(user_id, @exclude_ids)
GROUP BY reason ORDER BY n DESC;

SELECT '== 알림 권한 결과(iOS 는 허용해야 푸시 기기가 등록된다)' AS section;
SELECT COALESCE(platform, '?') platform, JSON_UNQUOTE(JSON_EXTRACT(params, '$.status_after')) status_after, COUNT(*) n
FROM app_events
WHERE name = 'notification_permission_result' AND created_at >= TIMESTAMPADD(DAY, -@days, UTC_TIMESTAMP()) AND test_device = 0 AND NOT FIND_IN_SET(user_id, @exclude_ids)
GROUP BY platform, status_after ORDER BY n DESC;

SELECT '== 푸시 기기 등록(버전별)' AS section;
SELECT platform, COALESCE(app_version, '?') app_version, COUNT(*) devices, SUM(is_active = 1) active, MAX(registered_at) last_registered
FROM notification_devices
GROUP BY platform, app_version ORDER BY last_registered DESC;

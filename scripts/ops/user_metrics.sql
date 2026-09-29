-- 실사용 지표 — 개발자 계정과 스토어 심사·자동 테스트 기기로 보이는 가입을 빼고 본다.
--
-- 실행(운영 서버):
--   docker exec -i liftupai-mysql-prod sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" liftupai_db' < user_metrics.sql
--
-- "테스트 기기 의심" 판정 (2026-09 실측 근거):
--   Google 로 가입 → 가입 60초 안에 ANDROID 푸시 기기 등록 → 온보딩(프로필) 없음 → 운동·채팅·식단 기록 없음.
--   7/26 이후 76건이 전부 이 패턴이고 Play 업로드 직후에 몰린다. 같은 기간 Play Console 의 설치 사용자는 4→8 로만 늘었다.
--   진짜 사용자가 온보딩 첫 화면에서 막혀도 같은 패턴이 되므로, 이 수치가 갑자기 늘면 온보딩 장애부터 의심할 것.

SET @exclude_ids = '22';         -- 개발자 본인 계정 (쉼표 구분)
SET @funnel_days = 30;           -- 퍼널을 볼 최근 가입 기간

DROP TEMPORARY TABLE IF EXISTS m_users;
CREATE TEMPORARY TABLE m_users AS
SELECT u.id,
       u.join_date,
       u.oauth_provider,
       EXISTS(SELECT 1 FROM user_profiles p WHERE p.user_id = u.id) AS onboarded,
       EXISTS(SELECT 1 FROM user_workout_plans wp WHERE wp.user_id = u.id) AS has_plan,
       (SELECT MIN(w.start_time) FROM workout_sessions w WHERE w.user_id = u.id) AS first_workout_at,
       EXISTS(SELECT 1 FROM workout_sessions w WHERE w.user_id = u.id AND w.status = 'COMPLETED') AS completed_workout,
       EXISTS(SELECT 1 FROM workout_sessions w
              JOIN workout_exercises we ON we.session_id = w.id
              JOIN exercise_sets s ON s.workout_exercise_id = we.id
              WHERE w.user_id = u.id AND s.completed = 1) AS logged_set,
       (SELECT COUNT(*) FROM chat_messages c
        WHERE c.user_id = u.id AND c.message_type <> 'SYSTEM' AND COALESCE(c.user_message, '') <> '') AS chats,
       (SELECT COUNT(*) FROM meal_logs m WHERE m.user_id = u.id) AS meals,
       GREATEST(
         COALESCE((SELECT MAX(w.start_time) FROM workout_sessions w WHERE w.user_id = u.id), '1970-01-01'),
         COALESCE((SELECT MAX(c.timestamp) FROM chat_messages c
                   WHERE c.user_id = u.id AND c.message_type <> 'SYSTEM' AND COALESCE(c.user_message, '') <> ''), '1970-01-01'),
         COALESCE((SELECT MAX(m.timestamp) FROM meal_logs m WHERE m.user_id = u.id), '1970-01-01')
       ) AS last_action_at,
       (SELECT MIN(d.registered_at) FROM notification_devices d
        WHERE d.user_id = u.id AND d.platform = 'ANDROID') AS android_registered_at
FROM users u
WHERE u.deleted_at IS NULL
  AND NOT FIND_IN_SET(u.id, @exclude_ids);

ALTER TABLE m_users ADD COLUMN suspected_test_device TINYINT;
UPDATE m_users
SET suspected_test_device = (
      oauth_provider = 'GOOGLE'
  AND onboarded = 0
  AND android_registered_at IS NOT NULL
  AND TIMESTAMPDIFF(SECOND, join_date, android_registered_at) BETWEEN 0 AND 60
  AND first_workout_at IS NULL AND chats = 0 AND meals = 0
);

SELECT '== 주별 가입 (테스트 기기 의심 제외 후 실가입)' AS section;
SELECT DATE_FORMAT(DATE_SUB(DATE(join_date), INTERVAL WEEKDAY(join_date) DAY), '%Y-%m-%d') AS week,
       COUNT(*) AS all_signups,
       SUM(suspected_test_device) AS test_device,
       SUM(1 - suspected_test_device) AS real_signups,
       SUM(onboarded) AS onboarded,
       SUM(first_workout_at IS NOT NULL) AS started_workout,
       SUM(DATE(last_action_at) > DATE(join_date)) AS came_back_later
FROM m_users
WHERE join_date >= UTC_TIMESTAMP() - INTERVAL 10 WEEK
GROUP BY week
ORDER BY week;

SELECT CONCAT('== 최근 ', @funnel_days, '일 실가입 퍼널') AS section;
SELECT COUNT(*) AS signed_up,
       SUM(onboarded) AS onboarded,
       SUM(has_plan) AS has_plan,
       SUM(chats > 0) AS chatted,
       SUM(first_workout_at IS NOT NULL) AS started_workout,
       SUM(logged_set) AS logged_a_set,
       SUM(completed_workout) AS completed_workout,
       SUM(DATE(last_action_at) > DATE(join_date)) AS came_back_later
FROM m_users
WHERE suspected_test_device = 0
  AND join_date >= UTC_TIMESTAMP() - INTERVAL @funnel_days DAY;

SELECT '== 실제 행동 기준 활성 사용자 (운동·채팅·식단 중 하나)' AS section;
SELECT SUM(last_action_at >= UTC_TIMESTAMP() - INTERVAL 1 DAY) AS dau,
       SUM(last_action_at >= UTC_TIMESTAMP() - INTERVAL 7 DAY) AS wau,
       SUM(last_action_at >= UTC_TIMESTAMP() - INTERVAL 30 DAY) AS mau
FROM m_users;

DROP TEMPORARY TABLE m_users;

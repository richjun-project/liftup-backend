-- LiftUp 일일 KPI (읽기 전용 SELECT 1개, 2026-10-21). 출력: 지표 이름·d·키 를 세는 행들 → kpi_daily.sh 가 JSON 으로 묶는다.
-- DB 시각은 UTC. d=0 은 최근 24시간, d=1 은 그 전 24시간, d 0..6 은 최근 7일.
-- 실사용자: 개발자 계정(22) 제외. 매일 오는 Google 테스트 기기(Google 로그인 → 온보딩 없음)는 가입 수에서 따로 센다(signups_no_onboarding).
WITH ev AS (
  SELECT 'signups_all' m, FLOOR(TIMESTAMPDIFF(SECOND, u.join_date, UTC_TIMESTAMP()) / 86400) d, u.id k
    FROM users u WHERE u.id <> 22
  UNION ALL SELECT 'signups_onboarded', FLOOR(TIMESTAMPDIFF(SECOND, u.join_date, UTC_TIMESTAMP()) / 86400), u.id
    FROM users u JOIN user_profiles p ON p.user_id = u.id WHERE u.id <> 22
  UNION ALL SELECT CONCAT('signups_onboarded_', COALESCE(s.language, 'none')), FLOOR(TIMESTAMPDIFF(SECOND, u.join_date, UTC_TIMESTAMP()) / 86400), u.id
    FROM users u JOIN user_profiles p ON p.user_id = u.id LEFT JOIN user_settings s ON s.user_id = u.id WHERE u.id <> 22
  UNION ALL SELECT 'deleted_accounts', FLOOR(TIMESTAMPDIFF(SECOND, u.deleted_at, UTC_TIMESTAMP()) / 86400), u.id
    FROM users u WHERE u.deleted_at IS NOT NULL AND u.id <> 22
  -- 활성화: 처음으로 세트를 완료한 사용자(그 날짜 기준)
  UNION ALL SELECT 'first_set_users', FLOOR(TIMESTAMPDIFF(SECOND, f.first_at, UTC_TIMESTAMP()) / 86400), f.user_id
    FROM (SELECT w.user_id, MIN(COALESCE(es.completed_at, w.start_time)) first_at
            FROM workout_sessions w JOIN workout_exercises we ON we.session_id = w.id JOIN exercise_sets es ON es.workout_exercise_id = we.id
           WHERE es.completed = 1 AND w.user_id <> 22 GROUP BY w.user_id) f
  UNION ALL SELECT 'workouts_completed', FLOOR(TIMESTAMPDIFF(SECOND, COALESCE(w.end_time, w.start_time), UTC_TIMESTAMP()) / 86400), w.id
    FROM workout_sessions w WHERE w.status = 'COMPLETED' AND w.user_id <> 22
  UNION ALL SELECT 'workout_users', FLOOR(TIMESTAMPDIFF(SECOND, w.start_time, UTC_TIMESTAMP()) / 86400), w.user_id
    FROM workout_sessions w WHERE w.user_id <> 22
  UNION ALL SELECT 'chat_messages', FLOOR(TIMESTAMPDIFF(SECOND, c.timestamp, UTC_TIMESTAMP()) / 86400), c.id
    FROM chat_messages c WHERE c.message_type <> 'SYSTEM' AND COALESCE(c.user_message, '') <> '' AND c.user_id <> 22
  UNION ALL SELECT 'chat_users', FLOOR(TIMESTAMPDIFF(SECOND, c.timestamp, UTC_TIMESTAMP()) / 86400), c.user_id
    FROM chat_messages c WHERE c.message_type <> 'SYSTEM' AND COALESCE(c.user_message, '') <> '' AND c.user_id <> 22
  UNION ALL SELECT 'chat_agent_tool_turns', FLOOR(TIMESTAMPDIFF(SECOND, c.timestamp, UTC_TIMESTAMP()) / 86400), c.id
    FROM chat_messages c WHERE COALESCE(c.tool_trace, '') <> '' AND c.user_id <> 22
  UNION ALL SELECT 'chat_errors', FLOOR(TIMESTAMPDIFF(SECOND, c.timestamp, UTC_TIMESTAMP()) / 86400), c.id
    FROM chat_messages c WHERE c.message_type <> 'SYSTEM' AND c.user_id <> 22 AND (c.status <> 'COMPLETED' OR c.error IS NOT NULL)
  UNION ALL SELECT 'meal_logs', FLOOR(TIMESTAMPDIFF(SECOND, m.timestamp, UTC_TIMESTAMP()) / 86400), m.id
    FROM meal_logs m WHERE m.user_id <> 22
  UNION ALL SELECT 'coach_memories_saved', FLOOR(TIMESTAMPDIFF(SECOND, cm.created_at, UTC_TIMESTAMP()) / 86400), cm.id
    FROM coach_memories cm WHERE cm.user_id <> 22
  -- 앱 이벤트(테스트 기기 제외)
  UNION ALL SELECT CONCAT('ev_', e.name), FLOOR(TIMESTAMPDIFF(SECOND, e.created_at, UTC_TIMESTAMP()) / 86400), e.user_id
    FROM app_events e WHERE COALESCE(e.user_id, 0) <> 22 AND COALESCE(e.test_device, 0) = 0
     AND e.name IN ('onboarding_start', 'onboarding_complete', 'paywall_view', 'purchase_result', 'workout_start', 'workout_complete', 'notification_permission_result')
  UNION ALL SELECT 'purchase_success', FLOOR(TIMESTAMPDIFF(SECOND, e.created_at, UTC_TIMESTAMP()) / 86400), e.user_id
    FROM app_events e WHERE e.name = 'purchase_result' AND e.params LIKE '%"success"%' AND COALESCE(e.user_id, 0) <> 22
  -- 매출: 실제 결제(금액>0, 샌드박스 제외). 체험 시작은 금액 0 결제 행으로 들어온다
  UNION ALL SELECT CONCAT('paid_', ph.currency), FLOOR(TIMESTAMPDIFF(SECOND, ph.payment_date, UTC_TIMESTAMP()) / 86400), ph.id
    FROM payment_history ph WHERE ph.amount > 0 AND ph.status = 'SUCCESS' AND ph.payment_method NOT LIKE 'SANDBOX%' AND ph.user_id <> 22
  UNION ALL SELECT 'trials_started', FLOOR(TIMESTAMPDIFF(SECOND, ph.payment_date, UTC_TIMESTAMP()) / 86400), ph.id
    FROM payment_history ph WHERE ph.amount = 0 AND ph.payment_method NOT LIKE 'SANDBOX%' AND ph.user_id <> 22
  UNION ALL SELECT 'subscriptions_cancelled', FLOOR(TIMESTAMPDIFF(SECOND, s.cancelled_at, UTC_TIMESTAMP()) / 86400), s.id
    FROM subscriptions s WHERE s.cancelled_at IS NOT NULL AND s.user_id <> 22 AND s.store NOT LIKE 'SANDBOX%'
  UNION ALL SELECT 'push_sent', FLOOR(TIMESTAMPDIFF(SECOND, n.created_at, UTC_TIMESTAMP()) / 86400), n.id
    FROM notification_history n WHERE n.user_id <> 22
)
SELECT 'metric', m, SUM(d = 0), SUM(d = 1), SUM(d BETWEEN 0 AND 6), COUNT(DISTINCT CASE WHEN d = 0 THEN k END), COUNT(DISTINCT CASE WHEN d BETWEEN 0 AND 6 THEN k END), COUNT(DISTINCT CASE WHEN d BETWEEN 0 AND 29 THEN k END)
  FROM ev WHERE d BETWEEN 0 AND 29 GROUP BY m
UNION ALL
-- 현재 상태: 실제로 돈을 낸 활성 구독(최근 40일 안 결제>0), 체험 중, 프로모션, 누적 사용자, 30일 활성
SELECT 'state', 'paying_subscribers', COUNT(*), 0, 0, 0, 0, 0 FROM subscriptions s
 WHERE s.status = 'ACTIVE' AND s.user_id <> 22 AND s.store IN ('APP_STORE', 'PLAY_STORE') AND s.expiry_date > UTC_TIMESTAMP()
   AND EXISTS (SELECT 1 FROM payment_history ph WHERE ph.user_id = s.user_id AND ph.amount > 0 AND ph.payment_date > UTC_TIMESTAMP() - INTERVAL 40 DAY)
UNION ALL
SELECT 'state', 'mrr_krw_est', COALESCE(SUM(CASE ph.currency WHEN 'KRW' THEN ph.amount WHEN 'JPY' THEN ph.amount * 9.2 WHEN 'USD' THEN ph.amount * 1400 ELSE 0 END), 0), 0, 0, 0, 0, 0
  FROM payment_history ph WHERE ph.amount > 0 AND ph.status = 'SUCCESS' AND ph.payment_method NOT LIKE 'SANDBOX%' AND ph.user_id <> 22
   AND ph.payment_date > UTC_TIMESTAMP() - INTERVAL 30 DAY
UNION ALL
SELECT 'state', 'in_trial', COUNT(*), 0, 0, 0, 0, 0 FROM subscriptions s
 WHERE s.status = 'ACTIVE' AND s.user_id <> 22 AND s.store IN ('APP_STORE', 'PLAY_STORE') AND s.expiry_date > UTC_TIMESTAMP()
   AND NOT EXISTS (SELECT 1 FROM payment_history ph WHERE ph.user_id = s.user_id AND ph.amount > 0)
UNION ALL
SELECT 'state', 'in_trial_auto_renew_off', COUNT(*), 0, 0, 0, 0, 0 FROM subscriptions s
 WHERE s.status = 'ACTIVE' AND s.user_id <> 22 AND s.store IN ('APP_STORE', 'PLAY_STORE') AND s.expiry_date > UTC_TIMESTAMP() AND s.auto_renew = 0
   AND NOT EXISTS (SELECT 1 FROM payment_history ph WHERE ph.user_id = s.user_id AND ph.amount > 0)
UNION ALL
SELECT 'state', 'users_onboarded_total', COUNT(*), 0, 0, 0, 0, 0 FROM user_profiles p JOIN users u ON u.id = p.user_id WHERE u.deleted_at IS NULL AND u.id <> 22
UNION ALL
SELECT 'state', 'active_users_7d', COUNT(DISTINCT a.uid), 0, 0, 0, 0, 0 FROM (
  SELECT user_id uid FROM workout_sessions WHERE start_time > UTC_TIMESTAMP() - INTERVAL 7 DAY
  UNION SELECT user_id FROM chat_messages WHERE timestamp > UTC_TIMESTAMP() - INTERVAL 7 DAY AND message_type <> 'SYSTEM' AND COALESCE(user_message, '') <> ''
  UNION SELECT user_id FROM meal_logs WHERE timestamp > UTC_TIMESTAMP() - INTERVAL 7 DAY
  -- 앱 이벤트는 온보딩을 마친 사용자만(테스트 기기는 온보딩 첫 화면 이벤트만 남긴다)
  UNION SELECT e.user_id FROM app_events e JOIN user_profiles p ON p.user_id = e.user_id WHERE e.created_at > UTC_TIMESTAMP() - INTERVAL 7 DAY AND COALESCE(e.test_device, 0) = 0) a
 WHERE a.uid IS NOT NULL AND a.uid <> 22
UNION ALL
SELECT 'state', 'active_users_30d', COUNT(DISTINCT a.uid), 0, 0, 0, 0, 0 FROM (
  SELECT user_id uid FROM workout_sessions WHERE start_time > UTC_TIMESTAMP() - INTERVAL 30 DAY
  UNION SELECT user_id FROM chat_messages WHERE timestamp > UTC_TIMESTAMP() - INTERVAL 30 DAY AND message_type <> 'SYSTEM' AND COALESCE(user_message, '') <> ''
  UNION SELECT user_id FROM meal_logs WHERE timestamp > UTC_TIMESTAMP() - INTERVAL 30 DAY
  -- 앱 이벤트는 온보딩을 마친 사용자만(테스트 기기는 온보딩 첫 화면 이벤트만 남긴다)
  UNION SELECT e.user_id FROM app_events e JOIN user_profiles p ON p.user_id = e.user_id WHERE e.created_at > UTC_TIMESTAMP() - INTERVAL 30 DAY AND COALESCE(e.test_device, 0) = 0) a
 WHERE a.uid IS NOT NULL AND a.uid <> 22;

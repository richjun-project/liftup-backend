-- 데이터베이스 정리 마이그레이션 스크립트
-- 실행일: 2025년 1월
-- 목적: 사용하지 않는 테이블, 컬럼, 중복 데이터 정리

-- ============================================
-- 1. UserProfile deprecated 필드 제거
-- ============================================
-- 이미 UserSettings로 마이그레이션된 필드들 제거

-- 데이터 백업 (안전을 위해)
CREATE TABLE IF NOT EXISTS user_profiles_backup AS
SELECT * FROM user_profiles;

-- deprecated 컬럼들 제거
ALTER TABLE user_profiles
DROP COLUMN IF EXISTS weekly_workout_days,
DROP COLUMN IF EXISTS workout_split,
DROP COLUMN IF EXISTS preferred_workout_time,
DROP COLUMN IF EXISTS workout_duration;

-- 관련 테이블 정리
DROP TABLE IF EXISTS user_equipment;  -- availableEquipment이 UserSettings로 이동됨
DROP TABLE IF EXISTS user_profile_injuries;  -- injuries가 UserSettings로 이동됨

-- ============================================
-- 2. 사용하지 않는 테이블 정리
-- ============================================

-- workout_logs 테이블은 실제로 사용됨 (SyncService, SocialService, StatsService)
-- 삭제하지 않음!

-- 아예 사용되지 않는 테이블들 (Entity가 없거나 Repository가 없는 경우)
-- 주의: 이 테이블들이 실제로 존재하지 않을 수 있음
-- DROP TABLE IF EXISTS unused_table_name;

-- ============================================
-- 3. 중복 데이터 정리
-- ============================================

-- exercise_muscle_groups 테이블 정리 (중복 제거)
DELETE FROM exercise_muscle_groups
WHERE id NOT IN (
    SELECT MIN(id)
    FROM exercise_muscle_groups
    GROUP BY exercise_id, muscle_groups
);

-- ============================================
-- 4. 인덱스 최적화
-- ============================================

-- 자주 조회되는 필드에 인덱스 추가
CREATE INDEX IF NOT EXISTS idx_workout_sessions_user_status
ON workout_sessions(user_id, status);

CREATE INDEX IF NOT EXISTS idx_workout_sessions_user_starttime
ON workout_sessions(user_id, start_time);

CREATE INDEX IF NOT EXISTS idx_exercise_sets_workout_exercise
ON exercise_sets(workout_exercise_id);

CREATE INDEX IF NOT EXISTS idx_personal_records_user_exercise
ON personal_records(user_id, exercise_id);

CREATE INDEX IF NOT EXISTS idx_achievements_user
ON achievements(user_id);

CREATE INDEX IF NOT EXISTS idx_muscle_recovery_user
ON muscle_recovery(user_id);

-- ============================================
-- 5. 데이터 정합성 체크
-- ============================================

-- 고아 레코드 삭제 (참조하는 user가 없는 경우)
DELETE FROM workout_sessions WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM user_profiles WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM user_settings WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM achievements WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM personal_records WHERE user_id NOT IN (SELECT id FROM users);

-- ============================================
-- 6. 통계 업데이트
-- ============================================
ANALYZE workout_sessions;
ANALYZE exercise_sets;
ANALYZE workout_exercises;
ANALYZE users;
ANALYZE user_profiles;
ANALYZE user_settings;

-- ============================================
-- 7. 검증 쿼리
-- ============================================

-- 정리 후 통계 확인
SELECT 'Users' as entity, COUNT(*) as count FROM users
UNION ALL
SELECT 'UserProfiles', COUNT(*) FROM user_profiles
UNION ALL
SELECT 'UserSettings', COUNT(*) FROM user_settings
UNION ALL
SELECT 'WorkoutSessions', COUNT(*) FROM workout_sessions
UNION ALL
SELECT 'ExerciseSets', COUNT(*) FROM exercise_sets
UNION ALL
SELECT 'WorkoutLogs', COUNT(*) FROM workout_logs
UNION ALL
SELECT 'Achievements', COUNT(*) FROM achievements
UNION ALL
SELECT 'PersonalRecords', COUNT(*) FROM personal_records;

-- UserProfile에서 제거된 컬럼 확인
SELECT column_name
FROM information_schema.columns
WHERE table_name = 'user_profiles'
AND column_name IN ('weekly_workout_days', 'workout_split',
                    'preferred_workout_time', 'workout_duration');

-- 만약 결과가 나오면 컬럼이 아직 존재하는 것
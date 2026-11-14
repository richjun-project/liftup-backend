-- 세션 데이터 품질 표시(2026-11-14 coach-autopilot-r1): 사람이 할 수 없는 속도로 끝난 세션은 PR·첫 운동 판정에서 뺀다.
-- 추가만 하는 마이그레이션(값이 없으면 정상 세션).
ALTER TABLE workout_sessions ADD COLUMN data_quality VARCHAR(30) NULL;

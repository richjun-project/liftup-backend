-- 온보딩 세부 목표(thick_legs·glutes·big_arms 등)를 보존한다.
-- FitnessGoal 은 6종뿐이라 user_goals 에는 MUSCLE_GAIN 등으로 뭉개져 저장되고, 어느 부위를 집중할지는 사라졌다.
-- 추가만 하는 마이그레이션 — 기존 테이블·데이터는 건드리지 않는다.
CREATE TABLE IF NOT EXISTS user_focus_goals (
    settings_id BIGINT NOT NULL,
    focus_goal VARCHAR(100) NOT NULL,
    PRIMARY KEY (settings_id, focus_goal)
);

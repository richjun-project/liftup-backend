-- "코치가 한 일"(2026-11-14 coach-autopilot-r2): 트레이너가 바꾼 플랜·설정과 바꾸기 전 상태(되돌리기용 JSON). 추가만 하는 마이그레이션.
CREATE TABLE IF NOT EXISTS coach_actions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    tool VARCHAR(40) NOT NULL,
    source VARCHAR(16) NOT NULL,
    summary VARCHAR(200) NOT NULL,
    snapshot TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    undone_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_coach_actions_user (user_id, id)
);

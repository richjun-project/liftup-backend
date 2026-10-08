-- 앱 활성화 이벤트(첫 운동 퍼널) — GA4 와 같은 이벤트 중 허용 목록만 서버에도 남겨 로그 분석에서 바로 본다.
-- 추가만 하는 마이그레이션. user_id 는 일반 컬럼(FK 없음 — 탈퇴 시 UserService.deleteAllUserData 가 지운다).
-- params 는 서버가 정제한 평평한 JSON(키 40자·값 100자·25개 이하, 자유 입력 문자열 없음).
CREATE TABLE IF NOT EXISTS app_events (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(40)  NOT NULL,
    params      VARCHAR(2000) NULL,
    app_version VARCHAR(32)  NULL,
    platform    VARCHAR(16)  NULL,
    test_device TINYINT(1)   NOT NULL DEFAULT 0,
    occurred_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_app_events_user_created ON app_events (user_id, created_at);
CREATE INDEX idx_app_events_name_created ON app_events (name, created_at);

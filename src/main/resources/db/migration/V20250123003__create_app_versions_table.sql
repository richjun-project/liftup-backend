-- 앱 버전 관리 테이블 생성
CREATE TABLE IF NOT EXISTS app_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(20) NOT NULL,
    version_code INT NOT NULL,
    platform VARCHAR(20) NOT NULL,
    minimum_version VARCHAR(20),
    minimum_version_code INT,
    force_update BOOLEAN NOT NULL DEFAULT FALSE,
    update_url VARCHAR(500),
    release_notes TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    release_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE,
    maintenance_message TEXT,
    features JSON,

    UNIQUE KEY uk_version_platform (version, platform),
    INDEX idx_platform_active (platform, is_active),
    INDEX idx_version_code (version_code),
    INDEX idx_maintenance (platform, maintenance_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초기 버전 데이터 삽입
INSERT IGNORE INTO app_versions (version, version_code, platform, release_notes, is_active) VALUES
('1.0.0', 100, 'IOS', '초기 릴리즈', true),
('1.0.0', 100, 'ANDROID', '초기 릴리즈', true),
('1.0.0', 100, 'WEB', '초기 릴리즈', true);
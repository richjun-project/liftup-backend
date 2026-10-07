-- 운동 즐겨찾기 + 검색 운영 로그 + 런타임 동의어.
-- 추가만 하는 마이그레이션 — 기존 테이블·데이터는 건드리지 않는다.

-- 즐겨찾기: user_id 는 일반 컬럼(탈퇴 시 삭제는 서비스가 deleteAllByUserId 로 한다),
-- exercise_id 는 FK(운동이 카탈로그 정리로 사라지면 같이 지워진다).
CREATE TABLE IF NOT EXISTS exercise_favorites (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    exercise_id BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exercise_favorites_user_exercise UNIQUE (user_id, exercise_id),
    CONSTRAINT fk_exercise_favorites_exercise
        FOREIGN KEY (exercise_id) REFERENCES exercises (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_exercise_favorites_user_created ON exercise_favorites (user_id, created_at);

-- 검색 로그: 사용자 식별자·부상 목록은 넣지 않는다(질의 문자열은 정규화본만).
CREATE TABLE IF NOT EXISTS search_query_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    query_text   VARCHAR(100) NOT NULL,
    locale       VARCHAR(10)  NOT NULL,
    result_count INT          NOT NULL,
    latency_ms   INT          NOT NULL,
    source       VARCHAR(20)  NOT NULL,
    has_filters  TINYINT(1)   NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_search_query_log_created ON search_query_log (created_at);
CREATE INDEX idx_search_query_log_zero ON search_query_log (result_count, created_at);

-- 런타임 동의어: line 은 엔진 사전과 같은 형식("트리거|트리거 => 확장|확장", 강한 별칭은 "=>>").
-- enabled=1 인 줄만 쓰이고 ExerciseSearchIndexService 가 짧은 TTL 로 다시 읽는다(재배포 불필요).
CREATE TABLE IF NOT EXISTS search_synonyms (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    line       VARCHAR(500) NOT NULL,
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    note       VARCHAR(200) NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 리프레시 토큰 가족 규칙(2026-11-22, RefreshTokenFamily): 이 시각 이후 발급된 리프레시 토큰은 받아 준다.
-- 갱신 응답을 못 받은 앱이 직전 토큰으로 다시 와도 로그아웃되지 않게. 추가만 하는 마이그레이션(null = 옛 동작).
ALTER TABLE users ADD COLUMN refresh_floor_at DATETIME(6) NULL;

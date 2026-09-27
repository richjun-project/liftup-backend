-- LiftUp AI: users.refresh_token VARCHAR(255) → VARCHAR(1024)
--
-- 리프레시 토큰은 HS512 JWT 에 identifier(이메일) 클레임을 담는다. 이메일이 길면 255자를 넘어
-- 구글 가입이 "Data too long for column 'refresh_token'" 로 500 이 났다(2026-09-25 운영, 신규 가입자 1명 유실).
-- 기존 토큰 최대 길이 239자. 이메일 최대 254자를 넣어도 넉넉하도록 1024 로 넓힌다.
ALTER TABLE users MODIFY refresh_token VARCHAR(1024) NULL;

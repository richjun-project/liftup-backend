# LiftUp AI Backend

AI 기반 개인 트레이너 애플리케이션 백엔드 (Google Gemini AI 통합)

## 기술 스택
- Spring Boot 3.5.5
- Kotlin 1.9.25
- MySQL 8.0
- Spring Data JPA
- Spring Security + JWT
- Google Gemini AI API
- Gradle

## 주요 기능
- 사용자 인증 (회원가입/로그인/JWT)
- AI 채팅 기능 (Google Gemini AI 연동)
- 운동 기록 관리
- 운동 통계 분석

## 프로젝트 구조
```
src/main/kotlin/com/richjun/liftupai/
├── entity/       # JPA 엔티티
├── repository/   # JPA Repository
├── service/      # 비즈니스 로직
├── controller/   # REST API 컨트롤러
├── dto/          # 데이터 전송 객체
├── config/       # 설정 클래스
├── security/     # JWT 인증/보안
└── exception/    # 예외 처리
```

## API 엔드포인트

### 인증
- `POST /api/auth/register` - 회원가입
- `POST /api/auth/login` - 로그인
- `POST /api/auth/refresh` - 토큰 갱신
- `POST /api/auth/logout` - 로그아웃
- `GET /api/auth/check-nickname` - 닉네임 중복 확인

### AI 채팅
- `POST /api/chat/send` - 메시지 전송
- `GET /api/chat/history` - 채팅 내역 조회
- `DELETE /api/chat/clear` - 채팅 내역 삭제

## 설정

### 환경 변수 설정
프로젝트 루트에 `.env` 파일 생성:
```bash
# Google Gemini API Configuration
GEMINI_API_KEY=your_gemini_api_key_here

# Database Configuration
DB_PASSWORD=your_mysql_password

# JWT Configuration
JWT_SECRET=your_jwt_secret_key_here
```

### 데이터베이스 설정
MySQL 데이터베이스는 자동으로 생성됩니다.
환경 변수 `DB_PASSWORD`를 `.env` 파일에서 설정하세요.

### Google Gemini AI 설정
1. [Google AI Studio](https://makersuite.google.com/app/apikey)에서 API 키 발급
2. `.env` 파일의 `GEMINI_API_KEY`에 설정

## 실행 방법

1. 환경 변수 설정
   - `.env` 파일 생성 및 설정

2. MySQL 실행
   - 데이터베이스는 자동 생성됨

3. 애플리케이션 실행:
```bash
./gradlew bootRun
```

4. API 테스트 (http://localhost:8080)

## 테스트 예제

### 회원가입
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "nickname": "testuser"
  }'
```

### 로그인
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'
```

### AI 채팅
```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Authorization: Bearer {access_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "오늘 가슴 운동 추천해줘"
  }'
```

## 참고사항
- Google Gemini AI API를 사용한 실시간 AI 응답
- 환경 변수는 `.env` 파일로 관리 (Git에 포함되지 않음)
- 개발 환경에서는 `create-drop` DDL 모드 사용 (운영 시 `validate`로 변경 필요)
- API Rate Limit: 분당 100 요청
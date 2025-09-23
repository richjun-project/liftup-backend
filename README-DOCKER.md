# Docker Compose 실행 가이드

## 사전 요구사항
- Docker Desktop 설치
- Docker Compose 설치 (Docker Desktop에 포함됨)

## 환경 설정

### 1. 환경 변수 설정
```bash
# .env.example 파일을 .env로 복사
cp .env.example .env

# .env 파일을 편집하여 실제 API 키 입력
# GEMINI_API_KEY, AWS_ACCESS_KEY, AWS_SECRET_KEY 설정 필요
```

## 실행 방법

### 개발 환경

#### 1. 전체 서비스 시작
```bash
# 빌드와 함께 시작
docker-compose up --build

# 백그라운드에서 실행
docker-compose up -d --build
```

#### 2. 로그 확인
```bash
# 전체 로그
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f app
docker-compose logs -f mysql
```

#### 3. 서비스 상태 확인
```bash
docker-compose ps
```

#### 4. 서비스 중지
```bash
# 서비스 중지 (컨테이너 제거, 볼륨 유지)
docker-compose down

# 서비스 중지 및 볼륨 삭제 (데이터베이스 초기화)
docker-compose down -v
```

### 운영 환경

```bash
# 운영 환경용 docker-compose 실행
docker-compose -f docker-compose.prod.yml up -d --build
```

## 접속 정보

### 애플리케이션
- URL: http://localhost:8080
- Health Check: http://localhost:8080/actuator/health

### MySQL
- Host: localhost
- Port: 3306
- Database: liftupai_db
- Username: root
- Password: rootpassword (개발 환경)

## 문제 해결

### 1. MySQL 연결 실패
```bash
# MySQL 컨테이너 상태 확인
docker-compose ps mysql

# MySQL 로그 확인
docker-compose logs mysql
```

### 2. 애플리케이션 빌드 실패
```bash
# 로컬에서 먼저 빌드 테스트
./gradlew build

# Docker 캐시 삭제 후 재빌드
docker-compose build --no-cache
```

### 3. 포트 충돌
- 3306 또는 8080 포트가 이미 사용 중인 경우
- docker-compose.yml에서 포트 번호 변경
```yaml
ports:
  - "3307:3306"  # MySQL
  - "8081:8080"  # Application
```

### 4. 컨테이너 내부 접속
```bash
# 애플리케이션 컨테이너
docker exec -it liftupai-app /bin/bash

# MySQL 컨테이너
docker exec -it liftupai-mysql mysql -uroot -prootpassword
```

## 유용한 명령어

```bash
# 이미지 재빌드
docker-compose build

# 특정 서비스만 재시작
docker-compose restart app

# 리소스 정리
docker system prune -a

# 볼륨 목록 확인
docker volume ls

# 네트워크 목록 확인
docker network ls
```

## 주의사항

1. **환경 변수**: 실제 API 키는 절대 git에 commit하지 마세요
2. **볼륨**: `docker-compose down -v` 명령은 데이터베이스를 완전히 초기화합니다
3. **메모리**: Docker Desktop의 메모리 할당을 최소 4GB 이상으로 설정하세요
4. **개발/운영 분리**: 운영환경에서는 `docker-compose.prod.yml` 사용
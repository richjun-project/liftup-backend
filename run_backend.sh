#!/bin/bash

echo "🚀 Starting LiftUp AI Backend Server..."

# 환경 변수 설정
export SPRING_PROFILES_ACTIVE=dev
export SERVER_PORT=8080

# 데이터베이스 설정 (필요시 수정)
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=liftupai
export DB_USERNAME=liftupai
export DB_PASSWORD=liftupai123

# JWT 시크릿 키 (개발용)
export JWT_SECRET=dev-secret-key-change-in-production-please-use-256-bit-key

# 파일 업로드 경로
export UPLOAD_PATH=./uploads

# 업로드 폴더 생성
mkdir -p uploads/chat
mkdir -p uploads/meal
mkdir -p uploads/form_check
mkdir -p uploads/profile
mkdir -p uploads/thumbnails

echo "📁 Upload directories created"

# Gradle 빌드
echo "🔨 Building project..."
./gradlew clean build -x test

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo "🏃 Starting server on port 8080..."
    echo "📱 Flutter app can connect to:"
    echo "   - Android Emulator: http://10.0.2.2:8080"
    echo "   - iOS Simulator: http://localhost:8080"
    echo "   - Physical Device: http://$(ipconfig getifaddr en0):8080"
    echo ""

    # 서버 실행
    java -jar build/libs/liftupai-0.0.1-SNAPSHOT.jar
else
    echo "❌ Build failed!"
    exit 1
fi
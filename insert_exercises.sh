#!/bin/bash

# Get JWT token first
echo "Getting JWT token..."
TOKEN_RESPONSE=$(curl -s -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test1@example.com",
    "password": "password123"
  }')

TOKEN=$(echo $TOKEN_RESPONSE | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "Failed to get token. Registering new user..."
  REGISTER_RESPONSE=$(curl -s -X POST "http://localhost:8080/api/auth/register" \
    -H "Content-Type: application/json" \
    -d '{
      "email": "admin@example.com",
      "password": "admin123",
      "nickname": "Admin"
    }')
  TOKEN=$(echo $REGISTER_RESPONSE | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
fi

echo "Token obtained: ${TOKEN:0:20}..."

# Create exercises using API
echo "Creating exercises..."

# Chest exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "벤치프레스",
    "category": "CHEST",
    "equipment": "BARBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "바벨을 가슴 위로 들어올립니다",
    "muscleGroups": ["CHEST", "TRICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "덤벨 플라이",
    "category": "CHEST",
    "equipment": "DUMBBELL",
    "difficulty": "BEGINNER",
    "instructions": "덤벨을 양옆으로 벌렸다가 모읍니다",
    "muscleGroups": ["CHEST"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "인클라인 벤치프레스",
    "category": "CHEST",
    "equipment": "BARBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "경사진 벤치에서 바벨을 들어올립니다",
    "muscleGroups": ["CHEST", "TRICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "푸시업",
    "category": "CHEST",
    "equipment": "BODY_WEIGHT",
    "difficulty": "BEGINNER",
    "instructions": "팔굽혀펴기를 수행합니다",
    "muscleGroups": ["CHEST", "TRICEPS"]
  }'

# Back exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "풀업",
    "category": "BACK",
    "equipment": "BODY_WEIGHT",
    "difficulty": "INTERMEDIATE",
    "instructions": "턱걸이를 수행합니다",
    "muscleGroups": ["BACK", "BICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "바벨 로우",
    "category": "BACK",
    "equipment": "BARBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "바벨을 배꼽쪽으로 당깁니다",
    "muscleGroups": ["BACK", "BICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "랫 풀다운",
    "category": "BACK",
    "equipment": "MACHINE",
    "difficulty": "BEGINNER",
    "instructions": "케이블을 아래로 당깁니다",
    "muscleGroups": ["BACK", "BICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "데드리프트",
    "category": "BACK",
    "equipment": "BARBELL",
    "difficulty": "ADVANCED",
    "instructions": "바닥에서 바벨을 들어올립니다",
    "muscleGroups": ["BACK", "HAMSTRINGS", "GLUTES"]
  }'

# Legs exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "스쿼트",
    "category": "LEGS",
    "equipment": "BARBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "바벨을 어깨에 얹고 앉았다 일어납니다",
    "muscleGroups": ["QUADRICEPS", "HAMSTRINGS", "GLUTES"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "레그프레스",
    "category": "LEGS",
    "equipment": "MACHINE",
    "difficulty": "BEGINNER",
    "instructions": "다리로 무게를 밀어냅니다",
    "muscleGroups": ["QUADRICEPS", "GLUTES"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "런지",
    "category": "LEGS",
    "equipment": "DUMBBELL",
    "difficulty": "BEGINNER",
    "instructions": "한 발씩 앞으로 나가며 앉습니다",
    "muscleGroups": ["QUADRICEPS", "GLUTES"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "레그컬",
    "category": "LEGS",
    "equipment": "MACHINE",
    "difficulty": "BEGINNER",
    "instructions": "누워서 다리를 구부립니다",
    "muscleGroups": ["HAMSTRINGS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "카프레이즈",
    "category": "LEGS",
    "equipment": "BARBELL",
    "difficulty": "BEGINNER",
    "instructions": "발꿈치를 들어올립니다",
    "muscleGroups": ["CALVES"]
  }'

# Shoulders exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "숄더프레스",
    "category": "SHOULDERS",
    "equipment": "DUMBBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "덤벨을 머리 위로 들어올립니다",
    "muscleGroups": ["SHOULDERS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "사이드 레터럴 레이즈",
    "category": "SHOULDERS",
    "equipment": "DUMBBELL",
    "difficulty": "BEGINNER",
    "instructions": "덤벨을 옆으로 들어올립니다",
    "muscleGroups": ["SHOULDERS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "프론트 레이즈",
    "category": "SHOULDERS",
    "equipment": "DUMBBELL",
    "difficulty": "BEGINNER",
    "instructions": "덤벨을 앞으로 들어올립니다",
    "muscleGroups": ["SHOULDERS"]
  }'

# Arms exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "바벨 컬",
    "category": "ARMS",
    "equipment": "BARBELL",
    "difficulty": "BEGINNER",
    "instructions": "바벨을 구부려 올립니다",
    "muscleGroups": ["BICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "해머 컬",
    "category": "ARMS",
    "equipment": "DUMBBELL",
    "difficulty": "BEGINNER",
    "instructions": "덤벨을 해머 그립으로 들어올립니다",
    "muscleGroups": ["BICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "트라이셉스 익스텐션",
    "category": "ARMS",
    "equipment": "DUMBBELL",
    "difficulty": "INTERMEDIATE",
    "instructions": "삼두근을 늘렸다 수축합니다",
    "muscleGroups": ["TRICEPS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "케이블 푸시다운",
    "category": "ARMS",
    "equipment": "CABLES",
    "difficulty": "BEGINNER",
    "instructions": "케이블을 아래로 밀어내립니다",
    "muscleGroups": ["TRICEPS"]
  }'

# Core exercises
curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "크런치",
    "category": "CORE",
    "equipment": "BODY_WEIGHT",
    "difficulty": "BEGINNER",
    "instructions": "상체를 구부려 복근을 수축합니다",
    "muscleGroups": ["ABS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "플랭크",
    "category": "CORE",
    "equipment": "BODY_WEIGHT",
    "difficulty": "BEGINNER",
    "instructions": "팔꿈치와 발끝으로 버팁니다",
    "muscleGroups": ["ABS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "레그레이즈",
    "category": "CORE",
    "equipment": "BODY_WEIGHT",
    "difficulty": "INTERMEDIATE",
    "instructions": "다리를 들어올려 복근을 자극합니다",
    "muscleGroups": ["ABS"]
  }'

curl -X POST "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "러시안 트위스트",
    "category": "CORE",
    "equipment": "BODY_WEIGHT",
    "difficulty": "INTERMEDIATE",
    "instructions": "상체를 좌우로 비틉니다",
    "muscleGroups": ["ABS", "OBLIQUES"]
  }'

echo ""
echo "Exercise data insertion completed!"
echo ""
echo "Checking exercises count..."
curl -s -X GET "http://localhost:8080/api/exercises" \
  -H "Authorization: Bearer $TOKEN" | grep -o '"exercises":\[[^]]*' | wc -c

echo ""
echo "Testing workout recommendation..."
curl -s -X GET "http://localhost:8080/api/workouts/recommendations/today" \
  -H "Authorization: Bearer $TOKEN"
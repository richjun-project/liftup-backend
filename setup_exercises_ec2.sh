#!/bin/bash

# EC2 운동 데이터베이스 초기화 및 설정 스크립트
# Docker 컨테이너 내부의 MySQL에 연결

echo "🔧 EC2 운동 데이터베이스 설정 시작..."
echo ""

# 1. 기존 운동 데이터 삭제
echo "📦 기존 데이터 삭제 중..."
docker exec liftupai-mysql mysql -uroot -prootpassword liftupai_db << 'EOF'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM exercise_muscle_groups;
DELETE FROM exercise_sets;
DELETE FROM workout_exercises;
DELETE FROM exercise_templates;
DELETE FROM exercises;
SET FOREIGN_KEY_CHECKS = 1;
SELECT '✅ 기존 데이터 삭제 완료' as status;
EOF

# 2. 기본 운동 데이터 추가 (350개)
echo ""
echo "📥 기본 운동 추가 중 (350개)..."
docker exec -i liftupai-mysql mysql -uroot -prootpassword liftupai_db < comprehensive_exercises_complete.sql
echo "  ✅ comprehensive_exercises_complete.sql 완료"

# 3. 추가 운동 데이터 추가 (15개 파트)
echo ""
echo "📥 추가 운동 추가 중 (part 1-15)..."
for i in {1..15}; do
    if [ -f "exercises_part_${i}.sql" ]; then
        docker exec -i liftupai-mysql mysql -uroot -prootpassword liftupai_db < exercises_part_${i}.sql
        echo "  ✅ Part $i 완료"
    else
        echo "  ⚠️  Part $i 파일 없음, 스킵"
    fi
done

# 4. 특수 운동 데이터 추가 (150개+)
echo ""
echo "📥 특수 운동 추가 중..."
docker exec -i liftupai-mysql mysql -uroot -prootpassword liftupai_db < additional_exercises.sql
echo "  ✅ additional_exercises.sql 완료"

# 5. 중복 제거
echo ""
echo "🧹 중복 운동 제거 중..."
docker exec -i liftupai-mysql mysql -uroot -prootpassword liftupai_db < remove_all_duplicates.sql
echo "  ✅ 중복 제거 완료"

# 6. 근육 그룹 매핑 추가
echo ""
echo "💪 근육 그룹 매핑 추가 중..."
docker exec -i liftupai-mysql mysql -uroot -prootpassword liftupai_db < exercise_muscle_mappings_fixed.sql
echo "  ✅ 근육 그룹 매핑 완료"

# 7. 최종 확인
echo ""
echo "=" | tr '=' '='
echo "=== 📊 최종 결과 ==="
echo "=" | tr '=' '='
docker exec liftupai-mysql mysql -uroot -prootpassword liftupai_db -e "
SELECT COUNT(*) as '전체 운동 수', COUNT(DISTINCT name) as '고유 운동 수' FROM exercises;
"

echo ""
echo "=== 📋 카테고리별 운동 수 ==="
docker exec liftupai-mysql mysql -uroot -prootpassword liftupai_db -e "
SELECT category as '카테고리', COUNT(*) as '운동 수'
FROM exercises
GROUP BY category
ORDER BY COUNT(*) DESC;
"

echo ""
echo "=== 🎯 Recommendation Tier 분포 ==="
docker exec liftupai-mysql mysql -uroot -prootpassword liftupai_db -e "
SELECT recommendation_tier as 'Tier', COUNT(*) as '운동 수'
FROM exercises
GROUP BY recommendation_tier
ORDER BY CASE recommendation_tier
  WHEN 'ESSENTIAL' THEN 1
  WHEN 'STANDARD' THEN 2
  WHEN 'ADVANCED' THEN 3
  WHEN 'SPECIALIZED' THEN 4
END;
"

echo ""
echo "=== 💪 근육 그룹 매핑 확인 ==="
docker exec liftupai-mysql mysql -uroot -prootpassword liftupai_db -e "
SELECT
  COUNT(DISTINCT exercise_id) as '매핑된 운동 수',
  COUNT(*) as '총 매핑 수'
FROM exercise_muscle_groups;
"

echo ""
echo "=" | tr '=' '='
echo "🎉 운동 데이터베이스 설정 완료!"
echo "=" | tr '=' '='
echo ""
echo "📝 참고:"
echo "  - 총 567개 운동 (중복 제거됨)"
echo "  - ESSENTIAL: 57개, STANDARD: 222개"
echo "  - ADVANCED: 112개, SPECIALIZED: 176개"
echo "  - Quick 추천에는 391개 운동 사용 (SPECIALIZED 제외)"

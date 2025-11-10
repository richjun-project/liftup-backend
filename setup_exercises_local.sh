#!/bin/bash

# 로컬 운동 데이터베이스 초기화 및 설정 스크립트
echo "운동 데이터베이스 설정 시작..."

# MySQL 연결 정보
MYSQL_HOST="127.0.0.1"
MYSQL_PORT="3308"
MYSQL_USER="root"
MYSQL_PASS="rootpassword"
MYSQL_DB="liftupai_db"

# 1. 기존 운동 데이터 삭제 (필요시)
echo "기존 데이터 삭제 중..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB << EOF
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM exercise_muscle_groups;
DELETE FROM exercise_sets;
DELETE FROM workout_exercises;
DELETE FROM exercise_templates;
DELETE FROM exercises;
SET FOREIGN_KEY_CHECKS = 1;
EOF

# 2. 기본 운동 데이터 추가 (350개)
echo "기본 운동 추가 중..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB < comprehensive_exercises_complete.sql

# 3. 추가 운동 데이터 추가 (15개 파트로 분할)
echo "추가 운동 추가 중..."
for i in {1..15}; do
    if [ -f "exercises_part_${i}.sql" ]; then
        mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB < exercises_part_${i}.sql
        echo "  Part $i 완료"
    else
        echo "  Part $i 파일 없음, 스킵"
    fi
done

# 4. 특수 운동 데이터 추가 (150개+)
echo "특수 운동 추가 중..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB < additional_exercises.sql

# 5. 중복 제거
echo "중복 운동 제거 중..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB < remove_all_duplicates.sql

# 6. 근육 그룹 매핑 추가
echo "근육 그룹 매핑 추가 중..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB < exercise_muscle_mappings_fixed.sql

# 7. 최종 확인
echo ""
echo "=== 최종 결과 ==="
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB -e "SELECT COUNT(*) as '전체 운동 수' FROM exercises;"
echo ""
echo "=== 카테고리별 운동 수 ==="
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB -e "SELECT category as '카테고리', COUNT(*) as '운동 수' FROM exercises GROUP BY category ORDER BY COUNT(*) DESC;"
echo ""
echo "=== 근육 그룹 매핑 확인 ==="
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASS $MYSQL_DB -e "SELECT COUNT(DISTINCT exercise_id) as '매핑된 운동 수', COUNT(*) as '총 매핑 수' FROM exercise_muscle_groups;"

echo ""
echo "운동 데이터베이스 설정 완료!"

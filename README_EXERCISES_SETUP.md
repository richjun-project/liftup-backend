# 운동 데이터베이스 설정 가이드

## 빠른 실행 (한 번에 모든 작업)
```bash
chmod +x setup_exercises_db.sh
./setup_exercises_db.sh
```

## 수동 실행 (단계별)

### 1. 기존 데이터 삭제 (선택사항)
```bash
mysql -u root liftupai_db << EOF
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM exercise_muscle_groups;
DELETE FROM exercise_sets;
DELETE FROM exercises;
SET FOREIGN_KEY_CHECKS = 1;
EOF
```

### 2. 운동 데이터 추가
```bash
# 기본 운동 110개
mysql -u root liftupai_db < comprehensive_exercises_complete.sql

# 추가 운동 240개 (15개 파트)
for i in {1..15}; do
    mysql -u root liftupai_db < exercises_part_${i}.sql
done

# 특수 운동 150개+
mysql -u root liftupai_db < additional_exercises.sql
```

### 3. 중복 제거
```bash
mysql -u root liftupai_db < remove_all_duplicates.sql
```

### 4. 근육 그룹 매핑 추가
```bash
mysql -u root liftupai_db < exercise_muscle_mappings_fixed.sql
```

### 5. 결과 확인
```bash
# 전체 운동 수 확인
mysql -u root liftupai_db -e "SELECT COUNT(*) FROM exercises;"

# 카테고리별 확인
mysql -u root liftupai_db -e "SELECT category, COUNT(*) FROM exercises GROUP BY category;"
```

## 필요한 SQL 파일 목록
- `comprehensive_exercises_complete.sql` - 기본 운동 110개
- `exercises_part_1.sql` ~ `exercises_part_15.sql` - 추가 운동 240개
- `additional_exercises.sql` - 특수 운동 150개+
- `remove_all_duplicates.sql` - 중복 제거 스크립트
- `exercise_muscle_mappings_fixed.sql` - 운동별 근육 그룹 매핑

## 최종 결과
- **총 567개의 고유한 운동**
- 8개 카테고리 (LEGS, SHOULDERS, BACK, ARMS, CORE, FULL_BODY, CHEST, CARDIO)
- 16개 근육 그룹과 매핑 (CHEST, BACK, LEGS, SHOULDERS, BICEPS, TRICEPS, ABS, OBLIQUES, GLUTES, HAMSTRINGS, QUADRICEPS, CALVES, LATS, TRAPS, FOREARMS, LOWER_BACK)
- 다양한 장비 타입 지원
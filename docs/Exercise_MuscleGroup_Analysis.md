# Exercise와 MuscleGroup 구조 분석

## 현재 구조 (중복 문제)

### Exercise 엔티티
```kotlin
@Entity
class Exercise {
    // ...

    // 1. 관계형 테이블로 저장 (exercise_muscle_groups 테이블)
    @ElementCollection(targetClass = MuscleGroup::class)
    @CollectionTable(name = "exercise_muscle_groups", joinColumns = [JoinColumn(name = "exercise_id")])
    @Enumerated(EnumType.STRING)
    val muscleGroups: MutableSet<MuscleGroup> = mutableSetOf(),

    // 2. 단일 컬럼으로 저장 (exercises 테이블의 muscle_group 컬럼)
    @Column
    val muscleGroup: String? = null
}
```

## 문제점

1. **데이터 중복**: 같은 정보가 두 곳에 저장
2. **일관성 문제**: 어느 것을 사용해야 할지 모호함
3. **현재 상황**:
   - `muscleGroups` (Set) → 비어있음 (데이터 없음)
   - `muscleGroup` (String) → null (데이터 없음)
   - `exercise_muscle_groups` 테이블 → 데이터 없음

## 올바른 구조

### 방법 1: ElementCollection 사용 (권장)
```kotlin
// muscleGroup 필드 제거
// muscleGroups만 사용
@ElementCollection(targetClass = MuscleGroup::class)
@CollectionTable(name = "exercise_muscle_groups", joinColumns = [JoinColumn(name = "exercise_id")])
@Column(name = "muscle_groups")  // Hibernate 기본 컬럼명
@Enumerated(EnumType.STRING)
val muscleGroups: MutableSet<MuscleGroup> = mutableSetOf()
```

**장점**:
- 한 운동이 여러 근육 그룹 타겟 가능
- 정규화된 구조
- JPA 표준 방식

### 방법 2: 단일 필드 사용
```kotlin
// muscleGroups 제거
// muscleGroup만 사용
@Column
val primaryMuscleGroup: String?  // 주요 근육 그룹만
```

**단점**:
- 한 운동이 하나의 근육만 타겟
- 제한적

## 현재 코드 사용 패턴

### WorkoutServiceV2
```kotlin
// muscleGroups Set을 사용
exercise.muscleGroups.forEach { muscleGroup ->
    muscleGroupsWorked.add(muscleGroup.name)
}
```

### StatsService
```kotlin
// 두 가지 모두 체크 (일관성 없음)
if (!log.exercise.muscleGroup.isNullOrBlank()) {
    // muscleGroup String 사용
}
if (log.exercise.muscleGroups.isNotEmpty()) {
    // muscleGroups Set 사용
}
```

## 해결 방안

### 1. 즉시 수정 (muscleGroup 필드 제거)
```kotlin
// Exercise.kt
@Entity
class Exercise {
    // muscleGroup: String? = null 제거
    // muscleGroups만 남김
}
```

### 2. 데이터 마이그레이션
```sql
-- exercise_muscle_groups 테이블에 데이터 채우기
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT id, 'CHEST' FROM exercises WHERE category = 'CHEST';
-- ... (populate_muscle_groups.sql 실행)
```

### 3. 코드 통일
- `muscleGroup` 참조하는 모든 코드를 `muscleGroups`로 변경
- StatsService의 중복 체크 로직 제거

## 테이블 구조

### exercises 테이블
- id, name, category, equipment, instructions
- ~~muscle_group~~ (제거 필요)

### exercise_muscle_groups 테이블
- exercise_id (FK)
- muscle_groups (ENUM)
- 복합 키: (exercise_id, muscle_groups)
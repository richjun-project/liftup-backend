-- 운동별 근육군 매핑 데이터 (수정 버전)
-- comprehensive_exercises_v3.sql 실행 후 이 파일을 실행하세요
USE liftupai_db;

-- 외래 키 체크 비활성화
SET FOREIGN_KEY_CHECKS = 0;

-- 기존 매핑 삭제
DELETE FROM exercise_muscle_groups;

-- 외래 키 체크 재활성화
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 1. CHEST 운동 근육 매핑
-- ============================================
-- 바벨 벤치프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '바벨 벤치프레스';

-- 인클라인 바벨 벤치프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '인클라인 바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '인클라인 바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '인클라인 바벨 벤치프레스';

-- 디클라인 바벨 벤치프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '디클라인 바벨 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '디클라인 바벨 벤치프레스';

-- 클로즈그립 벤치프레스 (삼두 위주)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '클로즈그립 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '클로즈그립 벤치프레스';

-- 와이드그립 벤치프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '와이드그립 벤치프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '와이드그립 벤치프레스';

-- 나머지 가슴 운동들
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.category = 'CHEST'
AND e.name NOT IN ('바벨 벤치프레스', '인클라인 바벨 벤치프레스', '디클라인 바벨 벤치프레스',
                   '클로즈그립 벤치프레스', '와이드그립 벤치프레스');

-- 푸시업 계열 추가 근육
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name LIKE '%푸시업%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%푸시업%';

-- 딥스 추가 근육
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name LIKE '%딥스%' AND e.category = 'CHEST';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%딥스%' AND e.category = 'CHEST';

-- ============================================
-- 2. BACK 운동 근육 매핑
-- ============================================
-- 데드리프트 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%데드리프트%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name LIKE '%데드리프트%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name LIKE '%데드리프트%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%데드리프트%';

-- 로우 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%로우%' AND e.category = 'BACK';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name LIKE '%로우%' AND e.category = 'BACK';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LATS' FROM exercises e WHERE e.name LIKE '%로우%' AND e.category = 'BACK';

-- 랫 풀다운 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LATS' FROM exercises e WHERE e.name LIKE '%풀다운%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%풀다운%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name LIKE '%풀다운%';

-- 풀업/친업 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LATS' FROM exercises e WHERE e.name LIKE '%풀업%' OR e.name LIKE '%친업%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%풀업%' OR e.name LIKE '%친업%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name LIKE '%풀업%' OR e.name LIKE '%친업%';

-- 슈러그
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name LIKE '%슈러그%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%슈러그%';

-- 페이스 풀
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '페이스 풀';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '페이스 풀';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name = '페이스 풀';

-- ============================================
-- 3. LEGS 운동 근육 매핑
-- ============================================
-- 스쿼트 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name LIKE '%스쿼트%' AND e.category = 'LEGS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name LIKE '%스쿼트%' AND e.category = 'LEGS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name LIKE '%스쿼트%' AND e.category = 'LEGS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%스쿼트%' AND e.category = 'LEGS';

-- 프론트 스쿼트 (코어 추가 강조)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '프론트 스쿼트';

-- 런지 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name LIKE '%런지%' AND e.category = 'LEGS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name LIKE '%런지%' AND e.category = 'LEGS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name LIKE '%런지%' AND e.category = 'LEGS';

-- 레그 프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name LIKE '%레그 프레스%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name LIKE '%레그 프레스%';

-- 레그 익스텐션
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'QUADRICEPS' FROM exercises e WHERE e.name LIKE '%레그 익스텐션%';

-- 레그 컬
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name LIKE '%레그 컬%';

-- 카프 레이즈
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CALVES' FROM exercises e WHERE e.name LIKE '%카프 레이즈%';

-- 힙 쓰러스트
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name = '힙 쓰러스트';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name = '힙 쓰러스트';

-- 어덕터/어브덕터
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name LIKE '%어덕터%' OR e.name LIKE '%어브덕터%';

-- ============================================
-- 4. SHOULDERS 운동 근육 매핑
-- ============================================
-- 프레스 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%프레스%' AND e.category = 'SHOULDERS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name LIKE '%프레스%' AND e.category = 'SHOULDERS';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%프레스%' AND e.category = 'SHOULDERS';

-- 레이즈 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%레이즈%' AND e.category = 'SHOULDERS';

-- 업라이트 로우
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%업라이트 로우%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name LIKE '%업라이트 로우%';

-- 리어 델트
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%리어 델트%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%리어 델트%';

-- ============================================
-- 5. ARMS 운동 근육 매핑
-- ============================================
-- 이두근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE
(e.name LIKE '%컬%' AND e.category = 'ARMS' AND e.name NOT LIKE '%리스트%' AND e.name NOT LIKE '%레그%');
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e WHERE
(e.name LIKE '%컬%' AND e.category = 'ARMS' AND e.name NOT LIKE '%리스트%' AND e.name NOT LIKE '%레그%');

-- 해머 컬 (전완 추가 강조)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e WHERE e.name LIKE '%해머 컬%';

-- 삼두근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE
(e.name LIKE '%익스텐션%' OR e.name LIKE '%푸시다운%' OR e.name LIKE '%킥백%'
OR e.name LIKE '%스컬크러셔%' OR e.name LIKE '%딥스%') AND e.category = 'ARMS';

-- 전완근 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e WHERE
(e.name LIKE '%리스트%' OR e.name LIKE '%파머스%' OR e.name LIKE '%데드 행%'
OR e.name LIKE '%핀치%') AND e.category = 'ARMS';

-- ============================================
-- 6. CORE 운동 근육 매핑
-- ============================================
-- 크런치 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name LIKE '%크런치%' AND e.category = 'CORE';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%크런치%' AND e.category = 'CORE';

-- 레그 레이즈 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name LIKE '%레이즈%' AND e.category = 'CORE';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%레이즈%' AND e.category = 'CORE';

-- 플랭크 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%플랭크%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name LIKE '%플랭크%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%플랭크%';

-- 러시안 트위스트, 우드촙 (사근)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%트위스트%' OR e.name LIKE '%우드촙%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name LIKE '%트위스트%' OR e.name LIKE '%우드촙%';

-- 팔로프 프레스
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '팔로프 프레스';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name = '팔로프 프레스';

-- 앱 롤러
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'ABS' FROM exercises e WHERE e.name LIKE '%앱 롤%' OR e.name LIKE '%롤아웃%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%앱 롤%' OR e.name LIKE '%롤아웃%';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%앱 롤%' OR e.name LIKE '%롤아웃%';

-- ============================================
-- 7. CARDIO 운동 근육 매핑
-- ============================================
-- 대부분 전신 운동
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.category = 'CARDIO';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.category = 'CARDIO';

-- 로잉 머신 (등 추가)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '로잉 머신';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BICEPS' FROM exercises e WHERE e.name = '로잉 머신';

-- 버피 (전신)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '버피';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '버피';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '버피';

-- 배틀 로프
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '배틀 로프';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '배틀 로프';

-- 케틀벨 스윙
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'CARDIO';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'CARDIO';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'CARDIO';

-- ============================================
-- 8. FULL_BODY 운동 근육 매핑
-- ============================================
-- 클린 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name LIKE '%클린%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%클린%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%클린%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name LIKE '%클린%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%클린%' AND e.category = 'FULL_BODY';

-- 스내치 계열
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name LIKE '%스내치%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name LIKE '%스내치%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name LIKE '%스내치%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name LIKE '%스내치%' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name LIKE '%스내치%' AND e.category = 'FULL_BODY';

-- 스러스터
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name = '스러스터';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '스러스터';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRICEPS' FROM exercises e WHERE e.name = '스러스터';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '스러스터';

-- 맨메이커
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CHEST' FROM exercises e WHERE e.name = '맨메이커';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '맨메이커';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '맨메이커';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name = '맨메이커';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '맨메이커';

-- 터키시 겟업
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '터키시 겟업';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '터키시 겟업';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name = '터키시 겟업';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name = '터키시 겟업';

-- 파머스 워크
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '파머스 워크';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'TRAPS' FROM exercises e WHERE e.name = '파머스 워크';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'FOREARMS' FROM exercises e WHERE e.name = '파머스 워크';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'LEGS' FROM exercises e WHERE e.name = '파머스 워크';

-- 케틀벨 스윙 (전신)
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'GLUTES' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'HAMSTRINGS' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'BACK' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'SHOULDERS' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'FULL_BODY';
INSERT INTO exercise_muscle_groups (exercise_id, muscle_groups)
SELECT e.id, 'CORE' FROM exercises e WHERE e.name = '케틀벨 스윙' AND e.category = 'FULL_BODY';
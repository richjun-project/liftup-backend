-- 추가 운동 데이터베이스 - 기존에 누락된 운동들 추가
-- 총 150개 이상의 추가 운동
USE liftupai_db;

-- ============================================
-- 추가 CHEST 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 바벨 운동
('스포토 프레스', 'CHEST', 'BARBELL', '벤치프레스 중 하단에서 2초 정지 후 프레스'),
('보드 프레스', 'CHEST', 'BARBELL', '가슴에 보드를 대고 제한된 가동범위로 프레스'),
('슬링샷 벤치프레스', 'CHEST', 'BARBELL', '슬링샷을 사용하여 과부하 벤치프레스'),
('체인 벤치프레스', 'CHEST', 'BARBELL', '체인을 달아 가변저항으로 벤치프레스'),
('밴드 벤치프레스', 'CHEST', 'BARBELL', '밴드를 사용한 가변저항 벤치프레스'),

-- 추가 덤벨 운동
('트위스트 프레스', 'CHEST', 'DUMBBELL', '덤벨을 회전시키며 프레스'),
('얼터네이팅 덤벨 프레스', 'CHEST', 'DUMBBELL', '한 팔씩 번갈아 프레스'),
('덤벨 스베드 프레스', 'CHEST', 'DUMBBELL', '스베드 벤치에서 덤벨 프레스'),
('파셜 덤벨 플라이', 'CHEST', 'DUMBBELL', '부분 가동범위로 플라이'),
('크러시 그립 덤벨 프레스', 'CHEST', 'DUMBBELL', '덤벨을 강하게 쥐고 프레스'),

-- 추가 케이블 운동
('싱글암 케이블 크로스오버', 'CHEST', 'CABLE', '한 팔로 케이블 크로스오버'),
('인클라인 케이블 플라이', 'CHEST', 'CABLE', '경사 벤치에서 케이블 플라이'),
('디클라인 케이블 플라이', 'CHEST', 'CABLE', '하향 경사에서 케이블 플라이'),
('케이블 체스트 스퀴즈', 'CHEST', 'CABLE', '케이블을 가슴 앞에서 압착'),

-- 추가 맨몸 운동
('파이크 푸시업', 'CHEST', 'BODYWEIGHT', '엉덩이를 높이 들고 푸시업'),
('스파이더맨 푸시업', 'CHEST', 'BODYWEIGHT', '푸시업 중 무릎을 팔꿈치로 당기기'),
('T-푸시업', 'CHEST', 'BODYWEIGHT', '푸시업 후 한 팔을 들어 T자 만들기'),
('스태거드 푸시업', 'CHEST', 'BODYWEIGHT', '손 위치를 엇갈리게 하여 푸시업'),
('네거티브 푸시업', 'CHEST', 'BODYWEIGHT', '천천히 내려가는 푸시업'),
('플라이오메트릭 푸시업', 'CHEST', 'BODYWEIGHT', '폭발적으로 밀어올리는 푸시업');

-- ============================================
-- 추가 BACK 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 바벨 운동
('트랩바 데드리프트', 'BACK', 'BARBELL', '트랩바로 데드리프트 수행'),
('디피시트 데드리프트', 'BACK', 'BARBELL', '플레이트 위에 서서 데드리프트'),
('파워 슈러그', 'BACK', 'BARBELL', '폭발적으로 슈러그 수행'),
('하이 풀', 'BACK', 'BARBELL', '골반 높이에서 바를 어깨까지 당기기'),
('체스트 서포티드 바벨 로우', 'BACK', 'BARBELL', '가슴을 지지하고 바벨 로우'),
('리브스 로우', 'BACK', 'BARBELL', '플레이트를 잡고 로우'),

-- 추가 덤벨 운동
('인클라인 덤벨 로우', 'BACK', 'DUMBBELL', '경사 벤치에 엎드려 로우'),
('바이어스 덤벨 로우', 'BACK', 'DUMBBELL', '한쪽으로 치우쳐 로우'),
('트라이포드 로우', 'BACK', 'DUMBBELL', '세 점 지지로 로우'),
('펜들레이 덤벨 로우', 'BACK', 'DUMBBELL', '덤벨로 펜들레이 로우'),
('덤벨 하이 풀', 'BACK', 'DUMBBELL', '덤벨을 폭발적으로 어깨까지'),

-- 추가 케이블 운동
('하프 니드 케이블 로우', 'BACK', 'CABLE', '한 무릎 꿇고 케이블 로우'),
('케이블 Y-레이즈', 'BACK', 'CABLE', 'Y자 모양으로 케이블 당기기'),
('케이블 W-레이즈', 'BACK', 'CABLE', 'W자 모양으로 케이블 당기기'),
('크로스오버 랫 풀다운', 'BACK', 'CABLE', '케이블을 교차하여 풀다운'),
('케이블 풀오버', 'BACK', 'CABLE', '케이블로 풀오버 동작'),

-- 추가 맨몸 운동
('커맨도 풀업', 'BACK', 'BODYWEIGHT', '바를 옆으로 잡고 풀업'),
('L-싯 풀업', 'BACK', 'BODYWEIGHT', 'L자 자세로 풀업'),
('타월 풀업', 'BACK', 'BODYWEIGHT', '수건을 잡고 풀업'),
('레버 프론트', 'BACK', 'BODYWEIGHT', '몸을 수평으로 유지'),
('스캡 풀업', 'BACK', 'BODYWEIGHT', '견갑골만 움직이는 풀업');

-- ============================================
-- 추가 LEGS 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 바벨 운동
('앤더슨 스쿼트', 'LEGS', 'BARBELL', '바닥 위치에서 시작하는 스쿼트'),
('핀 스쿼트', 'LEGS', 'BARBELL', '안전바에서 시작하는 스쿼트'),
('템포 스쿼트', 'LEGS', 'BARBELL', '정해진 템포로 스쿼트'),
('1.5 스쿼트', 'LEGS', 'BARBELL', '하단에서 반복 후 올라오기'),
('클러스터 스쿼트', 'LEGS', 'BARBELL', '짧은 휴식으로 연속 스쿼트'),
('스플릿 스쿼트', 'LEGS', 'BARBELL', '한 발 앞으로 하여 스쿼트'),
('커티시 런지', 'LEGS', 'BARBELL', '뒷발을 교차하여 런지'),
('프론트 랙 런지', 'LEGS', 'BARBELL', '프론트 랙 자세로 런지'),
('리버스 바벨 런지', 'LEGS', 'BARBELL', '뒤로 스텝하며 런지'),
('랜드마인 스쿼트', 'LEGS', 'BARBELL', '랜드마인 바벨로 스쿼트'),
('제퍼슨 스쿼트', 'LEGS', 'BARBELL', '바벨을 다리 사이에 두고 스쿼트'),

-- 추가 덤벨 운동
('펄스 스쿼트', 'LEGS', 'DUMBBELL', '하단에서 작은 반복 동작'),
('서커스 스쿼트', 'LEGS', 'DUMBBELL', '와이드 스탠스 스쿼트'),
('코사크 스쿼트', 'LEGS', 'DUMBBELL', '옆으로 이동하며 스쿼트'),
('쉬림프 스쿼트', 'LEGS', 'DUMBBELL', '뒷발을 잡고 한 다리 스쿼트'),
('점프 스쿼트 투 프레스', 'LEGS', 'DUMBBELL', '점프 스쿼트 후 프레스'),

-- 추가 머신 운동
('벨트 스쿼트', 'LEGS', 'MACHINE', '벨트에 무게를 달고 스쿼트'),
('시시 스쿼트 머신', 'LEGS', 'MACHINE', '시시 스쿼트 머신 사용'),
('노르딕 컬 머신', 'LEGS', 'MACHINE', '노르딕 컬 머신 사용'),
('글루트 드라이브', 'LEGS', 'MACHINE', '글루트 전용 머신'),
('바이킹 프레스', 'LEGS', 'MACHINE', '바이킹 프레스 머신'),

-- 추가 맨몸 운동
('불가리안 점프 스쿼트', 'LEGS', 'BODYWEIGHT', '불가리안 자세로 점프'),
('리버스 노르딕 컬', 'LEGS', 'BODYWEIGHT', '쿼드 중심 노르딕 컬'),
('싱글 레그 박스 점프', 'LEGS', 'BODYWEIGHT', '한 다리로 박스 점프'),
('라테럴 바운드', 'LEGS', 'BODYWEIGHT', '옆으로 뛰기'),
('크로스오버 스텝업', 'LEGS', 'BODYWEIGHT', '교차하여 스텝업');

-- ============================================
-- 추가 SHOULDERS 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 바벨 운동
('스콧 프레스', 'SHOULDERS', 'BARBELL', '프리처 벤치에서 프레스'),
('파셜 프레스', 'SHOULDERS', 'BARBELL', '부분 가동범위 프레스'),
('핀 프레스', 'SHOULDERS', 'BARBELL', '안전바에서 시작하는 프레스'),
('소츠 프레스', 'SHOULDERS', 'BARBELL', '스쿼트 자세에서 프레스'),
('킵 프레스', 'SHOULDERS', 'BARBELL', '반동을 이용한 프레스'),

-- 추가 덤벨 운동
('파웰 레이즈', 'SHOULDERS', 'DUMBBELL', '사선 각도로 레이즈'),
('폴리켓 레이즈', 'SHOULDERS', 'DUMBBELL', '엄지 올리며 레이즈'),
('루 레이즈', 'SHOULDERS', 'DUMBBELL', '전면과 측면 동시 레이즈'),
('체이트 레터럴', 'SHOULDERS', 'DUMBBELL', '반동을 이용한 레터럴'),
('파셜 레터럴', 'SHOULDERS', 'DUMBBELL', '부분 가동범위 레터럴'),
('6-웨이 레이즈', 'SHOULDERS', 'DUMBBELL', '6방향으로 레이즈'),
('아라운드 더 월드', 'SHOULDERS', 'DUMBBELL', '원을 그리며 덤벨 움직이기'),

-- 추가 케이블 운동
('케이블 내회전', 'SHOULDERS', 'CABLE', '어깨 내회전 운동'),
('케이블 외회전', 'SHOULDERS', 'CABLE', '어깨 외회전 운동'),
('하이 풀 케이블', 'SHOULDERS', 'CABLE', '높은 위치에서 당기기'),
('로우 풀 케이블', 'SHOULDERS', 'CABLE', '낮은 위치에서 당기기'),

-- 추가 특수 운동
('랜드마인 레이즈', 'SHOULDERS', 'BARBELL', '랜드마인 바벨로 레이즈'),
('플레이트 프론트 레이즈', 'SHOULDERS', 'OTHER', '원판으로 프론트 레이즈'),
('밴드 페이스 풀', 'SHOULDERS', 'OTHER', '밴드로 페이스 풀'),
('밴드 어파트', 'SHOULDERS', 'OTHER', '밴드를 옆으로 당기기');

-- ============================================
-- 추가 ARMS 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 이두근 운동
('베이비언 컬', 'ARMS', 'BARBELL', '뒤로 기울여 컬'),
('체이트 컬', 'ARMS', 'BARBELL', '반동을 이용한 컬'),
('파셜 컬', 'ARMS', 'BARBELL', '부분 가동범위 컬'),
('스쿼트 컬', 'ARMS', 'DUMBBELL', '스쿼트 자세에서 컬'),
('고릴라 컬', 'ARMS', 'DUMBBELL', '앞으로 숙여 컬'),
('제프 컬', 'ARMS', 'DUMBBELL', '뒤로 기울인 인클라인 컬'),
('웨이스트 레벨 컬', 'ARMS', 'CABLE', '허리 높이에서 컬'),
('하이 풀리 컬', 'ARMS', 'CABLE', '높은 위치에서 컬'),
('베이비언 케이블 컬', 'ARMS', 'CABLE', '케이블로 베이비언 컬'),

-- 추가 삼두근 운동
('플로어 스컬크러셔', 'ARMS', 'BARBELL', '바닥에서 스컬크러셔'),
('인클라인 스컬크러셔', 'ARMS', 'BARBELL', '경사에서 스컬크러셔'),
('데클라인 스컬크러셔', 'ARMS', 'BARBELL', '하향 경사 스컬크러셔'),
('싱글암 푸시다운', 'ARMS', 'CABLE', '한 팔 푸시다운'),
('크로스바디 푸시다운', 'ARMS', 'CABLE', '몸을 가로질러 푸시다운'),
('밴드 푸시다운', 'ARMS', 'OTHER', '밴드로 푸시다운'),
('밴드 오버헤드 익스텐션', 'ARMS', 'OTHER', '밴드로 오버헤드 익스텐션'),

-- 추가 전완근 운동
('바벨 롤러', 'ARMS', 'BARBELL', '바벨을 굴리며 전완 운동'),
('플레이트 컬', 'ARMS', 'OTHER', '원판 가장자리를 잡고 컬'),
('헥스 홀드', 'ARMS', 'DUMBBELL', '헥스 덤벨 머리 부분 잡기'),
('타월 행', 'ARMS', 'OTHER', '수건 잡고 버티기'),
('그리퍼 운동', 'ARMS', 'OTHER', '악력기 운동');

-- ============================================
-- 추가 CORE 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 복근 운동
('잭나이프', 'CORE', 'BODYWEIGHT', '상하체를 동시에 접기'),
('인치 웜', 'CORE', 'BODYWEIGHT', '손으로 걸어나가기'),
('앨리게이터 크롤', 'CORE', 'BODYWEIGHT', '악어처럼 기어가기'),
('스타 플랭크', 'CORE', 'BODYWEIGHT', '팔다리를 벌린 플랭크'),
('플랭크 투 푸시업', 'CORE', 'BODYWEIGHT', '플랭크에서 푸시업 자세로'),
('롤링 플랭크', 'CORE', 'BODYWEIGHT', '옆으로 구르며 플랭크'),
('바나나 홀드', 'CORE', 'BODYWEIGHT', '바나나 모양으로 유지'),
('V-싯 홀드', 'CORE', 'BODYWEIGHT', 'V자 자세 유지'),
('스프린터 싯업', 'CORE', 'BODYWEIGHT', '달리기 자세로 싯업'),

-- 추가 케이블/웨이트 운동
('랜드마인 로테이션', 'CORE', 'BARBELL', '랜드마인 바벨 회전'),
('랜드마인 180', 'CORE', 'BARBELL', '180도 회전'),
('안티 로테이션 프레스', 'CORE', 'CABLE', '회전을 막는 프레스'),
('데드 버그 프레스', 'CORE', 'DUMBBELL', '데드버그 자세로 프레스'),
('오버헤드 팔로프 프레스', 'CORE', 'CABLE', '머리 위로 팔로프 프레스'),

-- 추가 특수 운동
('GHR 싯업', 'CORE', 'MACHINE', 'GHR 머신에서 싯업'),
('보스 볼 플랭크', 'CORE', 'OTHER', '보스볼 위에서 플랭크'),
('TRX 니 턱', 'CORE', 'OTHER', 'TRX로 니 턱'),
('TRX 파이크', 'CORE', 'OTHER', 'TRX로 파이크'),
('메디신볼 V-업', 'CORE', 'OTHER', '메디신볼로 V-업');

-- ============================================
-- 추가 CARDIO 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 HIIT 운동
('스타 점프', 'CARDIO', 'BODYWEIGHT', '팔다리 벌려 점프'),
('플랭크 잭', 'CARDIO', 'BODYWEIGHT', '플랭크 자세에서 다리 벌리기'),
('베어 크롤 스프린트', 'CARDIO', 'BODYWEIGHT', '빠르게 베어 크롤'),
('크랩 워크', 'CARDIO', 'BODYWEIGHT', '게처럼 걷기'),
('인치웜 투 푸시업', 'CARDIO', 'BODYWEIGHT', '인치웜 후 푸시업'),
('스케이터 홉', 'CARDIO', 'BODYWEIGHT', '스케이트 타듯 옆으로 뛰기'),
('토 탭', 'CARDIO', 'BODYWEIGHT', '발끝 빠르게 터치'),
('X-점프', 'CARDIO', 'BODYWEIGHT', 'X자 모양으로 점프'),

-- 추가 장비 운동
('버서', 'CARDIO', 'MACHINE', '버서 머신 운동'),
('커브드 트레드밀', 'CARDIO', 'MACHINE', '커브드 트레드밀 달리기'),
('제이콥스 래더', 'CARDIO', 'MACHINE', '사다리 오르기 머신'),
('에어다인', 'CARDIO', 'MACHINE', '에어다인 바이크'),
('팬 바이크', 'CARDIO', 'MACHINE', '팬 저항 바이크'),

-- 추가 기능성 유산소
('래더 드릴', 'CARDIO', 'OTHER', '사다리 발놀림 운동'),
('콘 드릴', 'CARDIO', 'OTHER', '콘 사이 달리기'),
('셔틀 스프린트', 'CARDIO', 'BODYWEIGHT', '왕복 전력질주'),
('힐 스프린트', 'CARDIO', 'BODYWEIGHT', '언덕 달리기'),
('비치 런', 'CARDIO', 'BODYWEIGHT', '모래사장 달리기');

-- ============================================
-- 추가 FULL_BODY 운동
-- ============================================
INSERT INTO exercises (name, category, equipment, instructions) VALUES
-- 추가 올림픽 변형
('블록 클린', 'FULL_BODY', 'BARBELL', '블록 위에서 클린'),
('블록 스내치', 'FULL_BODY', 'BARBELL', '블록 위에서 스내치'),
('노 풋 클린', 'FULL_BODY', 'BARBELL', '발 움직임 없이 클린'),
('노 풋 스내치', 'FULL_BODY', 'BARBELL', '발 움직임 없이 스내치'),
('컴플렉스 클린', 'FULL_BODY', 'BARBELL', '여러 클린 동작 연속'),
('컴플렉스 스내치', 'FULL_BODY', 'BARBELL', '여러 스내치 동작 연속'),

-- 추가 복합 운동
('덤벨 스내치', 'FULL_BODY', 'DUMBBELL', '덤벨로 스내치'),
('덤벨 클린 앤 저크', 'FULL_BODY', 'DUMBBELL', '덤벨로 클린 앤 저크'),
('케틀벨 고블릿 스쿼트 투 프레스', 'FULL_BODY', 'KETTLEBELL', '고블릿 스쿼트 후 프레스'),
('케틀벨 롱 사이클', 'FULL_BODY', 'KETTLEBELL', '클린 앤 저크 반복'),
('더블 케틀벨 스윙', 'FULL_BODY', 'KETTLEBELL', '양손에 케틀벨 스윙'),
('더블 케틀벨 클린', 'FULL_BODY', 'KETTLEBELL', '양손 케틀벨 클린'),

-- 추가 캐리 운동
('제르처 캐리', 'FULL_BODY', 'BARBELL', '제르처 자세로 걷기'),
('프론트 랙 캐리', 'FULL_BODY', 'BARBELL', '프론트 랙 자세로 걷기'),
('오버헤드 캐리', 'FULL_BODY', 'BARBELL', '바벨 머리 위로 들고 걷기'),
('믹스드 캐리', 'FULL_BODY', 'DUMBBELL', '다른 무게 들고 걷기'),
('바텀업 케틀벨 캐리', 'FULL_BODY', 'KETTLEBELL', '케틀벨 뒤집어 들고 걷기'),

-- 스트롱맨 운동
('로그 클린 앤 프레스', 'FULL_BODY', 'OTHER', '통나무 들어 프레스'),
('액슬 바 데드리프트', 'FULL_BODY', 'OTHER', '두꺼운 바 데드리프트'),
('프레임 캐리', 'FULL_BODY', 'OTHER', '프레임 들고 걷기'),
('허슬 스톤', 'FULL_BODY', 'OTHER', '돌 들어 올리기'),
('코난 휠', 'FULL_BODY', 'OTHER', '무거운 바퀴 밀기');
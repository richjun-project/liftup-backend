-- 카프 레이즈를 ESSENTIAL에서 STANDARD로 이동
-- 카프 운동은 보조 운동이므로 메인 추천에서 우선순위를 낮춤

UPDATE exercises SET recommendation_tier = 'STANDARD'
WHERE name IN (
    '스탠딩 카프 레이즈',
    '시티드 카프 레이즈',
    '레그프레스 카프 레이즈',
    '덤벨 카프 레이즈',
    '스미스머신 카프 레이즈',
    '싱글 레그 카프 레이즈',
    '동키 카프 레이즈'
);

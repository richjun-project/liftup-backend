-- 최근 N일 동안 결과가 0건이었던 검색어 상위 목록 (운동 검색 품질 점검용).
-- search_query_log 에는 사용자 식별자가 없다. 질의는 정규화본(소문자·공백 정리)이고, 시각은 UTC 다.
--
-- 사용:  mysql liftupai < scripts/ops/search_zero_results.sql        (기본 7일, 상위 50개)
--        기간은 아래 @days, 개수는 맨 아래 LIMIT 를 고친다.
-- 결과를 평가셋(src/test/resources/search-eval)에 옮길 때는 query_text 와 locale 을 쓰면 된다.
SET @days = 7;

SELECT query_text,
       locale,
       COUNT(*)        AS searches,
       MAX(created_at) AS last_seen
FROM search_query_log
WHERE result_count = 0
  AND created_at >= TIMESTAMPADD(DAY, -@days, CURRENT_TIMESTAMP)
GROUP BY query_text, locale
ORDER BY searches DESC, last_seen DESC
LIMIT 50;

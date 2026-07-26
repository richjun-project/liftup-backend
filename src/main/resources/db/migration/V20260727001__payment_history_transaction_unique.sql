-- LiftUp AI: RevenueCat 웹훅 재전송 시 payment_history 중복 기록 방지.
--
-- 웹훅은 전송 보장을 위해 같은 이벤트를 재전송할 수 있는데, savePaymentHistory에
-- transaction_id 중복 방지가 없어 결제 이력이 중복 적재될 수 있었다 (2026-07-26 테스트
-- 작성 중 발견). 구독 상태는 멱등이라 Pro 이중 부여는 없었지만 이력 정합성 문제.
-- 코드 레벨 존재 검사 + DB 유니크 제약(백스톱)을 함께 배포한다.
--
-- 2026-07-27 실사: 16행 전부 고유 transaction_id — 제약 추가 안전.
-- 방어적으로 혹시 모를 중복(동일 transaction_id, NULL 제외)을 먼저 제거한다.
-- MySQL error 1093 회피를 위해 temp 테이블 사용.

CREATE TEMPORARY TABLE _ph_dup_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO _ph_dup_ids (id)
SELECT ph.id
FROM payment_history ph
JOIN payment_history keep
    ON keep.transaction_id = ph.transaction_id
    AND keep.id < ph.id
WHERE ph.transaction_id IS NOT NULL;

DELETE FROM payment_history WHERE id IN (SELECT id FROM _ph_dup_ids);

DROP TEMPORARY TABLE _ph_dup_ids;

-- NULL transaction_id는 유니크 인덱스에서 중복 허용됨 (MySQL 표준 동작) — 의도된 것.
ALTER TABLE payment_history
    ADD CONSTRAINT uk_payment_history_transaction_id UNIQUE (transaction_id);

-- 구독 출처(RevenueCat store: APP_STORE / PLAY_STORE / PROMOTIONAL ...)를 기록한다.
-- 이게 없어서 프로모션으로 부여한 평생 이용권과 실결제 구독을 DB 에서 구분할 수 없었고,
-- 매출·유료 사용자 지표가 RevenueCat 과 어긋났다.
ALTER TABLE subscriptions ADD COLUMN store VARCHAR(32) NULL;

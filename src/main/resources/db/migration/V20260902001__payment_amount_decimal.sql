-- 결제 금액이 정수로 저장돼 소수점이 잘렸다(예: USD 4.99 -> 4).
-- RevenueCat의 price_in_purchased_currency(실제 결제 통화 금액)를 손실 없이 담기 위해 DECIMAL로 넓힌다.
ALTER TABLE payment_history MODIFY COLUMN amount DECIMAL(12,2) NOT NULL;

-- V20260902001에서 DECIMAL로 바꿨으나 엔티티(Kotlin Double)는 DOUBLE을 기대해
-- Hibernate 스키마 검증이 실패했다. 엔티티 타입에 맞춰 DOUBLE로 정정한다.
ALTER TABLE payment_history MODIFY COLUMN amount DOUBLE NOT NULL;

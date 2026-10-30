package com.richjun.liftupai.domain.subscription.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findByUser(user: User): Optional<Subscription>

    fun findByUser_Id(userId: Long): Optional<Subscription>

    fun findByStatus(status: SubscriptionStatus): List<Subscription>

    @Query("SELECT s FROM Subscription s WHERE s.status = :status AND s.expiryDate < :now")
    fun findExpiredSubscriptions(status: SubscriptionStatus, now: LocalDateTime): List<Subscription>

    fun findByGooglePlayOrderId(orderId: String): Optional<Subscription>

    fun findByAppleTransactionId(transactionId: String): Optional<Subscription>

    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status = :status")
    fun countByStatus(status: SubscriptionStatus): Long

    /** 만료가 (from, to] 사이인 스토어 구독 — 체험 종료 알림 후보. 사용자를 함께 읽는다(스케줄러는 트랜잭션 밖). */
    @Query("SELECT s FROM Subscription s JOIN FETCH s.user WHERE s.status = :status AND s.store IN :stores AND s.expiryDate > :from AND s.expiryDate <= :to")
    fun findStoreSubscriptionsExpiringBetween(
        status: SubscriptionStatus,
        stores: Collection<String>,
        from: LocalDateTime,
        to: LocalDateTime
    ): List<Subscription>
}
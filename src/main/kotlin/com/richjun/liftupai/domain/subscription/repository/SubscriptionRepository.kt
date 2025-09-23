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
}
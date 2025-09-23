package com.richjun.liftupai.domain.subscription.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.subscription.entity.PaymentHistory
import com.richjun.liftupai.domain.subscription.entity.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PaymentHistoryRepository : JpaRepository<PaymentHistory, Long> {
    fun findByUser(user: User): List<PaymentHistory>

    fun findByUserOrderByPaymentDateDesc(user: User): List<PaymentHistory>

    fun findByTransactionId(transactionId: String): PaymentHistory?

    fun findByUserAndStatus(user: User, status: PaymentStatus): List<PaymentHistory>

    fun findByPaymentDateBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<PaymentHistory>
}
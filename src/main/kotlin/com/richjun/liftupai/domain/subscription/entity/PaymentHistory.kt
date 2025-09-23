package com.richjun.liftupai.domain.subscription.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "payment_history")
data class PaymentHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    val subscription: Subscription,

    @Column(nullable = false)
    val amount: Int,

    @Column(nullable = false)
    val currency: String = "KRW",

    @Column(nullable = false)
    val paymentMethod: String,

    @Column
    val transactionId: String? = null,

    @Column
    val receiptData: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: PaymentStatus = PaymentStatus.PENDING,

    @Column(nullable = false)
    val paymentDate: LocalDateTime = LocalDateTime.now(),

    @Column
    val failureReason: String? = null
)

enum class PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    CANCELLED
}
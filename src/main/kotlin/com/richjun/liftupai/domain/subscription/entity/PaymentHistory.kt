package com.richjun.liftupai.domain.subscription.entity

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "payment_history")
class PaymentHistory(
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
    val paymentDate: LocalDateTime = AppTime.utcNow(),

    @Column
    val failureReason: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentHistory) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    CANCELLED
}

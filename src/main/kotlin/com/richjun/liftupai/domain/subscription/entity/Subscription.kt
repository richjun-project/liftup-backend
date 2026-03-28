package com.richjun.liftupai.domain.subscription.entity

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "subscriptions")
class Subscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var plan: SubscriptionPlan = SubscriptionPlan.FREE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE,

    @Column(nullable = false)
    var startDate: LocalDateTime = AppTime.utcNow(),

    @Column
    var expiryDate: LocalDateTime? = null,

    @Column
    var cancelledAt: LocalDateTime? = null,

    @Column
    var googlePlayOrderId: String? = null,

    @Column
    var appleTransactionId: String? = null,

    @Column
    var autoRenew: Boolean = false,

    @Column(nullable = false)
    val createdAt: LocalDateTime = AppTime.utcNow(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = AppTime.utcNow()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Subscription) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

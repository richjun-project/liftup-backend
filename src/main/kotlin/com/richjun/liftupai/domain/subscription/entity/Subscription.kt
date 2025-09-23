package com.richjun.liftupai.domain.subscription.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "subscriptions")
data class Subscription(
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
    var startDate: LocalDateTime = LocalDateTime.now(),

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
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
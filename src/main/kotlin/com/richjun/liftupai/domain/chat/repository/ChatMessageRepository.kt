package com.richjun.liftupai.domain.chat.repository

import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.auth.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findByUserOrderByTimestampDesc(user: User, pageable: Pageable): Page<ChatMessage>

    fun findByUserAndTimestampBetweenOrderByTimestampDesc(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        pageable: Pageable
    ): Page<ChatMessage>

    @Query("SELECT COUNT(c) FROM ChatMessage c WHERE c.user = :user")
    fun countByUser(user: User): Long

    fun deleteAllByUser(user: User)

    @Query("SELECT c FROM ChatMessage c WHERE c.user = :user AND c.timestamp >= :date ORDER BY c.timestamp DESC")
    fun findRecentMessages(user: User, date: LocalDateTime): List<ChatMessage>
}
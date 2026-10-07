package com.richjun.liftupai.domain.workout.search.ops

import com.richjun.liftupai.global.time.AppTime
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.text.Normalizer
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 검색 운영 로그 기록기 — 요청 스레드는 메모리 큐에 넣기만 하고(논블로킹), 스케줄러가 묶어서 한 번에 INSERT 한다.
 * 큐가 가득 차면 새 기록을 버린다(검색이 로그 때문에 느려지거나 메모리를 먹지 않게). 종료 시에는 남은 기록을 비운다.
 * 사용자 식별자는 받지도 저장하지도 않는다.
 */
@Component
class SearchOpsRecorder(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${app.search-ops.enabled:true}") private val enabled: Boolean = true,
    @Value("\${app.search-ops.retention-days:30}") private val retentionDays: Int = 30
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private class Entry(
        val query: String, val locale: String, val resultCount: Int,
        val latencyMs: Int, val source: String, val hasFilters: Boolean, val at: LocalDateTime
    )

    private val queue = ArrayBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val dropped = AtomicLong()

    /** 큐 가득참으로 버린 기록 수 (운영 점검용) */
    fun droppedCount(): Long = dropped.get()

    fun pendingCount(): Int = queue.size

    /**
     * 검색 한 번을 기록한다. 빈 질의(브라우즈)·개인정보 의심 질의는 기록하지 않는다.
     * @return 큐에 넣었으면 true
     */
    fun record(rawQuery: String?, locale: String?, resultCount: Int, latencyMs: Long, source: String, hasFilters: Boolean = false): Boolean {
        if (!enabled) return false
        val query = normalize(rawQuery) ?: return false
        val entry = Entry(
            query = query,
            locale = normalizeLocale(locale),
            resultCount = resultCount.coerceAtLeast(0),
            latencyMs = latencyMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            source = source.take(20),
            hasFilters = hasFilters,
            at = AppTime.utcNow()
        )
        if (!queue.offer(entry)) {
            dropped.incrementAndGet()
            return false
        }
        return true
    }

    /** 테스트가 직접 flush() 로 검증할 때 스케줄러가 끼어들지 않게 끄는 스위치 */
    @Volatile
    internal var autoFlush = true

    @Scheduled(fixedDelayString = "\${app.search-ops.flush-interval-ms:5000}", initialDelayString = "\${app.search-ops.flush-interval-ms:5000}")
    fun scheduledFlush() {
        if (autoFlush) flush()
    }

    /** 큐를 비워 DB 에 배치로 쓴다. 쓴 개수를 돌려준다. (스케줄러·종료·테스트에서 호출) */
    fun flush(): Int {
        var written = 0
        repeat(MAX_BATCHES_PER_FLUSH) {
            val batch = ArrayList<Entry>(BATCH_SIZE)
            queue.drainTo(batch, BATCH_SIZE)
            if (batch.isEmpty()) return written
            try {
                jdbcTemplate.batchUpdate(
                    "INSERT INTO search_query_log (query_text, locale, result_count, latency_ms, source, has_filters, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    batch, batch.size
                ) { ps, e ->
                    ps.setString(1, e.query)
                    ps.setString(2, e.locale)
                    ps.setInt(3, e.resultCount)
                    ps.setInt(4, e.latencyMs)
                    ps.setString(5, e.source)
                    ps.setBoolean(6, e.hasFilters)
                    ps.setTimestamp(7, Timestamp.valueOf(e.at))
                }
                written += batch.size
            } catch (e: Exception) {
                // 로그 때문에 검색이 영향받으면 안 된다 — 이 배치는 버리고 다음 주기에 이어간다
                logger.warn("Search ops log flush failed, dropped {} rows: {}", batch.size, e.message)
            }
        }
        return written
    }

    /** 보관 기간이 지난 기록 삭제 (매일 새벽) */
    @Scheduled(cron = "0 17 4 * * *")
    fun purgeOld(): Int {
        if (!enabled) return 0
        return try {
            val cutoff = Timestamp.valueOf(AppTime.utcNow().minusDays(retentionDays.coerceAtLeast(1).toLong()))
            jdbcTemplate.update("DELETE FROM search_query_log WHERE created_at < ?", cutoff)
        } catch (e: Exception) {
            logger.warn("Search ops log purge failed: {}", e.message)
            0
        }
    }

    @PreDestroy
    fun shutdown() {
        flush()
    }

    companion object {
        private const val QUEUE_CAPACITY = 10_000
        private const val BATCH_SIZE = 500
        private const val MAX_BATCHES_PER_FLUSH = 20
        const val MAX_QUERY_LENGTH = 100

        private val WHITESPACE = Regex("\\s+")
        private val SENSITIVE = Regex("@|https?://|\\d{6,}", RegexOption.IGNORE_CASE)

        /** 정규화: NFKC(전각→반각)·소문자·제어문자 제거·공백 정리·길이 제한. 비었거나 개인정보로 보이면 null */
        fun normalize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .replace(WHITESPACE, " ")
                .trim()
            if (folded.isEmpty() || SENSITIVE.containsMatchIn(folded)) return null
            return if (folded.codePointCount(0, folded.length) > MAX_QUERY_LENGTH)
                folded.substring(0, folded.offsetByCodePoints(0, MAX_QUERY_LENGTH)).trim()
            else folded
        }

        private fun normalizeLocale(raw: String?): String {
            val l = raw?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
            return if (l.isEmpty()) "en" else l.take(10)
        }
    }
}

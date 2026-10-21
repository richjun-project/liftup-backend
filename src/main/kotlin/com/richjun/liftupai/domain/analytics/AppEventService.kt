package com.richjun.liftupai.domain.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱 활성화 이벤트 수집. 허용 목록의 이벤트만, 정제한 파라미터로, 사용자별 분당 상한 안에서 저장한다.
 * 분석용 기록이라 어떤 실패도 앱 흐름을 막지 않는다 — 규칙에 맞지 않는 것은 조용히 버리고 개수만 돌려준다.
 */
@Service
class AppEventService(
    private val repository: AppEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.events.enabled:true}") private val enabled: Boolean = true,
    @Value("\${app.events.per-user-per-minute:120}") private val perUserPerMinute: Int = 120,
    @Value("\${app.events.retention-days:180}") private val retentionDays: Int = 180
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    data class Result(val accepted: Int, val dropped: Int)

    private class Window(var minute: Long, var count: Int)
    private val windows = ConcurrentHashMap<Long, Window>()

    @Transactional
    fun record(userId: Long, items: List<AppEventItem>?, client: ClientInfo): Result {
        val all = items.orEmpty()
        if (!enabled || all.isEmpty()) return Result(0, all.size)
        val now = AppTime.utcNow()
        val candidates = all.take(MAX_BATCH).mapNotNull { item ->
            val name = item.name?.trim()?.takeIf { it in ALLOWED_EVENTS } ?: return@mapNotNull null
            AppEvent(
                userId = userId,
                name = name,
                params = sanitizeParams(item.params),
                appVersion = client.appVersion,
                platform = client.platform,
                testDevice = client.testDevice,
                occurredAt = occurredAt(item.ts, now),
                createdAt = now
            )
        }
        val allowed = reserve(userId, candidates.size)
        val toSave = candidates.take(allowed)
        if (toSave.isNotEmpty()) repository.saveAll(toSave)
        return Result(toSave.size, all.size - toSave.size)
    }

    /** 사용자별 분당 상한 — 이번 요청에서 저장해도 되는 개수 */
    private fun reserve(userId: Long, wanted: Int): Int {
        if (wanted <= 0) return 0
        val minute = System.currentTimeMillis() / 60_000L
        if (windows.size > MAX_TRACKED_USERS) windows.entries.removeIf { it.value.minute < minute }
        val w = windows.computeIfAbsent(userId) { Window(minute, 0) }
        synchronized(w) {
            if (w.minute != minute) { w.minute = minute; w.count = 0 }
            val granted = (perUserPerMinute - w.count).coerceIn(0, wanted)
            w.count += granted
            return granted
        }
    }

    /** 평평한 키-값만 남긴다: 키 규칙, 값은 코드형 문자열·유한한 숫자·불리언. 직렬화 길이 상한을 넘으면 뒤의 키를 버린다. */
    internal fun sanitizeParams(params: Map<String, Any?>?): String? {
        if (params.isNullOrEmpty()) return null
        val out = sortedMapOf<String, Any>()
        for ((rawKey, value) in params) {
            if (out.size >= MAX_PARAMS) break
            val key = rawKey.trim().takeIf { KEY.matches(it) } ?: continue
            val clean: Any = when (value) {
                is Boolean -> value
                is Int, is Long, is Short -> (value as Number).toLong()
                is Number -> value.toDouble().takeIf { it.isFinite() } ?: continue
                is String -> value.trim().takeIf { CODE_VALUE.matches(it) } ?: continue
                else -> continue
            }
            out[key] = clean
        }
        if (out.isEmpty()) return null
        var json = objectMapper.writeValueAsString(out)
        while (json.length > MAX_PARAMS_JSON && out.isNotEmpty()) {
            out.remove(out.lastKey())
            json = objectMapper.writeValueAsString(out)
        }
        return json.takeIf { out.isNotEmpty() }
    }

    private fun occurredAt(ts: Long?, now: LocalDateTime): LocalDateTime? {
        if (ts == null || ts <= 0) return null
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC)
        return at.takeIf { it.isAfter(now.minusDays(7)) && it.isBefore(now.plusMinutes(10)) }
    }

    /** 보관 기간이 지난 이벤트 삭제(매일 새벽) */
    @Scheduled(cron = "0 23 4 * * *")
    @Transactional
    fun purgeOld(): Int = try {
        repository.deleteOlderThan(AppTime.utcNow().minusDays(retentionDays.coerceAtLeast(1).toLong()))
    } catch (e: Exception) {
        logger.warn("App event purge failed: {}", e.message)
        0
    }

    companion object {
        const val MAX_BATCH = 20
        const val MAX_PARAMS = 25
        const val MAX_PARAMS_JSON = 2000
        private const val MAX_TRACKED_USERS = 10_000
        private val KEY = Regex("^[a-z][a-z0-9_]{0,39}$")
        /** 코드값만(공백·자유 입력 불가) — 이유 코드, 화면 이름, 로케일, 버전 같은 값 */
        private val CODE_VALUE = Regex("^[A-Za-z0-9_.:+-]{1,64}$")

        /** 서버에 남기는 이벤트(앱 AnalyticsEvents 와 같은 이름). 활성화 퍼널과 그 원인을 보는 데 필요한 것만. */
        val ALLOWED_EVENTS: Set<String> = setOf(
            "onboarding_start", "onboarding_step_done", "onboarding_abandon", "onboarding_plan_choice", "onboarding_complete", "template_apply_result",
            "workout_preview_open", "workout_start", "set_complete", "workout_screen_exit", "workout_complete",
            "first_workout_exit_prompt", "first_workout_exit_reason", "first_workout_reminder_prompt",
            "trainer_action_tap", "notification_permission_result",
            "exercise_search", "exercise_add", "exercise_search_error",
            "paywall_view", "purchase_result"
        )
    }
}

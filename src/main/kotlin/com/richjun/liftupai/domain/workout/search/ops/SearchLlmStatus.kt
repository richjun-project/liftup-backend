package com.richjun.liftupai.domain.workout.search.ops

import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewriter
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 기동 직후 검색의 LLM 기능 상태를 한 번 알린다 — 설정은 켜져 있는데 키가 비어 있으면 눈에 띄는 WARN 한 줄(조용히 어휘 검색만 도는 일을 막는다).
 * 그리고 첫 요청이 TLS·클래스 로딩 비용을 치르지 않도록 전송을 미리 데운다(차단기·예산에 세지 않음, 백그라운드).
 */
@Component
class SearchLlmStatus(
    private val embedder: QueryEmbedder,
    private val rewriter: QueryRewriter,
    private val guard: SearchLlmGuard
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /** 사람이 읽는 상태 한 줄(점검·테스트용) */
    fun describe(): String = "semantic=${state(embedder.configured, embedder.keyPresent)} rewrite=${state(rewriter.configured, rewriter.keyPresent)}" +
        (if (rewriter.configured && !embedder.configured) " (rewrite is used only while semantic search is on)" else "") + " " + guard.limitsLine()

    private fun state(configured: Boolean, keyPresent: Boolean): String = when {
        !configured -> "disabled(config off)"
        !keyPresent -> "DISABLED(gemini api key is blank)"
        else -> "enabled"
    }

    /** 설정은 켜져 있는데 키가 비어 있는 기능이 있는가 */
    fun misconfigured(): Boolean = (embedder.configured && !embedder.keyPresent) || (rewriter.configured && !rewriter.keyPresent)

    @EventListener(ApplicationReadyEvent::class)
    fun report() {
        if (misconfigured()) {
            logger.warn(
                "Search LLM features are ENABLED in config but gemini.api-key (GEMINI_API_KEY) is blank — falling back to lexical search only: {}. " +
                    "Set the key, or turn the features off with SEMANTIC_SEARCH_ENABLED=false / QUERY_REWRITE_ENABLED=false.", describe()
            )
        } else {
            logger.info("Search LLM features: {}", describe())
        }
        if (embedder.enabled || rewriter.enabled) {
            Thread({ rewriter.warmUp(); embedder.warmUp() }, "search-llm-warmup").apply { isDaemon = true }.start()
        }
    }
}

package com.richjun.liftupai.domain.analytics

/** POST /api/v2/events 본문 — 앱이 몇 초 단위로 묶어 보낸다 */
data class AppEventBatchRequest(
    val events: List<AppEventItem>? = null
)

data class AppEventItem(
    val name: String? = null,
    /** 평평한 키-값(문자열·숫자·불리언). 그 밖의 값과 규칙에 맞지 않는 키는 버린다 */
    val params: Map<String, Any?>? = null,
    /** 앱에서 이벤트가 생긴 시각(epoch ms, UTC) */
    val ts: Long? = null
)

data class AppEventBatchResponse(
    val accepted: Int,
    val dropped: Int
)

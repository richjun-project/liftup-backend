package com.richjun.liftupai.domain.workout.search.ops

/**
 * 이 요청 스레드에서 방금 한 검색이 의미(임베딩)·LLM 해석을 썼는지 — 검색 운영 기록([SearchOpsRecorder])의 source 에 꼬리표로 붙인다.
 * 검색 서비스는 호출 지점이 기록기와 떨어져 있어(컨트롤러·서비스) 같은 스레드에서 읽는 가벼운 전달 통로를 쓴다.
 * 질의 문자열은 같은 질의인지 비교하는 데만 쓰고 저장·기록하지 않는다(해시만).
 */
object SearchUsageContext {
    const val SEMANTIC = 1
    const val REWRITTEN = 2
    const val DEGRADED = 4

    private class State(val queryHash: Int, var flags: Int, var atNanos: Long)

    private val state = ThreadLocal<State?>()

    /** 검색 하나가 끝났다. 같은 질의의 연이은 검색(필터 해제 재조회)은 꼬리표를 합친다. */
    fun note(query: String?, flags: Int, nowNanos: Long = System.nanoTime()) {
        val h = query.orEmpty().hashCode()
        val cur = state.get()
        if (cur != null && cur.queryHash == h && nowNanos - cur.atNanos < MERGE_WINDOW_NANOS) {
            cur.flags = cur.flags or flags
            cur.atNanos = nowNanos
        } else state.set(State(h, flags, nowNanos))
    }

    /** 기록기가 source 꼬리표를 읽고 비운다: 의미 "s", LLM 해석 "r", 해석이 필요했는데 못 얻음 "d" — 없으면 빈 문자열 */
    fun consumeSuffix(nowNanos: Long = System.nanoTime()): String {
        val cur = state.get() ?: return ""
        state.remove()
        if (nowNanos - cur.atNanos > STALE_NANOS || cur.flags == 0) return ""
        return buildString {
            append('+')
            if (cur.flags and SEMANTIC != 0) append('s')
            if (cur.flags and REWRITTEN != 0) append('r')
            if (cur.flags and DEGRADED != 0) append('d')
        }
    }

    fun clear() = state.remove()

    private const val MERGE_WINDOW_NANOS = 1_000_000_000L
    private const val STALE_NANOS = 15_000_000_000L
}

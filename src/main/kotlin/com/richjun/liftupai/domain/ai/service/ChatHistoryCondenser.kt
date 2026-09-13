package com.richjun.liftupai.domain.ai.service

/**
 * 대화 히스토리를 프롬프트용으로 압축한다.
 *
 * 예전: 최근 10턴을 각 400자로 잘라 넣었다 → 긴 답변(루틴)이 중간에 잘려서
 * "그거 몇 세트야?" 같은 후속 질문에 세트 수를 못 찾고 되물었다.
 * 지금: 최근 [recentTurns]턴은 원문(각 [recentLimit]자, 잘릴 때는 뒤가 아니라 앞을 보존하고 '…'),
 *       그보다 오래된 턴은 한 줄 요약(질문 요지 → 답변 첫 문장)으로 남긴다. 별도 모델 호출 없음.
 */
object ChatHistoryCondenser {

    data class Condensed(val summaryLines: List<String>, val recent: List<Pair<String, String>>)

    fun condense(
        history: List<Pair<String, String>>,
        recentTurns: Int = 4,
        recentLimit: Int = 800,
        summaryUserLimit: Int = 120,
        summaryAiLimit: Int = 160,
    ): Condensed {
        val clean = history.filter { (u, a) -> u.isNotBlank() || a.isNotBlank() }
        val older = clean.dropLast(recentTurns)
        val recent = clean.takeLast(recentTurns).map { (u, a) -> cut(u, recentLimit) to cut(a, recentLimit) }
        val summaries = older.map { (u, a) ->
            val q = oneLine(u).let { cut(it, summaryUserLimit) }
            val ans = firstSentence(a).let { cut(it, summaryAiLimit) }
            "User asked: \"$q\" → You answered: \"$ans\""
        }
        return Condensed(summaries, recent)
    }

    fun promptBlock(c: Condensed): String {
        if (c.summaryLines.isEmpty() && c.recent.isEmpty()) return ""
        return buildString {
            appendLine("## Conversation so far")
            if (c.summaryLines.isNotEmpty()) {
                appendLine("Earlier (summarized, oldest first):")
                c.summaryLines.forEach { appendLine("- $it") }
                appendLine()
            }
            if (c.recent.isNotEmpty()) {
                appendLine("Most recent turns (verbatim, oldest first):")
                c.recent.forEach { (u, a) ->
                    if (u.isNotBlank()) appendLine("User: $u")
                    if (a.isNotBlank()) appendLine("You: $a")
                }
            }
            appendLine()
            appendLine("Follow-up rule: \"this\", \"that routine\", \"저거\", \"그거\", \"それ\" refer to YOUR most recent answer above. " +
                "If the question can be answered from the history (sets, reps, calories, exercise names), answer it directly — never ask what they mean.")
        }.trim()
    }

    private fun cut(s: String, limit: Int): String {
        val t = s.trim()
        return if (t.length <= limit) t else t.take(limit - 1) + "…"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun firstSentence(s: String): String {
        val one = oneLine(s).replace(Regex("\\*\\*|__|#+ "), "")
        val m = Regex("^(.+?[.!?。！？])(\\s|$)").find(one)
        return m?.groupValues?.get(1) ?: one
    }
}

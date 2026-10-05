package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.chat.entity.ChatAction

/**
 * 모델이 답 끝에 붙인 [[START_TODAY_WORKOUT]] 같은 태그를 떼어 버튼으로 바꾼다.
 * 태그는 사용자에게 보이면 안 되므로 본문에서 지우고, 안전 검사도 태그를 뗀 본문으로 한다.
 */
object ChatActionParser {
    private val TAG = Regex("""\[\[\s*([A-Z_]+)\s*]]""")
    private const val MAX_ACTIONS = 2

    data class Parsed(val text: String, val actions: List<ChatAction>)

    fun extract(raw: String?): Parsed {
        if (raw.isNullOrEmpty()) return Parsed(raw.orEmpty(), emptyList())
        val actions = TAG.findAll(raw)
            .mapNotNull { m -> ChatAction.entries.firstOrNull { it.name == m.groupValues[1] } }
            .filter { it in ChatAction.MODEL_ALLOWED }
            .distinct()
            .take(MAX_ACTIONS)
            .toList()
        val text = raw.replace(TAG, "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return Parsed(text, actions)
    }
}

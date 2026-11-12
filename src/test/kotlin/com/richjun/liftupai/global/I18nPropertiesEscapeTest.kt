package com.richjun.liftupai.global

import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 2026-11-12: 일본어 운동 추천 채팅이 "U0001f4aa 難易度"·"3セット xd7 4-6レップ" 로 나갔다(9월부터).
 * workout_messages_ja.properties 가 파이썬식 이스케이프(\U0001f4aa, \xd7)로 저장돼 있었는데,
 * Java Properties 는 \U·\x 를 모르는 이스케이프로 보고 역슬래시만 버린다(오류 없음).
 * → 모든 i18n 파일에서 Java 가 아는 이스케이프만 쓰는지 본다.
 */
class I18nPropertiesEscapeTest {

    private val dir = File("src/main/resources/i18n")

    // Java Properties 가 아는 것: \uXXXX, \t \n \r \f, 그리고 문자 그대로(\\ \: \= \# \! \공백). 줄 끝 \ 는 줄 잇기.
    private val unknownEscape = Regex("""\\(?![uUtnrf\\:=#! ]|$)|\\U|\\u(?![0-9a-fA-F]{4})""")

    @Test
    fun `i18n 파일에 Java 가 모르는 이스케이프가 없다`() {
        val files = dir.listFiles { f -> f.name.endsWith(".properties") }.orEmpty()
        assertTrue(files.isNotEmpty(), "i18n 폴더를 못 찾음: ${dir.absolutePath}")
        val bad = files.flatMap { f ->
            f.readLines(Charsets.UTF_8).mapIndexedNotNull { i, line ->
                // 홀수 개 역슬래시 뒤의 글자만 이스케이프다 — \\U 는 문자 그대로의 역슬래시 + U
                val stripped = line.replace("\\\\", "")
                if (!line.trimStart().startsWith("#") && unknownEscape.containsMatchIn(stripped)) "${f.name}:${i + 1}: $line" else null
            }
        }
        assertTrue(bad.isEmpty(), "모르는 이스케이프:\n" + bad.joinToString("\n"))
    }

    @Test
    fun `일본어 추천 요약 줄에 이모지와 곱하기 기호가 그대로 나온다`() {
        val difficulty = WorkoutLocalization.message("chat.summary.difficulty", "ja", "上級")
        assertEquals("💪 難易度: 上級", difficulty)
        val setLine = WorkoutLocalization.message("chat.set.line", "ja", 3, "4-6", 180)
        assertTrue(setLine.contains("3セット × 4-6レップ"), setLine)
        listOf("chat.summary.target", "chat.summary.equipment", "chat.summary.calories", "chat.section.plan",
            "chat.section.tips", "chat.section.insights").forEach { key ->
            val text = WorkoutLocalization.message(key, "ja", "x")
            assertFalse(Regex("""U000[0-9a-f]{5}""").containsMatchIn(text), "$key: $text")
        }
    }
}

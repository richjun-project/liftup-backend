package com.richjun.liftupai.domain.workout.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 부상을 "설정 우선, 비면 프로필" 로 고르는 옛 해석(경로마다 달라 부상이 조용히 빠졌다)이 다시 들어오지 않게 소스를 훑는다.
 * 부상은 UserTrainingContextResolver(프로필 ∪ 설정 ∪ 요청)만 거쳐야 한다.
 */
class InjurySourceLintTest {
    private val forbidden = listOf(
        Regex("""injuries\s*\?:\s*"""),                         // settings?.injuries ?: profile?.injuries
        Regex("""injuries\??\.takeIf\s*\{\s*it\.isNotEmpty"""), // settings.injuries.takeIf { it.isNotEmpty() } ?: profile...
        Regex("""injuries\.ifEmpty\s*\{"""),                    // settingsInjuries.ifEmpty { profile... }
        Regex("""fromSettings\.isNotEmpty\(\)\s*\)\s*fromSettings"""),
    )
    private val allowedFiles = setOf("UserTrainingContextResolver.kt", "ChatSafetyService.kt")

    @Test
    @DisplayName("main 소스에 '설정 우선 → 비면 프로필' 부상 해석이 없다")
    fun noSettingsFirstInjuryResolution() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "소스 루트를 못 찾았다: ${root.absolutePath}")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowedFiles }.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> !line.trim().startsWith("//") && !line.trim().startsWith("*") && forbidden.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
        }.toList()
        assertTrue(offenders.isEmpty(), "경로별 부상 해석이 다시 들어왔다 — UserTrainingContextResolver 를 쓰라:\n" + offenders.joinToString("\n"))
    }
}

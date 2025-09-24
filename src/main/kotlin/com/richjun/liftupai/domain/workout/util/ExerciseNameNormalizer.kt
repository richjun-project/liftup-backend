package com.richjun.liftupai.domain.workout.util

import org.springframework.stereotype.Component

@Component
class ExerciseNameNormalizer {

    // 철자 변형 매핑
    private val spellingVariations = mapOf(
        "푸쉬" to "푸시",
        "푸쉬업" to "푸시업",  // 푸쉬업 -> 푸시업 변환 추가
        "푸시" to "푸시",  // 정규화된 형태
        "래터럴" to "레터럴",
        "레터럴" to "레터럴",  // 정규화된 형태
        "프래스" to "프레스",
        "프레스" to "프레스",  // 정규화된 형태
        "플레이" to "플라이",
        "플라이" to "플라이",  // 정규화된 형태
        "덤밸" to "덤벨",
        "덤벨" to "덤벨",  // 정규화된 형태
        "로오" to "로우",
        "로우" to "로우",  // 정규화된 형태
        "데드리프" to "데드리프트",
        "데드리프트" to "데드리프트",  // 정규화된 형태
        "풀다운" to "풀다운",
        "풀 다운" to "풀다운",
        "푸시다운" to "푸시다운",
        "푸쉬다운" to "푸시다운",
        "푸시 다운" to "푸시다운",
        "푸쉬 업" to "푸시업",  // 띄어쓰기 있는 버전도 추가
        "푸시 업" to "푸시업",
        "벤치프레스" to "벤치프레스",
        "벤치 프레스" to "벤치프레스",
        "레그프레스" to "레그프레스",
        "레그 프레스" to "레그프레스",
        "숄더프레스" to "숄더프레스",
        "숄더 프레스" to "숄더프레스",
        "체스트프레스" to "체스트프레스",
        "체스트 프레스" to "체스트프레스"
    )

    // 일반적인 운동명 약어/변형
    private val commonAbbreviations = mapOf(
        "db" to "덤벨",
        "bb" to "바벨",
        "ez" to "이지바",
        "ohp" to "오버헤드프레스",
        "rdl" to "루마니안데드리프트",
        "sldl" to "스티프레그데드리프트"
    )

    /**
     * 운동명을 정규화합니다.
     * 철자 변형, 띄어쓰기, 대소문자를 통일합니다.
     */
    fun normalize(name: String): String {
        var normalized = name.trim().lowercase()

        // 약어 처리
        commonAbbreviations.forEach { (abbr, full) ->
            normalized = normalized.replace(abbr, full)
        }

        // 철자 변형 처리
        spellingVariations.forEach { (variant, standard) ->
            normalized = normalized.replace(variant, standard)
        }

        // 특수문자 정규화
        normalized = normalized
            .replace("_", " ")
            .replace("-", " ")
            .replace("  ", " ")
            .trim()

        return normalized
    }

    /**
     * 두 운동명이 유사한지 확인합니다.
     * 정규화 후 비교하거나 편집 거리를 계산합니다.
     */
    fun areSimilar(name1: String, name2: String, threshold: Int = 2): Boolean {
        val normalized1 = normalize(name1)
        val normalized2 = normalize(name2)

        // 정규화 후 같으면 true
        if (normalized1 == normalized2) return true

        // 편집 거리가 threshold 이하면 유사하다고 판단
        return calculateLevenshteinDistance(normalized1, normalized2) <= threshold
    }

    /**
     * 주어진 운동명의 가능한 변형들을 생성합니다.
     */
    fun generateVariations(name: String): List<String> {
        val normalized = normalize(name)
        val variations = mutableSetOf(
            name,
            normalized,
            normalized.replace(" ", ""),
            normalized.replace(" ", "-"),
            normalized.replace(" ", "_")
        )

        // 역방향 매핑 추가 (정규화된 형태에서 원래 형태들로)
        val reverseMap = mutableMapOf<String, MutableList<String>>()
        spellingVariations.forEach { (variant, standard) ->
            if (variant != standard) {
                reverseMap.getOrPut(standard) { mutableListOf() }.add(variant)
            }
        }

        // 정규화된 형태에 대한 모든 가능한 변형 추가
        reverseMap[normalized]?.forEach { variant ->
            variations.add(variant)
            variations.add(variant.replace(" ", ""))
        }

        return variations.toList()
    }

    /**
     * Levenshtein 편집 거리를 계산합니다.
     */
    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 삭제
                    dp[i][j - 1] + 1,      // 삽입
                    dp[i - 1][j - 1] + cost // 치환
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * 운동명 리스트에서 가장 유사한 운동을 찾습니다.
     */
    fun findBestMatch(target: String, candidates: List<String>): Pair<String, Int>? {
        val normalizedTarget = normalize(target)
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE

        for (candidate in candidates) {
            val normalizedCandidate = normalize(candidate)

            // 정확히 일치하면 바로 반환
            if (normalizedTarget == normalizedCandidate) {
                return Pair(candidate, 0)
            }

            val distance = calculateLevenshteinDistance(normalizedTarget, normalizedCandidate)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = candidate
            }
        }

        return bestMatch?.let { Pair(it, bestDistance) }
    }
}
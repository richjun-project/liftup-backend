package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import org.springframework.stereotype.Component

@Component
class ExerciseNameNormalizer {
    private val spellingVariations = WorkoutAliasCatalog.mapping("normalizer.spelling")
    private val commonAbbreviations = WorkoutAliasCatalog.mapping("normalizer.abbreviation")
    private val chestKeywords = WorkoutAliasCatalog.list("normalizer.keyword.chest")
    private val backKeywords = WorkoutAliasCatalog.list("normalizer.keyword.back")
    private val legKeywords = WorkoutAliasCatalog.list("normalizer.keyword.legs")
    private val shoulderKeywords = WorkoutAliasCatalog.list("normalizer.keyword.shoulders")
    private val armKeywords = WorkoutAliasCatalog.list("normalizer.keyword.arms")
    private val coreKeywords = WorkoutAliasCatalog.list("normalizer.keyword.core")
    private val compoundKeywords = WorkoutAliasCatalog.list("normalizer.keyword.compound")
    private val coreExerciseKeywords = WorkoutAliasCatalog.list("normalizer.keyword.core_exercise")

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

    fun extractCoreKeywords(name: String): List<String> {
        val normalized = name.lowercase()
        return coreExerciseKeywords.filter { keyword -> normalized.contains(keyword.lowercase()) }
    }

    fun inferMuscleGroups(exerciseName: String, targetMuscle: String?): List<MuscleGroup> {
        val lowerName = exerciseName.lowercase()
        val groups = mutableListOf<MuscleGroup>()

        when {
            containsAnyKeyword(lowerName, chestKeywords) -> {
                groups.add(MuscleGroup.CHEST)
                groups.add(MuscleGroup.TRICEPS)
            }
            containsAnyKeyword(lowerName, backKeywords) -> {
                groups.add(MuscleGroup.BACK)
                groups.add(MuscleGroup.BICEPS)
            }
            containsAnyKeyword(lowerName, legKeywords) -> {
                groups.add(MuscleGroup.LEGS)
                groups.add(MuscleGroup.GLUTES)
            }
            containsAnyKeyword(lowerName, shoulderKeywords) && WorkoutTargetResolver.recommendationKey(targetMuscle) == "shoulders" -> {
                groups.add(MuscleGroup.SHOULDERS)
            }
            containsAnyKeyword(lowerName, shoulderKeywords) -> {
                groups.add(MuscleGroup.SHOULDERS)
            }
            containsAnyKeyword(lowerName, armKeywords) -> {
                groups.add(MuscleGroup.BICEPS)
                groups.add(MuscleGroup.TRICEPS)
            }
            containsAnyKeyword(lowerName, coreKeywords) -> {
                groups.add(MuscleGroup.CORE)
                groups.add(MuscleGroup.ABS)
            }
        }

        if (groups.isEmpty() && !targetMuscle.isNullOrBlank()) {
            groups.addAll(WorkoutTargetResolver.muscleGroupsFor(targetMuscle))
        }

        return groups.distinct()
    }

    fun inferCategory(exerciseName: String, targetMuscle: String?): ExerciseCategory? {
        val lowerName = exerciseName.lowercase()

        return when {
            containsAnyKeyword(lowerName, chestKeywords) -> ExerciseCategory.CHEST
            containsAnyKeyword(lowerName, backKeywords) && !containsAnyKeyword(lowerName, legKeywords) -> ExerciseCategory.BACK
            containsAnyKeyword(lowerName, legKeywords) -> ExerciseCategory.LEGS
            containsAnyKeyword(lowerName, shoulderKeywords) -> ExerciseCategory.SHOULDERS
            containsAnyKeyword(lowerName, armKeywords) -> ExerciseCategory.ARMS
            containsAnyKeyword(lowerName, coreKeywords) -> ExerciseCategory.CORE
            else -> when (WorkoutTargetResolver.recommendationKey(targetMuscle)) {
                "chest" -> ExerciseCategory.CHEST
                "back" -> ExerciseCategory.BACK
                "legs" -> ExerciseCategory.LEGS
                "shoulders" -> ExerciseCategory.SHOULDERS
                "arms" -> ExerciseCategory.ARMS
                "core" -> ExerciseCategory.CORE
                "full_body" -> ExerciseCategory.FULL_BODY
                else -> null
            }
        }
    }

    fun isCompoundHint(exerciseName: String): Boolean {
        val lowerName = exerciseName.lowercase()
        return containsAnyKeyword(lowerName, compoundKeywords)
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

    private fun containsAnyKeyword(value: String, keywords: List<String>): Boolean {
        return keywords.any { keyword -> value.contains(keyword.lowercase()) }
    }
}

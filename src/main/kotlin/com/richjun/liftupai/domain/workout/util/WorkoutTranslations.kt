package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.WorkoutType

/**
 * 운동 관련 용어를 한글로 번역하는 유틸리티 함수들
 */
object WorkoutTranslations {

    /**
     * 운동 분할 프로그램 이름을 한글로 변환
     */
    fun translateWorkoutSplit(split: String): String {
        return when (split.lowercase()) {
            "ppl", "push_pull_legs" -> "밀기/당기기/하체"
            "push_pull", "push/pull" -> "밀기/당기기"
            "upper_lower", "upper/lower" -> "상하체 분할"
            "full_body", "full" -> "전신"
            "bro_split", "5_split", "5-split" -> "부위별 분할"
            else -> split
        }
    }

    /**
     * WorkoutType을 한글로 변환
     */
    fun translateWorkoutType(type: WorkoutType): String {
        return when (type) {
            WorkoutType.PUSH -> "밀기"
            WorkoutType.PULL -> "당기기"
            WorkoutType.LEGS -> "하체"
            WorkoutType.UPPER -> "상체"
            WorkoutType.LOWER -> "하체"
            WorkoutType.CHEST -> "가슴"
            WorkoutType.BACK -> "등"
            WorkoutType.SHOULDERS -> "어깨"
            WorkoutType.ARMS -> "팔"
            WorkoutType.FULL_BODY -> "전신"
            WorkoutType.ABS -> "복근"
            WorkoutType.CARDIO -> "유산소"
        }
    }

    /**
     * 운동 프로그램 이름 생성
     */
    fun createProgramName(days: Int, split: String, level: String? = null): String {
        val translatedSplit = translateWorkoutSplit(split)
        val levelPrefix = when (level?.lowercase()) {
            "beginner" -> "초급"
            "intermediate" -> "중급"
            "advanced" -> "고급"
            else -> ""
        }

        return when {
            days <= 2 -> "전신 운동 프로그램"
            days == 3 && split.lowercase() == "full_body" -> "3일 전신 운동 프로그램"
            days == 3 && split.lowercase() in listOf("ppl", "push_pull_legs") -> "초급 밀기/당기기/하체 프로그램"
            days == 4 && split.lowercase() in listOf("upper_lower", "upper/lower") -> "중급 상하체 분할 프로그램"
            days == 5 && split.lowercase() in listOf("ppl", "push_pull_legs") -> "고급 밀기/당기기/하체 프로그램"
            days >= 6 && split.lowercase() == "bro_split" -> "보디빌딩 분할 프로그램"
            else -> {
                if (levelPrefix.isNotEmpty()) {
                    "$levelPrefix ${days}일 $translatedSplit 프로그램"
                } else {
                    "${days}일 $translatedSplit 프로그램"
                }
            }
        }
    }

    /**
     * 운동 타입 시퀀스를 한글 설명으로 변환
     */
    fun translateWorkoutSequence(sequence: List<WorkoutType>): String {
        return sequence.joinToString(" → ") { translateWorkoutType(it) }
    }
}
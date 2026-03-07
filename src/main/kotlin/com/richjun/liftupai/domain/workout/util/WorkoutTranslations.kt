package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.WorkoutType

/**
 * 운동 관련 용어를 한글로 번역하는 유틸리티 함수들
 */
object WorkoutTranslations {

    /**
     * 운동 분할 프로그램 이름을 한글로 변환
     */
    fun translateWorkoutSplit(split: String, locale: String = "ko"): String {
        return WorkoutLocalization.splitName(split, locale)
    }

    /**
     * WorkoutType을 한글로 변환
     */
    fun translateWorkoutType(type: WorkoutType, locale: String = "ko"): String {
        return WorkoutLocalization.workoutTypeName(type, locale)
    }

    /**
     * 운동 프로그램 이름 생성
     */
    fun createProgramName(days: Int, split: String, level: String? = null, locale: String = "ko"): String {
        val splitName = translateWorkoutSplit(split, locale)
        val levelPrefix = level
            ?.takeIf { it.isNotBlank() }
            ?.let { WorkoutLocalization.difficultyDisplayName(it, locale) }
            ?.takeIf { it.isNotBlank() }

        return if (levelPrefix != null) {
            WorkoutLocalization.message("program.name.with_level", locale, levelPrefix, days, splitName)
        } else {
            WorkoutLocalization.message("program.name.default", locale, days, splitName)
        }
    }

    /**
     * 운동 타입 시퀀스를 한글 설명으로 변환
     */
    fun translateWorkoutSequence(sequence: List<WorkoutType>, locale: String = "ko"): String {
        return sequence.joinToString(" -> ") { translateWorkoutType(it, locale) }
    }
}

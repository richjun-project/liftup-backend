package com.richjun.liftupai.domain.workout.util

import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.WorkoutType

enum class WorkoutFocus {
    FULL_BODY,
    PUSH,
    PULL,
    LEGS,
    UPPER,
    LOWER,
    CHEST,
    BACK,
    SHOULDERS,
    ARMS,
    CORE,
    CARDIO
}

object WorkoutTargetResolver {
    private val aliases = mapOf(
        WorkoutFocus.FULL_BODY to WorkoutAliasCatalog.list("focus.alias.full_body").toSet(),
        WorkoutFocus.PUSH to WorkoutAliasCatalog.list("focus.alias.push").toSet(),
        WorkoutFocus.PULL to WorkoutAliasCatalog.list("focus.alias.pull").toSet(),
        WorkoutFocus.LEGS to WorkoutAliasCatalog.list("focus.alias.legs").toSet(),
        WorkoutFocus.UPPER to WorkoutAliasCatalog.list("focus.alias.upper").toSet(),
        WorkoutFocus.LOWER to WorkoutAliasCatalog.list("focus.alias.lower").toSet(),
        WorkoutFocus.CHEST to WorkoutAliasCatalog.list("focus.alias.chest").toSet(),
        WorkoutFocus.BACK to WorkoutAliasCatalog.list("focus.alias.back").toSet(),
        WorkoutFocus.SHOULDERS to WorkoutAliasCatalog.list("focus.alias.shoulders").toSet(),
        WorkoutFocus.ARMS to WorkoutAliasCatalog.list("focus.alias.arms").toSet(),
        WorkoutFocus.CORE to WorkoutAliasCatalog.list("focus.alias.core").toSet(),
        WorkoutFocus.CARDIO to WorkoutAliasCatalog.list("focus.alias.cardio").toSet()
    )

    private val muscleGroupAliases = mapOf(
        MuscleGroup.CHEST to WorkoutAliasCatalog.list("muscle.alias.chest").toSet(),
        MuscleGroup.BACK to WorkoutAliasCatalog.list("muscle.alias.back").toSet(),
        MuscleGroup.SHOULDERS to WorkoutAliasCatalog.list("muscle.alias.shoulders").toSet(),
        MuscleGroup.BICEPS to WorkoutAliasCatalog.list("muscle.alias.biceps").toSet(),
        MuscleGroup.TRICEPS to WorkoutAliasCatalog.list("muscle.alias.triceps").toSet(),
        MuscleGroup.LEGS to WorkoutAliasCatalog.list("muscle.alias.legs").toSet(),
        MuscleGroup.CORE to WorkoutAliasCatalog.list("muscle.alias.core").toSet(),
        MuscleGroup.ABS to WorkoutAliasCatalog.list("muscle.alias.abs").toSet(),
        MuscleGroup.GLUTES to WorkoutAliasCatalog.list("muscle.alias.glutes").toSet(),
        MuscleGroup.CALVES to WorkoutAliasCatalog.list("muscle.alias.calves").toSet(),
        MuscleGroup.FOREARMS to WorkoutAliasCatalog.list("muscle.alias.forearms").toSet(),
        MuscleGroup.NECK to WorkoutAliasCatalog.list("muscle.alias.neck").toSet(),
        MuscleGroup.QUADRICEPS to WorkoutAliasCatalog.list("muscle.alias.quadriceps").toSet(),
        MuscleGroup.HAMSTRINGS to WorkoutAliasCatalog.list("muscle.alias.hamstrings").toSet(),
        MuscleGroup.LATS to WorkoutAliasCatalog.list("muscle.alias.lats").toSet(),
        MuscleGroup.TRAPS to WorkoutAliasCatalog.list("muscle.alias.traps").toSet()
    )

    fun resolveFocus(raw: String?): WorkoutFocus? {
        val normalized = raw
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?: return null

        return aliases.entries.firstOrNull { (_, values) -> normalized in values }?.key
    }

    fun resolveMuscleGroup(raw: String?): MuscleGroup? {
        val normalized = raw
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?: return null

        return muscleGroupAliases.entries.firstOrNull { (_, values) -> normalized in values }?.key
    }

    fun muscleGroupsFor(raw: String?): Set<MuscleGroup> {
        resolveMuscleGroup(raw)?.let { return setOf(it) }
        return resolveFocus(raw)?.let(::muscleGroupsForFocus) ?: emptySet()
    }

    fun muscleGroupsForFocus(focus: WorkoutFocus): Set<MuscleGroup> {
        return when (focus) {
            WorkoutFocus.FULL_BODY, WorkoutFocus.CARDIO -> emptySet()
            WorkoutFocus.PUSH -> setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            WorkoutFocus.PULL -> setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.FOREARMS)
            WorkoutFocus.LEGS, WorkoutFocus.LOWER -> setOf(
                MuscleGroup.QUADRICEPS,
                MuscleGroup.HAMSTRINGS,
                MuscleGroup.GLUTES,
                MuscleGroup.CALVES
            )
            WorkoutFocus.UPPER -> setOf(
                MuscleGroup.CHEST,
                MuscleGroup.BACK,
                MuscleGroup.LATS,
                MuscleGroup.SHOULDERS,
                MuscleGroup.BICEPS,
                MuscleGroup.TRICEPS,
                MuscleGroup.FOREARMS
            )
            WorkoutFocus.CHEST -> setOf(MuscleGroup.CHEST)
            WorkoutFocus.BACK -> setOf(MuscleGroup.BACK, MuscleGroup.LATS)
            WorkoutFocus.SHOULDERS -> setOf(MuscleGroup.SHOULDERS)
            WorkoutFocus.ARMS -> setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS)
            WorkoutFocus.CORE -> setOf(MuscleGroup.ABS, MuscleGroup.CORE)
        }
    }

    fun displayName(focus: WorkoutFocus, locale: String = "en"): String {
        return WorkoutLocalization.focusName(focus, locale)
    }

    fun displayName(muscleGroup: MuscleGroup, locale: String = "en"): String {
        return WorkoutLocalization.muscleGroupName(muscleGroup, locale)
    }

    fun key(focus: WorkoutFocus): String {
        return when (focus) {
            WorkoutFocus.FULL_BODY -> "full_body"
            WorkoutFocus.PUSH -> "push"
            WorkoutFocus.PULL -> "pull"
            WorkoutFocus.LEGS -> "legs"
            WorkoutFocus.UPPER -> "upper"
            WorkoutFocus.LOWER -> "lower"
            WorkoutFocus.CHEST -> "chest"
            WorkoutFocus.BACK -> "back"
            WorkoutFocus.SHOULDERS -> "shoulders"
            WorkoutFocus.ARMS -> "arms"
            WorkoutFocus.CORE -> "core"
            WorkoutFocus.CARDIO -> "cardio"
        }
    }

    fun recommendationKey(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "general", "auto" -> null
            "fullbody" -> "full_body"
            else -> {
                resolveFocus(normalized)?.let { focus ->
                    return when (focus) {
                        WorkoutFocus.CARDIO -> "full_body"
                        else -> key(focus)
                    }
                }

                resolveMuscleGroup(normalized)?.let { muscleGroup ->
                    return when (muscleGroup) {
                        MuscleGroup.CHEST -> "chest"
                        MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.TRAPS -> "back"
                        MuscleGroup.SHOULDERS -> "shoulders"
                        MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS -> "arms"
                        MuscleGroup.LEGS, MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES -> "legs"
                        MuscleGroup.CORE, MuscleGroup.ABS -> "core"
                        else -> normalized
                    }
                }

                normalized
            }
        }
    }

    fun focusForMuscleGroup(muscleGroup: MuscleGroup): WorkoutFocus {
        return when (muscleGroup) {
            MuscleGroup.CHEST -> WorkoutFocus.CHEST
            MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.TRAPS -> WorkoutFocus.BACK
            MuscleGroup.SHOULDERS -> WorkoutFocus.SHOULDERS
            MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.FOREARMS -> WorkoutFocus.ARMS
            MuscleGroup.LEGS, MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES -> WorkoutFocus.LEGS
            MuscleGroup.CORE, MuscleGroup.ABS -> WorkoutFocus.CORE
            MuscleGroup.NECK -> WorkoutFocus.UPPER
        }
    }

    fun focusForCategory(category: ExerciseCategory): WorkoutFocus? {
        return when (category) {
            ExerciseCategory.CHEST -> WorkoutFocus.CHEST
            ExerciseCategory.BACK -> WorkoutFocus.BACK
            ExerciseCategory.LEGS -> WorkoutFocus.LEGS
            ExerciseCategory.SHOULDERS -> WorkoutFocus.SHOULDERS
            ExerciseCategory.ARMS -> WorkoutFocus.ARMS
            ExerciseCategory.CORE -> WorkoutFocus.CORE
            ExerciseCategory.CARDIO -> WorkoutFocus.CARDIO
            ExerciseCategory.FULL_BODY -> WorkoutFocus.FULL_BODY
        }
    }

    fun recoveryHours(focus: WorkoutFocus): Int {
        return when (focus) {
            WorkoutFocus.LEGS -> 72
            WorkoutFocus.BACK -> 72
            WorkoutFocus.CHEST, WorkoutFocus.SHOULDERS, WorkoutFocus.PUSH, WorkoutFocus.PULL, WorkoutFocus.UPPER, WorkoutFocus.LOWER -> 48
            WorkoutFocus.ARMS, WorkoutFocus.CORE, WorkoutFocus.CARDIO -> 24
            WorkoutFocus.FULL_BODY -> 48
        }
    }

    fun primaryFocusForWorkoutType(type: WorkoutType): WorkoutFocus {
        return when (type) {
            WorkoutType.PUSH -> WorkoutFocus.PUSH
            WorkoutType.PULL -> WorkoutFocus.PULL
            WorkoutType.LEGS -> WorkoutFocus.LEGS
            WorkoutType.UPPER -> WorkoutFocus.UPPER
            WorkoutType.LOWER -> WorkoutFocus.LOWER
            WorkoutType.CHEST -> WorkoutFocus.CHEST
            WorkoutType.BACK -> WorkoutFocus.BACK
            WorkoutType.SHOULDERS -> WorkoutFocus.SHOULDERS
            WorkoutType.ARMS -> WorkoutFocus.ARMS
            WorkoutType.ABS -> WorkoutFocus.CORE
            WorkoutType.CARDIO -> WorkoutFocus.CARDIO
            WorkoutType.FULL_BODY -> WorkoutFocus.FULL_BODY
        }
    }

    fun targetsForWorkoutType(type: WorkoutType): List<WorkoutFocus> {
        return when (type) {
            WorkoutType.PUSH -> listOf(WorkoutFocus.CHEST, WorkoutFocus.SHOULDERS, WorkoutFocus.ARMS)
            WorkoutType.PULL -> listOf(WorkoutFocus.BACK, WorkoutFocus.ARMS)
            WorkoutType.LEGS -> listOf(WorkoutFocus.LEGS)
            WorkoutType.UPPER -> listOf(WorkoutFocus.CHEST, WorkoutFocus.BACK, WorkoutFocus.SHOULDERS, WorkoutFocus.ARMS)
            WorkoutType.LOWER -> listOf(WorkoutFocus.LEGS, WorkoutFocus.CORE)
            WorkoutType.CHEST -> listOf(WorkoutFocus.CHEST)
            WorkoutType.BACK -> listOf(WorkoutFocus.BACK)
            WorkoutType.SHOULDERS -> listOf(WorkoutFocus.SHOULDERS)
            WorkoutType.ARMS -> listOf(WorkoutFocus.ARMS)
            WorkoutType.ABS -> listOf(WorkoutFocus.CORE)
            WorkoutType.CARDIO -> listOf(WorkoutFocus.CARDIO)
            WorkoutType.FULL_BODY -> listOf(
                WorkoutFocus.CHEST,
                WorkoutFocus.BACK,
                WorkoutFocus.LEGS,
                WorkoutFocus.SHOULDERS,
                WorkoutFocus.ARMS,
                WorkoutFocus.CORE
            )
        }
    }

    fun displayNamesForWorkoutType(type: WorkoutType, locale: String = "en"): List<String> {
        return targetsForWorkoutType(type).map { displayName(it, locale) }
    }
}

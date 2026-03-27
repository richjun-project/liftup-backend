package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.springframework.stereotype.Service

/**
 * Groups exercises by movement pattern so a session can avoid redundant variants.
 */
@Service
class ExercisePatternClassifier {

    enum class MovementPattern {
        // Lower body patterns
        SQUAT, LUNGE, HIP_HINGE, LEG_PRESS, LEG_CURL, LEG_EXTENSION, GLUTE_FOCUSED, CALF,

        // Upper push patterns
        HORIZONTAL_PRESS_BARBELL, HORIZONTAL_PRESS_DUMBBELL, HORIZONTAL_PRESS_MACHINE,
        INCLINE_PRESS_BARBELL, INCLINE_PRESS_DUMBBELL, DECLINE_PRESS,
        VERTICAL_PRESS_BARBELL, VERTICAL_PRESS_DUMBBELL, VERTICAL_PRESS_MACHINE,
        DIPS, PUSHUP, FLY,

        // Upper pull patterns
        PULLUP_CHINUP, LAT_PULLDOWN, BARBELL_ROW, DUMBBELL_ROW, CABLE_ROW, INVERTED_ROW, DEADLIFT,

        // Shoulder patterns
        LATERAL_RAISE, FRONT_RAISE, REAR_DELT, FACE_PULL, UPRIGHT_ROW, SHRUG,

        // Arm patterns
        BICEP_CURL_BARBELL, BICEP_CURL_DUMBBELL, BICEP_CURL_CABLE,
        TRICEP_OVERHEAD, TRICEP_LYING, TRICEP_PUSHDOWN,

        // Core patterns
        CRUNCH, LEG_RAISE, PLANK, ROTATION, ROLLOUT,

        // Other
        CLEAN, SNATCH, COMPOUND_COMPLEX, CARRY, CARDIO, PLYOMETRIC, STRETCHING, OTHER
    }

    private val inclineKeywords = listOf("incline")
    private val barbellKeywords = listOf("barbell", "smith")
    private val dumbbellKeywords = listOf("dumbbell", "db")
    private val machineKeywords = listOf("machine", "hammer strength")
    private val benchKeywords = listOf("bench")
    private val shoulderKeywords = listOf("shoulder")
    private val pressKeywords = listOf("press")
    private val rowKeywords = listOf("row")
    private val oneArmKeywords = listOf("one arm", "single arm", "single-arm")
    private val cableKeywords = listOf("cable")
    private val seatedKeywords = listOf("seated")
    private val invertedKeywords = listOf("inverted")
    private val uprightKeywords = listOf("upright")
    private val curlKeywords = listOf("curl")
    private val legKeywords = listOf("leg", "hamstring", "nordic")
    private val preacherKeywords = listOf("preacher")
    private val ezKeywords = listOf("ez")
    private val hipHingeKeywords = listOf(
        "romanian", "rdl", "good morning", "back extension",
        "hyperextension", "stiff leg", "stiff-leg", "glute ham", "ghr"
    )
    private val squatExclusionKeywords = listOf("lunge", "split", "bulgarian", "hack", "leg press", "jump")

    private val simplePatternKeywords = mapOf(
        MovementPattern.LEG_PRESS to listOf("leg press", "hack squat"),
        MovementPattern.LEG_EXTENSION to listOf("leg extension", "knee extension"),
        MovementPattern.GLUTE_FOCUSED to listOf("hip thrust", "glute drive", "glute bridge", "pull through", "kettlebell swing"),
        MovementPattern.CALF to listOf("calf"),
        MovementPattern.LUNGE to listOf("lunge", "bulgarian", "split squat", "step up", "step-up", "curtsy"),
        MovementPattern.DIPS to listOf("dip", "dips"),
        MovementPattern.PUSHUP to listOf("push up", "pushup"),
        MovementPattern.FLY to listOf("fly", "pec deck", "cable crossover"),
        MovementPattern.DECLINE_PRESS to listOf("decline"),
        MovementPattern.VERTICAL_PRESS_BARBELL to listOf("military press", "overhead press", "push press", "push jerk"),
        MovementPattern.PULLUP_CHINUP to listOf("pullup", "pull-up", "chinup", "chin-up"),
        MovementPattern.LAT_PULLDOWN to listOf("lat pulldown", "pulldown"),
        MovementPattern.DEADLIFT to listOf("deadlift"),
        MovementPattern.LATERAL_RAISE to listOf("lateral raise", "side raise"),
        MovementPattern.FRONT_RAISE to listOf("front raise"),
        MovementPattern.REAR_DELT to listOf("rear delt", "reverse fly"),
        MovementPattern.FACE_PULL to listOf("face pull"),
        MovementPattern.SHRUG to listOf("shrug"),
        MovementPattern.TRICEP_OVERHEAD to listOf("overhead extension", "triceps extension"),
        MovementPattern.TRICEP_LYING to listOf("skull crusher", "lying extension"),
        MovementPattern.TRICEP_PUSHDOWN to listOf("pushdown", "kickback"),
        MovementPattern.BICEP_CURL_CABLE to listOf("cable curl"),
        MovementPattern.CRUNCH to listOf("crunch", "situp", "sit-up"),
        MovementPattern.LEG_RAISE to listOf("leg raise", "knee raise", "hanging raise"),
        MovementPattern.PLANK to listOf("plank"),
        MovementPattern.ROTATION to listOf("russian twist", "woodchop", "rotation"),
        MovementPattern.ROLLOUT to listOf("rollout", "ab wheel", "ab roller"),
        MovementPattern.CLEAN to listOf("clean"),
        MovementPattern.SNATCH to listOf("snatch"),
        MovementPattern.COMPOUND_COMPLEX to listOf("thruster", "man maker", "man-maker", "devil press"),
        MovementPattern.CARRY to listOf("farmer", "carry", "waiter carry", "suitcase carry"),
        MovementPattern.CARDIO to listOf("rowing machine", "rower", "bike", "treadmill", "running", "jump rope"),
        MovementPattern.PLYOMETRIC to listOf("jump", "burpee", "plyo", "plyometric")
    )

    fun classifyExercise(exercise: Exercise): MovementPattern {
        exercise.movementPattern
            ?.let { raw -> runCatching { MovementPattern.valueOf(raw) }.getOrNull() }
            ?.let { return it }

        val name = exercise.name.lowercase()

        // 1. Distinguish hip hinge assistance from conventional deadlift first.
        if (isHipHinge(name)) return MovementPattern.HIP_HINGE

        // 2. Exclude split-squat and hack-squat variants from generic squat.
        if (isSquatExclusion(name)) {
            return classifyNonSquat(name)
        }

        // 3. Fall back to general pattern matching.
        return findMatchingPattern(name) ?: MovementPattern.OTHER
    }

    private fun isHipHinge(name: String): Boolean {
        return hipHingeKeywords.any { name.contains(it) }
    }

    private fun isSquatExclusion(name: String): Boolean {
        return name.contains("squat") && squatExclusionKeywords.any { name.contains(it) }
    }

    private fun classifyNonSquat(name: String): MovementPattern {
        return when {
            containsAny(name, listOf("lunge", "split", "bulgarian")) -> MovementPattern.LUNGE
            containsAny(name, listOf("hack", "leg press")) -> MovementPattern.LEG_PRESS
            containsAny(name, listOf("jump")) -> MovementPattern.PLYOMETRIC
            else -> MovementPattern.OTHER
        }
    }

    private fun findMatchingPattern(name: String): MovementPattern? {
        // 구체적인 패턴을 먼저 체크 (incline lateral raise → LATERAL_RAISE, not INCLINE_PRESS)
        for ((pattern, keywords) in simplePatternKeywords) {
            if (containsAny(name, keywords)) {
                return pattern
            }
        }

        if (containsAny(name, inclineKeywords)) {
            return when {
                containsAny(name, barbellKeywords) -> MovementPattern.INCLINE_PRESS_BARBELL
                containsAny(name, dumbbellKeywords) -> MovementPattern.INCLINE_PRESS_DUMBBELL
                else -> MovementPattern.INCLINE_PRESS_DUMBBELL
            }
        }

        if (containsAny(name, benchKeywords)) {
            return when {
                containsAny(name, machineKeywords) -> MovementPattern.HORIZONTAL_PRESS_MACHINE
                containsAny(name, dumbbellKeywords) -> MovementPattern.HORIZONTAL_PRESS_DUMBBELL
                else -> MovementPattern.HORIZONTAL_PRESS_BARBELL
            }
        }

        if (containsAny(name, shoulderKeywords) && containsAny(name, pressKeywords)) {
            return when {
                containsAny(name, machineKeywords) -> MovementPattern.VERTICAL_PRESS_MACHINE
                containsAny(name, dumbbellKeywords) -> MovementPattern.VERTICAL_PRESS_DUMBBELL
                else -> MovementPattern.VERTICAL_PRESS_BARBELL
            }
        }

        if (containsAny(name, rowKeywords)) {
            return when {
                containsAny(name, listOf("pendlay", "t-bar", "barbell")) -> MovementPattern.BARBELL_ROW
                containsAny(name, dumbbellKeywords + oneArmKeywords) -> MovementPattern.DUMBBELL_ROW
                containsAny(name, cableKeywords + seatedKeywords) -> MovementPattern.CABLE_ROW
                containsAny(name, invertedKeywords) -> MovementPattern.INVERTED_ROW
                containsAny(name, uprightKeywords) -> MovementPattern.UPRIGHT_ROW
                else -> MovementPattern.BARBELL_ROW
            }
        }

        if (containsAny(name, curlKeywords)) {
            if (containsAny(name, legKeywords)) {
                return MovementPattern.LEG_CURL
            }
            return when {
                containsAny(name, cableKeywords) -> MovementPattern.BICEP_CURL_CABLE
                containsAny(name, barbellKeywords + ezKeywords + preacherKeywords) -> MovementPattern.BICEP_CURL_BARBELL
                else -> MovementPattern.BICEP_CURL_DUMBBELL
            }
        }

        // simplePatternKeywords는 이미 위에서 체크했으므로 여기서는 squat만 체크
        if (name.contains("squat")) {
            return MovementPattern.SQUAT
        }

        return null
    }

    private fun containsAny(name: String, keywords: List<String>): Boolean {
        return keywords.any { keyword -> name.contains(keyword) }
    }
}

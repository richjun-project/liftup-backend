package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.user.entity.UserProfile
import kotlin.math.roundToInt

/**
 * 채팅에서 자주 묻는 수치(활동 칼로리, 유지 칼로리, 단백질, 1RM)를 서버가 계산해 프롬프트에 준다.
 *
 * 이유: 모델이 암산하면 같은 질문에 답이 매번 달라진다. 실사용 로그에서 15세 사용자가
 * "42키로 기준 600칼로리" 를 10회 넘게 반복 질문했고 답이 계속 흔들렸다(2026-09-01).
 * 프롬프트에는 "이 숫자를 그대로 쓰고 다시 계산하지 마라" 로 들어간다.
 */
object ChatComputedFacts {

    data class Activity(val label: String, val met: Double)

    /** ACSM Compendium 기준 대표 MET. 한/영/일 키워드 → 활동. */
    private val ACTIVITIES: List<Pair<List<String>, Activity>> = listOf(
        listOf("버피", "burpee", "バーピー") to Activity("burpees", 8.0),
        listOf("줄넘기", "jump rope", "skipping rope", "縄跳び") to Activity("jump rope", 11.0),
        listOf("hiit", "인터벌", "서킷", "interval", "circuit", "インターバル", "サーキット") to Activity("HIIT / circuit", 8.0),
        listOf("달리기", "러닝", "런닝", "running", "run ", "ランニング") to Activity("running (~10 km/h)", 9.8),
        listOf("조깅", "jog", "ジョギング") to Activity("jogging", 7.0),
        listOf("걷기", "산책", "걸으", "walking", "walk ", "ウォーキング", "散歩") to Activity("walking (brisk)", 3.5),
        listOf("자전거", "사이클", "cycling", "bike", "サイクリング", "自転車") to Activity("cycling (moderate)", 7.5),
        listOf("수영", "swim", "水泳") to Activity("swimming", 8.0),
        listOf("계단", "stairs", "階段") to Activity("stair climbing", 8.0),
        listOf("등산", "hiking", "hike", "登山", "ハイキング") to Activity("hiking", 6.0),
        listOf("요가", "yoga", "ヨガ") to Activity("yoga", 2.5),
        listOf("플랭크", "plank", "プランク") to Activity("plank", 3.8),
        listOf("웨이트", "근력", "헬스", "weight training", "lifting", "strength training", "筋トレ", "ウェイト") to Activity("weight training", 5.0),
    )

    private val STATED_WEIGHT = Regex("(\\d{2,3}(?:\\.\\d)?)\\s*(kg|킬로|키로|kilo|キロ)")
    private val EQUIPMENT_CTX = listOf("덤벨", "바벨", "원판", "케틀벨", "플레이트", "dumbbell", "barbell", "plate", "kettlebell", "ダンベル", "バーベル", "プレート", "세트", "set", "セット", "들", "lift", "curl", "press", "row", "squat", "bench", "데드", "스쿼트", "벤치", "컬", "프레스", "로우")
    private val BODY_WEIGHT_CTX = listOf("체중", "몸무게", "weigh", "body", "体重", "kg인데", "키로인데", "kg이고", "키로고", "kg 기준", "키로 기준", "키로인", "i am", "i'm", "나는", "내가", "저는", "제가")
    private val TARGET_KCAL = Regex("(\\d{3,4})\\s*(kcal|칼로리|칼|cal\\b|calories|キロカロリー|カロリー)")
    private val WEIGHT_X_REPS = Regex("(\\d{1,3}(?:\\.\\d)?)\\s*(kg|킬로|키로|kilo|キロ)[^\\d]{0,12}(\\d{1,2})\\s*(회|번|reps?|rep|回)")
    private val CALORIE_TOPIC = listOf("칼로리", "kcal", "칼 ", "cal", "calorie", "태우", "소모", "burn", "カロリー", "消費", "燃焼")

    data class Facts(val lines: List<String>) {
        val isEmpty get() = lines.isEmpty()
    }

    fun build(profile: UserProfile?, message: String, risk: ChatSafetyService.RiskProfile): Facts {
        val lines = mutableListOf<String>()
        val lower = message.lowercase()
        val profileWeight = profile?.bodyInfo?.weight
        // "30kg 덤벨로", "60kg×8회" 는 장비 무게지 체중이 아니다. 뒤에 장비·세트 문맥이 붙으면 제외하고,
        // 프로필 체중과 다른 값은 "체중/몸무게/weigh/体重" 문맥이 있거나 "N키로인데" 꼴일 때만 체중으로 본다.
        val statedWeight = STATED_WEIGHT.findAll(lower).map { m ->
            val after = lower.substring(m.range.last + 1).take(14)
            val before = lower.substring(0, m.range.first).takeLast(14)
            val equipmentCtx = EQUIPMENT_CTX.any { after.contains(it) || before.contains(it) } || WEIGHT_X_REPS.containsMatchIn(lower.substring(m.range.first).take(30))
            val bodyCtx = BODY_WEIGHT_CTX.any { before.contains(it) || after.contains(it) } || after.startsWith("인데") || after.startsWith("인데") || after.startsWith("기준")
            if (equipmentCtx && !bodyCtx) null else m.groupValues[1].toDoubleOrNull()
        }.filterNotNull().firstOrNull()
            ?.takeIf { it in 25.0..300.0 && (profileWeight == null || kotlin.math.abs(it - profileWeight) >= 0.5) }

        // 신체 기본값
        val tdee = NutritionTargetCalculator.calculateTDEE(profile)
        if (profile != null && profileWeight != null && tdee != null) {
            val protein = NutritionTargetCalculator.calculateProteinTarget(profile).roundToInt()
            val bmiText = risk.bmi?.let { "BMI ${"%.1f".format(it)} (${risk.bmiCategory?.name?.lowercase() ?: "unknown"})" } ?: "BMI unknown"
            lines += "Profile weight ${trim(profileWeight)} kg, $bmiText, estimated maintenance ${tdee} kcal/day, protein target ~${protein} g/day."
            val deficitAllowed = !(risk.minor || risk.underweight || risk.pregnancy || risk.eatingDisorderCues)
            if (deficitAllowed && FitnessGoal.WEIGHT_LOSS in profile.goals) {
                lines += "Weight-loss intake target (moderate deficit): ${NutritionTargetCalculator.calculateTargetCalories(profile)} kcal/day."
            } else if (!deficitAllowed) {
                lines += "No calorie-deficit target exists for this user (minor / underweight / pregnancy / disordered-eating signal). Do not invent one."
            }
        }

        // 활동 칼로리 — 칼로리 얘기가 나올 때만
        val calorieTopic = CALORIE_TOPIC.any { lower.contains(it) }
        val activities = ACTIVITIES.filter { (keys, _) -> keys.any { lower.contains(it) } }.map { it.second }.distinctBy { it.label }
        val weightsForCalc = listOfNotNull(profileWeight?.let { "profile" to it }, statedWeight?.let { "user-stated" to it })
        if (calorieTopic && weightsForCalc.isNotEmpty()) {
            val targetKcal = TARGET_KCAL.find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val acts = activities.ifEmpty { listOf(Activity("HIIT / circuit", 8.0), Activity("jogging", 7.0), Activity("walking (brisk)", 3.5)) }
            for ((source, w) in weightsForCalc) {
                for (a in acts) {
                    val perHour = (a.met * w).roundToInt()
                    var line = "${a.label} at ${trim(w)} kg ($source weight): ~${perHour} kcal/hour, ~${perHour / 2} kcal/30 min (MET ${a.met})"
                    if (targetKcal != null && perHour > 0) {
                        val minutes = (targetKcal.toDouble() / perHour * 60).roundToInt()
                        line += "; ${targetKcal} kcal would take ~${minutes} min of continuous effort" + if (minutes > 60) " (not realistic in one session)" else ""
                    }
                    lines += line
                }
            }
            if (statedWeight != null && profileWeight != null) {
                lines += "The user quoted ${trim(statedWeight)} kg but the profile says ${trim(profileWeight)} kg — state once which one you use, then stay consistent."
            }
        }

        // 1RM (Epley) — "60kg 8회" 형태
        WEIGHT_X_REPS.findAll(lower).take(3).forEach { m ->
            val w = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            val r = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (r in 1..30 && w > 0) {
                val oneRm = epley1Rm(w, r)
                lines += "Epley 1RM estimate for ${trim(w)} kg × ${r} reps: ~${trim(oneRm)} kg (5-rep ~${trim(oneRm * 0.87)} kg, 10-rep ~${trim(oneRm * 0.75)} kg)."
            }
        }

        return Facts(lines)
    }

    fun epley1Rm(weightKg: Double, reps: Int): Double = if (reps <= 1) weightKg else weightKg * (1 + reps / 30.0)

    fun kcalPerHour(met: Double, weightKg: Double): Int = (met * weightKg).roundToInt()

    fun promptBlock(facts: Facts): String {
        if (facts.isEmpty) return ""
        return buildString {
            appendLine("## COMPUTED FACTS (use these numbers verbatim — do not recompute or contradict them)")
            facts.lines.forEach { appendLine("- $it") }
        }.trim()
    }

    private fun trim(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)
}

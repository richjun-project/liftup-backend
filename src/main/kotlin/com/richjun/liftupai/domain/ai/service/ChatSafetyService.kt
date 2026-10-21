package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.ai.util.AILocalization
import com.richjun.liftupai.domain.nutrition.util.NutritionTargetCalculator
import com.richjun.liftupai.domain.user.entity.UserProfile

/**
 * AI 채팅의 안전 판정을 프롬프트 밖(서버 코드)에서 수행한다.
 *
 * 왜 코드로 하는가: 규칙을 프롬프트에만 두면 페르소나 예시나 긴 대화에 밀려 샌다.
 * 실사용 사례 — 무릎 수술 사용자에게 스쿼트 권유(2026-08-26), 15세·BMI 16.4 사용자에게
 * 미성년/저체중 언급 없이 감량 칼로리 안내(2026-09-01), "플랜 바꿔줄게" 허위 약속(2026-09-10).
 *
 * 흐름: [assess] 위험 판정 → [hardRulesBlock] 프롬프트 최상단 주입 → 응답 →
 *       [validate] 위반 검사 → 위반 시 사유를 붙여 1회 재생성 → 그래도 위반이면 [safeFallback].
 */
class ChatSafetyService {

    enum class BmiCategory { UNDERWEIGHT, NORMAL, OVERWEIGHT, OBESE }

    data class RiskProfile(
        val age: Int? = null,
        val gender: String? = null,
        val bmi: Double? = null,
        val bmiCategory: BmiCategory? = null,
        val minor: Boolean = false,
        val senior: Boolean = false,
        val underweight: Boolean = false,
        /** 프로필/설정에 저장된 부상 부위 (정규화: knee, shoulder, lower_back ...) */
        val storedInjuries: Set<String> = emptySet(),
        /** 이번 메시지 또는 최근 대화에서 언급된 부상 부위 */
        val mentionedInjuries: Set<String> = emptySet(),
        val redFlagSymptoms: Boolean = false,
        val eatingDisorderCues: Boolean = false,
        val pregnancy: Boolean = false,
        val medicationOrSupplement: Boolean = false,
        val locale: String = "en",
    ) {
        val injuries: Set<String> get() = storedInjuries + mentionedInjuries
        /** 칼로리·단백질 목표 숫자를 주지 않는 사용자(미성년·저체중·섭식장애 신호). */
        val noIntakeNumbers: Boolean get() = minor || underweight || eatingDisorderCues
        val hasAnyRisk: Boolean
            get() = minor || senior || underweight || injuries.isNotEmpty() ||
                redFlagSymptoms || eatingDisorderCues || pregnancy || medicationOrSupplement
    }

    enum class Violation {
        DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT,
        INTAKE_NUMBER_TO_VULNERABLE_USER,
        RESTRICTED_EXERCISE_FOR_INJURY,
        RED_FLAG_WITHOUT_MEDICAL_REFERRAL,
        LANGUAGE_MISMATCH,
        FALSE_PROMISE_OF_PLAN_CHANGE,
        ERROR_TEXT_EXPOSED,
        EMPTY_REPLY,
    }

    // ───────────────────────── 판정 ─────────────────────────

    fun assess(
        profile: UserProfile?,
        settingsInjuries: Collection<String>,
        message: String,
        history: List<Pair<String, String>>,
        locale: String,
    ): RiskProfile {
        val age = profile?.age
        val weight = profile?.bodyInfo?.weight
        val height = profile?.bodyInfo?.height
        val bmi = NutritionTargetCalculator.calculateBmi(weight, height)
        val category = bmi?.let { bmiCategory(it) }
        val stored = normalizeInjuries((profile?.injuries.orEmpty()) + settingsInjuries)

        // 이번 메시지가 가장 중요하고, 최근 2턴의 사용자 발화도 본다("무릎 수술했거든요" 뒤에 "루틴 짜줘").
        val recentUserText = (history.takeLast(2).map { it.first } + message).joinToString("\n")
        val mentioned = detectBodyParts(recentUserText)

        return RiskProfile(
            age = age,
            gender = profile?.gender,
            bmi = bmi,
            bmiCategory = category,
            minor = age != null && age < MINOR_AGE_LIMIT,
            senior = age != null && age >= SENIOR_AGE,
            underweight = category == BmiCategory.UNDERWEIGHT,
            storedInjuries = stored,
            mentionedInjuries = mentioned,
            redFlagSymptoms = containsAny(message, RED_FLAG_KEYWORDS),
            eatingDisorderCues = containsAny(recentUserText, EATING_DISORDER_KEYWORDS) || extremeCalorieIntake(recentUserText),
            pregnancy = containsAny(recentUserText, PREGNANCY_KEYWORDS),
            medicationOrSupplement = containsAny(message, MEDICATION_KEYWORDS),
            locale = AILocalization.normalizeLocale(locale),
        )
    }

    fun bmiCategory(bmi: Double): BmiCategory = when {
        bmi < 18.5 -> BmiCategory.UNDERWEIGHT
        bmi < 25.0 -> BmiCategory.NORMAL
        bmi < 30.0 -> BmiCategory.OVERWEIGHT
        else -> BmiCategory.OBESE
    }

    /** "knee:moderate", "무릎", "Lower Back" 등을 knee / lower_back 같은 키로 정규화한다. */
    fun normalizeInjuries(raw: Collection<String>): Set<String> =
        raw.asSequence()
            .map { it.substringBefore(':').trim().lowercase() }
            .filter { it.isNotEmpty() && it != "none" }
            .mapNotNull { token -> BODY_PART_ALIASES.entries.firstOrNull { (_, aliases) -> aliases.any { token == it || token.contains(it) } }?.key ?: token.takeIf { it.all { c -> c.isLetter() || c == '_' } } }
            .toSet()

    /** 자유 텍스트에서 부상·통증과 함께 언급된 부위를 찾는다. 부위만 언급(예: "무릎 운동 추천")은 부상으로 보지 않는다. */
    fun detectBodyParts(text: String): Set<String> {
        val lower = text.lowercase()
        if (!containsAny(lower, INJURY_CONTEXT_KEYWORDS)) return emptySet()
        return BODY_PART_ALIASES.filterValues { aliases -> aliases.any { lower.contains(it) } }.keys
    }

    // ───────────────────────── 프롬프트 블록 ─────────────────────────

    /** 프롬프트 최상단에 들어가는, 이 사용자에게만 적용되는 절대 규칙. 위험이 없으면 빈 문자열. */
    fun hardRulesBlock(risk: RiskProfile): String {
        val rules = mutableListOf<String>()
        if (risk.redFlagSymptoms) rules += """
            - EMERGENCY SIGNAL: the user describes chest pain, fainting, breathing difficulty, severe headache or similar.
              FIRST sentence: tell them to stop exercising now and contact a doctor / emergency services (119 in Korea, 911 in the US).
              Do not give a workout or diet plan in this reply. Keep persona warmth, drop persona jokes.
        """.trimIndent()
        if (risk.minor) rules += """
            - The user is a MINOR (age ${risk.age}). Never give a calorie deficit, weight-loss calorie target, fasting plan,
              "cut", or body-fat percentage goal. Do not present any "eat X kcal to lose weight" number.
              Explain that growth needs full nutrition, redirect to strength/skill/enjoyment goals, and suggest involving a parent,
              school nurse, or pediatrician for any weight change. Calories BURNED by activity may be stated as information only.
              Do not recommend supplements (creatine, protein powder amounts, pre-workout, fat burners) to a minor — food first,
              and any supplement question goes to a parent and a doctor.
        """.trimIndent()
        if (risk.underweight) rules += """
            - The user is UNDERWEIGHT (BMI ${"%.1f".format(risk.bmi ?: 0.0)}). Do not help them lose weight or restrict calories.
              Say plainly that weight loss is not appropriate at this BMI and why, then help with strength, energy, and eating enough.
              If they insist, hold the line kindly and recommend a doctor or dietitian.
        """.trimIndent()
        if (risk.eatingDisorderCues) rules += """
            - The conversation contains signs of disordered eating (starving, purging, extreme calorie limits, weight obsession).
              Do not give any restriction advice. Respond with care, name that this pattern can harm them, and point to
              a doctor, counselor, or a helpline. Stay warm; no persona sarcasm on this topic.
        """.trimIndent()
        if (risk.injuries.isNotEmpty()) rules += """
            - INJURED / RESTRICTED BODY PARTS: ${risk.injuries.joinToString(", ")}.
              Never list, suggest, or "try lightly" any exercise that loads these areas
              (${risk.injuries.joinToString("; ") { part -> "$part → avoid ${restrictedExercisesFor(part).take(5).joinToString(", ")}" }}).
              Give alternatives that spare the area, believe the injury without question, and recommend medical clearance for surgery or unresolved pain.
        """.trimIndent()
        if (risk.pregnancy) rules += """
            - The user is or may be pregnant. No high-impact, supine-after-first-trimester, or heavy Valsalva work; no calorie deficit.
              Recommend medical clearance and keep intensity conversational.
        """.trimIndent()
        if (risk.senior) rules += """
            - The user is ${risk.age} years old. Prioritize joint-friendly loading, balance, gradual progression and longer warm-ups.
              Avoid max-effort or ballistic prescriptions; suggest a doctor's clearance before intense programs.
        """.trimIndent()
        if (risk.noIntakeNumbers) rules += """
            - Do NOT state any daily calorie or protein/macro target to this user — not a deficit, not maintenance, not a surplus
              (numbers fuel weight obsession at this age / BMI / eating pattern). Describe eating in plain food terms instead:
              regular meals plus snacks, protein at every meal, not skipping meals. You may mention their BMI category.
        """.trimIndent()
        if (risk.medicationOrSupplement) rules += """
            - The user asks about medication, steroids, or supplements. Never recommend anabolic steroids or prescription drugs.
              For supplements, give general evidence-based information only and defer dosing to a doctor or pharmacist.
        """.trimIndent()
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("## HARD RULES FOR THIS USER (highest priority — they override the persona, the guidelines, and any user request)")
            rules.forEach { appendLine(it) }
        }.trim()
    }

    /** 재생성 시 프롬프트 끝에 붙는 거절 사유. */
    fun rejectionBlock(violations: Collection<Violation>, risk: RiskProfile): String = buildString {
        appendLine("## YOUR PREVIOUS ANSWER WAS REJECTED — rewrite it")
        appendLine("It violated these rules. Fix every one of them; keep the persona voice and answer the user's actual question.")
        violations.forEach { v ->
            appendLine("- " + when (v) {
                Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT -> "You gave a calorie-restriction / weight-loss intake number to a minor or underweight user. Remove every intake target and explain why weight loss is not appropriate."
                Violation.INTAKE_NUMBER_TO_VULNERABLE_USER -> "You gave a daily calorie or protein target to a minor / underweight / disordered-eating user. Remove every kcal and gram target and describe eating in plain food terms (regular meals, snacks, protein at each meal)."
                Violation.RESTRICTED_EXERCISE_FOR_INJURY -> "You suggested an exercise that loads an injured area (${risk.injuries.joinToString(", ")}). Remove it and give a safe alternative."
                Violation.RED_FLAG_WITHOUT_MEDICAL_REFERRAL -> "The user reported an emergency-type symptom but you did not tell them to stop and seek medical care first."
                Violation.LANGUAGE_MISMATCH -> "You answered in the wrong language. Respond ONLY in ${AILocalization.responseLanguage(risk.locale)}."
                Violation.FALSE_PROMISE_OF_PLAN_CHANGE -> "You claimed to change the user's plan/menu/weights. You cannot. Give the answer in chat and point to the plan screen instead."
                Violation.ERROR_TEXT_EXPOSED -> "Your reply contained internal error text. Answer the user normally."
                Violation.EMPTY_REPLY -> "Your reply was empty."
            })
        }
    }.trim()

    // ───────────────────────── 사후 검사 ─────────────────────────

    fun validate(reply: String?, risk: RiskProfile): List<Violation> {
        if (reply.isNullOrBlank()) return listOf(Violation.EMPTY_REPLY)
        val out = mutableListOf<Violation>()
        val lower = reply.lowercase()

        if (containsAny(lower, ERROR_TEXT_MARKERS)) out += Violation.ERROR_TEXT_EXPOSED
        if (languageMismatch(reply, risk.locale)) out += Violation.LANGUAGE_MISMATCH
        if (FALSE_PROMISE_PATTERNS.any { it.containsMatchIn(reply) }) out += Violation.FALSE_PROMISE_OF_PLAN_CHANGE
        if ((risk.minor || risk.underweight) && containsDeficitIntakeAdvice(reply)) out += Violation.DEFICIT_ADVICE_TO_MINOR_OR_UNDERWEIGHT
        else if (risk.noIntakeNumbers && containsIntakeTarget(reply)) out += Violation.INTAKE_NUMBER_TO_VULNERABLE_USER
        if (risk.injuries.isNotEmpty() && recommendsRestrictedExercise(reply, risk.injuries)) out += Violation.RESTRICTED_EXERCISE_FOR_INJURY
        if (risk.redFlagSymptoms && !containsAny(lower, MEDICAL_REFERRAL_KEYWORDS)) out += Violation.RED_FLAG_WITHOUT_MEDICAL_REFERRAL
        return out
    }

    /**
     * 최종 안전 폴백(로케일별). 재생성까지 실패했을 때만 쓴다.
     * 섭식장애/미성년/저체중 폴백에는 실제 값이 있을 때만 프로필 기반 한 문장(BMI·나이)을 덧붙인다 —
     * 일반 문구만 나가면 "왜 안 되는지"가 빠져 사용자가 같은 질문을 반복한다(e2e uw_02).
     */
    fun safeFallback(risk: RiskProfile, nickname: String): String {
        val key = when {
            risk.redFlagSymptoms -> "safety.fallback.red_flag"
            risk.eatingDisorderCues -> "safety.fallback.eating"
            risk.minor || risk.underweight -> "safety.fallback.minor_underweight"
            risk.injuries.isNotEmpty() -> "safety.fallback.injury"
            else -> "safety.fallback.generic"
        }
        if (key == "safety.fallback.injury") {
            // "그 부상이 있으면…" 처럼 어느 부위인지 말하지 않으면 사용자는 무엇이 문제인지 모른다(골든셋 2026-09-27, 심판 2.5점)
            val parts = risk.injuries.toList().sorted()
            val partNames = parts.joinToString(AILocalization.message("safety.part.join", risk.locale)) { part ->
                AILocalization.maybeMessage("safety.part.$part", risk.locale) ?: AILocalization.message("safety.part.other", risk.locale)
            }
            val alt = parts.singleOrNull()?.let { AILocalization.maybeMessage("safety.alt.$it", risk.locale) }
                ?: AILocalization.message("safety.alt.generic", risk.locale)
            return AILocalization.message(key, risk.locale, nickname, partNames, alt)
        }
        val base = AILocalization.message(key, risk.locale, nickname)
        if (key != "safety.fallback.eating" && key != "safety.fallback.minor_underweight") return base
        return listOfNotNull(base, profileContextSentence(risk)).joinToString(" ")
    }

    /** "지금 BMI 17.3은 저체중이라…" / "15세는 성장기라…" — 값이 있을 때만. */
    internal fun profileContextSentence(risk: RiskProfile): String? {
        val bmi = risk.bmi?.takeIf { risk.underweight }?.let { "%.1f".format(it) }
        val age = risk.age?.takeIf { risk.minor }
        return when {
            bmi != null && age != null -> AILocalization.message("safety.fallback.context.minor_underweight", risk.locale, age, bmi)
            bmi != null -> AILocalization.message("safety.fallback.context.underweight", risk.locale, bmi)
            age != null -> AILocalization.message("safety.fallback.context.minor", risk.locale, age)
            else -> null
        }
    }

    // ───────────────────────── 내부 규칙 ─────────────────────────

    /**
     * "하루 1200kcal로 줄이세요", "500kcal 적자", "cut to 1500 calories" 처럼
     * 섭취 제한·적자 수치를 제시하는 문장. "600kcal 태우려면"(소모)은 해당하지 않는다.
     */
    internal fun containsDeficitIntakeAdvice(reply: String): Boolean {
        val sentences = reply.split(Regex("(?<=[.!?。！？\\n])"))
        return sentences.any { s ->
            val lower = s.lowercase()
            val hasKcal = KCAL_NUMBER.containsMatchIn(lower)
            if (!hasKcal) return@any false
            // 소모 칼로리 정보("버피 1시간 ~340kcal 태워요")는 섭취 조언이 아니다
            val burnOnly = containsAny(lower, BURN_KEYWORDS) && !containsAny(lower, INTAKE_KEYWORDS)
            if (burnOnly) return@any false
            // "유지 칼로리 1800kcal 채워 먹어라", "최소 1500kcal 이상" — 늘리라는 조언은 위반이 아니다
            val increase = containsAny(lower, INCREASE_KEYWORDS)
            val explicitDeficit = containsAny(lower, EXPLICIT_DEFICIT_KEYWORDS)
            if (increase && !explicitDeficit) return@any false
            // "1200kcal 이하로 먹지 마세요" — 제한을 부정하는 문장
            val floorPhrase = containsAny(lower, FLOOR_KEYWORDS)
            if (floorPhrase && containsAny(lower, NEGATION_KEYWORDS) && !explicitDeficit) return@any false
            containsAny(lower, DEFICIT_KEYWORDS)
        }
    }

    /**
     * 하루 섭취 칼로리·단백질 목표 숫자("하루 1963kcal", "단백질 94g", "2,819 kcal target")가 있는가.
     * 활동 소모 칼로리("1시간에 336kcal 소모")는 섭취 목표가 아니다.
     */
    internal fun containsIntakeTarget(reply: String): Boolean {
        val sentences = reply.split(Regex("(?<=[.!?。！？\\n])"))
        return sentences.any { s ->
            val lower = s.lowercase()
            val kcal = KCAL_NUMBER.containsMatchIn(lower)
            val protein = PROTEIN_GRAMS.containsMatchIn(lower)
            if (!kcal && !protein) return@any false
            !(containsAny(lower, BURN_KEYWORDS) && !containsAny(lower, INTAKE_KEYWORDS) && !protein)
        }
    }

    /** 부상 부위에 부하를 주는 운동명이 "권유 문맥"(부정어 없이)으로 등장하는가. */
    internal fun recommendsRestrictedExercise(reply: String, injuries: Set<String>): Boolean {
        val names = injuries.flatMap { restrictedExercisesFor(it) }.map { it.lowercase() }.toSet()
        if (names.isEmpty()) return false
        val sentences = reply.split(Regex("(?<=[.!?。！？\\n])"))
        return sentences.any { s ->
            val lower = s.lowercase()
            if (names.none { lower.contains(it) }) return@any false
            // "스쿼트 대신 레그 익스텐션을 하세요" — '대신/instead' 앞은 배제, 뒤는 권유
            val pivot = INSTEAD_KEYWORDS.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (pivot != null) {
                val recommendedPart = lower.substring(pivot)
                return@any names.any { recommendedPart.contains(it) } && !containsAny(recommendedPart.substringAfter(INSTEAD_KEYWORDS.first { recommendedPart.startsWith(it) || recommendedPart.contains(it) }), STRONG_NEGATION)
            }
            // "재활이 끝난 뒤 스쿼트로 돌아가려면 의사 승인이 필요해요" — 미래·조건부 복귀 설명은 권유가 아니다
            !containsAny(lower, NEGATION_KEYWORDS) && !containsAny(lower, DEFERRED_RETURN_KEYWORDS)
        }
    }

    internal fun languageMismatch(reply: String, locale: String): Boolean {
        val lang = AILocalization.toLocale(locale).language
        val hangul = reply.count { it in '가'..'힣' }
        val kana = reply.count { it in '぀'..'ヿ' }
        val letters = reply.count { it.isLetter() }
        if (letters == 0) return false
        // 인용부호 안(운동명·음식명 인용)은 언어 판정에서 제외한다
        val body = reply.replace(Regex("[\"'「」『』“”‘’\\[\\]()][^\"'「」『』“”‘’\\[\\]()]{1,40}[\"'「」『』“”‘’\\[\\]()]"), " ")
        val h = body.count { it in '가'..'힣' }.toDouble()
        val k = body.count { it in '぀'..'ヿ' }.toDouble()
        val cjk = body.count { it in '一'..'鿿' }.toDouble()
        val latin = body.count { it in 'a'..'z' || it in 'A'..'Z' }.toDouble()
        val total = (h + k + cjk + latin).takeIf { it > 0 } ?: return false
        return when (lang) {
            "ko" -> h / total < 0.3 && total >= 8            // 한영 혼용 페르소나("오늘도 파이팅! Let's go bro!")는 허용, 거의 전부 외국어면 불일치
            "ja" -> (k + cjk) / total < 0.5 || h / total > 0.2
            "en" -> latin / total < 0.6 || (h + k) / total > 0.2
            else -> false
        }
    }

    fun restrictedExercisesFor(bodyPart: String): List<String> = RESTRICTED_EXERCISES[bodyPart].orEmpty()

    private fun extremeCalorieIntake(text: String): Boolean {
        val lower = text.lowercase()
        if (!containsAny(lower, INTAKE_KEYWORDS)) return false
        // "300kcal 간식" 은 신호가 아니다 — 하루 총량 문맥("하루", "per day", "一日")일 때만
        if (!containsAny(lower, listOf("하루", "일일", "per day", "a day", "daily", "total", "一日", "1日", "毎日"))) return false
        return KCAL_NUMBER.findAll(lower).any { m ->
            val n = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@any false
            n in 1..799
        }
    }

    private fun containsAny(text: String, keywords: Collection<String>): Boolean {
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    companion object {
        const val MINOR_AGE_LIMIT = 19
        const val SENIOR_AGE = 65

        private val PROTEIN_GRAMS = Regex("(단백질|protein|タンパク質|たんぱく質)[^.!?。\\n]{0,12}?\\d{2,3}\\s*(g|그램|グラム)(?![a-z])|\\d{2,3}\\s*(g|그램|グラム)[^.!?。\\n]{0,6}(의\\s*)?(단백질|of protein|protein|タンパク質)", RegexOption.IGNORE_CASE)
        private val KCAL_NUMBER = Regex("(\\d{1,2},?\\d{3}|\\d{3,4})\\s*(kcal|칼로리|칼|cal\\b|calories|キロカロリー|カロリー)")

        val BODY_PART_ALIASES: Map<String, List<String>> = linkedMapOf(
            "knee" to listOf("knee", "무릎", "膝", "ひざ"),
            "lower_back" to listOf("lower back", "lower_back", "lowerback", "허리", "요추", "腰", "back pain", "디스크"),
            "shoulder" to listOf("shoulder", "어깨", "肩", "회전근", "rotator"),
            "elbow" to listOf("elbow", "팔꿈치", "肘", "ひじ"),
            "wrist" to listOf("wrist", "손목", "手首"),
            "ankle" to listOf("ankle", "발목", "足首"),
            "hip" to listOf(" hip", "고관절", "股関節", "엉덩관절"),
            // "首"만 쓰면 手首(손목)에도 걸린다 — 목은 통증 문맥이 붙은 형태만 본다.
            "neck" to listOf("neck", "목 디스크", "경추", "首が痛", "首の痛", "首を痛", "頸椎", "목이 아", "목 통증", "목을 다"),
        )

        /** 부위별로 프롬프트 경고·사후 검사에 쓰는 대표 운동명(ko/en/ja). */
        val RESTRICTED_EXERCISES: Map<String, List<String>> = mapOf(
            "knee" to listOf("스쿼트", "런지", "레그 프레스", "레그프레스", "레그 익스텐션", "점프", "버피", "박스 점프", "squat", "lunge", "leg press", "leg extension", "jump", "burpee", "スクワット", "ランジ", "レッグプレス", "ジャンプ", "バーピー"),
            "lower_back" to listOf("데드리프트", "굿모닝", "바벨 로우", "벤트오버", "백 익스텐션", "deadlift", "good morning", "bent-over row", "bent over row", "back extension", "デッドリフト", "ベントオーバー"),
            "shoulder" to listOf("오버헤드 프레스", "숄더 프레스", "밀리터리 프레스", "업라이트 로우", "사이드 레터럴", "래터럴 레이즈", "overhead press", "shoulder press", "military press", "upright row", "lateral raise", "ショルダープレス", "オーバーヘッドプレス"),
            "elbow" to listOf("스컬크러셔", "스컬 크러셔", "트라이셉스 익스텐션", "클로즈 그립", "skull crusher", "triceps extension", "close-grip", "close grip", "スカルクラッシャー"),
            "wrist" to listOf("푸시업", "푸쉬업", "팔굽혀펴기", "리스트 컬", "프론트 스쿼트", "push-up", "push up", "pushup", "wrist curl", "front squat", "腕立て", "プッシュアップ"),
            "ankle" to listOf("점프", "박스 점프", "줄넘기", "런닝", "러닝", "달리기", "카프 레이즈", "jump", "jump rope", "running", "sprint", "calf raise", "ジャンプ", "縄跳び", "ランニング"),
            "hip" to listOf("스쿼트", "데드리프트", "런지", "힙 쓰러스트", "squat", "deadlift", "lunge", "hip thrust", "スクワット", "デッドリフト"),
            "neck" to listOf("오버헤드 프레스", "슈러그", "넥 브릿지", "overhead press", "shrug", "neck bridge", "シュラッグ"),
        )

        private val INJURY_CONTEXT_KEYWORDS = listOf(
            "부상", "다쳤", "다쳐", "수술", "아파", "아프", "통증", "삐", "염증", "인대", "디스크", "재활",
            "injur", "hurt", "surgery", "pain", "sore", "sprain", "tear", "torn", "rehab", "operation",
            "怪我", "ケガ", "けが", "手術", "痛", "捻挫", "靭帯", "リハビリ",
        )
        private val RED_FLAG_KEYWORDS = listOf(
            "가슴 통증", "가슴이 아", "가슴이 답답", "흉통", "실신", "기절", "쓰러졌", "숨이 안", "숨쉬기 힘", "호흡곤란", "호흡 곤란",
            "심한 두통", "머리가 터질", "피를 토", "각혈", "심장이 너무", "부정맥", "의식을 잃",
            // 고중량 중 갑작스러운 극심한 두통(벼락 두통) — "맥스 드는데 머리가 깨질듯 아파요"가 응급으로 안 잡혔다(골든 g_emg_05, 2026-10-21)
            "머리가 깨질", "머리 깨질", "벼락 두통", "벼락두통", "쓰러질 것 같", "쓰러질것 같",
            "chest pain", "chest tightness", "faint", "passed out", "blacked out", "can't breathe", "cannot breathe",
            "shortness of breath", "severe headache", "coughing blood", "heart racing", "irregular heartbeat", "lost consciousness",
            "thunderclap headache", "worst headache", "splitting headache",
            // "My heart is racing and I feel like I'm going to pass out" 가 "heart racing"·"passed out" 에 안 걸렸다(골든 g_emg_08)
            "pass out", "passing out", "heart is racing", "heart is pounding", "racing heart",
            "胸が痛", "胸の痛み", "胸が苦し", "失神", "気を失", "倒れ", "息が苦し", "呼吸困難", "激しい頭痛", "血を吐",
            "頭が割れ", "割れるような頭痛", "雷鳴頭痛",
        )
        private val EATING_DISORDER_KEYWORDS = listOf(
            "굶", "안 먹", "안먹", "토했", "토해", "구토", "먹토", "폭식", "절식", "단식", "물만 먹", "하루 한 끼", "살찌는 게 무서",
            // 먹는 것에 대한 두려움 — "살찔까봐 무서워서 밥을 못 먹겠어요" 에 감량 칼로리를 준 사고(골든셋 2026-09-27, 심판 2.0)
            "살찔까봐", "살찔까 봐", "먹는 게 무서", "먹는게 무서", "먹기가 무서", "밥을 못 먹", "밥을 못먹", "먹으면 죄책감", "먹고 나서 죄책감",
            "starv", "purge", "purging", "vomit", "throw up", "binge", "fasting for days", "not eating", "skip all meals", "afraid of gaining",
            "afraid to eat", "scared to eat", "scared of gaining", "can't eat because", "guilty after eating",
            "断食", "絶食", "吐い", "吐く", "過食", "食べない", "食べてない", "太るのが怖", "食べるのが怖", "太りそうで怖", "食べると罪悪感",
        )
        private val PREGNANCY_KEYWORDS = listOf("임신", "임산부", "출산", "pregnan", "postpartum", "trimester", "妊娠", "妊婦", "産後")
        private val MEDICATION_KEYWORDS = listOf(
            "스테로이드", "아나볼릭", "약 먹", "약을 먹", "복용", "처방", "보조제", "부스터", "크레아틴", "다이어트약", "식욕억제",
            "steroid", "anabolic", "sarm", "medication", "prescription", "supplement", "creatine", "pre-workout", "fat burner", "appetite suppress",
            "ステロイド", "薬を", "サプリ", "クレアチン", "処方",
        )

        private val DEFICIT_KEYWORDS = listOf(
            "적자", "결핍", "감량", "감소", "줄이", "줄여", "제한", "컷", "빼려면", "빼기 위해", "다이어트 식단", "섭취량을", "이하로", "까지 줄",
            "deficit", "cut ", "cutting", "reduce", "restrict", "lose weight", "weight loss", "to lose", "below", "limit your",
            "減ら", "制限", "減量", "欠損", "カット", "痩せる", "以下に",
        )
        /** 섭취를 늘리라는 조언 — 제한이 아니다 */
        private val INCREASE_KEYWORDS = listOf(
            "채워", "채우", "이상", "늘리", "늘려", "더 먹", "충분히", "부족하", "최소", "유지 칼로리", "유지칼로리", "맞춰 먹", "맞춰서 먹",
            "at least", "increase", "more than", "enough", "fill", "maintenance", "minimum", "no less than", "eat up to",
            "以上", "増や", "十分", "維持カロリー", "最低", "しっかり食べ",
        )
        /** 늘리라는 말과 함께 있어도 명백한 감량 지시 */
        private val EXPLICIT_DEFICIT_KEYWORDS = listOf("적자", "결핍", "감량 목표", "deficit", "cut to", "cutting to", "減量目標", "欠損")
        /** "~이하로" 류 하한선 표현 (부정어와 함께면 제한이 아님) */
        private val FLOOR_KEYWORDS = listOf("이하로", "아래로", "below", "under ", "以下", "未満")
        private val BURN_KEYWORDS = listOf("태우", "소모", "소비", "burn", "expend", "消費", "燃焼")
        private val INTAKE_KEYWORDS = listOf("섭취", "먹", "식사", "식단", "eat", "intake", "consume", "diet", "meal", "摂取", "食べ", "食事", "食")

        private val DEFERRED_RETURN_KEYWORDS = listOf(
            "돌아가", "복귀", "나중에", "이후에", "끝난 뒤", "끝나면", "승인", "허락", "허가", "회복된 후", "회복되면",
            "return to", "once cleared", "after rehab", "when cleared", "doctor's approval", "later on",
            "復帰", "許可", "承認", "回復したら", "治ったら",
        )
        private val INSTEAD_KEYWORDS = listOf("대신", "instead of", "instead", "rather than", "代わりに", "代わり")
        private val STRONG_NEGATION = listOf("하지 마", "하지마", "금지", "안 돼", "안돼", "don't", "do not", "never", "禁止", "やめ")
        private val NEGATION_KEYWORDS = listOf(
            "하지 마", "하지마", "피하", "제외", "금지", "빼고", "대신", "안 돼", "안돼", "말고", "말아", "않", "삼가", "쉬", "피해",
            "avoid", "skip", "don't", "do not", "not ", "instead", "never", "no ", "without", "exclude", "rather than", "stay away",
            "避け", "禁止", "やめ", "代わり", "しない", "なし", "除外", "控え",
        )
        private val MEDICAL_REFERRAL_KEYWORDS = listOf(
            "병원", "의사", "응급", "119", "진료", "의료", "구급", "전문의",
            "doctor", "hospital", "emergency", "911", "medical", "physician", "urgent care", "er ",
            "病院", "医師", "救急", "医療", "受診",
        )
        private val ERROR_TEXT_MARKERS = listOf(
            "ai 분석에 실패", "ai 응답을 받을 수", "gemini", "api key", "exception", "stack trace", "http 4", "http 5", "エラーが発生",
            "analysis failed", "response unavailable", "internal server error",
        )
        private val FALSE_PROMISE_PATTERNS = listOf(
            Regex("(플랜|메뉴|루틴|프로그램|무게|중량|설정)[^.!?\\n]{0,15}(바꿨|바꿔\\s?드렸|바꿔\\s?놓|바꿔\\s?줄게|바꿔\\s?드릴게|바꿀게|변경했|변경해\\s?드렸|변경해\\s?줄게|변경해\\s?드릴게|변경할게|수정했|수정해\\s?드렸|교체했|교체해\\s?드렸|적용했|적용해\\s?드렸|반영했|반영해\\s?드렸|업데이트했)"),
            Regex("(?i)\\bI('ve| have|'ll| will| just)? ?(changed|updated|swapped|switched|modified|adjusted|replaced|edited) (your|the) (plan|routine|menu|program|workout|weights?|settings)"),
            Regex("(?i)\\b(your|the) (plan|routine|menu|program|weights?) (has|have) been (changed|updated|swapped|switched|modified|adjusted)"),
            Regex("(プラン|メニュー|ルーティン|プログラム|重量|設定)[^。！？\\n]{0,12}(変更しました|変えました|変更します|変えます|変えておき|切り替えます|切り替えました|更新しました|反映しました|修正しました)"),
        )
    }
}
